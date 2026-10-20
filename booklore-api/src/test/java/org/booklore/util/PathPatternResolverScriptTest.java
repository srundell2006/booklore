package org.booklore.util;

import org.booklore.model.dto.BookMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.text.Normalizer;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * sanitize() strips accents from Latin letters only. It used to drop every non-ASCII code point, which emptied
 * titles and authors written in other scripts and produced names like "01.  - Naguib Mahfouz (2000)".
 */
class PathPatternResolverScriptTest {

    private static String resolve(String title, String author, String pattern, String fileName) {
        BookMetadata metadata = BookMetadata.builder().title(title).authors(List.of(author)).build();
        return PathPatternResolver.resolvePattern(metadata, pattern, fileName);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "Arabic   | الثلاثية                | نجيب محفوظ",
            "Chinese  | 中国文化合集              | 费孝通",
            "Japanese | ガイドがある             | 村上春樹",
            "Hindi    | गोदान                   | प्रेमचंद",
            "Russian  | Война и мир             | Лев Толстой",
            "Greek    | Ελληνικά διηγήματα      | Αλέξανδρος Παπαδιαμάντης",
            "Korean   | 소년이 온다              | 한강",
    })
    void otherScripts_keepEveryCharacter(String script, String title, String author) {
        String result = resolve(title, author, "{authors}/{title}", "original.epub");

        assertThat(result).isEqualTo(author + "/" + title + ".epub");
    }

    @Test
    void latinAccents_areStillStripped() {
        String result = resolve("Derrière la haine", "Gabriel García Márquez", "{authors}/{title}", "original.epub");

        assertThat(result).isEqualTo("Gabriel Garcia Marquez/Derriere la haine.epub");
    }

    @Test
    void stackedLatinAccents_areStripped() {
        assertThat(resolve("Tiếng Việt", "Nguyễn Du", "{authors}/{title}", "a.epub")).isEqualTo("Nguyen Du/Tieng Viet.epub");
    }

    @Test
    void decomposedInput_isStrippedTheSameWay() {
        // File names copied from macOS arrive in NFD
        String nfdTitle = Normalizer.normalize("Café crème", Normalizer.Form.NFD);

        assertThat(resolve(nfdTitle, "Émile Zola", "{authors}/{title}", "a.epub")).isEqualTo("Emile Zola/Cafe creme.epub");
    }

    @Test
    void latinLettersWithoutAnAccentMark_areKept() {
        // ø and ł are letters of their own, not a base letter plus an accent; they used to vanish ("Sren")
        assertThat(resolve("Frygt og Bæven", "Søren Kierkegaard", "{authors}/{title}", "a.epub"))
                .isEqualTo("Søren Kierkegaard/Frygt og Bæven.epub");
        assertThat(resolve("Ziemia obiecana", "Władysław Reymont", "{authors}/{title}", "a.epub"))
                .isEqualTo("Władysław Reymont/Ziemia obiecana.epub");
    }

    @Test
    void mixedScripts_keepTheNonLatinPartAndStripLatinAccents() {
        assertThat(resolve("Le Petit Prince (星の王子さま)", "Antoine de Saint-Exupéry", "{authors}/{title}", "a.epub"))
                .isEqualTo("Antoine de Saint-Exupery/Le Petit Prince (星の王子さま).epub");
    }

    @Test
    void folderAudiobook_withAnArabicTitle_keepsTheTitle() {
        BookMetadata metadata = BookMetadata.builder().title("قصر الشوق").authors(List.of("Naguib Mahfouz")).build();

        String result = PathPatternResolver.resolvePattern(metadata, "01. {title} - {authors}", "old folder name");

        assertThat(result).isEqualTo("01. قصر الشوق - Naguib Mahfouz");
    }
}
