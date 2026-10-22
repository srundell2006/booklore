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
 * @param folderBased   whether the row is a folder audiobook: {@code fileName} is then a folder, and
 *                      {@code fileSubPath} the folder that holds it
 */
public record BookFileScanRow(Long bookFileId,
                              Long bookId,
                              Long libraryPathId,
                              String libraryPath,
                              String fileSubPath,
                              String fileName,
                              boolean bookFormat,
                              BookFileType bookType,
                              Boolean bookDeleted,
                              boolean folderBased) {

    /** A row of a single file, not a folder audiobook. */
    public BookFileScanRow(Long bookFileId, Long bookId, Long libraryPathId, String libraryPath, String fileSubPath,
                           String fileName, boolean bookFormat, BookFileType bookType, Boolean bookDeleted) {
        this(bookFileId, bookId, libraryPathId, libraryPath, fileSubPath, fileName, bookFormat, bookType, bookDeleted, false);
    }

    /**
     * The folder that is this row's own: the folder holding a file, or a folder audiobook's folder itself. In
     * BOOK_PER_FOLDER a folder is one book, and a folder audiobook "Author/Book One" is the book of "Author/Book
     * One", not of "Author".
     */
    public String ownFolder() {
        return ownFolder(fileSubPath, fileName, folderBased);
    }

    /** {@link #ownFolder()} of a file or folder audiobook at this sub path and name. */
    public static String ownFolder(String fileSubPath, String fileName, boolean folderBased) {
        String subPath = fileSubPath == null ? "" : fileSubPath;
        if (!folderBased) {
            return subPath;
        }
        return subPath.isEmpty() ? fileName : subPath + "/" + fileName;
    }

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
