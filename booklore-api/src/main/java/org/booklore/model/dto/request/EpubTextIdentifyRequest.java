package org.booklore.model.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Set;

/**
 * Scope and options for the EPUB text-based metadata identification task.
 */
@Builder
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EpubTextIdentifyRequest {

    /** How the target book set is specified. */
    private RefreshType refreshType;

    /** Required when refreshType == LIBRARY. */
    private Long libraryId;

    /** Required when refreshType == MAGIC_SHELF. */
    private Long magicShelfId;

    /** Required when refreshType == BOOKS. */
    private Set<Long> bookIds;

    /**
     * When false (default) only books whose title OR authors are missing are updated.
     * Set true to run identification on all EPUBs and overwrite existing metadata.
     */
    @Builder.Default
    private boolean overwriteExisting = false;

    public enum RefreshType {
        LIBRARY, MAGIC_SHELF, BOOKS
    }
}
