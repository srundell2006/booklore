package org.booklore.service.library;

import org.booklore.BookloreApplication;
import org.booklore.model.dto.settings.LibraryFile;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.entity.LibraryPathEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.repository.BookAdditionalFileRepository;
import org.booklore.repository.BookRepository;
import org.booklore.repository.LibraryRepository;
import org.booklore.service.library.importer.ImportSummary;
import org.booklore.service.library.importer.ParallelBookImporter;
import org.booklore.service.library.matching.ExistingBookMatcher.Kind;
import org.booklore.service.task.TaskCronService;
import org.booklore.service.watcher.BookFilePersistenceService;
import org.booklore.service.watcher.BookFileTransactionalHandler;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * A file is the file at exactly its path. MariaDB compares the stored paths through the columns' collation, which ignores
 * case, and with the uca1400 accent-insensitive collations also accents and the composed or decomposed form of a letter,
 * while a library walk compares paths as Java compares strings. Here H2 compares strings at primary strength, which
 * equates the same, so each lookup by path returns the other files MariaDB would return: the new file must still get a
 * book and a row of its own, and the stored file's book must stay as it was.
 */
@SpringBootTest(classes = {
        BookloreApplication.class
})
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        // Case- and accent-insensitive, and blind to how an accented letter is encoded, like utf8mb4_uca1400_ai_ci
        "spring.datasource.url=jdbc:h2:mem:exactpaths;DB_CLOSE_DELAY=-1;INIT=SET COLLATION ENGLISH STRENGTH PRIMARY",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "app.path-config=build/tmp/exact-paths-config",
        "app.bookdrop-folder=build/tmp/test-bookdrop",
        "spring.main.allow-bean-definition-overriding=true",
        "spring.task.scheduling.enabled=false",
        "app.task.scan-library-cron=*/1 * * * * *",
        "app.task.process-bookdrop-cron=*/1 * * * * *",
        "app.features.oidc-enabled=false"
})
@Import(ExactPathIdentityDatabaseTest.TestConfig.class)
class ExactPathIdentityDatabaseTest {

    /** "Les Miserables.epub" with an acute e, written as one character, as most clients write it. */
    private static final String COMPOSED = "Les Mis\u00e9rables.epub";
    /** The same name with its acute e written as an e and a combining accent, as macOS clients write it over SMB. */
    private static final String DECOMPOSED = "Les Mise\u0301rables.epub";
    /**
     * The same name without its accent. Files on disk here have plain names: the test JVM may not run in a UTF-8
     * locale, and then cannot name a file with an accent. The database compares this name equal to both of the above.
     */
    private static final String PLAIN = "Les Miserables.epub";

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
    private ParallelBookImporter parallelBookImporter;
    @Autowired
    private FileAsBookProcessor fileAsBookProcessor;
    @Autowired
    private ExistingBookAttacher existingBookAttacher;
    @Autowired
    private BookFileTransactionalHandler bookFileTransactionalHandler;
    @Autowired
    private BookFilePersistenceService bookFilePersistenceService;

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
                    .name("Books " + System.nanoTime()).icon("book").watch(false)
                    .formatPriority(new ArrayList<>(List.of(BookFileType.EPUB, BookFileType.PDF)))
                    .libraryPaths(new ArrayList<>())
                    .build());
            created.getLibraryPaths().add(LibraryPathEntity.builder().library(created).path(root.toString()).build());
            return libraryRepository.save(created);
        });
        libraryPath = library.getLibraryPaths().getFirst();
    }

    @Test
    void theDatabaseHereComparesPathsAsMariaDbDoes() {
        long bookId = storedBook("Hugo", COMPOSED, "Curated", false);

        // Each of these is another file than the stored one, and the database returns the stored one for each
        for (String name : List.of(DECOMPOSED, "les mis\u00e9rables.epub", "LES MISERABLES.EPUB")) {
            List<BookFileEntity> found = bookAdditionalFileRepository.findAllByLibraryPathIdAndFileSubPathAndFileName(
                    libraryPath.getId(), "hugo", name);
            assertThat(found).as(name).extracting(file -> file.getBook().getId()).containsExactly(bookId);
            assertThat(bookAdditionalFileRepository.findExactlyAt(libraryPath.getId(), "hugo", name)).as(name).isEmpty();
            assertThat(bookFilePersistenceService.findBookFileByLibraryPathSubPathAndFileName(libraryPath.getId(), "hugo", name)).as(name).isEmpty();
        }
        assertThat(bookAdditionalFileRepository.findExactlyAt(libraryPath.getId(), "Hugo", COMPOSED)).isPresent();
        assertThat(bookFilePersistenceService.findBookFileByLibraryPathSubPathAndFileName(libraryPath.getId(), "Hugo", COMPOSED)).isPresent();
    }

    @Test
    void aNewFileWhosePathOnlyComparesEqualToAStoredOneGetsABookOfItsOwnOnARescan() throws IOException {
        long curated = storedBook("Hugo", COMPOSED, "Curated", false);
        epub(root.resolve("Hugo").resolve(PLAIN), "From The File");

        ImportSummary summary = parallelBookImporter.importGroups(Map.of("g", List.of(onDisk("Hugo", PLAIN, BookFileType.EPUB))), library);

        assertThat(summary.added()).isEqualTo(1);
        assertUntouched(curated, "Hugo", COMPOSED);
        Long added = bookIdAt("Hugo", PLAIN);
        assertThat(added).isNotNull().isNotEqualTo(curated);
        assertThat(title(added)).isEqualTo("From The File");
    }

    @Test
    void aNewFileWhoseFolderOnlyDiffersInCaseGetsABookOfItsOwnTheWayTheWatcherImports() throws IOException {
        long curated = storedBook("Hugo", PLAIN, "Curated", true);
        epub(root.resolve("hugo").resolve(PLAIN), "From The File");

        fileAsBookProcessor.processLibraryFiles(List.of(onDisk("hugo", PLAIN, BookFileType.EPUB)), library);

        assertUntouched(curated, "Hugo", PLAIN);
        tx.executeWithoutResult(status -> assertThat(bookRepository.findById(curated).orElseThrow().getDeleted()).isTrue());
        Long added = bookIdAt("hugo", PLAIN);
        assertThat(added).isNotNull().isNotEqualTo(curated);
        assertThat(title(added)).isEqualTo("From The File");
    }

    @Test
    void anAdditionalFormatWhosePathOnlyComparesEqualToAnotherBooksFileIsStillAttached() throws IOException {
        long other = storedBook("Hugo", "les mis\u00e9rables.pdf", "Another Book", false);
        epub(root.resolve("Hugo").resolve(PLAIN), "Les Miserables");
        Files.writeString(root.resolve("Hugo").resolve("Les Miserables.pdf"), "a pdf");
        Map<String, List<LibraryFile>> groups = new LinkedHashMap<>();
        groups.put("g", List.of(onDisk("Hugo", PLAIN, BookFileType.EPUB), onDisk("Hugo", "Les Miserables.pdf", BookFileType.PDF)));

        parallelBookImporter.importGroups(groups, library);

        // Before, the other book's file was found for the pdf's path, and the pdf was skipped as already stored
        Long added = bookIdAt("Hugo", PLAIN);
        assertThat(added).isNotNull().isNotEqualTo(other);
        assertThat(bookIdAt("Hugo", "Les Miserables.pdf")).isEqualTo(added);
        assertUntouched(other, "Hugo", "les mis\u00e9rables.pdf");
    }

    @Test
    void aFileAttachedToABookIsNotTakenForAStoredFileWhosePathOnlyComparesEqual() throws IOException {
        long target = storedBook("Hugo", "Notre-Dame de Paris.epub", "Curated", false);
        long other = storedBook("Hugo", "les mis\u00e9rables.pdf", "Another Book", false);
        Files.createDirectories(root.resolve("Hugo"));
        Files.writeString(root.resolve("Hugo").resolve("Les Miserables.pdf"), "a pdf");

        List<LibraryFile> attached = existingBookAttacher.attach(target, Kind.SAME_FOLDER,
                List.of(onDisk("Hugo", "Les Miserables.pdf", BookFileType.PDF)));

        // Before, the other book's file was found for this path, and the file was skipped as already stored
        assertThat(attached).hasSize(1);
        assertThat(bookIdAt("Hugo", "Les Miserables.pdf")).isEqualTo(target);
        assertUntouched(other, "Hugo", "les mis\u00e9rables.pdf");
    }

    @Test
    void aFileStoredAtExactlyItsPathKeepsItsBooksMetadataAndCoverAndOnlyThatFileGetsTheNewHash() throws IOException {
        long bookId = storedBook("Hugo", "Primary.epub", "Curated", false);
        long fileAtPath = tx.execute(status -> bookAdditionalFileRepository.save(BookFileEntity.builder()
                .book(bookRepository.findById(bookId).orElseThrow()).fileSubPath("Hugo").fileName(PLAIN)
                .bookType(BookFileType.EPUB).isBookFormat(true).currentHash("old").initialHash("old").build()).getId());
        epub(root.resolve("Hugo").resolve(PLAIN), "From The File");

        parallelBookImporter.importGroups(Map.of("g", List.of(onDisk("Hugo", PLAIN, BookFileType.EPUB))), library);

        tx.executeWithoutResult(status -> {
            BookEntity book = bookRepository.findById(bookId).orElseThrow();
            assertThat(book.getMetadata().getTitle()).isEqualTo("Curated");
            assertThat(book.getBookCoverHash()).isEqualTo("cover");
            assertThat(book.getMetadata().getCoverUpdatedOn()).isNull();
            assertThat(book.getBookFiles()).hasSize(2);
            BookFileEntity primary = book.getBookFiles().stream().filter(file -> file.isAt("Hugo", "Primary.epub")).findFirst().orElseThrow();
            assertThat(primary.getCurrentHash()).isEqualTo("stored");
            assertThat(bookAdditionalFileRepository.findById(fileAtPath).orElseThrow().getCurrentHash()).isNotEqualTo("old");
        });
        assertThat(bookRepository.countIncludingDeletedByLibraryId(library.getId())).isEqualTo(1);
    }

    @Test
    void aDeletedBookBroughtBackByItsFileTakesTheFilesMetadataUnlessItsOwnerLockedSome() throws IOException {
        long unlocked = storedBook("Hugo", PLAIN, "Curated", true);
        long locked = storedBook("Dumas", "Les Trois Mousquetaires.epub", "Curated", true);
        tx.executeWithoutResult(status -> bookRepository.findById(locked).orElseThrow().getMetadata().setTitleLocked(true));
        epub(root.resolve("Hugo").resolve(PLAIN), "From The File");
        epub(root.resolve("Dumas").resolve("Les Trois Mousquetaires.epub"), "From The File");
        Map<String, List<LibraryFile>> groups = new LinkedHashMap<>();
        groups.put("hugo", List.of(onDisk("Hugo", PLAIN, BookFileType.EPUB)));
        groups.put("dumas", List.of(onDisk("Dumas", "Les Trois Mousquetaires.epub", BookFileType.EPUB)));

        parallelBookImporter.importGroups(groups, library);

        tx.executeWithoutResult(status -> {
            BookEntity restored = bookRepository.findById(unlocked).orElseThrow();
            assertThat(restored.getDeleted()).isFalse();
            assertThat(restored.getMetadata().getTitle()).isEqualTo("From The File");
            BookEntity kept = bookRepository.findById(locked).orElseThrow();
            assertThat(kept.getDeleted()).isFalse();
            assertThat(kept.getMetadata().getTitle()).isEqualTo("Curated");
        });
        assertThat(bookRepository.countIncludingDeletedByLibraryId(library.getId())).isEqualTo(2);
    }

    @Test
    void aWatchedFileWhosePathOnlyComparesEqualIsNotTakenForTheStoredFile() throws IOException {
        long stored = storedBook("Watched", "Book.epub", "Curated", false);
        Path file = root.resolve("Watched").resolve("book.epub");
        // A title no other test's file has: a book with this file's hash would be taken for the file moved
        epub(file, "Seen By The Watcher");

        bookFileTransactionalHandler.handleNewBookFile(library.getId(), file);

        // Before, the stored file was found for this path and given this file's hash as a changed file. Now the new file
        // gets a row of its own: same-folder matching takes it for another copy of the stored file's book
        assertUntouched(stored, "Watched", "Book.epub");
        assertThat(bookIdAt("Watched", "book.epub")).isEqualTo(stored);
    }

    // ========== fixture ==========

    /** A book with one file, a title, a cover and a known hash, as a scan and its owner left it. */
    private long storedBook(String subPath, String fileName, String title, boolean deleted) {
        return tx.execute(status -> {
            BookEntity book = BookEntity.builder()
                    .library(libraryRepository.findById(library.getId()).orElseThrow())
                    .libraryPath(libraryPath)
                    .addedOn(Instant.now())
                    .deleted(deleted)
                    .deletedAt(deleted ? Instant.now() : null)
                    .bookCoverHash("cover")
                    .bookFiles(new ArrayList<>())
                    .build();
            book.getBookFiles().add(BookFileEntity.builder()
                    .book(book).fileSubPath(subPath).fileName(fileName)
                    .bookType(fileName.endsWith(".pdf") ? BookFileType.PDF : BookFileType.EPUB).isBookFormat(true)
                    .currentHash("stored").initialHash("stored").fileSizeKb(7L).addedOn(Instant.now())
                    .build());
            book.setMetadata(BookMetadataEntity.builder().book(book).title(title).build());
            return bookRepository.save(book).getId();
        });
    }

    /** The book keeps its title and cover, and its file at that path its hash and size. */
    private void assertUntouched(long bookId, String subPath, String fileName) {
        tx.executeWithoutResult(status -> {
            BookEntity book = bookRepository.findById(bookId).orElseThrow();
            assertThat(book.getMetadata().getTitle()).isIn("Curated", "Another Book");
            assertThat(book.getBookCoverHash()).isEqualTo("cover");
            assertThat(book.getMetadata().getCoverUpdatedOn()).isNull();
            assertThat(book.getBookFiles()).filteredOn(file -> file.isAt(subPath, fileName)).singleElement().satisfies(file -> {
                assertThat(file.getCurrentHash()).isEqualTo("stored");
                assertThat(file.getInitialHash()).isEqualTo("stored");
                assertThat(file.getFileSizeKb()).isEqualTo(7L);
            });
        });
    }

    private Long bookIdAt(String subPath, String fileName) {
        return tx.execute(status -> bookAdditionalFileRepository.findExactlyAt(libraryPath.getId(), subPath, fileName)
                .map(file -> file.getBook().getId())
                .orElse(null));
    }

    private String title(long bookId) {
        return tx.execute(status -> bookRepository.findById(bookId).orElseThrow().getMetadata().getTitle());
    }

    private LibraryFile onDisk(String subPath, String fileName, BookFileType type) {
        return LibraryFile.builder()
                .libraryEntity(library)
                .libraryPathEntity(libraryPath)
                .fileSubPath(subPath)
                .fileName(fileName)
                .bookFileType(type)
                .build();
    }

    /** A minimal EPUB with a title. */
    private static void epub(Path file, String title) throws IOException {
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
    }
}
