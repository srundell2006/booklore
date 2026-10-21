package org.booklore.util;

import org.booklore.model.dto.BookMetadata;
import org.booklore.model.entity.AuthorEntity;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.entity.BookMetadataEntity;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import java.text.BreakIterator;
import java.text.Normalizer;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@UtilityClass
@Slf4j
public class PathPatternResolver {

    private final int MAX_COMPONENT_BYTES = 200;
    private final int MAX_FILESYSTEM_COMPONENT_BYTES = 245; // Left 10 bytes buffer
    private final int MAX_AUTHOR_BYTES = 180;

    private final String TRUNCATION_SUFFIX = " et al.";
    private final int SUFFIX_BYTES = TRUNCATION_SUFFIX.getBytes(StandardCharsets.UTF_8).length;

    private final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");
    private final Pattern CONTROL_CHARACTER_PATTERN = Pattern.compile("\\p{Cntrl}");
    private final Pattern INVALID_CHARS_PATTERN = Pattern.compile("[\\\\/:*?\"<>|]");
    // A capture rather than a lookbehind, which in Java misses a Latin letter outside the BMP (U+10780, U+1DF00)
    private final Pattern LATIN_ACCENT_PATTERN = Pattern.compile("(\\p{IsLatin})\\p{M}+");
    private final Pattern ASCII_LETTERS_AND_DIGITS_PATTERN = Pattern.compile("[A-Za-z0-9]+");
    private final int ZERO_WIDTH_NON_JOINER = 0x200C;
    private final int ZERO_WIDTH_JOINER = 0x200D;
    // Punctuation that sits inside a word or a number, where a space would split it in two: apostrophes and the marks
    // typed in their place ("п’ять", "д’Артаньян", Hebrew ג׳ורג׳), the Armenian emphasis, exclamation, question and
    // abbreviation marks written over a vowel (Ինչո՞ւ), the Arabic thousands separator (١٬٠٠٠) and the spacing
    // dakuten and handakuten typed after kana (ト゛ラコ゛ン). They are dropped like other punctuation but never become a
    // space. The Arabic decimal separator is not one of them: dropped, it would make another number of ٢٠٫٥ (٢٠٥).
    private final String IN_WORD_PUNCTUATION = "\u2018\u2019\u201B\u2032\u00B4\u055A\u05F3\u05F4\uFF07"
            + "\u055B\u055C\u055E\u055F\u066C\u309B\u309C";
    // The filesystem-illegal characters of INVALID_CHARS_PATTERN, for a test of one code point
    private final String INVALID_CHARS = "\\/:*?\"<>|";
    private final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{(.*?)}");
    private final Pattern MODIFIER_PLACEHOLDER_PATTERN = Pattern.compile("\\{([^}:]+)(?::([^}]+))?}");
    private final Pattern COMMA_SPACE_PATTERN = Pattern.compile(", ");
    private final Pattern SLASH_PATTERN = Pattern.compile("/");

    public String resolvePattern(BookEntity book, String pattern) {
        BookFileEntity primaryFile = book.getPrimaryBookFile();
        return resolvePattern(book, primaryFile, pattern, primaryFile != null && primaryFile.isFolderBased());
    }

    public String resolvePattern(BookEntity book, BookFileEntity bookFile, String pattern) {
        return resolvePattern(book, bookFile, pattern, bookFile != null && bookFile.isFolderBased());
    }

    public String resolvePattern(BookEntity book, BookFileEntity bookFile, String pattern, boolean folderBased) {
        String currentFilename = bookFile != null && bookFile.getFileName() != null ? bookFile.getFileName().trim() : "";
        return resolvePattern(book.getMetadata(), pattern, currentFilename, folderBased);
    }

    public String resolvePattern(BookMetadata metadata, String pattern, String filename) {
        MetadataProvider metadataProvider = MetadataProvider.from(metadata);
        return resolvePattern(metadataProvider, pattern, filename, false);
    }

    public String resolvePattern(BookMetadataEntity metadata, String pattern, String filename) {
        return resolvePattern(metadata, pattern, filename, false);
    }

    public String resolvePattern(BookMetadataEntity metadata, String pattern, String filename, boolean folderBased) {
        MetadataProvider metadataProvider = MetadataProvider.from(metadata);
        return resolvePattern(metadataProvider, pattern, filename, folderBased);
    }

    private String resolvePattern(MetadataProvider metadata, String pattern, String filename, boolean folderBased) {
        if (pattern == null || pattern.isBlank()) {
            return filename;
        }

        String filenameBase = "Untitled";
        if (filename != null && !filename.isBlank()) {
            if (folderBased) {
                // For folder-based items, don't strip extension from folder name
                filenameBase = filename;
            } else {
                int lastDot = filename.lastIndexOf('.');
                if (lastDot > 0) {
                    filenameBase = filename.substring(0, lastDot);
                } else {
                    filenameBase = filename;
                }
            }
        }

        String title = sanitize(metadata != null && metadata.getTitle() != null
                ? metadata.getTitle()
                : filenameBase);

        String subtitle = sanitize(metadata != null ? metadata.getSubtitle() : "");

        String authors = sanitize(
                metadata != null
                        ? truncateAuthorsForFilesystem(String.join(", ", metadata.getAuthors()))
                        : ""
        );
        String year = sanitize(
                metadata != null && metadata.getPublishedDate() != null
                        ? String.valueOf(metadata.getPublishedDate().getYear())
                        : ""
        );
        String series = sanitize(metadata != null ? metadata.getSeriesName() : "");
        String seriesIndex = "";
        if (metadata != null && metadata.getSeriesNumber() != null) {
            Float seriesNumber = metadata.getSeriesNumber();
            if (seriesNumber % 1 == 0) {
                // Whole number - format with leading zero for 1-9
                seriesIndex = String.format("%02d", seriesNumber.intValue());
            } else {
                // Decimal number - format integer part with leading zero
                int intPart = seriesNumber.intValue();
                String formatted = seriesNumber.toString();
                String decimalPart = formatted.substring(formatted.indexOf('.'));
                seriesIndex = String.format("%02d", intPart) + decimalPart;
            }
            seriesIndex = sanitize(seriesIndex);
        }
        String language = sanitize(metadata != null ? metadata.getLanguage() : "");
        String publisher = sanitize(metadata != null ? metadata.getPublisher() : "");
        String isbn = sanitize(
                metadata != null
                        ? (metadata.getIsbn13() != null
                        ? metadata.getIsbn13()
                        : metadata.getIsbn10() != null
                        ? metadata.getIsbn10()
                        : "")
                        : ""
        );

        Map<String, String> values = new LinkedHashMap<>();
        values.put("authors", authors);
        values.put("title", truncatePathComponent(title, MAX_COMPONENT_BYTES));
        values.put("subtitle", truncatePathComponent(subtitle, MAX_COMPONENT_BYTES));
        values.put("year", year);
        values.put("series", truncatePathComponent(series, MAX_COMPONENT_BYTES));
        values.put("seriesIndex", seriesIndex);
        values.put("language", language);
        values.put("publisher", truncatePathComponent(publisher, MAX_COMPONENT_BYTES));
        values.put("isbn", isbn);
        values.put("currentFilename", filename);

        return resolvePatternWithValues(pattern, values, filename, folderBased);
    }

    private String resolvePatternWithValues(String pattern, Map<String, String> values, String currentFilename, boolean folderBased) {
        String extension = "";
        if (!folderBased) {
            // Only extract extension for regular files, not for folder-based items
            int lastDot = currentFilename.lastIndexOf('.');
            if (lastDot >= 0 && lastDot < currentFilename.length() - 1) {
                extension = sanitize(currentFilename.substring(lastDot + 1));  // e.g. "epub"
            }
        }

        values.put("extension", extension);

        // Handle optional blocks enclosed in <...>, supporting else clause via pipe: <left|right>
        Pattern optionalBlockPattern = Pattern.compile("<([^<>]*)>");
        Matcher matcher = optionalBlockPattern.matcher(pattern);
        StringBuilder resolved = new StringBuilder(1024);

        while (matcher.find()) {
            String blockContent = matcher.group(1);

            // Split on first unescaped pipe for else clause
            int pipeIndex = blockContent.indexOf('|');
            String primaryBlock = pipeIndex >= 0 ? blockContent.substring(0, pipeIndex) : blockContent;
            String fallbackBlock = pipeIndex >= 0 ? blockContent.substring(pipeIndex + 1) : null;

            boolean allHaveValues = checkAllPlaceholdersHaveValues(primaryBlock, values);

            if (allHaveValues) {
                String resolvedBlock = resolveBlockPlaceholders(primaryBlock, values);
                matcher.appendReplacement(resolved, Matcher.quoteReplacement(resolvedBlock));
            } else if (fallbackBlock != null) {
                String resolvedFallback = resolveBlockPlaceholders(fallbackBlock, values);
                matcher.appendReplacement(resolved, Matcher.quoteReplacement(resolvedFallback));
            } else {
                matcher.appendReplacement(resolved, "");
            }
        }
        matcher.appendTail(resolved);

        String result = resolved.toString();

        // Replace known placeholders (with optional modifiers) with values, preserve unknown ones
        Matcher placeholderMatcher = MODIFIER_PLACEHOLDER_PATTERN.matcher(result);
        StringBuilder finalResult = new StringBuilder(1024);

        while (placeholderMatcher.find()) {
            String fieldName = placeholderMatcher.group(1);
            String modifier = placeholderMatcher.group(2);
            if (values.containsKey(fieldName)) {
                String replacement = values.get(fieldName);
                if (modifier != null && !modifier.isEmpty()) {
                    replacement = applyModifier(replacement, modifier, fieldName);
                }
                placeholderMatcher.appendReplacement(finalResult, Matcher.quoteReplacement(replacement));
            } else {
                // Preserve unknown placeholders (e.g., {foo})
                placeholderMatcher.appendReplacement(finalResult, Matcher.quoteReplacement(placeholderMatcher.group()));
            }
        }
        placeholderMatcher.appendTail(finalResult);

        result = finalResult.toString();

        boolean usedFallbackFilename = false;
        if (result.isBlank()) {
            result = values.getOrDefault("currentFilename", "untitled");
            usedFallbackFilename = true;
        }

        boolean patternIncludesExtension = pattern.contains("{extension}");
        boolean patternIncludesFullFilename = pattern.contains("{currentFilename}");

        // Don't auto-append extension for folder-based items
        if (!folderBased && !usedFallbackFilename && !patternIncludesExtension && !patternIncludesFullFilename && !extension.isBlank()) {
            result += "." + extension;
        }

        return validateFinalPath(result, folderBased);
    }

    private boolean checkAllPlaceholdersHaveValues(String block, Map<String, String> values) {
        Matcher placeholderMatcher = MODIFIER_PLACEHOLDER_PATTERN.matcher(block);
        while (placeholderMatcher.find()) {
            String fieldName = placeholderMatcher.group(1);
            String value = values.getOrDefault(fieldName, "");
            if (value.isBlank()) {
                return false;
            }
        }
        return true;
    }

    private String resolveBlockPlaceholders(String block, Map<String, String> values) {
        Matcher m = MODIFIER_PLACEHOLDER_PATTERN.matcher(block);
        StringBuilder sb = new StringBuilder(256);
        while (m.find()) {
            String fieldName = m.group(1);
            String modifier = m.group(2);
            String replacement = values.getOrDefault(fieldName, "");
            if (modifier != null && !modifier.isEmpty()) {
                replacement = applyModifier(replacement, modifier, fieldName);
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String applyModifier(String value, String modifier, String fieldName) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        return switch (modifier.toLowerCase(Locale.ROOT)) {
            case "first" -> {
                String[] parts = COMMA_SPACE_PATTERN.split(value);
                yield parts[0].trim();
            }
            case "sort" -> {
                String firstItem = COMMA_SPACE_PATTERN.split(value)[0].trim();
                int lastSpace = firstItem.lastIndexOf(' ');
                if (lastSpace > 0) {
                    yield firstItem.substring(lastSpace + 1) + ", " + firstItem.substring(0, lastSpace);
                }
                yield firstItem;
            }
            case "initial", "lastinitial" -> {
                String target = value;
                if ("authors".equals(fieldName)) {
                    // For authors, use the first letter of the last name of the first author
                    String firstAuthor = COMMA_SPACE_PATTERN.split(value)[0].trim();
                    int lastSpace = firstAuthor.lastIndexOf(' ');
                    target = lastSpace > 0 ? firstAuthor.substring(lastSpace + 1) : firstAuthor;
                }
                yield initialOf(target);
            }
            case "firstinitial" -> {
                String target = value;
                if ("authors".equals(fieldName)) {
                    // For authors, use the first letter of the given name of the first author
                    String firstAuthor = COMMA_SPACE_PATTERN.split(value)[0].trim();
                    int firstSpace = firstAuthor.indexOf(' ');
                    target = firstSpace > 0 ? firstAuthor.substring(0, firstSpace) : firstAuthor;
                }
                yield initialOf(target);
            }
            case "upper" -> value.toUpperCase();
            case "lower" -> value.toLowerCase();
            default -> value;
        };
    }

    /**
     * First alphanumeric character of {@code value}, uppercased.  Falls back to
     * "#" when the value has no alphanumeric characters, so initials never
     * produce an empty or punctuation-only path component.
     */
    private String initialOf(String value) {
        if (value == null) {
            return "#";
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                return String.valueOf(Character.toUpperCase(c));
            }
        }
        return "#";
    }

    /**
     * Sanitize a string for use as a filesystem path component.
     * <p>
     * Steps:
     * 1. NFD-decompose accented characters so combining marks become separate code points
     *    (e.g. é → e + ́, ñ → n + ̃).
     * 2. Write each letter that is only another form of ASCII letters as those (its NFKC form):
     *    ligatures (ﬁ → fi), fullwidth and mathematical letters (Ａ → A, 𝐁𝐨𝐥𝐝 → Bold) and circled and
     *    squared letters (Ⓐ 🄰 → A). The numbers that are another form of ASCII ones (１ 𝟏 Ⅷ) are
     *    written in ASCII by step 5. Superscript and subscript letters, the ordinal indicators among
     *    them (ª º ᵉ ⁿ), are left for step 5 to drop like the superscript digits (² ³), as they were
     *    when every non-ASCII code point was dropped: "Nº 5" and "451º Fahrenheit" keep their names.
     *    Other numbers (½ ①), symbols that spell letters (™ №) and the letters that are emoji (ℹ Ⓜ)
     *    are dropped by step 5 too, as they always were.
     * 3. Strip the marks that sit on Latin letters, leaving the base letter.
     * 4. Turn every Unicode space separator (no-break, ideographic, thin spaces) into a space.
     * 5. Keep every ASCII character, and of the rest only what names are spelt with: letters,
     *    decimal digits, the letter numbers of other scripts (the 〇 of 二〇〇八), the combining marks
     *    on those letters (Japanese が, Devanagari vowel signs and Cyrillic й are a letter plus a mark,
     *    not an accent), and the zero-width (non-)joiners between non-Latin letters that Persian and
     *    Indic text need. A digit that is only another form of an ASCII digit (fullwidth １,
     *    mathematical 𝟏) is written as that digit, and a Roman numeral as its ASCII letters (Ⅷ → VIII,
     *    so that ドラゴンクエストⅡ and Ⅲ keep apart). A modifier letter that belongs to no script
     *    (ー ـ ʼ ˈ) is kept only where it follows a non-Latin letter or digit, as in コーヒー, and is
     *    dropped next to Latin text (Winterʼs → Winters). Punctuation (’ – « »), symbols and emoji,
     *    control and format characters (bidi marks, zero-width space, soft hyphen), lone surrogates
     *    and private-use or unassigned code points are dropped.
     *    Punctuation or a symbol whose nearest kept neighbours on both sides are non-Latin letters or
     *    digits becomes a space instead, so that it keeps the words apart (三体·黑暗森林 → 三体 黑暗森林).
     *    ASCII neighbours do not count, but a fullwidth digit does, though it is written in ASCII
     *    (第１・２巻 → 第1 2巻), and so does a Roman numeral, though it is Latin: dropped, the
     *    punctuation would run two numerals into another one (ドラゴンクエストⅠ・Ⅱ → ドラゴンクエストI II,
     *    not the III of ドラゴンクエストⅢ; Episode Ⅰ–Ⅲ → Episode I III). What steps 6 and 7 remove is
     *    looked through (なぜ?・どうして → なぜ どうして). Punctuation that sits inside a word or a number
     *    is dropped all the same: apostrophes (п’ять → пять), the Armenian question mark over a vowel
     *    (Ինչո՞ւ → Ինչու), the Arabic thousands separator (١٬٠٠٠ → ١٠٠٠) and the spacing dakuten (ト゛ → ト).
     *    Apart from the spaces of step 4, from letters with no accent to strip (ø, ł, æ, ß), which are
     *    now kept, and from the letters and numbers written in ASCII, Latin text so comes out as it did
     *    when every non-ASCII code point was dropped.
     * 6. Remove filesystem-illegal characters (\ / : * ? " < > |).
     * 7. Remove control characters.
     * 8. NFC-recompose.
     * 9. Collapse runs of whitespace to a single space and strip.
     * <p>
     * Sanitizing an already sanitized string returns it unchanged. The move preview in the UI
     * (pattern-resolver.ts) mirrors this method exactly.
     */
    private String sanitize(String input) {
        if (input == null) return "";
        String decomposed = writeCompatibilityFormsInAscii(Normalizer.normalize(input, Normalizer.Form.NFD));
        // Strip accents from Latin letters: drop the combining marks that follow them
        String unaccented = LATIN_ACCENT_PATTERN.matcher(decomposed).replaceAll("$1");
        String legal = CONTROL_CHARACTER_PATTERN.matcher(
                INVALID_CHARS_PATTERN.matcher(keepNameCharacters(unaccented)).replaceAll("")
        ).replaceAll("");
        return WHITESPACE_PATTERN.matcher(Normalizer.normalize(legal, Normalizer.Form.NFC)).replaceAll(" ").strip();
    }

    /**
     * Step 2 of {@link #sanitize}. It runs before accents are stripped, so that a mark on a
     * mathematical letter (𝐀 + ́) is stripped from the A it becomes. Digits and Roman numerals are
     * written in ASCII later, by {@link #keepNameCharacters} (see {@link #asciiNumberOf}).
     */
    private String writeCompatibilityFormsInAscii(String decomposed) {
        StringBuilder written = new StringBuilder(decomposed.length());
        decomposed.codePoints().forEach(codePoint -> {
            String ascii = asciiFormOf(codePoint);
            if (ascii != null) {
                written.append(ascii);
            } else {
                written.appendCodePoint(codePoint);
            }
        });
        return written.toString();
    }

    /**
     * The ASCII letters a non-ASCII letter or circled letter stands for, or null when it is more than a
     * form of them, or a superscript or subscript letter.
     */
    private String asciiFormOf(int codePoint) {
        if (codePoint < 0x80 || Character.isExtendedPictographic(codePoint) || isSuperscriptOrSubscriptLetter(codePoint)) {
            return null;
        }
        boolean letter = Character.isLetter(codePoint)
                // Circled and squared Latin letters (Ⓐ 🄰) are symbols, but alphabetic ones
                || (Character.getType(codePoint) == Character.OTHER_SYMBOL && Character.isAlphabetic(codePoint));
        return letter ? asciiCompatibilityForm(codePoint) : null;
    }

    /**
     * The ASCII a non-ASCII number is only another form of, or null: the digit of a fullwidth or mathematical
     * digit (１ 𝟏 → 1) and the letters of a Roman numeral (Ⅷ → VIII, ⅻ → xii). Unlike letters, these are
     * written in ASCII only once {@link #punctuationBetweenNonLatinWords} has seen them as the numbers they
     * are, so that 第１・２巻 keeps its numbers apart (第1 2巻) the way 三体·黑暗 keeps its words, and
     * ドラゴンクエストⅠ・Ⅱ does not become the III of ドラゴンクエストⅢ (ドラゴンクエストI II).
     */
    private String asciiNumberOf(int codePoint) {
        if (codePoint < 0x80) {
            return null;
        }
        int type = Character.getType(codePoint);
        if (type == Character.DECIMAL_DIGIT_NUMBER) {
            String compatible = asciiCompatibilityForm(codePoint);
            return compatible != null && compatible.length() == 1 ? compatible : null;
        }
        // Only the Roman numerals are letter numbers whose NFKC form is ASCII
        return type == Character.LETTER_NUMBER ? asciiCompatibilityForm(codePoint) : null;
    }

    /** The NFKC form of {@code codePoint} when it is ASCII letters and digits only, or null. */
    private String asciiCompatibilityForm(int codePoint) {
        String compatible = Normalizer.normalize(Character.toString(codePoint), Normalizer.Form.NFKC);
        return ASCII_LETTERS_AND_DIGITS_PATTERN.matcher(compatible).matches() ? compatible : null;
    }

    /**
     * ª º ᵉ ⁿ ₐ: the letters that are a superscript or subscript form of ASCII letters. They are the Latin
     * modifier and other letters (Lm, Lo) whose NFKC form is ASCII, which only the ordinal indicators ª º
     * and the superscript and subscript letters have. Like the superscript digits they are dropped, as when
     * every non-ASCII code point was, rather than written in ASCII, which would rename books whose names were
     * fine ("Nº 5" to "No 5") and spell "451º Fahrenheit", whose º stands for a degree sign, "451o".
     */
    private boolean isSuperscriptOrSubscriptLetter(int codePoint) {
        int type = Character.getType(codePoint);
        return (type == Character.MODIFIER_LETTER || type == Character.OTHER_LETTER)
                && Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.LATIN
                && asciiCompatibilityForm(codePoint) != null;
    }

    /**
     * Steps 4 and 5 of {@link #sanitize}, on decomposed text. A combining mark belongs to the
     * code point before it, so it is kept only along with a letter (or the mark or joiner that
     * follows one), never moved onto whatever came before a dropped emoji or symbol. The digits
     * and Roman numerals that are another form of ASCII ones are written in ASCII here.
     */
    private String keepNameCharacters(String decomposed) {
        int[] codePoints = decomposed.codePoints().toArray();
        boolean[] keptModifiers = modifierLettersOfNonLatinWords(codePoints);
        boolean[] spaced = punctuationBetweenNonLatinWords(codePoints, keptModifiers);
        StringBuilder kept = new StringBuilder(decomposed.length());
        boolean previousTakesMarks = false;
        for (int i = 0; i < codePoints.length; i++) {
            int codePoint = codePoints[i];
            boolean keep;
            if (spaced[i]) {
                codePoint = ' ';
                keep = true;
            } else if (isCommonModifierLetter(codePoint)) {
                keep = keptModifiers[i];
            } else if (isKeptOnItsOwn(codePoint)) {
                String asciiNumber = asciiNumberOf(codePoint);
                if (asciiNumber != null) {
                    // All but the last of its ASCII characters (Ⅻ → XII), which is appended below
                    kept.append(asciiNumber, 0, asciiNumber.length() - 1);
                    codePoint = asciiNumber.charAt(asciiNumber.length() - 1);
                } else if (Character.isSpaceChar(codePoint)) {
                    codePoint = ' ';
                }
                keep = true;
            } else if (isMark(codePoint)) {
                keep = previousTakesMarks;
            } else if (isJoiner(codePoint)) {
                keep = joinsNonLatinLetters(kept, codePoints, i, keptModifiers, spaced);
            } else {
                keep = false;
            }
            if (keep) {
                kept.appendCodePoint(codePoint);
            }
            previousTakesMarks = keep && (Character.isLetter(codePoint) || isMark(codePoint) || isJoiner(codePoint));
        }
        return kept.toString();
    }

    /**
     * Which of {@code codePoints} are modifier letters of the Common script (ー ـ ʼ) kept as part
     * of a non-Latin word: those that follow a non-Latin letter or digit with nothing but marks,
     * joiners and other such modifier letters between (コーヒー, ｶﾞｰﾙ, Ukrainian мʼята). Anywhere
     * else, next to Latin letters, ASCII, punctuation or a space, they are dropped as they always were.
     */
    private boolean[] modifierLettersOfNonLatinWords(int[] codePoints) {
        boolean[] kept = new boolean[codePoints.length];
        boolean inNonLatinWord = false;
        for (int i = 0; i < codePoints.length; i++) {
            int codePoint = codePoints[i];
            if (isCommonModifierLetter(codePoint)) {
                kept[i] = inNonLatinWord;
            } else if (!isMark(codePoint) && !isJoiner(codePoint)) {
                // A fullwidth digit or Roman numeral is written in ASCII, after which a modifier letter would not be kept
                // the next time
                inNonLatinWord = isNonLatinLetterOrNumber(codePoint) && asciiNumberOf(codePoint) == null;
            }
        }
        return kept;
    }

    /**
     * Which of {@code codePoints} are non-ASCII punctuation or symbols that become a space: those
     * whose nearest kept neighbours on both sides are non-Latin letters or digits (or the marks and
     * modifier letters kept with them), so that "三体·黑暗森林" and "東京・大阪" keep their words apart.
     * Marks, joiners, other punctuation, whatever is dropped and the ASCII that steps 6 and 7 of
     * {@link #sanitize} remove (なぜ?・どうして) are looked through. An ASCII character that stays (a
     * space, a digit, a Latin letter) or a non-ASCII Latin letter on either side leaves it dropped as
     * it always was, so Latin names do not change. A fullwidth or mathematical digit counts as the
     * non-Latin digit it is, though it is then written in ASCII (第１・２巻 → 第1 2巻), and so does a
     * Roman numeral, though it is Latin (ドラゴンクエストⅠ・Ⅱ → ドラゴンクエストI II, Episode Ⅰ–Ⅲ →
     * Episode I III): written in ASCII, two numerals the punctuation no longer kept apart would run into
     * another one (III, the name of ドラゴンクエストⅢ). Punctuation that sits inside a word or a number
     * ({@link #IN_WORD_PUNCTUATION}) is always dropped.
     */
    private boolean[] punctuationBetweenNonLatinWords(int[] codePoints, boolean[] keptModifiers) {
        int length = codePoints.length;
        boolean[] afterNonLatin = new boolean[length];
        boolean nonLatin = false;
        for (int i = 0; i < length; i++) {
            afterNonLatin[i] = nonLatin;
            if (staysInTheName(codePoints[i], keptModifiers[i])) {
                nonLatin = keptModifiers[i] || isNonLatinLetterOrNumber(codePoints[i]);
            }
        }
        boolean[] spaced = new boolean[length];
        nonLatin = false;
        for (int i = length - 1; i >= 0; i--) {
            spaced[i] = afterNonLatin[i] && nonLatin && separatesWords(codePoints[i]);
            if (staysInTheName(codePoints[i], keptModifiers[i])) {
                nonLatin = keptModifiers[i] || isNonLatinLetterOrNumber(codePoints[i]);
            }
        }
        return spaced;
    }

    /** Whether a code point is in the sanitized name: kept by step 5 and not removed by steps 6 and 7. */
    private boolean staysInTheName(int codePoint, boolean keptModifier) {
        return keptModifier || (isKeptOnItsOwn(codePoint) && !isRemovedLater(codePoint));
    }

    /** The filesystem-illegal characters and ASCII controls that steps 6 and 7 of {@link #sanitize} remove. */
    private boolean isRemovedLater(int codePoint) {
        return codePoint < 0x20 || codePoint == 0x7F || INVALID_CHARS.indexOf(codePoint) >= 0;
    }

    /** Non-ASCII punctuation, symbols and emoji, but for the punctuation that sits inside a word or a number. */
    private boolean separatesWords(int codePoint) {
        if (codePoint < 0x80 || IN_WORD_PUNCTUATION.indexOf(codePoint) >= 0) {
            return false;
        }
        return switch (Character.getType(codePoint)) {
            case Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION,
                 Character.END_PUNCTUATION, Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
                 Character.OTHER_PUNCTUATION, Character.MATH_SYMBOL, Character.CURRENCY_SYMBOL,
                 Character.MODIFIER_SYMBOL, Character.OTHER_SYMBOL -> true;
            // ℹ is a letter, but an emoji too
            default -> Character.isLetter(codePoint) && Character.isExtendedPictographic(codePoint);
        };
    }

    /**
     * Whether the zero-width (non-)joiner at {@code index} sits between non-Latin letters or
     * marks once dropped characters are gone. Persian and Indic text need it there; anywhere
     * else it is invisible noise that Latin names never had.
     */
    private boolean joinsNonLatinLetters(CharSequence kept, int[] codePoints, int index, boolean[] keptModifiers, boolean[] spaced) {
        if (kept.isEmpty()) {
            return false;
        }
        int previous = Character.codePointBefore(kept, kept.length());
        if (!isNonLatinLetter(previous) && !isMark(previous)) {
            return false;
        }
        boolean skippedDropped = false;
        for (int i = index + 1; i < codePoints.length; i++) {
            int next = codePoints[i];
            if (spaced[i]) {
                // Punctuation that becomes a space ends the word
                return false;
            } else if (isMark(next)) {
                if (!skippedDropped) {
                    // The mark sits on the joiner itself (Sinhala touching letters) and is kept with it
                    return true;
                }
                // Dropped along with the character it sits on
            } else if (isCommonModifierLetter(next)) {
                if (keptModifiers[i]) {
                    return true;
                }
                skippedDropped = true;
            } else if (!isKeptOnItsOwn(next) && !isJoiner(next)) {
                skippedDropped = true;
            } else {
                return isNonLatinLetter(next);
            }
        }
        return false;
    }

    /**
     * ASCII, letters, decimal digits, the letter numbers of scripts other than Latin (〇), the Roman
     * numerals that are ASCII letters (Ⅷ) and space separators: kept whatever surrounds them. Modifier
     * letters of the Common script depend on what they follow; the letters that are emoji (ℹ) are
     * dropped with the other emoji, and the superscript and subscript letters (ª º ᵉ) with the
     * superscript digits.
     */
    private boolean isKeptOnItsOwn(int codePoint) {
        if (codePoint < 0x80) {
            return true;
        }
        if (Character.isLetter(codePoint)) {
            return !isCommonModifierLetter(codePoint)
                    && !Character.isExtendedPictographic(codePoint)
                    && !isSuperscriptOrSubscriptLetter(codePoint);
        }
        int type = Character.getType(codePoint);
        return type == Character.DECIMAL_DIGIT_NUMBER
                || (type == Character.LETTER_NUMBER && (isOfANonLatinScript(codePoint) || asciiNumberOf(codePoint) != null))
                || Character.isSpaceChar(codePoint);
    }

    /** Of a script of its own other than Latin: not Latin, and not Common to several scripts. */
    private boolean isOfANonLatinScript(int codePoint) {
        Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
        return script != Character.UnicodeScript.LATIN && script != Character.UnicodeScript.COMMON;
    }

    private boolean isMark(int codePoint) {
        int type = Character.getType(codePoint);
        return type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK || type == Character.ENCLOSING_MARK;
    }

    private boolean isJoiner(int codePoint) {
        return codePoint == ZERO_WIDTH_NON_JOINER || codePoint == ZERO_WIDTH_JOINER;
    }

    /** ー ـ ʼ ˈ and the like: modifier letters used by several scripts, or by none. */
    private boolean isCommonModifierLetter(int codePoint) {
        return Character.getType(codePoint) == Character.MODIFIER_LETTER
                && Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.COMMON;
    }

    private boolean isNonLatinLetter(int codePoint) {
        return Character.isLetter(codePoint) && Character.UnicodeScript.of(codePoint) != Character.UnicodeScript.LATIN;
    }

    /**
     * A non-ASCII letter, decimal digit or letter number kept on its own that is not Latin: Arabic, CJK,
     * Cyrillic or Devanagari, say, and the fullwidth digits. The Roman numerals count too, though they are
     * Latin, as numbers that written in ASCII would run into the next (see {@link #punctuationBetweenNonLatinWords}).
     */
    private boolean isNonLatinLetterOrNumber(int codePoint) {
        return codePoint >= 0x80
                && isKeptOnItsOwn(codePoint)
                && !Character.isSpaceChar(codePoint)
                && (Character.UnicodeScript.of(codePoint) != Character.UnicodeScript.LATIN || asciiNumberOf(codePoint) != null);
    }

    private String truncateAuthorsForFilesystem(String authors) {
        if (authors == null || authors.isEmpty()) {
            return authors;
        }

        byte[] originalBytes = authors.getBytes(StandardCharsets.UTF_8);
        if (originalBytes.length <= MAX_AUTHOR_BYTES) {
            return authors;
        }

        String[] authorArray = COMMA_SPACE_PATTERN.split(authors);
        StringBuilder result = new StringBuilder(256);
        int currentBytes = 0;
        int truncationLimit = MAX_AUTHOR_BYTES - SUFFIX_BYTES;

        for (int i = 0; i < authorArray.length; i++) {
            String author = authorArray[i];

            int separatorBytes = (i > 0) ? 2 : 0;
            int authorBytes = author.getBytes(StandardCharsets.UTF_8).length;

            if (currentBytes + separatorBytes + authorBytes > MAX_AUTHOR_BYTES) {
                if (result.isEmpty()) {
                     return truncatePathComponent(author, truncationLimit) + TRUNCATION_SUFFIX;
                }
                return result + TRUNCATION_SUFFIX;
            }

            if (i > 0) {
                result.append(", ");
                currentBytes += 2;
            }
            result.append(author);
            currentBytes += authorBytes;
        }

        return result.toString();
    }

    /**
     * Cuts {@code component} to at most {@code maxBytes} of UTF-8. The cut falls between two
     * characters as a reader sees them (a letter and its vowel signs, accents or virama stay
     * together), and whitespace or a zero-width (non-)joiner left at the end of the cut is
     * dropped, so a cut name never ends in a space or in a joiner with nothing to join.
     */
    public String truncatePathComponent(String component, int maxBytes) {
        if (component == null || component.isEmpty()) {
            return component;
        }

        byte[] bytes = component.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) {
            return component;
        }

        CharsetEncoder encoder = StandardCharsets.UTF_8.newEncoder();
        ByteBuffer buffer = ByteBuffer.allocate(maxBytes);
        CharBuffer charBuffer = CharBuffer.wrap(component);

        encoder.encode(charBuffer, buffer, true);

        int end = charBuffer.position();
        BreakIterator characters = BreakIterator.getCharacterInstance(Locale.ROOT);
        characters.setText(component);
        int characterEnd = characters.isBoundary(end) ? end : characters.preceding(end);
        // A single character longer than the limit (a letter under a pile of marks) is cut where the bytes run out
        if (characterEnd > 0) {
            end = characterEnd;
        }
        while (end > 0) {
            int last = Character.codePointBefore(component, end);
            if (!Character.isWhitespace(last) && !Character.isSpaceChar(last) && !isJoiner(last)) {
                break;
            }
            end -= Character.charCount(last);
        }

        String truncated = component.substring(0, end);
        if (!truncated.equals(component)) {
            log.debug("Truncated path component from {} to {} bytes for filesystem safety",
                bytes.length, truncated.getBytes(StandardCharsets.UTF_8).length);
        }
        return truncated;
    }


    private String validateFinalPath(String path, boolean folderBased) {
        String[] components = SLASH_PATTERN.split(path);
        StringBuilder result = new StringBuilder(512);
        boolean first = true;

        for (int i = 0; i < components.length; i++) {
            String component = components[i];
            if (component == null || component.isEmpty()) {
                continue;
            }

            boolean isLastComponent = (i == components.length - 1);

            // For folder-based items, don't treat dots as extension separators
            if (isLastComponent && component.contains(".") && !folderBased) {
                component = truncateFilenameWithExtension(component);
            } else {
                if (component.getBytes(StandardCharsets.UTF_8).length > MAX_FILESYSTEM_COMPONENT_BYTES) {
                    component = truncatePathComponent(component, MAX_FILESYSTEM_COMPONENT_BYTES);
                }
                // Don't strip trailing dots from folder names for folder-based items
                if (!folderBased) {
                    while (component != null && !component.isEmpty() && component.endsWith(".")) {
                        component = component.substring(0, component.length() - 1);
                    }
                }
            }

            if (!first) result.append("/");
            result.append(component);
            first = false;
        }
        return result.toString();
    }

    public String truncateFilenameWithExtension(String filename) {
        int lastDotIndex = filename.lastIndexOf('.');
        if (lastDotIndex == -1 || lastDotIndex == 0) {
            // No extension or dot is at start (hidden file), treat as normal component
            if (filename.getBytes(StandardCharsets.UTF_8).length > MAX_FILESYSTEM_COMPONENT_BYTES) {
                return truncatePathComponent(filename, MAX_FILESYSTEM_COMPONENT_BYTES);
            }
            return filename;
        }

        String extension = filename.substring(lastDotIndex); // includes dot
        String name = filename.substring(0, lastDotIndex);

        int extBytes = extension.getBytes(StandardCharsets.UTF_8).length;

        if (extBytes > 50) {
            log.warn("Unusually long extension detected: {}", extension);
            if (filename.getBytes(StandardCharsets.UTF_8).length > MAX_FILESYSTEM_COMPONENT_BYTES) {
                 return truncatePathComponent(filename, MAX_FILESYSTEM_COMPONENT_BYTES);
            }
            return filename;
        }

        int maxNameBytes = MAX_FILESYSTEM_COMPONENT_BYTES - extBytes;

        if (name.getBytes(StandardCharsets.UTF_8).length > maxNameBytes) {
            String truncatedName = truncatePathComponent(name, maxNameBytes);
            return truncatedName + extension;
        }

        return filename;
    }

    private interface MetadataProvider {
        String getTitle();

        String getSubtitle();

        List<String> getAuthors();

        Integer getYear();

        String getSeriesName();

        Float getSeriesNumber();

        String getLanguage();

        String getPublisher();

        String getIsbn13();

        String getIsbn10();

        LocalDate getPublishedDate();

        static BookMetadataProvider from(BookMetadata metadata) {
            if (metadata == null) {
                return null;
            }

            return new BookMetadataProvider(metadata);
        }

        static BookMetadataEntityProvider from(BookMetadataEntity metadata) {
            if (metadata == null) {
                return null;
            }

            return new BookMetadataEntityProvider(metadata);
        }
    }

    private record BookMetadataProvider(BookMetadata metadata) implements MetadataProvider {

        @Override
        public String getTitle() {
            return metadata.getTitle();
        }

        @Override
        public String getSubtitle() {
            return metadata.getSubtitle();
        }

        @Override
        public List<String> getAuthors() {
            return metadata.getAuthors() != null ? metadata.getAuthors().stream().toList() : Collections.emptyList();
        }

        @Override
        public Integer getYear() {
            return metadata.getPublishedDate() != null ? metadata.getPublishedDate().getYear() : null;
        }

        @Override
        public String getSeriesName() {
            return metadata.getSeriesName();
        }

        @Override
        public Float getSeriesNumber() {
            return metadata.getSeriesNumber();
        }

        @Override
        public String getLanguage() {
            return metadata.getLanguage();
        }

        @Override
        public String getPublisher() {
            return metadata.getPublisher();
        }

        @Override
        public String getIsbn13() {
            return metadata.getIsbn13();
        }

        @Override
        public String getIsbn10() {
            return metadata.getIsbn10();
        }

        @Override
        public LocalDate getPublishedDate() {
            return metadata.getPublishedDate();
        }
    }

    private record BookMetadataEntityProvider(BookMetadataEntity metadata) implements MetadataProvider {

        @Override
        public String getTitle() {
            return metadata.getTitle();
        }

        @Override
        public String getSubtitle() {
            return metadata.getSubtitle();
        }

        @Override
        public List<String> getAuthors() {
            return metadata.getAuthors() != null
                    ? metadata.getAuthors()
                    .stream()
                    .map(AuthorEntity::getName)
                    .toList()
                    : Collections.emptyList();
        }

        @Override
        public Integer getYear() {
            return metadata.getPublishedDate() != null ? metadata.getPublishedDate().getYear() : null;
        }

        @Override
        public String getSeriesName() {
            return metadata.getSeriesName();
        }

        @Override
        public Float getSeriesNumber() {
            return metadata.getSeriesNumber();
        }

        @Override
        public String getLanguage() {
            return metadata.getLanguage();
        }

        @Override
        public String getPublisher() {
            return metadata.getPublisher();
        }

        @Override
        public String getIsbn13() {
            return metadata.getIsbn13();
        }

        @Override
        public String getIsbn10() {
            return metadata.getIsbn10();
        }

        @Override
        public LocalDate getPublishedDate() {
            return metadata.getPublishedDate();
        }
    }
}
