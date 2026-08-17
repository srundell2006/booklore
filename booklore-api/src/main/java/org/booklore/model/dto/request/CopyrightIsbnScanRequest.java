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

    /**
     * How many spine documents to read beyond the declared copyright page.
     *
     * <p>Zero (the default) keeps the scan strictly declarative: OPF-declared
     * copyright page only. Any positive value widens to the full escalation —
     * dc:identifier, copyright page, this many spine documents, then
     * copyright-named manifest files — which finds more ISBNs but can pick up
     * one belonging to another book advertised in the front matter.
     *
     * <p>Note these are spine <em>documents</em>, not rendered pages: in a
     * typical novel 15 reaches roughly the tenth chapter.
     */
    @Builder.Default
    private int spineItemsToScan = 0;

    public enum RefreshType {
        LIBRARY, MAGIC_SHELF, BOOKS
    }
}
