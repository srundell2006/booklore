package org.booklore.util;

import org.booklore.model.dto.BookMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.text.Normalizer;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * sanitize() strips accents from Latin letters only, and keeps the letters, digits and marks of every script. It used
 * to drop every non-ASCII code point, which emptied titles and authors written in other scripts and produced names
 * like "01.  - Naguib Mahfouz (2000)". Punctuation, symbols and invisible characters are still dropped, so names in
 * Latin script come out as they did before, and books whose names were fine are not renamed.
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

    private static String sanitized(String title) {
        return PathPatternResolver.resolvePattern(BookMetadata.builder().title(title).build(), "{title}", "untitled");
    }

    /** sanitize() as it was before non-Latin text was kept: decompose, then drop every non-ASCII code point. */
    private static String asciiOnlySanitize(String input) {
        String ascii = Normalizer.normalize(input, Normalizer.Form.NFD).replaceAll("[^\\p{ASCII}]", "");
        return ascii.replaceAll("[\\\\/:*?\"<>|]", "").replaceAll("\\p{Cntrl}", "").replaceAll("\\s+", " ").trim();
    }

    static Stream<Arguments> latinTitles() {
        return Stream.of(
                arguments("Harry Potter and the Philosopher’s Stone", "Harry Potter and the Philosophers Stone"),
                arguments("A – B", "A B"),
                arguments("Derrière", "Derriere"),
                arguments("“Quoted” — and… more", "Quoted and more"),
                arguments("¿Qué? ¡Sí!", "Que Si!"),
                arguments("«Les Misérables» · Tome 1", "Les Miserables Tome 1"),
                arguments("Pokémon™ © 2000 ½", "Pokemon 2000"),
                arguments("Love \u2764\uFE0F Story \uD83D\uDE00", "Love Story"),
                arguments("Chapter 1\uFE0F\u20E3", "Chapter 1"),
                arguments("Soft\u00ADhyphen", "Softhyphen"),
                arguments("\u202EReversed\u202C \u200Eand\u200F \u2066isolated\u2069", "Reversed and isolated"),
                arguments("\uFEFFZero\u200Bwidth\u200C\u200Djoiners", "Zerowidthjoiners"),
                arguments("Stray marks on e\u200B\u0301 and 1\u0301", "Stray marks on e and 1"),
                arguments("Tab\tand bell\u0007", "Taband bell"),
                arguments("C1\u0085 and \u0092 controls", "C1 and controls")
        );
    }

    @ParameterizedTest(name = "{1}")
    @MethodSource("latinTitles")
    void latinTitles_comeOutAsTheyDidWhenEveryNonAsciiCodePointWasDropped(String title, String expected) {
        assertThat(sanitized(title)).isEqualTo(expected).isEqualTo(asciiOnlySanitize(title));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
            "Søren Kierkegaard     | Søren Kierkegaard",
            "Władysław Łoziński    | Władysław Łozinski",
            "Æsop and Œdipus       | Æsop and Œdipus",
            "Die Straße            | Die Straße",
            "Đorđe Balašević       | Đorđe Balasevic",
            "Þórr and Óðinn        | Þorr and Oðinn",
            "Iğdır                 | Igdır",
            "Ħamrun                | Ħamrun",
            "Ŋaŋa                  | Ŋaŋa",
    })
    void latinLettersWithNoAccentToStrip_areKept(String title, String expected) {
        assertThat(sanitized(title)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
            // Every letter is kept, so these differ from the names they used to get ("Signicance", "Winters Tale")
            "The Signi\uFB01cance of \uFB02ow  | The Signi\uFB01cance of \uFB02ow",
            "N\u00BA 5 and 1\u00AA edici\u00F3n | N\u00BA 5 and 1\u00AA edicion",
            "XIX\u1D49 si\u00E8cle             | XIX\u1D49 siecle",
            "Winter\u02BCs Tale                | Winter\u02BCs Tale",
            "Winter\u2019s Tale                | Winters Tale",
    })
    void ligaturesOrdinalsAndModifierLetters_areKeptLikeAnyOtherLetter(String title, String expected) {
        // Ligatures, ordinal indicators and modifier letters (the U+02BC apostrophe among them) are letters, \p{L}
        assertThat(sanitized(title)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "Persian with ZWNJ    | \u06A9\u062A\u0627\u0628\u200C\u0647\u0627 \u0645\u06CC\u200C\u062E\u0648\u0627\u0647\u0645",
            "Devanagari with ZWJ  | \u0915\u094D\u200D\u0937",
            "Sinhala with ZWJ     | \u0D9A\u200D\u0DCA\u0DC0",
            "Arabic-Indic digits  | \u0627\u0644\u062C\u0632\u0621 \u0663",
            "Thai                 | \u0E2A\u0E27\u0E31\u0E2A\u0E14\u0E35",
            "Hebrew with niqqud   | \u05E9\u05C1\u05B8\u05DC\u05D5\u05B9\u05DD",
    })
    void otherScripts_keepTheirJoinersMarksAndDigits(String script, String title) {
        assertThat(sanitized(title)).isEqualTo(Normalizer.normalize(title, Normalizer.Form.NFC));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"ガイドがある", "Война и мир", "소년이 온다", "Ελληνικά διηγήματα"})
    void decomposedNonLatinInput_comesOutComposed(String title) {
        // File names written by macOS clients arrive in NFD
        String decomposed = Normalizer.normalize(title, Normalizer.Form.NFD);

        assertThat(decomposed).isNotEqualTo(title);
        assertThat(sanitized(decomposed)).isEqualTo(title);
    }

    @ParameterizedTest(name = "{1}")
    @CsvSource(delimiter = '|', value = {
            "Titre\u00A0: sous-titre          | Titre sous-titre",
            "Titre\u00A0:\u00A0sous-titre     | Titre sous-titre",
            "10\u2009000 lieues               | 10 000 lieues",
            "東京\u3000物語                    | 東京 物語",
            "Line\u2028and\u2029paragraph     | Line and paragraph",
            "'\u00A0 padded \u3000'           | padded",
    })
    void unicodeSpaces_becomeOneAsciiSpace(String title, String expected) {
        assertThat(sanitized(title)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
            "《三体》                      | 三体",
            "こんにちは。世界               | こんにちは世界",
            "الثلاثية، الجزء الأول          | الثلاثية الجزء الأول",
            "«Война и мир»                 | Война и мир",
    })
    void nonLatinPunctuation_isDropped(String title, String expected) {
        assertThat(sanitized(title)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "U+{0}")
    @ValueSource(strings = {
            "0085", "0092", "009F", "200E", "200F", "202A", "202B", "202C", "202D", "202E", "2066", "2067", "2068",
            "2069", "FEFF", "200B", "00AD", "2060", "2122", "00A9", "00BD", "2764", "1F600", "1F1EB", "1F3FD",
            "E000", "F0000", "0378", "D800", "DC00",
    })
    void invisibleCharactersSymbolsAndEmoji_neverReachTheName(String hex) {
        String character = new String(Character.toChars(Integer.parseInt(hex, 16)));

        assertThat(sanitized("Naguib" + character + "Mahfouz")).isEqualTo("NaguibMahfouz");
        assertThat(sanitized("قصر" + character + "الشوق")).isEqualTo("قصرالشوق");
        assertThat(sanitized("中国" + character + " " + character + "文化")).isEqualTo("中国 文化");
    }

    @Test
    void emojiSequences_leaveNothingBehind() {
        assertThat(sanitized("猫\u2764\uFE0F")).isEqualTo("猫");
        assertThat(sanitized("Family \uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67 Album")).isEqualTo("Family Album");
        assertThat(sanitized("\u0633\u0644\u0627\u0645 #\uFE0F\u20E3")).isEqualTo("\u0633\u0644\u0627\u0645 #");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "\u200C\u06A9\u062A\u0627\u0628\u200C",
            "\u06A9\u200C \u0628",
            "\u06A9\u200C\uD83D\uDE00",
    })
    void joinersAtTheEdgeOfAWord_areDropped(String title) {
        assertThat(sanitized(title)).doesNotContain("\u200C");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Harry Potter and the Philosopher’s Stone", "«Les Misérables» · Tome 1", "Titre\u00A0:\u00A0sous-titre",
            "ガイドがある", "\u30AB\u3099", "Война и мир", "\u1100:\u1161", "e\u200B\u0301", "\u0301leading mark",
            "\u06A9\u200C\u200C\u0628", "\u06A9\u200C\uD83D\uDE00\u0628", "\u0915\u094D\u200D\u0937",
            "\u0D9A\u200D\u0DCA\u0DC0", "\u0D9A\u2764\u200D\u0DCA", "a\u200D\u0301b", "Love \u2764\uFE0F Story",
            "Æsop · Œdipus", "\uD800lone\uDC00", "\u202Bقصر\u200F الشوق\u202C", "1\uFE0F\u20E3", "  ",
    })
    void sanitizing_isStableAndComposed(String title) {
        String once = sanitized(title);

        assertThat(sanitized(once)).isEqualTo(once);
        assertThat(Normalizer.isNormalized(once, Normalizer.Form.NFC)).isTrue();
    }
}
