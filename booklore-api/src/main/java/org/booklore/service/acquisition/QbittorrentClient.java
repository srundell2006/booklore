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

@Slf4j
@Service
public class QbittorrentClient {

    /**
     * Adds a torrent by magnet/URL. Returns true on success.
     */
    public boolean addTorrent(BookAcquisitionSettings settings, String torrentUrl) {
        HttpClient client = newClient();
        if (!login(client, settings)) {
            return false;
        }
        try {
            String form = "urls=" + URLEncoder.encode(torrentUrl, StandardCharsets.UTF_8)
                    + "&category=" + URLEncoder.encode(nullSafe(settings.getQbittorrentCategory()), StandardCharsets.UTF_8);
            HttpResponse<String> response = client.send(HttpRequest.newBuilder()
                            .uri(URI.create(trim(settings.getQbittorrentUrl()) + "/api/v2/torrents/add"))
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .timeout(Duration.ofSeconds(30))
                            .POST(HttpRequest.BodyPublishers.ofString(form))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            boolean ok = response.statusCode() == 200 && !"Fails.".equalsIgnoreCase(response.body().trim());
            if (!ok) {
                log.warn("qBittorrent add failed. Status: {}, body: {}", response.statusCode(), response.body());
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

    public boolean testConnection(BookAcquisitionSettings settings) {
        return login(newClient(), settings);
    }

    private boolean login(HttpClient client, BookAcquisitionSettings settings) {
        try {
            String form = "username=" + URLEncoder.encode(nullSafe(settings.getQbittorrentUsername()), StandardCharsets.UTF_8)
                    + "&password=" + URLEncoder.encode(nullSafe(settings.getQbittorrentPassword()), StandardCharsets.UTF_8);
            HttpResponse<String> response = client.send(HttpRequest.newBuilder()
                            .uri(URI.create(trim(settings.getQbittorrentUrl()) + "/api/v2/auth/login"))
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .header("Referer", trim(settings.getQbittorrentUrl()))
                            .timeout(Duration.ofSeconds(15))
                            .POST(HttpRequest.BodyPublishers.ofString(form))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            boolean ok = response.statusCode() == 200 && "Ok.".equalsIgnoreCase(response.body().trim());
            if (!ok) {
                log.warn("qBittorrent login failed. Status: {}, body: {}", response.statusCode(), response.body());
            }
            return ok;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            log.warn("qBittorrent login error: {}", e.getMessage());
            return false;
        }
    }

    private HttpClient newClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .cookieHandler(new CookieManager())
                .build();
    }

    private String trim(String url) {
        if (url == null) return "";
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
