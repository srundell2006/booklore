package org.booklore.service.watcher;

import org.booklore.BookloreApplication;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The watcher matches a new file against the library's fileless books as it read them up to ten minutes before. A
 * fileless book edited since then (retitled, or no longer a physical book) must not take a file named for what it
 * was. Runs the watcher's handler against H2 with the files on disk.
 */
@SpringBootTest(classes = {
        BookloreApplication.class
})
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.datasource.url=jdbc:h2:mem:watcherfileless;DB_CLOSE_DELAY=-1",
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

    // ========== fixture ==========

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
