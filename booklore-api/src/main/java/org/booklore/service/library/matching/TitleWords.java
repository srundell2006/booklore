package org.booklore.service.library.matching;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The closed, English-only vocabularies {@link TitleKey} reads names with. A word outside them is always part of
 * the title: an unknown word is never dropped on a guess.
 */
final class TitleWords {

    private TitleWords() {
    }

    static final Set<String> ARTICLES = Set.of("the", "a", "an");

    private static final Set<String> FORMATS = Set.of(
            "pdf", "epub", "mobi", "azw", "azw3", "fb2", "cbz", "cbr", "cb7", "m4b", "m4a", "mp3", "opus", "kindle");

    /**
     * Words an edition or format descriptor is made of ("20th Anniversary Edition", "Complete and Uncut Edition",
     * "A Full Cast Production"). Genre words such as "Stories" or "A Novel" are not here: "A Quiver Full of Arrows -
     * Stories" is a different book from "A Quiver Full of Arrows".
     */
    private static final Set<String> DESCRIPTORS = new HashSet<>(Set.of(
            "edition", "ed", "anniversary", "illustrated", "annotated", "unabridged", "abridged", "complete", "uncut",
            "deluxe", "special", "collectors", "collector", "expanded", "revised", "extended", "updated", "definitive",
            "enhanced", "remastered", "digital", "retail", "ebook", "audiobook", "audio", "full", "cast", "production",
            "dramatized", "dramatised", "dramatization", "dramatisation", "version", "original", "scan",
            "first", "second", "third", "fourth", "fifth", "sixth", "seventh", "eighth", "ninth", "tenth",
            "twentieth", "thirtieth", "fortieth", "fiftieth", "sixtieth", "seventieth", "hundredth",
            "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "hundred",
            "and", "the", "a", "an"));

    /**
     * A run of descriptor words is only dropped when it holds one of these: "Special" or "Complete" alone are as
     * likely to be title words ("The Complete Sherlock Holmes" is not "Sherlock Holmes").
     */
    private static final Set<String> ANCHORS = new HashSet<>(Set.of(
            "edition", "ed", "anniversary", "illustrated", "annotated", "unabridged", "abridged", "uncut", "deluxe",
            "expanded", "revised", "remastered", "digital", "retail", "ebook", "audiobook", "audio", "dramatized",
            "dramatised", "dramatization", "dramatisation", "production", "scan"));

    /**
     * Anchors that mean a descriptor even as the only word next to a title ("The Hobbit Unabridged"). Others need a
     * second descriptor word ("Neuromancer 20th Anniversary"), since "Going Digital" or "Mister Ed" are titles.
     */
    private static final Set<String> STANDALONE_ANCHORS = new HashSet<>(Set.of(
            "unabridged", "abridged", "uncut", "ebook", "audiobook", "dramatized", "dramatised", "illustrated", "annotated"));

    /** Anchors that may also lead a name ("Audiobook The Hobbit"); "The Illustrated Man" keeps its first words. */
    private static final Set<String> LEADING_ANCHORS = new HashSet<>(Set.of(
            "unabridged", "abridged", "ebook", "audiobook", "dramatized", "dramatised"));

    /** Format words that are also English words: a descriptor only in brackets or next to another one ("Kindle Edition"). */
    private static final Set<String> WORDLIKE_FORMATS = Set.of("kindle", "opus");

    static {
        DESCRIPTORS.addAll(FORMATS);
        ANCHORS.addAll(FORMATS);
        // "Kindle the Flame" and "Magnum Opus" are titles
        FORMATS.stream().filter(format -> !WORDLIKE_FORMATS.contains(format)).forEach(STANDALONE_ANCHORS::add);
        FORMATS.stream().filter(format -> !WORDLIKE_FORMATS.contains(format)).forEach(LEADING_ANCHORS::add);
    }

    /** Words a volume or series number follows: "Book 2", "Vol. 3", "Part II", "#4", "No. 5", "Season One". */
    static final Set<String> SERIES_KEYWORDS = Set.of(
            "book", "bk", "vol", "volume", "part", "pt", "episode", "ep", "chapter", "issue", "season", "no", "number", "#");

    /** The keywords a comic's issue number follows ("#1", "Issue 1", "No. 1"); after the others it is a volume. */
    static final Set<String> ISSUE_KEYWORDS = Set.of("#", "issue", "no", "number", "episode", "ep", "chapter");

    /** The keywords after which a number may also be written as a word or a one-letter Roman numeral ("Book One", "Part I"). */
    private static final Set<String> WORD_NUMBER_KEYWORDS = Set.of(
            "book", "bk", "vol", "volume", "part", "pt", "episode", "ep", "chapter", "issue", "season");

    private static final Map<String, Integer> NUMBER_WORDS = new HashMap<>();

    static {
        List<String> cardinals = List.of("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
                "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen", "twenty");
        for (int i = 0; i < cardinals.size(); i++) {
            NUMBER_WORDS.put(cardinals.get(i), i);
        }
        List<String> ordinals = List.of("first", "second", "third", "fourth", "fifth", "sixth", "seventh", "eighth", "ninth",
                "tenth", "eleventh", "twelfth");
        for (int i = 0; i < ordinals.size(); i++) {
            NUMBER_WORDS.put(ordinals.get(i), i + 1);
        }
    }

    private static final Pattern DIGITS = Pattern.compile("\\d+");
    private static final Pattern RELEASE_NUMBER = Pattern.compile("v(\\d+)");
    private static final Pattern ORDINAL = Pattern.compile("(\\d+)(?:st|nd|rd|th)");
    /** I to XXXIX: enough for any volume, and "mix" or "civil" never match. */
    private static final Pattern ROMAN = Pattern.compile("x{0,3}(?:ix|iv|v?i{0,3})");

    /**
     * Descriptor words that name a different collection of a comic, not an edition of one: "Saga Volume 1 (Deluxe
     * Edition)" collects eighteen issues, "Saga Volume 1" six.
     */
    private static final Set<String> COMIC_COLLECTIONS = Set.of("deluxe");

    static boolean isDescriptor(String word) {
        return DESCRIPTORS.contains(word) || ORDINAL.matcher(word).matches();
    }

    /** {@link #isDescriptor(String)}, for a comic's name when {@code comic}. */
    static boolean isDescriptor(String word, boolean comic) {
        return isDescriptor(word) && !(comic && COMIC_COLLECTIONS.contains(word));
    }

    static boolean isAnchor(String word) {
        return ANCHORS.contains(word);
    }

    static boolean isStandaloneAnchor(String word) {
        return STANDALONE_ANCHORS.contains(word);
    }

    static boolean isLeadingAnchor(String word) {
        return LEADING_ANCHORS.contains(word);
    }

    static boolean isNumberWord(String word) {
        return NUMBER_WORDS.containsKey(word);
    }

    /**
     * The number a word stands for on its own: digits without leading zeros ("007" is 7), a release-style "v01", an
     * ordinal ("2nd"), or a Roman numeral of two letters or more ("II", "XIV"). One-letter numerals are read only
     * after a keyword ({@link #numberAfter}): "I, Robot" and "X-Men" are titles.
     */
    static String number(String word) {
        if (DIGITS.matcher(word).matches()) {
            return withoutLeadingZeros(word);
        }
        var release = RELEASE_NUMBER.matcher(word);
        if (release.matches()) {
            return withoutLeadingZeros(release.group(1));
        }
        var ordinal = ORDINAL.matcher(word);
        if (ordinal.matches()) {
            return withoutLeadingZeros(ordinal.group(1));
        }
        if (word.length() >= 2 && ROMAN.matcher(word).matches()) {
            return String.valueOf(roman(word));
        }
        return null;
    }

    /** The number a word stands for right after {@code keyword}, or null when it is not one ("Book of Dust"). */
    static String numberAfter(String keyword, String word) {
        String n = number(word);
        if (n != null || !WORD_NUMBER_KEYWORDS.contains(keyword)) {
            return n;
        }
        if (word.length() == 1 && ROMAN.matcher(word).matches()) {
            return String.valueOf(roman(word));
        }
        Integer value = NUMBER_WORDS.get(word);
        return value == null ? null : String.valueOf(value);
    }

    /** A release-style number ("v01"): a comic's volume. */
    static boolean isReleaseNumber(String word) {
        return RELEASE_NUMBER.matcher(word).matches();
    }

    private static String withoutLeadingZeros(String digits) {
        return digits.replaceFirst("^0+(?=\\d)", "");
    }

    private static int roman(String numeral) {
        int total = 0;
        int previous = 0;
        for (int i = numeral.length() - 1; i >= 0; i--) {
            int value = switch (numeral.charAt(i)) {
                case 'i' -> 1;
                case 'v' -> 5;
                case 'x' -> 10;
                default -> 0;
            };
            total += value < previous ? -value : value;
            previous = Math.max(previous, value);
        }
        return total;
    }
}
