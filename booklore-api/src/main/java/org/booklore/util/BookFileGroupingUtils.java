package org.booklore.util;

import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.text.similarity.FuzzyScore;
import org.booklore.model.dto.settings.LibraryFile;
import org.booklore.model.enums.BookFileType;
import org.booklore.service.library.matching.NameContext;
import org.booklore.service.library.matching.TitleKey;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@UtilityClass
@Slf4j
public class BookFileGroupingUtils {

    private static final Pattern FORMAT_INDICATOR_PATTERN = Pattern.compile(
            "[\\(\\[]\\s*(?:pdf|epub|mobi|azw3?|fb2|cbz|cbr|cb7|m4b|m4a|mp3|audiobook|audio)\\s*[\\)\\]]",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");

    private static final Pattern UNDERSCORE_PATTERN = Pattern.compile("_");

    // Matches trailing author: " - J.R.R. Tolkien", " - George Orwell", " - J.K. Rowling"
    private static final Pattern TRAILING_AUTHOR_PATTERN = Pattern.compile(
            "\\s*[-–—]\\s+(?:[A-Z](?:\\.[A-Z])*\\.\\s+)?[A-Z][a-z]+(?:\\s+[A-Z](?:\\.[A-Z])*\\.?)?(?:\\s+[A-Z][a-z]+)*\\s*$"
    );

    // Matches ", The" or ", A" or ", An" at end
    private static final Pattern ARTICLE_SUFFIX_PATTERN = Pattern.compile(
            ",\\s*(The|A|An)\\s*$", Pattern.CASE_INSENSITIVE
    );

    // Part/disc indicators: "(part 1)", "[pt 2]", "- disc 1", "cd 3", etc.
    // "part" requires brackets or dash prefix to avoid conflict with series labels ("Part 1" = series entry).
    // "pt", "disc", "disk", "cd" are unambiguous and can be matched bare.
    private static final Pattern PART_INDICATOR_PATTERN = Pattern.compile(
            "\\s*(?:" +
                    "[\\(\\[]\\s*(?:part|pt|dis[ck]|cd)\\s*\\d+\\s*[\\)\\]]" +
                    "|-\\s*(?:part|pt|dis[ck]|cd)\\s*\\d+" +
                    "|(?:pt|dis[ck]|cd)\\s*\\d+" +
                    ")\\s*$",
            Pattern.CASE_INSENSITIVE
    );

    // Known book file extensions - only strip these, not arbitrary dots in folder names
    private static final Set<String> KNOWN_EXTENSIONS = Set.of(
            "pdf", "epub", "cbz", "cbr", "cb7", "mobi", "azw3", "azw", "fb2",
            "m4b", "m4a", "mp3"
    );

    /**
     * The key the rescan's and the watcher's matching against existing books still compare names by, until that
     * matching moves to {@link TitleKey}. New files are no longer grouped by it: it drops any " - Capitalised Words"
     * as an author, so "Assignment - Karachi" and "KING - Las Vegas Bad Boys" read as "assignment" and "king".
     */
    @Deprecated
    public String extractGroupingKey(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return "";
        }

        int lastDot = fileName.lastIndexOf('.');
        String baseName;
        if (lastDot > 0) {
            String possibleExtension = fileName.substring(lastDot + 1).toLowerCase();
            // Only strip if it's a known book file extension
            if (KNOWN_EXTENSIONS.contains(possibleExtension)) {
                baseName = fileName.substring(0, lastDot);
            } else {
                baseName = fileName;
            }
        } else {
            baseName = fileName;
        }

        // Convert underscores to spaces
        baseName = UNDERSCORE_PATTERN.matcher(baseName).replaceAll(" ");

        baseName = FORMAT_INDICATOR_PATTERN.matcher(baseName).replaceAll("");

        // Strip part/disc indicators (e.g., "(part 1)", "[cd 2]")
        baseName = PART_INDICATOR_PATTERN.matcher(baseName).replaceAll("");

        // Strip trailing author names (after dash)
        baseName = TRAILING_AUTHOR_PATTERN.matcher(baseName).replaceAll("");

        // Reposition articles ("Hobbit, The" -> "The Hobbit")
        Matcher articleMatcher = ARTICLE_SUFFIX_PATTERN.matcher(baseName);
        if (articleMatcher.find()) {
            String article = articleMatcher.group(1);
            baseName = article + " " + baseName.substring(0, articleMatcher.start());
        }

        baseName = WHITESPACE_PATTERN.matcher(baseName.trim()).replaceAll(" ");

        return baseName.toLowerCase().trim();
    }

    /**
     * The prefix-biased fuzzy score the rescan's and the watcher's matching against existing books still use, until
     * that matching moves to {@link TitleKey}. It must not decide whether two names are one book: names that differ
     * only in a volume number score above 0.9 ("A Dangerous Universe 3" and "5").
     */
    @Deprecated
    public double calculateSimilarity(String s1, String s2) {
        if (s1 == null || s2 == null || s1.isEmpty() || s2.isEmpty()) {
            return 0;
        }
        FuzzyScore fuzzyScore = new FuzzyScore(Locale.ENGLISH);
        int score = fuzzyScore.fuzzyScore(s1, s2);
        int maxScore = Math.max(
                fuzzyScore.fuzzyScore(s1, s1),
                fuzzyScore.fuzzyScore(s2, s2)
        );
        return maxScore > 0 ? (double) score / maxScore : 0;
    }

    /**
     * Groups new files into books, folder by folder (library path and sub path, the root included). Files of one
     * folder are one book only when their {@link TitleKey#identity()}s are equal: the same title words and the same
     * volume numbers, read with the folder's names as the only evidence of an author. There is no fuzzy score and no
     * matching against the folder's name, so "Battle Born by Dale Brown" and "Shadow Command by Dale Brown" stay two
     * books in "B/Dale Brown", and "Rocky II" and "Rocky III" stay two books in "Rocky".
     * <p>
     * On top of equal identities:
     * <ul>
     *   <li>Files sharing a base name (the name without its extension) are always one book. Their sidecar metadata
     *       and cover copy share that name too, so as two books they would overwrite each other's.</li>
     *   <li>A duplicate's copy marker (" (1)", " - Copy") is ignored when the file it duplicates is in the folder;
     *       otherwise " (2)" reads as a number, so "Saga (1)" and "Saga (2)" stay two books.</li>
     *   <li>Files naming different publication years ("Dune (1965)", "Dune (2021)") are different books; a file
     *       naming none then stays apart from both rather than guessing which it belongs to.</li>
     * </ul>
     * Group keys are {@code <libraryPathId>:<subPath>:<identity>}.
     */
    public Map<String, List<LibraryFile>> groupByBaseName(List<LibraryFile> libraryFiles) {
        Map<String, List<LibraryFile>> byFolder = new LinkedHashMap<>();
        for (LibraryFile file : libraryFiles) {
            byFolder.computeIfAbsent(folderKey(file), k -> new ArrayList<>()).add(file);
        }

        Map<String, List<LibraryFile>> result = new LinkedHashMap<>();
        byFolder.forEach((folderKey, filesInFolder) -> groupFolder(folderKey, filesInFolder).forEach((key, group) -> {
            result.put(key, group);
            if (group.size() > 1) {
                log.debug("Grouped {} files as one book '{}': {}", group.size(), key,
                        group.stream().map(LibraryFile::getFileName).toList());
            }
        }));
        return result;
    }

    private String folderKey(LibraryFile file) {
        return file.getLibraryPathEntity().getId() + ":" + (file.getFileSubPath() == null ? "" : file.getFileSubPath());
    }

    private Map<String, List<LibraryFile>> groupFolder(String folderKey, List<LibraryFile> files) {
        NameContext context = NameContext.forFolder(files.getFirst().getFileSubPath());
        List<TitleKey> keys = files.stream()
                .map(file -> TitleKey.ofFileName(file.getFileName(), file.getBookFileType() == BookFileType.AUDIOBOOK, context))
                .toList();

        // A copy marker only folds a file into the book it duplicates when that book is in the folder
        Set<String> unmarked = new HashSet<>();
        for (TitleKey key : keys) {
            if (key.copy() == null) {
                unmarked.add(key.identity());
            }
        }
        List<String> identities = new ArrayList<>();
        for (int i = 0; i < files.size(); i++) {
            TitleKey key = keys.get(i);
            String identity = key.copy() != null && unmarked.contains(key.identityWithoutCopy())
                    ? key.identityWithoutCopy()
                    : key.identity();
            // A name with no word or number left to tell it by ("(Digital).cbz") is only the same book as its own
            // base name
            identities.add(identity.isEmpty() ? "=" + baseNameKey(files.get(i)) : identity);
        }

        // Different years split a title; a file naming no year then belongs to neither
        Map<String, Set<String>> yearsByIdentity = new HashMap<>();
        for (int i = 0; i < files.size(); i++) {
            if (!keys.get(i).years().isEmpty()) {
                yearsByIdentity.computeIfAbsent(identities.get(i), k -> new TreeSet<>()).add(yearLabel(keys.get(i)));
            }
        }
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < files.size(); i++) {
            String identity = identities.get(i);
            boolean split = yearsByIdentity.getOrDefault(identity, Set.of()).size() > 1;
            labels.add(split ? identity + " (" + yearLabel(keys.get(i)) + ")" : identity);
        }

        // Files sharing a base name are one book whatever their names read as ("Book (Part 1).epub" and
        // "Book (Part 1).m4b": only the audiobook's part is a split file)
        Map<String, String> labelOfBaseName = new HashMap<>();
        Map<String, String> mergedInto = new HashMap<>();
        for (int i = 0; i < files.size(); i++) {
            String label = root(mergedInto, labels.get(i));
            String other = labelOfBaseName.putIfAbsent(baseNameKey(files.get(i)), label);
            if (other != null) {
                String otherRoot = root(mergedInto, other);
                if (!otherRoot.equals(label)) {
                    mergedInto.put(label, otherRoot);
                }
            }
        }

        Map<String, List<LibraryFile>> groups = new LinkedHashMap<>();
        for (int i = 0; i < files.size(); i++) {
            groups.computeIfAbsent(folderKey + ":" + root(mergedInto, labels.get(i)), k -> new ArrayList<>()).add(files.get(i));
        }
        return groups;
    }

    private String root(Map<String, String> mergedInto, String label) {
        String current = label;
        while (mergedInto.containsKey(current)) {
            current = mergedInto.get(current);
        }
        return current;
    }

    private String yearLabel(TitleKey key) {
        return key.years().isEmpty() ? "no year" : key.years().stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    /** Base names compare case-insensitively: on a share that ignores case, "Book" and "book" share sidecar files. */
    private String baseNameKey(LibraryFile file) {
        return TitleKey.baseName(file.getFileName()).toLowerCase(Locale.ROOT);
    }
}
