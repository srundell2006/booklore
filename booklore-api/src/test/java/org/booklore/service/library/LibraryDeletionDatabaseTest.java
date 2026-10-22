package org.booklore.service.library;

import org.booklore.BookloreApplication;
import org.booklore.model.entity.AuthorEntity;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.model.entity.CategoryEntity;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.entity.LibraryPathEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.repository.AuthorRepository;
import org.booklore.repository.BookRepository;
import org.booklore.repository.CategoryRepository;
import org.booklore.repository.LibraryRepository;
import org.booklore.service.task.TaskCronService;
import org.booklore.util.FileService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.FileSystemUtils;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Deleting a library, and a library's removed books, against H2 with the real services, repositories and cover
 * folders. The library no longer maps its books, so its books go through the database's foreign keys rather than
 * through Hibernate: these tests hold the entities' on-delete cascades to what the Flyway schema declares, and the
 * cover folders to every book the library had. With those cascades declared, Hibernate may leave a deleted book's
 * files and metadata to the database too, so a book deleted on its own is checked as well. Nothing here runs in a
 * test transaction, as each deletion opens its own.
 */
@SpringBootTest(classes = {
        BookloreApplication.class
})
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.datasource.url=jdbc:h2:mem:librarydeletion;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "app.bookdrop-folder=build/tmp/test-bookdrop",
        "spring.main.allow-bean-definition-overriding=true",
        "spring.task.scheduling.enabled=false",
        "app.task.scan-library-cron=*/1 * * * * *",
        "app.task.process-bookdrop-cron=*/1 * * * * *",
        "app.features.oidc-enabled=false"
})
@Import(LibraryDeletionDatabaseTest.TestConfig.class)
class LibraryDeletionDatabaseTest {

    /** The images folders: a fresh one per run, as book ids start again with every run's database. */
    private static final Path CONFIG_DIR = createConfigDir();

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

    @DynamicPropertySource
    static void pathConfig(DynamicPropertyRegistry registry) {
        registry.add("app.path-config", CONFIG_DIR::toString);
    }

    @AfterAll
    static void deleteConfigDir() throws IOException {
        FileSystemUtils.deleteRecursively(CONFIG_DIR);
    }

    @Autowired
    private LibraryService libraryService;
    @Autowired
    private BookDeletionService bookDeletionService;
    @Autowired
    private LibraryRepository libraryRepository;
    @Autowired
    private BookRepository bookRepository;
    @Autowired
    private AuthorRepository authorRepository;
    @Autowired
    private CategoryRepository categoryRepository;
    @Autowired
    private FileService fileService;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private DataSource dataSource;

    private TransactionTemplate tx;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        jdbc = new JdbcTemplate(dataSource);
    }

    @Test
    void deletesEveryBookOfTheLibraryWithItsFilesMetadataAndCoversAndNothingOfAnotherLibrary() throws IOException {
        LibraryEntity doomed = library("Doomed", "/doomed/one", "/doomed/two");
        LibraryEntity kept = library("Kept", "/kept");
        LibraryPathEntity one = doomed.getLibraryPaths().get(0);
        LibraryPathEntity two = doomed.getLibraryPaths().get(1);
        long active = book(doomed, one, false, false, "Active", "Active.epub", "Active.pdf");
        long softDeleted = book(doomed, one, true, false, "Soft Deleted", "Soft Deleted.epub");
        long fileless = book(doomed, two, false, false, "Fileless");
        long physical = book(doomed, null, false, true, "Physical");
        long other = book(kept, kept.getLibraryPaths().getFirst(), false, false, "Other", "Other.epub");
        List<Long> doomedBooks = List.of(active, softDeleted, fileless, physical);
        for (long bookId : List.of(active, softDeleted, fileless, physical, other)) {
            cover(bookId);
        }

        libraryService.deleteLibrary(doomed.getId());

        assertThat(libraryRepository.findById(doomed.getId())).isEmpty();
        assertThat(count("library_path WHERE library_id = ?", doomed.getId())).isZero();
        assertThat(rowsOf("book", "id", doomedBooks)).isZero();
        assertThat(rowsOf("book_file", "book_id", doomedBooks)).isZero();
        assertThat(rowsOf("book_metadata", "book_id", doomedBooks)).isZero();
        assertThat(rowsOf("book_metadata_author_mapping", "book_id", doomedBooks)).isZero();
        assertThat(rowsOf("book_metadata_category_mapping", "book_id", doomedBooks)).isZero();
        for (long bookId : doomedBooks) {
            assertThat(Path.of(fileService.getImagesFolder(bookId))).as("covers of book %d", bookId).doesNotExist();
        }

        assertThat(libraryRepository.findById(kept.getId())).isPresent();
        assertThat(rowsOf("book", "id", List.of(other))).isOne();
        assertThat(rowsOf("book_file", "book_id", List.of(other))).isOne();
        assertThat(rowsOf("book_metadata", "book_id", List.of(other))).isOne();
        assertThat(rowsOf("book_metadata_author_mapping", "book_id", List.of(other))).isOne();
        assertThat(rowsOf("book_metadata_category_mapping", "book_id", List.of(other))).isOne();
        assertThat(authorRepository.findByName("Shared Writer")).isPresent();
        assertThat(categoryRepository.findByName("Shared Genre")).isPresent();
        assertThat(Path.of(fileService.getImagesFolder(other))).isDirectory();
    }

    @Test
    void deletingRemovedBooksDeletesTheirFilesMetadataAndLinksAndLeavesTheRest() throws IOException {
        LibraryEntity library = library("Rescanned", "/rescanned");
        LibraryPathEntity path = library.getLibraryPaths().getFirst();
        long removed = book(library, path, false, false, "Removed", "Removed.epub", "Removed.pdf");
        long remaining = book(library, path, false, false, "Remaining", "Remaining.epub");
        cover(removed);

        bookDeletionService.deleteRemovedBooks(List.of(removed));

        assertThat(rowsOf("book", "id", List.of(removed))).isZero();
        assertThat(rowsOf("book_file", "book_id", List.of(removed))).isZero();
        assertThat(rowsOf("book_metadata", "book_id", List.of(removed))).isZero();
        assertThat(rowsOf("book_metadata_author_mapping", "book_id", List.of(removed))).isZero();
        assertThat(rowsOf("book_metadata_category_mapping", "book_id", List.of(removed))).isZero();
        assertThat(Path.of(fileService.getImagesFolder(removed))).doesNotExist();
        assertThat(rowsOf("book", "id", List.of(remaining))).isOne();
        assertThat(rowsOf("book_file", "book_id", List.of(remaining))).isOne();
        assertThat(rowsOf("book_metadata_author_mapping", "book_id", List.of(remaining))).isOne();
        assertThat(authorRepository.findByName("Shared Writer")).isPresent();
    }

    @Test
    void deletesALibraryThatHasNoBooks() {
        LibraryEntity empty = library("Empty", "/empty");

        libraryService.deleteLibrary(empty.getId());

        assertThat(libraryRepository.findById(empty.getId())).isEmpty();
        assertThat(count("library_path WHERE library_id = ?", empty.getId())).isZero();
    }

    // ========== fixture ==========

    private LibraryEntity library(String name, String... paths) {
        return tx.execute(status -> {
            LibraryEntity created = libraryRepository.save(LibraryEntity.builder()
                    .name(name).icon("book").watch(false)
                    .formatPriority(new ArrayList<>(List.of(BookFileType.EPUB, BookFileType.PDF)))
                    .libraryPaths(new ArrayList<>())
                    .build());
            for (String path : paths) {
                created.getLibraryPaths().add(LibraryPathEntity.builder().library(created).path(path).build());
            }
            return libraryRepository.save(created);
        });
    }

    /** A book with the given files, and metadata by the shared author under the shared category. */
    private long book(LibraryEntity library, LibraryPathEntity path, boolean deleted, boolean physical, String title,
                      String... fileNames) {
        return tx.execute(status -> {
            BookEntity book = BookEntity.builder()
                    .library(libraryRepository.findById(library.getId()).orElseThrow())
                    .libraryPath(path)
                    .isPhysical(physical)
                    .deleted(deleted)
                    .deletedAt(deleted ? Instant.now() : null)
                    .addedOn(Instant.now())
                    .bookFiles(new ArrayList<>())
                    .build();
            for (String fileName : fileNames) {
                book.getBookFiles().add(BookFileEntity.builder()
                        .book(book)
                        .fileName(fileName)
                        .fileSubPath("")
                        .isBookFormat(true)
                        .bookType(fileName.endsWith(".pdf") ? BookFileType.PDF : BookFileType.EPUB)
                        .currentHash(library.getName() + "/" + fileName)
                        .initialHash(library.getName() + "/" + fileName)
                        .addedOn(Instant.now())
                        .build());
            }
            BookMetadataEntity metadata = BookMetadataEntity.builder().book(book).title(title).build();
            metadata.setAuthors(new ArrayList<>());
            metadata.getAuthors().add(authorRepository.findByName("Shared Writer")
                    .orElseGet(() -> authorRepository.save(AuthorEntity.builder().name("Shared Writer").build())));
            metadata.setCategories(new HashSet<>());
            metadata.getCategories().add(categoryRepository.findByName("Shared Genre")
                    .orElseGet(() -> categoryRepository.save(CategoryEntity.builder().name("Shared Genre").build())));
            book.setMetadata(metadata);
            return bookRepository.save(book).getId();
        });
    }

    private void cover(long bookId) throws IOException {
        Path folder = Files.createDirectories(Path.of(fileService.getImagesFolder(bookId)));
        Files.write(folder.resolve("cover.jpg"), new byte[]{1, 2, 3});
        Files.write(folder.resolve("thumbnail.jpg"), new byte[]{4, 5});
    }

    private long count(String fromWhere, Object... args) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + fromWhere, Long.class, args);
    }

    private long rowsOf(String table, String column, List<Long> bookIds) {
        String ids = bookIds.stream().map(String::valueOf).collect(Collectors.joining(", "));
        return count(table + " WHERE " + column + " IN (" + ids + ")");
    }

    private static Path createConfigDir() {
        try {
            return Files.createTempDirectory("library-deletion-config");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
