package org.booklore.service.library;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class LibraryScanResultTest {

    @Test
    void pathIsUnderAnIncompletePathAtOrBelowIt() {
        LibraryScanResult result = new LibraryScanResult(List.of(), Set.of(Path.of("/books/Author"), Path.of("/books/locked.epub")));

        assertThat(result.isComplete()).isFalse();
        assertThat(result.isUnderIncompletePath(Path.of("/books/Author"))).isTrue();
        assertThat(result.isUnderIncompletePath(Path.of("/books/Author/Book/ch01.mp3"))).isTrue();
        assertThat(result.isUnderIncompletePath(Path.of("/books/locked.epub"))).isTrue();
        // Whole path components only, and nothing above an incomplete path.
        assertThat(result.isUnderIncompletePath(Path.of("/books/Author 2/book.epub"))).isFalse();
        assertThat(result.isUnderIncompletePath(Path.of("/books/locked.epub.part"))).isFalse();
        assertThat(result.isUnderIncompletePath(Path.of("/books"))).isFalse();
        assertThat(result.isUnderIncompletePath(Path.of("/"))).isFalse();
        assertThat(result.isUnderIncompletePath(null)).isFalse();
    }

    @Test
    void pathsAreComparedAbsoluteAndNormalized() {
        LibraryScanResult result = new LibraryScanResult(List.of(), Set.of(Path.of("/books/./Author/../Shelf"), Path.of("relative")));

        assertThat(result.incompletePaths()).containsExactlyInAnyOrder(
                Path.of("/books/Shelf"), Path.of("relative").toAbsolutePath());
        assertThat(result.isUnderIncompletePath(Path.of("/books/Shelf/a.epub"))).isTrue();
        assertThat(result.isUnderIncompletePath(Path.of("/books/Other/../Shelf/a.epub"))).isTrue();
        assertThat(result.isUnderIncompletePath(Path.of("relative/a.epub"))).isTrue();
        assertThat(result.isUnderIncompletePath(Path.of("relative/a.epub").toAbsolutePath())).isTrue();
    }

    @Test
    void completeResultCoversNothing() {
        LibraryScanResult result = new LibraryScanResult(List.of(), Set.of());

        assertThat(result.isComplete()).isTrue();
        assertThat(result.isUnderIncompletePath(Path.of("/books/a.epub"))).isFalse();
    }

    @Test
    void manyIncompletePathsStayCheapToCheck() {
        // Thousands of unreadable files, each also a candidate the rescan checks before deleting its book.
        Set<Path> unreadable = new HashSet<>();
        for (int i = 0; i < 20_000; i++) {
            unreadable.add(Path.of("/books/Author " + i + "/book.epub"));
        }
        LibraryScanResult result = new LibraryScanResult(List.of(), unreadable);

        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            for (Path path : unreadable) {
                assertThat(result.isUnderIncompletePath(path)).isTrue();
                assertThat(result.isUnderIncompletePath(path.resolveSibling("other.epub"))).isFalse();
            }
        });
    }
}
