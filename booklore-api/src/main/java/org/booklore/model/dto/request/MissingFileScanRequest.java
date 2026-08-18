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

    /**
     * Concurrent existence checks. These are IO-bound round-trips to storage,
     * not CPU work, so the useful value is far above the core count. On a
     * network share a serial scan of a large library is effectively unbounded
     * in duration; parallelism is what makes this task viable at all.
     */
    @Builder.Default
    private int parallelism = 32;

    /**
     * Seconds to wait for a single book's files before giving up on it.
     *
     * <p>A wedged SMB path can block a stat call indefinitely and the thread is
     * not interruptible. Bounding the wait lets the scan carry on and report
     * the book as unresolved rather than stalling the entire run.
     */
    @Builder.Default
    private int checkTimeoutSeconds = 15;

    public enum RefreshType {
        LIBRARY, MAGIC_SHELF, BOOKS
    }
}
