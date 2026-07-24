package org.booklore.service.acquisition;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.model.dto.settings.BookAcquisitionSettings;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Slf4j
@Service
@RequiredArgsConstructor
public class SabnzbdClient {

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    /**
     * Adds an NZB by URL. Returns the SABnzbd nzo_id, or null on failure.
     */
    public String addNzb(BookAcquisitionSettings settings, String nzbUrl, String niceName) {
        URI uri = UriComponentsBuilder.fromUriString(trim(settings.getSabnzbdUrl()) + "/api")
                .queryParam("mode", "addurl")
                .queryParam("name", nzbUrl)
                .queryParam("nzbname", niceName)
                .queryParam("cat", settings.getSabnzbdCategory())
                .queryParam("apikey", settings.getSabnzbdApiKey())
                .queryParam("output", "json")
                .build().toUri();
        try {
            HttpResponse<String> response = httpClient.send(
                    HttpRequest.newBuilder().uri(uri).timeout(Duration.ofSeconds(30)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("SABnzbd addurl failed. Status: {}", response.statusCode());
                return null;
            }
            JsonNode json = objectMapper.readTree(response.body());
            if (!json.path("status").asBoolean(false)) {
                log.warn("SABnzbd rejected NZB: {}", response.body());
                return null;
            }
            JsonNode ids = json.path("nzo_ids");
            return ids.isArray() && !ids.isEmpty() ? ids.get(0).asText() : "unknown";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            log.error("SABnzbd addurl error: {}", e.getMessage());
            return null;
        }
    }

    public boolean testConnection(BookAcquisitionSettings settings) {
        try {
            URI uri = UriComponentsBuilder.fromUriString(trim(settings.getSabnzbdUrl()) + "/api")
                    .queryParam("mode", "version")
                    .queryParam("apikey", settings.getSabnzbdApiKey())
                    .queryParam("output", "json")
                    .build().toUri();
            HttpResponse<String> response = httpClient.send(
                    HttpRequest.newBuilder().uri(uri).timeout(Duration.ofSeconds(15)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200 && response.body().contains("version")) {
                return true;
            }
            log.warn("SABnzbd connection test failed: HTTP {} - {}", response.statusCode(), truncate(response.body()));
            return false;
        } catch (Exception e) {
            log.warn("SABnzbd connection test failed: {}: {}", e.getClass().getSimpleName(), e.getMessage());
            return false;
        }
    }

    private String trim(String url) {
        if (url == null) return "";
        String trimmed = url.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    private String truncate(String s) {
        if (s == null) return "";
        return s.length() > 200 ? s.substring(0, 200) : s;
    }
}
