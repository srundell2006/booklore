package org.booklore.model.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Set;

/**
 * Scope and options for the missing-file scan.
 */
@Builder
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MissingFileScanRequest {

    private RefreshType refreshType;

    /** Required when refreshType == LIBRARY. */
    private Long libraryId;

    /** Required when refreshType == MAGIC_SHELF. */
    private Long magicShelfId;

    /** Required when refreshType == BOOKS. */
    private Set<Long> bookIds;

    /**
     * Tag applied to books whose file is missing. Defaults to
     * {@code File Not Found} when blank.
     */
    private String tagName;

    /**
     * Remove the tag from books whose file is present again. On by default so
     * repeated runs converge on the truth rather than accumulating stale tags.
     */
    @Builder.Default
    private boolean clearTagWhenPresent = true;

    /** Report what would change without writing tags. */
    @Builder.Default
    private boolean dryRun = false;

    public enum RefreshType {
        LIBRARY, MAGIC_SHELF, BOOKS
    }
}
