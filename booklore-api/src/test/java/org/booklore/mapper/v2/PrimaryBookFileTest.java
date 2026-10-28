package org.booklore.mapper.v2;

import org.booklore.model.enums.BookFileType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PrimaryBookFileTest {

    private record File(String name, boolean isBook, BookFileType type) {
    }

    private static File select(List<File> files, List<BookFileType> priority) {
        return PrimaryBookFile.select(files, priority, File::isBook, File::type);
    }

    private final File epub = new File("a.epub", true, BookFileType.EPUB);
    private final File cbx = new File("a.cbz", true, BookFileType.CBX);
    private final File secondEpub = new File("b.epub", true, BookFileType.EPUB);
    private final File cover = new File("cover.pdf", false, BookFileType.PDF);

    @Test
    void theLibrarysFormatPriorityDecides() {
        assertThat(select(List.of(epub, cbx), List.of(BookFileType.CBX, BookFileType.EPUB))).isSameAs(cbx);
        assertThat(select(List.of(cbx, epub), List.of(BookFileType.EPUB))).isSameAs(epub);
    }

    @Test
    void theFirstFileOfThePreferredTypeWins() {
        assertThat(select(List.of(cbx, epub, secondEpub), List.of(BookFileType.EPUB))).isSameAs(epub);
    }

    @Test
    void withoutAMatchTheFirstBookFormatWins() {
        assertThat(select(List.of(cbx, epub), List.of(BookFileType.AUDIOBOOK))).isSameAs(cbx);
        assertThat(select(List.of(cbx, epub), List.of())).isSameAs(cbx);
        assertThat(select(List.of(cbx, epub), null)).isSameAs(cbx);
    }

    @Test
    void supplementaryFilesAreNeverPrimary() {
        assertThat(select(List.of(cover, epub), List.of(BookFileType.PDF))).isSameAs(epub);
        assertThat(select(List.of(cover), List.of(BookFileType.PDF))).isNull();
    }

    @Test
    void aFilelessBookHasNone() {
        assertThat(select(List.of(), List.of(BookFileType.EPUB))).isNull();
        assertThat(select(null, List.of(BookFileType.EPUB))).isNull();
    }
}
