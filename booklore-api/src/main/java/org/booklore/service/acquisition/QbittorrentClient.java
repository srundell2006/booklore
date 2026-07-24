package org.booklore.service.acquisition;

import lombok.extern.slf4j.Slf4j;
import org.booklore.model.dto.settings.BookAcquisitionSettings;
import org.springframework.stereotype.Service;

import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@lombok.RequiredArgsConstructor
public class QbittorrentClient {

    private final tools.jackson.databind.ObjectMapper objectMapper;

    /**
     * Adds a torrent by magnet/URL. Returns true on success.
     */
    public boolean addTorrent(BookAcquisitionSettings settings, String torrentUrl) {
        HttpClient client = newClient();
        if (!ensureAccess(client, settings)) {
            return false;
        }
        try {
            String form = "urls=" + URLEncoder.encode(torrentUrl, StandardCharsets.UTF_8)
                    + "&category=" + URLEncoder.encode(nullSafe(settings.getQbittorrentCategory()), StandardCharsets.UTF_8);
            HttpResponse<String> response = client.send(HttpRequest.newBuilder()
                            .uri(URI.create(trim(settings.getQbittorrentUrl()) + "/api/v2/torrents/add"))
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .header("Referer", trim(settings.getQbittorrentUrl()))
                            .timeout(Duration.ofSeconds(30))
                            .POST(HttpRequest.BodyPublishers.ofString(form))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            boolean ok = isSuccess(response.statusCode()) && !"Fails.".equalsIgnoreCase(response.body().trim());
            if (!ok) {
                log.warn("qBittorrent add failed. HTTP {}: {}", response.statusCode(), truncate(response.body()));
            }
            return ok;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            log.error("qBittorrent add error: {}", e.getMessage());
            return false;
        }
    }

    /** Reads active torrents and normalizes them. */
    public List<DownloadQueueItem> getQueue(BookAcquisitionSettings settings) {
        List<DownloadQueueItem> items = new ArrayList<>();
        HttpClient client = newClient();
        if (!ensureAccess(client, settings)) {
            return items;
        }
        try {
            HttpResponse<String> response = client.send(HttpRequest.newBuilder()
                            .uri(URI.create(trim(settings.getQbittorrentUrl()) + "/api/v2/torrents/info"))
                            .header("Referer", trim(settings.getQbittorrentUrl()))
                            .timeout(Duration.ofSeconds(20))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (!isSuccess(response.statusCode())) {
                log.warn("qBittorrent queue read failed. HTTP {}", response.statusCode());
                return items;
            }
            for (tools.jackson.databind.JsonNode torrent : objectMapper.readTree(response.body())) {
                double ratio = torrent.path("progress").asDouble(0);
                long eta = torrent.path("eta").asLong(0);
                items.add(DownloadQueueItem.builder()
                        .id(torrent.path("hash").asText(null))
                        .name(torrent.path("name").asText(""))
                        .client("QBITTORRENT")
                        .state(torrent.path("state").asText(""))
                        .progress(Math.round(ratio * 1000) / 10.0)
                        .sizeBytes(torrent.path("size").asLong(0))
                        .remainingBytes(torrent.path("amount_left").asLong(0))
                        .downloadSpeed(torrent.path("dlspeed").asLong(0))
                        // qBittorrent uses 8640000 as its "unknown/infinite" sentinel
                        .etaSeconds(eta <= 0 || eta >= 8640000 ? null : eta)
                        .category(torrent.path("category").asText(null))
                        .completed(ratio >= 1.0)
                        .build());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("qBittorrent queue read error: {}", e.getMessage());
        }
        return items;
    }

    /** Deletes a torrent, optionally removing downloaded files. */
    public boolean deleteTorrent(BookAcquisitionSettings settings, String hash, boolean deleteFiles) {
        HttpClient client = newClient();
        if (!ensureAccess(client, settings)) {
            return false;
        }
        try {
            String form = "hashes=" + URLEncoder.encode(nullSafe(hash), StandardCharsets.UTF_8)
                    + "&deleteFiles=" + deleteFiles;
            HttpResponse<String> response = client.send(HttpRequest.newBuilder()
                            .uri(URI.create(trim(settings.getQbittorrentUrl()) + "/api/v2/torrents/delete"))
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .header("Referer", trim(settings.getQbittorrentUrl()))
                            .timeout(Duration.ofSeconds(20))
                            .POST(HttpRequest.BodyPublishers.ofString(form))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            boolean ok = isSuccess(response.statusCode());
            if (!ok) {
                log.warn("qBittorrent delete failed. HTTP {}: {}", response.statusCode(), truncate(response.body()));
            }
            return ok;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            log.warn("qBittorrent delete error: {}", e.getMessage());
            return false;
        }
    }

    public boolean testConnection(BookAcquisitionSettings settings) {
        return ensureAccess(newClient(), settings);
    }

    /**
     * Establishes usable API access.
     * <p>
     * qBittorrent can be configured with an auth-subnet whitelist
     * (WebUI\AuthSubnetWhitelistEnabled), in which case /auth/login returns
     * HTTP 204 with an empty body instead of "Ok." and no cookie is issued —
     * yet every API endpoint is fully usable. So rather than trusting the login
     * response alone, we attempt login best-effort and then verify real access
     * by calling an authenticated endpoint.
     */
    private boolean ensureAccess(HttpClient client, BookAcquisitionSettings settings) {
        String baseUrl = trim(settings.getQbittorrentUrl());
        if (baseUrl.isEmpty()) {
            log.warn("qBittorrent URL is not configured");
            return false;
        }
        attemptLogin(client, settings);
        return verifyApiAccess(client, baseUrl);
    }

    private void attemptLogin(HttpClient client, BookAcquisitionSettings settings) {
        String username = nullSafe(settings.getQbittorrentUsername());
        String password = nullSafe(settings.getQbittorrentPassword());
        if (username.isEmpty() && password.isEmpty()) {
            log.debug("qBittorrent credentials empty; relying on subnet whitelist if configured");
            return;
        }
        try {
            String form = "username=" + URLEncoder.encode(username, StandardCharsets.UTF_8)
                    + "&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8);
            HttpResponse<String> response = client.send(HttpRequest.newBuilder()
                            .uri(URI.create(trim(settings.getQbittorrentUrl()) + "/api/v2/auth/login"))
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .header("Referer", trim(settings.getQbittorrentUrl()))
                            .timeout(Duration.ofSeconds(15))
                            .POST(HttpRequest.BodyPublishers.ofString(form))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            String body = response.body() == null ? "" : response.body().trim();
            if ("Ok.".equalsIgnoreCase(body)) {
                log.debug("qBittorrent login succeeded");
            } else if (response.statusCode() == 204 || body.isEmpty()) {
                log.debug("qBittorrent login returned HTTP {} with empty body — auth likely bypassed via subnet whitelist", response.statusCode());
            } else {
                log.warn("qBittorrent login rejected. HTTP {}: {}", response.statusCode(), truncate(body));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("qBittorrent login error: {}", e.getMessage());
        }
    }

    /** Calls an authenticated endpoint to confirm the session (or bypass) actually works. */
    private boolean verifyApiAccess(HttpClient client, String baseUrl) {
        try {
            HttpResponse<String> response = client.send(HttpRequest.newBuilder()
                            .uri(URI.create(baseUrl + "/api/v2/app/version"))
                            .header("Referer", baseUrl)
                            .timeout(Duration.ofSeconds(15))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (isSuccess(response.statusCode())) {
                log.debug("qBittorrent API reachable, version {}", response.body().trim());
                return true;
            }
            log.warn("qBittorrent API access check failed. HTTP {}: {}", response.statusCode(), truncate(response.body()));
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            log.warn("qBittorrent API access check error: {}", e.getMessage());
            return false;
        }
    }

    private boolean isSuccess(int statusCode) {
        return statusCode >= 200 && statusCode < 300;
    }

    private HttpClient newClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .cookieHandler(new CookieManager())
                .build();
    }

    private String trim(String url) {
        if (url == null) return "";
        String trimmed = url.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    private String nullSafe(String value) {
        return value == null ? "" : value.trim();
    }

    private String truncate(String s) {
        if (s == null) return "";
        return s.length() > 200 ? s.substring(0, 200) : s;
    }
}
