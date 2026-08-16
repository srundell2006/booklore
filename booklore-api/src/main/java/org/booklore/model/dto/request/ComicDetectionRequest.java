package org.booklore.model.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Set;

/**
 * Scope and overrides for a comic detection scan.
 */
@Builder
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ComicDetectionRequest {

    private RefreshType refreshType;

    /** Required when refreshType == LIBRARY. */
    private Long libraryId;

    /** Required when refreshType == MAGIC_SHELF. */
    private Long magicShelfId;

    /** Required when refreshType == BOOKS. */
    private Set<Long> bookIds;

    /** Re-evaluate books already flagged as comics. Overrides the saved setting when set. */
    private Boolean recheckExisting;

    /** Score nothing is written for; report only. Overrides the saved setting when set. */
    @Builder.Default
    private boolean dryRun = false;

    public enum RefreshType {
        LIBRARY, MAGIC_SHELF, BOOKS
    }
}
