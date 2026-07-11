package org.booklore.model.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Set;

@Builder
@Data
@NoArgsConstructor
@AllArgsConstructor
public class IsbnScanRequest {

    /** How the target book set is specified. */
    private RefreshType refreshType;

    /** Required when refreshType == LIBRARY. */
    private Long libraryId;

    /** Required when refreshType == MAGIC_SHELF. */
    private Long magicShelfId;

    /** Required when refreshType == BOOKS. */
    private Set<Long> bookIds;

    /**
     * When false (default) only books whose isbn13 AND isbn10 are both blank are
     * updated.  Set true to overwrite existing ISBN values with whatever the scanner
     * finds in the file content.
     */
    @Builder.Default
    private boolean overwriteExisting = false;

    public enum RefreshType {
        LIBRARY, MAGIC_SHELF, BOOKS
    }
}
