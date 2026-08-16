package org.booklore.model.dto.settings;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Thresholds and stage toggles for the comic detection scan.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ComicDetectionSettings {

    /** Score at or above which a book is marked as a comic without review. */
    @Builder.Default
    private int autoMarkThreshold = 80;

    /** Score at or above which a book is queued for review instead of ignored. */
    @Builder.Default
    private int reviewThreshold = 45;

    /** Open EPUB/PDF files to inspect layout, image ratio and text density. */
    @Builder.Default
    private boolean structuralAnalysis = true;

    /** Use publisher, category and title heuristics from stored metadata. */
    @Builder.Default
    private boolean metadataHeuristics = true;

    /** Ask the local LLM to break ties in the borderline band. */
    @Builder.Default
    private boolean llmTiebreaker = true;

    /** Re-evaluate books already flagged as comics instead of skipping them. */
    @Builder.Default
    private boolean recheckExisting = false;
}
