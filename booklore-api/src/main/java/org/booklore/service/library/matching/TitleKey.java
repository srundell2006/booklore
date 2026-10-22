package org.booklore.service.library.matching;

import org.booklore.model.enums.BookFileExtension;
import org.booklore.model.enums.BookFileType;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a file name or a book title says about which book it is, reduced to the parts that tell two books apart: the
 * title's words, the volume or series numbers it names, and the publication years in brackets. Two names are the same
 * book only when their {@link #identity}s are equal. Nothing is ever decided by how similar two names look: "A
 * Dangerous Universe 3" and "A Dangerous Universe 5" share all but one character and are still two books.
 * <p>
 * Only closed vocabularies are dropped on sight ({@link TitleWords}): the extension, accents, a duplicate's copy marker,
 * bracketed years, release tags and edition or format descriptors, and, for audio files, the part or disc a book is
 * split over. A " - X" or " by X" part is dropped as an author only when X is a person, with a given name or an
 * initial, who agrees with something the {@link NameContext} knows (a folder of the file, an author of the book it is
 * compared with), or when the last part is written with initials ("J.R.R. Tolkien"). A part that merely looks like a
 * name stays in the title: "Assignment - Karachi" is not "Assignment", and "KING - Las Vegas Bad Boys" is not "King".
 *
 * @param title       the title's words, lower case, without accents, punctuation, numbers or a leading article
 * @param numbers     every volume or series number, in order and without leading zeros ("Henry IV Part 2" is [4, 2])
 * @param years       publication years written in brackets ("(2009)")
 * @param part        for an audio file, the part or disc of a book split over several files; null otherwise
 * @param copy        a duplicate's copy marker at the end of a file name; null when there is none
 * @param droppedName the part dropped as an author, as written; null when none was
 */
public record TitleKey(String title, List<String> numbers, Set<Integer> years, Integer part, CopyMarker copy,
                       String droppedName) {

    /**
     * " (1)" as a browser names a second download, or " - Copy" / "(copy)" as a file manager names a duplicate.
     *
     * @param number the n of " (n)", or null for " - Copy" and "(copy)"
     * @param issue  whether n, when it is no copy after all, is a comic's issue number rather than a volume's
     */
    public record CopyMarker(Integer number, boolean issue) {
    }

    /** What an issue number of a comic is marked with in an identity: "saga #i1" is issue 1, "saga #1" volume 1. */
    private static final String ISSUE = "i";

    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /** " (1)" to " (9)". Two digits are more likely a volume ("Saga (12)") than a twelfth download of one file. */
    private static final Pattern COPY_NUMBER = Pattern.compile("^(.*?\\S)\\s*\\(([1-9])\\)$");
    /**
     * " - Copy", " - Copy (2)", " - Copy 2", "(copy)", "(Copy 2)". The dash must be spaced and a bare " copy" does not
     * count: "Carbon-Copy" and "Carbon Copy" are titles.
     */
    private static final Pattern COPY_WORD = Pattern.compile(
            "^(.*?\\S)(?:\\s+[-\u2013\u2014]\\s*copy(?:\\s*\\(?\\d{1,2}\\)?)?|\\s*\\(\\s*copy(?:\\s*\\d{1,2})?\\s*\\))$",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern AUDIO_BOOK = Pattern.compile("\\baudio[\\s-]book\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern E_BOOK = Pattern.compile("\\be[\\s-]book\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern BRACKET = Pattern.compile("[(\\[{]([^()\\[\\]{}]*)[)\\]}]");
    private static final Pattern YEAR = Pattern.compile("(?:1[5-9]|20)\\d{2}");
    /** "(v5.0)", "[v2]": a release's revision, not a volume. */
    private static final Pattern RELEASE_TAG = Pattern.compile("v\\d+(?:\\.\\d+)*", Pattern.CASE_INSENSITIVE);
    private static final String PART_WORD = "(?:part|pt|dis[ck]|cd)";
    private static final String NUMBER_WORD = "(?:[ivx]{1,7}|zero|one|two|three|four|five|six|seven|eight|nine|ten|eleven"
            + "|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|twenty)";
    /**
     * The number of a part: digits, which may follow the keyword at once ("CD1"), or a Roman numeral or a number word
     * after a space ("Part II", "Part One"). At most four digits: a longer number ("Part 9780261102217") is no part of
     * a book split over files, and would not fit an int.
     */
    private static final String PART_NUMBER = "(?:\\s*\\.?\\s*(\\d{1,4})|\\.?\\s+(" + NUMBER_WORD + "))(?![\\p{L}\\p{N}])";
    private static final String OF_TOTAL = "(?:\\s*of\\s*\\d{1,4}|\\s+of\\s+" + NUMBER_WORD + ")?";
    /** "Part 2", "disc 1 of 3", "CD1", "Part Two": a whole bracket group, or a whole name. */
    private static final Pattern PART_CONTENT = Pattern.compile(PART_WORD + PART_NUMBER + OF_TOTAL, Pattern.CASE_INSENSITIVE);
    private static final Pattern TRAILING_PART = Pattern.compile(
            "\\s*(?:[-\u2013\u2014]\\s*|\\s)" + PART_WORD + PART_NUMBER + OF_TOTAL + "$", Pattern.CASE_INSENSITIVE);
    /** "[1-2]", "(1 of 2)", "(2/3)": a part and the number of parts, in brackets. */
    private static final Pattern PART_OF_PARTS = Pattern.compile("(\\d{1,2})\\s*(?:of|-|/)\\s*(\\d{1,2})", Pattern.CASE_INSENSITIVE);

    /** " - ", " – ", " — " and ": ". Spaced dashes only: "Spider-Man" and "Catch-22" are one word. */
    private static final Pattern SEGMENT_SEPARATOR = Pattern.compile("\\s+[-\u2013\u2014]\\s+|\\s*:\\s+");
    private static final Pattern BY = Pattern.compile("\\s+by\\s+", Pattern.CASE_INSENSITIVE);
    private static final Pattern ARTICLE_SUFFIX = Pattern.compile("^(.*?),\\s*(the|a|an)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern APOSTROPHES = Pattern.compile("['\u2018\u2019`]");
    private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{N}#]+");

    private static final Pattern INITIALS = Pattern.compile("(?:\\p{Lu}\\.)+");
    private static final Pattern NAME_WORD = Pattern.compile("\\p{Lu}[\\p{L}'\u2019.-]*");
    private static final Pattern GLUED_INITIALS = Pattern.compile("(?<=\\.)(?=\\p{Lu})");
    private static final Set<String> NAME_PARTICLES = Set.of(
            "de", "del", "della", "der", "den", "di", "da", "du", "la", "le", "van", "von", "ten", "ter", "y", "&", "and");

    public TitleKey {
        numbers = List.copyOf(numbers);
        years = Collections.unmodifiableSet(new TreeSet<>(years));
    }

    /**
     * The key of a book file's name.
     *
     * @param audio whether the file is an audiobook: only then are part and disc markers ("Part 2", "(disc 1)") read
     *              as one book split over several files rather than as a second volume
     */
    public static TitleKey ofFileName(String fileName, boolean audio, NameContext context) {
        Prepared prepared = prepare(baseName(fileName), true, audio, isComic(fileName));
        return prepared.read(context == null ? NameContext.NONE : context);
    }

    /** The key of a book's title as its metadata has it: no extension, no copy marker, no audio parts. */
    public static TitleKey ofTitle(String title, NameContext context) {
        return prepare(title, false, false, false).read(context == null ? NameContext.NONE : context);
    }

    /**
     * Every way a file name can be read: first as {@link #ofFileName} reads it, then with each part that could be a
     * person's name dropped although nothing confirmed it ("Primal Heat - Lora Leigh" also reads as "Primal Heat"
     * with "Lora Leigh" dropped). Such a reading is only evidence once the caller has confirmed its
     * {@link #droppedName} itself, for instance against the authors of the one book it is comparing with.
     */
    public static List<TitleKey> readings(String fileName, boolean audio, NameContext context) {
        Prepared prepared = prepare(baseName(fileName), true, audio, isComic(fileName));
        NameContext ctx = context == null ? NameContext.NONE : context;
        Map<String, TitleKey> readings = new LinkedHashMap<>();
        TitleKey chosen = prepared.read(ctx);
        readings.put(chosen.identity() + "|" + chosen.droppedName(), chosen);
        for (TitleKey reading : prepared.nameReadings()) {
            readings.putIfAbsent(reading.identity() + "|" + reading.droppedName(), reading);
        }
        return List.copyOf(readings.values());
    }

    /**
     * A comic book archive: in its name a bare number, or one after "#", "Issue" or "No.", is an issue ("Saga 001",
     * "Saga #1"), and a number after "Vol.", "v" or "Book" a volume collecting several issues ("Saga Vol. 1"). The two
     * are different books. A metadata title's numbers are all read as volumes, so an issue never matches one.
     */
    private static boolean isComic(String fileName) {
        return fileName != null && BookFileExtension.fromFileName(fileName)
                .map(extension -> extension.getType() == BookFileType.CBX).orElse(false);
    }

    /** The file name without a book file extension; a folder audiobook's name, which has none, as it is. */
    public static String baseName(String fileName) {
        if (fileName == null) {
            return "";
        }
        return BookFileExtension.fromFileName(fileName)
                .map(extension -> fileName.substring(0, fileName.length() - extension.getExtension().length() - 1))
                .orElse(fileName);
    }

    /** A base name without a duplicate's copy marker (" (1)", " - Copy", "(copy)"); as it is when it has none. */
    public static String withoutCopyMarker(String baseName) {
        String s = collapse(baseName == null ? "" : baseName);
        Matcher word = COPY_WORD.matcher(s);
        if (word.matches()) {
            return word.group(1).trim();
        }
        Matcher number = COPY_NUMBER.matcher(s);
        return number.matches() ? number.group(1).trim() : s;
    }

    /**
     * An audiobook file's base name without the part or disc it holds: "Title (Part 2)" and "Title - Disc 1" are
     * "Title", and a name that is only a disc ("CD1") is "".
     */
    public static String withoutAudioPart(String baseName) {
        String s = collapse(baseName == null ? "" : baseName);
        StringBuilder kept = new StringBuilder();
        Matcher bracket = BRACKET.matcher(s);
        int last = 0;
        while (bracket.find()) {
            kept.append(s, last, bracket.start());
            if (bracketPart(bracket.group(1).trim()) == null) {
                kept.append(bracket.group());
            }
            last = bracket.end();
        }
        kept.append(s.substring(last));
        s = collapse(kept.toString());
        Matcher whole = PART_CONTENT.matcher(s);
        if (whole.matches() && partNumber(whole) != null) {
            return "";
        }
        Matcher trailingPart = TRAILING_PART.matcher(s);
        return trailingPart.find() && partNumber(trailingPart) != null ? s.substring(0, trailingPart.start()).trim() : s;
    }

    /** The part a bracket group names ("Part 2", "disc One", "1 of 2", "1-2"), or null when it names none. */
    private static Integer bracketPart(String inner) {
        Matcher part = PART_CONTENT.matcher(inner);
        if (part.matches()) {
            return partNumber(part);
        }
        Matcher ofParts = PART_OF_PARTS.matcher(inner);
        if (ofParts.matches()) {
            int n = Integer.parseInt(ofParts.group(1));
            return n >= 1 && n <= Integer.parseInt(ofParts.group(2)) ? n : null;
        }
        return null;
    }

    /** The number a {@link #PART_CONTENT} or {@link #TRAILING_PART} match names, or null for a word that is no numeral. */
    private static Integer partNumber(Matcher part) {
        if (part.group(1) != null) {
            return Integer.parseInt(part.group(1));
        }
        String number = TitleWords.numberAfter("part", part.group(2).toLowerCase(Locale.ROOT));
        return number == null ? null : Integer.parseInt(number);
    }

    /** The book this name reads as. A " (n)" copy number counts as one more number: "Saga (2)" is the second Saga. */
    public String identity() {
        if (copy != null && copy.number() != null) {
            List<String> withCopy = new ArrayList<>(numbers);
            withCopy.add((copy.issue() ? ISSUE : "") + copy.number());
            return identity(title, withCopy);
        }
        return identity(title, numbers);
    }

    /** The book this name is a duplicate of, when it is one: its identity without the copy marker. */
    public String identityWithoutCopy() {
        return identity(title, numbers);
    }

    /**
     * The people the part dropped as an author names, each with a given name or an initial: empty when no part was
     * dropped, or when the part dropped names the file's own folder (a series: "KING - Las Vegas Bad Boys" in "Las
     * Vegas Bad Boys").
     */
    public List<PersonName> namedAuthors(NameContext context) {
        if (droppedName == null || (context != null && context.isOwnFolder(droppedName))) {
            return List.of();
        }
        return PersonName.parseAll(droppedName).stream().filter(PersonName::hasGivenName).toList();
    }

    private static String identity(String title, List<String> numbers) {
        // Numbers are taken out of the title, so "#" never appears in one: "henry #4/2" cannot be read two ways
        if (numbers.isEmpty()) {
            return title;
        }
        return (title.isEmpty() ? "#" : title + " #") + String.join("/", numbers);
    }

    /**
     * A name with the closed vocabularies already taken out: what is left to read for a title and an author.
     */
    private record Prepared(String text, Set<Integer> years, Integer part, CopyMarker copy, boolean comic) {

        /** The reading the context supports: at most one part dropped, and only one it confirms as an author. */
        TitleKey read(NameContext ctx) {
            if (text.isEmpty() && part != null && !ctx.leafName().isEmpty()) {
                // A disc and nothing else ("CD1" in "Tolkien/The Hobbit"): the disc of the book its folder names
                TitleKey folder = prepare(ctx.leafName(), false, false, false).read(ctx);
                Set<Integer> allYears = new TreeSet<>(years);
                allYears.addAll(folder.years());
                return new TitleKey(folder.title(), folder.numbers(), allYears, part, copy, null);
            }
            List<String> segments = segments(text, comic);
            List<String> beforeBy = beforeBy(segments);
            if (beforeBy != null) {
                String name = byName(segments);
                if (ctx.confirms(name) || hasInitials(name)) {
                    return finish(beforeBy, name);
                }
            }
            if (segments.size() > 1) {
                int author = authorSegment(segments, ctx);
                if (author >= 0) {
                    String dropped = segments.remove(author);
                    return finish(segments, dropped);
                }
            }
            return finish(segments, null);
        }

        /** Each reading with one name-like part dropped, confirmed or not: the " by " side, the first or the last part. */
        List<TitleKey> nameReadings() {
            List<TitleKey> out = new ArrayList<>();
            List<String> segments = segments(text, comic);
            List<String> beforeBy = beforeBy(segments);
            if (beforeBy != null) {
                out.add(finish(beforeBy, byName(segments)));
            }
            if (segments.size() > 1) {
                for (int index : List.of(0, segments.size() - 1)) {
                    String candidate = segments.get(index);
                    if (looksLikeName(candidate) && hasTitleWord(joinWithout(segments, index))) {
                        List<String> rest = new ArrayList<>(segments);
                        rest.remove(index);
                        out.add(finish(rest, candidate));
                    }
                }
            }
            return out;
        }

        private TitleKey finish(List<String> segments, String droppedName) {
            String joined = String.join(" ", segments.stream().map(TitleKey::articleToFront).toList());
            List<String> titleWords = new ArrayList<>();
            List<String> numbers = new ArrayList<>();
            List<String> tokens = withoutDescriptorRuns(words(joined), comic);
            for (int i = 0; i < tokens.size(); i++) {
                String token = tokens.get(i);
                if (TitleWords.SERIES_KEYWORDS.contains(token) && i + 1 < tokens.size()) {
                    String number = TitleWords.numberAfter(token, tokens.get(i + 1));
                    if (number != null) {
                        numbers.add(comic && TitleWords.ISSUE_KEYWORDS.contains(token) ? ISSUE + number : number);
                        i++;
                        continue;
                    }
                }
                if (token.equals("#")) {
                    continue;
                }
                String number = TitleWords.number(token);
                if (number != null) {
                    // A comic's "v01" is its first volume; any other bare number its issue
                    numbers.add(comic && !TitleWords.isReleaseNumber(token) ? ISSUE + number : number);
                } else {
                    titleWords.add(token);
                }
            }
            if (titleWords.size() > 1 && TitleWords.ARTICLES.contains(titleWords.getFirst())) {
                titleWords.removeFirst();
            }
            return new TitleKey(String.join(" ", titleWords), numbers, years, part, copy, droppedName);
        }
    }

    private static Prepared prepare(String raw, boolean fileName, boolean audio, boolean comic) {
        String s = collapse(foldAccents(raw == null ? "" : raw).replace('_', ' '));

        CopyMarker copy = null;
        if (fileName) {
            Matcher word = COPY_WORD.matcher(s);
            Matcher number = COPY_NUMBER.matcher(s);
            if (word.matches()) {
                copy = new CopyMarker(null, comic);
                s = word.group(1).trim();
            } else if (number.matches()) {
                copy = new CopyMarker(Integer.parseInt(number.group(2)), comic);
                s = number.group(1).trim();
            }
        }
        s = AUDIO_BOOK.matcher(s).replaceAll("Audiobook");
        s = E_BOOK.matcher(s).replaceAll("Ebook");

        // Bracketed groups: a year is kept apart, an audio part is noted, a release tag or a group of descriptor
        // words ("(Illustrated Edition)", "[epub]") is dropped, and anything else ("(Graphic Novel)") stays as words
        Set<Integer> years = new TreeSet<>();
        Integer part = null;
        StringBuilder kept = new StringBuilder();
        Matcher bracket = BRACKET.matcher(s);
        int last = 0;
        while (bracket.find()) {
            kept.append(s, last, bracket.start()).append(' ');
            String inner = bracket.group(1).trim();
            Integer bracketPart = audio ? bracketPart(inner) : null;
            if (YEAR.matcher(inner).matches()) {
                years.add(Integer.parseInt(inner));
            } else if (bracketPart != null) {
                part = bracketPart;
            } else if (!inner.isEmpty() && !RELEASE_TAG.matcher(inner).matches() && !isDescriptorGroup(words(inner), comic)) {
                kept.append(inner).append(' ');
            }
            last = bracket.end();
        }
        kept.append(s.substring(last));
        s = collapse(kept.toString());

        if (audio) {
            Matcher whole = PART_CONTENT.matcher(s);
            Matcher trailingPart = TRAILING_PART.matcher(s);
            if (whole.matches() && partNumber(whole) != null) {
                // "CD1", "Disc 2": the folders of one audiobook's discs, named after the book's folder when read
                part = partNumber(whole);
                s = "";
            } else if (trailingPart.find() && partNumber(trailingPart) != null) {
                part = partNumber(trailingPart);
                s = s.substring(0, trailingPart.start()).trim();
            }
        }
        return new Prepared(s, years, part, copy, comic);
    }

    /**
     * The parts with the last one cut at its last " by ", when what follows could be a person's name and what is left
     * still has a title word: "Death by Chocolate by Sarah Graves", "Battle Born by Dale Brown - Unabridged". Null
     * when there is no such " by ".
     */
    private static List<String> beforeBy(List<String> segments) {
        if (segments.isEmpty()) {
            return null;
        }
        String[] by = splitAtLastBy(segments.getLast());
        if (by == null || !looksLikeName(by[1])) {
            return null;
        }
        List<String> rest = new ArrayList<>(segments.subList(0, segments.size() - 1));
        rest.add(by[0]);
        return hasTitleWord(String.join(" ", rest)) ? rest : null;
    }

    /** The name after the last part's last " by ", for a name {@link #beforeBy} accepted. */
    private static String byName(List<String> segments) {
        return splitAtLastBy(segments.getLast())[1];
    }

    private static String[] splitAtLastBy(String text) {
        Matcher by = BY.matcher(text);
        int start = -1;
        int end = -1;
        while (by.find()) {
            start = by.start();
            end = by.end();
        }
        return start > 0 ? new String[]{text.substring(0, start).trim(), text.substring(end).trim()} : null;
    }

    /** The " - " or ": " separated parts, without a part made only of descriptors (" - Unabridged"). */
    private static List<String> segments(String text, boolean comic) {
        List<String> segments = new ArrayList<>(Arrays.stream(SEGMENT_SEPARATOR.split(text)).map(String::trim)
                .filter(segment -> !segment.isEmpty()).toList());
        if (segments.size() > 1) {
            List<String> withoutDescriptors = segments.stream().filter(segment -> !isDescriptorGroup(words(segment), comic)).toList();
            if (!withoutDescriptors.isEmpty()) {
                segments = new ArrayList<>(withoutDescriptors);
            }
        }
        return segments;
    }

    /**
     * Which of two or more " - " separated parts is an author, or -1 for none. Only the first or the last part can be,
     * only one is dropped, and what is left must still have a title word ("The Expanse - Book One" keeps both).
     * <ul>
     *   <li>The last part when the context confirms it or it is written with initials ("The Hobbit - J.R.R. Tolkien").
     *       A last part naming the file's own folder is dropped too: in a series folder "Las Vegas Bad Boys", "KING -
     *       Las Vegas Bad Boys" is the book "King".</li>
     *   <li>The first part when the context confirms it, unless it names the file's own folder: in a title folder
     *       "American Gods", "American Gods - Neil Gaiman" is American Gods, not a book called "Neil Gaiman". Written
     *       as "Gaiman, Neil" or with initials it is a name all the same.</li>
     *   <li>When both are confirmed ("Neil Gaiman - American Gods" in "Neil Gaiman/American Gods"), the one naming the
     *       file's own folder is the title; when neither does, nothing is dropped.</li>
     * </ul>
     */
    private static int authorSegment(List<String> segments, NameContext ctx) {
        int lastIndex = segments.size() - 1;
        String first = segments.getFirst();
        String last = segments.get(lastIndex);
        boolean lastIsName = looksLikeName(last) && (ctx.confirms(last) || hasInitials(last))
                && hasTitleWord(joinWithout(segments, lastIndex));
        boolean firstIsName = looksLikeName(first) && ctx.confirms(first) && hasTitleWord(joinWithout(segments, 0));
        if (lastIsName && firstIsName) {
            if (ctx.isOwnFolder(last)) {
                return 0;
            }
            return ctx.isOwnFolder(first) ? lastIndex : -1;
        }
        if (lastIsName) {
            return lastIndex;
        }
        if (firstIsName && (!ctx.isOwnFolder(first) || hasInitials(first) || first.contains(","))) {
            return 0;
        }
        return -1;
    }

    private static String joinWithout(List<String> segments, int index) {
        List<String> rest = new ArrayList<>(segments);
        rest.remove(index);
        return String.join(" ", rest);
    }

    /**
     * Up to six words, each capitalised, an initial or a name particle ("de", "van"), and no number in any form: "The
     * Dark Tower II" in a folder "The Dark Tower", or "Book One", names a volume, and dropping it would lose it.
     */
    private static boolean looksLikeName(String segment) {
        if (namesANumber(segment)) {
            return false;
        }
        String spaced = GLUED_INITIALS.matcher(segment).replaceAll(" ").replace(',', ' ').trim();
        if (spaced.isEmpty()) {
            return false;
        }
        String[] nameWords = WHITESPACE.split(spaced);
        if (nameWords.length > 6) {
            return false;
        }
        boolean hasSurname = false;
        for (String word : nameWords) {
            if (INITIALS.matcher(word).matches() || NAME_PARTICLES.contains(word)) {
                continue;
            }
            if (!NAME_WORD.matcher(word).matches()) {
                return false;
            }
            hasSurname |= word.length() >= 2;
        }
        return hasSurname;
    }

    private static boolean namesANumber(String segment) {
        if (segment.chars().anyMatch(Character::isDigit)) {
            return true;
        }
        List<String> tokens = words(segment);
        for (int i = 0; i < tokens.size(); i++) {
            if (TitleWords.number(tokens.get(i)) != null) {
                return true;
            }
            if (TitleWords.SERIES_KEYWORDS.contains(tokens.get(i)) && i + 1 < tokens.size()
                    && TitleWords.numberAfter(tokens.get(i), tokens.get(i + 1)) != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * A name written with initials and a surname ("J.R.R. Tolkien", "Edward S. Aarons"). Only an initial with its
     * period counts: "Rome - A History" has a capital "A" but no initial.
     */
    private static boolean hasInitials(String segment) {
        if (!looksLikeName(segment) || PersonName.parse(segment) == null) {
            return false;
        }
        String spaced = GLUED_INITIALS.matcher(segment).replaceAll(" ").replace(',', ' ').trim();
        return Arrays.stream(WHITESPACE.split(spaced)).anyMatch(word -> INITIALS.matcher(word).matches());
    }

    /** False when what is left is only a series label ("Book One", "Vol. 2"), which is no title on its own. */
    private static boolean hasTitleWord(String text) {
        List<String> tokens = words(text);
        if (tokens.isEmpty()) {
            return false;
        }
        boolean keyword = false;
        for (String token : tokens) {
            if (TitleWords.SERIES_KEYWORDS.contains(token)) {
                keyword = true;
            } else if (!TitleWords.ARTICLES.contains(token) && !TitleWords.isNumberWord(token) && TitleWords.number(token) == null) {
                return true;
            }
        }
        return !keyword;
    }

    /** "Hobbit, The" reads "The Hobbit". */
    private static String articleToFront(String segment) {
        Matcher article = ARTICLE_SUFFIX.matcher(segment.trim());
        return article.matches() ? article.group(2) + " " + article.group(1) : segment;
    }

    /** A bracket group or " - " part made only of descriptor words, one of them an anchor ("(Illustrated Edition)"). */
    private static boolean isDescriptorGroup(List<String> tokens, boolean comic) {
        return !tokens.isEmpty() && tokens.stream().allMatch(word -> TitleWords.isDescriptor(word, comic))
                && tokens.stream().anyMatch(TitleWords::isAnchor);
    }

    /**
     * Drops a run of descriptor words at the end ("Neuromancer 20th Anniversary Edition", "The Hobbit Unabridged") and
     * one at the start ("Audiobook The Hobbit"), always leaving a word. A trailing run needs an anchor and either two
     * descriptor words or an anchor that is never a title word; a leading run needs an anchor such as "Audiobook",
     * since "The Illustrated Man" and "Digital Fortress" are titles.
     */
    private static List<String> withoutDescriptorRuns(List<String> tokens, boolean comic) {
        int cut = tokens.size();
        while (cut > 1 && TitleWords.isDescriptor(tokens.get(cut - 1), comic)) {
            cut--;
        }
        List<String> trailing = tokens.subList(cut, tokens.size());
        if (!trailing.isEmpty() && trailing.stream().anyMatch(TitleWords::isAnchor)
                && (trailing.stream().anyMatch(TitleWords::isStandaloneAnchor) || descriptorWordCount(trailing) >= 2)) {
            tokens = tokens.subList(0, cut);
        }
        int start = 0;
        while (start < tokens.size() - 1 && TitleWords.isDescriptor(tokens.get(start), comic)) {
            start++;
        }
        if (start > 0 && tokens.subList(0, start).stream().anyMatch(TitleWords::isLeadingAnchor)) {
            tokens = tokens.subList(start, tokens.size());
        }
        return tokens;
    }

    /** Descriptor words of a run other than the "the", "a" and "and" that join them. */
    private static long descriptorWordCount(List<String> run) {
        return run.stream().filter(word -> !TitleWords.ARTICLES.contains(word) && !word.equals("and")).count();
    }

    /**
     * Lower case words: "&" read as "and", apostrophes removed ("Sorcerer's" is "sorcerers"), "#" kept as a word of
     * its own, and every other run of punctuation a word break.
     */
    static List<String> words(String text) {
        String n = foldAccents(text).toLowerCase(Locale.ROOT).replace("&", " and ").replace("#", " # ");
        n = NON_WORD.matcher(APOSTROPHES.matcher(n).replaceAll("")).replaceAll(" ").trim();
        return n.isEmpty() ? List.of() : List.of(WHITESPACE.split(n));
    }

    /** The words of a text joined by single spaces, without a leading article: how a folder name is compared. */
    static String normalize(String text) {
        List<String> tokens = words(text == null ? "" : text);
        if (tokens.size() > 1 && TitleWords.ARTICLES.contains(tokens.getFirst())) {
            tokens = tokens.subList(1, tokens.size());
        }
        return String.join(" ", tokens);
    }

    private static String foldAccents(String text) {
        return MARKS.matcher(Normalizer.normalize(text, Normalizer.Form.NFKD)).replaceAll("");
    }

    private static String collapse(String text) {
        return WHITESPACE.matcher(text).replaceAll(" ").trim();
    }
}
