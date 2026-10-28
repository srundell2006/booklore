package org.booklore.service.catalog;

import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.catalog.MagicShelfCount;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookLoreUserEntity;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.entity.MagicShelfEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.model.enums.ContentRestrictionType;
import org.booklore.service.MagicShelfService;
import org.booklore.service.catalog.MagicShelfCountCompiler.Compiled;
import org.booklore.service.catalog.MagicShelfCountCompiler.Leaf;
import org.booklore.service.catalog.MagicShelfCountCompiler.Supported;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which shelves are counted and how each status comes about. The counts themselves are checked against the UI's
 * evaluator in {@link CatalogParityTest}.
 */
class MagicShelfCountServiceTest extends CatalogDatabaseTest {

    private static final Instant ADDED = Instant.parse("2026-10-01T12:00:00Z");
    private static final String TITLE_STARTS_WITH_D = "{\"type\":\"group\",\"join\":\"and\",\"rules\":[{\"field\":\"title\",\"operator\":\"starts_with\",\"value\":\"d\"}]}";
    private static final String SERIES_STATUS = "{\"type\":\"group\",\"join\":\"and\",\"rules\":[{\"field\":\"seriesStatus\",\"operator\":\"equals\",\"value\":\"reading\"}]}";
    private static final String NOT_JSON = "{\"type\":\"group\",";
    private static final String NOT_PDF = "{\"type\":\"group\",\"join\":\"and\",\"rules\":[{\"field\":\"fileType\",\"operator\":\"not_equals\",\"value\":\"pdf\"}]}";

    @Autowired
    private MagicShelfCountService countService;
    @Autowired
    private MagicShelfService magicShelfService;
    @Autowired
    private CatalogViewerResolver viewerResolver;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private LibraryEntity fiction;
    private LibraryEntity comics;
    private BookLoreUserEntity readerUser;
    private BookLoreUserEntity otherUser;

    @BeforeEach
    void fixture() {
        fiction = library("fiction", BookFileType.EPUB);
        comics = library("comics", BookFileType.CBX);
        readerUser = user("reader");
        otherUser = user("other");

        book(fiction, "Dune", ADDED);
        book(fiction, "Dracula", ADDED);
        book(fiction, "Emma", ADDED);
        deleted(book(fiction, "Deleted dune", ADDED));
        book(comics, "Daredevil", ADDED);
    }

    @Test
    void listsOwnShelvesAndOtherUsersPublicOnes() {
        MagicShelfEntity ok = magicShelf(readerUser, "Starts with d", TITLE_STARTS_WITH_D, false);
        MagicShelfEntity invalid = magicShelf(readerUser, "Broken", NOT_JSON, false);
        MagicShelfEntity unsupported = magicShelf(readerUser, "Series", SERIES_STATUS, false);
        MagicShelfEntity othersPublic = magicShelf(otherUser, "Public", TITLE_STARTS_WITH_D, true);
        magicShelf(otherUser, "Private", TITLE_STARTS_WITH_D, false);

        Map<Long, MagicShelfCount> counts = counts(viewer(readerUser, false, fiction));

        assertThat(counts).containsOnlyKeys(ok.getId(), invalid.getId(), unsupported.getId(), othersPublic.getId());
        // Dune and Dracula: the deleted book and the other library's comic are not in the reader's list
        assertThat(counts.get(ok.getId())).isEqualTo(MagicShelfCount.ok(2));
        assertThat(counts.get(invalid.getId())).isEqualTo(MagicShelfCount.invalid());
        assertThat(counts.get(unsupported.getId())).isEqualTo(MagicShelfCount.unsupported(List.of("seriesStatus:equals")));
        assertThat(counts.get(othersPublic.getId())).isEqualTo(MagicShelfCount.ok(2));
    }

    @Test
    void anAdminCountsEveryLibrary() {
        MagicShelfEntity ok = magicShelf(readerUser, "Starts with d", TITLE_STARTS_WITH_D, false);

        Map<Long, MagicShelfCount> counts = counts(viewer(readerUser, true));

        assertThat(counts.get(ok.getId())).isEqualTo(MagicShelfCount.ok(3));
    }

    @Test
    void aRestrictedUserLeavesEveryShelfToTheClient() {
        MagicShelfEntity ok = magicShelf(readerUser, "Starts with d", TITLE_STARTS_WITH_D, false);
        MagicShelfEntity invalid = magicShelf(readerUser, "Broken", NOT_JSON, false);
        restrict(readerUser, ContentRestrictionType.CATEGORY, "Horror");

        Map<Long, MagicShelfCount> counts = counts(viewer(readerUser, false, fiction));

        assertThat(counts).containsOnly(
                Map.entry(ok.getId(), MagicShelfCount.unsupported(List.of("restrictions"))),
                Map.entry(invalid.getId(), MagicShelfCount.unsupported(List.of("restrictions"))));
    }

    @Test
    void aViewerWithoutBooksCountsZeroWhateverTheRules() {
        MagicShelfEntity ok = magicShelf(readerUser, "Starts with d", TITLE_STARTS_WITH_D, false);
        MagicShelfEntity invalid = magicShelf(readerUser, "Broken", NOT_JSON, false);
        MagicShelfEntity unsupported = magicShelf(readerUser, "Series", SERIES_STATUS, false);

        Map<Long, MagicShelfCount> counts = counts(viewer(readerUser, false));

        // The UI filters an empty list and never evaluates a rule
        assertThat(counts.get(ok.getId())).isEqualTo(MagicShelfCount.ok(0));
        assertThat(counts.get(unsupported.getId())).isEqualTo(MagicShelfCount.ok(0));
        assertThat(counts.get(invalid.getId())).isEqualTo(MagicShelfCount.invalid());
    }

    @Test
    void aFailingQueryIsAnErrorAndTheOtherShelvesStillCount() {
        String failing = "{\"type\":\"group\",\"join\":\"and\",\"rules\":[{\"field\":\"title\",\"operator\":\"equals\",\"value\":\"fails\"}]}";
        MagicShelfEntity broken = magicShelf(readerUser, "Fails in SQL", failing, false);
        MagicShelfEntity ok = magicShelf(readerUser, "Starts with d", TITLE_STARTS_WITH_D, false);
        MagicShelfCountCompiler compiler = new MagicShelfCountCompiler() {
            @Override
            public Compiled compile(String filterJson) {
                if (!failing.equals(filterJson)) return super.compile(filterJson);
                return new Supported(new Leaf(ctx -> ctx.cb().equal(ctx.cb().function("no_such_function", Integer.class), 1)),
                        List.of(), List.of());
            }
        };
        MagicShelfCountService service = new MagicShelfCountService(em, compiler, magicShelfService, viewerResolver, transactionManager);

        em.flush();
        em.clear();
        Map<Long, MagicShelfCount> counts = service.getCounts(viewer(readerUser, false, fiction)).magicShelves();

        assertThat(counts.get(broken.getId())).isEqualTo(MagicShelfCount.error());
        assertThat(counts.get(ok.getId())).isEqualTo(MagicShelfCount.ok(2));
    }

    @Test
    void fileTypeRulesAreCheckedAgainstThePrimaryFile() {
        // Comics prefer CBX, so the comic's PDF is not its primary file
        BookEntity comic = book(comics, "Both", ADDED);
        file(comic, "both.pdf", BookFileType.PDF, true);
        file(comic, "both.cbz", BookFileType.CBX, true);
        BookEntity pdf = book(fiction, "Pdf", ADDED);
        file(pdf, "only.pdf", BookFileType.PDF, true);
        file(pdf, "cover.epub", BookFileType.EPUB, false);
        MagicShelfEntity notPdf = magicShelf(readerUser, "Not pdf", NOT_PDF, false);

        Map<Long, MagicShelfCount> counts = counts(viewer(readerUser, true));

        // Every visible book but the one whose primary file is a PDF: Dune, Dracula, Emma, Daredevil and Both
        assertThat(counts.get(notPdf.getId())).isEqualTo(MagicShelfCount.ok(5));
    }

    @Test
    void tooManyFileTypeCandidatesAreLeftToTheClient() {
        Long pathId = fiction.getLibraryPaths().getFirst().getId();
        jdbcTemplate.batchUpdate("INSERT INTO book (library_id, library_path_id, deleted) VALUES (?, ?, FALSE)",
                IntStream.range(0, MagicShelfCountService.FILE_TYPE_CANDIDATE_LIMIT)
                        .mapToObj(i -> new Object[]{fiction.getId(), pathId})
                        .toList());
        CatalogViewer viewer = viewerResolver.resolve(viewer(readerUser, true));
        Supported compiled = (Supported) new MagicShelfCountCompiler().compile(NOT_PDF);

        // 5,004 visible books are candidates: one past the limit is enough to refuse
        assertThat(countService.count(viewer, compiled, Instant.now()))
                .isEqualTo(MagicShelfCount.unsupported(List.of("fileType:not_equals")));

        BookLoreUser reader = viewer(readerUser, false, comics);
        assertThat(countService.count(viewerResolver.resolve(reader), compiled, Instant.now())).isEqualTo(MagicShelfCount.ok(1));
    }

    /** Read back from the database, as a request reads them. */
    private Map<Long, MagicShelfCount> counts(BookLoreUser user) {
        em.flush();
        em.clear();
        return countService.getCounts(user).magicShelves();
    }
}
