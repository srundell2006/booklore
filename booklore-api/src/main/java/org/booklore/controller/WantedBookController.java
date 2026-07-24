package org.booklore.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.booklore.config.security.service.AuthenticationService;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.settings.BookAcquisitionSettings;
import org.booklore.model.entity.WantedBookEntity;
import org.booklore.model.enums.WantedBookStatus;
import org.booklore.repository.WantedBookRepository;
import org.booklore.service.acquisition.BookAcquisitionService;
import org.booklore.service.acquisition.ProwlarrClient;
import org.booklore.service.acquisition.ProwlarrRelease;
import org.booklore.service.acquisition.QbittorrentClient;
import org.booklore.service.acquisition.SabnzbdClient;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/wanted-books")
@Tag(name = "Wanted Books", description = "Readarr-style wanted list, indexer search, and automated grabbing")
public class WantedBookController {

    private final WantedBookRepository wantedBookRepository;
    private final BookAcquisitionService acquisitionService;
    private final ProwlarrClient prowlarrClient;
    private final SabnzbdClient sabnzbdClient;
    private final QbittorrentClient qbittorrentClient;
    private final AuthenticationService authenticationService;

    @Operation(summary = "List all wanted books")
    @PreAuthorize("@securityUtil.isAdmin()")
    @GetMapping
    public ResponseEntity<List<WantedBookEntity>> list() {
        return ResponseEntity.ok(wantedBookRepository.findAllByOrderByAddedAtDesc());
    }

    @Operation(summary = "Add a wanted book")
    @PreAuthorize("@securityUtil.isAdmin()")
    @PostMapping
    public ResponseEntity<WantedBookEntity> add(@RequestBody WantedBookEntity request) {
        BookLoreUser user = authenticationService.getAuthenticatedUser();
        WantedBookEntity entity = WantedBookEntity.builder()
                .title(request.getTitle())
                .author(request.getAuthor())
                .isbn13(request.getIsbn13())
                .asin(request.getAsin())
                .preferredFormat(request.getPreferredFormat())
                .status(WantedBookStatus.WANTED)
                .addedByUserId(user != null ? user.getId() : null)
                .build();
        return ResponseEntity.ok(wantedBookRepository.save(entity));
    }

    @Operation(summary = "Remove a wanted book")
    @PreAuthorize("@securityUtil.isAdmin()")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        wantedBookRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Reset a wanted book back to WANTED status")
    @PreAuthorize("@securityUtil.isAdmin()")
    @PatchMapping("/{id}/reset")
    public ResponseEntity<WantedBookEntity> reset(@PathVariable Long id) {
        WantedBookEntity entity = wantedBookRepository.findById(id).orElseThrow();
        entity.setStatus(WantedBookStatus.WANTED);
        entity.setGrabbedAt(null);
        entity.setGrabbedReleaseTitle(null);
        entity.setGrabbedIndexer(null);
        entity.setDownloadClient(null);
        entity.setDownloadId(null);
        entity.setFailureReason(null);
        return ResponseEntity.ok(wantedBookRepository.save(entity));
    }

    @Operation(summary = "Search indexers for releases matching a wanted book")
    @PreAuthorize("@securityUtil.isAdmin()")
    @PostMapping("/{id}/search")
    public ResponseEntity<List<ProwlarrRelease>> search(@PathVariable Long id) {
        return ResponseEntity.ok(acquisitionService.searchReleases(id));
    }

    @Operation(summary = "Grab a specific release for a wanted book")
    @PreAuthorize("@securityUtil.isAdmin()")
    @PostMapping("/{id}/grab")
    public ResponseEntity<WantedBookEntity> grab(@PathVariable Long id, @RequestBody ProwlarrRelease release) {
        return ResponseEntity.ok(acquisitionService.grabRelease(id, release));
    }

    @Operation(summary = "Run the automatic search across all wanted books now")
    @PreAuthorize("@securityUtil.isAdmin()")
    @PostMapping("/search-all")
    public ResponseEntity<Void> searchAll() {
        Thread.startVirtualThread(acquisitionService::searchAllWanted);
        return ResponseEntity.accepted().build();
    }

    @Operation(summary = "Test connectivity to Prowlarr, SABnzbd and qBittorrent")
    @PreAuthorize("@securityUtil.isAdmin()")
    @PostMapping("/test-connections")
    public ResponseEntity<Map<String, Boolean>> testConnections(@RequestBody BookAcquisitionSettings settings) {
        return ResponseEntity.ok(Map.of(
                "prowlarr", prowlarrClient.testConnection(settings),
                "sabnzbd", sabnzbdClient.testConnection(settings),
                "qbittorrent", qbittorrentClient.testConnection(settings)
        ));
    }
}
