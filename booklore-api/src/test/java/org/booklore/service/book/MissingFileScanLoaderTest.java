package org.booklore.service.book;

import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.model.entity.LibraryPathEntity;
import org.booklore.model.entity.TagEntity;
import org.booklore.repository.BookRepository;
import org.booklore.service.book.MissingFileScanLoader.FileCheckTarget;
import org.booklore.service.book.MissingFileScanLoader.FileRef;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MissingFileScanLoaderTest {

    private final BookRepository bookRepository = mock(BookRepository.class);
    private final MissingFileScanLoader loader = new MissingFileScanLoader(bookRepository);

    private static BookEntity book(long id, LibraryPathEntity libraryPath, String tag) {
        BookEntity book = BookEntity.builder()
                .id(id)
                .libraryPath(libraryPath)
                .metadata(BookMetadataEntity.builder().title("Book " + id)
                        .tags(new HashSet<>(Set.of(TagEntity.builder().name(tag).build()))).build())
                .bookFiles(new ArrayList<>())
                .build();
        // Bytecode enhancement adds the file to the book's files.
        BookFileEntity.builder().book(book).fileSubPath("Author").fileName(id + ".epub").build();
        return book;
    }

    @Test
    void targetsCarryTheLibraryPathTheirFilesAreUnder() {
        LibraryPathEntity libraryPath = LibraryPathEntity.builder().id(1L).path("/books").build();
        BookEntity mounted = book(1L, libraryPath, "File Not Found");
        BookEntity noLibraryPath = book(2L, null, "Fiction");
        when(bookRepository.findAllWithMetadataByIds(Set.of(1L, 2L))).thenReturn(List.of(mounted, noLibraryPath));

        List<FileCheckTarget> targets = loader.load(Set.of(1L, 2L), "file not found");

        assertThat(targets).containsExactly(
                new FileCheckTarget(1L, "Book 1", Path.of("/books"),
                        List.of(new FileRef("1.epub", Path.of("/books/Author/1.epub"))), true),
                new FileCheckTarget(2L, "Book 2", null, List.of(new FileRef("2.epub", null)), false));
    }
}
