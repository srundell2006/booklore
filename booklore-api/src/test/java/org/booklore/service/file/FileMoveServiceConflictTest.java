package org.booklore.service.file;

import jakarta.persistence.EntityManager;
import org.booklore.config.AppProperties;
import org.booklore.mapper.BookMapper;
import org.booklore.mapper.LibraryMapper;
import org.booklore.model.dto.FileMoveResult;
import org.booklore.model.dto.request.FileMoveRequest;
import org.booklore.model.entity.AuthorEntity;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.entity.LibraryPathEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.repository.BookAdditionalFileRepository;
import org.booklore.repository.BookRepository;
import org.booklore.repository.LibraryRepository;
import org.booklore.service.NotificationService;
import org.booklore.service.appsettings.AppSettingService;
import org.booklore.service.metadata.sidecar.SidecarMetadataMapper;
import org.booklore.service.metadata.sidecar.SidecarMetadataWriter;
import org.booklore.service.monitoring.MonitoringRegistrationService;
import org.booklore.util.FileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Moves on real files: a move never replaces another book's file or folder, never leaves a book half moved, and a
 * book already in place, under whatever spelling the share stores it, stays where it is.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FileMoveServiceConflictTest {

    @Mock private AppProperties appProperties;
    @Mock private BookRepository bookRepository;
    @Mock private BookAdditionalFileRepository bookFileRepository;
    @Mock private LibraryRepository libraryRepository;
    @Mock private MonitoringRegistrationService monitoringRegistrationService;
    @Mock private AppSettingService appSettingService;
    @Mock private LibraryMapper libraryMapper;
    @Mock private BookMapper bookMapper;
    @Mock private NotificationService notificationService;
    @Mock private EntityManager entityManager;
    @Mock private TransactionTemplate transactionTemplate;
    @Mock private SidecarMetadataWriter sidecarMetadataWriter;

    @TempDir
    Path tempDir;

    private FileMoveService service;
    private Path libraryRoot;
    private LibraryEntity library;
    private LibraryPathEntity libraryPath;
    private long nextId = 1;

    @BeforeEach
    void setUp() throws IOException {
        when(appProperties.isLocalStorage()).thenReturn(true);
        when(monitoringRegistrationService.getPathsForLibraries(anySet())).thenReturn(Collections.emptySet());
        doAnswer(invocation -> {
            Consumer<TransactionStatus> action = invocation.getArgument(0);
            action.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        service = service(new FileMoveHelper(monitoringRegistrationService, appSettingService));
        libraryRoot = Files.createDirectories(tempDir.resolve("library"));
        library = library(1L, libraryRoot);
        libraryPath = library.getLibraryPaths().getFirst();
    }

    private FileMoveService service(FileMoveHelper fileMoveHelper) {
        return service(fileMoveHelper, sidecarMetadataWriter);
    }

    private FileMoveService service(FileMoveHelper fileMoveHelper, SidecarMetadataWriter sidecarMetadataWriter) {
        return service(fileMoveHelper, sidecarMetadataWriter, FileMoveHelper.MOVE_LOCK_WAIT);
    }

    private FileMoveService service(FileMoveHelper fileMoveHelper, Duration moveLockWait) {
        return service(fileMoveHelper, sidecarMetadataWriter, moveLockWait);
    }

    private FileMoveService service(FileMoveHelper fileMoveHelper, SidecarMetadataWriter sidecarMetadataWriter, Duration moveLockWait) {
        return new FileMoveService(appProperties, bookRepository, bookFileRepository, libraryRepository, fileMoveHelper,
                monitoringRegistrationService, libraryMapper, bookMapper, notificationService, entityManager,
                transactionTemplate, sidecarMetadataWriter) {
            @Override
            protected void sleep(long millis) {
                // No-op
            }

            @Override
            protected Duration moveLockWait() {
                return moveLockWait;
            }
        };
    }

    private LibraryEntity library(Long id, Path root) {
        LibraryEntity entity = new LibraryEntity();
        entity.setId(id);
        entity.setName("Library " + id);
        entity.setFileNamingPattern("{title}");
        LibraryPathEntity path = new LibraryPathEntity();
        path.setId(id * 10);
        path.setPath(root.toString());
        path.setLibrary(entity);
        entity.setLibraryPaths(List.of(path));
        return entity;
    }

    private BookEntity book(String title, String subPath, String fileName, boolean folderBased) {
        BookEntity book = new BookEntity();
        book.setId(nextId++);
        book.setLibrary(library);
        book.setLibraryPath(libraryPath);
        BookMetadataEntity metadata = new BookMetadataEntity();
        metadata.setTitle(title);
        book.setMetadata(metadata);
        book.setBookFiles(new ArrayList<>());
        addFile(book, subPath, fileName, true, folderBased);
        when(bookRepository.findById(book.getId())).thenReturn(Optional.of(book));
        when(bookRepository.findByIdWithBookFiles(book.getId())).thenReturn(Optional.of(book));
        return book;
    }

    private BookFileEntity addFile(BookEntity book, String subPath, String fileName, boolean isBook, boolean folderBased) {
        BookFileEntity file = new BookFileEntity();
        file.setId(nextId++);
        file.setBook(book);
        file.setFileName(fileName);
        file.setFileSubPath(subPath);
        file.setBookFormat(isBook);
        file.setFolderBased(folderBased);
        if (isBook) {
            file.setBookType(folderBased ? BookFileType.AUDIOBOOK : BookFileType.EPUB);
        }
        // Hibernate's association management may already have added it in setBook
        if (!book.getBookFiles().contains(file)) {
            book.getBookFiles().add(file);
        }
        return file;
    }

    private static Path write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        return Files.writeString(file, content);
    }

    /** Non-ASCII file names need the JVM to encode them as UTF-8, as it does under a UTF-8 locale (LANG=C.UTF-8). */
    private static void assumeUnicodeFileNames() {
        assumeTrue(Charset.forName(System.getProperty("sun.jnu.encoding", "UTF-8")).newEncoder().canEncode("한강ガ"),
                "File names are not encoded as UTF-8 here: run under a UTF-8 locale");
    }

    private List<Path> leftoverTempFiles() throws IOException {
        try (Stream<Path> paths = Files.walk(tempDir)) {
            return paths.filter(path -> path.getFileName().toString().endsWith(".tmp_move")).toList();
        }
    }

    @Test
    void twoBooksResolvingToTheSameName_secondStaysWhereItIs() throws IOException {
        Path first = write(libraryRoot.resolve("incoming/first.epub"), "first book");
        Path second = write(libraryRoot.resolve("incoming/second.epub"), "second book");
        BookEntity firstBook = book("Same Title", "incoming", "first.epub", false);
        BookEntity secondBook = book("Same Title", "incoming", "second.epub", false);
        Path target = libraryRoot.resolve("Same Title.epub");

        assertThat(service.moveSingleFile(firstBook).isMoved()).isTrue();
        FileMoveResult result = service.moveSingleFile(secondBook);

        assertThat(result.isMoved()).isFalse();
        assertThat(Files.readString(target)).isEqualTo("first book");
        assertThat(first).doesNotExist();
        assertThat(Files.readString(second)).isEqualTo("second book");
        assertThat(leftoverTempFiles()).isEmpty();
        verify(bookFileRepository, never()).updateFileNameAndSubPath(eq(secondBook.getBookFiles().getFirst().getId()), any(), any());
    }

    @Test
    void moveStuckOnTheShare_failsLaterMovesInsteadOfHoldingThemUpForGood() throws Exception {
        write(libraryRoot.resolve("incoming/a.epub"), "stuck book");
        Path otherSource = write(libraryRoot.resolve("incoming/b.epub"), "other book");
        BookEntity stuckBook = book("Stuck", "incoming", "a.epub", false);
        BookEntity otherBook = book("Other", "incoming", "b.epub", false);
        CountDownLatch stuck = new CountDownLatch(1);
        CountDownLatch unstuck = new CountDownLatch(1);
        // A rename into the target directory that blocks in IO on the share, deaf to interrupts
        FileMoveService stuckService = service(new FileMoveHelper(monitoringRegistrationService, appSettingService) {
            @Override
            protected void rename(Path source, Path target) throws IOException {
                if (target.getFileName().toString().startsWith("Stuck")) {
                    stuck.countDown();
                    awaitUninterruptibly(unstuck);
                }
                super.rename(source, target);
            }
        });
        ExecutorService movers = Executors.newCachedThreadPool();
        try {
            Future<FileMoveResult> stuckMove = movers.submit(() -> stuckService.moveSingleFile(stuckBook));
            assertThat(stuck.await(10, TimeUnit.SECONDS)).isTrue();

            FileMoveService waitingService = service(new FileMoveHelper(monitoringRegistrationService, appSettingService), Duration.ofMillis(200));
            FileMoveResult waited = movers.submit(() -> waitingService.moveSingleFile(otherBook)).get(10, TimeUnit.SECONDS);

            assertThat(waited.isMoved()).isFalse();
            assertThat(Files.readString(otherSource)).isEqualTo("other book");
            verify(bookFileRepository, never()).updateFileNameAndSubPath(eq(otherBook.getBookFiles().getFirst().getId()), any(), any());

            // Once a move has given up on it, later moves do not each wait it out again
            FileMoveService patientService = service(new FileMoveHelper(monitoringRegistrationService, appSettingService), Duration.ofMinutes(1));
            assertThat(movers.submit(() -> patientService.moveSingleFile(otherBook)).get(10, TimeUnit.SECONDS).isMoved()).isFalse();
            assertThat(Files.readString(otherSource)).isEqualTo("other book");

            unstuck.countDown();
            assertThat(stuckMove.get(10, TimeUnit.SECONDS).isMoved()).isTrue();
            assertThat(patientService.moveSingleFile(otherBook).isMoved()).isTrue();
            assertThat(Files.readString(libraryRoot.resolve("Other.epub"))).isEqualTo("other book");
            assertThat(Files.readString(libraryRoot.resolve("Stuck.epub"))).isEqualTo("stuck book");
        } finally {
            unstuck.countDown();
            movers.shutdownNow();
        }
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private FileMoveRequest moveToLibrary2(BookEntity... books) {
        FileMoveRequest request = new FileMoveRequest();
        request.setMoves(Stream.of(books).map(book -> {
            FileMoveRequest.Move move = new FileMoveRequest.Move();
            move.setBookId(book.getId());
            move.setTargetLibraryId(2L);
            move.setTargetLibraryPathId(20L);
            return move;
        }).toList());
        return request;
    }

    @Test
    void twoBooksMovedToTheSameNameInABulkMove_secondStaysWhereItIs() throws IOException {
        Path targetRoot = Files.createDirectories(tempDir.resolve("target"));
        LibraryEntity targetLibrary = library(2L, targetRoot);
        when(libraryRepository.findById(2L)).thenReturn(Optional.of(targetLibrary));
        Path first = write(libraryRoot.resolve("first.epub"), "first book");
        Path second = write(libraryRoot.resolve("second.epub"), "second book");
        BookEntity firstBook = book("Same Title", "", "first.epub", false);
        BookEntity secondBook = book("Same Title", "", "second.epub", false);

        service.bulkMoveFiles(moveToLibrary2(firstBook, secondBook));

        assertThat(Files.readString(targetRoot.resolve("Same Title.epub"))).isEqualTo("first book");
        assertThat(first).doesNotExist();
        assertThat(Files.readString(second)).isEqualTo("second book");
        assertThat(leftoverTempFiles()).isEmpty();
        verify(bookRepository).updateLibrary(eq(firstBook.getId()), eq(2L), any());
        verify(bookRepository, never()).updateLibrary(eq(secondBook.getId()), anyLong(), any());
    }

    @Test
    void twoFolderAudiobooksResolvingToTheSameFolder_secondStaysWhereItIs() throws IOException {
        write(libraryRoot.resolve("incoming/01. First - Author (2001)/part1.mp3"), "first audiobook");
        write(libraryRoot.resolve("incoming/01. Second - Author (2002)/part1.mp3"), "second audiobook");
        BookEntity firstBook = book("Same Title", "incoming", "01. First - Author (2001)", true);
        BookEntity secondBook = book("Same Title", "incoming", "01. Second - Author (2002)", true);
        Path target = libraryRoot.resolve("Same Title");

        assertThat(service.moveSingleFile(firstBook).isMoved()).isTrue();
        assertThat(service.moveSingleFile(secondBook).isMoved()).isFalse();

        assertThat(Files.readString(target.resolve("part1.mp3"))).isEqualTo("first audiobook");
        try (Stream<Path> files = Files.list(target)) {
            assertThat(files).hasSize(1);
        }
        assertThat(Files.readString(libraryRoot.resolve("incoming/01. Second - Author (2002)/part1.mp3"))).isEqualTo("second audiobook");
        assertThat(leftoverTempFiles()).isEmpty();
    }

    @Test
    void caseOnlyRename_stillMovesTheBook() throws IOException {
        write(libraryRoot.resolve("the hobbit.epub"), "hobbit");
        BookEntity book = book("The Hobbit", "", "the hobbit.epub", false);

        FileMoveResult result = service.moveSingleFile(book);

        assertThat(result.isMoved()).isTrue();
        try (Stream<Path> files = Files.list(libraryRoot)) {
            assertThat(files.map(path -> path.getFileName().toString())).containsExactly("The Hobbit.epub");
        }
        assertThat(Files.readString(libraryRoot.resolve("The Hobbit.epub"))).isEqualTo("hobbit");
        verify(bookFileRepository).updateFileNameAndSubPath(book.getBookFiles().getFirst().getId(), "The Hobbit.epub", "");
    }

    @Test
    void targetAppearingBetweenPlanAndCommit_isNotReplacedAndTheSourceIsRestored() throws IOException {
        Path source = write(libraryRoot.resolve("incoming/book.epub"), "the book");
        BookEntity book = book("Title", "incoming", "book.epub", false);
        Path target = libraryRoot.resolve("Title.epub");
        FileMoveService racingService = service(new FileMoveHelper(monitoringRegistrationService, appSettingService) {
            @Override
            public Path moveFileWithBackup(Path source) throws IOException {
                Path tempPath = super.moveFileWithBackup(source);
                write(target, "another book");
                return tempPath;
            }
        });

        FileMoveResult result = racingService.moveSingleFile(book);

        assertThat(result.isMoved()).isFalse();
        assertThat(Files.readString(target)).isEqualTo("another book");
        assertThat(Files.readString(source)).isEqualTo("the book");
        assertThat(leftoverTempFiles()).isEmpty();
        verify(bookFileRepository, never()).updateFileNameAndSubPath(anyLong(), any(), any());
    }

    @Test
    void bookStoredDecomposed_isAlreadyInPlace() throws IOException {
        assumeUnicodeFileNames();
        String title = "ガイドがある";
        String decomposed = Normalizer.normalize(title, Normalizer.Form.NFD) + ".epub";
        Path source = write(libraryRoot.resolve(decomposed), "the book");
        BookEntity book = book(title, "", decomposed, false);

        FileMoveResult result = service.moveSingleFile(book);

        assertThat(result.isMoved()).isFalse();
        assertThat(Files.readString(source)).isEqualTo("the book");
        assertThat(leftoverTempFiles()).isEmpty();
        verify(bookFileRepository, never()).updateFileNameAndSubPath(anyLong(), any(), any());
    }

    @Test
    void fileLeftInPlaceUnderADecomposedName_keepsThatNameInTheDatabase() throws IOException {
        assumeUnicodeFileNames();
        library.setFileNamingPattern("{authors}/{title}");
        String decomposedAuthor = Normalizer.normalize("한강", Normalizer.Form.NFD);
        write(libraryRoot.resolve(decomposedAuthor).resolve("old name.epub"), "the book");
        Path notes = write(libraryRoot.resolve(decomposedAuthor).resolve("notes.txt"), "notes");
        // A share that finds the decomposed folder under the composed name, as a link does here
        Files.createSymbolicLink(libraryRoot.resolve("한강"), libraryRoot.resolve(decomposedAuthor));
        BookEntity book = book("소년이 온다", decomposedAuthor, "old name.epub", false);
        book.getMetadata().setAuthors(List.of(AuthorEntity.builder().name("한강").build()));
        BookFileEntity notesFile = addFile(book, decomposedAuthor, "notes.txt", false, false);

        assertThat(service.moveSingleFile(book).isMoved()).isTrue();

        assertThat(Files.readString(notes)).isEqualTo("notes");
        assertThat(Files.readString(libraryRoot.resolve(decomposedAuthor).resolve("소년이 온다.epub"))).isEqualTo("the book");
        verify(bookFileRepository).updateFileNameAndSubPath(book.getBookFiles().getFirst().getId(), "소년이 온다.epub", "한강");
        verify(bookFileRepository).updateFileNameAndSubPath(notesFile.getId(), "notes.txt", decomposedAuthor);
    }

    @Test
    void bookInADecomposedFolderOnASensitiveFileSystem_movesTogether() throws IOException {
        assumeUnicodeFileNames();
        library.setFileNamingPattern("{authors}/{title}");
        String decomposedAuthor = Normalizer.normalize("한강", Normalizer.Form.NFD);
        write(libraryRoot.resolve(decomposedAuthor).resolve("old name.epub"), "the book");
        write(libraryRoot.resolve(decomposedAuthor).resolve("notes.txt"), "notes");
        BookEntity book = book("소년이 온다", decomposedAuthor, "old name.epub", false);
        book.getMetadata().setAuthors(List.of(AuthorEntity.builder().name("한강").build()));
        BookFileEntity notesFile = addFile(book, decomposedAuthor, "notes.txt", false, false);

        assertThat(service.moveSingleFile(book).isMoved()).isTrue();

        // The composed folder is another folder here: the notes go into it with the book, not stay behind
        assertThat(Files.readString(libraryRoot.resolve("한강/소년이 온다.epub"))).isEqualTo("the book");
        assertThat(Files.readString(libraryRoot.resolve("한강/notes.txt"))).isEqualTo("notes");
        assertThat(libraryRoot.resolve(decomposedAuthor)).doesNotExist();
        verify(bookFileRepository).updateFileNameAndSubPath(book.getBookFiles().getFirst().getId(), "소년이 온다.epub", "한강");
        verify(bookFileRepository).updateFileNameAndSubPath(notesFile.getId(), "notes.txt", "한강");
    }

    @Test
    void twoFilesOfOneBookResolvingToTheSameName_bookStaysWhereItIs() throws IOException {
        // An attached second EPUB is named "Dune_1.epub", and "{title}" names both files "Dune Messiah.epub"
        Path first = write(libraryRoot.resolve("incoming/Dune.epub"), "first file");
        Path second = write(libraryRoot.resolve("incoming/Dune_1.epub"), "second file");
        BookEntity book = book("Dune Messiah", "incoming", "Dune.epub", false);
        addFile(book, "incoming", "Dune_1.epub", true, false);

        FileMoveResult result = service.moveSingleFile(book);

        assertThat(result.isMoved()).isFalse();
        assertThat(Files.readString(first)).isEqualTo("first file");
        assertThat(Files.readString(second)).isEqualTo("second file");
        assertThat(libraryRoot.resolve("Dune Messiah.epub")).doesNotExist();
        assertThat(leftoverTempFiles()).isEmpty();
        verify(bookFileRepository, never()).updateFileNameAndSubPath(anyLong(), any(), any());
    }

    @Test
    void twoFilesOfOneBookResolvingToNamesThatDifferOnlyInCase_bookStaysWhereItIs() throws IOException {
        // "Title.EPUB" and "Title.epub" are one name on a case-insensitive share
        Path first = write(libraryRoot.resolve("incoming/a.EPUB"), "first file");
        Path second = write(libraryRoot.resolve("incoming/b.epub"), "second file");
        BookEntity book = book("Title", "incoming", "a.EPUB", false);
        addFile(book, "incoming", "b.epub", true, false);

        assertThat(service.moveSingleFile(book).isMoved()).isFalse();

        assertThat(Files.readString(first)).isEqualTo("first file");
        assertThat(Files.readString(second)).isEqualTo("second file");
        verify(bookFileRepository, never()).updateFileNameAndSubPath(anyLong(), any(), any());
    }

    @Test
    void twoFilesOfOneBookResolvingToTheSameNameInABulkMove_bookStaysWhereItIs() throws IOException {
        Path targetRoot = Files.createDirectories(tempDir.resolve("target"));
        when(libraryRepository.findById(2L)).thenReturn(Optional.of(library(2L, targetRoot)));
        Path first = write(libraryRoot.resolve("Dune.epub"), "first file");
        Path second = write(libraryRoot.resolve("Dune_1.epub"), "second file");
        BookEntity book = book("Dune Messiah", "", "Dune.epub", false);
        addFile(book, "", "Dune_1.epub", true, false);

        service.bulkMoveFiles(moveToLibrary2(book));

        assertThat(Files.readString(first)).isEqualTo("first file");
        assertThat(Files.readString(second)).isEqualTo("second file");
        assertThat(targetRoot.resolve("Dune Messiah.epub")).doesNotExist();
        assertThat(leftoverTempFiles()).isEmpty();
        verify(bookRepository, never()).updateLibrary(anyLong(), anyLong(), any());
    }

    @Test
    void targetAppearingForTheSecondFileOfABook_putsBothFilesBack() throws IOException {
        Path epub = write(libraryRoot.resolve("incoming/book.epub"), "the epub");
        Path pdf = write(libraryRoot.resolve("incoming/book.pdf"), "the pdf");
        BookEntity book = book("Title", "incoming", "book.epub", false);
        addFile(book, "incoming", "book.pdf", true, false);
        Path pdfTarget = libraryRoot.resolve("Title.pdf");
        FileMoveService racingService = service(new FileMoveHelper(monitoringRegistrationService, appSettingService) {
            @Override
            public Path moveFileWithBackup(Path source) throws IOException {
                Path tempPath = super.moveFileWithBackup(source);
                if (source.equals(pdf)) {
                    write(pdfTarget, "another book");
                }
                return tempPath;
            }
        });

        FileMoveResult result = racingService.moveSingleFile(book);

        // The EPUB was already at its new name when the PDF could not follow: it is moved back too
        assertThat(result.isMoved()).isFalse();
        assertThat(Files.readString(epub)).isEqualTo("the epub");
        assertThat(Files.readString(pdf)).isEqualTo("the pdf");
        assertThat(libraryRoot.resolve("Title.epub")).doesNotExist();
        assertThat(Files.readString(pdfTarget)).isEqualTo("another book");
        assertThat(leftoverTempFiles()).isEmpty();
        verify(bookFileRepository, never()).updateFileNameAndSubPath(anyLong(), any(), any());
    }

    private void completeTransaction(int status, Runnable work) {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            work.run();
            TransactionSynchronizationManager.getSynchronizations().forEach(synchronization -> synchronization.afterCompletion(status));
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void callersTransactionRollingBackAfterTheMove_putsTheFilesBack() throws IOException {
        Path source = write(libraryRoot.resolve("incoming/old/book.epub"), "the book");
        Path notes = write(libraryRoot.resolve("incoming/old/notes.txt"), "notes");
        BookEntity book = book("Title", "incoming/old", "book.epub", false);
        addFile(book, "incoming/old", "notes.txt", false, false);

        completeTransaction(TransactionSynchronization.STATUS_ROLLED_BACK, () -> {
            assertThat(service.moveSingleFile(book).isMoved()).isTrue();
            assertThat(source).doesNotExist();
        });

        // The rows went back to the old names with the transaction, and so do the files, folder and all
        assertThat(Files.readString(source)).isEqualTo("the book");
        assertThat(Files.readString(notes)).isEqualTo("notes");
        assertThat(libraryRoot.resolve("Title.epub")).doesNotExist();
        assertThat(libraryRoot.resolve("notes.txt")).doesNotExist();
    }

    @Test
    void callersTransactionCommitting_leavesTheFilesMoved() throws IOException {
        Path source = write(libraryRoot.resolve("incoming/book.epub"), "the book");
        BookEntity book = book("Title", "incoming", "book.epub", false);

        completeTransaction(TransactionSynchronization.STATUS_COMMITTED,
                () -> assertThat(service.moveSingleFile(book).isMoved()).isTrue());

        assertThat(source).doesNotExist();
        assertThat(Files.readString(libraryRoot.resolve("Title.epub"))).isEqualTo("the book");
    }

    private FileMoveService serviceWithSidecars() {
        SidecarMetadataWriter writer = new SidecarMetadataWriter(appProperties, new SidecarMetadataMapper(), mock(FileService.class), appSettingService);
        return service(new FileMoveHelper(monitoringRegistrationService, appSettingService), writer);
    }

    @Test
    void sidecarFiles_moveWithTheBook() throws IOException {
        write(libraryRoot.resolve("incoming/book.pdf"), "the book");
        write(libraryRoot.resolve("incoming/book.metadata.json"), "sidecar");
        write(libraryRoot.resolve("incoming/book.cover.jpg"), "cover");
        BookEntity book = book("Title", "incoming", "book.pdf", false);

        assertThat(serviceWithSidecars().moveSingleFile(book).isMoved()).isTrue();

        assertThat(Files.readString(libraryRoot.resolve("Title.metadata.json"))).isEqualTo("sidecar");
        assertThat(Files.readString(libraryRoot.resolve("Title.cover.jpg"))).isEqualTo("cover");
    }

    @Test
    void anotherBooksSidecarFilesAtTheNewNames_areNotReplaced() throws IOException {
        // "Title.epub" and "Title.pdf" are different books, but both have sidecars named "Title.metadata.json"
        write(libraryRoot.resolve("Title.epub"), "book A");
        Path sidecarA = write(libraryRoot.resolve("Title.metadata.json"), "sidecar A");
        Path coverA = write(libraryRoot.resolve("Title.cover.jpg"), "cover A");
        Path sidecarB = write(libraryRoot.resolve("incoming/book.metadata.json"), "sidecar B");
        Path coverB = write(libraryRoot.resolve("incoming/book.cover.jpg"), "cover B");
        write(libraryRoot.resolve("incoming/book.pdf"), "book B");
        BookEntity bookB = book("Title", "incoming", "book.pdf", false);

        assertThat(serviceWithSidecars().moveSingleFile(bookB).isMoved()).isTrue();

        assertThat(Files.readString(libraryRoot.resolve("Title.pdf"))).isEqualTo("book B");
        assertThat(Files.readString(sidecarA)).isEqualTo("sidecar A");
        assertThat(Files.readString(coverA)).isEqualTo("cover A");
        assertThat(Files.readString(sidecarB)).isEqualTo("sidecar B");
        assertThat(Files.readString(coverB)).isEqualTo("cover B");
    }

    @Test
    void folderAudiobookHoldingAnotherBooksFile_staysWhereItIs() throws IOException {
        // The scanner makes "Old Folder" an audiobook and the PDF in its subfolder a book of its own
        write(libraryRoot.resolve("Old Folder/01.mp3"), "part 1");
        Path booklet = write(libraryRoot.resolve("Old Folder/extras/booklet.pdf"), "booklet");
        BookEntity audiobook = book("New Title", "", "Old Folder", true);
        BookEntity bookletBook = book("Booklet", "Old Folder/extras", "booklet.pdf", false);
        when(bookRepository.findAllByLibraryPathIdAndFileSubPathStartingWith(libraryPath.getId(), "Old Folder"))
                .thenReturn(List.of(bookletBook));

        FileMoveResult result = service.moveSingleFile(audiobook);

        assertThat(result.isMoved()).isFalse();
        assertThat(Files.readString(libraryRoot.resolve("Old Folder/01.mp3"))).isEqualTo("part 1");
        assertThat(Files.readString(booklet)).isEqualTo("booklet");
        assertThat(libraryRoot.resolve("New Title")).doesNotExist();
        verify(bookFileRepository, never()).updateFileNameAndSubPath(anyLong(), any(), any());
    }

    @Test
    void folderAudiobookHoldingOneOfItsOwnFiles_staysWhereItIs() throws IOException {
        // A PDF attached to the audiobook without moving it, from inside the audiobook's folder
        write(libraryRoot.resolve("Old Folder/01.mp3"), "part 1");
        Path booklet = write(libraryRoot.resolve("Old Folder/extras/booklet.pdf"), "booklet");
        BookEntity audiobook = book("New Title", "", "Old Folder", true);
        addFile(audiobook, "Old Folder/extras", "booklet.pdf", true, false);
        // The PDF first: moving it aside and then its folder takes the PDF's temporary file away with the folder
        audiobook.getBookFiles().addFirst(audiobook.getBookFiles().removeLast());

        FileMoveResult result = service.moveSingleFile(audiobook);

        assertThat(result.isMoved()).isFalse();
        assertThat(Files.readString(libraryRoot.resolve("Old Folder/01.mp3"))).isEqualTo("part 1");
        assertThat(Files.readString(booklet)).isEqualTo("booklet");
        assertThat(leftoverTempFiles()).isEmpty();
        verify(bookFileRepository, never()).updateFileNameAndSubPath(anyLong(), any(), any());
    }

    @Test
    void folderAudiobookWithABookInASimilarlyNamedFolder_stillMoves() throws IOException {
        // The query matches "_" as any character; that book is not inside the folder
        write(libraryRoot.resolve("Old_Folder/01.mp3"), "part 1");
        write(libraryRoot.resolve("OldXFolder/book.epub"), "another book");
        BookEntity audiobook = book("New Title", "", "Old_Folder", true);
        BookEntity otherBook = book("Other", "OldXFolder", "book.epub", false);
        when(bookRepository.findAllByLibraryPathIdAndFileSubPathStartingWith(libraryPath.getId(), "Old_Folder"))
                .thenReturn(List.of(otherBook));

        FileMoveResult result = service.moveSingleFile(audiobook);

        assertThat(result.isMoved()).isTrue();
        assertThat(Files.readString(libraryRoot.resolve("New Title/01.mp3"))).isEqualTo("part 1");
        assertThat(Files.readString(libraryRoot.resolve("OldXFolder/book.epub"))).isEqualTo("another book");
    }
}
