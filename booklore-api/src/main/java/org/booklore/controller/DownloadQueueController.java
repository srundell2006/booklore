package org.booklore.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.booklore.service.acquisition.DownloadQueueItem;
import org.booklore.service.acquisition.DownloadQueueService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/download-queue")
@Tag(name = "Download Queue", description = "Unified activity view across configured download clients")
public class DownloadQueueController {

    private final DownloadQueueService downloadQueueService;

    @Operation(summary = "Get the active download queue from all configured clients")
    @PreAuthorize("@securityUtil.isAdmin()")
    @GetMapping
    public ResponseEntity<List<DownloadQueueItem>> getQueue() {
        return ResponseEntity.ok(downloadQueueService.getQueue());
    }

    @Operation(summary = "Remove an item from its download client")
    @PreAuthorize("@securityUtil.isAdmin()")
    @DeleteMapping("/{client}/{id}")
    public ResponseEntity<Void> remove(
            @Parameter(description = "SABNZBD or QBITTORRENT") @PathVariable String client,
            @Parameter(description = "Client-native item id") @PathVariable String id,
            @Parameter(description = "Also delete downloaded files") @RequestParam(defaultValue = "false") boolean deleteFiles) {
        boolean removed = downloadQueueService.remove(client, id, deleteFiles);
        return removed ? ResponseEntity.noContent().build() : ResponseEntity.badRequest().build();
    }
}
