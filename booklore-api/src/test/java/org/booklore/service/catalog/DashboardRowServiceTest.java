package org.booklore.service.catalog;

import org.booklore.model.dto.Book;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.entity.BookLoreUserEntity;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.entity.UserBookProgressEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.model.enums.ContentRestrictionType;
import org.booklore.model.enums.ReadStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Each row against {@code main-dashboard.component.ts}'s own selection, ported here and run over the list
 * {@code GET /api/v1/books} streams to the same user.
 */
class DashboardRowServiceTest extends CatalogDatabaseTest {

    private static final Instant NOW = Instant.parse("2026-10-28T12:00:00Z");
    private static final Set<String> IN_PROGRESS = Set.of("READING", "RE_READING", "PAUSED");
    private static final Set<String> RANDOM_EXCLUDED = Set.of("READ", "PARTIALLY_READ", "READING", "PAUSED", "WONT_READ", "ABANDONED");

    @Autowired
    private DashboardRowService rowService;

    private LibraryEntity fiction;
    private LibraryEntity hidden;
    private BookLoreUserEntity readerUser;
    private BookLoreUserEntity otherUser;
    private BookLoreUser reader;
    private BookLoreUser admin;

    @BeforeEach
    void users() {
        fiction = library("fiction", BookFileType.EPUB, BookFileType.AUDIOBOOK);
        hidden = library("hidden", BookFileType.EPUB);
        readerUser = user("reader");
        otherUser = user("other");
        reader = viewer(readerUser, false, fiction);
        admin = viewer(user("admin"), true);
    }

    // ------------------------------------------------------------------ recently added

    @Test
    void recentlyAddedIsNewestFirstWithTiesInIdOrder() throws IOException {
        BookEntity older = book(fiction, "Older", NOW.minus(2, ChronoUnit.DAYS));
        BookEntity tiedFirst = book(fiction, "Tied first", NOW.minus(1, ChronoUnit.DAYS));
        BookEntity tiedSecond = book(fiction, "Tied second", NOW.minus(1, ChronoUnit.DAYS));
        BookEntity newest = book(fiction, "Newest", NOW);
        book(fiction, "Never added", null);
        // No metadata row: added all the same
        book(fiction, null, NOW.minus(3, ChronoUnit.DAYS));
        deleted(book(fiction, "Deleted", NOW.plus(1, ChronoUnit.DAYS)));
        BookEntity elsewhere = book(hidden, "Elsewhere", NOW.plus(2, ChronoUnit.DAYS));

        assertThat(rowIds(reader, DashboardRowKind.RECENTLY_ADDED, 20)).isEqualTo(uiLatestAdded(reader, 20))
                .startsWith(newest.getId(), tiedFirst.getId(), tiedSecond.getId(), older.getId())
                .hasSize(5);
        assertThat(rowIds(reader, DashboardRowKind.RECENTLY_ADDED, 2)).isEqualTo(uiLatestAdded(reader, 2));
        assertThat(rowIds(admin, DashboardRowKind.RECENTLY_ADDED, 3)).isEqualTo(uiLatestAdded(admin, 3))
                .containsExactly(elsewhere.getId(), newest.getId(), tiedFirst.getId());
    }

    @Test
    void rowBooksAreTheStreamsListViewDtos() throws IOException {
        BookEntity first = book(fiction, "First", NOW);
        file(first, "first.epub", BookFileType.EPUB, true);
        tag(first, "Favourite");
        BookEntity second = book(fiction, null, NOW.minus(1, ChronoUnit.HOURS));
        file(second, "second.m4b", BookFileType.AUDIOBOOK, true);
        UserBookProgressEntity progress = progress(readerUser, second, ReadStatus.READING, NOW);
        progress.setKoboProgressPercent(40f);
        shelf(readerUser, "Mine", first);

        Map<Long, JsonNode> streamed = new HashMap<>();
        streamedList(reader).forEach(book -> streamed.put(book.get("id").asLong(), book));
        List<Book> row = rowService.getRow(reader, DashboardRowKind.RECENTLY_ADDED, 20);

        assertThat(row).extracting(Book::getId).containsExactly(first.getId(), second.getId());
        for (Book book : row) {
            assertThat(objectMapper.readTree(objectMapper.writeValueAsString(book))).isEqualTo(streamed.get(book.getId()));
        }
    }

    // ------------------------------------------------------------------ random

    @Test
    void randomLeavesOutTheStatusesTheDashboardLeavesOut() throws IOException {
        for (ReadStatus status : ReadStatus.values()) {
            progress(readerUser, book(fiction, status.name(), NOW), status, NOW);
        }
        progress(readerUser, book(fiction, "Null status", NOW), null, NOW);
        book(fiction, "No progress", NOW);
        // Only the viewer's own status counts
        progress(otherUser, book(fiction, "Read by someone else", NOW), ReadStatus.READ, NOW);
        deleted(book(fiction, "Deleted", NOW));
        book(hidden, "Elsewhere", NOW);

        List<Long> uiCandidates = uiRandomCandidates(reader);
        List<Long> row = rowIds(reader, DashboardRowKind.RANDOM, 50);

        assertThat(row).containsExactlyInAnyOrderElementsOf(uiCandidates).hasSize(6).doesNotHaveDuplicates();
    }

    @Test
    void randomRespectsTheSize() throws IOException {
        for (int i = 0; i < 12; i++) {
            book(fiction, "Book " + i, NOW);
        }
        List<Long> uiCandidates = uiRandomCandidates(reader);

        List<Long> row = rowIds(reader, DashboardRowKind.RANDOM, 5);

        assertThat(row).hasSize(5).doesNotHaveDuplicates();
        assertThat(uiCandidates).containsAll(row);
    }

    // ------------------------------------------------------------------ continue reading and listening

    @Test
    void continueRowsMatchTheDashboardsFilters() throws IOException {
        BookEntity epubRead = book(fiction, "Epub progress", NOW);
        progress(readerUser, epubRead, ReadStatus.READING, NOW.minus(1, ChronoUnit.HOURS)).setEpubProgressPercent(10f);

        BookEntity koboTie = book(fiction, "Kobo progress, same time", NOW);
        progress(readerUser, koboTie, ReadStatus.READING, NOW.minus(1, ChronoUnit.HOURS)).setKoboProgressPercent(20f);

        BookEntity pdfRereading = book(fiction, "Pdf, re-reading", NOW);
        progress(readerUser, pdfRereading, ReadStatus.RE_READING, NOW.minus(2, ChronoUnit.HOURS)).setPdfProgress(3);

        // The time and the progress both come from the file progress
        BookEntity pausedFile = book(fiction, "Paused, file progress", NOW);
        BookFileEntity pausedEpub = file(pausedFile, "paused.epub", BookFileType.EPUB, true);
        progress(readerUser, pausedFile, ReadStatus.PAUSED, null);
        fileProgress(readerUser, pausedEpub, NOW.minus(30, ChronoUnit.MINUTES));

        BookEntity finished = book(fiction, "Finished", NOW);
        progress(readerUser, finished, ReadStatus.READ, NOW).setEpubProgressPercent(100f);

        BookEntity noTime = book(fiction, "Never opened", NOW);
        progress(readerUser, noTime, ReadStatus.READING, null).setEpubProgressPercent(5f);

        BookEntity noStatusRow = book(fiction, "Only file progress", NOW);
        fileProgress(readerUser, file(noStatusRow, "only.epub", BookFileType.EPUB, true), NOW);

        BookEntity othersBook = book(fiction, "Someone else's", NOW);
        progress(otherUser, othersBook, ReadStatus.READING, NOW).setEpubProgressPercent(50f);

        BookEntity listening = book(fiction, "Listening", NOW);
        BookFileEntity listeningM4b = file(listening, "listening.m4b", BookFileType.AUDIOBOOK, true);
        progress(readerUser, listening, ReadStatus.READING, NOW.minus(3, ChronoUnit.HOURS));
        fileProgress(readerUser, listeningM4b, NOW.minus(10, ChronoUnit.MINUTES));

        BookEntity pausedListening = book(fiction, "Paused listening", NOW);
        BookFileEntity pausedM4b = file(pausedListening, "paused.m4b", BookFileType.AUDIOBOOK, true);
        progress(readerUser, pausedListening, ReadStatus.PAUSED, NOW.minus(5, ChronoUnit.HOURS));
        fileProgress(readerUser, pausedM4b, NOW.minus(4, ChronoUnit.HOURS));

        BookEntity listenedOut = book(fiction, "Listened to the end", NOW);
        progress(readerUser, listenedOut, ReadStatus.READ, NOW);
        fileProgress(readerUser, file(listenedOut, "end.m4b", BookFileType.AUDIOBOOK, true), NOW);

        BookEntity deletedBook = deleted(book(fiction, "Deleted", NOW));
        progress(readerUser, deletedBook, ReadStatus.READING, NOW).setEpubProgressPercent(1f);
        BookEntity elsewhere = book(hidden, "Elsewhere", NOW);
        progress(readerUser, elsewhere, ReadStatus.READING, NOW).setEpubProgressPercent(1f);

        assertThat(rowIds(reader, DashboardRowKind.CONTINUE_READING, 20))
                .isEqualTo(uiContinue(reader, false, 20))
                .containsExactly(pausedFile.getId(), epubRead.getId(), koboTie.getId(), pdfRereading.getId());
        assertThat(rowIds(reader, DashboardRowKind.CONTINUE_READING, 2)).isEqualTo(uiContinue(reader, false, 2));

        assertThat(rowIds(reader, DashboardRowKind.CONTINUE_LISTENING, 20))
                .isEqualTo(uiContinue(reader, true, 20))
                .containsExactly(listening.getId(), pausedListening.getId());
        assertThat(rowIds(reader, DashboardRowKind.CONTINUE_LISTENING, 1)).isEqualTo(uiContinue(reader, true, 1));
    }

    @Test
    void anotherUsersProgressGivesTheViewerNoRows() throws IOException {
        BookEntity book = book(fiction, "Someone else's", NOW);
        BookFileEntity m4b = file(book, "book.m4b", BookFileType.AUDIOBOOK, true);
        progress(otherUser, book, ReadStatus.READING, NOW).setEpubProgressPercent(50f);
        fileProgress(otherUser, m4b, NOW);

        assertThat(rowIds(reader, DashboardRowKind.CONTINUE_READING, 20)).isEmpty();
        assertThat(rowIds(reader, DashboardRowKind.CONTINUE_LISTENING, 20)).isEmpty();
        BookLoreUser other = viewer(otherUser, false, fiction);
        assertThat(rowIds(other, DashboardRowKind.CONTINUE_READING, 20)).isEqualTo(uiContinue(other, false, 20)).containsExactly(book.getId());
        assertThat(rowIds(other, DashboardRowKind.CONTINUE_LISTENING, 20)).isEqualTo(uiContinue(other, true, 20)).containsExactly(book.getId());
    }

    // ------------------------------------------------------------------ restrictions and empty viewers

    @Test
    void restrictedUserStillGetsAFullRowOfBooksTheyMaySee() throws IOException {
        BookLoreUserEntity restrictedUser = user("restricted");
        restrict(restrictedUser, ContentRestrictionType.TAG, "hidden");
        BookLoreUser restricted = viewer(restrictedUser, false, fiction);
        List<BookEntity> books = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            BookEntity book = book(fiction, "Book " + i, NOW.minus(i, ChronoUnit.HOURS));
            if (i < 3) tag(book, "Hidden");
            books.add(book);
        }

        assertThat(rowIds(restricted, DashboardRowKind.RECENTLY_ADDED, 2))
                .isEqualTo(uiLatestAdded(restricted, 2))
                .containsExactly(books.get(3).getId(), books.get(4).getId());
        assertThat(rowIds(restricted, DashboardRowKind.RANDOM, 10))
                .containsExactlyInAnyOrder(books.get(3).getId(), books.get(4).getId(), books.get(5).getId());
    }

    @Test
    void nonAdminWithoutLibrariesGetsEmptyRows() {
        book(fiction, "Book", NOW);
        BookLoreUser nobody = viewer(user("nobody"), false);

        for (DashboardRowKind kind : DashboardRowKind.values()) {
            assertThat(rowService.getRow(nobody, kind, 20)).as(kind.path()).isEmpty();
        }
    }

    // ------------------------------------------------------------------ getListViewBooks

    @Test
    void listViewBooksKeepTheGivenOrderAcrossChunks() {
        List<Long> visible = new ArrayList<>();
        for (int i = 0; i < 520; i++) {
            visible.add(book(fiction, null, NOW).getId());
            if (i % 100 == 0) em.clear();
        }
        Long deletedId = deleted(book(fiction, "Deleted", NOW)).getId();
        Long elsewhereId = book(hidden, "Elsewhere", NOW).getId();
        em.clear();

        List<Long> requested = new ArrayList<>(visible.reversed());
        requested.add(250, deletedId);
        requested.add(10, elsewhereId);
        requested.add(requested.get(300));
        requested.add(Long.MAX_VALUE);

        List<Long> forReader = bookService.getListViewBooks(reader, requested).stream().map(Book::getId).toList();
        List<Long> forAdmin = bookService.getListViewBooks(admin, requested).stream().map(Book::getId).toList();

        assertThat(forReader).isEqualTo(visible.reversed());
        List<Long> expectedForAdmin = new ArrayList<>(visible.reversed());
        expectedForAdmin.add(10, elsewhereId);
        assertThat(forAdmin).isEqualTo(expectedForAdmin);
        assertThat(bookService.getListViewBooks(reader, List.of())).isEmpty();
    }

    // ------------------------------------------------------------------ the dashboard's selections

    private List<Long> rowIds(BookLoreUser user, DashboardRowKind kind, int size) {
        em.flush();
        em.clear();
        return rowService.getRow(user, kind, size).stream().map(Book::getId).toList();
    }

    /** {@code getLatestAddedBooks}: books with an addedOn, newest first by milliseconds, the sort stable. */
    private List<Long> uiLatestAdded(BookLoreUser user, int maxItems) throws IOException {
        return streamedList(user).stream()
                .filter(book -> book.hasNonNull("addedOn"))
                .sorted(Comparator.comparingLong((JsonNode book) -> millis(book, "addedOn")).reversed())
                .limit(maxItems)
                .map(book -> book.get("id").asLong())
                .toList();
    }

    /** {@code getRandomBooks} before the shuffle: no status, or one the row does not leave out. */
    private List<Long> uiRandomCandidates(BookLoreUser user) throws IOException {
        return streamedList(user).stream()
                .filter(book -> !book.hasNonNull("readStatus") || !RANDOM_EXCLUDED.contains(book.get("readStatus").stringValue()))
                .map(book -> book.get("id").asLong())
                .toList();
    }

    /** {@code getLastReadBooks} and {@code getLastListenedBooks}. */
    private List<Long> uiContinue(BookLoreUser user, boolean listening, int maxItems) throws IOException {
        return streamedList(user).stream()
                .filter(book -> book.hasNonNull("lastReadTime"))
                .filter(book -> book.hasNonNull("readStatus") && IN_PROGRESS.contains(book.get("readStatus").stringValue()))
                .filter(book -> listening
                        ? book.hasNonNull("audiobookProgress")
                        : Stream.of("epubProgress", "pdfProgress", "cbxProgress", "koreaderProgress", "koboProgress").anyMatch(book::hasNonNull))
                .sorted(Comparator.comparingLong((JsonNode book) -> millis(book, "lastReadTime")).reversed())
                .limit(maxItems)
                .map(book -> book.get("id").asLong())
                .toList();
    }

    private static long millis(JsonNode book, String field) {
        return Instant.parse(book.get(field).stringValue()).toEpochMilli();
    }
}
