package org.booklore.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.model.dto.settings.EbookConversionSettings;
import org.booklore.model.websocket.Topic;
import org.booklore.service.NotificationService;
import org.booklore.service.conversion.EbookConversionService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
import java.util.Map;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/ebook-conversion")
@Tag(name = "Ebook Conversion", description = "Convert books between formats via the converter sidecar")
public class EbookConversionController {

    private final EbookConversionService conversionService;
    private final NotificationService notificationService;

    @Operation(summary = "Convert a book to another format")
    @PreAuthorize("@securityUtil.isAdmin() or @securityUtil.canEditMetadata()")
    @PostMapping("/books/{bookId}")
    public ResponseEntity<Map<String, Object>> convert(
            @Parameter(description = "ID of the book") @PathVariable Long bookId,
            @Parameter(description = "Target format, e.g. epub") @RequestParam(required = false) String target) {

        Thread.startVirtualThread(() -> {
            try {
                notificationService.sendMessage(Topic.LOG, "Converting book " + bookId + "...");
                Path result = conversionService.convertBook(bookId, target);
                notificationService.sendMessage(Topic.LOG, "Conversion complete: " + result.getFileName());
            } catch (Exception e) {
                log.error("Conversion failed for book {}", bookId, e);
                notificationService.sendMessage(Topic.LOG,
                        "Conversion failed for book " + bookId + ": " + e.getMessage());
            }
        });

        return ResponseEntity.accepted().body(Map.of("bookId", bookId, "status", "started"));
    }

    @Operation(summary = "Test connectivity to the ebook conversion service")
    @PreAuthorize("@securityUtil.isAdmin()")
    @PostMapping("/test-connection")
    public ResponseEntity<Map<String, Boolean>> testConnection(@RequestBody EbookConversionSettings settings) {
        return ResponseEntity.ok(Map.of("ebookConverter", conversionService.testConnection(settings)));
    }
}
