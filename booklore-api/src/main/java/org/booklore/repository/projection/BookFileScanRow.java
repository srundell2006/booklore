package org.booklore.repository.projection;

import org.booklore.model.enums.BookFileType;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * One book_file row of a library, with the few book columns a rescan needs to decide what
 * was removed, restored or is new. Lets a rescan reason about a whole library without
 * loading its entity graph.
 *
 * @param libraryPathId the owning book's library path (not a column of book_file)
 * @param libraryPath   that library path's root directory
 * @param bookDeleted   the owning book's soft-delete flag, which may be null
 */
public record BookFileScanRow(Long bookFileId,
                              Long bookId,
                              Long libraryPathId,
                              String libraryPath,
                              String fileSubPath,
                              String fileName,
                              boolean bookFormat,
                              BookFileType bookType,
                              Boolean bookDeleted) {

    /**
     * Same path as {@code BookFileEntity.getFullFilePath()}, or null where
     * {@code BookEntity.getFullFilePath()} would return null.
     */
    public Path fullPath() {
        if (libraryPath == null || fileSubPath == null || fileName == null) {
            return null;
        }
        return Paths.get(libraryPath, fileSubPath, fileName);
    }

    public boolean isBookDeleted() {
        return Boolean.TRUE.equals(bookDeleted);
    }
}
