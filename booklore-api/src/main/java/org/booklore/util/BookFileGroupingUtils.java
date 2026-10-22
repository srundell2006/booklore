package org.booklore.util;

import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.booklore.model.dto.settings.LibraryFile;
import org.booklore.model.enums.BookFileType;
import org.booklore.service.library.matching.NameContext;
import org.booklore.service.library.matching.PersonName;
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
import java.util.stream.Collectors;

@UtilityClass
@Slf4j
public class BookFileGroupingUtils {

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
     *   <li>Files whose dropped author parts name different people ("Collected Poems - W. B. Yeats", "Collected Poems
     *       - T. S. Eliot") are different books; a file naming no one then stays apart from both.</li>
     * </ul>
     * Group keys are {@code <libraryPathId>:<subPath>:<identity>}, with the year or the author that split a title.
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
        splitByAuthor(labels, keys.stream().map(key -> key.namedAuthors(context)).toList());

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

    /**
     * Files of one label whose dropped author parts name different people are different books: "Collected Poems - W.
     * B. Yeats" and "Collected Poems - T. S. Eliot", or "Dune - F. Herbert" and "Dune - B. Herbert", read as the same
     * title once their initials are dropped. Files naming people who agree, by surname and compatible given names or
     * initials, stay together; when they do not all agree, each file naming someone goes only with the files naming
     * the same people written alike, and a file naming no one belongs to none of them.
     */
    private void splitByAuthor(List<String> labels, List<List<PersonName>> named) {
        Map<String, List<Integer>> namedByLabel = new LinkedHashMap<>();
        for (int i = 0; i < labels.size(); i++) {
            if (!named.get(i).isEmpty()) {
                namedByLabel.computeIfAbsent(labels.get(i), k -> new ArrayList<>()).add(i);
            }
        }
        namedByLabel.forEach((label, indexes) -> {
            boolean allAgree = true;
            for (int a = 0; a < indexes.size() && allAgree; a++) {
                for (int b = a + 1; b < indexes.size() && allAgree; b++) {
                    allAgree = PersonName.bestStrength(named.get(indexes.get(a)), named.get(indexes.get(b))) == 2;
                }
            }
            if (allAgree) {
                return;
            }
            for (int i : indexes) {
                labels.set(i, label + " by " + named.get(i).stream()
                        .map(name -> name.surname() + " " + String.join(" ", name.givenNames()))
                        .sorted()
                        .collect(Collectors.joining(" & ")));
            }
        });
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
