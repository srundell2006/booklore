package org.booklore.service.library;

import org.booklore.model.enums.BookFileType;
import org.booklore.repository.projection.BookFileScanRow;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every book_file row of one library, read with a single projection query and grouped per
 * book. Rescan phases make their decisions from this plain data and only load entities for
 * the ids they actually change, so a rescan never holds the library's entity graph.
 */
public final class LibraryBookFiles {

    private final List<BookFileScanRow> rows;
    private final Map<Long, List<BookFileScanRow>> filesByBook;

    public LibraryBookFiles(List<BookFileScanRow> rows) {
        this.rows = List.copyOf(rows);
        Map<Long, List<BookFileScanRow>> byBook = new LinkedHashMap<>();
        for (BookFileScanRow row : this.rows) {
            byBook.computeIfAbsent(row.bookId(), id -> new ArrayList<>()).add(row);
        }
        // BookEntity.bookFiles is @OrderBy("id ASC"); primary-file selection depends on it.
        byBook.values().forEach(files -> files.sort(Comparator.comparing(BookFileScanRow::bookFileId)));
        this.filesByBook = byBook;
    }

    public List<BookFileScanRow> rows() {
        return rows;
    }

    /** Files per book id, each list in book_file id order. Books without files are absent. */
    public Map<Long, List<BookFileScanRow>> filesByBook() {
        return filesByBook;
    }

    /** The primary file of every book that has files, keyed by book id. */
    public Map<Long, BookFileScanRow> primaryFiles(List<BookFileType> formatPriority) {
        Map<Long, BookFileScanRow> primaries = new LinkedHashMap<>();
        filesByBook.forEach((bookId, files) -> primaries.put(bookId, primaryFile(files, formatPriority)));
        return primaries;
    }

    /**
     * The file {@code BookEntity.getPrimaryBookFile()} picks: the first book-format file of the
     * first format in {@code formatPriority} that has one, otherwise the first file.
     *
     * @param files one book's files in id order, not empty
     */
    static BookFileScanRow primaryFile(List<BookFileScanRow> files, List<BookFileType> formatPriority) {
        if (formatPriority != null) {
            for (BookFileType format : formatPriority) {
                for (BookFileScanRow file : files) {
                    if (file.bookFormat() && file.bookType() == format) {
                        return file;
                    }
                }
            }
        }
        return files.getFirst();
    }
}
