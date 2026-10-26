package org.booklore.service.watcher;

import org.booklore.model.entity.*;
import org.booklore.model.enums.BookFileType;
import org.booklore.model.enums.LibraryOrganizationMode;
import org.booklore.repository.BookRepository;
import org.booklore.repository.LibraryRepository;
import org.booklore.service.NotificationService;
import org.booklore.service.file.FileFingerprint;
import org.booklore.repository.projection.BookFileScanRow;
import org.booklore.repository.projection.FilelessBookRow;
import org.booklore.service.library.ExistingBookAttacher;
import org.booklore.service.library.FilelessShells;
import org.booklore.service.library.LibraryProcessingService;
import org.booklore.service.library.matching.ExistingBookMatcher.Kind;
import org.booklore.util.FileUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.MockitoAnnotations;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BookFileTransactionalHandlerTest {

    @Mock private BookFilePersistenceService bookFilePersistenceService;
    @Mock private LibraryProcessingService libraryProcessingService;
    @Mock private NotificationService notificationService;
    @Mock private LibraryRepository libraryRepository;
    @Mock private BookRepository bookRepository;
    @Mock private PendingDeletionPool pendingDeletionPool;
    @Mock private ExistingBookAttacher existingBookAttacher;

    private FilelessShells filelessShells;

    private BookFileTransactionalHandler handler;

    private AutoCloseable mocks;
    private MockedStatic<FileFingerprint> fingerprintMock;
    private MockedStatic<FileUtils> fileUtilsMock;

    private LibraryEntity library;
    private LibraryPathEntity libraryPath;

    @BeforeEach
    void setUp() {
        mocks = MockitoAnnotations.openMocks(this);
        // The real per-library index of fileless books, read through the mocked repository
        filelessShells = new FilelessShells(bookRepository);
        handler = new BookFileTransactionalHandler(
                bookFilePersistenceService, libraryProcessingService, notificationService,
                libraryRepository, bookRepository, pendingDeletionPool, filelessShells, existingBookAttacher);

        fingerprintMock = mockStatic(FileFingerprint.class);
        fileUtilsMock = mockStatic(FileUtils.class);

        fingerprintMock.when(() -> FileFingerprint.generateHash(any(Path.class))).thenReturn("hash123");
        fingerprintMock.when(() -> FileFingerprint.generateFolderHash(any(Path.class))).thenReturn("folderhash");
        fileUtilsMock.when(() -> FileUtils.getRelativeSubPath(anyString(), any(Path.class))).thenReturn("sub");
        fileUtilsMock.when(() -> FileUtils.getFileSizeInKb(any(Path.class))).thenReturn(100L);
        fileUtilsMock.when(() -> FileUtils.getFolderSizeInKb(any(Path.class))).thenReturn(500L);
        when(existingBookAttacher.attach(anyLong(), any(), anyList())).thenAnswer(inv -> inv.getArgument(2));

        libraryPath = new LibraryPathEntity();
        libraryPath.setId(1L);
        libraryPath.setPath("/library");

        library = LibraryEntity.builder()
                .id(1L)
                .name("Test Library")
                .libraryPaths(List.of(libraryPath))
                .organizationMode(LibraryOrganizationMode.AUTO_DETECT)
                .build();

        when(libraryRepository.findById(1L)).thenReturn(Optional.of(library));
        when(bookFilePersistenceService.findMatchingLibraryPath(eq(library), any(Path.class))).thenReturn("/library");
        when(bookFilePersistenceService.getLibraryPathEntityForFile(eq(library), eq("/library"))).thenReturn(libraryPath);
    }

    @AfterEach
    void tearDown() throws Exception {
        fileUtilsMock.close();
        fingerprintMock.close();
        mocks.close();
    }

    private BookEntity buildBook(Long id, boolean deleted) {
        return BookEntity.builder()
                .id(id)
                .library(library)
                .libraryPath(libraryPath)
                .deleted(deleted)
                .bookFiles(new ArrayList<>())
                .build();
    }

    private BookFileEntity buildBookFile(Long id, BookEntity book, String fileName, String hash) {
        return BookFileEntity.builder()
                .id(id)
                .book(book)
                .fileName(fileName)
                .fileSubPath("sub")
                .currentHash(hash)
                .initialHash(hash)
                .isBookFormat(true)
                .bookType(BookFileType.EPUB)
                .build();
    }

    /** The books the folder queries find for {@code folder}: every book format of theirs, as the collation might. */
    private void inFolder(String folder, BookEntity... books) {
        List<BookFileScanRow> rows = new ArrayList<>();
        for (BookEntity book : books) {
            for (BookFileEntity bf : book.getBookFiles()) {
                rows.add(new BookFileScanRow(bf.getId(), book.getId(), 1L, "/library", bf.getFileSubPath(), bf.getFileName(),
                        bf.isBookFormat(), bf.getBookType(), book.getDeleted(), bf.isFolderBased()));
            }
        }
        when(bookRepository.findBookFormatRowsInFolder(1L, folder)).thenReturn(rows);
        when(bookRepository.findBookFormatRowsOwnedByFolder(eq(1L), eq(folder), anyString(), anyString())).thenReturn(rows);
    }

    private void verifyNoFolderLookup() {
        verify(bookRepository, never()).findBookFormatRowsInFolder(anyLong(), any());
        verify(bookRepository, never()).findBookFormatRowsOwnedByFolder(anyLong(), any(), any(), any());
    }

    /** The file's sub path below the library path, as FileUtils.getRelativeSubPath gives it. */
    private void subPath(String subPath) {
        fileUtilsMock.when(() -> FileUtils.getRelativeSubPath(anyString(), any(Path.class))).thenReturn(subPath);
    }

    private FilelessBookRow shellRow(long bookId, Long libraryPathId, String title, String... authors) {
        return new FilelessBookRow(bookId, libraryPathId, false, null, null, title, null, null, null,
                authors.length == 0 ? null : authors[0]);
    }

    private void shells(FilelessBookRow... rows) {
        when(bookRepository.findFilelessBookRowsByLibraryId(1L)).thenReturn(List.of(rows));
    }

    private PendingDeletionPool.FileSnapshot fileSnap(Long id, String fileName, String hash) {
        return new PendingDeletionPool.FileSnapshot(id, fileName, "sub", hash, false, BookFileType.EPUB);
    }

    private PendingDeletionPool.BookSnapshot bookSnap(Long bookId, List<PendingDeletionPool.FileSnapshot> files) {
        return new PendingDeletionPool.BookSnapshot(bookId, 1L, "sub", files);
    }

    @SuppressWarnings("unchecked")
    private PendingDeletionPool.MatchResult matchResult(PendingDeletionPool.BookSnapshot book, PendingDeletionPool.FileSnapshot file) {
        var pending = new PendingDeletionPool.PendingDeletion(
                Path.of("/library/sub"), false, 1L, Instant.now(),
                mock(ScheduledFuture.class), new ConcurrentHashMap<>(), new ConcurrentHashMap<>());
        return new PendingDeletionPool.MatchResult(pending, book, file);
    }

    @Nested
    class HandleNewBookFile {

        @Test
        void libraryNotFound_throws() {
            when(libraryRepository.findById(99L)).thenReturn(Optional.empty());

            org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
                    () -> handler.handleNewBookFile(99L, Path.of("/library/sub/test.epub")));
        }

        @Test
        void existingAtPath_deletedBook_restoresIt() {
            BookEntity book = buildBook(10L, true);
            BookFileEntity bookFile = buildBookFile(100L, book, "test.epub", "oldhash");
            book.setBookFiles(List.of(bookFile));

            when(bookFilePersistenceService.findBookFileByLibraryPathSubPathAndFileName(1L, "sub", "test.epub"))
                    .thenReturn(Optional.of(bookFile));
            when(pendingDeletionPool.matchByHash("oldhash")).thenReturn(Optional.empty());

            handler.handleNewBookFile(1L, Path.of("/library/sub/test.epub"));

            verify(bookFilePersistenceService).save(book);
            org.assertj.core.api.Assertions.assertThat(book.getDeleted()).isFalse();
            org.assertj.core.api.Assertions.assertThat(book.getDeletedAt()).isNull();
        }

        @Test
        void existingAtPath_sameHash_skipsProcessing() {
            BookEntity book = buildBook(10L, false);
            BookFileEntity bookFile = buildBookFile(100L, book, "test.epub", "hash123");
            book.setBookFiles(List.of(bookFile));

            when(bookFilePersistenceService.findBookFileByLibraryPathSubPathAndFileName(1L, "sub", "test.epub"))
                    .thenReturn(Optional.of(bookFile));

            handler.handleNewBookFile(1L, Path.of("/library/sub/test.epub"));

            verify(bookFilePersistenceService, never()).save(any());
            verify(pendingDeletionPool).cancelByPath(any());
        }

        @Test
        void existingAtPath_hashChanged_updatesHash() {
            BookEntity book = buildBook(10L, false);
            BookFileEntity bookFile = buildBookFile(100L, book, "test.epub", "oldhash");
            book.setBookFiles(List.of(bookFile));

            when(bookFilePersistenceService.findBookFileByLibraryPathSubPathAndFileName(1L, "sub", "test.epub"))
                    .thenReturn(Optional.of(bookFile));

            handler.handleNewBookFile(1L, Path.of("/library/sub/test.epub"));

            verify(pendingDeletionPool).cancelByPath(any());
            verify(bookFilePersistenceService).save(book);
            org.assertj.core.api.Assertions.assertThat(bookFile.getCurrentHash()).isEqualTo("hash123");
        }

        @Test
        void noExistingAtPath_poolMatch_recoversBook() {
            when(bookFilePersistenceService.findBookFileByLibraryPathSubPathAndFileName(anyLong(), anyString(), anyString()))
                    .thenReturn(Optional.empty());

            var fSnap = fileSnap(100L, "test.epub", "hash123");
            var bSnap = bookSnap(10L, List.of(fSnap));
            var match = matchResult(bSnap, fSnap);
            when(pendingDeletionPool.matchByHash("hash123")).thenReturn(Optional.of(match));

            handler.handleNewBookFile(1L, Path.of("/library/sub/test.epub"));

            verify(pendingDeletionPool).recoverBook(eq(match), eq(libraryPath), eq("sub"), eq("test.epub"), eq("hash123"));
            verify(libraryProcessingService, never()).processLibraryFiles(any(), any());
        }

        @Test
        void noExistingAtPath_hashBasedMoveDetection_updatesPath() {
            when(bookFilePersistenceService.findBookFileByLibraryPathSubPathAndFileName(anyLong(), anyString(), anyString()))
                    .thenReturn(Optional.empty());
            when(pendingDeletionPool.matchByHash(anyString())).thenReturn(Optional.empty());

            BookEntity existingBook = buildBook(10L, false);
            BookFileEntity movedFile = buildBookFile(100L, existingBook, "moved.epub", "hash123");
            when(bookFilePersistenceService.findMovedFile(eq("hash123"), eq(Path.of("/library/sub/test.epub")), any()))
                    .thenReturn(Optional.of(movedFile));

            handler.handleNewBookFile(1L, Path.of("/library/sub/test.epub"));

            verify(bookFilePersistenceService).updatePathIfChanged(eq(movedFile), eq(library), eq(Path.of("/library/sub/test.epub")));
            verify(libraryProcessingService, never()).processLibraryFiles(any(), any());
        }

        @Test
        void noExistingAtPath_aCopyOfAStoredFileIsANewFile() {
            // Before, the book of the file with this hash was moved to the copy, off its file that is still there
            when(bookFilePersistenceService.findBookFileByLibraryPathSubPathAndFileName(anyLong(), anyString(), anyString()))
                    .thenReturn(Optional.empty());
            when(pendingDeletionPool.matchByHash(anyString())).thenReturn(Optional.empty());
            when(bookFilePersistenceService.findMovedFile(eq("hash123"), any(), any())).thenReturn(Optional.empty());

            handler.handleNewBookFile(1L, Path.of("/library/sub/test.epub"));

            verify(bookFilePersistenceService, never()).updatePathIfChanged(any(), any(), any());
            verify(libraryProcessingService).processLibraryFiles(anyList(), eq(library));
        }

        private void noEarlierMatch() {
            when(bookFilePersistenceService.findBookFileByLibraryPathSubPathAndFileName(anyLong(), any(), anyString()))
                    .thenReturn(Optional.empty());
            when(pendingDeletionPool.matchByHash(anyString())).thenReturn(Optional.empty());
            when(bookFilePersistenceService.findMovedFile(anyString(), any(), any())).thenReturn(Optional.empty());
        }

        @Test
        void autoDetect_attachesToTheFilelessBookWhoseTitleAndAuthorAgree() {
            // Changed: the file attached to an author-less fileless book "test" because a static mock made every
            // title score 0.90; now the title must be equal and an author must agree with the folders
            subPath("W/Weir, Andy");
            noEarlierMatch();
            shells(shellRow(10L, null, "The Martian", "Andy Weir"));

            handler.handleNewBookFile(1L, Path.of("/library/W/Weir, Andy/The Martian.epub"));

            verify(existingBookAttacher).attach(eq(10L), eq(Kind.FILELESS), argThat(files ->
                    files.size() == 1 && files.getFirst().getFileName().equals("The Martian.epub")
                            && files.getFirst().getFileSubPath().equals("W/Weir, Andy")));
            verify(libraryProcessingService, never()).processLibraryFiles(any(), any());
        }

        @Test
        void autoDetect_aFilelessBookOfAnotherAuthorOrWithoutAuthorsIsNotMatched() {
            // Production: "Assignment - Karachi" and four more went to "Assignment" by I. Christie
            subPath("A/Aarons, Edward S");
            noEarlierMatch();
            shells(shellRow(10L, null, "Assignment", "I. Christie"), shellRow(11L, null, "Assignment - Karachi"));

            handler.handleNewBookFile(1L, Path.of("/library/A/Aarons, Edward S/Assignment - Karachi.epub"));

            verifyNoInteractions(existingBookAttacher);
            verify(libraryProcessingService).processLibraryFiles(anyList(), eq(library));
        }

        @Test
        void autoDetect_theFilelessBooksAreReadOncePerLibraryAndAClaimedOneIsNotOfferedAgain() {
            // Before, every event read every fileless book of the library, and lazily its metadata
            noEarlierMatch();
            shells(shellRow(10L, null, "Survival", "Ann Smith"));
            when(existingBookAttacher.attach(anyLong(), any(), anyList())).thenAnswer(inv -> {
                filelessShells.notFileless(inv.getArgument(0));
                return inv.getArgument(2);
            });

            subPath("S/Smith, Ann");
            handler.handleNewBookFile(1L, Path.of("/library/S/Smith, Ann/Survival.epub"));
            subPath("S/Smith, Ann/Series");
            handler.handleNewBookFile(1L, Path.of("/library/S/Smith, Ann/Series/Survival.epub"));

            // The watcher takes files one at a time: the first folder wins, and the second file is a new book
            verify(existingBookAttacher, times(1)).attach(eq(10L), eq(Kind.FILELESS), anyList());
            verify(libraryProcessingService, times(1)).processLibraryFiles(argThat(files ->
                    files.getFirst().getFileSubPath().equals("S/Smith, Ann/Series")), eq(library));
            verify(bookRepository, times(1)).findFilelessBookRowsByLibraryId(1L);
        }

        @Test
        void autoDetect_alikeFilelessBooksTakeTheFilesOfTwoFoldersOneEach() {
            // Intended: the book the first file went to leaves the index, so the next alike file takes the next book
            noEarlierMatch();
            shells(shellRow(11L, null, "Survival", "Ann Smith"), shellRow(10L, null, "Survival", "Ann Smith"));
            when(existingBookAttacher.attach(anyLong(), any(), anyList())).thenAnswer(inv -> {
                filelessShells.notFileless(inv.getArgument(0));
                return inv.getArgument(2);
            });

            subPath("S/Smith, Ann");
            handler.handleNewBookFile(1L, Path.of("/library/S/Smith, Ann/Survival.epub"));
            subPath("S/Smith, Ann/Series");
            handler.handleNewBookFile(1L, Path.of("/library/S/Smith, Ann/Series/Survival.epub"));

            InOrder inOrder = inOrder(existingBookAttacher);
            inOrder.verify(existingBookAttacher).attach(eq(10L), eq(Kind.FILELESS), argThat(files ->
                    files.getFirst().getFileSubPath().equals("S/Smith, Ann")));
            inOrder.verify(existingBookAttacher).attach(eq(11L), eq(Kind.FILELESS), argThat(files ->
                    files.getFirst().getFileSubPath().equals("S/Smith, Ann/Series")));
            verify(libraryProcessingService, never()).processLibraryFiles(any(), any());
        }

        @Test
        void autoDetect_aSecondDownloadAttachesToTheFilelessBookOfItsTitle() {
            subPath("A/Kate Atkinson");
            noEarlierMatch();
            shells(shellRow(10L, null, "Started Early, Took My Dog", "Kate Atkinson"));

            handler.handleNewBookFile(1L, Path.of("/library/A/Kate Atkinson/Started Early, Took My Dog (1).epub"));

            verify(existingBookAttacher).attach(eq(10L), eq(Kind.FILELESS), argThat(files ->
                    files.getFirst().getFileName().equals("Started Early, Took My Dog (1).epub")));
            verify(libraryProcessingService, never()).processLibraryFiles(any(), any());
        }

        @Test
        void autoDetect_aWeakMatchAttachesAsAnyOther() {
            // Only " (1)" read as a copy makes "MEG (1).epub" the "MEG": a weak match. A rescan that also found
            // "A/Steve Alten/MEG/The MEG.epub" would give the book to that one; here there is only this file
            subPath("A/Steve Alten");
            noEarlierMatch();
            shells(shellRow(70L, null, "MEG", "Steve Alten"));

            handler.handleNewBookFile(1L, Path.of("/library/A/Steve Alten/MEG (1).epub"));

            verify(existingBookAttacher).attach(eq(70L), eq(Kind.FILELESS), argThat(files ->
                    files.getFirst().getFileName().equals("MEG (1).epub")));
            verify(libraryProcessingService, never()).processLibraryFiles(any(), any());
        }

        @Test
        void autoDetect_aBookTheNameReadsAsGoesBeforeOneOfTheNameReadAsACopy() {
            // "Nico (1).epub" is "Nico (Book 1)", as before the copy reading, and not ambiguous with "Nico"
            subPath("W/Naomi West");
            noEarlierMatch();
            shells(shellRow(2L, null, "Nico", "Naomi West"), shellRow(1L, null, "Nico (Book 1)", "Naomi West"));

            handler.handleNewBookFile(1L, Path.of("/library/W/Naomi West/Nico (1).epub"));

            verify(existingBookAttacher, times(1)).attach(anyLong(), any(), anyList());
            verify(existingBookAttacher).attach(eq(1L), eq(Kind.FILELESS), argThat(files ->
                    files.getFirst().getFileName().equals("Nico (1).epub")));
            verify(libraryProcessingService, never()).processLibraryFiles(any(), any());
        }

        @Test
        void autoDetect_aSecondDownloadIsNotTheBookWhoseSubtitleNamesAnotherVolume() {
            // "Saga (1).epub" may be volume 1, and the book says it is volume 2: the real volume 2 still finds it
            subPath("V/Vaughan, Brian K.");
            noEarlierMatch();
            shells(new FilelessBookRow(8L, null, false, null, null, "Saga", "Volume Two", null, null, "Brian K. Vaughan"));
            when(existingBookAttacher.attach(anyLong(), any(), anyList())).thenAnswer(inv -> {
                filelessShells.notFileless(inv.getArgument(0));
                return inv.getArgument(2);
            });

            handler.handleNewBookFile(1L, Path.of("/library/V/Vaughan, Brian K./Saga (1).epub"));
            handler.handleNewBookFile(1L, Path.of("/library/V/Vaughan, Brian K./Saga (2).epub"));

            verify(existingBookAttacher, times(1)).attach(anyLong(), any(), anyList());
            verify(existingBookAttacher).attach(eq(8L), eq(Kind.FILELESS), argThat(files ->
                    files.getFirst().getFileName().equals("Saga (2).epub")));
            verify(libraryProcessingService, times(1)).processLibraryFiles(argThat(files ->
                    files.getFirst().getFileName().equals("Saga (1).epub")), eq(library));
        }

        @Test
        void autoDetect_aBookThatChangedSinceItWasReadMakesTheFileANewBook() {
            subPath("W/Weir, Andy");
            noEarlierMatch();
            shells(shellRow(10L, null, "The Martian", "Andy Weir"));
            when(existingBookAttacher.attach(anyLong(), any(), anyList())).thenReturn(List.of());

            handler.handleNewBookFile(1L, Path.of("/library/W/Weir, Andy/The Martian.epub"));

            verify(libraryProcessingService).processLibraryFiles(anyList(), eq(library));
        }

        @Test
        void autoDetect_noFilelessMatch_matchesExistingBookByTitle() {
            noEarlierMatch();
            BookEntity existingBook = buildBook(20L, false);
            BookFileEntity primaryFile = buildBookFile(200L, existingBook, "test.epub", "otherhash");
            existingBook.setBookFiles(new ArrayList<>(List.of(primaryFile)));
            inFolder("sub", existingBook);

            handler.handleNewBookFile(1L, Path.of("/library/sub/test.pdf"));

            verify(existingBookAttacher).attach(eq(20L), eq(Kind.SAME_FOLDER), anyList());
            verify(libraryProcessingService, never()).processLibraryFiles(any(), any());
        }

        @Test
        void autoDetect_noMatch_createsNewBook() {
            noEarlierMatch();

            handler.handleNewBookFile(1L, Path.of("/library/sub/test.epub"));

            verify(libraryProcessingService).processLibraryFiles(anyList(), eq(library));
            verifyNoInteractions(existingBookAttacher);
        }

        @Test
        void bookPerFile_neverAttachesToBooksWithFiles() {
            library.setOrganizationMode(LibraryOrganizationMode.BOOK_PER_FILE);
            noEarlierMatch();

            handler.handleNewBookFile(1L, Path.of("/library/sub/test.epub"));

            verifyNoFolderLookup();
            verify(libraryProcessingService).processLibraryFiles(anyList(), eq(library));
        }

        @Test
        void bookPerFile_matchesAFilelessBookByTitleAndAuthor() {
            library.setOrganizationMode(LibraryOrganizationMode.BOOK_PER_FILE);
            subPath("Frank Herbert");
            noEarlierMatch();
            shells(shellRow(10L, 1L, "Dune", "Frank Herbert"), shellRow(11L, 1L, "different title", "Frank Herbert"));

            handler.handleNewBookFile(1L, Path.of("/library/Frank Herbert/Dune.epub"));

            verify(existingBookAttacher).attach(eq(10L), eq(Kind.FILELESS), anyList());
            verifyNoFolderLookup();
        }

        @Test
        void bookPerFolder_theFormatsOfARootFileGoToAlikeFilelessBooksOneEach() {
            // Intended: a file at the library's root is a book of its own, so another format takes the next alike book
            library.setOrganizationMode(LibraryOrganizationMode.BOOK_PER_FOLDER);
            subPath("");
            twoFormatsAgainstAlikeFilelessBooks("/library/");
        }

        @Test
        void bookPerFile_theFormatsOfAFileGoToAlikeFilelessBooksOneEach() {
            library.setOrganizationMode(LibraryOrganizationMode.BOOK_PER_FILE);
            subPath("W/Weir, Andy");
            twoFormatsAgainstAlikeFilelessBooks("/library/W/Weir, Andy/");
            verifyNoFolderLookup();
        }

        private void twoFormatsAgainstAlikeFilelessBooks(String folder) {
            noEarlierMatch();
            shells(shellRow(12L, 1L, "The Martian", "Andy Weir"), shellRow(40L, 1L, "The Martian", "Andy Weir"));
            when(existingBookAttacher.attach(anyLong(), any(), anyList())).thenAnswer(inv -> {
                filelessShells.notFileless(inv.getArgument(0));
                return inv.getArgument(2);
            });

            handler.handleNewBookFile(1L, Path.of(folder + "The Martian - Andy Weir.epub"));
            handler.handleNewBookFile(1L, Path.of(folder + "The Martian - Andy Weir.pdf"));

            InOrder inOrder = inOrder(existingBookAttacher);
            inOrder.verify(existingBookAttacher).attach(eq(12L), eq(Kind.FILELESS), argThat(files ->
                    files.getFirst().getFileName().equals("The Martian - Andy Weir.epub")));
            inOrder.verify(existingBookAttacher).attach(eq(40L), eq(Kind.FILELESS), argThat(files ->
                    files.getFirst().getFileName().equals("The Martian - Andy Weir.pdf")));
            verify(libraryProcessingService, never()).processLibraryFiles(any(), any());
        }

        @Test
        void bookPerFolder_attachesToBookInSameFolder() {
            library.setOrganizationMode(LibraryOrganizationMode.BOOK_PER_FOLDER);
            noEarlierMatch();

            BookEntity existingBook = buildBook(20L, false);
            BookFileEntity primaryFile = buildBookFile(200L, existingBook, "existing.epub", "otherhash");
            existingBook.setBookFiles(new ArrayList<>(List.of(primaryFile)));
            inFolder("sub", existingBook);

            handler.handleNewBookFile(1L, Path.of("/library/sub/new.pdf"));

            verify(existingBookAttacher).attach(eq(20L), eq(Kind.SAME_FOLDER), anyList());
            verify(libraryProcessingService, never()).processLibraryFiles(any(), any());
        }

        @Test
        void bookPerFolder_aBookWhoseFolderOnlyComparesEqualIsNotTheBookOfTheFolder() {
            // The database finds 'sub' through its collation, which ignores case: this book's files are in 'Sub'
            library.setOrganizationMode(LibraryOrganizationMode.BOOK_PER_FOLDER);
            noEarlierMatch();
            BookEntity otherFolder = buildBook(20L, false);
            BookFileEntity file = buildBookFile(200L, otherFolder, "existing.epub", "otherhash");
            file.setFileSubPath("Sub");
            otherFolder.setBookFiles(new ArrayList<>(List.of(file)));
            inFolder("sub", otherFolder);

            handler.handleNewBookFile(1L, Path.of("/library/sub/new.pdf"));

            verifyNoInteractions(existingBookAttacher);
            verify(libraryProcessingService).processLibraryFiles(anyList(), eq(library));
        }

        @Test
        void autoDetect_aBookWhoseFolderOnlyComparesEqualIsNotInTheFilesFolder() {
            noEarlierMatch();
            BookEntity otherFolder = buildBook(20L, false);
            BookFileEntity file = buildBookFile(200L, otherFolder, "test.epub", "otherhash");
            file.setFileSubPath("SUB");
            otherFolder.setBookFiles(new ArrayList<>(List.of(file)));
            inFolder("sub", otherFolder);

            handler.handleNewBookFile(1L, Path.of("/library/sub/test.pdf"));

            verifyNoInteractions(existingBookAttacher);
            verify(libraryProcessingService).processLibraryFiles(anyList(), eq(library));
        }

        @Test
        void bookPerFolder_noBookInFolder_createsNew() {
            library.setOrganizationMode(LibraryOrganizationMode.BOOK_PER_FOLDER);
            noEarlierMatch();
            inFolder("sub");

            handler.handleNewBookFile(1L, Path.of("/library/sub/test.epub"));

            verify(libraryProcessingService).processLibraryFiles(anyList(), eq(library));
        }

        @Test
        void bookPerFolder_audiobook_findsNearestAncestorWithEbook() {
            library.setOrganizationMode(LibraryOrganizationMode.BOOK_PER_FOLDER);
            subPath("author/book/audio");
            noEarlierMatch();
            inFolder("author/book/audio");

            BookEntity parentBook = buildBook(30L, false);
            BookFileEntity ebookFile = buildBookFile(300L, parentBook, "book.epub", "ebookhash");
            ebookFile.setBookType(BookFileType.EPUB);
            ebookFile.setFileSubPath("author/book");
            parentBook.setBookFiles(new ArrayList<>(List.of(ebookFile)));
            inFolder("author/book", parentBook);

            handler.handleNewBookFile(1L, Path.of("/library/author/book/audio/chapter1.m4b"));

            verify(existingBookAttacher).attach(eq(30L), eq(Kind.SAME_FOLDER), anyList());
        }

        @Test
        void bookPerFolder_audiobookBelowTwoBooksWithEbooksAttachesToNeither() {
            library.setOrganizationMode(LibraryOrganizationMode.BOOK_PER_FOLDER);
            subPath("author/series/audio");
            noEarlierMatch();
            BookEntity one = buildBook(30L, false);
            BookFileEntity oneEpub = buildBookFile(300L, one, "one.epub", "h1");
            oneEpub.setFileSubPath("author/series");
            one.setBookFiles(new ArrayList<>(List.of(oneEpub)));
            BookEntity two = buildBook(31L, false);
            BookFileEntity twoEpub = buildBookFile(310L, two, "two.epub", "h2");
            twoEpub.setFileSubPath("author/series");
            two.setBookFiles(new ArrayList<>(List.of(twoEpub)));
            inFolder("author/series", one, two);

            handler.handleNewBookFile(1L, Path.of("/library/author/series/audio/two.m4b"));

            verifyNoInteractions(existingBookAttacher);
            verify(libraryProcessingService).processLibraryFiles(anyList(), eq(library));
        }

        @Test
        void bookPerFolder_aFileAtTheRootIsABookOfItsOwn() {
            library.setOrganizationMode(LibraryOrganizationMode.BOOK_PER_FOLDER);
            subPath("");
            noEarlierMatch();
            BookEntity dune = buildBook(30L, false);
            BookFileEntity duneEpub = buildBookFile(300L, dune, "Dune.epub", "h1");
            duneEpub.setFileSubPath("");
            dune.setBookFiles(new ArrayList<>(List.of(duneEpub)));
            inFolder("", dune);

            handler.handleNewBookFile(1L, Path.of("/library/Neuromancer.epub"));

            verifyNoInteractions(existingBookAttacher);
            verify(libraryProcessingService).processLibraryFiles(anyList(), eq(library));
        }

        @Test
        void autoDetect_aFileTwoBooksOfItsFolderReadAsIsNotGivenToAFilelessBookEither() {
            subPath("H/Herbert, Frank");
            noEarlierMatch();
            BookEntity epub = buildBook(20L, false);
            BookFileEntity epubFile = buildBookFile(200L, epub, "Dune.epub", "h1");
            epubFile.setFileSubPath("H/Herbert, Frank");
            epub.setBookFiles(new ArrayList<>(List.of(epubFile)));
            BookEntity mobi = buildBook(21L, false);
            BookFileEntity mobiFile = buildBookFile(201L, mobi, "Dune.mobi", "h2");
            mobiFile.setFileSubPath("H/Herbert, Frank");
            mobi.setBookFiles(new ArrayList<>(List.of(mobiFile)));
            inFolder("H/Herbert, Frank", epub, mobi);
            shells(shellRow(10L, null, "Dune", "Frank Herbert"));

            handler.handleNewBookFile(1L, Path.of("/library/H/Herbert, Frank/Dune.pdf"));

            verifyNoInteractions(existingBookAttacher);
            verify(bookRepository, never()).findFilelessBookRowsByLibraryId(anyLong());
            verify(libraryProcessingService).processLibraryFiles(anyList(), eq(library));
        }

        @Test
        void autoDetect_theFoldersBooksAreReadInTwoQueriesWhateverTheirNumber() {
            // Before, each book of the folder cost a query for its files, its metadata and its authors
            noEarlierMatch();
            List<BookEntity> books = new ArrayList<>();
            for (long id = 1; id <= 50; id++) {
                BookEntity book = buildBook(id, false);
                book.setBookFiles(new ArrayList<>(List.of(buildBookFile(id * 10, book, "Book " + id + ".epub", "h" + id))));
                books.add(book);
            }
            inFolder("sub", books.toArray(BookEntity[]::new));

            handler.handleNewBookFile(1L, Path.of("/library/sub/Book 7.pdf"));

            verify(existingBookAttacher).attach(eq(7L), eq(Kind.SAME_FOLDER), anyList());
            verify(bookRepository).findBookFormatRowsInFolder(1L, "sub");
            verify(bookRepository).findAuthorNamesByBookIds(argThat(ids -> ids.size() == 50));
        }

        @Test
        void autoDetect_filelessBook_wrongLibraryPath_skipped() {
            subPath("W/Weir, Andy");
            noEarlierMatch();
            shells(shellRow(10L, 99L, "The Martian", "Andy Weir"));

            handler.handleNewBookFile(1L, Path.of("/library/W/Weir, Andy/The Martian.epub"));

            verifyNoInteractions(existingBookAttacher);
            verify(libraryProcessingService).processLibraryFiles(anyList(), eq(library));
        }

        @Test
        void autoDetect_attachesToTheBookOfTheSameTitleAmongSeveral() {
            // Changed: the static mock of the similarity score is gone; the names decide
            noEarlierMatch();

            BookEntity book1 = buildBook(20L, false);
            BookFileEntity file1 = buildBookFile(200L, book1, "mybook.epub", "h1");
            book1.setBookFiles(new ArrayList<>(List.of(file1)));

            BookEntity book2 = buildBook(21L, false);
            BookFileEntity file2 = buildBookFile(201L, book2, "otherbook.epub", "h2");
            book2.setBookFiles(new ArrayList<>(List.of(file2)));

            inFolder("sub", book1, book2);

            handler.handleNewBookFile(1L, Path.of("/library/sub/mybook.pdf"));

            verify(existingBookAttacher).attach(eq(20L), eq(Kind.SAME_FOLDER), anyList());
        }

        @Test
        void autoDetect_anotherVolumeInTheFolderIsANewBook() {
            // Production: "A Dangerous Universe 3" went to the book of "A Dangerous Universe 5.epub" at 0.95
            noEarlierMatch();
            BookEntity five = buildBook(20L, false);
            five.setBookFiles(new ArrayList<>(List.of(buildBookFile(200L, five, "A Dangerous Universe 5.epub", "h1"))));
            inFolder("sub", five);

            handler.handleNewBookFile(1L, Path.of("/library/sub/A Dangerous Universe 3.epub"));

            verifyNoInteractions(existingBookAttacher);
            verify(libraryProcessingService).processLibraryFiles(anyList(), eq(library));
        }

        @Test
        void nullSubPath_autoDetect_createsNewBook() {
            fileUtilsMock.when(() -> FileUtils.getRelativeSubPath(anyString(), any(Path.class))).thenReturn(null);
            noEarlierMatch();

            handler.handleNewBookFile(1L, Path.of("/library/test.epub"));

            verifyNoFolderLookup();
            verify(libraryProcessingService).processLibraryFiles(anyList(), eq(library));
        }

        @Test
        void bookPerFolder_severalBooksInTheFolderAttachToNone() {
            // Changed: the book with the most files took the new file; with several books there, that is a guess
            library.setOrganizationMode(LibraryOrganizationMode.BOOK_PER_FOLDER);
            noEarlierMatch();

            BookEntity smallBook = buildBook(20L, false);
            BookFileEntity f1 = buildBookFile(200L, smallBook, "a.epub", "h1");
            smallBook.setBookFiles(new ArrayList<>(List.of(f1)));

            BookEntity bigBook = buildBook(21L, false);
            BookFileEntity f2 = buildBookFile(201L, bigBook, "b.epub", "h2");
            BookFileEntity f3 = buildBookFile(202L, bigBook, "b.pdf", "h3");
            bigBook.setBookFiles(new ArrayList<>(List.of(f2, f3)));

            inFolder("sub", smallBook, bigBook);

            handler.handleNewBookFile(1L, Path.of("/library/sub/new.mobi"));

            verifyNoInteractions(existingBookAttacher);
            verify(libraryProcessingService).processLibraryFiles(anyList(), eq(library));
        }

        @Test
        void existingAtPath_deletedBook_matchesPendingDeletionHash() {
            BookEntity book = buildBook(10L, true);
            BookFileEntity bookFile = buildBookFile(100L, book, "test.epub", "oldhash");
            book.setBookFiles(List.of(bookFile));

            when(bookFilePersistenceService.findBookFileByLibraryPathSubPathAndFileName(1L, "sub", "test.epub"))
                    .thenReturn(Optional.of(bookFile));
            when(pendingDeletionPool.matchByHash("oldhash")).thenReturn(Optional.empty());

            handler.handleNewBookFile(1L, Path.of("/library/sub/test.epub"));

            verify(pendingDeletionPool).matchByHash("oldhash");
            verify(bookFilePersistenceService).save(book);
        }

        @Test
        void nullOrganizationMode_defaultsToAutoDetect() {
            library.setOrganizationMode(null);
            noEarlierMatch();

            handler.handleNewBookFile(1L, Path.of("/library/sub/test.epub"));

            // AUTO_DETECT: the folder's books, then the fileless books
            verify(bookRepository).findBookFormatRowsInFolder(1L, "sub");
            verify(bookRepository).findFilelessBookRowsByLibraryId(1L);
        }

        @Test
        void autoDetect_deletedBookInDirectory_skipped() {
            noEarlierMatch();

            BookEntity deletedBook = buildBook(20L, true);
            BookFileEntity file = buildBookFile(200L, deletedBook, "test.epub", "h1");
            deletedBook.setBookFiles(new ArrayList<>(List.of(file)));
            inFolder("sub", deletedBook);

            handler.handleNewBookFile(1L, Path.of("/library/sub/test.pdf"));

            verify(libraryProcessingService).processLibraryFiles(anyList(), eq(library));
        }
    }

    @Nested
    class HandleNewFolderAudiobook {

        @Test
        void libraryNotFound_throws() {
            when(libraryRepository.findById(99L)).thenReturn(Optional.empty());

            org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
                    () -> handler.handleNewFolderAudiobook(99L, Path.of("/library/audiobook")));
        }

        @Test
        void poolMatch_recoversBook() {
            var fSnap = fileSnap(100L, "audiobook", "folderhash");
            var bSnap = bookSnap(10L, List.of(fSnap));
            var match = matchResult(bSnap, fSnap);
            when(pendingDeletionPool.matchByHash("folderhash")).thenReturn(Optional.of(match));

            handler.handleNewFolderAudiobook(1L, Path.of("/library/sub/audiobook"));

            verify(pendingDeletionPool).recoverBook(eq(match), eq(libraryPath), eq("sub"), eq("audiobook"), eq("folderhash"));
            verify(libraryProcessingService, never()).processLibraryFiles(any(), any());
        }

        @Test
        void matchingBook_autoAttachesFolderAudiobook() {
            // Changed: the folder audiobook is compared with the books of the folder that holds it, as a rescan
            // does, not with those of the folder above that, and by its name, not through a static mock of the key
            when(pendingDeletionPool.matchByHash(anyString())).thenReturn(Optional.empty());

            BookEntity existingBook = buildBook(20L, false);
            BookFileEntity primaryFile = buildBookFile(200L, existingBook, "audiobook.epub", "h1");
            existingBook.setBookFiles(new ArrayList<>(List.of(primaryFile)));
            inFolder("sub", existingBook);

            handler.handleNewFolderAudiobook(1L, Path.of("/library/sub/audiobook"));

            verify(existingBookAttacher).attach(eq(20L), eq(Kind.SAME_FOLDER), argThat(files -> files.size() == 1
                    && files.getFirst().isFolderBased() && files.getFirst().getBookFileType() == BookFileType.AUDIOBOOK
                    && files.getFirst().getFileName().equals("audiobook")));
            verify(libraryProcessingService, never()).processLibraryFiles(any(), any());
        }

        @Test
        void bookPerFolder_aFolderAudiobookIsNotTheBookOfTheFolderAudiobookNextToIt() {
            // "sub/Book One/" is a folder audiobook already: its row is in "sub", named "Book One"
            library.setOrganizationMode(LibraryOrganizationMode.BOOK_PER_FOLDER);
            when(pendingDeletionPool.matchByHash(anyString())).thenReturn(Optional.empty());
            BookEntity bookOne = buildBook(20L, false);
            BookFileEntity folder = buildBookFile(200L, bookOne, "Book One", "h1");
            folder.setBookType(BookFileType.AUDIOBOOK);
            folder.setFolderBased(true);
            bookOne.setBookFiles(new ArrayList<>(List.of(folder)));
            inFolder("sub", bookOne);

            handler.handleNewFolderAudiobook(1L, Path.of("/library/sub/Book Two"));

            verifyNoInteractions(existingBookAttacher);
            verify(libraryProcessingService).processLibraryFiles(anyList(), eq(library));
        }

        @Test
        void bookPerFolder_aFileInAFolderAudiobooksFolderIsThatBooks() {
            library.setOrganizationMode(LibraryOrganizationMode.BOOK_PER_FOLDER);
            subPath("sub/Book One");
            when(bookFilePersistenceService.findBookFileByLibraryPathSubPathAndFileName(anyLong(), any(), anyString()))
                    .thenReturn(Optional.empty());
            when(pendingDeletionPool.matchByHash(anyString())).thenReturn(Optional.empty());
            when(bookFilePersistenceService.findMovedFile(anyString(), any(), any())).thenReturn(Optional.empty());
            BookEntity bookOne = buildBook(20L, false);
            BookFileEntity folder = buildBookFile(200L, bookOne, "Book One", "h1");
            folder.setBookType(BookFileType.AUDIOBOOK);
            folder.setFolderBased(true);
            bookOne.setBookFiles(new ArrayList<>(List.of(folder)));
            inFolder("sub/Book One", bookOne);

            handler.handleNewBookFile(1L, Path.of("/library/sub/Book One/Book One.epub"));

            verify(existingBookAttacher).attach(eq(20L), eq(Kind.SAME_FOLDER), anyList());
            verify(bookRepository).findBookFormatRowsOwnedByFolder(1L, "sub/Book One", "sub", "Book One");
        }

        @Test
        void aFolderAudiobookCanBeTheFileOfAFilelessBook() {
            subPath("W/Weir, Andy");
            when(pendingDeletionPool.matchByHash(anyString())).thenReturn(Optional.empty());
            shells(shellRow(10L, null, "The Martian", "Andy Weir"));

            handler.handleNewFolderAudiobook(1L, Path.of("/library/W/Weir, Andy/The Martian"));

            verify(existingBookAttacher).attach(eq(10L), eq(Kind.FILELESS), anyList());
        }

        @Test
        void aStoredFolderAudiobookGoneFromItsFolderMovesToTheNewFolder() {
            when(pendingDeletionPool.matchByHash(anyString())).thenReturn(Optional.empty());
            BookEntity book = buildBook(20L, false);
            BookFileEntity folder = buildBookFile(200L, book, "audiobook", "folderhash");
            folder.setFolderBased(true);
            when(bookFilePersistenceService.findMovedFile(eq("folderhash"), eq(Path.of("/library/sub/audiobook")), any()))
                    .thenReturn(Optional.of(folder));

            handler.handleNewFolderAudiobook(1L, Path.of("/library/sub/audiobook"));

            verify(bookFilePersistenceService).updatePathIfChanged(folder, library, Path.of("/library/sub/audiobook"));
            verify(libraryProcessingService, never()).processLibraryFiles(any(), any());
            verifyNoInteractions(existingBookAttacher);
        }

        @Test
        void aCopyOfAStoredFolderAudiobookStillThereIsLeftAloneToWait() {
            when(pendingDeletionPool.matchByHash(anyString())).thenReturn(Optional.empty());
            when(bookFilePersistenceService.findMovedFile(anyString(), any(), any())).thenReturn(Optional.empty());
            when(bookFilePersistenceService.findBookCopied(eq("folderhash"), any())).thenReturn(Optional.of(20L));

            org.assertj.core.api.Assertions.assertThat(handler.handleNewFolderAudiobookUnlessCopy(1L, Path.of("/library/sub/audiobook")))
                    .contains(new BookFileTransactionalHandler.CopyOfAStoredFile(20L, "folderhash"));

            verify(libraryProcessingService, never()).processLibraryFiles(any(), any());
            verifyNoInteractions(existingBookAttacher);
        }

        @Test
        void aFolderAudiobookStoredAtThisVeryFolderIsNeitherMovedHereNorACopyOfItself() {
            // What an overflow re-walk sends for the folder of a stored folder audiobook
            when(pendingDeletionPool.matchByHash(anyString())).thenReturn(Optional.empty());
            when(bookFilePersistenceService.isStoredByALiveBook(library, Path.of("/library/sub/audiobook"))).thenReturn(true);
            when(bookFilePersistenceService.findBookCopied(eq("folderhash"), any())).thenReturn(Optional.of(20L));

            org.assertj.core.api.Assertions.assertThat(handler.handleNewFolderAudiobookUnlessCopy(1L, Path.of("/library/sub/audiobook")))
                    .isEmpty();

            verify(bookFilePersistenceService, never()).findMovedFile(any(), any(), any());
            verify(bookFilePersistenceService, never()).findBookCopied(any(), any());
        }

        @Test
        void aCopyOfAStoredFolderAudiobookIsANewOneOnceItWaited() {
            when(pendingDeletionPool.matchByHash(anyString())).thenReturn(Optional.empty());
            when(bookFilePersistenceService.findMovedFile(anyString(), any(), any())).thenReturn(Optional.empty());
            when(bookFilePersistenceService.findBookCopied(eq("folderhash"), any())).thenReturn(Optional.of(20L));

            handler.handleNewFolderAudiobook(1L, Path.of("/library/sub/audiobook"));

            verify(bookFilePersistenceService, never()).findBookCopied(any(), any());
            verify(libraryProcessingService).processLibraryFiles(argThat(files -> files.size() == 1
                    && files.getFirst().isFolderBased()), eq(library));
        }

        @Test
        void noMatch_createsNewFolderAudiobook() {
            when(pendingDeletionPool.matchByHash(anyString())).thenReturn(Optional.empty());

            handler.handleNewFolderAudiobook(1L, Path.of("/library/sub/audiobook"));

            verify(libraryProcessingService).processLibraryFiles(argThat(files ->
                    files.size() == 1 && files.getFirst().isFolderBased() && files.getFirst().getBookFileType() == BookFileType.AUDIOBOOK), eq(library));
        }

        @Test
        void deletedBookInParent_notMatchedForFolderAudiobook() {
            when(pendingDeletionPool.matchByHash(anyString())).thenReturn(Optional.empty());

            BookEntity deletedBook = buildBook(20L, true);
            BookFileEntity file = buildBookFile(200L, deletedBook, "audiobook.epub", "h1");
            deletedBook.setBookFiles(new ArrayList<>(List.of(file)));
            inFolder("sub", deletedBook);

            handler.handleNewFolderAudiobook(1L, Path.of("/library/sub/audiobook"));

            verify(libraryProcessingService).processLibraryFiles(anyList(), eq(library));
        }
    }
}
