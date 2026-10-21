package org.booklore.service.library;

import org.booklore.model.enums.BookFileType;
import org.booklore.repository.projection.BookFileScanRow;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class WalkCoverageTest {

    private static BookFileScanRow row(long fileId, long bookId, String subPath, String fileName, boolean bookDeleted) {
        return new BookFileScanRow(fileId, bookId, 10L, "/books", subPath, fileName, true, BookFileType.EPUB, bookDeleted);
    }

    @Test
    void aRowCountsAsChangedUnlessItIsExactlyAsBeforeTheWalk() {
        BookFileScanRow before = row(1, 11, "a", "a.epub", false);
        WalkCoverage coverage = new WalkCoverage(Set.of(before), new LibraryScanResult(List.of(), Set.of()));

        assertThat(coverage.changedDuringWalk(row(1, 11, "a", "a.epub", false))).isFalse();
        assertThat(coverage.changedDuringWalk(row(1, 11, "b", "a.epub", false))).as("moved").isTrue();
        assertThat(coverage.changedDuringWalk(row(1, 11, "a", "b.epub", false))).as("renamed").isTrue();
        assertThat(coverage.changedDuringWalk(row(1, 11, "a", "a.epub", true))).as("book soft-deleted").isTrue();
        assertThat(coverage.changedDuringWalk(row(1, 12, "a", "a.epub", false))).as("reassigned").isTrue();
        assertThat(coverage.changedDuringWalk(row(2, 11, "a", "a.epub", false))).as("added").isTrue();
        assertThat(coverage.changedDuringWalk(
                new BookFileScanRow(1L, 11L, 20L, "/other", "a", "a.epub", true, BookFileType.EPUB, false)))
                .as("moved to another library path").isTrue();
    }

    @Test
    void whatTheWalkCouldNotReadIsWhatTheScanResultReports() {
        Path incomplete = Path.of("/books/Author/Title");
        WalkCoverage coverage = new WalkCoverage(Set.of(), new LibraryScanResult(List.of(), Set.of(incomplete)));

        // A folder audiobook is the directory itself; its tracks lie below it.
        assertThat(coverage.mayHaveMissed(Path.of("/books/Author/Title"))).isTrue();
        assertThat(coverage.mayHaveMissed(Path.of("/books/Author/Title/01.mp3"))).isTrue();
        assertThat(coverage.mayHaveMissed(Path.of("/books/Author/Other.epub"))).isFalse();
        assertThat(coverage.mayHaveMissed(null)).isFalse();
    }
}
