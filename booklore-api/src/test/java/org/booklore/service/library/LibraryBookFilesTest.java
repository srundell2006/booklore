package org.booklore.service.library;

import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.repository.projection.BookFileScanRow;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class LibraryBookFilesTest {

    private static final BookFileType[] TYPES = BookFileType.values();

    private static BookFileScanRow row(long fileId, long bookId, BookFileType type, boolean bookFormat) {
        return new BookFileScanRow(fileId, bookId, 1L, "/books", "sub", "file" + fileId, bookFormat, type, false);
    }

    @Test
    void primaryFileMatchesBookEntityGetPrimaryBookFile() {
        Random random = new Random(20261020L);
        long nextFileId = 1;
        for (long bookId = 1; bookId <= 2_000; bookId++) {
            int fileCount = 1 + random.nextInt(4);
            List<BookFileScanRow> rows = new ArrayList<>();
            List<BookFileEntity> files = new ArrayList<>();
            for (int i = 0; i < fileCount; i++) {
                long fileId = nextFileId++;
                BookFileType type = TYPES[random.nextInt(TYPES.length)];
                boolean bookFormat = random.nextInt(5) != 0;
                rows.add(row(fileId, bookId, type, bookFormat));
                files.add(BookFileEntity.builder().id(fileId).bookType(type).isBookFormat(bookFormat).build());
            }
            List<BookFileType> formatPriority = randomPriority(random);

            BookEntity book = BookEntity.builder()
                    .id(bookId)
                    .library(LibraryEntity.builder().formatPriority(formatPriority).build())
                    .bookFiles(files)
                    .build();
            // Rows may arrive in any order; the entity's collection is in id order.
            Collections.shuffle(rows, random);

            BookFileScanRow primary = new LibraryBookFiles(rows).primaryFiles(formatPriority).get(bookId);

            assertThat(primary.bookFileId())
                    .as("book %d, priority %s", bookId, formatPriority)
                    .isEqualTo(book.getPrimaryBookFile().getId());
        }
    }

    private static List<BookFileType> randomPriority(Random random) {
        return switch (random.nextInt(4)) {
            case 0 -> null;
            case 1 -> List.of();
            default -> {
                List<BookFileType> priority = new ArrayList<>(List.of(TYPES));
                Collections.shuffle(priority, random);
                yield List.copyOf(priority.subList(0, 1 + random.nextInt(priority.size())));
            }
        };
    }

    @Test
    void priorityPicksTheFirstBookFormatFileOfTheBestFormatAndSkipsNonBookFiles() {
        List<BookFileScanRow> rows = List.of(
                row(4, 1, BookFileType.PDF, true),
                row(1, 1, BookFileType.PDF, false),
                row(3, 1, BookFileType.EPUB, true),
                row(2, 1, BookFileType.EPUB, true));

        LibraryBookFiles bookFiles = new LibraryBookFiles(rows);

        assertThat(bookFiles.primaryFiles(List.of(BookFileType.PDF, BookFileType.EPUB)).get(1L).bookFileId()).isEqualTo(4L);
        assertThat(bookFiles.primaryFiles(List.of(BookFileType.EPUB)).get(1L).bookFileId()).isEqualTo(2L);
        // No match, or no priority: the first file, even though it is not a book format.
        assertThat(bookFiles.primaryFiles(List.of(BookFileType.MOBI)).get(1L).bookFileId()).isEqualTo(1L);
        assertThat(bookFiles.primaryFiles(null).get(1L).bookFileId()).isEqualTo(1L);
    }

    @Test
    void groupsRowsPerBookInIdOrder() {
        LibraryBookFiles bookFiles = new LibraryBookFiles(List.of(
                row(5, 2, BookFileType.EPUB, true),
                row(3, 1, BookFileType.EPUB, true),
                row(1, 2, BookFileType.PDF, true)));

        Map<Long, List<BookFileScanRow>> byBook = bookFiles.filesByBook();

        assertThat(byBook).containsOnlyKeys(1L, 2L);
        assertThat(byBook.get(2L)).extracting(BookFileScanRow::bookFileId).containsExactly(1L, 5L);
        assertThat(bookFiles.rows()).hasSize(3);
    }

    @Test
    void fullPathMatchesTheEntityPathAndIsNullWithoutALibraryPath() {
        BookFileScanRow atRoot = new BookFileScanRow(1L, 1L, 1L, "/books", "", "a.epub", true, BookFileType.EPUB, null);
        BookFileScanRow nested = new BookFileScanRow(2L, 1L, 1L, "/books", "x/y", "b.epub", true, BookFileType.EPUB, null);
        BookFileScanRow noPath = new BookFileScanRow(3L, 1L, null, null, "x", "c.epub", true, BookFileType.EPUB, null);

        assertThat(atRoot.fullPath()).isEqualTo(Path.of("/books", "a.epub"));
        assertThat(nested.fullPath()).isEqualTo(Path.of("/books/x/y/b.epub"));
        assertThat(noPath.fullPath()).isNull();
        assertThat(atRoot.isBookDeleted()).isFalse();
    }
}
