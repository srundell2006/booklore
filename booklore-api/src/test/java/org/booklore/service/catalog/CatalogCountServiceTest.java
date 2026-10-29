package org.booklore.service.catalog;

import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.catalog.CatalogCounts;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookLoreUserEntity;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.entity.ShelfEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.model.enums.ContentRestrictionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Each count against the sidebar's own computation over the list {@code GET /api/v1/books} streams to the same user:
 * {@code app.menu.component.ts} (All Books), {@code library.service.ts}, {@code shelf.service.ts} (own shelves and
 * unshelved) and {@code series-data.service.ts}.
 */
class CatalogCountServiceTest extends CatalogDatabaseTest {

    private static final Instant ADDED = Instant.parse("2026-10-01T12:00:00Z");

    @Autowired
    private CatalogCountService countService;

    private LibraryEntity fiction;
    private LibraryEntity comics;
    private BookLoreUserEntity adminUser;
    private BookLoreUserEntity readerUser;
    private BookLoreUserEntity nobodyUser;
    private BookLoreUserEntity restrictedUser;
    private ShelfEntity adminPicks;
    private ShelfEntity readerFavourites;
    private ShelfEntity readerEmpty;
    private ShelfEntity nobodysShelf;

    @BeforeEach
    void fixture() {
        fiction = library("fiction", BookFileType.EPUB);
        comics = library("comics", BookFileType.CBX);
        adminUser = user("admin");
        readerUser = user("reader");
        nobodyUser = user("nobody");
        restrictedUser = user("restricted");
        BookLoreUserEntity otherUser = user("other");

        BookEntity dune = book(fiction, "Dune", ADDED);
        file(dune, "dune.epub", BookFileType.EPUB, true);
        seriesName(dune, "Dune");
        // Fileless, and the same series once trimmed and lower-cased
        BookEntity messiah = book(fiction, "Dune Messiah", ADDED);
        seriesName(messiah, " dune ");
        // No metadata row at all
        BookEntity untitled = book(fiction, null, ADDED);
        file(untitled, "untitled.pdf", BookFileType.PDF, true);
        BookEntity foundation = book(fiction, "Foundation", ADDED);
        file(foundation, "foundation.epub", BookFileType.EPUB, true);
        seriesName(foundation, "Foundation");
        BookEntity deletedFoundation = book(fiction, "Foundation and Empire", ADDED);
        seriesName(deletedFoundation, "Only Deleted Books");
        deleted(deletedFoundation);
        // '' is no series; a blank name is, under the key ''
        BookEntity emptySeries = book(fiction, "Empty series", ADDED);
        seriesName(emptySeries, "");
        BookEntity blankSeries = book(fiction, "Blank series", ADDED);
        seriesName(blankSeries, "   ");

        BookEntity saga1 = book(comics, "Saga 1", ADDED);
        file(saga1, "saga1.cbz", BookFileType.CBX, true);
        seriesName(saga1, "Saga");
        BookEntity saga2 = book(comics, "Saga 2", ADDED);
        seriesName(saga2, "SAGA");
        BookEntity loose = book(comics, "Loose", ADDED);
        BookEntity deletedComic = deleted(book(comics, "Gone", ADDED));
        seriesName(deletedComic, "Gone");

        adminPicks = shelf(adminUser, "Picks", dune, messiah, loose, deletedComic);
        readerFavourites = shelf(readerUser, "Favourites", dune, saga1, deletedFoundation);
        readerEmpty = shelf(readerUser, "Empty");
        nobodysShelf = shelf(nobodyUser, "Nobody's", dune);
        // Another user's shelf neither appears nor shelves anything for the viewer
        shelf(otherUser, "Public", untitled, foundation);

        restrict(restrictedUser, ContentRestrictionType.TAG, "hidden");
        // Admins are never restricted
        restrict(adminUser, ContentRestrictionType.TAG, "hidden");
    }

    @Test
    void adminCountsEveryLibrary() throws IOException {
        BookLoreUser admin = viewer(adminUser, true);

        CatalogCounts counts = countService.getCounts(admin);

        assertThat(counts).isEqualTo(uiCounts(admin, List.of(fiction, comics), List.of(adminPicks)));
        assertThat(counts).isEqualTo(new CatalogCounts(false, 9L, 4L, 6L,
                Map.of(fiction.getId(), 6L, comics.getId(), 3L), Map.of(adminPicks.getId(), 3L)));
    }

    @Test
    void nonAdminCountsTheirLibrariesOnly() throws IOException {
        BookLoreUser reader = viewer(readerUser, false, fiction);

        CatalogCounts counts = countService.getCounts(reader);

        assertThat(counts).isEqualTo(uiCounts(reader, List.of(fiction), List.of(readerFavourites, readerEmpty)));
        // The comic and the deleted book on Favourites are not in the reader's list
        assertThat(counts).isEqualTo(new CatalogCounts(false, 6L, 3L, 5L,
                Map.of(fiction.getId(), 6L), Map.of(readerFavourites.getId(), 1L, readerEmpty.getId(), 0L)));
    }

    @Test
    void nonAdminWithoutLibrariesGetsZeros() throws IOException {
        BookLoreUser nobody = viewer(nobodyUser, false);

        CatalogCounts counts = countService.getCounts(nobody);

        assertThat(counts).isEqualTo(uiCounts(nobody, List.of(), List.of(nobodysShelf)));
        assertThat(counts).isEqualTo(new CatalogCounts(false, 0L, 0L, 0L, Map.of(), Map.of(nobodysShelf.getId(), 0L)));
    }

    @Test
    void restrictedUserIsLeftToTheClient() {
        BookLoreUser restricted = viewer(restrictedUser, false, fiction, comics);

        assertThat(countService.getCounts(restricted)).isEqualTo(CatalogCounts.computedOnClient());
    }

    @Test
    void anEmptyLibraryIsListedWithZero() throws IOException {
        LibraryEntity empty = library("empty", BookFileType.EPUB);
        BookLoreUser reader = viewer(readerUser, false, fiction, empty);

        CatalogCounts counts = countService.getCounts(reader);

        assertThat(counts).isEqualTo(uiCounts(reader, List.of(fiction, empty), List.of(readerFavourites, readerEmpty)));
        assertThat(counts.libraries()).containsEntry(empty.getId(), 0L);
    }

    @Test
    void seriesNamesLoseTheWhitespaceJavaScriptTrims() throws IOException {
        // String.prototype.trim also removes tabs, no-break and ideographic spaces at either end, which SQL TRIM keeps
        BookEntity saga3 = book(comics, "Saga 3", ADDED);
        seriesName(saga3, "Saga　");
        BookEntity saga4 = book(comics, "Saga 4", ADDED);
        seriesName(saga4, "　  \tsaga \n");
        // A name of such whitespace only is the blank series ''
        BookEntity ideographicBlank = book(comics, "Ideographic blank", ADDED);
        seriesName(ideographicBlank, "　 ");
        // Inside a name it stays
        BookEntity spacedSaga = book(comics, "Sa ga", ADDED);
        seriesName(spacedSaga, "Sa　ga");
        BookLoreUser admin = viewer(adminUser, true);

        CatalogCounts counts = countService.getCounts(admin);

        assertThat(counts).isEqualTo(uiCounts(admin, List.of(fiction, comics), List.of(adminPicks)));
        // dune, foundation, '', saga and the spaced saga
        assertThat(counts.series()).isEqualTo(5L);
    }

    @Test
    void withoutOwnShelvesEveryBookIsUnshelved() throws IOException {
        BookLoreUserEntity shelfless = user("shelfless");
        BookLoreUser viewer = viewer(shelfless, false, comics);

        CatalogCounts counts = countService.getCounts(viewer);

        assertThat(counts).isEqualTo(uiCounts(viewer, List.of(comics), List.of()));
        assertThat(counts.unshelved()).isEqualTo(counts.allBooks()).isEqualTo(3L);
    }

    // ------------------------------------------------------------------ the UI's computation

    /** The sidebar's numbers from the streamed list, for the libraries and own shelves the sidebar lists. */
    private CatalogCounts uiCounts(BookLoreUser user, List<LibraryEntity> libraries, List<ShelfEntity> ownShelves) throws IOException {
        List<JsonNode> books = streamedList(user);

        Map<Long, Long> libraryCounts = new HashMap<>();
        for (LibraryEntity library : libraries) {
            libraryCounts.put(library.getId(), books.stream().filter(b -> b.get("libraryId").asLong() == library.getId()).count());
        }
        Map<Long, Long> shelfCounts = new HashMap<>();
        for (ShelfEntity shelf : ownShelves) {
            shelfCounts.put(shelf.getId(), books.stream().filter(b -> shelfIds(b).contains(shelf.getId())).count());
        }
        long unshelved = books.stream().filter(b -> shelfIds(b).isEmpty()).count();

        Set<String> series = new HashSet<>();
        for (JsonNode book : books) {
            JsonNode seriesName = book.path("metadata").path("seriesName");
            if (seriesName.isString() && !seriesName.stringValue().isEmpty()) {
                series.add(JsSemantics.trim(seriesName.stringValue()).toLowerCase(Locale.ROOT));
            }
        }
        return new CatalogCounts(false, (long) books.size(), (long) series.size(), unshelved, libraryCounts, shelfCounts);
    }

    private static Set<Long> shelfIds(JsonNode book) {
        Set<Long> ids = new HashSet<>();
        JsonNode shelves = book.get("shelves");
        if (shelves != null && shelves.isArray()) {
            shelves.values().forEach(shelf -> ids.add(shelf.get("id").asLong()));
        }
        return ids;
    }
}
