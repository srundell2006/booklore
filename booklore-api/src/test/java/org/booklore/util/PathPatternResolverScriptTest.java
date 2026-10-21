package org.booklore.util;

import org.booklore.model.dto.BookMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.text.Normalizer;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * sanitize() strips accents from Latin letters only, and keeps the letters, digits and marks of every script. It used
 * to drop every non-ASCII code point, which emptied titles and authors written in other scripts and produced names
 * like "01.  - Naguib Mahfouz (2000)". Punctuation, symbols and invisible characters are still dropped, so names in
 * Latin script come out as they did before, and books whose names were fine are not renamed. Between two non-Latin
 * words punctuation becomes a space, and letters that are only another form of ASCII letters (ﬁ, Ａ, 𝐁) are written
 * in ASCII.
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
                arguments("A\u2013B", "AB"),
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
                arguments("C1\u0085 and \u0092 controls", "C1 and controls"),
                // Modifier letters of no script (U+02BC apostrophe, okina, stress mark) are dropped next to Latin letters
                arguments("Winter\u02BCs Tale", "Winters Tale"),
                arguments("Hawai\u02BBi and \u02C8stress", "Hawaii and stress"),
                // Numbers that are not digits, symbols that spell letters and the letters that are emoji are dropped
                arguments("E=mc\u00B2 \u00BD", "E=mc"),
                arguments("Henry \u2167", "Henry"),
                arguments("Chapter \u2460", "Chapter"),
                arguments("Star Wars\u2122 \u2116 4", "Star Wars 4"),
                arguments("Info \u2139\uFE0F and Metro \u24C2\uFE0F", "Info and Metro"),
                arguments("Parenthesized \u249C\u249D", "Parenthesized")
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
            // These letters used to vanish ("Signicance", "XIX siecle"); they are now written as the ASCII they stand for
            "The Signi\uFB01cance of \uFB02ow  | The Significance of flow",
            "N\u00BA 5 and 1\u00AA edici\u00F3n | No 5 and 1a edicion",
            "XIX\u1D49 si\u00E8cle             | XIXe siecle",
            "\uFF28\uFF41\uFF52\uFF52\uFF59 \uFF11         | Harry 1",
            "\uD835\uDC01\uD835\uDC28\uD835\uDC25\uD835\uDC1D \uD835\uDFCF | Bold 1",
            "\u24B6\u24D1\u24D2 \uD83C\uDD30BC        | Abc ABC",
            "\u0132sselmeer                  | IJsselmeer",
            "Wei\u017Fs and \u01C8ubljana        | Weiss and Ljubljana",
            "\uD835\uDC00\u0301 and \uFF25\u0301        | A and E",
    })
    void compatibilityLetters_areWrittenInAscii(String title, String expected) {
        assertThat(sanitized(title)).isEqualTo(expected);
    }

    @Test
    void compatibilityLetters_thatAreMoreThanAsciiLetters_areLeftAlone() {
        // Ǆ is D + Ž, not plain ASCII, and stays the letter it is; the rest are not letters and are dropped as before
        assertThat(sanitized("\u01C4 \u00B2\u00BD\u2460\u2167\u2122\u2116\u249C \u2139\u24C2 x")).isEqualTo("\u01C4 x");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "Persian with ZWNJ    | \u06A9\u062A\u0627\u0628\u200C\u0647\u0627 \u0645\u06CC\u200C\u062E\u0648\u0627\u0647\u0645",
            "Devanagari with ZWJ  | \u0915\u094D\u200D\u0937",
            "Sinhala with ZWJ     | \u0D9A\u200D\u0DCA\u0DC0",
            "Arabic-Indic digits  | \u0627\u0644\u062C\u0632\u0621 \u0663",
            "Thai                 | \u0E2A\u0E27\u0E31\u0E2A\u0E14\u0E35",
            "Hebrew with niqqud   | \u05E9\u05C1\u05B8\u05DC\u05D5\u05B9\u05DD",
            "Katakana with ー     | \u30B3\u30FC\u30D2\u30FC",
            "Halfwidth katakana   | \uFF76\uFF9E\uFF70\uFF99",
            "Arabic with tatweel  | \u06A9\u0640\u062A\u0627\u0628",
            "Ukrainian with ʼ     | \u043C\u02BC\u044F\u0442\u0430",
    })
    void otherScripts_keepTheirJoinersMarksModifiersAndDigits(String script, String title) {
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
            "こんにちは。                  | こんにちは",
            "الثلاثية، الجزء الأول          | الثلاثية الجزء الأول",
            "«Война и мир»                 | Война и мир",
            "三体 · 黑暗森林                | 三体 黑暗森林",
    })
    void nonLatinPunctuationAtTheEdgeOfAWord_isDropped(String title, String expected) {
        assertThat(sanitized(title)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
            "三体·黑暗森林                  | 三体 黑暗森林",
            "東京・大阪                     | 東京 大阪",
            "こんにちは。世界               | こんにちは 世界",
            "ハリー・ポッター               | ハリー ポッター",
            "Война—мир                     | Война мир",
            "《三体》《黑暗森林》            | 三体 黑暗森林",
    })
    void nonLatinPunctuationBetweenNonLatinWords_becomesASpace(String title, String expected) {
        // It used to glue the words together ("三体黑暗森林")
        assertThat(sanitized(title)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
            "三体·2                        | 三体2",
            "東京·Tokyo                    | 東京Tokyo",
            "Bjørk·東京                    | Bjørk東京",
            "п\u2019ять                       | пять",
            "Жанна д\u2019Арк                 | Жанна дАрк",
            "三体-黑暗                      | 三体-黑暗",
            "三体:黑暗                      | 三体黑暗",
    })
    void punctuationNextToAsciiOrLatinText_orInsideAWord_isDroppedAsBefore(String title, String expected) {
        // ASCII and Latin neighbours keep Latin names as they were; an apostrophe sits inside a word
        assertThat(sanitized(title)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "U+{0}")
    @ValueSource(strings = {
            "0085", "0092", "009F", "200E", "200F", "202A", "202B", "202C", "202D", "202E", "2066", "2067", "2068",
            "2069", "FEFF", "200B", "00AD", "2060", "00BD", "00B2", "2460", "2167", "E000", "F0000", "0378", "D800", "DC00",
    })
    void invisibleCharactersAndNumbers_neverReachTheName(String hex) {
        String character = new String(Character.toChars(Integer.parseInt(hex, 16)));

        assertThat(sanitized("Naguib" + character + "Mahfouz")).isEqualTo("NaguibMahfouz");
        assertThat(sanitized("قصر" + character + "الشوق")).isEqualTo("قصرالشوق");
        assertThat(sanitized("中国" + character + " " + character + "文化")).isEqualTo("中国 文化");
    }

    @ParameterizedTest(name = "U+{0}")
    @ValueSource(strings = {"2122", "00A9", "2764", "1F600", "1F1EB", "1F3FD", "2116", "2139", "24C2", "00B7", "2014"})
    void symbolsAndEmoji_neverReachTheName_butKeepNonLatinWordsApart(String hex) {
        String character = new String(Character.toChars(Integer.parseInt(hex, 16)));

        assertThat(sanitized("Naguib" + character + "Mahfouz")).isEqualTo("NaguibMahfouz");
        assertThat(sanitized("قصر" + character + "الشوق")).isEqualTo("قصر الشوق");
        assertThat(sanitized("中国" + character + " " + character + "文化")).isEqualTo("中国 文化");
    }

    @Test
    void emojiSequences_leaveNothingBehind() {
        assertThat(sanitized("猫\u2764\uFE0F")).isEqualTo("猫");
        assertThat(sanitized("Family \uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67 Album")).isEqualTo("Family Album");
        assertThat(sanitized("\u0633\u0644\u0627\u0645 #\uFE0F\u20E3")).isEqualTo("\u0633\u0644\u0627\u0645 #");
        assertThat(sanitized("猫\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67狗")).isEqualTo("猫 狗");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "\u200C\u06A9\u062A\u0627\u0628\u200C",
            "\u06A9\u200C \u0628",
            "\u06A9\u200C\uD83D\uDE00",
            "\u06A9\u200C\u00B7\u0628",
            "\u06A9\u00B7\u200C\u0628",
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
            "三体·黑暗森林", "ハリー・ポッター", "\u06A9\u200C\u00B7\u0628", "\u30AB\u00B7\u30FC\u30AD", "\u6771\u30FC\u00B7a",
            "\u06A9\u200D\u0640", "\uFF76\uFF9E\uFF70", "Winter\u02BCs", "\uD835\uDC00\u0301", "\u24B6\u2139\u24C2",
            "\u4E2D\u3001\u3001\u200C\u6587", "\u30AB\u3099\u00B7\u3099\u30AD",
    })
    void sanitizing_isStableAndComposed(String title) {
        String once = sanitized(title);

        assertThat(sanitized(once)).isEqualTo(once);
        assertThat(Normalizer.isNormalized(once, Normalizer.Form.NFC)).isTrue();
    }

    /**
     * The cases the move preview must agree on: pattern-resolver.spec.ts in booklore-ui asserts this same table
     * (SHARED_SANITIZE_CASES), so a row added or changed here belongs there too. Each row is a description, a
     * title and the name sanitize() makes of it.
     */
    private static final String[][] SHARED_SANITIZE_CASES = {
            // Punctuation and symbols between non-Latin words become a space
            {"middle dot between Chinese words", "三体·黑暗森林", "三体 黑暗森林"},
            {"katakana middle dot", "東京・大阪", "東京 大阪"},
            {"middle dot after a prolonged sound mark", "ハリー・ポッター", "ハリー ポッター"},
            {"fullwidth colon", "三体\uFF1A黑暗森林", "三体 黑暗森林"},
            {"ideographic full stop", "こんにちは。世界", "こんにちは 世界"},
            {"em dash between Cyrillic words", "Война—мир", "Война мир"},
            {"danda between Hindi words", "गोदान।प्रेमचंद", "गोदान प्रेमचंद"},
            {"Arabic comma before an Arabic-Indic digit", "الجزء،٣", "الجزء ٣"},
            {"symbol between Arabic words", "قصر™الشوق", "قصر الشوق"},
            {"emoji between Chinese words", "猫\uD83D\uDE00狗", "猫 狗"},
            {"emoji sequence between Chinese words", "猫\uD83D\uDC68\u200D\uD83D\uDC69狗", "猫 狗"},
            {"two in a row make one space", "三体·—黑暗", "三体 黑暗"},
            {"after a letter with a mark", "カ\u3099·キ\u3099", "ガ ギ"},
            {"joiner before it is dropped", "ک\u200C·ب", "ک ب"},
            // and are dropped as before anywhere else
            {"brackets at the edges", "《三体》", "三体"},
            {"comma before a space", "الثلاثية، الجزء الأول", "الثلاثية الجزء الأول"},
            {"already spaced", "三体 · 黑暗", "三体 黑暗"},
            {"next to an ASCII digit", "三体·2", "三体2"},
            {"next to a Latin word", "東京·Tokyo", "東京Tokyo"},
            {"next to a Latin letter that is not ASCII", "Bjørk·東京", "Bjørk東京"},
            {"Latin apostrophe", "Harry Potter and the Philosopher\u2019s Stone", "Harry Potter and the Philosophers Stone"},
            {"Latin dash", "A\u2013B", "AB"},
            {"Latin quotes and middle dot", "«Les Misérables» · Tome 1", "Les Miserables Tome 1"},
            {"apostrophe inside a Ukrainian word", "п\u2019ять", "пять"},
            {"apostrophe in a Russian name", "Жанна д\u2019Арк", "Жанна дАрк"},
            {"Hebrew geresh", "ג\u05F3ורג\u05F3", "גורג"},
            {"zero-width space, which is not punctuation", "中国\u200B文化", "中国文化"},
            {"fraction, which is not punctuation", "中国\u00BD文化", "中国文化"},
            // ASCII punctuation is handled as before
            {"ASCII hyphen", "三体-黑暗", "三体-黑暗"},
            {"ASCII colon", "三体:黑暗", "三体黑暗"},
            // Letters and digits that are another form of ASCII ones are written in ASCII
            {"ligatures", "The Signi\uFB01cance of \uFB02ow", "The Significance of flow"},
            {"fullwidth letters and digits", "\uFF28\uFF41\uFF52\uFF52\uFF59 \uFF11", "Harry 1"},
            {"mathematical letters and digits", "\uD835\uDC01\uD835\uDC28\uD835\uDC25\uD835\uDC1D \uD835\uDFCF", "Bold 1"},
            {"circled and squared letters", "\u24B6\u24D1\u24D2 \uD83C\uDD30", "Abc A"},
            {"ordinal indicators", "N\u00BA 5 and 1\u00AA edición", "No 5 and 1a edicion"},
            {"superscript letter", "XIX\u1D49 siècle", "XIXe siecle"},
            {"Dutch ij and long s", "\u0132sselmeer Wei\u017Fs", "IJsselmeer Weiss"},
            {"accent on a mathematical letter", "\uD835\uDC00\u0301", "A"},
            {"inside non-Latin text", "中\uFB01文", "中fi文"},
            // and the rest are left as they were
            {"ligature that is not plain ASCII stays a letter", "\u01C4", "\u01C4"},
            {"parenthesized letters, which are more than letters", "A\u249C\u249DB", "AB"},
            {"superscript digits and fractions", "E=mc\u00B2 \u00BD", "E=mc"},
            {"Roman numerals", "Henry \u2167", "Henry"},
            {"circled digits", "Chapter \u2460", "Chapter"},
            {"symbols that spell letters", "Pokémon\u2122 \u2116 5", "Pokemon 5"},
            {"letters that are emoji", "Info \u2139\uFE0F Metro \u24C2\uFE0F", "Info Metro"},
            // Modifier letters of no script are dropped next to Latin text and kept in non-Latin words
            {"modifier apostrophe in a Latin word", "Winter\u02BCs Tale", "Winters Tale"},
            {"okina", "Hawai\u02BBi", "Hawaii"},
            {"stress mark", "\u02C8stress", "stress"},
            {"before a Latin word", "\u30FCstart", "start"},
            {"prolonged sound marks", "コ\u30FCヒ\u30FC", "コ\u30FCヒ\u30FC"},
            {"prolonged sound mark before Latin letters", "ファイナルファンタジ\u30FCVII", "ファイナルファンタジ\u30FCVII"},
            {"halfwidth katakana", "\uFF76\uFF9E\uFF70\uFF99", "\uFF76\uFF9E\uFF70\uFF99"},
            {"Arabic tatweel", "ک\u0640تاب", "ک\u0640تاب"},
            {"modifier apostrophe in a Ukrainian word", "м\u02BCята", "м\u02BCята"},
            {"Greek numeral sign", "\u0391\u0374", "\u0391\u02B9"},
    };

    static Stream<Arguments> sharedSanitizeCases() {
        return Arrays.stream(SHARED_SANITIZE_CASES).map(row -> arguments((Object[]) row));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("sharedSanitizeCases")
    void sharedCases_comeOutAsTheMovePreviewShowsThem(String description, String title, String expected) {
        assertThat(sanitized(title)).isEqualTo(expected);
    }

    // Characters that occur in and around names in Latin script, and those of other scripts
    private static final int[] LATIN_NAME_CHARACTERS = {
            'a', 'Z', 'e', '1', ' ', '-', '.', '\'', '_', ':', '?', '\t', 0xE9, 0x0301, 0x0308, 0xFE0F, 0x20E3, 0x200C,
            0x200D, 0x200B, 0x200E, 0xFEFF, 0xAD, 0x85, 0x2019, 0x2018, 0x2013, 0x2014, 0x2026, 0xAB, 0xBB, 0xB7, 0x30FB,
            0x3002, 0xFF0C, 0xFF1A, 0x300A, 0x2122, 0xA9, 0xBD, 0xB2, 0x2460, 0x216B, 0x2139, 0x24C2, 0x1F600, 0x1F3FD,
            0x1F1EB, 0x2260, 0x2116, 0x02BC, 0x02C8, 0x02BB, 0x02B9, 0x0374, 0x30FC, 0x0640, 0xFF70, 0xFF9E, 0x3031, 0xE000,
            0x0378, 0x249C, 0x05F3, 0x2032, 0xB4,
    };
    private static final int[] OTHER_NAME_CHARACTERS = {
            0x4E09, 0x4F53, 0x30AC, 0x30AB, 0x3099, 0xFF76, 0x06A9, 0x0628, 0x064E, 0x043F, 0x0414, 0x0915, 0x094D, 0x0D9A,
            0x0DCA, 0x05E9, 0x05C1, 0x0E2A, 0x1100, 0x1161, 0xAC00, 0x0663, 0x00F8, 0x0142, 0x00A0, 0x3000, 0x2028, 0xFB01,
            0xFF21, 0xFF11, 0x1D401, 0x1D7CF, 0xAA, 0x1D49, 0x017F, 0x0133, 0x01C5, 0x01C8, 0xB5, 0x24B6, 0x1F130, 0x0E46,
            0x3005, 0xD800, 0xDC00, 0x1E9B,
    };

    private static String randomName(Random random, int[] alphabet) {
        StringBuilder name = new StringBuilder();
        for (int length = 1 + random.nextInt(16); length > 0; length--) {
            name.appendCodePoint(alphabet[random.nextInt(alphabet.length)]);
        }
        return name.toString();
    }

    @Test
    void randomNames_sanitizeStablyAndComposed() {
        int[] alphabet = IntStream.concat(Arrays.stream(LATIN_NAME_CHARACTERS), Arrays.stream(OTHER_NAME_CHARACTERS)).toArray();
        Random random = new Random(20261021);
        for (int i = 0; i < 20_000; i++) {
            String name = randomName(random, alphabet);
            String once = sanitized(name);

            assertThat(sanitized(once)).as("sanitizing %s twice", name.codePoints().mapToObj(Integer::toHexString).toList())
                    .isEqualTo(once);
            assertThat(Normalizer.isNormalized(once, Normalizer.Form.NFC)).isTrue();
        }
    }

    @Test
    void randomLatinNames_comeOutAsTheyDidWhenEveryNonAsciiCodePointWasDropped() {
        Random random = new Random(20261021);
        for (int i = 0; i < 20_000; i++) {
            // Letters around it, so the name is never empty
            String name = "a" + randomName(random, LATIN_NAME_CHARACTERS) + "a";

            assertThat(sanitized(name)).as("sanitizing %s", name.codePoints().mapToObj(Integer::toHexString).toList())
                    .isEqualTo(asciiOnlySanitize(name));
        }
    }

    // Names in other scripts now reach the 200-byte cap of a title at 70 to 100 characters

    @Test
    void longTitleCutAfterASpace_endsWithoutIt() {
        // The first 200 bytes end in a space
        String title = "مكتبة " + "كتاب ".repeat(21) + "الأخير";
        BookMetadata metadata = BookMetadata.builder().title(title).publishedDate(LocalDate.of(2000, 1, 1)).build();

        String result = PathPatternResolver.resolvePattern(metadata, "{title} ({year})", "a.epub");

        assertThat(result).isEqualTo("مكتبة " + "كتاب ".repeat(20) + "كتاب (2000).epub");
    }

    @Test
    void longTitleCutInsideACharacter_endsBeforeIt() {
        // 200 bytes end between य and its candrabindu: याँ is one character to a reader, and stays whole
        String title = "क" + "कहानियाँ ".repeat(7) + "कहानियाँ";

        String result = resolve(title, "Author", "{title}", "a.epub");

        assertThat(result).isEqualTo("क" + "कहानियाँ ".repeat(7) + "कहानि.epub");
    }

    @Test
    void longTitleCutAfterAJoiner_endsWithoutIt() {
        // 200 bytes end right after the zero-width non-joiner of می‌خواهم, which then joins nothing
        String title = "کتاب ".repeat(21) + "نومی\u200Cخواهم";

        String result = resolve(title, "Author", "{title}", "a.epub");

        assertThat(result).isEqualTo("کتاب ".repeat(21) + "نومی.epub");
    }

    @Test
    void longLatinTitleCutAfterASpace_endsWithoutIt() {
        String title = "word ".repeat(40) + "end";

        String result = resolve(title, "Author", "{title}", "a.epub");

        assertThat(result).isEqualTo("word ".repeat(39) + "word.epub");
    }

    @Test
    void longLatinTitleCutInsideAWord_isCutAtTheByteLimitAsBefore() {
        String title = "word ".repeat(39) + "words and more";

        String result = resolve(title, "Author", "{title}", "a.epub");

        assertThat(result).isEqualTo("word ".repeat(39) + "words.epub");
    }
}
