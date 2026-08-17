package org.booklore.model.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Set;

/**
 * Scope and options for the filename-based author extraction task.
 */
@Builder
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FilenameAuthorExtractRequest {

    /** How the target book set is specified. */
    private RefreshType refreshType;

    /** Required when refreshType == LIBRARY. */
    private Long libraryId;

    /** Required when refreshType == MAGIC_SHELF. */
    private Long magicShelfId;

    /** Required when refreshType == BOOKS. */
    private Set<Long> bookIds;

    /**
     * When false (default) only books whose authors list is missing are updated.
     * Set true to re-parse and overwrite existing authors.
     */
    @Builder.Default
    private boolean overwriteExisting = false;

    public enum RefreshType {
        LIBRARY, MAGIC_SHELF, BOOKS
    }
}
