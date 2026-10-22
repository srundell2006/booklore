package org.booklore.service.library.matching;

import org.booklore.service.library.matching.BooksInFolder.Entry;
import org.booklore.service.library.matching.FileEvidence.SeriesReading;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Decides whether a new file belongs to a book the library already has: one with files in the same folder, or a
 * fileless one (a physical book, or a book whose files were lost). Pure: no repository, no entity and no I/O, so the
 * rescan and the watcher decide alike. When in doubt it attaches nothing: a duplicate book can be merged by hand, a
 * file attached to the wrong book is silently wrong.
 * <p>
 * Names are compared by {@link TitleKey#identity}, which holds a title's words and its volume numbers. Two names are
 * one book only when their identities are equal; there is no similarity score anywhere, so "A Dangerous Universe 3"
 * never matches "5", and "Kokoro Connect Volume 4 - Michi Random" never matches "Kokoro Connect Volume - Nise Random".
 * A part of a name is dropped as an author only when its folders vouch for it ({@link TitleKey#readings}' first
 * reading) or when it names an author of the very book it is compared with.
 * <p>
 * Same-folder matching runs first: the books with files in the new file's folder, by every one of their book files
 * there. Exactly one such book attaches; two or more mean the name does not say which, and none does. Only then are
 * fileless books considered, and one of them must also:
 * <ul>
 *   <li>be in the file's library path, or in none yet;</li>
 *   <li>have an author who agrees, by surname and a compatible given name or initial ({@link PersonName}), with one of
 *       the file's folders or with an author part of its name. A bare surname is not enough, and a one-word title
 *       ("Survival", "King") needs the author's first given name written as the book has it, not an initial for a
 *       name. A fileless book without authors only matches when it is a physical book;</li>
 *   <li>when a leading series number was taken off the name ("01 - Title"), have that series number;</li>
 *   <li>be the only fileless book that qualifies.</li>
 * </ul>
 * A rescan matches every new file against the same fileless books, so {@link #resolveShellClaims} then leaves alone a
 * fileless book claimed from more than one folder, or by two different files of one format.
 */
public final class ExistingBookMatcher {

    private ExistingBookMatcher() {
    }

    public enum Kind {
        /** A book with files in the new file's folder: the file is another format, part or copy of it. */
        SAME_FOLDER,
        /** A book without files, which the file gives one. */
        FILELESS
    }

    public sealed interface Decision permits Match, NoMatch, Ambiguous {
        String reason();
    }

    public record Match(long bookId, Kind kind, String reason) implements Decision {
    }

    public record NoMatch(String reason) implements Decision {
    }

    public record Ambiguous(List<Long> bookIds, String reason) implements Decision {
    }

    // ---- another format, part or copy of a book in the same folder ----

    public static Decision matchInFolder(FileEvidence file, BooksInFolder books) {
        if (books == null || books.isEmpty()) {
            return new NoMatch("no book has files in the folder");
        }
        // A book with a file of the same base name shares its sidecar files: they are one book whatever the names say
        Set<Long> matched = new LinkedHashSet<>(books.withBaseName(file.baseNameKey()));
        List<TitleKey> readings = file.readings();
        for (int i = 0; i < readings.size(); i++) {
            TitleKey reading = readings.get(i);
            Evidence evidence = Evidence.of(file, reading, i == 0);
            if (evidence == null) {
                continue;
            }
            for (Entry entry : candidates(reading, books)) {
                if (!yearsAgree(reading, entry.key())) {
                    continue;
                }
                List<PersonName> authors = books.authorsOf(entry.bookId());
                if (evidence.confirmed()) {
                    // The folders vouch for an author part that is none of this book's authors
                    if (!evidence.claim().isEmpty() && !authors.isEmpty() && PersonName.bestStrength(evidence.claim(), authors) == 0) {
                        continue;
                    }
                } else if (!agreesWithGivenName(evidence.claim(), authors)) {
                    continue;
                }
                matched.add(entry.bookId());
            }
        }
        if (matched.size() == 1) {
            return new Match(matched.iterator().next(), Kind.SAME_FOLDER, "same title in the same folder");
        }
        if (matched.size() > 1) {
            return new Ambiguous(List.copyOf(matched), matched.size() + " books in the folder have this title");
        }
        return new NoMatch("no book in the folder has this title");
    }

    /**
     * The books' files that read as this name: the same identity, or the file a duplicate's copy marker points at,
     * either way round ("Title (1).epub" next to "Title.epub").
     */
    private static List<Entry> candidates(TitleKey reading, BooksInFolder books) {
        List<Entry> entries = new ArrayList<>(books.withIdentity(reading.identity()));
        if (reading.copy() != null) {
            books.withIdentity(reading.identityWithoutCopy()).stream()
                    .filter(entry -> entry.key().copy() == null)
                    .forEach(entries::add);
        } else {
            entries.addAll(books.copiesOf(reading.identity()));
        }
        return entries;
    }

    /** Two names that both give a publication year are different books when the years differ. */
    private static boolean yearsAgree(TitleKey a, TitleKey b) {
        return a.years().isEmpty() || b.years().isEmpty() || a.years().equals(b.years());
    }

    // ---- a fileless book ----

    public static Decision matchShell(FileEvidence file, FilelessShellIndex index) {
        if (index == null || index.size() == 0) {
            return new NoMatch("the library has no fileless books");
        }
        Map<Long, ShellCandidate> accepted = new LinkedHashMap<>();
        Map<Long, String> rejected = new LinkedHashMap<>();
        List<TitleKey> readings = file.readings();
        for (int i = 0; i < readings.size(); i++) {
            consider(file, readings.get(i), i == 0, null, index, accepted, rejected);
        }
        for (SeriesReading reading : file.seriesReadings()) {
            consider(file, reading.key(), reading.confirmed(), reading.seriesNumber(), index, accepted, rejected);
        }
        if (accepted.size() == 1) {
            ShellCandidate shell = accepted.values().iterator().next();
            return new Match(shell.bookId(), Kind.FILELESS, "title and author agree with " + label(shell));
        }
        if (accepted.size() > 1) {
            return new Ambiguous(List.copyOf(accepted.keySet()), accepted.size() + " fileless books qualify: "
                    + accepted.values().stream().map(ExistingBookMatcher::label).collect(Collectors.joining(", ")));
        }
        if (rejected.isEmpty()) {
            return new NoMatch("no fileless book has this title");
        }
        return new NoMatch(String.join("; ", rejected.values()));
    }

    private static void consider(FileEvidence file, TitleKey reading, boolean confirmed, Integer seriesNumber,
                                 FilelessShellIndex index, Map<Long, ShellCandidate> accepted, Map<Long, String> rejected) {
        Evidence evidence = Evidence.of(file, reading, confirmed);
        if (evidence == null) {
            return;
        }
        for (ShellCandidate shell : index.lookup(reading.identity())) {
            if (accepted.containsKey(shell.bookId())) {
                continue;
            }
            String why = rejection(file, reading, evidence, seriesNumber, shell);
            if (why == null) {
                accepted.put(shell.bookId(), shell);
                rejected.remove(shell.bookId());
            } else {
                rejected.putIfAbsent(shell.bookId(), label(shell) + " " + why);
            }
        }
    }

    /** Why the fileless book is not this file's book, or null when it is. */
    private static String rejection(FileEvidence file, TitleKey reading, Evidence evidence, Integer seriesNumber,
                                    ShellCandidate shell) {
        if (shell.libraryPathId() != null && !shell.libraryPathId().equals(file.libraryPathId())) {
            return "is in another library path";
        }
        if (seriesNumber != null && !seriesNumber.equals(shell.seriesNumber())) {
            return "is not number " + seriesNumber + " of its series";
        }
        if (shell.authors().isEmpty()) {
            if (!shell.physical()) {
                return "has no author to check the file against";
            }
            // Nothing to confirm an author part of the name against: only a part the folders vouch for may go
            return evidence.confirmed() ? null : "has no author that '" + reading.droppedName() + "' could be";
        }
        if (!evidence.claim().isEmpty() && !agreesWithGivenName(evidence.claim(), shell.authors())) {
            return "is not by '" + reading.droppedName() + "'";
        }
        List<PersonName> names = new ArrayList<>(file.context().folderNames());
        names.addAll(evidence.claim());
        boolean oneWord = !reading.title().contains(" ");
        if (!agreesWithGivenName(names, shell.authors())) {
            return "has none of its authors in the path";
        }
        if (oneWord && !agreesWithSameGivenName(names, shell.authors())) {
            return "has a one-word title, which needs its author's given name in the path as the book has it";
        }
        return null;
    }

    private static String label(ShellCandidate shell) {
        return "book#" + shell.bookId() + " \"" + shell.title() + "\"";
    }

    /**
     * What a reading of a name says about its author, or null when the reading is no evidence at all.
     *
     * @param confirmed whether it is the reading the folders support; any other reading dropped a part that only
     *                  counts when it names an author of the book compared with
     * @param claim     the author part the reading dropped, parsed; empty when it dropped none, or dropped the file's
     *                  own folder name (a series folder: "KING - Las Vegas Bad Boys" in "Las Vegas Bad Boys")
     */
    private record Evidence(boolean confirmed, List<PersonName> claim) {

        static Evidence of(FileEvidence file, TitleKey reading, boolean confirmed) {
            String dropped = reading.droppedName();
            boolean ownFolder = dropped != null && file.context().isOwnFolder(dropped);
            if (!confirmed && (dropped == null || ownFolder)) {
                return null;
            }
            if (ownFolder && reading.title().isEmpty()) {
                // "01 - Kane and Abel" in a folder "Kane and Abel": nothing but the number is left
                return null;
            }
            List<PersonName> claim = dropped == null || ownFolder
                    ? List.of()
                    : PersonName.parseAll(dropped).stream().filter(PersonName::hasGivenName).toList();
            if (!confirmed && claim.isEmpty()) {
                return null;
            }
            return new Evidence(confirmed, claim);
        }
    }

    /** True when one of the names is one of the authors by surname and a compatible given name or initial. */
    private static boolean agreesWithGivenName(Collection<PersonName> names, Collection<PersonName> authors) {
        return PersonName.bestStrength(names, authors) == 2;
    }

    /**
     * As {@link #agreesWithGivenName}, with the first given name written alike on both sides: "Zadie" and "Zadie", or
     * "J." and "J.", but not "Z." for "Zadie", which could as well be another Smith.
     */
    private static boolean agreesWithSameGivenName(Collection<PersonName> names, Collection<PersonName> authors) {
        for (PersonName name : names) {
            for (PersonName author : authors) {
                if (name.strength(author) == 2 && name.givenNames().getFirst().equals(author.givenNames().getFirst())) {
                    return true;
                }
            }
        }
        return false;
    }

    // ---- rescan: one fileless book, one folder ----

    /**
     * A new file of a rescan that matched a fileless book.
     *
     * @param folderKey the file's library path and sub path
     * @param format    the file's format, or one value for every file where a book has a single file
     * @param nameKey   the file's base name without a copy marker ({@link FileEvidence#copyBaseNameKey}): files of
     *                  one format and one name key are copies of one file
     */
    public record Claim<F>(F file, String fileName, long bookId, String folderKey, String format, String nameKey) {
    }

    /**
     * @param accepted the files each fileless book gets, by book id
     * @param rejected why each fileless book that gets none was left alone, by book id
     */
    public record ClaimResolution<F>(Map<Long, List<F>> accepted, Map<Long, String> rejected) {
    }

    /**
     * Keeps a fileless book's claims only when they agree on one book: they all come from one folder, and no two of
     * them are different files of one format. Every file of a rescan is matched against the same fileless books, so
     * without the first rule one book would collect every "Survival.epub" of the library. Two different epubs are as
     * likely two books ("A Quiver Full of Arrows.epub" and "A Quiver Full of Arrows - Stories.epub" against a book
     * subtitled "Stories"; "King.epub" and "KING - Las Vegas Bad Boys.epub" in a folder "Las Vegas Bad Boys") as one,
     * and a fileless book is not the place to guess: such files become a new book, which can be merged by hand.
     */
    public static <F> ClaimResolution<F> resolveShellClaims(List<Claim<F>> claims) {
        Map<Long, List<Claim<F>>> byBook = new LinkedHashMap<>();
        for (Claim<F> claim : claims) {
            byBook.computeIfAbsent(claim.bookId(), k -> new ArrayList<>()).add(claim);
        }
        Map<Long, List<F>> accepted = new LinkedHashMap<>();
        Map<Long, String> rejected = new LinkedHashMap<>();
        byBook.forEach((bookId, bookClaims) -> {
            Set<String> folders = new TreeSet<>();
            Map<String, Set<String>> namesByFormat = new HashMap<>();
            for (Claim<F> claim : bookClaims) {
                folders.add(claim.folderKey());
                namesByFormat.computeIfAbsent(claim.format(), k -> new LinkedHashSet<>()).add(claim.nameKey());
            }
            if (folders.size() > 1) {
                rejected.put(bookId, "claimed by files of " + folders.size() + " folders " + folders + ": "
                        + names(bookClaims));
            } else if (namesByFormat.values().stream().anyMatch(names -> names.size() > 1)) {
                rejected.put(bookId, "claimed by different files of one format: " + names(bookClaims));
            } else {
                accepted.put(bookId, bookClaims.stream().map(Claim::file).toList());
            }
        });
        return new ClaimResolution<>(accepted, rejected);
    }

    private static String names(List<? extends Claim<?>> claims) {
        return claims.stream().map(Claim::fileName).collect(Collectors.joining(", "));
    }
}
