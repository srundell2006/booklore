package org.booklore.service.library;

import org.booklore.repository.projection.BookFileScanRow;

import java.nio.file.Path;
import java.util.Set;

/**
 * Which book_file rows a rescan's walk can judge. The walk not finding a row's file only
 * proves the file is gone when the walk could have seen it, which takes two things:
 * <ul>
 *   <li>The row is exactly as it was when the walk started. On a network share the walk
 *       takes hours, and a book added, moved or soft-deleted meanwhile may sit in a
 *       directory the walk had already listed.</li>
 *   <li>The walk could read the file's place in the library (see
 *       {@link LibraryScanResult#isUnderIncompletePath(Path)}).</li>
 * </ul>
 */
public final class WalkCoverage {

    private final Set<BookFileScanRow> rowsBeforeWalk;
    private final LibraryScanResult scanResult;

    /**
     * @param rowsBeforeWalk the library's rows, read just before the walk started
     * @param scanResult     what the walk found and what it could not read
     */
    public WalkCoverage(Set<BookFileScanRow> rowsBeforeWalk, LibraryScanResult scanResult) {
        this.rowsBeforeWalk = rowsBeforeWalk;
        this.scanResult = scanResult;
    }

    /** True when the row (its book, location, format or the book's soft-delete flag) is not as it was before the walk. */
    public boolean changedDuringWalk(BookFileScanRow row) {
        return !rowsBeforeWalk.contains(row);
    }

    /** True when the walk could not read {@code path} or a directory above it, so not finding it proves nothing. */
    public boolean mayHaveMissed(Path path) {
        return scanResult.isUnderIncompletePath(path);
    }
}
