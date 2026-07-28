package org.booklore.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.model.dto.settings.AudiobookMergeSettings;
import org.booklore.service.audiobook.AudiobookMergeService;
import org.booklore.service.audiobook.M4bMergeClient;
import org.booklore.service.NotificationService;
import org.booklore.model.websocket.Topic;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/audiobook-merge")
@Tag(name = "Audiobook Merge", description = "Merge multi-file audiobooks into a single m4b")
public class AudiobookMergeController {

    private final AudiobookMergeService mergeService;
    private final M4bMergeClient mergeClient;
    private final NotificationService notificationService;

    /** bookId -> cancel flag for in-flight merges. */
    private final Map<Long, AtomicBoolean> running = new ConcurrentHashMap<>();

    @Operation(summary = "Start merging a book's audio files into a single .m4b")
    @PreAuthorize("@securityUtil.isAdmin()")
    @PostMapping("/books/{bookId}")
    public ResponseEntity<Map<String, Object>> merge(
            @Parameter(description = "ID of the audiobook") @PathVariable Long bookId) {

        if (running.putIfAbsent(bookId, new AtomicBoolean(false)) != null) {
            return ResponseEntity.status(409).body(Map.of(
                    "error", "A merge is already running for this book", "bookId", bookId));
        }

        Thread.startVirtualThread(() -> {
            AtomicBoolean cancelFlag = running.get(bookId);
            try {
                Path result = mergeService.mergeBook(bookId, new AudiobookMergeService.ProgressListener() {
                    @Override
                    public void onProgress(int percent, String message) {
                        notificationService.sendMessage(Topic.LOG,
                                String.format("Audiobook merge (book %d): %d%% - %s", bookId, percent, message));
                    }

                    @Override
                    public boolean isCancelled() {
                        return cancelFlag != null && cancelFlag.get();
                    }
                });
                notificationService.sendMessage(Topic.LOG,
                        "Audiobook merge complete: " + result.getFileName());
            } catch (Exception e) {
                log.error("Audiobook merge failed for book {}", bookId, e);
                notificationService.sendMessage(Topic.LOG,
                        "Audiobook merge failed for book " + bookId + ": " + e.getMessage());
            } finally {
                running.remove(bookId);
            }
        });

        return ResponseEntity.accepted().body(Map.of("bookId", bookId, "status", "started"));
    }

    @Operation(summary = "Request cancellation of an in-flight merge")
    @PreAuthorize("@securityUtil.isAdmin()")
    @DeleteMapping("/books/{bookId}")
    public ResponseEntity<Void> cancel(@PathVariable Long bookId) {
        AtomicBoolean flag = running.get(bookId);
        if (flag == null) {
            return ResponseEntity.notFound().build();
        }
        flag.set(true);
        return ResponseEntity.accepted().build();
    }

    @Operation(summary = "List books with a merge currently running")
    @PreAuthorize("@securityUtil.isAdmin()")
    @GetMapping("/running")
    public ResponseEntity<Map<String, Object>> runningMerges() {
        return ResponseEntity.ok(Map.of("bookIds", running.keySet()));
    }

    @Operation(summary = "Test connectivity to the m4b-merge service")
    @PreAuthorize("@securityUtil.isAdmin()")
    @PostMapping("/test-connection")
    public ResponseEntity<Map<String, Boolean>> testConnection(@RequestBody AudiobookMergeSettings settings) {
        return ResponseEntity.ok(Map.of("m4bMerge", mergeClient.testConnection(settings)));
    }
}
