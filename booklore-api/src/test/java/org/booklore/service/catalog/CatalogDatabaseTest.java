package org.booklore.service.catalog;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.booklore.BookloreApplication;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.Library;
import org.booklore.model.entity.*;
import org.booklore.model.enums.BookFileType;
import org.booklore.model.enums.ContentRestrictionMode;
import org.booklore.model.enums.ContentRestrictionType;
import org.booklore.model.enums.ReadStatus;
import org.booklore.service.book.BookService;
import org.booklore.service.task.TaskCronService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.transaction.BeforeTransaction;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.mockito.Mockito.mock;

/**
 * The H2 context the catalog tests share: one database of their own, every test in a transaction that rolls back.
 * The helpers build just enough of a library for the list stream, which these tests use as the oracle.
 */
@SpringBootTest(classes = {BookloreApplication.class})
@Transactional
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.datasource.url=jdbc:h2:mem:catalogdb;DB_CLOSE_DELAY=-1;NON_KEYWORDS=VALUE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "app.path-config=build/tmp/test-config",
        "app.bookdrop-folder=build/tmp/test-bookdrop",
        "spring.main.allow-bean-definition-overriding=true",
        "spring.task.scheduling.enabled=false",
        "app.task.scan-library-cron=*/1 * * * * *",
        "app.task.process-bookdrop-cron=*/1 * * * * *",
        "app.features.oidc-enabled=false"
})
@Import(CatalogDatabaseTest.TestConfig.class)
public abstract class CatalogDatabaseTest {

    @TestConfiguration
    static class TestConfig {
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

    @PersistenceContext
    protected EntityManager em;

    @Autowired
    protected BookService bookService;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @BeforeTransaction
    protected void storeFilterJsonAsText() {
        // H2 reads a string written to a JSON column as a JSON string literal, so a shelf's filterJson would come back
        // quoted; MariaDB's JSON is text, as here
        jdbcTemplate.execute("ALTER TABLE magic_shelf ALTER COLUMN filter_json CLOB");
    }

    protected LibraryEntity library(String name, BookFileType... formatPriority) {
        LibraryEntity library = LibraryEntity.builder()
                .name(name)
                .icon("book")
                .watch(false)
                .formatPriority(new ArrayList<>(Arrays.asList(formatPriority)))
                .build();
        em.persist(library);
        LibraryPathEntity path = LibraryPathEntity.builder().library(library).path("/fixture/" + name).build();
        em.persist(path);
        addPath(library, path);
        em.flush();
        return library;
    }

    /** Adds to the managed collection: replacing it would orphan the old one, which orphanRemoval refuses. */
    protected static void addPath(LibraryEntity library, LibraryPathEntity path) {
        if (library.getLibraryPaths() == null) {
            library.setLibraryPaths(new ArrayList<>(List.of(path)));
        } else {
            library.getLibraryPaths().add(path);
        }
    }

    protected BookLoreUserEntity user(String username) {
        BookLoreUserEntity user = BookLoreUserEntity.builder()
                .username(username)
                .passwordHash("hash")
                .isDefaultPassword(false)
                .name(username)
                .build();
        em.persist(user);
        em.flush();
        return user;
    }

    protected static BookLoreUser viewer(BookLoreUserEntity user, boolean admin, LibraryEntity... libraries) {
        BookLoreUser.UserPermissions permissions = new BookLoreUser.UserPermissions();
        permissions.setAdmin(admin);
        return BookLoreUser.builder()
                .id(user.getId())
                .username(user.getUsername())
                .permissions(permissions)
                .assignedLibraries(Arrays.stream(libraries).map(l -> Library.builder().id(l.getId()).name(l.getName()).build()).toList())
                .build();
    }

    protected BookEntity book(LibraryEntity library, String title, Instant addedOn) {
        BookEntity book = BookEntity.builder()
                .library(library)
                .libraryPath(library.getLibraryPaths().getFirst())
                .addedOn(addedOn)
                .deleted(false)
                .build();
        em.persist(book);
        if (title != null) {
            BookMetadataEntity metadata = BookMetadataEntity.builder().book(book).bookId(book.getId()).title(title).build();
            em.persist(metadata);
            book.setMetadata(metadata);
        }
        em.flush();
        return book;
    }

    protected BookFileEntity file(BookEntity book, String fileName, BookFileType type, boolean isBookFormat) {
        BookFileEntity file = BookFileEntity.builder()
                .book(book)
                .fileName(fileName)
                .fileSubPath("")
                .isBookFormat(isBookFormat)
                .bookType(type)
                .fileSizeKb(1024L)
                .build();
        em.persist(file);
        em.flush();
        return file;
    }

    protected BookEntity deleted(BookEntity book) {
        book.setDeleted(true);
        em.flush();
        return book;
    }

    /** Written past the entity, which trims names and drops blank ones. */
    protected void seriesName(BookEntity book, String seriesName) {
        em.flush();
        jdbcTemplate.update("UPDATE book_metadata SET series_name = ? WHERE book_id = ?", seriesName, book.getId());
    }

    /** Written past the entity, like the name: a change to the entity would flush its stale name over that one. */
    protected void seriesNumber(BookEntity book, Float seriesNumber) {
        em.flush();
        jdbcTemplate.update("UPDATE book_metadata SET series_number = ? WHERE book_id = ?", seriesNumber, book.getId());
    }

    protected void tag(BookEntity book, String name) {
        TagEntity tag = em.createQuery("SELECT t FROM TagEntity t WHERE t.name = :name", TagEntity.class)
                .setParameter("name", name)
                .getResultStream()
                .findFirst()
                .orElseGet(() -> {
                    TagEntity created = TagEntity.builder().name(name).build();
                    em.persist(created);
                    em.flush();
                    return created;
                });
        jdbcTemplate.update("INSERT INTO book_metadata_tag_mapping (book_id, tag_id) VALUES (?, ?)", book.getId(), tag.getId());
    }

    /** Book and shelf both map {@code book_shelf_mapping}, so the rows go in directly. */
    protected ShelfEntity shelf(BookLoreUserEntity owner, String name, BookEntity... books) {
        ShelfEntity shelf = ShelfEntity.builder().user(owner).name(name).build();
        em.persist(shelf);
        em.flush();
        for (BookEntity book : books) {
            jdbcTemplate.update("INSERT INTO book_shelf_mapping (book_id, shelf_id) VALUES (?, ?)", book.getId(), shelf.getId());
        }
        return shelf;
    }

    protected void restrict(BookLoreUserEntity user, ContentRestrictionType type, String value) {
        em.persist(UserContentRestrictionEntity.builder()
                .user(user)
                .restrictionType(type)
                .mode(ContentRestrictionMode.EXCLUDE)
                .value(value)
                .build());
        em.flush();
    }

    protected UserBookProgressEntity progress(BookLoreUserEntity user, BookEntity book, ReadStatus status, Instant lastReadTime) {
        UserBookProgressEntity progress = UserBookProgressEntity.builder()
                .user(user)
                .book(book)
                .readStatus(status)
                .lastReadTime(lastReadTime)
                .build();
        em.persist(progress);
        em.flush();
        return progress;
    }

    protected UserBookFileProgressEntity fileProgress(BookLoreUserEntity user, BookFileEntity file, Instant lastReadTime) {
        UserBookFileProgressEntity progress = UserBookFileProgressEntity.builder()
                .user(user)
                .bookFile(file)
                .progressPercent(12.5f)
                .lastReadTime(lastReadTime)
                .build();
        em.persist(progress);
        em.flush();
        return progress;
    }

    protected MagicShelfEntity magicShelf(BookLoreUserEntity owner, String name, String filterJson, boolean isPublic) {
        MagicShelfEntity shelf = MagicShelfEntity.builder()
                .userId(owner.getId())
                .name(name)
                .icon("pi pi-book")
                .filterJson(filterJson)
                .isPublic(isPublic)
                .build();
        em.persist(shelf);
        em.flush();
        return shelf;
    }

    /** What the browser receives from {@code GET /api/v1/books} for this user, parsed. */
    protected List<JsonNode> streamedList(BookLoreUser user) throws IOException {
        em.flush();
        em.clear();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bookService.streamBooks(user, false, out);
        List<JsonNode> books = new ArrayList<>();
        objectMapper.readTree(out.toByteArray()).values().forEach(books::add);
        return books;
    }
}
