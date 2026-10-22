package org.booklore.service.library.matching;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a new file's path says about which book it is, read once per file: the ways its name reads as a title, the
 * names its folders could be, and its base name.
 *
 * @param folder         the file's sub path below its library path, "" at the root; for a folder audiobook, the folder
 *                       that holds it, as LibraryFile has it
 * @param readings       {@link TitleKey#readings}: first the reading the folders support, then each reading with a
 *                       part dropped that only might be an author
 * @param seriesReadings readings of the name without a leading series number ("01 - Title", "Book 2 - Title",
 *                       "[Discworld 04] Title"), each only good for a fileless book with that series number
 */
public record FileEvidence(Long libraryPathId,
                           String folder,
                           String fileName,
                           boolean audio,
                           NameContext context,
                           List<TitleKey> readings,
                           List<SeriesReading> seriesReadings) {

    /**
     * A reading of a name whose leading series number was taken off.
     *
     * @param key          how the rest of the name reads
     * @param confirmed    whether it is the reading the folders support, rather than one with an unconfirmed author
     *                     part dropped
     * @param seriesNumber the number taken off
     */
    public record SeriesReading(TitleKey key, boolean confirmed, int seriesNumber) {
    }

    /** "01 - Title", "1. Title". Up to three digits: "1984 - George Orwell" is a title. */
    private static final Pattern NUMBER_PREFIX = Pattern.compile("^(\\d{1,3})(?:\\s+[-–—]\\s+|\\.\\s+)(\\S.*)$");
    /** "Book 2 - Title", "Vol. 3: Title", "Book One - Title". */
    private static final Pattern KEYWORD_PREFIX = Pattern.compile(
            "^(book|bk|vol|volume)\\.?\\s*(\\d{1,3}|\\p{L}+)(?:\\s+[-–—]|\\s*[:.])\\s+(\\S.*)$", Pattern.CASE_INSENSITIVE);
    /** "[Discworld 04] Mort", "(Discworld #4) - Mort". */
    private static final Pattern BRACKET_PREFIX = Pattern.compile(
            "^[\\[(][^\\[\\]()]*\\p{L}[^\\[\\]()]*?\\s#?(\\d{1,3})\\s*[\\])]\\s*(?:[-–—]\\s+)?(\\S.*)$");

    public FileEvidence {
        readings = List.copyOf(readings);
        seriesReadings = List.copyOf(seriesReadings);
    }

    public static FileEvidence of(Long libraryPathId, String folder, String fileName, boolean audio) {
        return of(libraryPathId, folder, fileName, audio, NameContext.forFolder(folder));
    }

    /** @param context {@code NameContext.forFolder(folder)}, for a caller that reads many files of one folder */
    public static FileEvidence of(Long libraryPathId, String folder, String fileName, boolean audio, NameContext context) {
        String safeFolder = folder == null ? "" : folder;
        List<TitleKey> readings = TitleKey.readings(fileName, audio, context);
        List<SeriesReading> seriesReadings = new ArrayList<>();
        SeriesPrefix prefix = seriesPrefix(fileName);
        if (prefix != null) {
            List<TitleKey> rest = TitleKey.readings(prefix.rest(), audio, context);
            for (int i = 0; i < rest.size(); i++) {
                seriesReadings.add(new SeriesReading(rest.get(i), i == 0, prefix.number()));
            }
        }
        return new FileEvidence(libraryPathId, safeFolder, fileName, audio, context, readings, seriesReadings);
    }

    /** The reading the folders support: what new-book grouping groups the file by. */
    public TitleKey chosen() {
        return readings.getFirst();
    }

    /** Library path and sub path: the folder a file is in, for a rule that one book is claimed from one folder only. */
    public String folderKey() {
        return libraryPathId + ":" + folder;
    }

    /** Base names compare case-insensitively: on a share that ignores case, "Book" and "book" share sidecar files. */
    public String baseNameKey() {
        return baseNameKey(fileName);
    }

    static String baseNameKey(String fileName) {
        return TitleKey.baseName(fileName).toLowerCase(Locale.ROOT);
    }

    /** The base name without a duplicate's copy marker: "Title - Copy.epub" is the same file as "Title.epub". */
    public String copyBaseNameKey() {
        return TitleKey.withoutCopyMarker(TitleKey.baseName(fileName)).toLowerCase(Locale.ROOT);
    }

    private record SeriesPrefix(int number, String rest) {
    }

    /** The series number a name starts with and the rest of the name, extension included; null when there is none. */
    private static SeriesPrefix seriesPrefix(String fileName) {
        if (fileName == null) {
            return null;
        }
        String name = fileName.trim();
        Matcher number = NUMBER_PREFIX.matcher(name);
        if (number.matches()) {
            return new SeriesPrefix(Integer.parseInt(number.group(1)), number.group(2));
        }
        Matcher keyword = KEYWORD_PREFIX.matcher(name);
        if (keyword.matches()) {
            String value = TitleWords.numberAfter(keyword.group(1).toLowerCase(Locale.ROOT), keyword.group(2).toLowerCase(Locale.ROOT));
            return value == null ? null : new SeriesPrefix(Integer.parseInt(value), keyword.group(3));
        }
        Matcher bracket = BRACKET_PREFIX.matcher(name);
        if (bracket.matches()) {
            return new SeriesPrefix(Integer.parseInt(bracket.group(1)), bracket.group(2));
        }
        return null;
    }
}
