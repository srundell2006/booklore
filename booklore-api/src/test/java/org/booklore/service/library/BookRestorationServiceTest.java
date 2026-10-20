package org.booklore.service.library;

import org.booklore.mapper.BookMapper;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.model.websocket.Topic;
import org.booklore.repository.BookRepository;
import org.booklore.repository.projection.BookFileScanRow;
import org.booklore.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BookRestorationServiceTest {

    @Mock
    private BookRepository bookRepository;
    @Mock
    private BookMapper bookMapper;
    @Mock
    private NotificationService notificationService;

    @InjectMocks
    private BookRestorationService bookRestorationService;

    private LibraryEntity library;

    @BeforeEach
    void setUp() {
        library = LibraryEntity.builder().id(1L).name("Books").build();
    }

    private static BookFileScanRow row(long fileId, long bookId, String fileName, BookFileType type, boolean deleted) {
        return new BookFileScanRow(fileId, bookId, 10L, "/books", "", fileName, true, type, deleted);
    }

    /** Coverage for a walk during which none of these rows changed. */
    private static WalkCoverage unchangedSinceWalkStart(LibraryBookFiles bookFiles) {
        return new WalkCoverage(Set.copyOf(bookFiles.rows()), new LibraryScanResult(List.of(), Set.of()));
    }

    @Test
    void restoresSoftDeletedBooksWhosePrimaryFileIsBackOnDisk() {
        library.setFormatPriority(List.of(BookFileType.PDF));
        LibraryBookFiles bookFiles = new LibraryBookFiles(List.of(
                // book 12: deleted, epub still gone but the preferred pdf is back
                row(1, 12, "a.epub", BookFileType.EPUB, true),
                row(2, 12, "a.pdf", BookFileType.PDF, true),
                // book 13: deleted, its primary is still gone
                row(3, 13, "b.pdf", BookFileType.PDF, true),
                row(4, 13, "b.epub", BookFileType.EPUB, true),
                // book 11: not deleted
                row(5, 11, "c.epub", BookFileType.EPUB, false)));
        Set<Path> onDisk = Set.of(Path.of("/books/a.pdf"), Path.of("/books/b.epub"), Path.of("/books/c.epub"));
        BookEntity book12 = BookEntity.builder().id(12L).deleted(true).deletedAt(Instant.EPOCH).build();
        when(bookRepository.findAllById(List.of(12L))).thenReturn(List.of(book12));

        bookRestorationService.restoreDeletedBooks(library, bookFiles, onDisk, unchangedSinceWalkStart(bookFiles));

        assertThat(book12.getDeleted()).isFalse();
        assertThat(book12.getDeletedAt()).isNull();
        assertThat(book12.getAddedOn()).isNotNull();
        verify(bookMapper).toBookWithDescription(book12, false);
        verify(notificationService).sendMessage(eq(Topic.BOOK_ADD), any());
        verify(bookRepository).saveAll(List.of(book12));
    }

    @Test
    void doesNothingWhenNoDeletedBookIsBack() {
        LibraryBookFiles bookFiles = new LibraryBookFiles(List.of(
                row(1, 12, "a.epub", BookFileType.EPUB, true),
                row(2, 11, "c.epub", BookFileType.EPUB, false)));

        WalkCoverage coverage = unchangedSinceWalkStart(bookFiles);
        bookRestorationService.restoreDeletedBooks(library, bookFiles, Set.of(Path.of("/books/c.epub")), coverage);
        bookRestorationService.restoreDeletedBooks(library, bookFiles, Set.of(), coverage);

        verifyNoInteractions(bookRepository, bookMapper, notificationService);
    }

    @Test
    void onlyRestoresBooksThatWereAlreadyDeletedWhenTheWalkStarted() {
        // Book 12 was soft-deleted while the walk ran, after the walk had listed its file; book 13
        // was deleted before and is back.
        List<BookFileScanRow> beforeWalk = List.of(
                row(1, 12, "a.epub", BookFileType.EPUB, false),
                row(2, 13, "b.epub", BookFileType.EPUB, true));
        LibraryBookFiles afterWalk = new LibraryBookFiles(List.of(
                row(1, 12, "a.epub", BookFileType.EPUB, true),
                row(2, 13, "b.epub", BookFileType.EPUB, true)));
        WalkCoverage coverage = new WalkCoverage(Set.copyOf(beforeWalk), new LibraryScanResult(List.of(), Set.of()));
        BookEntity book13 = BookEntity.builder().id(13L).deleted(true).build();
        when(bookRepository.findAllById(List.of(13L))).thenReturn(List.of(book13));

        bookRestorationService.restoreDeletedBooks(library, afterWalk,
                Set.of(Path.of("/books/a.epub"), Path.of("/books/b.epub")), coverage);

        assertThat(book13.getDeleted()).isFalse();
        verify(bookRepository).saveAll(List.of(book13));
        verify(bookRepository).findAllById(List.of(13L));
    }
}
