package org.booklore.service.acquisition;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.model.dto.settings.BookAcquisitionSettings;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProwlarrClient {

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    public List<ProwlarrRelease> search(BookAcquisitionSettings settings, String query) {
        String baseUrl = trimTrailingSlash(settings.getProwlarrUrl());
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(baseUrl + "/api/v1/search")
                .queryParam("query", query)
                .queryParam("type", "search")
                .queryParam("limit", 100);

        // Prowlarr expects categories as repeated query parameters
        // (categories=7000&categories=7020). Passing the configured value as a
        // single comma-joined string made Prowlarr reject the entire request
        // with 400 "The value '7000,7020' is not valid.", so every search came
        // back empty.
        for (String category : splitCategories(settings.getSearchCategories())) {
            builder.queryParam("categories", category);
        }

        URI uri = builder.build().toUri();

        try {
            log.info("Prowlarr search: {}", query);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(uri)
                    .header("X-Api-Key", settings.getProwlarrApiKey())
                    .timeout(Duration.ofSeconds(90))
                    .GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("Prowlarr search failed. Status: {}, body: {}", response.statusCode(), truncate(response.body()));
                throw new ProwlarrSearchException(
                        "Prowlarr rejected the search (HTTP " + response.statusCode() + "): " + truncate(response.body()));
            }
            return objectMapper.readValue(response.body(), new TypeReference<List<ProwlarrRelease>>() {});
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProwlarrSearchException("Prowlarr search was interrupted");
        } catch (ProwlarrSearchException e) {
            throw e;
        } catch (Exception e) {
            log.error("Prowlarr search error for query '{}': {}", query, e.getMessage());
            throw new ProwlarrSearchException("Prowlarr search failed: " + e.getMessage());
        }
    }

    /** "7000, 7020" -> ["7000", "7020"]; blank or null yields no categories at all. */
    private List<String> splitCategories(String configured) {
        if (configured == null || configured.isBlank()) {
            return List.of();
        }
        return Arrays.stream(configured.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toList();
    }

    public boolean testConnection(BookAcquisitionSettings settings) {
        try {
            String baseUrl = trimTrailingSlash(settings.getProwlarrUrl());
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/v1/health"))
                    .header("X-Api-Key", settings.getProwlarrApiKey())
                    .timeout(Duration.ofSeconds(15))
                    .GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return true;
            }
            if (response.statusCode() == 401) {
                log.warn("Prowlarr connection test failed: HTTP 401 Unauthorized — check the API key");
            } else {
                log.warn("Prowlarr connection test failed: HTTP {} - {}", response.statusCode(), truncate(response.body()));
            }
            return false;
        } catch (Exception e) {
            log.warn("Prowlarr connection test failed: {}: {}", e.getClass().getSimpleName(), e.getMessage());
            return false;
        }
    }

    private String trimTrailingSlash(String url) {
        if (url == null) return "";
        String trimmed = url.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    private String truncate(String s) {
        if (s == null) return null;
        return s.length() > 300 ? s.substring(0, 300) : s;
    }
}
