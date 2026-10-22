package org.booklore.service.watcher;

import org.booklore.mapper.BookMapper;
import org.booklore.model.entity.*;
import org.booklore.model.enums.BookFileType;
import org.booklore.repository.BookFileRepository;
import org.booklore.repository.BookRepository;
import org.booklore.service.NotificationService;
import org.booklore.util.FileUtils;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.MockitoAnnotations;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.booklore.service.watcher.PendingDeletionPool.BookSnapshot;
import org.booklore.service.watcher.PendingDeletionPool.FileSnapshot;

class BookFilePersistenceServiceTest {

    @Mock private EntityManager entityManager;
    @Mock private BookRepository bookRepository;
    @Mock private BookFileRepository bookFileRepository;
    @Mock private NotificationService notificationService;
    @Mock private BookMapper bookMapper;

    private BookFilePersistenceService service;

    private AutoCloseable mocks;
    private MockedStatic<FileUtils> fileUtilsMock;

    private LibraryEntity library;
    private LibraryPathEntity libraryPath;

    @BeforeEach
    void setUp() {
        mocks = MockitoAnnotations.openMocks(this);
        service = new BookFilePersistenceService(entityManager, bookRepository, bookFileRepository, notificationService, bookMapper);

        fileUtilsMock = mockStatic(FileUtils.class);
        fileUtilsMock.when(() -> FileUtils.getRelativeSubPath(anyString(), any(Path.class))).thenReturn("sub");

        libraryPath = new LibraryPathEntity();
        libraryPath.setId(1L);
        libraryPath.setPath("/library");

        library = LibraryEntity.builder()
                .id(1L)
                .name("Test Library")
                .libraryPaths(List.of(libraryPath))
                .build();
    }

    @AfterEach
    void tearDown() throws Exception {
        fileUtilsMock.close();
        mocks.close();
    }

    private BookEntity buildBook(Long id) {
        return BookEntity.builder()
                .id(id)
                .library(library)
                .libraryPath(libraryPath)
                .deleted(false)
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
                .isBookFormat(true)
                .bookType(BookFileType.EPUB)
                .build();
    }

    @Nested
    class FindMatchingLibraryPath {

        @Test
        void matchesFileUnderLibraryPath() {
            String result = service.findMatchingLibraryPath(library, Path.of("/library/sub/test.epub"));

            assertThat(result).isEqualTo("/library");
        }

        @Test
        void throwsWhenNoMatchingPath() {
            assertThatThrownBy(() -> service.findMatchingLibraryPath(library, Path.of("/other/test.epub")))
                    .isInstanceOf(Exception.class);
        }

        @Test
        void matchesDeeperNestedFile() {
            String result = service.findMatchingLibraryPath(library, Path.of("/library/a/b/c/test.epub"));

            assertThat(result).isEqualTo("/library");
        }
    }

    @Nested
    class GetLibraryPathEntityForFile {

        @Test
        void returnsMatchingPathEntity() {
            LibraryPathEntity result = service.getLibraryPathEntityForFile(library, "/library/sub/test.epub");

            assertThat(result).isEqualTo(libraryPath);
        }

        @Test
        void throwsWhenNoMatchingPathEntity() {
            assertThatThrownBy(() -> service.getLibraryPathEntityForFile(library, "/other/test.epub"))
                    .isInstanceOf(Exception.class);
        }

        @Test
        void picksLongestMatchWhenMultiplePaths() {
            LibraryPathEntity deeperPath = new LibraryPathEntity();
            deeperPath.setId(2L);
            deeperPath.setPath("/library/sub");
            library.setLibraryPaths(List.of(libraryPath, deeperPath));

            LibraryPathEntity result = service.getLibraryPathEntityForFile(library, "/library/sub/test.epub");

            assertThat(result).isEqualTo(deeperPath);
        }
    }

    @Nested
    class FindMovedFile {

        private static final Instant CUTOFF = Instant.now().minusSeconds(60);
        private static final Path NEW_PATH = Path.of("/library/new/Title.epub");

        @TempDir
        Path root;

        private LibraryPathEntity onDisk;

        @BeforeEach
        void libraryOnDisk() {
            onDisk = new LibraryPathEntity();
            onDisk.setId(5L);
            onDisk.setPath(root.toString());
        }

        /** A book's file with the hash in folder 'old', present on disk or not. */
        private BookFileEntity stored(long bookId, String name, boolean present, boolean deleted) throws IOException {
            BookEntity book = buildBook(bookId);
            book.setLibraryPath(onDisk);
            book.setDeleted(deleted);
            BookFileEntity file = buildBookFile(bookId * 10, book, name, "h");
            file.setFileSubPath("old");
            book.getBookFiles().add(file);
            if (present) {
                Files.createDirectories(root.resolve("old"));
                Files.writeString(root.resolve("old").resolve(name), "same content");
            }
            return file;
        }

        private void withHash(BookFileEntity... files) {
            when(bookRepository.findBookFormatsByCurrentHashIncludingRecentlyDeleted("h", CUTOFF)).thenReturn(List.of(files));
        }

        @Test
        void noFileWithTheHash_isNoMove() {
            withHash();

            assertThat(service.findMovedFile("h", NEW_PATH, CUTOFF)).isEmpty();
        }

        @Test
        void theOneBooksFileGoneFromItsPath_movedToTheNewFile() throws IOException {
            BookFileEntity gone = stored(1, "Title.epub", false, false);
            withHash(gone);

            assertThat(service.findMovedFile("h", NEW_PATH, CUTOFF)).containsSame(gone);
        }

        @Test
        void theOneBooksFileStillAtItsPath_theNewFileIsACopy() throws IOException {
            // Before, the book was moved to the copy, off the file that is still there
            withHash(stored(1, "Title.epub", true, false));

            assertThat(service.findMovedFile("h", NEW_PATH, CUTOFF)).isEmpty();
        }

        @Test
        void aBookDeletedWithinTheWindow_movedToTheNewFile() throws IOException {
            BookFileEntity deleted = stored(1, "Title.epub", true, true);
            withHash(deleted);

            assertThat(service.findMovedFile("h", NEW_PATH, CUTOFF)).containsSame(deleted);
        }

        @Test
        void filesOfTwoBooksWithTheHash_noMoveAndNoException() throws IOException {
            // Before, the lookup threw IncorrectResultSizeDataAccessException for two books
            withHash(stored(1, "Title.epub", false, false), stored(2, "Title (1).epub", false, false));

            assertThat(service.findMovedFile("h", NEW_PATH, CUTOFF)).isEmpty();
        }

        @Test
        void twoFilesOfOneBookWithTheHash_movesTheOneThatIsGone() throws IOException {
            BookFileEntity there = stored(1, "Title.epub", true, false);
            BookFileEntity gone = buildBookFile(11L, there.getBook(), "Title (1).epub", "h");
            gone.setFileSubPath("old");
            there.getBook().getBookFiles().add(gone);
            withHash(there, gone);

            assertThat(service.findMovedFile("h", NEW_PATH, CUTOFF)).containsSame(gone);

            Files.delete(root.resolve("old").resolve("Title.epub"));
            assertThat(service.findMovedFile("h", NEW_PATH, CUTOFF)).as("both gone: which one moved is a guess").isEmpty();
        }
    }

    @Nested
    class UpdatePathIfChanged {

        /** The file as findMovedFile hands it over, read again in the move's transaction. */
        private BookFileEntity moving(BookFileEntity file) {
            when(bookFileRepository.findById(file.getId())).thenReturn(Optional.of(file));
            return file;
        }

        @Test
        void updatesPathWhenSubPathDiffers() {
            BookEntity book = buildBook(10L);
            BookFileEntity bookFile = buildBookFile(100L, book, "test.epub", "hash123");
            book.setBookFiles(new ArrayList<>(List.of(bookFile)));

            when(entityManager.merge(any(LibraryPathEntity.class))).thenReturn(libraryPath);
            fileUtilsMock.when(() -> FileUtils.getRelativeSubPath(anyString(), any(Path.class))).thenReturn("newsub");

            service.updatePathIfChanged(moving(bookFile), library, Path.of("/library/newsub/test.epub"));

            verify(bookRepository).save(book);
            assertThat(bookFile.getFileSubPath()).isEqualTo("newsub");
        }

        @Test
        void updatesPathWhenFileNameDiffers() {
            BookEntity book = buildBook(10L);
            BookFileEntity bookFile = buildBookFile(100L, book, "old.epub", "hash123");
            book.setBookFiles(new ArrayList<>(List.of(bookFile)));

            when(entityManager.merge(any(LibraryPathEntity.class))).thenReturn(libraryPath);

            service.updatePathIfChanged(moving(bookFile), library, Path.of("/library/sub/new.epub"));

            verify(bookRepository).save(book);
            assertThat(bookFile.getFileName()).isEqualTo("new.epub");
        }

        @Test
        void undeleteBookOnPathUpdate() {
            BookEntity book = buildBook(10L);
            book.setDeleted(true);
            BookFileEntity bookFile = buildBookFile(100L, book, "test.epub", "hash123");
            book.setBookFiles(new ArrayList<>(List.of(bookFile)));

            when(entityManager.merge(any(LibraryPathEntity.class))).thenReturn(libraryPath);

            service.updatePathIfChanged(moving(bookFile), library, Path.of("/library/sub/test.epub"));

            verify(bookRepository).save(book);
            assertThat(book.getDeleted()).isFalse();
            assertThat(book.getDeletedAt()).isNull();
        }

        @Test
        void skipsSaveWhenNothingChanged() {
            BookEntity book = buildBook(10L);
            BookFileEntity bookFile = buildBookFile(100L, book, "test.epub", "hash123");
            bookFile.setFileSubPath("sub");
            book.setBookFiles(new ArrayList<>(List.of(bookFile)));

            when(entityManager.merge(any(LibraryPathEntity.class))).thenReturn(libraryPath);

            service.updatePathIfChanged(moving(bookFile), library, Path.of("/library/sub/test.epub"));

            verify(bookRepository, never()).save(any());
        }

        @Test
        void movesTheFileItIsGiven_notThePrimary() {
            BookEntity book = buildBook(10L);
            BookFileEntity primaryFile = buildBookFile(100L, book, "primary.epub", "primaryhash");
            BookFileEntity secondaryFile = buildBookFile(101L, book, "secondary.pdf", "hash123");
            book.setBookFiles(new ArrayList<>(List.of(primaryFile, secondaryFile)));

            when(entityManager.merge(any(LibraryPathEntity.class))).thenReturn(libraryPath);
            fileUtilsMock.when(() -> FileUtils.getRelativeSubPath(anyString(), any(Path.class))).thenReturn("newsub");

            service.updatePathIfChanged(moving(secondaryFile), library, Path.of("/library/newsub/secondary.pdf"));

            // The file it is given moves, not the primary
            assertThat(secondaryFile.getFileSubPath()).isEqualTo("newsub");
            assertThat(secondaryFile.getFileName()).isEqualTo("secondary.pdf");
            assertThat(primaryFile.getFileSubPath()).isEqualTo("sub");
            verify(bookRepository).save(book);
        }

        @Test
        void sendsNotificationAfterUpdate() {
            BookEntity book = buildBook(10L);
            BookFileEntity bookFile = buildBookFile(100L, book, "test.epub", "hash123");
            book.setBookFiles(new ArrayList<>(List.of(bookFile)));

            when(entityManager.merge(any(LibraryPathEntity.class))).thenReturn(libraryPath);
            fileUtilsMock.when(() -> FileUtils.getRelativeSubPath(anyString(), any(Path.class))).thenReturn("newsub");

            service.updatePathIfChanged(moving(bookFile), library, Path.of("/library/newsub/test.epub"));

            verify(notificationService).sendMessageToPermissions(any(), any(), any());
        }
    }

    @Nested
    class MarkAllBooksUnderPathAsDeleted {

        @Test
        void marksAllBooksAsDeleted() {
            BookEntity book1 = buildBook(10L);
            BookEntity book2 = buildBook(11L);
            when(bookRepository.findAllByLibraryPathIdAndFileSubPathStartingWith(1L, "sub"))
                    .thenReturn(List.of(book1, book2));

            int count = service.markAllBooksUnderPathAsDeleted(1L, "sub");

            assertThat(count).isEqualTo(2);
            assertThat(book1.getDeleted()).isTrue();
            assertThat(book1.getDeletedAt()).isNotNull();
            assertThat(book2.getDeleted()).isTrue();
            verify(bookRepository).saveAll(List.of(book1, book2));
        }

        @Test
        void returnsZeroWhenNoBooksFound() {
            when(bookRepository.findAllByLibraryPathIdAndFileSubPathStartingWith(1L, "empty"))
                    .thenReturn(List.of());

            int count = service.markAllBooksUnderPathAsDeleted(1L, "empty");

            assertThat(count).isZero();
        }

        @Test
        void throwsOnNullRelativePath() {
            assertThatThrownBy(() -> service.markAllBooksUnderPathAsDeleted(1L, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class DelegationMethods {

        @Test
        void findBookFileByLibraryPathSubPathAndFileName_delegates() {
            BookFileEntity bookFile = buildBookFile(100L, buildBook(10L), "test.epub", "hash");
            when(bookFileRepository.findAllByLibraryPathIdAndFileSubPathAndFileName(1L, "sub", "test.epub"))
                    .thenReturn(List.of(bookFile));

            Optional<BookFileEntity> result = service.findBookFileByLibraryPathSubPathAndFileName(1L, "sub", "test.epub");

            assertThat(result).isPresent();
            assertThat(result.get().getFileName()).isEqualTo("test.epub");
        }

        @Test
        void findBookFileByLibraryPathSubPathAndFileName_returnsOnlyTheFileAtExactlyThePath() {
            // What MariaDB's case- and accent-insensitive collation returns for 'test.epub' in 'sub'
            BookFileEntity otherCase = buildBookFile(100L, buildBook(10L), "Test.epub", "h1");
            BookFileEntity decomposed = buildBookFile(101L, buildBook(11L), "te\u0301st.epub", "h2");
            BookFileEntity exact = buildBookFile(102L, buildBook(12L), "test.epub", "h3");
            when(bookFileRepository.findAllByLibraryPathIdAndFileSubPathAndFileName(1L, "sub", "test.epub"))
                    .thenReturn(List.of(otherCase, decomposed, exact));

            assertThat(service.findBookFileByLibraryPathSubPathAndFileName(1L, "sub", "test.epub")).containsSame(exact);

            when(bookFileRepository.findAllByLibraryPathIdAndFileSubPathAndFileName(1L, "sub", "test.epub"))
                    .thenReturn(List.of(otherCase, decomposed));
            assertThat(service.findBookFileByLibraryPathSubPathAndFileName(1L, "sub", "test.epub")).isEmpty();
        }

        @Test
        void deleteBookFile_delegates() {
            BookFileEntity bookFile = buildBookFile(100L, buildBook(10L), "test.epub", "hash");

            service.deleteBookFile(bookFile);

            verify(bookFileRepository).delete(bookFile);
        }

        @Test
        void markBookAsDeleted_setsDeletedFields() {
            BookEntity book = buildBook(10L);

            service.markBookAsDeleted(book);

            assertThat(book.getDeleted()).isTrue();
            assertThat(book.getDeletedAt()).isNotNull();
            verify(bookRepository).save(book);
        }

        @Test
        void save_delegates() {
            BookEntity book = buildBook(10L);

            service.save(book);

            verify(bookRepository).save(book);
        }

        @Test
        void countBookFilesByBookId_delegates() {
            when(bookFileRepository.countByBookId(10L)).thenReturn(3L);

            long count = service.countBookFilesByBookId(10L);

            assertThat(count).isEqualTo(3L);
        }
    }

    @Nested
    class RecoverFolderBook {

        @Test
        void recoversBookAndUpdatesFiles() {
            BookEntity book = buildBook(10L);
            book.setDeleted(true);
            BookFileEntity bookFile = buildBookFile(100L, book, "old.epub", "filehash1");
            book.setBookFiles(new ArrayList<>(List.of(bookFile)));

            var fileSnap = new FileSnapshot(100L, "old.epub", "sub", "filehash1", false, BookFileType.EPUB);
            var bookSnap = new BookSnapshot(10L, 1L, "sub", List.of(fileSnap));

            when(bookRepository.findById(10L)).thenReturn(Optional.of(book));
            when(bookFileRepository.findById(100L)).thenReturn(Optional.of(bookFile));
            when(entityManager.merge(any(LibraryPathEntity.class))).thenReturn(libraryPath);
            fileUtilsMock.when(() -> FileUtils.getRelativeSubPath(anyString(), any(Path.class))).thenReturn("newsub");

            Map<Path, String> fileHashes = Map.of(Path.of("/library/newsub/new.epub"), "filehash1");

            service.recoverFolderBook(bookSnap, libraryPath, Path.of("/library/newsub"), fileHashes);

            assertThat(book.getDeleted()).isFalse();
            assertThat(book.getDeletedAt()).isNull();
            assertThat(bookFile.getFileName()).isEqualTo("new.epub");
            assertThat(bookFile.getFileSubPath()).isEqualTo("newsub");
            verify(bookRepository).save(book);
            verify(notificationService).sendMessageToPermissions(any(), any(), any());
        }

        @Test
        void bookNotFound_doesNothing() {
            var bookSnap = new BookSnapshot(999L, 1L, "sub", List.of());
            when(bookRepository.findById(999L)).thenReturn(Optional.empty());

            service.recoverFolderBook(bookSnap, libraryPath, Path.of("/library"), Map.of());

            verify(bookRepository, never()).save(any());
        }

        @Test
        void multipleFilesRecovered() {
            BookEntity book = buildBook(10L);
            book.setDeleted(true);
            BookFileEntity file1 = buildBookFile(100L, book, "a.epub", "hash1");
            BookFileEntity file2 = buildBookFile(101L, book, "b.epub", "hash2");
            book.setBookFiles(new ArrayList<>(List.of(file1, file2)));

            var snap1 = new FileSnapshot(100L, "a.epub", "sub", "hash1", false, BookFileType.EPUB);
            var snap2 = new FileSnapshot(101L, "b.epub", "sub", "hash2", false, BookFileType.EPUB);
            var bookSnap = new BookSnapshot(10L, 1L, "sub", List.of(snap1, snap2));

            when(bookRepository.findById(10L)).thenReturn(Optional.of(book));
            when(bookFileRepository.findById(100L)).thenReturn(Optional.of(file1));
            when(bookFileRepository.findById(101L)).thenReturn(Optional.of(file2));
            when(entityManager.merge(any(LibraryPathEntity.class))).thenReturn(libraryPath);
            fileUtilsMock.when(() -> FileUtils.getRelativeSubPath(eq("/library"), eq(Path.of("/new/a.epub")))).thenReturn("newsub1");
            fileUtilsMock.when(() -> FileUtils.getRelativeSubPath(eq("/library"), eq(Path.of("/new/b.epub")))).thenReturn("newsub2");

            Map<Path, String> fileHashes = Map.of(
                    Path.of("/new/a.epub"), "hash1",
                    Path.of("/new/b.epub"), "hash2");

            service.recoverFolderBook(bookSnap, libraryPath, Path.of("/new"), fileHashes);

            assertThat(book.getDeleted()).isFalse();
            verify(bookRepository).save(book);
        }

        @Test
        void fileSnapshotWithNoHashMatch_leftUnchanged() {
            BookEntity book = buildBook(10L);
            BookFileEntity bookFile = buildBookFile(100L, book, "old.epub", "oldhash");
            book.setBookFiles(new ArrayList<>(List.of(bookFile)));

            var fileSnap = new FileSnapshot(100L, "old.epub", "sub", "nomatch", false, BookFileType.EPUB);
            var bookSnap = new BookSnapshot(10L, 1L, "sub", List.of(fileSnap));

            when(bookRepository.findById(10L)).thenReturn(Optional.of(book));
            when(bookFileRepository.findById(100L)).thenReturn(Optional.of(bookFile));
            when(entityManager.merge(any(LibraryPathEntity.class))).thenReturn(libraryPath);

            Map<Path, String> fileHashes = Map.of(Path.of("/new/other.epub"), "differenthash");

            service.recoverFolderBook(bookSnap, libraryPath, Path.of("/new"), fileHashes);

            // File name should remain unchanged since hash didn't match
            assertThat(bookFile.getFileName()).isEqualTo("old.epub");
            verify(bookRepository).save(book);
        }
    }
}
