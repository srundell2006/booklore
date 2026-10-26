package org.booklore.service.library;

import org.booklore.repository.projection.FilelessBookRow;
import org.booklore.service.library.matching.ShellCandidate;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/** How the projection rows of a library's fileless books, one per author, become one candidate per book. */
class FilelessShellsTest {

    @Test
    void oneCandidatePerBookWithWhatItsRowsSay() {
        List<FilelessBookRow> rows = List.of(
                new FilelessBookRow(1L, 10L, false, "cover", null, "The Testaments", null, "The Handmaid's Tale", 2f, "Margaret Atwood"),
                new FilelessBookRow(1L, 10L, false, "cover", null, "The Testaments", null, "The Handmaid's Tale", 2f, "Ann Smith"),
                new FilelessBookRow(2L, null, true, null, null, "Storm Front (The Dresden Files, #1)", null, null, null, null),
                new FilelessBookRow(3L, 10L, null, null, "audio", "Saga", "Volume Two", "  ", 2f, " "));

        List<ShellCandidate> candidates = FilelessShells.candidates(rows);

        assertThat(candidates).extracting(ShellCandidate::bookId, ShellCandidate::libraryPathId, ShellCandidate::physical,
                        ShellCandidate::hasCover, ShellCandidate::title, ShellCandidate::seriesName, ShellCandidate::seriesPlace)
                .containsExactly(
                        tuple(1L, 10L, false, true, "The Testaments", "The Handmaid's Tale", 2f),
                        tuple(2L, null, true, false, "Storm Front (The Dresden Files, #1)", "The Dresden Files", 1f),
                        tuple(3L, 10L, false, true, "Saga", null, 2f));
        assertThat(candidates.get(0).authors()).hasSize(2);
        assertThat(candidates.get(0).seriesIdentities()).containsExactly("handmaids tale");
        assertThat(candidates.get(1).seriesIdentities()).containsExactly("dresden files");
        // A blank series name is none, and a blank author no author
        assertThat(candidates.get(2).seriesIdentities()).isEmpty();
        assertThat(candidates.get(2).authors()).isEmpty();
        assertThat(candidates.get(2).volumeNumbers()).containsExactly("2");
    }
}
