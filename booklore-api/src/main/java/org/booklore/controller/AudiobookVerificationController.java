package org.booklore.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.booklore.model.dto.settings.AudiobookVerificationSettings;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/audiobook-verification")
@Tag(name = "Audiobook Verification", description = "Audio content verification against book metadata")
public class AudiobookVerificationController {

    private final RestClient restClient = RestClient.create();

    @Operation(summary = "Test connectivity to the Whisper transcription service")
    @PreAuthorize("@securityUtil.isAdmin()")
    @PostMapping("/test-connection")
    public ResponseEntity<Map<String, Boolean>> testConnection(
            @RequestBody AudiobookVerificationSettings settings) {

        boolean ok = false;
        try {
            String base = settings.getWhisperUrl() == null
                    ? ""
                    : settings.getWhisperUrl().replaceAll("/$", "");
            restClient.get()
                    .uri(base + "/health")
                    .retrieve()
                    .toBodilessEntity();
            ok = true;
        } catch (Exception e) {
            log.warn("Whisper health check failed: {}", e.getMessage());
        }
        return ResponseEntity.ok(Map.of("whisper", ok));
    }
}
