package org.booklore.service.watcher;

import org.booklore.BookloreApplication;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.entity.LibraryPathEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.model.enums.LibraryOrganizationMode;
import org.booklore.repository.BookAdditionalFileRepository;
import org.booklore.repository.BookRepository;
import org.booklore.repository.LibraryRepository;
import org.booklore.service.file.FileFingerprint;
import org.booklore.service.task.TaskCronService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.function.BooleanSupplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;

/**
 * A watched file with the content of a stored file is that file moved only when the stored file is gone from its path
 * and no other book has a file of that content. A copy, such as "Title (1).epub" next to or away from its original,
 * is a new file; before, the original's book was moved onto the copy, and two books holding the content made the
 * lookup throw. A copy whose stored file goes soon after, as a mover that writes the destination before it removes
 * the source leaves it, is that file moved, whether it came alone or in a new folder, and in every organization mode.
 * Runs the watcher against H2 with the files on disk.
 */
@SpringBootTest(classes = {
        BookloreApplication.class
})
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.datasource.url=jdbc:h2:mem:watchermoves;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "app.path-config=build/tmp/watcher-moves-config",
        "app.bookdrop-folder=build/tmp/test-bookdrop",
        "spring.main.allow-bean-definition-overriding=true",
        "spring.task.scheduling.enabled=false",
        "app.task.scan-library-cron=*/1 * * * * *",
        "app.task.process-bookdrop-cron=*/1 * * * * *",
        "app.features.oidc-enabled=false"
})
@Import(WatcherMovedFileDatabaseTest.TestConfig.class)
class WatcherMovedFileDatabaseTest {

    @TestConfiguration
    public static class TestConfig {
        @Bean("flyway")
        @Primary
        public org.flywaydb.core.Flyway flyway() {
            return mock(org.flywaydb.core.Flyway.class);
        }

        @Bean
        @Primary
        public TaskCronService taskCronService() {
            return mock(TaskCronService.class);
        }
    }

    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private LibraryRepository libraryRepository;
    @Autowired
    private BookRepository bookRepository;
    @Autowired
    private BookAdditionalFileRepository bookAdditionalFileRepository;
    @Autowired
    private BookFileTransactionalHandler bookFileTransactionalHandler;
    @Autowired
    private BookFilePersistenceService bookFilePersistenceService;
    @Autowired
    private PendingDeletionPool pendingDeletionPool;
    @Autowired
    private LibraryFileEventProcessor libraryFileEventProcessor;

    @TempDir
    Path tempDir;

    private TransactionTemplate tx;
    private LibraryEntity library;
    private LibraryPathEntity libraryPath;
    private Path root;

    @BeforeEach
    void setUp() throws IOException {
        tx = new TransactionTemplate(transactionManager);
        // As in the Flyway schema the application runs on: a new book's empty metadata is saved before its title is
        // set. The entity's nullable = false only shapes the schema H2 generates from it.
        new JdbcTemplate(dataSource).execute("ALTER TABLE book_metadata ALTER COLUMN title SET NULL");
        root = Files.createDirectories(tempDir.resolve("library"));
        library = tx.execute(status -> {
            LibraryEntity created = libraryRepository.save(LibraryEntity.builder()
                    .name("Watched " + System.nanoTime()).icon("book").watch(false)
                    .formatPriority(new ArrayList<>(List.of(BookFileType.EPUB, BookFileType.PDF)))
                    .libraryPaths(new ArrayList<>())
                    .build());
            created.getLibraryPaths().add(LibraryPathEntity.builder().library(created).path(root.toString()).build());
            return libraryRepository.save(created);
        });
        libraryPath = library.getLibraryPaths().getFirst();
    }

    @AfterEach
    void restoreTheWait() {
        libraryFileEventProcessor.setCopySourceWaitMs(LibraryFileEventProcessor.COPY_SOURCE_WAIT_MS);
    }

    @Test
    void aCopyOfAStoredFileIsANewFileAndItsOriginalStaysWhereItIs() throws IOException {
        Path original = epub(root.resolve("Asimov/Foundation.epub"), "Foundation " + System.nanoTime());
        long bookId = storedBook("Asimov", "Foundation.epub", FileFingerprint.generateHash(original), false);
        Path copy = Files.copy(original, Files.createDirectories(root.resolve("Inbox")).resolve("Foundation (1).epub"));

        bookFileTransactionalHandler.handleNewBookFile(library.getId(), copy);

        assertThat(pathsOf(bookId)).containsExactly("Asimov/Foundation.epub");
        Long copyBook = bookIdAt("Inbox", "Foundation (1).epub");
        assertThat(copyBook).isNotNull().isNotEqualTo(bookId);
    }

    @Test
    void aStoredFileGoneFromItsPathMovesToTheNewFile() throws IOException {
        Path moved = epub(root.resolve("Inbox/Foundation.epub"), "Foundation " + System.nanoTime());
        long bookId = storedBook("Asimov", "Foundation.epub", FileFingerprint.generateHash(moved), false);

        bookFileTransactionalHandler.handleNewBookFile(library.getId(), moved);

        assertThat(pathsOf(bookId)).containsExactly("Inbox/Foundation.epub");
        assertThat(bookRepository.countIncludingDeletedByLibraryId(library.getId())).isEqualTo(1);
    }

    @Test
    void aBookDeletedWithinTheWindowComesBackAtTheNewPath() throws IOException {
        Path moved = epub(root.resolve("Inbox/Foundation.epub"), "Foundation " + System.nanoTime());
        long bookId = storedBook("Asimov", "Foundation.epub", FileFingerprint.generateHash(moved), true);

        bookFileTransactionalHandler.handleNewBookFile(library.getId(), moved);

        assertThat(pathsOf(bookId)).containsExactly("Inbox/Foundation.epub");
        tx.executeWithoutResult(status -> {
            BookEntity book = bookRepository.findById(bookId).orElseThrow();
            assertThat(book.getDeleted()).isFalse();
            assertThat(book.getDeletedAt()).isNull();
        });
    }

    @Test
    void twoBooksWithTheNewFilesContentNeitherMovesAndNothingThrows() throws IOException {
        Path file = epub(root.resolve("Inbox/Foundation.epub"), "Foundation " + System.nanoTime());
        String hash = FileFingerprint.generateHash(file);
        // Both gone from their paths: which one moved cannot be told
        long first = storedBook("Asimov", "Foundation.epub", hash, false);
        long second = storedBook("Asimov/Copies", "Foundation (1).epub", hash, false);

        assertThatCode(() -> bookFileTransactionalHandler.handleNewBookFile(library.getId(), file)).doesNotThrowAnyException();

        assertThat(pathsOf(first)).containsExactly("Asimov/Foundation.epub");
        assertThat(pathsOf(second)).containsExactly("Asimov/Copies/Foundation (1).epub");
        assertThat(bookIdAt("Inbox", "Foundation.epub")).isNotNull().isNotIn(first, second);
    }

    @Test
    void aFileMovedByCopyingThenRemovingTheSourceMovesTheBook() throws Exception {
        // A mover that writes the destination first and removes the source after the watcher saw the destination
        Path source = epub(root.resolve("Asimov/Foundation.epub"), "Foundation " + System.nanoTime());
        long bookId = storedBook("Asimov", "Foundation.epub", FileFingerprint.generateHash(source), false);
        Path destination = Files.copy(source, Files.createDirectories(root.resolve("Moved")).resolve("Foundation.epub"));
        // Written a while ago, so the watcher does not wait for it to settle
        Files.setLastModifiedTime(destination, FileTime.from(Instant.now().minusSeconds(60)));

        libraryFileEventProcessor.processEvent(StandardWatchEventKinds.ENTRY_CREATE, library.getId(), destination, false);
        // The watcher has seen the destination, with its source still there
        Thread.sleep(2000);
        assertThat(bookIdAt("Moved", "Foundation.epub")).isNull();
        Files.delete(source);
        libraryFileEventProcessor.processEvent(StandardWatchEventKinds.ENTRY_DELETE, library.getId(), source, false);

        long deadline = System.currentTimeMillis() + 15000;
        while (!pathsOf(bookId).equals(List.of("Moved/Foundation.epub")) && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        assertThat(pathsOf(bookId)).containsExactly("Moved/Foundation.epub");
        assertThat(bookRepository.countIncludingDeletedByLibraryId(library.getId())).isEqualTo(1);
        // Past the grace period of the source's deletion, the book is still there
        Thread.sleep(9000);
        tx.executeWithoutResult(status -> assertThat(bookRepository.findById(bookId).orElseThrow().getDeleted()).isFalse());
    }

    @Test
    void aCopyWaitingForItsSourceToGoTakesTheBookWhenTheSourcesDeletionComesIn() throws IOException {
        Path source = epub(root.resolve("Asimov/Foundation.epub"), "Foundation " + System.nanoTime());
        long bookId = storedBook("Asimov", "Foundation.epub", FileFingerprint.generateHash(source), false);
        Path destination = Files.copy(source, Files.createDirectories(root.resolve("Moved")).resolve("Foundation.epub"));

        assertThat(bookFileTransactionalHandler.handleNewBookFileUnlessCopy(library.getId(), destination))
                .hasValueSatisfying(copy -> assertThat(copy.bookId()).isEqualTo(bookId));
        assertThat(bookIdAt("Moved", "Foundation.epub")).isNull();
        Files.delete(source);
        sourceDeletionComesIn(source, "Asimov", "Foundation.epub");
        // The copy is handed back once the source's deletion is in
        bookFileTransactionalHandler.handleNewBookFile(library.getId(), destination);

        assertThat(pathsOf(bookId)).containsExactly("Moved/Foundation.epub");
        assertThat(bookRepository.countIncludingDeletedByLibraryId(library.getId())).isEqualTo(1);
        tx.executeWithoutResult(status -> assertThat(bookRepository.findById(bookId).orElseThrow().getDeleted()).isFalse());
    }

    @Test
    void aCopyWaitingForItsSourceToGoTakesTheBookBackWhenTheSourcesDeletionExpiredFirst() throws IOException {
        Path source = epub(root.resolve("Asimov/Foundation.epub"), "Foundation " + System.nanoTime());
        long bookId = storedBook("Asimov", "Foundation.epub", FileFingerprint.generateHash(source), false);
        Path destination = Files.copy(source, Files.createDirectories(root.resolve("Moved")).resolve("Foundation.epub"));

        assertThat(bookFileTransactionalHandler.handleNewBookFileUnlessCopy(library.getId(), destination)).isPresent();
        Files.delete(source);
        sourceDeletionComesIn(source, "Asimov", "Foundation.epub");
        pendingDeletionPool.expireFileDeletion(source);
        bookFileTransactionalHandler.handleNewBookFile(library.getId(), destination);

        assertThat(pathsOf(bookId)).containsExactly("Moved/Foundation.epub");
        assertThat(bookRepository.countIncludingDeletedByLibraryId(library.getId())).isEqualTo(1);
        tx.executeWithoutResult(status -> assertThat(bookRepository.findById(bookId).orElseThrow().getDeleted()).isFalse());
    }

    @Test
    void aCopyWhoseSourceStaysIsANewBookOnceItIsHandedBack() throws IOException {
        Path original = epub(root.resolve("Asimov/Foundation.epub"), "Foundation " + System.nanoTime());
        long bookId = storedBook("Asimov", "Foundation.epub", FileFingerprint.generateHash(original), false);
        Path copy = Files.copy(original, Files.createDirectories(root.resolve("Inbox")).resolve("Foundation.epub"));

        assertThat(bookFileTransactionalHandler.handleNewBookFileUnlessCopy(library.getId(), copy)).isPresent();
        bookFileTransactionalHandler.handleNewBookFile(library.getId(), copy);

        assertThat(pathsOf(bookId)).containsExactly("Asimov/Foundation.epub");
        assertThat(bookIdAt("Inbox", "Foundation.epub")).isNotNull().isNotEqualTo(bookId);
    }

    @Test
    void aFileWithoutAStoredFileOfItsContentIsNotLeftWaiting() throws IOException {
        Path file = epub(root.resolve("Inbox/Dune.epub"), "Dune " + System.nanoTime());

        assertThat(bookFileTransactionalHandler.handleNewBookFileUnlessCopy(library.getId(), file)).isEmpty();

        assertThat(bookIdAt("Inbox", "Dune.epub")).isNotNull();
    }

    // ========== moved by copying into a new folder ==========

    @Test
    void aFileMovedByCopyingIntoANewFolderThenRemovingTheSourceMovesTheBook() throws Exception {
        // The watcher sees a new folder, not the file: a mover that creates the destination's folder, writes the file
        // and removes the source once the watcher handled the folder, as a copy that outlasts the folder's debounce does
        Path source = epub(root.resolve("Asimov/Foundation.epub"), "Foundation " + System.nanoTime());
        long bookId = storedBook("Asimov", "Foundation.epub", FileFingerprint.generateHash(source), false);
        Path folder = Files.createDirectories(root.resolve("Moved"));
        Path destination = settled(Files.copy(source, folder.resolve("Foundation.epub")));

        libraryFileEventProcessor.processEvent(StandardWatchEventKinds.ENTRY_CREATE, library.getId(), folder, true);
        waitUntil(() -> libraryFileEventProcessor.waitsAsCopy(destination));
        assertThat(bookIdAt("Moved", "Foundation.epub")).isNull();
        Files.delete(source);
        libraryFileEventProcessor.processEvent(StandardWatchEventKinds.ENTRY_DELETE, library.getId(), source, false);

        waitUntil(() -> pathsOf(bookId).equals(List.of("Moved/Foundation.epub")));
        assertThat(bookRepository.countIncludingDeletedByLibraryId(library.getId())).isEqualTo(1);
        // Past the grace period of the source's deletion, the book is still there
        Thread.sleep(9000);
        assertThat(pathsOf(bookId)).containsExactly("Moved/Foundation.epub");
        assertNotDeleted(bookId);
    }

    @Test
    void aFileMovedByCopyingIntoANewFolderMovesTheBookWhenTheMoverWritesAFileThatIsNoBookFileForLongerThanTheWait() throws Exception {
        // The mover then writes an extra into the folder, no book file, for longer than the wait, and removes the source
        // once it is done. The wait is longer than the 3 s between checks of a file still written, as the production
        // wait is.
        libraryFileEventProcessor.setCopySourceWaitMs(4000);
        Path source = epub(root.resolve("Asimov/Foundation.epub"), "Foundation " + System.nanoTime());
        long bookId = storedBook("Asimov", "Foundation.epub", FileFingerprint.generateHash(source), false);
        Path folder = Files.createDirectories(root.resolve("Moved"));
        Path destination = settled(Files.copy(source, folder.resolve("Foundation.epub")));
        libraryFileEventProcessor.processEvent(StandardWatchEventKinds.ENTRY_CREATE, library.getId(), folder, true);
        waitUntil(() -> libraryFileEventProcessor.waitsAsCopy(destination));

        // What LibraryWatchService sends for a file that is no book file
        Path extra = Files.writeString(folder.resolve("extras.mkv"), "the start");
        libraryFileEventProcessor.noteFileArrived(library.getId(), extra);
        long until = System.currentTimeMillis() + 10000;
        while (System.currentTimeMillis() < until) {
            Files.writeString(extra, " and more", StandardOpenOption.APPEND);
            Thread.sleep(300);
        }
        assertThat(libraryFileEventProcessor.waitsAsCopy(destination)).isTrue();
        Files.delete(source);
        libraryFileEventProcessor.processEvent(StandardWatchEventKinds.ENTRY_DELETE, library.getId(), source, false);

        waitUntil(() -> pathsOf(bookId).equals(List.of("Moved/Foundation.epub")));
        // Past the grace period of the source's deletion, the book is still there, and it is the only one
        Thread.sleep(9000);
        assertThat(pathsOf(bookId)).containsExactly("Moved/Foundation.epub");
        assertNotDeleted(bookId);
        assertThat(bookRepository.countIncludingDeletedByLibraryId(library.getId())).isEqualTo(1);
    }

    @Test
    void bookPerFile_aBookFolderMovedByCopyingThenRemovedMovesItsBook() throws Exception {
        // GNU mv across file systems: the tree is copied, then removed, and the watcher reports the removed folder
        // as well as its file; both are held for deletion, and the one the copy does not take must not delete the book
        organizedAs(LibraryOrganizationMode.BOOK_PER_FILE);
        Path sourceFolder = root.resolve("Asimov");
        Path source = epub(sourceFolder.resolve("Foundation.epub"), "Foundation " + System.nanoTime());
        long bookId = storedBook("Asimov", "Foundation.epub", FileFingerprint.generateHash(source), false);
        Path folder = Files.createDirectories(root.resolve("Shelf"));
        Path destination = settled(Files.copy(source, folder.resolve("Foundation.epub")));

        libraryFileEventProcessor.processEvent(StandardWatchEventKinds.ENTRY_CREATE, library.getId(), folder, true);
        waitUntil(() -> libraryFileEventProcessor.waitsAsCopy(destination));
        assertThat(bookIdAt("Shelf", "Foundation.epub")).isNull();
        removeTree(sourceFolder);
        libraryFileEventProcessor.processEvent(StandardWatchEventKinds.ENTRY_DELETE, library.getId(), sourceFolder, true);
        libraryFileEventProcessor.processEvent(StandardWatchEventKinds.ENTRY_DELETE, library.getId(), source, false);

        waitUntil(() -> pathsOf(bookId).equals(List.of("Shelf/Foundation.epub")));
        Thread.sleep(9000);
        assertThat(pathsOf(bookId)).containsExactly("Shelf/Foundation.epub");
        assertNotDeleted(bookId);
        assertThat(bookRepository.countIncludingDeletedByLibraryId(library.getId())).isEqualTo(1);
    }

    @Test
    void bookPerFolder_aBookFolderMovedByCopyingIntoANewFolderThenRemovedMovesTheBookWithAllItsFiles() throws Exception {
        organizedAs(LibraryOrganizationMode.BOOK_PER_FOLDER);
        Path sourceFolder = root.resolve("Asimov/Foundation");
        Path epub = epub(sourceFolder.resolve("Foundation.epub"), "Foundation " + System.nanoTime());
        Path pdf = Files.writeString(sourceFolder.resolve("Foundation.pdf"), "%PDF-1.4 Foundation " + System.nanoTime());
        long bookId = storedBook(
                new StoredFile("Asimov/Foundation", "Foundation.epub", FileFingerprint.generateHash(epub), BookFileType.EPUB, false),
                new StoredFile("Asimov/Foundation", "Foundation.pdf", FileFingerprint.generateHash(pdf), BookFileType.PDF, false));
        Path shelf = root.resolve("Shelf");
        copyTree(sourceFolder, shelf.resolve("Foundation"));

        libraryFileEventProcessor.processEvent(StandardWatchEventKinds.ENTRY_CREATE, library.getId(), shelf, true);
        waitUntil(() -> libraryFileEventProcessor.waitsAsCopy(shelf));
        assertThat(bookRepository.countIncludingDeletedByLibraryId(library.getId())).isEqualTo(1);
        removeTree(sourceFolder);
        libraryFileEventProcessor.processEvent(StandardWatchEventKinds.ENTRY_DELETE, library.getId(), epub, false);
        libraryFileEventProcessor.processEvent(StandardWatchEventKinds.ENTRY_DELETE, library.getId(), pdf, false);
        libraryFileEventProcessor.processEvent(StandardWatchEventKinds.ENTRY_DELETE, library.getId(), sourceFolder, true);

        List<String> moved = List.of("Shelf/Foundation/Foundation.epub", "Shelf/Foundation/Foundation.pdf");
        waitUntil(() -> Set.copyOf(pathsOf(bookId)).equals(Set.copyOf(moved)));
        Thread.sleep(9000);
        assertThat(pathsOf(bookId)).containsExactlyInAnyOrderElementsOf(moved);
        assertNotDeleted(bookId);
        assertThat(bookRepository.countIncludingDeletedByLibraryId(library.getId())).isEqualTo(1);
    }

    @Test
    void aFolderAudiobookMovedByCopyingThenRemovingTheSourceMovesTheBook() throws Exception {
        Path source = root.resolve("Audio/Dune");
        audio(source.resolve("Dune - 01.mp3"));
        audio(source.resolve("Dune - 02.mp3"));
        long bookId = storedBook(new StoredFile("Audio", "Dune", FileFingerprint.generateFolderHash(source), BookFileType.AUDIOBOOK, true));
        Path destination = root.resolve("Listening/Dune");
        copyTree(source, destination);

        libraryFileEventProcessor.processEvent(StandardWatchEventKinds.ENTRY_CREATE, library.getId(), destination, true);
        waitUntil(() -> libraryFileEventProcessor.waitsAsCopy(destination));
        removeTree(source);
        libraryFileEventProcessor.processEvent(StandardWatchEventKinds.ENTRY_DELETE, library.getId(), source, true);

        waitUntil(() -> pathsOf(bookId).equals(List.of("Listening/Dune")));
        Thread.sleep(9000);
        assertThat(pathsOf(bookId)).containsExactly("Listening/Dune");
        assertNotDeleted(bookId);
        assertThat(bookRepository.countIncludingDeletedByLibraryId(library.getId())).isEqualTo(1);
    }

    @Test
    void aFileCopiedIntoANewFolderIsANewBookOnceTheWaitIsOverAndItsOriginalStays() throws Exception {
        libraryFileEventProcessor.setCopySourceWaitMs(2000);
        Path original = epub(root.resolve("Asimov/Foundation.epub"), "Foundation " + System.nanoTime());
        long bookId = storedBook("Asimov", "Foundation.epub", FileFingerprint.generateHash(original), false);
        Path folder = Files.createDirectories(root.resolve("Copies"));
        settled(Files.copy(original, folder.resolve("Foundation.epub")));

        libraryFileEventProcessor.processEvent(StandardWatchEventKinds.ENTRY_CREATE, library.getId(), folder, true);

        waitUntil(() -> bookIdAt("Copies", "Foundation.epub") != null);
        assertThat(bookIdAt("Copies", "Foundation.epub")).isNotEqualTo(bookId);
        assertThat(pathsOf(bookId)).containsExactly("Asimov/Foundation.epub");
        assertNotDeleted(bookId);
        assertThat(bookRepository.countIncludingDeletedByLibraryId(library.getId())).isEqualTo(2);
    }

    @Test
    void bookPerFolder_aBookFolderCopiedIntoANewFolderIsANewBookOnceTheWaitIsOverAndItsOriginalStays() throws Exception {
        organizedAs(LibraryOrganizationMode.BOOK_PER_FOLDER);
        libraryFileEventProcessor.setCopySourceWaitMs(2000);
        Path sourceFolder = root.resolve("Asimov/Foundation");
        Path original = epub(sourceFolder.resolve("Foundation.epub"), "Foundation " + System.nanoTime());
        long bookId = storedBook("Asimov/Foundation", "Foundation.epub", FileFingerprint.generateHash(original), false);
        Path copies = root.resolve("Copies");
        copyTree(sourceFolder, copies.resolve("Foundation"));

        libraryFileEventProcessor.processEvent(StandardWatchEventKinds.ENTRY_CREATE, library.getId(), copies, true);

        waitUntil(() -> bookIdAt("Copies/Foundation", "Foundation.epub") != null);
        assertThat(bookIdAt("Copies/Foundation", "Foundation.epub")).isNotEqualTo(bookId);
        assertThat(pathsOf(bookId)).containsExactly("Asimov/Foundation/Foundation.epub");
        assertNotDeleted(bookId);
        assertThat(bookRepository.countIncludingDeletedByLibraryId(library.getId())).isEqualTo(2);
    }

    // ========== moved by a mover that removes each source as soon as it copied it ==========

    @Test
    void aFileMovedIntoANewFolderWhileAnotherFileThereIsStillWrittenMovesItsBookAtOnce() throws Exception {
        moveOneFileIntoANewFolderWhileAnotherIsWritten();
    }

    @Test
    void bookPerFolder_aFileMovedIntoANewFolderWhileAnotherFileThereIsStillWrittenMovesItsBookAtOnce() throws Exception {
        organizedAs(LibraryOrganizationMode.BOOK_PER_FOLDER);
        moveOneFileIntoANewFolderWhileAnotherIsWritten();
    }

    @Test
    void filesMovedOneByOneIntoANewFolderForLongerThanTheGracePeriodEachMoveTheirBook() throws Exception {
        // A mover that copies each file into a new folder and removes its source at once, a file every 4 s: each file
        // puts the folder's handling off again, so it is handled once the last came, past the first deletions' grace
        Path foundation = epub(root.resolve("Asimov/Foundation.epub"), "Foundation " + System.nanoTime());
        Path pdf = Files.writeString(root.resolve("Asimov/Foundation.pdf"), "%PDF-1.4 Foundation " + System.nanoTime());
        long foundationBook = storedBook(
                new StoredFile("Asimov", "Foundation.epub", FileFingerprint.generateHash(foundation), BookFileType.EPUB, false),
                new StoredFile("Asimov", "Foundation.pdf", FileFingerprint.generateHash(pdf), BookFileType.PDF, false));
        Path robot = epub(root.resolve("Asimov/I, Robot.epub"), "I, Robot " + System.nanoTime());
        long robotBook = storedBook("Asimov", "I, Robot.epub", FileFingerprint.generateHash(robot), false);
        Path caves = epub(root.resolve("Asimov/The Caves of Steel.epub"), "The Caves of Steel " + System.nanoTime());
        long cavesBook = storedBook("Asimov", "The Caves of Steel.epub", FileFingerprint.generateHash(caves), false);
        Path folder = Files.createDirectories(root.resolve("Moved"));

        libraryFileEventProcessor.processEvent(StandardWatchEventKinds.ENTRY_CREATE, library.getId(), folder, true);
        for (Path source : List.of(foundation, robot, caves)) {
            Path copy = settled(Files.copy(source, folder.resolve(source.getFileName())));
            libraryFileEventProcessor.processEvent(StandardWatchEventKinds.ENTRY_CREATE, library.getId(), copy, false);
            Files.delete(source);
            libraryFileEventProcessor.processEvent(StandardWatchEventKinds.ENTRY_DELETE, library.getId(), source, false);
            Thread.sleep(4000);
        }

        waitUntil(() -> pathsOf(cavesBook).equals(List.of("Moved/The Caves of Steel.epub")));
        Thread.sleep(9000);
        assertThat(pathsOf(foundationBook)).containsExactlyInAnyOrder("Moved/Foundation.epub", "Asimov/Foundation.pdf");
        assertThat(pathsOf(robotBook)).containsExactly("Moved/I, Robot.epub");
        assertThat(pathsOf(cavesBook)).containsExactly("Moved/The Caves of Steel.epub");
        assertNotDeleted(foundationBook);
        assertNotDeleted(robotBook);
        assertNotDeleted(cavesBook);
        assertThat(bookRepository.countIncludingDeletedByLibraryId(library.getId())).isEqualTo(3);
    }

    /**
     * GNU mv with several sources across devices, or a file-by-file importer: Foundation.epub is copied into a new folder
     * and its source removed at once, while another file is written into that folder for longer than the pool holds the
     * book. The book has a PDF too, so had its deletion expired, the moved EPUB's row would have been deleted with it.
     */
    private void moveOneFileIntoANewFolderWhileAnotherIsWritten() throws Exception {
        Path source = epub(root.resolve("Asimov/Foundation.epub"), "Foundation " + System.nanoTime());
        Path pdf = Files.writeString(root.resolve("Asimov/Foundation.pdf"), "%PDF-1.4 Foundation " + System.nanoTime());
        long bookId = storedBook(
                new StoredFile("Asimov", "Foundation.epub", FileFingerprint.generateHash(source), BookFileType.EPUB, false),
                new StoredFile("Asimov", "Foundation.pdf", FileFingerprint.generateHash(pdf), BookFileType.PDF, false));
        Path folder = Files.createDirectories(root.resolve("Moved"));
        settled(Files.copy(source, folder.resolve("Foundation.epub")));
        Path other = Files.writeString(folder.resolve("Other Book.pdf"), "%PDF-1.4 Other Book " + System.nanoTime());

        libraryFileEventProcessor.processEvent(StandardWatchEventKinds.ENTRY_CREATE, library.getId(), folder, true);
        Files.delete(source);
        libraryFileEventProcessor.processEvent(StandardWatchEventKinds.ENTRY_DELETE, library.getId(), source, false);
        long until = System.currentTimeMillis() + 15000;
        while (System.currentTimeMillis() < until) {
            Files.writeString(other, " more", StandardOpenOption.APPEND);
            Thread.sleep(500);
        }

        assertThat(pathsOf(bookId)).containsExactlyInAnyOrder("Moved/Foundation.epub", "Asimov/Foundation.pdf");
        assertNotDeleted(bookId);
        // The other file is handled once it is written
        waitUntil(() -> bookIdAt("Moved", "Other Book.pdf") != null);
        assertThat(pathsOf(bookId)).contains("Moved/Foundation.epub", "Asimov/Foundation.pdf");
        assertNotDeleted(bookId);
    }

    // ========== an author folder holding a folder audiobook ==========

    @Test
    void anAuthorFolderHoldingAFolderAudiobookMovedByCopyingThenRemovedMovesTheBook() throws Exception {
        Path sourceAuthor = root.resolve("Audio/Herbert");
        Path source = sourceAuthor.resolve("Dune");
        audio(source.resolve("Dune - 01.mp3"));
        audio(source.resolve("Dune - 02.mp3"));
        long bookId = storedBook(new StoredFile("Audio/Herbert", "Dune", FileFingerprint.generateFolderHash(source), BookFileType.AUDIOBOOK, true));
        Path destinationAuthor = root.resolve("Listening/Herbert");
        copyTree(source, destinationAuthor.resolve("Dune"));

        libraryFileEventProcessor.processEvent(StandardWatchEventKinds.ENTRY_CREATE, library.getId(), destinationAuthor, true);
        waitUntil(() -> libraryFileEventProcessor.waitsAsCopy(destinationAuthor.resolve("Dune")));
        removeTree(sourceAuthor);
        libraryFileEventProcessor.processEvent(StandardWatchEventKinds.ENTRY_DELETE, library.getId(), source, true);
        libraryFileEventProcessor.processEvent(StandardWatchEventKinds.ENTRY_DELETE, library.getId(), sourceAuthor, true);

        waitUntil(() -> pathsOf(bookId).equals(List.of("Listening/Herbert/Dune")));
        Thread.sleep(9000);
        assertThat(pathsOf(bookId)).containsExactly("Listening/Herbert/Dune");
        assertNotDeleted(bookId);
        assertThat(bookRepository.countIncludingDeletedByLibraryId(library.getId())).isEqualTo(1);
    }

    @Test
    void anAuthorFolderHoldingAFolderAudiobookRenamedMovesTheBook() throws Exception {
        Path sourceAuthor = root.resolve("Audio/Herbert");
        Path source = sourceAuthor.resolve("Dune");
        audio(source.resolve("Dune - 01.mp3"));
        audio(source.resolve("Dune - 02.mp3"));
        long bookId = storedBook(new StoredFile("Audio/Herbert", "Dune", FileFingerprint.generateFolderHash(source), BookFileType.AUDIOBOOK, true));
        Path destinationAuthor = Files.createDirectories(root.resolve("Listening")).resolve("Herbert");
        Files.move(sourceAuthor, destinationAuthor);

        // What a rename reports: the folder's deletion, and its creation where it went
        libraryFileEventProcessor.processEvent(StandardWatchEventKinds.ENTRY_DELETE, library.getId(), sourceAuthor, true);
        libraryFileEventProcessor.processEvent(StandardWatchEventKinds.ENTRY_CREATE, library.getId(), destinationAuthor, true);

        waitUntil(() -> pathsOf(bookId).equals(List.of("Listening/Herbert/Dune")));
        Thread.sleep(9000);
        assertThat(pathsOf(bookId)).containsExactly("Listening/Herbert/Dune");
        assertNotDeleted(bookId);
        assertThat(bookRepository.countIncludingDeletedByLibraryId(library.getId())).isEqualTo(1);
    }

    @Test
    void aStoredFolderAudiobookAnnouncedAgainIsNotLeftWaitingAsACopyOfItself() throws Exception {
        Path folder = root.resolve("Audio/Dune");
        audio(folder.resolve("Dune - 01.mp3"));
        audio(folder.resolve("Dune - 02.mp3"));
        long bookId = storedBook(new StoredFile("Audio", "Dune", FileFingerprint.generateFolderHash(folder), BookFileType.AUDIOBOOK, true));

        // What LibraryWatchService.handleOverflow sends for the folder's watch key
        libraryFileEventProcessor.processEvent(StandardWatchEventKinds.ENTRY_CREATE, library.getId(), folder, true);

        // Handled after the folder's debounce, and not left waiting: an in-app move in the library would wait out the
        // drain timeout meanwhile
        Thread.sleep(8000);
        assertThat(libraryFileEventProcessor.waitsAsCopy(folder)).isFalse();
        assertThat(libraryFileEventProcessor.hasPendingEventsForPaths(Set.of(root))).isFalse();
        assertThat(pathsOf(bookId)).containsExactly("Audio/Dune");
        assertNotDeleted(bookId);
        assertThat(bookRepository.countIncludingDeletedByLibraryId(library.getId())).isEqualTo(1);
    }

    // ========== fixture ==========

    /** What the watcher does with the deletion of a stored file: the pool holds its book for a grace period. */
    private void sourceDeletionComesIn(Path source, String subPath, String fileName) {
        BookFileEntity file = bookFilePersistenceService.findBookFileByLibraryPathSubPathAndFileName(
                libraryPath.getId(), subPath, fileName).orElseThrow();
        pendingDeletionPool.addFileDeletion(source, library.getId(), file, file.getBook(), mock(ScheduledFuture.class));
    }

    private record StoredFile(String subPath, String fileName, String hash, BookFileType type, boolean folderBased) {
    }

    /** A book that is not deleted, with these files. */
    private long storedBook(StoredFile... files) {
        return tx.execute(status -> {
            BookEntity book = BookEntity.builder()
                    .library(libraryRepository.findById(library.getId()).orElseThrow())
                    .libraryPath(libraryPath)
                    .addedOn(Instant.now())
                    .deleted(false)
                    .bookFiles(new ArrayList<>())
                    .build();
            for (StoredFile file : files) {
                book.getBookFiles().add(BookFileEntity.builder()
                        .book(book).fileSubPath(file.subPath()).fileName(file.fileName()).bookType(file.type())
                        .isBookFormat(true).folderBased(file.folderBased())
                        .currentHash(file.hash()).initialHash(file.hash()).addedOn(Instant.now())
                        .build());
            }
            book.setMetadata(BookMetadataEntity.builder().book(book).title("Foundation").build());
            return bookRepository.save(book).getId();
        });
    }

    private void organizedAs(LibraryOrganizationMode mode) {
        tx.executeWithoutResult(status -> libraryRepository.findById(library.getId()).orElseThrow().setOrganizationMode(mode));
    }

    private void assertNotDeleted(long bookId) {
        tx.executeWithoutResult(status -> assertThat(bookRepository.findById(bookId).orElseThrow().getDeleted()).isFalse());
    }

    private static void waitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }

    /** Written a while ago, so the watcher does not wait for it to settle. */
    private static Path settled(Path file) throws IOException {
        Files.setLastModifiedTime(file, FileTime.from(Instant.now().minusSeconds(60)));
        return file;
    }

    /** A copy of the folder and its files, as a mover writes it, with every file settled. */
    private static void copyTree(Path from, Path to) throws IOException {
        Files.createDirectories(to);
        try (var files = Files.list(from)) {
            for (Path file : files.toList()) {
                settled(Files.copy(file, to.resolve(file.getFileName())));
            }
        }
    }

    private static void removeTree(Path folder) throws IOException {
        try (var paths = Files.walk(folder)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    /** An audio track whose content, and so its hash, is its own. */
    private static void audio(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, "ID3 " + file.getFileName() + " " + System.nanoTime());
        settled(file);
    }

    private long storedBook(String subPath, String fileName, String hash, boolean deleted) {
        return tx.execute(status -> {
            BookEntity book = BookEntity.builder()
                    .library(libraryRepository.findById(library.getId()).orElseThrow())
                    .libraryPath(libraryPath)
                    .addedOn(Instant.now())
                    .deleted(deleted)
                    .deletedAt(deleted ? Instant.now().minusSeconds(10) : null)
                    .bookFiles(new ArrayList<>())
                    .build();
            book.getBookFiles().add(BookFileEntity.builder()
                    .book(book).fileSubPath(subPath).fileName(fileName).bookType(BookFileType.EPUB).isBookFormat(true)
                    .currentHash(hash).initialHash(hash).addedOn(Instant.now())
                    .build());
            book.setMetadata(BookMetadataEntity.builder().book(book).title("Foundation").build());
            return bookRepository.save(book).getId();
        });
    }

    private List<String> pathsOf(long bookId) {
        return tx.execute(status -> bookRepository.findById(bookId).orElseThrow().getBookFiles().stream()
                .map(file -> file.getFileSubPath() + "/" + file.getFileName())
                .toList());
    }

    private Long bookIdAt(String subPath, String fileName) {
        return tx.execute(status -> bookAdditionalFileRepository.findExactlyAt(libraryPath.getId(), subPath, fileName)
                .map(file -> file.getBook().getId())
                .orElse(null));
    }

    /** A minimal EPUB with a title, which also makes its content, and so its hash, its own. */
    private static Path epub(Path file, String title) throws IOException {
        Files.createDirectories(file.getParent());
        String opf = """
                <?xml version="1.0" encoding="UTF-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>%s</dc:title></metadata>
                  <manifest></manifest>
                </package>""".formatted(title);
        String container = """
                <?xml version="1.0" encoding="UTF-8"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
                </container>""";
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
            for (Map.Entry<String, String> entry : List.of(Map.entry("mimetype", "application/epub+zip"),
                    Map.entry("META-INF/container.xml", container), Map.entry("OEBPS/content.opf", opf))) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return file;
    }
}
