package org.booklore.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.booklore.config.security.service.AuthenticationService;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.ComicCandidateDto;
import org.booklore.service.comic.ComicReviewService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/comic-detection")
@Tag(name = "Comic Detection", description = "Review queue for books detected as possible comics")
public class ComicDetectionController {

    private final ComicReviewService comicReviewService;
    private final AuthenticationService authenticationService;

    @Operation(summary = "List borderline candidates awaiting review")
    @PreAuthorize("@securityUtil.isAdmin()")
    @GetMapping("/candidates")
    public ResponseEntity<List<ComicCandidateDto>> listPending() {
        return ResponseEntity.ok(comicReviewService.listPending());
    }

    @Operation(summary = "Count borderline candidates awaiting review")
    @PreAuthorize("@securityUtil.isAdmin()")
    @GetMapping("/candidates/count")
    public ResponseEntity<Map<String, Long>> pendingCount() {
        return ResponseEntity.ok(Map.of("pending", comicReviewService.pendingCount()));
    }

    @Operation(summary = "Confirm a candidate as a comic")
    @PreAuthorize("@securityUtil.isAdmin()")
    @PostMapping("/candidates/{id}/accept")
    public ResponseEntity<Void> accept(@PathVariable Long id) {
        comicReviewService.accept(id, currentUserId());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Dismiss a candidate")
    @PreAuthorize("@securityUtil.isAdmin()")
    @PostMapping("/candidates/{id}/reject")
    public ResponseEntity<Void> reject(@PathVariable Long id) {
        comicReviewService.reject(id, currentUserId());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Confirm several candidates at once")
    @PreAuthorize("@securityUtil.isAdmin()")
    @PostMapping("/candidates/accept")
    public ResponseEntity<Map<String, Integer>> acceptAll(@RequestBody List<Long> ids) {
        return ResponseEntity.ok(Map.of("accepted", comicReviewService.acceptAll(ids, currentUserId())));
    }

    @Operation(summary = "Dismiss several candidates at once")
    @PreAuthorize("@securityUtil.isAdmin()")
    @PostMapping("/candidates/reject")
    public ResponseEntity<Map<String, Integer>> rejectAll(@RequestBody List<Long> ids) {
        return ResponseEntity.ok(Map.of("rejected", comicReviewService.rejectAll(ids, currentUserId())));
    }

    @Operation(summary = "Delete already-resolved candidate rows")
    @PreAuthorize("@securityUtil.isAdmin()")
    @DeleteMapping("/candidates/resolved")
    public ResponseEntity<Map<String, Long>> clearResolved() {
        return ResponseEntity.ok(Map.of("deleted", comicReviewService.clearResolved()));
    }

    private Long currentUserId() {
        BookLoreUser user = authenticationService.getAuthenticatedUser();
        return user != null ? user.getId() : null;
    }
}
