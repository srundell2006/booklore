package org.booklore.service.library;

import org.booklore.BookloreApplication;
import org.booklore.model.MetadataUpdateContext;
import org.booklore.model.MetadataUpdateWrapper;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.BookMetadata;
import org.booklore.model.dto.request.CreatePhysicalBookRequest;
import org.booklore.model.dto.settings.LibraryFile;
import org.booklore.model.entity.AuthorEntity;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.model.entity.CategoryEntity;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.entity.LibraryPathEntity;
import org.booklore.model.entity.MoodEntity;
import org.booklore.model.entity.TagEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.model.enums.MetadataReplaceMode;
import org.booklore.repository.AuthorRepository;
import org.booklore.repository.BookRepository;
import org.booklore.repository.CategoryRepository;
import org.booklore.repository.LibraryRepository;
import org.booklore.repository.MoodRepository;
import org.booklore.repository.TagRepository;
import org.booklore.service.book.BookFileDetachmentService;
import org.booklore.service.book.PhysicalBookService;
import org.booklore.service.library.importer.ImportSummary;
import org.booklore.service.library.importer.ParallelBookImporter;
import org.booklore.service.metadata.BookMetadataUpdater;
import org.booklore.service.reader.FfprobeService;
import org.booklore.service.task.TaskCronService;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Work on one book must cost the same however many books its library, author, category, mood or tag already has.
 * Each case runs once in an empty library and once in a library of {@value #EXISTING} books that all share the
 * case's author, category, mood and tag, and compares what Hibernate loaded. Time would not show it at this size, and
 * neither would the statement count: loading all of a library's books, or all of a category's, is one statement. With
 * Hibernate's association management, giving a book its library, or a book's metadata a set of categories, adds the
 * book to the other side's collection, which loads that collection in full first; at 129k books that took seconds
 * per book.
 */
@SpringBootTest(classes = {
        BookloreApplication.class
})
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "spring.datasource.url=jdbc:h2:mem:libraryscaling;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "app.path-config=build/tmp/library-scaling-config",
        "app.bookdrop-folder=build/tmp/test-bookdrop",
        "spring.main.allow-bean-definition-overriding=true",
        "spring.task.scheduling.enabled=false",
        "app.task.scan-library-cron=*/1 * * * * *",
        "app.task.process-bookdrop-cron=*/1 * * * * *",
        "app.features.oidc-enabled=false"
})
@Import(LibraryScalingIT.TestConfig.class)
class LibraryScalingIT {

    private static final int EXISTING = 2000;

    /** The many-to-many sides a book's metadata adds itself to when association management runs. */
    private static final List<String> INVERSE_SIDES = List.of(
            AuthorEntity.class.getName() + ".bookMetadataEntityList",
            CategoryEntity.class.getName() + ".bookMetadataEntityList",
            MoodEntity.class.getName() + ".bookMetadataEntityList",
            TagEntity.class.getName() + ".bookMetadataEntityList");

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

    // Statistics are counted across the whole application: nothing may load books from another thread
    @MockitoBean
    private LibraryHealthService libraryHealthService;
    @MockitoBean
    private FfprobeService ffprobeService;

    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private EntityManagerFactory entityManagerFactory;
    @Autowired
    private LibraryRepository libraryRepository;
    @Autowired
    private BookRepository bookRepository;
    @Autowired
    private AuthorRepository authorRepository;
    @Autowired
    private CategoryRepository categoryRepository;
    @Autowired
    private MoodRepository moodRepository;
    @Autowired
    private TagRepository tagRepository;
    @Autowired
    private ParallelBookImporter parallelBookImporter;
    @Autowired
    private FileAsBookProcessor fileAsBookProcessor;
    @Autowired
    private PhysicalBookService physicalBookService;
    @Autowired
    private BookMetadataUpdater bookMetadataUpdater;
    @Autowired
    private BookFileDetachmentService bookFileDetachmentService;

    @TempDir
    Path tempDir;

    private TransactionTemplate tx;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        jdbc = new JdbcTemplate(dataSource);
        // As in the Flyway schema the application runs on: a new book's empty metadata is saved before its title is
        // set. The entity's nullable = false only shapes the schema H2 generates from it.
        jdbc.execute("ALTER TABLE book_metadata ALTER COLUMN title SET NULL");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void importingNewBooksInParallelCostsTheSameInALargeLibrary() throws Exception {
        Cost empty = importInParallel(library("parallel-empty", 0));
        Cost large = importInParallel(library("parallel-large", EXISTING));

        assertSameCost(empty, large);
    }

    @Test
    void importingANewBookTheWayTheWatcherDoesCostsTheSameInALargeLibrary() throws Exception {
        Cost empty = importOneByOne(library("watcher-empty", 0));
        Cost large = importOneByOne(library("watcher-large", EXISTING));

        assertSameCost(empty, large);
    }

    @Test
    void creatingAPhysicalBookCostsTheSameInALargeLibrary() throws Exception {
        Cost empty = createPhysicalBook(library("physical-empty", 0));
        Cost large = createPhysicalBook(library("physical-large", EXISTING));

        assertSameCost(empty, large);
    }

    @Test
    void updatingABooksMetadataCostsTheSameWhenItsAuthorAndCategoryHaveManyBooks() throws Exception {
        Cost empty = updateMetadata(library("metadata-empty", 0));
        Cost large = updateMetadata(library("metadata-large", EXISTING));

        assertSameCost(empty, large);
    }

    @Test
    void detachingAFileWithItsMetadataCostsTheSameInALargeLibrary() throws Exception {
        Cost empty = detachFile(library("detach-empty", 0));
        Cost large = detachFile(library("detach-large", EXISTING));

        assertSameCost(empty, large);
    }

    // ========== the measured work ==========

    private Cost importInParallel(Library library) throws IOException {
        Map<String, List<LibraryFile>> groups = new LinkedHashMap<>();
        for (int i = 0; i < 3; i++) {
            String name = "New %02d.epub".formatted(i);
            epub(library.root().resolve(name), "New " + i, library);
            groups.put(name, List.of(file(library, name)));
        }
        return measure(() -> {
            ImportSummary summary = parallelBookImporter.importGroups(groups, library.entity());
            assertThat(summary.added()).isEqualTo(3);
        });
    }

    private Cost importOneByOne(Library library) throws IOException {
        epub(library.root().resolve("Watched.epub"), "Watched", library);
        return measure(() -> fileAsBookProcessor.processLibraryFiles(List.of(file(library, "Watched.epub")), library.entity()));
    }

    private Cost createPhysicalBook(Library library) {
        CreatePhysicalBookRequest request = CreatePhysicalBookRequest.builder()
                .libraryId(library.entity().getId())
                .title("On The Shelf")
                .authors(List.of(library.author()))
                .categories(List.of(library.category()))
                .build();
        return measure(() -> physicalBookService.createPhysicalBook(request));
    }

    private Cost updateMetadata(Library library) {
        long bookId = bookWithFiles(library, "Updated", "Updated.epub");
        BookMetadata update = BookMetadata.builder()
                .title("Updated Again")
                .authors(List.of(library.author(), "Second Author " + library.entity().getName()))
                .categories(Set.of(library.category()))
                .moods(Set.of(library.mood()))
                .tags(Set.of(library.tag()))
                .build();
        return measure(() -> tx.executeWithoutResult(status -> bookMetadataUpdater.setBookMetadata(MetadataUpdateContext.builder()
                .bookEntity(bookRepository.findById(bookId).orElseThrow())
                .metadataUpdateWrapper(MetadataUpdateWrapper.builder().metadata(update).build())
                .mergeCategories(true)
                .mergeMoods(true)
                .mergeTags(true)
                .replaceMode(MetadataReplaceMode.REPLACE_ALL)
                .build())));
    }

    private Cost detachFile(Library library) {
        long bookId = bookWithFiles(library, "Two Formats", "Two Formats.epub", "Two Formats.pdf");
        long pdfId = tx.execute(status -> bookRepository.findByIdWithBookFiles(bookId).orElseThrow().getBookFiles().stream()
                .filter(file -> file.getBookType() == BookFileType.PDF).findFirst().orElseThrow().getId());
        // A principal whose id is not a user's, so no default settings are created for it
        BookLoreUser user = BookLoreUser.builder().id(-1L).username("scaling").build();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
        return measure(() -> bookFileDetachmentService.detachBookFile(bookId, pdfId, true));
    }

    // ========== measuring ==========

    /**
     * What one piece of work loaded: books, entities of every kind, and for each inverse many-to-many side of a book's
     * metadata, how often it was loaded in full.
     */
    record Cost(long bookLoads, long entityLoads, Map<String, Long> inverseSideLoads) {
    }

    private Cost measure(Runnable work) {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        work.run();
        Map<String, Long> inverseSideLoads = new TreeMap<>();
        INVERSE_SIDES.forEach(role -> inverseSideLoads.put(role, statistics.getCollectionStatistics(role).getFetchCount()));
        return new Cost(
                statistics.getEntityStatistics(BookEntity.class.getName()).getLoadCount(),
                statistics.getEntityLoadCount(),
                inverseSideLoads);
    }

    private static void assertSameCost(Cost empty, Cost large) {
        assertThat(large.inverseSideLoads()).as("inverse sides loaded in full").allSatisfy((role, loads) -> assertThat(loads).as(role).isZero());
        assertThat(empty.inverseSideLoads()).as("inverse sides loaded in full").allSatisfy((role, loads) -> assertThat(loads).as(role).isZero());
        assertThat(large.bookLoads()).as("books loaded").isEqualTo(empty.bookLoads());
        assertThat(large.entityLoads()).as("entities loaded").isEqualTo(empty.entityLoads());
    }

    // ========== fixture ==========

    /** A library with one path, and the author, category, mood and tag its {@code existing} books all share. */
    record Library(LibraryEntity entity, Path root, String author, String category, String mood, String tag) {
    }

    private Library library(String name, int existing) throws IOException {
        Path root = Files.createDirectories(tempDir.resolve(name));
        Library library = tx.execute(status -> {
            LibraryEntity created = libraryRepository.save(LibraryEntity.builder()
                    .name(name).icon("book").watch(false)
                    .formatPriority(new ArrayList<>(List.of(BookFileType.EPUB, BookFileType.PDF)))
                    .libraryPaths(new ArrayList<>())
                    .build());
            created.getLibraryPaths().add(LibraryPathEntity.builder().library(created).path(root.toString()).build());
            created = libraryRepository.save(created);
            return new Library(created, root,
                    authorRepository.save(AuthorEntity.builder().name("Author " + name).build()).getName(),
                    categoryRepository.save(CategoryEntity.builder().name("Category " + name).build()).getName(),
                    moodRepository.save(MoodEntity.builder().name("Mood " + name).build()).getName(),
                    tagRepository.save(TagEntity.builder().name("Tag " + name).build()).getName());
        });
        seed(library, existing);
        return library;
    }

    /** Books with metadata under the library's author, category, mood and tag, written straight to the tables. */
    private void seed(Library library, int count) {
        if (count == 0) return;
        long libraryId = library.entity().getId();
        long pathId = library.entity().getLibraryPaths().getFirst().getId();
        List<Object[]> books = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            books.add(new Object[]{libraryId, pathId, Timestamp.from(Instant.now()), false});
        }
        jdbc.batchUpdate("INSERT INTO book (library_id, library_path_id, added_on, deleted) VALUES (?, ?, ?, ?)", books);
        List<Long> ids = jdbc.queryForList("SELECT id FROM book WHERE library_id = ? ORDER BY id", Long.class, libraryId);
        jdbc.batchUpdate("INSERT INTO book_metadata (book_id, title, title_changed_by_auto_fetch) VALUES (?, ?, false)",
                ids.stream().map(id -> new Object[]{id, "Existing " + id}).toList());
        long authorId = jdbc.queryForObject("SELECT id FROM author WHERE name = ?", Long.class, library.author());
        long categoryId = jdbc.queryForObject("SELECT id FROM category WHERE name = ?", Long.class, library.category());
        long moodId = jdbc.queryForObject("SELECT id FROM mood WHERE name = ?", Long.class, library.mood());
        long tagId = jdbc.queryForObject("SELECT id FROM tag WHERE name = ?", Long.class, library.tag());
        map("book_metadata_author_mapping (book_id, author_id, sort_order) VALUES (?, ?, 0)", ids, authorId);
        map("book_metadata_category_mapping (book_id, category_id) VALUES (?, ?)", ids, categoryId);
        map("book_metadata_mood_mapping (book_id, mood_id) VALUES (?, ?)", ids, moodId);
        map("book_metadata_tag_mapping (book_id, tag_id) VALUES (?, ?)", ids, tagId);
    }

    private void map(String tableAndValues, List<Long> bookIds, long targetId) {
        jdbc.batchUpdate("INSERT INTO " + tableAndValues, bookIds.stream().map(id -> new Object[]{id, targetId}).toList());
    }

    /** A book of the library with the given files, its metadata under the library's author, category, mood and tag. */
    private long bookWithFiles(Library library, String title, String... fileNames) {
        return tx.execute(status -> {
            BookEntity book = BookEntity.builder()
                    .library(libraryRepository.findById(library.entity().getId()).orElseThrow())
                    .libraryPath(library.entity().getLibraryPaths().getFirst())
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
                        .currentHash(fileName)
                        .initialHash(fileName)
                        .addedOn(Instant.now())
                        .build());
            }
            BookMetadataEntity metadata = BookMetadataEntity.builder().book(book).title(title).build();
            metadata.setAuthors(new ArrayList<>());
            metadata.getAuthors().add(authorRepository.findByName(library.author()).orElseThrow());
            metadata.setCategories(new HashSet<>());
            metadata.getCategories().add(categoryRepository.findByName(library.category()).orElseThrow());
            metadata.setMoods(new HashSet<>());
            metadata.getMoods().add(moodRepository.findByName(library.mood()).orElseThrow());
            metadata.setTags(new HashSet<>());
            metadata.getTags().add(tagRepository.findByName(library.tag()).orElseThrow());
            book.setMetadata(metadata);
            return bookRepository.save(book).getId();
        });
    }

    private static LibraryFile file(Library library, String fileName) {
        return LibraryFile.builder()
                .libraryEntity(library.entity())
                .libraryPathEntity(library.entity().getLibraryPaths().getFirst())
                .fileSubPath("")
                .fileName(fileName)
                .bookFileType(BookFileType.EPUB)
                .build();
    }

    /** An EPUB by the library's author, under its category. */
    private static void epub(Path file, String title, Library library) throws IOException {
        String opf = """
                <?xml version="1.0" encoding="UTF-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opf="http://www.idpf.org/2007/opf">
                    <dc:title>%s</dc:title><dc:creator opf:role="aut">%s</dc:creator><dc:subject>%s</dc:subject>
                  </metadata>
                  <manifest></manifest>
                </package>""".formatted(title, library.author(), library.category());
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
