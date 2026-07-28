package org.booklore.service.audiobook;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.model.dto.settings.AudiobookMergeSettings;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/** Thin HTTP client for the m4b-merge sidecar. */
@Slf4j
@Service
@RequiredArgsConstructor
public class M4bMergeClient {

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    public boolean testConnection(AudiobookMergeSettings settings) {
        try {
            HttpResponse<String> response = get(settings, "/health");
            if (response.statusCode() == 200) {
                log.info("m4b-merge health: {}", truncate(response.body()));
                return true;
            }
            log.warn("m4b-merge health check failed: HTTP {} - {}", response.statusCode(), truncate(response.body()));
            return false;
        } catch (Exception e) {
            log.warn("m4b-merge health check failed: {}: {}", e.getClass().getSimpleName(), e.getMessage());
            return false;
        }
    }

    public MergeJobStatus submit(AudiobookMergeSettings settings, String inputPath, String outputPath,
                                 Map<String, Object> options) {
        try {
            String payload = objectMapper.writeValueAsString(Map.of(
                    "inputPath", inputPath,
                    "outputPath", outputPath,
                    "options", options));
            HttpResponse<String> response = httpClient.send(HttpRequest.newBuilder()
                            .uri(URI.create(baseUrl(settings) + "/jobs"))
                            .header("Content-Type", "application/json")
                            .timeout(Duration.ofSeconds(60))
                            .POST(HttpRequest.BodyPublishers.ofString(payload))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 202 && response.statusCode() != 200) {
                throw new IllegalStateException("m4b-merge rejected job: HTTP "
                        + response.statusCode() + " - " + truncate(response.body()));
            }
            return objectMapper.readValue(response.body(), MergeJobStatus.class);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while submitting merge job", e);
        } catch (Exception e) {
            throw new IllegalStateException("failed to submit merge job: " + e.getMessage(), e);
        }
    }

    public MergeJobStatus poll(AudiobookMergeSettings settings, String jobId) {
        try {
            HttpResponse<String> response = get(settings, "/jobs/" + jobId);
            if (response.statusCode() != 200) {
                throw new IllegalStateException("m4b-merge job lookup failed: HTTP " + response.statusCode());
            }
            return objectMapper.readValue(response.body(), MergeJobStatus.class);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while polling merge job", e);
        } catch (Exception e) {
            throw new IllegalStateException("failed to poll merge job: " + e.getMessage(), e);
        }
    }

    public void cancel(AudiobookMergeSettings settings, String jobId) {
        try {
            httpClient.send(HttpRequest.newBuilder()
                            .uri(URI.create(baseUrl(settings) + "/jobs/" + jobId))
                            .timeout(Duration.ofSeconds(30))
                            .DELETE().build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("Failed to cancel merge job {}: {}", jobId, e.getMessage());
        }
    }

    private HttpResponse<String> get(AudiobookMergeSettings settings, String path)
            throws java.io.IOException, InterruptedException {
        return httpClient.send(HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl(settings) + path))
                        .timeout(Duration.ofSeconds(30))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private String baseUrl(AudiobookMergeSettings settings) {
        String url = settings.getServiceUrl();
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("Audiobook merge service URL is not configured");
        }
        url = url.trim();
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private String truncate(String value) {
        if (value == null) return "";
        return value.length() > 300 ? value.substring(0, 300) : value;
    }
}
