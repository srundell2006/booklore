package org.booklore.service.catalog;

import org.assertj.core.api.SoftAssertions;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.catalog.MagicShelfCount;
import org.booklore.model.entity.*;
import org.booklore.model.enums.BookFileType;
import org.booklore.model.enums.ReadStatus;
import org.booklore.service.catalog.MagicShelfCountCompiler.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.transaction.BeforeTransaction;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;
import java.sql.Types;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The compiler against the UI's own evaluator: {@code catalog-parity/cases.json} holds the ids the real
 * {@code BookRuleEvaluatorService} matched in {@code books.json} (the UI spec {@code catalog-parity.spec.ts} checks
 * that against the same files). Here the books are rebuilt as entities, the stream is checked to give back the books
 * the UI evaluated, and each case's SQL must select exactly those ids, or be refused where the case allows it.
 */
class CatalogParityTest extends CatalogDatabaseTest {

    private static final List<String> RULE_METADATA_KEYS = List.of("title", "authors", "categories", "moods", "tags",
            "publisher", "seriesName", "language", "narrator", "isbn13", "isbn10", "pageCount", "amazonRating",
            "amazonReviewCount", "goodreadsRating", "goodreadsReviewCount", "hardcoverRating", "hardcoverReviewCount",
            "ranobedbRating", "titleChangedByAutoFetch");
    private static final Set<String> SET_KEYS = Set.of("categories", "moods", "tags");

    @Autowired
    private MagicShelfCountCompiler compiler;
    @Autowired
    private MagicShelfCountService countService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private JsonNode fixture;
    private final Map<Long, Long> fixtureIdByBookId = new HashMap<>();
    private final Map<String, AuthorEntity> authors = new HashMap<>();
    private final Map<String, CategoryEntity> categories = new HashMap<>();
    private final Map<String, MoodEntity> moods = new HashMap<>();
    private final Map<String, TagEntity> tags = new HashMap<>();
    private BookLoreUser viewer;

    @BeforeTransaction
    void widenIsbn13() {
        // Book 30's hyphenated ISBN-13 is longer than the 13-character column, which H2 enforces
        jdbcTemplate.execute("ALTER TABLE book_metadata ALTER COLUMN isbn_13 VARCHAR(32)");
    }

    @BeforeEach
    void buildFixture() throws IOException {
        fixture = read("catalog-parity/books.json");

        Map<Long, LibraryEntity> libraries = new HashMap<>();
        for (JsonNode library : fixture.get("libraries").values()) {
            libraries.put(library.get("id").asLong(), libraryWithId(library));
        }

        BookLoreUserEntity viewerEntity = user("parity-viewer");
        BookLoreUserEntity otherUser = user("parity-other");
        viewer = viewer(viewerEntity, false, libraries.values().toArray(LibraryEntity[]::new));

        for (JsonNode book : fixture.get("books").values()) {
            persistBook(book, false, libraries, viewerEntity, otherUser);
        }
        for (JsonNode book : fixture.get("deletedBooks").values()) {
            persistBook(book, true, libraries, viewerEntity, otherUser);
        }
        em.flush();
        em.clear();
    }

    @Test
    void streamGivesBackTheBooksTheUiEvaluated() throws IOException {
        List<JsonNode> streamed = streamedList(viewer);
        Map<Long, JsonNode> expected = new LinkedHashMap<>();
        fixture.get("books").values().forEach(book -> expected.put(book.get("id").asLong(), book));

        assertThat(streamed.stream().map(book -> fixtureId(book.get("id").asLong())).sorted().toList())
                .isEqualTo(List.copyOf(expected.keySet()));

        SoftAssertions softly = new SoftAssertions();
        for (JsonNode actual : streamed) {
            long id = fixtureId(actual.get("id").asLong());
            JsonNode book = expected.get(id);
            String at = "book " + id + " ";
            softly.assertThat(actual.get("libraryId").asLong()).as(at + "libraryId").isEqualTo(book.get("libraryId").asLong());
            softly.assertThat(text(actual, "addedOn")).as(at + "addedOn").isEqualTo(text(book, "addedOn"));
            softly.assertThat(number(actual, "metadataMatchScore")).as(at + "metadataMatchScore").isEqualTo(number(book, "metadataMatchScore"));
            softly.assertThat(text(actual, "readStatus")).as(at + "readStatus").isEqualTo(text(book, "readStatus"));
            softly.assertThat(text(actual, "isPhysical")).as(at + "isPhysical").isEqualTo(text(book, "isPhysical"));
            softly.assertThat(text(actual, "isComic")).as(at + "isComic").isEqualTo(text(book, "isComic"));
            softly.assertThat(fileSummary(actual.get("primaryFile"))).as(at + "primaryFile").isEqualTo(fileSummary(book.get("primaryFile")));
            softly.assertThat(fileSummaries(actual.get("alternativeFormats"))).as(at + "alternativeFormats").isEqualTo(fileSummaries(book.get("alternativeFormats")));
            softly.assertThat(fileSummaries(actual.get("supplementaryFiles"))).as(at + "supplementaryFiles").isEqualTo(fileSummaries(book.get("supplementaryFiles")));

            JsonNode actualMetadata = actual.get("metadata");
            JsonNode expectedMetadata = book.get("metadata");
            softly.assertThat(actualMetadata == null).as(at + "has no metadata").isEqualTo(expectedMetadata == null);
            if (actualMetadata != null && expectedMetadata != null) {
                for (String key : RULE_METADATA_KEYS) {
                    softly.assertThat(metadataValue(actualMetadata, key)).as(at + "metadata." + key)
                            .isEqualTo(metadataValue(expectedMetadata, key));
                }
            }
        }
        softly.assertAll();
    }

    @Test
    void everyCaseMatchesTheUiEvaluator() throws IOException {
        CatalogViewer catalogViewer = new CatalogViewer(viewer.getId(), false,
                viewer.getAssignedLibraries().stream().map(org.booklore.model.dto.Library::getId).collect(Collectors.toSet()), false);

        SoftAssertions softly = new SoftAssertions();
        int supported = 0;
        int refusedOptional = 0;
        List<String> refused = new ArrayList<>();
        for (JsonNode testCase : read("catalog-parity/cases.json").values()) {
            String name = testCase.get("name").stringValue();
            Compiled compiled = compiler.compile(testCase.get("filterJson").stringValue());
            JsonNode expect = testCase.get("expect");

            if (expect.isString()) {
                if ("INVALID".equals(expect.stringValue())) {
                    softly.assertThat(compiled).as(name).isInstanceOf(Invalid.class);
                } else {
                    softly.assertThat(compiled).as(name).isInstanceOf(Unsupported.class);
                    if (compiled instanceof Unsupported unsupported && testCase.has("reason")) {
                        softly.assertThat(unsupported.reasons()).as(name + " reasons").contains(testCase.get("reason").stringValue());
                    }
                }
                continue;
            }

            List<Long> expectedIds = StreamSupport.stream(expect.values().spliterator(), false).map(JsonNode::asLong).toList();
            boolean required = testCase.path("required").asBoolean(false);
            if (!(compiled instanceof Supported compiledShelf)) {
                if (required) {
                    softly.fail("%s: required, but compiled to %s", name, compiled);
                } else {
                    refusedOptional++;
                    refused.add(name + " " + compiled);
                }
                continue;
            }
            supported++;
            Instant now = Instant.parse(testCase.get("now").stringValue());
            List<Long> ids = countService.matchingIds(catalogViewer, compiledShelf, now);
            softly.assertThat(ids).as(name + " (over the candidate limit)").isNotNull();
            if (ids == null) continue;
            softly.assertThat(ids.stream().map(this::fixtureId).sorted().toList()).as(name).isEqualTo(expectedIds);
            softly.assertThat(countService.count(catalogViewer, compiledShelf, now)).as(name + " count")
                    .isEqualTo(MagicShelfCount.ok(expectedIds.size()));
        }
        System.out.printf("Catalog parity: %d cases counted on the server, %d optional ones left to the client%n",
                supported, refusedOptional);
        refused.forEach(r -> System.out.println("  left to the client: " + r));
        softly.assertAll();
    }

    // ------------------------------------------------------------------ fixture

    private LibraryEntity libraryWithId(JsonNode library) {
        long fixtureId = library.get("id").asLong();
        String name = library.get("name").stringValue();
        List<BookFileType> priority = new ArrayList<>();
        library.get("formatPriority").values().forEach(type -> priority.add(BookFileType.valueOf(type.stringValue())));

        LibraryEntity entity = LibraryEntity.builder().name(name).icon("book").watch(false).formatPriority(priority).build();
        em.persist(entity);
        em.flush();
        long generatedId = entity.getId();
        em.detach(entity);
        // Rules name libraries by id (library equals 3), so the libraries keep the fixture's ids
        if (generatedId != fixtureId) {
            em.createNativeQuery("UPDATE library SET id = :fixtureId WHERE id = :generatedId")
                    .setParameter("fixtureId", fixtureId)
                    .setParameter("generatedId", generatedId)
                    .executeUpdate();
        }
        LibraryEntity reloaded = em.find(LibraryEntity.class, fixtureId);
        LibraryPathEntity path = LibraryPathEntity.builder().library(reloaded).path("/fixture/" + name.toLowerCase(Locale.ROOT)).build();
        em.persist(path);
        addPath(reloaded, path);
        em.flush();
        return reloaded;
    }

    private void persistBook(JsonNode book, boolean deleted, Map<Long, LibraryEntity> libraries,
                             BookLoreUserEntity viewerEntity, BookLoreUserEntity otherUser) {
        JsonNode setup = book.get("fixture");
        LibraryEntity library = libraries.get(book.get("libraryId").asLong());
        BookEntity entity = BookEntity.builder()
                .library(library)
                .libraryPath(library.getLibraryPaths().getFirst())
                .addedOn(book.has("addedOn") ? Instant.parse(book.get("addedOn").stringValue()) : null)
                .deleted(deleted)
                .isPhysical(nullableBoolean(setup.get("isPhysical")))
                .isComic(nullableBoolean(setup.get("isComic")))
                .metadataMatchScore(book.has("metadataMatchScore") ? (float) book.get("metadataMatchScore").doubleValue() : null)
                .build();
        em.persist(entity);
        fixtureIdByBookId.put(entity.getId(), book.get("id").asLong());

        for (JsonNode file : setup.get("files").values()) {
            em.persist(BookFileEntity.builder()
                    .book(entity)
                    .fileName(file.get("fileName").stringValue())
                    .fileSubPath("")
                    .isBookFormat(file.get("isBookFormat").booleanValue())
                    .bookType(BookFileType.valueOf(file.get("bookType").stringValue()))
                    .fileSizeKb(file.get("fileSizeKb").asLong())
                    .build());
        }

        if (setup.get("hasMetadata").booleanValue()) {
            persistMetadata(entity, book.get("metadata"));
        }

        progress(entity, viewerEntity, setup.get("progress"));
        progress(entity, otherUser, setup.get("otherUserProgress"));
        em.flush();
    }

    private void persistMetadata(BookEntity entity, JsonNode metadata) {
        BookMetadataEntity row = BookMetadataEntity.builder()
                .book(entity)
                .bookId(entity.getId())
                .title("set below")
                .pageCount(metadata.has("pageCount") ? metadata.get("pageCount").intValue() : null)
                .amazonRating(nullableDouble(metadata, "amazonRating"))
                .amazonReviewCount(nullableInt(metadata, "amazonReviewCount"))
                .goodreadsRating(nullableDouble(metadata, "goodreadsRating"))
                .goodreadsReviewCount(nullableInt(metadata, "goodreadsReviewCount"))
                .hardcoverRating(nullableDouble(metadata, "hardcoverRating"))
                .hardcoverReviewCount(nullableInt(metadata, "hardcoverReviewCount"))
                .ranobedbRating(nullableDouble(metadata, "ranobedbRating"))
                .titleChangedByAutoFetch(metadata.get("titleChangedByAutoFetch").booleanValue())
                .authors(names(metadata, "authors").stream().map(name -> named(authors, name, () -> AuthorEntity.builder().name(name).build())).collect(Collectors.toCollection(ArrayList::new)))
                .categories(names(metadata, "categories").stream().map(name -> named(categories, name, () -> CategoryEntity.builder().name(name).build())).collect(Collectors.toCollection(HashSet::new)))
                .moods(names(metadata, "moods").stream().map(name -> named(moods, name, () -> MoodEntity.builder().name(name).build())).collect(Collectors.toCollection(HashSet::new)))
                .tags(names(metadata, "tags").stream().map(name -> named(tags, name, () -> TagEntity.builder().name(name).build())).collect(Collectors.toCollection(HashSet::new)))
                .build();
        em.persist(row);
        em.flush();

        // The entity trims its strings before it is written; the fixture's blank and padded values go in as they are
        jdbcTemplate.update("UPDATE book_metadata SET title = ?, publisher = ?, series_name = ?, language = ?, narrator = ?, "
                        + "isbn_13 = ?, isbn_10 = ? WHERE book_id = ?",
                new Object[]{text(metadata, "title"), text(metadata, "publisher"), text(metadata, "seriesName"),
                        text(metadata, "language"), text(metadata, "narrator"), text(metadata, "isbn13"),
                        text(metadata, "isbn10"), entity.getId()},
                new int[]{Types.VARCHAR, Types.VARCHAR, Types.VARCHAR, Types.VARCHAR, Types.VARCHAR, Types.VARCHAR,
                        Types.VARCHAR, Types.BIGINT});
    }

    private void progress(BookEntity book, BookLoreUserEntity user, JsonNode progress) {
        if (progress == null || progress.isNull()) return;
        JsonNode status = progress.get("readStatus");
        em.persist(UserBookProgressEntity.builder()
                .user(user)
                .book(book)
                .readStatus(status == null || status.isNull() ? null : ReadStatus.valueOf(status.stringValue()))
                .build());
    }

    private <T> T named(Map<String, T> byName, String name, java.util.function.Supplier<T> create) {
        return byName.computeIfAbsent(name, n -> {
            T entity = create.get();
            em.persist(entity);
            return entity;
        });
    }

    // ------------------------------------------------------------------ JSON

    private JsonNode read(String resource) throws IOException {
        try (InputStream in = new ClassPathResource(resource).getInputStream()) {
            return objectMapper.readTree(in);
        }
    }

    private long fixtureId(long bookId) {
        Long id = fixtureIdByBookId.get(bookId);
        assertThat(id).as("fixture id of book " + bookId).isNotNull();
        return id;
    }

    private static List<String> names(JsonNode metadata, String key) {
        List<String> names = new ArrayList<>();
        if (metadata.has(key)) metadata.get(key).values().forEach(name -> names.add(name.stringValue()));
        return names;
    }

    private static Object metadataValue(JsonNode metadata, String key) {
        JsonNode value = metadata.get(key);
        if (value == null || value.isNull()) {
            return SET_KEYS.contains(key) || key.equals("authors") ? List.of() : null;
        }
        if (value.isArray()) {
            List<String> names = new ArrayList<>();
            value.values().forEach(name -> names.add(name.stringValue()));
            if (SET_KEYS.contains(key)) Collections.sort(names);
            return names;
        }
        return value.isNumber() ? (Object) value.doubleValue() : value.asString();
    }

    private static String fileSummary(JsonNode file) {
        if (file == null || file.isNull()) return null;
        return file.get("bookType").stringValue() + " " + file.get("fileName").stringValue() + " "
                + file.get("filePath").stringValue() + " " + text(file, "extension");
    }

    /**
     * Distinct, in order. The stream's loader fetches the files (a bag) together with the authors, so a book with
     * several of each lists every file once per author: an existing quirk of {@code GET /api/v1/books} that no rule
     * reads, left as it is.
     */
    private static List<String> fileSummaries(JsonNode files) {
        Set<String> summaries = new LinkedHashSet<>();
        if (files != null) files.values().forEach(file -> summaries.add(fileSummary(file)));
        return List.copyOf(summaries);
    }

    private static String text(JsonNode node, String key) {
        JsonNode value = node.get(key);
        return value == null || value.isNull() ? null : value.asString();
    }

    private static Double number(JsonNode node, String key) {
        JsonNode value = node.get(key);
        return value == null || value.isNull() ? null : value.doubleValue();
    }

    private static Boolean nullableBoolean(JsonNode value) {
        return value == null || value.isNull() ? null : value.booleanValue();
    }

    private static Double nullableDouble(JsonNode metadata, String key) {
        return metadata.has(key) ? metadata.get(key).doubleValue() : null;
    }

    private static Integer nullableInt(JsonNode metadata, String key) {
        return metadata.has(key) ? metadata.get(key).intValue() : null;
    }
}
