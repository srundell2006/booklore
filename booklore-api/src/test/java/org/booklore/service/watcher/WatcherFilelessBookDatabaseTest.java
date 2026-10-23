package org.booklore.service.watcher;

import org.booklore.BookloreApplication;
import org.booklore.model.dto.settings.LibraryFile;
import org.booklore.model.entity.AuthorEntity;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.entity.LibraryPathEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.repository.AuthorRepository;
import org.booklore.repository.BookAdditionalFileRepository;
import org.booklore.repository.BookRepository;
import org.booklore.repository.LibraryRepository;
import org.booklore.service.book.PhysicalBookService;
import org.booklore.service.library.ExistingBookAttacher;
import org.booklore.service.library.FilelessShells;
import org.booklore.service.library.matching.ExistingBookMatcher.Kind;
import org.booklore.service.task.TaskCronService;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * The watcher matches a new file against the library's fileless books as it read them up to ten minutes before. A
 * fileless book edited since then (retitled, or no longer a physical book) must not take a file named for what it
 * was. Nor may a watcher event and a rescan, attaching at once, give one fileless book the files of two folders.
 * Runs the watcher's handler and the shared attacher against H2 with the files on disk.
 */
@SpringBootTest(classes = {
        BookloreApplication.class
})
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.datasource.url=jdbc:h2:mem:watcherfileless;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=20000",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "app.path-config=build/tmp/watcher-fileless-config",
        "app.bookdrop-folder=build/tmp/test-bookdrop",
        "spring.main.allow-bean-definition-overriding=true",
        "spring.task.scheduling.enabled=false",
        "app.task.scan-library-cron=*/1 * * * * *",
        "app.task.process-bookdrop-cron=*/1 * * * * *",
        "app.features.oidc-enabled=false"
})
@Import(WatcherFilelessBookDatabaseTest.TestConfig.class)
class WatcherFilelessBookDatabaseTest {

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
    private AuthorRepository authorRepository;
    @Autowired
    private BookAdditionalFileRepository bookAdditionalFileRepository;
    @Autowired
    private BookFileTransactionalHandler bookFileTransactionalHandler;
    @Autowired
    private PhysicalBookService physicalBookService;
    @Autowired
    private ExistingBookAttacher existingBookAttacher;
    @MockitoSpyBean
    private FilelessShells filelessShells;

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

    @Test
    void aFilelessBookRetitledSinceTheWatcherReadItTakesNoFileOfItsOldTitle() throws IOException {
        long shell = filelessBook("The Martian", "Andy Weir", false);
        // Any event reads the library's fileless books into the watcher's index
        bookFileTransactionalHandler.handleNewBookFile(library.getId(), epub("Misc/Unrelated Thing.epub"));

        tx.executeWithoutResult(status -> bookRepository.findById(shell).orElseThrow().getMetadata().setTitle("Project Hail Mary"));
        bookFileTransactionalHandler.handleNewBookFile(library.getId(), epub("Weir, Andy/The Martian.epub"));

        assertThat(pathsOf(shell)).isEmpty();
        assertThat(bookIdAt("Weir, Andy", "The Martian.epub")).isNotNull().isNotEqualTo(shell);
    }

    @Test
    void anAuthorlessFilelessBookNoLongerPhysicalSinceTheWatcherReadItTakesNoFile() throws IOException {
        long shell = filelessBook("A Field Guide to Birds", null, true);
        bookFileTransactionalHandler.handleNewBookFile(library.getId(), epub("Misc/Unrelated Thing.epub"));

        // D1: a fileless book without authors only takes a file while it is a physical book
        physicalBookService.togglePhysicalFlag(shell, false);
        bookFileTransactionalHandler.handleNewBookFile(library.getId(), epub("Nature/A Field Guide to Birds.epub"));

        assertThat(pathsOf(shell)).isEmpty();
        assertThat(bookIdAt("Nature", "A Field Guide to Birds.epub")).isNotNull().isNotEqualTo(shell);
    }

    @Test
    void aFilelessBookTheWatcherReadTakesTheFileOfItsTitle() throws IOException {
        long shell = filelessBook("The Martian", "Andy Weir", false);
        bookFileTransactionalHandler.handleNewBookFile(library.getId(), epub("Misc/Unrelated Thing.epub"));

        bookFileTransactionalHandler.handleNewBookFile(library.getId(), epub("Weir, Andy/The Martian.epub"));

        assertThat(pathsOf(shell)).containsExactly("Weir, Andy/The Martian.epub");
    }

    @Test
    void twoAttachesToOneFilelessBookAtOnceGiveItTheFilesOfOneFolderOnly() throws Exception {
        long shell = filelessBook("The Martian", "Andy Weir", false);
        // With a cover and a library path, an attach adds the book's file and changes nothing else of the book
        new JdbcTemplate(dataSource).update("UPDATE book SET book_cover_hash = 'cover', library_path_id = ? WHERE id = ?",
                libraryPath.getId(), shell);
        epub("Weir, Andy/The Martian.epub");
        epub("Andy Weir/The Martian.epub");
        // The first attach stops once it has stored its file, before its transaction commits
        CountDownLatch firstStored = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            firstStored.countDown();
            release.await(30, TimeUnit.SECONDS);
            return invocation.callRealMethod();
        }).doCallRealMethod().when(filelessShells).notFileless(shell);

        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            // The watcher's attach from one folder, the rescan's from another, each matched while the book had no file
            Future<List<LibraryFile>> first = threads.submit(() ->
                    existingBookAttacher.attach(shell, Kind.FILELESS, List.of(libraryFile("Weir, Andy", "The Martian.epub"))));
            assertThat(firstStored.await(30, TimeUnit.SECONDS)).isTrue();
            Future<List<LibraryFile>> second = threads.submit(() ->
                    existingBookAttacher.attach(shell, Kind.FILELESS, List.of(libraryFile("Andy Weir", "The Martian.epub"))));
            Thread.sleep(500);
            assertThat(second.isDone()).as("the second attach waits for the first to commit").isFalse();
            release.countDown();

            assertThat(first.get(30, TimeUnit.SECONDS)).hasSize(1);
            assertThat(second.get(30, TimeUnit.SECONDS)).isEmpty();
        } finally {
            release.countDown();
            threads.shutdownNow();
        }
        assertThat(pathsOf(shell)).containsExactly("Weir, Andy/The Martian.epub");
        assertThat(bookIdAt("Andy Weir", "The Martian.epub")).isNull();
    }

    @Test
    void anAttachInAWatcherEventSeesTheFileARescanGaveTheBookAfterTheEventBegan() throws Exception {
        long shell = filelessBook("The Martian", "Andy Weir", false);
        new JdbcTemplate(dataSource).update("UPDATE book SET book_cover_hash = 'cover', library_path_id = ? WHERE id = ?",
                libraryPath.getId(), shell);
        epub("Weir, Andy/The Martian.epub");
        epub("Andy Weir/The Martian.epub");

        // The watcher's event runs in a transaction that has read the database before it attaches. On MariaDB, at its
        // default REPEATABLE READ, that transaction's reads then see the data as it was at its first read. H2's
        // REPEATABLE READ still sees rows other transactions add since, and its SERIALIZABLE does not, as MariaDB's
        // REPEATABLE READ does not: an attach that joined the event's transaction would miss the rescan's file here.
        // Only on H2: MariaDB's SERIALIZABLE reads with locks, which see the rescan's file, so there the test keeps
        // the default, at which it fails for an attach that joins the event's transaction
        TransactionTemplate eventTx = new TransactionTemplate(transactionManager);
        if (onH2()) {
            eventTx.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
        }
        ExecutorService threads = Executors.newSingleThreadExecutor();
        try {
            List<LibraryFile> inEvent = eventTx.execute(status -> {
                bookRepository.count();
                try {
                    assertThat(threads.submit(() -> existingBookAttacher.attach(shell, Kind.FILELESS,
                            List.of(libraryFile("Weir, Andy", "The Martian.epub")))).get(30, TimeUnit.SECONDS)).hasSize(1);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                return existingBookAttacher.attach(shell, Kind.FILELESS, List.of(libraryFile("Andy Weir", "The Martian.epub")));
            });
            assertThat(inEvent).isEmpty();
        } finally {
            threads.shutdownNow();
        }
        assertThat(pathsOf(shell)).containsExactly("Weir, Andy/The Martian.epub");
    }

    // ========== fixture ==========

    private boolean onH2() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            return "H2".equals(connection.getMetaData().getDatabaseProductName());
        }
    }

    private LibraryFile libraryFile(String subPath, String name) {
        return LibraryFile.builder()
                .libraryEntity(library)
                .libraryPathEntity(libraryPath)
                .fileSubPath(subPath)
                .fileName(name)
                .bookFileType(BookFileType.EPUB)
                .build();
    }

    private long filelessBook(String title, String author, boolean physical) {
        return tx.execute(status -> {
            BookEntity book = BookEntity.builder()
                    .library(libraryRepository.findById(library.getId()).orElseThrow())
                    .isPhysical(physical)
                    .addedOn(Instant.now())
                    .build();
            BookMetadataEntity metadata = BookMetadataEntity.builder().book(book).title(title).build();
            metadata.setAuthors(new ArrayList<>());
            if (author != null) {
                metadata.getAuthors().add(authorRepository.findByName(author)
                        .orElseGet(() -> authorRepository.save(AuthorEntity.builder().name(author).build())));
            }
            book.setMetadata(metadata);
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

    /** A minimal EPUB whose title makes its content, and so its hash, its own. */
    private Path epub(String relative) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        String opf = """
                <?xml version="1.0" encoding="UTF-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>%s</dc:title></metadata>
                  <manifest></manifest>
                </package>""".formatted(relative + " " + System.nanoTime());
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
