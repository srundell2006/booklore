package org.booklore.service.library;

import jakarta.persistence.EntityManager;
import org.booklore.model.dto.settings.LibraryFile;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.entity.LibraryPathEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.model.websocket.Topic;
import org.booklore.repository.BookAdditionalFileRepository;
import org.booklore.repository.BookRepository;
import org.booklore.repository.projection.BookFileScanRow;
import org.booklore.service.NotificationService;
import org.booklore.util.FileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BookDeletionServiceTest {

    @Mock
    private BookRepository bookRepository;
    @Mock
    private BookAdditionalFileRepository bookAdditionalFileRepository;
    @Mock
    private FileService fileService;
    @Mock
    private NotificationService notificationService;
    @Mock
    private EntityManager entityManager;

    @TempDir
    Path tempDir;

    private BookDeletionService bookDeletionService;
    private LibraryEntity library;
    private LibraryPathEntity libraryPath;

    @BeforeEach
    void setUp() {
        bookDeletionService = new BookDeletionService(bookRepository, bookAdditionalFileRepository, fileService,
                notificationService, entityManager);
        libraryPath = LibraryPathEntity.builder().id(10L).path("/books").build();
        library = LibraryEntity.builder().id(1L).name("Books").libraryPaths(List.of(libraryPath)).build();
    }

    private void givenBookFoldersUnderTempDir() {
        when(fileService.getImagesFolder(anyLong())).thenAnswer(inv -> tempDir.resolve("images-" + inv.getArgument(0)).toString());
        when(fileService.getBookMetadataBackupPath(anyLong())).thenAnswer(inv -> tempDir.resolve("backup-" + inv.getArgument(0)).toString());
    }

    private BookEntity book(long id, BookFileEntity... files) {
        BookEntity book = BookEntity.builder().id(id).library(library).libraryPath(libraryPath)
                .bookFiles(new ArrayList<>(List.of(files))).build();
        for (BookFileEntity file : files) {
            file.setBook(book);
        }
        return book;
    }

    private BookFileEntity file(long id, String subPath, String name, BookFileType type) {
        return BookFileEntity.builder().id(id).fileSubPath(subPath).fileName(name).isBookFormat(true).bookType(type).build();
    }

    private LibraryFile onDisk(String subPath, String name) {
        return LibraryFile.builder().libraryEntity(library).libraryPathEntity(libraryPath)
                .fileSubPath(subPath).fileName(name).bookFileType(BookFileType.EPUB).build();
    }

    private BookFileScanRow row(long fileId, long bookId, BookFileType type, boolean bookFormat) {
        return new BookFileScanRow(fileId, bookId, 10L, "/books", "", "f" + fileId, bookFormat, type, false);
    }

    @Test
    void deleteRemovedAdditionalFiles_deletesTheIdsInOneStatement() {
        bookDeletionService.deleteRemovedAdditionalFiles(List.of(3L, 4L));

        verify(bookAdditionalFileRepository).deleteAllByIdInBatch(List.of(3L, 4L));
        verifyNoMoreInteractions(bookAdditionalFileRepository);
    }

    @Test
    void processDeletedLibraryFiles_keepsBooksWithARemainingFormatAndDeletesTheRest() {
        BookFileEntity goneEpub = file(1, "a", "a.epub", BookFileType.EPUB);
        BookFileEntity presentPdf = file(2, "a", "a.pdf", BookFileType.PDF);
        BookEntity promoted = book(11, goneEpub, presentPdf);
        BookEntity gone = book(12, file(3, "b", "b.epub", BookFileType.EPUB));
        LibraryScanResult scan = spy(new LibraryScanResult(List.of(onDisk("a", "a.pdf")), Set.of()));
        when(bookRepository.findAllById(List.of(11L, 12L))).thenReturn(List.of(promoted, gone));
        when(bookRepository.findAllById(List.of(12L))).thenReturn(List.of(gone));
        givenBookFoldersUnderTempDir();

        bookDeletionService.processDeletedLibraryFiles(List.of(11L, 12L), scan);

        verify(bookAdditionalFileRepository).delete(goneEpub);
        assertThat(promoted.getBookFiles()).containsExactly(presentPdf);
        verify(bookRepository).save(promoted);
        verify(bookRepository).deleteAll(List.of(gone));
        verify(notificationService).sendMessage(Topic.BOOKS_REMOVE, List.of(12L));
        // The file-name set is built once for the call, not once per book.
        verify(scan, times(1)).files();
    }

    @Test
    void processDeletedLibraryFiles_keepsFilesUnderPathsTheScanCouldNotRead() {
        BookFileEntity goneEpub = file(1, "a", "a.epub", BookFileType.EPUB);
        BookFileEntity unseenPdf = file(2, "offline/a", "a.pdf", BookFileType.PDF);
        BookEntity book = book(11, goneEpub, unseenPdf);
        LibraryScanResult scan = new LibraryScanResult(List.of(), Set.of(Path.of("/books/offline")));
        when(bookRepository.findAllById(List.of(11L))).thenReturn(List.of(book));

        bookDeletionService.processDeletedLibraryFiles(List.of(11L), scan);

        verify(bookAdditionalFileRepository).delete(goneEpub);
        verify(bookAdditionalFileRepository, never()).delete(unseenPdf);
        assertThat(book.getBookFiles()).containsExactly(unseenPdf);
        verify(bookRepository).save(book);
        verify(bookRepository, never()).deleteAll(any(Iterable.class));
    }

    @Test
    void purgeDisallowedFormats_deletesDisallowedFilesAndBooksLeftWithoutAnAllowedFormat() {
        library.setAllowedFormats(List.of(BookFileType.EPUB));
        LibraryBookFiles bookFiles = new LibraryBookFiles(List.of(
                // book 11 keeps its epub; its pdf goes
                row(1, 11, BookFileType.EPUB, true),
                row(2, 11, BookFileType.PDF, true),
                // book 12 only has a mobi (and a non-book pdf, which does not count)
                row(3, 12, BookFileType.MOBI, true),
                row(4, 12, BookFileType.PDF, false),
                // book 13 is untouched
                row(5, 13, BookFileType.EPUB, true)));
        BookEntity book12 = BookEntity.builder().id(12L).build();
        when(bookRepository.findAllById(List.of(12L))).thenReturn(List.of(book12));
        givenBookFoldersUnderTempDir();

        boolean changed = bookDeletionService.purgeDisallowedFormats(library, bookFiles);

        assertThat(changed).isTrue();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<Long>> deletedFiles = ArgumentCaptor.forClass(Collection.class);
        verify(bookAdditionalFileRepository).deleteAllByIdInBatch(deletedFiles.capture());
        assertThat(deletedFiles.getValue()).containsExactlyInAnyOrder(2L, 3L);
        verify(bookRepository).deleteAll(List.of(book12));
        verify(notificationService).sendMessage(Topic.BOOKS_REMOVE, List.of(12L));
    }

    @Test
    void purgeDisallowedFormats_changesNothingWhenEverythingIsAllowed() {
        library.setAllowedFormats(List.of(BookFileType.EPUB, BookFileType.PDF));
        LibraryBookFiles bookFiles = new LibraryBookFiles(List.of(
                row(1, 11, BookFileType.EPUB, true),
                row(2, 11, BookFileType.MOBI, false)));

        assertThat(bookDeletionService.purgeDisallowedFormats(library, bookFiles)).isFalse();

        library.setAllowedFormats(null);
        assertThat(bookDeletionService.purgeDisallowedFormats(library, bookFiles)).isFalse();

        verifyNoInteractions(bookAdditionalFileRepository, bookRepository, notificationService);
    }
}
