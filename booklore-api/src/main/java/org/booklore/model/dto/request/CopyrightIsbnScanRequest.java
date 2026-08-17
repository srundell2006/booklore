package org.booklore.model.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Set;

/**
 * Scope and options for the copyright-page ISBN scan.
 */
@Builder
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CopyrightIsbnScanRequest {

    private RefreshType refreshType;

    /** Required when refreshType == LIBRARY. */
    private Long libraryId;

    /** Required when refreshType == MAGIC_SHELF. */
    private Long magicShelfId;

    /** Required when refreshType == BOOKS. */
    private Set<Long> bookIds;

    /** Re-scan books that already carry an ISBN. */
    @Builder.Default
    private boolean overwriteExisting = false;

    /** Record the ISBN but skip the metadata lookup, for inspecting results first. */
    @Builder.Default
    private boolean isbnOnly = false;

    public enum RefreshType {
        LIBRARY, MAGIC_SHELF, BOOKS
    }
}
