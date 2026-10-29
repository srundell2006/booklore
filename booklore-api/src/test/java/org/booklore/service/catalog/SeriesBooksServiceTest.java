package org.booklore.service.catalog;

import jakarta.persistence.EntityManager;
import org.booklore.exception.APIException;
import org.booklore.model.dto.Book;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.catalog.SeriesBooks;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookLoreUserEntity;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.model.enums.ContentRestrictionType;
import org.booklore.service.book.BookService;
import org.hibernate.query.sqm.tree.select.SqmSelectStatement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * The series lookup against {@code series-page.component.ts}'s own selection, ported here and run over the list
 * {@code GET /api/v1/books} streams to the same user.
 */
class SeriesBooksServiceTest extends CatalogDatabaseTest {

    private static final Instant NOW = Instant.parse("2026-10-28T12:00:00Z");
    /** What the page sorts a book without a series number as. */
    private static final double MAX_SAFE_INTEGER = 9007199254740991d;

    @Autowired
    private SeriesBooksService seriesService;

    private LibraryEntity fiction;
    private LibraryEntity hidden;
    private BookLoreUserEntity readerUser;
    private BookLoreUser reader;
    private BookLoreUser admin;

    @BeforeEach
    void users() {
        fiction = library("fiction", BookFileType.EPUB);
        hidden = library("hidden", BookFileType.EPUB);
        readerUser = user("reader");
        reader = viewer(readerUser, false, fiction);
        admin = viewer(user("admin"), true);
    }

    private BookEntity inSeries(LibraryEntity library, String title, String seriesName, Float seriesNumber) {
        BookEntity book = book(library, title, NOW);
        seriesName(book, seriesName);
        seriesNumber(book, seriesNumber);
        return book;
    }

    // ------------------------------------------------------------------ matching

    @Test
    void matchesTheNameTrimmedAndLowerCasedAsThePageDoes() throws IOException {
        BookEntity plain = inSeries(fiction, "Plain", "The Expanse", 1f);
        BookEntity lower = inSeries(fiction, "Lower", "the expanse", 2f);
        BookEntity tabAndNbsp = inSeries(fiction, "Tab and no-break space", "\tThe Expanse ", 3f);
        BookEntity ideographicAndBom = inSeries(fiction, "Ideographic space and BOM", "　THE EXPANSE﻿", 4f);
        BookEntity lineBreaks = inSeries(fiction, "Line breaks", "\n\r The Expanse  ", 5f);
        // Inner whitespace is kept
        BookEntity twoSpaces = inSeries(fiction, "Two spaces", "The  Expanse", 6f);
        BookEntity innerTab = inSeries(fiction, "Inner tab", "The\tExpanse", 7f);
        // Other series, some of them containing the name
        inSeries(fiction, "Sequel", "The Expanse 2", 1f);
        inSeries(fiction, "Origins", "The Expanse: Origins", 1f);
        inSeries(fiction, "Shorter", "Expanse", 1f);
        inSeries(fiction, "Prefixed", "Before The Expanse", 1f);
        book(fiction, "No series", NOW);

        List<Long> expected = List.of(plain.getId(), lower.getId(), tabAndNbsp.getId(), ideographicAndBom.getId(), lineBreaks.getId());
        for (String name : List.of("The Expanse", "the expanse", "  THE EXPANSE\t", "﻿ the Expanse　 ")) {
            assertThat(seriesIds(reader, name, 100)).as(name).isEqualTo(uiSeries(reader, name)).isEqualTo(expected);
        }
        assertThat(seriesIds(reader, "the  expanse", 100)).isEqualTo(uiSeries(reader, "the  expanse")).containsExactly(twoSpaces.getId());
        assertThat(seriesIds(reader, "THE\texpanse", 100)).isEqualTo(uiSeries(reader, "THE\texpanse")).containsExactly(innerTab.getId());
        assertThat(seriesIds(reader, "Expanse", 100)).isEqualTo(uiSeries(reader, "Expanse")).hasSize(1);
        assertThat(seriesIds(reader, "The Expanse 3", 100)).isEmpty();
        assertThat(seriesIds(reader, "The", 100)).isEmpty();
    }

    @Test
    void likeWildcardsAndTheEscapeCharacterInTheNameAreLiteral() throws IOException {
        BookEntity percent = inSeries(fiction, "Percent", "100% Wolf", null);
        inSeries(fiction, "Anything for the percent", "100 Big Wolf", null);
        BookEntity underscore = inSeries(fiction, "Underscore", "a_b", null);
        inSeries(fiction, "Any one for the underscore", "axb", null);
        BookEntity bang = inSeries(fiction, "Escape character", "Wow!", null);
        inSeries(fiction, "Without it", "Wow", null);
        BookEntity escapedPercent = inSeries(fiction, "Escape before a percent", "x!%y", null);
        inSeries(fiction, "Percent alone", "x%y", null);

        assertThat(seriesIds(reader, "100% wolf", 100)).isEqualTo(uiSeries(reader, "100% wolf")).containsExactly(percent.getId());
        assertThat(seriesIds(reader, "A_B", 100)).isEqualTo(uiSeries(reader, "A_B")).containsExactly(underscore.getId());
        assertThat(seriesIds(reader, "wow!", 100)).isEqualTo(uiSeries(reader, "wow!")).containsExactly(bang.getId());
        assertThat(seriesIds(reader, "X!%Y", 100)).isEqualTo(uiSeries(reader, "X!%Y")).containsExactly(escapedPercent.getId());
        assertThat(seriesIds(reader, "%", 100)).isEmpty();
        assertThat(seriesIds(reader, "_", 100)).isEmpty();
    }

    /** H2 compares exactly; under MariaDB's PAD SPACE only a LIKE keeps {@code "saga "} apart from {@code "saga"}. */
    @Test
    void theNameIsComparedWithALikeAndTheOrderPutsUnnumberedBooksLast() {
        CatalogViewer viewer = new CatalogViewer(readerUser.getId(), false, Set.of(fiction.getId()), false);

        String hql = ((SqmSelectStatement<?>) seriesService.idsQuery(viewer, "100%_wolf!")).toHqlString();

        assertThat(MagicShelfCountSqlTest.operatorsOnLowerCasedText(hql)).containsExactly("like", "like");
        assertThat(hql).contains("'%100!%!_wolf!!%'", "'100!%!_wolf!!'")
                .containsPattern("(?s)order by case when .*seriesNumber is null then 1 else 0 end.*seriesNumber.*\\.id");
    }

    @Test
    void seriesNameIsTheFirstBooksStoredName() {
        inSeries(fiction, "Second", "Dune", 2f);
        inSeries(fiction, "First", " DUNE ", 1f);
        em.flush();
        em.clear();

        SeriesBooks series = seriesService.getSeriesBooks(reader, "dune", 100);

        assertThat(series.seriesName()).isEqualTo(" DUNE ");
        assertThat(series.books()).extracting(book -> book.getMetadata().getTitle()).containsExactly("First", "Second");
        // No match: the name as it was asked for, which the page shows today
        assertThat(seriesService.getSeriesBooks(reader, " Nothing ", 100))
                .isEqualTo(new SeriesBooks(" Nothing ", 0, List.of()));
    }

    @Test
    void aBlankNameIsABadRequest() {
        for (String name : Arrays.asList(null, "", "   ", "\t 　﻿")) {
            assertThatThrownBy(() -> seriesService.getSeriesBooks(admin, name, 100))
                    .as(String.valueOf(name))
                    .isInstanceOfSatisfying(APIException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        }
    }

    // ------------------------------------------------------------------ which books

    @Test
    void onlyBooksInTheViewersListMatch() throws IOException {
        BookEntity kept = inSeries(fiction, "Kept", "Saga", 1f);
        deleted(inSeries(fiction, "Deleted", "Saga", 2f));
        BookEntity elsewhere = inSeries(hidden, "Elsewhere", "Saga", 3f);
        // Deleted flag left null: not deleted
        BookEntity nullDeleted = inSeries(fiction, "Null deleted", "Saga", 4f);
        jdbcTemplate.update("UPDATE book SET deleted = NULL WHERE id = ?", nullDeleted.getId());

        assertThat(seriesIds(reader, "saga", 100)).isEqualTo(uiSeries(reader, "saga")).containsExactly(kept.getId(), nullDeleted.getId());
        assertThat(seriesIds(admin, "saga", 100)).isEqualTo(uiSeries(admin, "saga"))
                .containsExactly(kept.getId(), elsewhere.getId(), nullDeleted.getId());
        assertThat(seriesService.getSeriesBooks(admin, "saga", 100).total()).isEqualTo(3);
    }

    @Test
    void filelessBooksMatchAndBooksWithoutMetadataDoNot() throws IOException {
        BookEntity withFile = inSeries(fiction, "With a file", "Saga", 1f);
        file(withFile, "saga.epub", BookFileType.EPUB, true);
        BookEntity fileless = inSeries(fiction, "Fileless", "Saga", 2f);
        BookEntity onlyAlternative = inSeries(fiction, "Only a non-book file", "Saga", 3f);
        file(onlyAlternative, "notes.pdf", BookFileType.PDF, false);
        book(fiction, null, NOW);
        book(fiction, "Metadata without a series", NOW);

        assertThat(seriesIds(reader, "Saga", 100)).isEqualTo(uiSeries(reader, "Saga"))
                .containsExactly(withFile.getId(), fileless.getId(), onlyAlternative.getId());
    }

    @Test
    void nonAdminWithoutLibrariesGetsNothingWithoutAQuery() {
        inSeries(fiction, "Book", "Saga", 1f);
        BookLoreUser nobody = viewer(user("nobody"), false);

        assertThat(seriesService.getSeriesBooks(nobody, "Saga", 100)).isEqualTo(new SeriesBooks("Saga", 0, List.of()));

        EntityManager entityManager = mock(EntityManager.class);
        CatalogViewerResolver resolver = mock(CatalogViewerResolver.class);
        BookService books = mock(BookService.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(resolver.resolve(nobody)).thenReturn(new CatalogViewer(nobody.getId(), false, Set.of(), false));
        SeriesBooksService isolated = new SeriesBooksService(entityManager, resolver, books, transactionManager);

        assertThat(isolated.getSeriesBooks(nobody, "Saga", 100).books()).isEmpty();
        verifyNoInteractions(entityManager, books, transactionManager);
    }

    @Test
    void restrictedUserGetsOnlyTheBooksTheyMaySee() throws IOException {
        BookLoreUserEntity restrictedUser = user("restricted");
        restrict(restrictedUser, ContentRestrictionType.TAG, "hidden");
        BookLoreUser restricted = viewer(restrictedUser, false, fiction);
        List<BookEntity> books = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            BookEntity book = inSeries(fiction, "Book " + i, "Saga", (float) i);
            if (i == 0 || i == 2) tag(book, "Hidden");
            books.add(book);
        }

        SeriesBooks all = series(restricted, "saga", 100);
        assertThat(all.books()).extracting(Book::getId).isEqualTo(uiSeries(restricted, "saga"))
                .containsExactly(books.get(1).getId(), books.get(3).getId(), books.get(4).getId());
        assertThat(all.total()).isEqualTo(3);
        assertThat(all.seriesName()).isEqualTo("Saga");

        // Not cut off at the limit: the total is exact
        assertThat(series(restricted, "saga", 5).total()).isEqualTo(3);
        // Cut off: the books are those of the first ids, and the total leaves out only the hidden book among them
        SeriesBooks cut = series(restricted, "saga", 2);
        assertThat(cut.books()).extracting(Book::getId).containsExactly(books.get(1).getId());
        assertThat(cut.total()).isEqualTo(4);
    }

    // ------------------------------------------------------------------ order, limit and the DTOs

    @Test
    void orderIsBySeriesNumberWithUnnumberedBooksLastAndTiesById() throws IOException {
        BookEntity two = inSeries(fiction, "Two", "Saga", 2f);
        BookEntity unnumbered = inSeries(fiction, "Unnumbered", "Saga", null);
        BookEntity one = inSeries(fiction, "One", "Saga", 1f);
        BookEntity oneAndAHalf = inSeries(fiction, "One and a half", "saga", 1.5f);
        BookEntity oneAgain = inSeries(fiction, "One again", "SAGA", 1f);
        BookEntity unnumberedAgain = inSeries(fiction, "Unnumbered again", "Saga", null);
        BookEntity zero = inSeries(fiction, "Zero", "Saga", 0f);
        BookEntity negative = inSeries(fiction, "Negative", "Saga", -1f);
        BookEntity large = inSeries(fiction, "Large", "Saga", 1e9f);

        assertThat(seriesIds(reader, "Saga", 100)).isEqualTo(uiSeries(reader, "Saga"))
                .containsExactly(negative.getId(), zero.getId(), one.getId(), oneAgain.getId(), oneAndAHalf.getId(),
                        two.getId(), large.getId(), unnumbered.getId(), unnumberedAgain.getId());
    }

    @Test
    void limitCutsTheListAndTotalCountsEveryMatch() throws IOException {
        List<Long> ids = new ArrayList<>();
        for (int i = 7; i > 0; i--) {
            ids.addFirst(inSeries(fiction, "Book " + i, "Saga", (float) i).getId());
        }
        inSeries(fiction, "Other", "Other", 1f);
        deleted(inSeries(fiction, "Deleted", "Saga", 0f));
        List<Long> ui = uiSeries(reader, "Saga");
        assertThat(ui).isEqualTo(ids);

        for (int limit : new int[]{1, 3, 6, 7, 8, 1000}) {
            SeriesBooks series = series(reader, "Saga", limit);
            assertThat(series.books()).extracting(Book::getId).as("limit " + limit).isEqualTo(ui.subList(0, Math.min(limit, 7)));
            assertThat(series.total()).as("limit " + limit).isEqualTo(7);
        }
    }

    @Test
    void booksAreTheStreamsListViewDtos() throws IOException {
        BookEntity first = inSeries(fiction, "First", "Saga", 1f);
        file(first, "first.epub", BookFileType.EPUB, true);
        tag(first, "Favourite");
        BookEntity second = inSeries(fiction, "Second", "Saga", 2f);
        shelf(readerUser, "Mine", second);

        Map<Long, JsonNode> streamed = new HashMap<>();
        streamedList(reader).forEach(book -> streamed.put(book.get("id").asLong(), book));
        List<Book> books = series(reader, "Saga", 100).books();

        assertThat(books).extracting(Book::getId).containsExactly(first.getId(), second.getId());
        for (Book book : books) {
            assertThat(objectMapper.readTree(objectMapper.writeValueAsString(book))).isEqualTo(streamed.get(book.getId()));
        }
    }

    // ------------------------------------------------------------------ the page's selection

    private SeriesBooks series(BookLoreUser user, String name, int limit) {
        em.flush();
        em.clear();
        return seriesService.getSeriesBooks(user, name, limit);
    }

    private List<Long> seriesIds(BookLoreUser user, String name, int limit) {
        return series(user, name, limit).books().stream().map(Book::getId).toList();
    }

    /**
     * {@code filteredBooks$}: {@code seriesName?.trim().toLowerCase()} equal to the route's name trimmed and
     * lower-cased, sorted by {@code seriesNumber ?? Number.MAX_SAFE_INTEGER}, the sort stable.
     */
    private List<Long> uiSeries(BookLoreUser user, String name) throws IOException {
        String key = JsSemantics.lower(JsSemantics.trim(name));
        return streamedList(user).stream()
                .filter(book -> {
                    JsonNode seriesName = book.path("metadata").path("seriesName");
                    return seriesName.isString() && JsSemantics.lower(JsSemantics.trim(seriesName.stringValue())).equals(key);
                })
                .sorted(Comparator.comparingDouble(book -> {
                    JsonNode seriesNumber = book.path("metadata").path("seriesNumber");
                    return seriesNumber.isNumber() ? seriesNumber.doubleValue() : MAX_SAFE_INTEGER;
                }))
                .map(book -> book.get("id").asLong())
                .toList();
    }
}
