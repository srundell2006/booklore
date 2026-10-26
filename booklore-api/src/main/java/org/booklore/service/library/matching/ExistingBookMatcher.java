package org.booklore.service.library.matching;

import org.booklore.service.library.matching.BooksInFolder.Entry;
import org.booklore.service.library.matching.FileEvidence.SeriesReading;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Predicate;
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
 * reading) or when it names an author of the very book it is compared with. A duplicate's copy marker " (n)" counts as
 * one more volume number: "Saga (2).epub" is the second Saga.
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
 *   <li>be the only fileless book that qualifies, or one of several alike ones: the same titles, with the same
 *       years in brackets (so "Rick Steves Italy (2020)" is not the 2023 edition), the same series number (as given,
 *       so 1.5 is not 2.5) and the same authors, as a book entered twice or a paper copy's record next to its ebook's
 *       are. The file goes to one that is not physical, since an ebook file belongs with the record whose file was
 *       lost, else to the one with the lowest id. Books without authors are never alike: nothing says they are one
 *       book.</li>
 * </ul>
 * A name ending in " (1)", as a browser names a second download, is also read without it, by the same rules:
 * "Started Early, Took My Dog (1).epub" is the fileless book "Started Early, Took My Dog" as well as its first volume.
 * A book found only that way that says it is another volume than 1 is turned down when nothing else in the name is a
 * number, since " (1)" may as well mean volume 1: its series number is another (2, or 2.5) and its series may be what
 * the name calls it, or its title or subtitle names volumes after a series keyword and none of them is 1 ("Saga:
 * Volume Two"). A series may be what the name calls it when it has no name, or its name reads as the name without
 * its " (1)", maybe with a last word such as "Series" or "Trilogy" ({@link TitleWords#SERIES_NOUNS}): "Saga (1).epub"
 * may be volume 1 of a series "Saga", but "The Testaments (1).epub" is no volume of The Handmaid's Tale, whose first
 * volume would be named after it, and is the copy of "The Testaments", its number 2. A "(Series, #N)" suffix that
 * gives the book's place names its series too, whatever the metadata's series name: "Saga (Saga, #2)" is number 2 of
 * a series "Saga" ({@link ShellCandidate#seriesIdentities}). A book turned down so may be the
 * file's book all the same, if the " (1)" is a copy's: so when another book qualifies as a copy too the file is
 * ambiguous ("Saga (1).epub" against a "Saga" numbered 1 and one numbered 2), and only when none does is it no match;
 * a book the name reads as without the copy reading ("Saga: Volume 1") is the file's book. A bare number in
 * a subtitle names no volume: "Unbroken (1).epub" is the copy of "Unbroken: A World War II Story of Survival,
 * Resilience, and Redemption". With a leading series number ("03 - Title (1).epub") or another number in the name
 * the " (1)" is a copy, and the series number rule decides. A comic's " (1)" is an issue, and " (2)" to " (9)" stay
 * volume numbers.
 * <p>
 * A book's authors are its stored author names, each read as {@link PersonName#parseAuthor} reads it: one name that
 * packs several ("Arthur C. Clarke;Gentry Lee") is each of them, an HTML entity is decoded ("P&eacute;ter") and a part
 * in brackets left out ("Otto Penzler (Editor)"). Read as one person each ({@link ShellCandidate#authorsAsStored}), the
 * first is a Lee, and "C/Arthur C. Clarke/Cradle.epub" was no file of that "Cradle". A book's title drops an author
 * part that names one of its authors either way: "Child of a Dead God - John Hendee" by "Barb Hendee; John Hendee" is
 * also "Child of a Dead God", but only with the names read apart ({@link ShellCandidate#identitiesAsStored}).
 * <p>
 * Those three rules, the choice among alike books, a " (1)" read as a copy and the stored author names read apart,
 * only ever add a book to a file that the other rules give none; they never take one away or change it. So a match
 * only they make is weak ({@link Match#weak}), and the books the name reads as without them decide first: a file that
 * reads as one book is that book's, whatever its " (1)" read as a copy finds besides ("Nico (1).epub" is "Nico (Book
 * 1)", next to a "Nico" by the same author), or another record of the book whose author names only read apart agree
 * ("02. Reliquary - Douglas Preston (1998).epub" is the "Reliquary" by Douglas Preston, next to one by "Douglas
 * Preston;Lincoln Child", and "Child of a Dead God.epub" the one by Barb Hendee, next to "Child of a Dead God - John
 * Hendee"), and when it reads as several only the choice among alike ones may pick one of them. The rules then go in
 * the order they came: when the name reads as no book by the authors as stored, the book its " (1)" read as a copy
 * finds by the authors as stored, if that is one book (or one of alike ones); only where that gives none, the books
 * the name reads as with their author names read apart; and only then the rest, those found with its " (1)" read as a
 * copy only with the names read apart, and those the " (1)" guard turned down. So "Cradle (1).epub" in "C/Arthur C.
 * Clarke" is the copy of the "Cradle" by Arthur C. Clarke, as before, not the "Cradle 1" by "Arthur C. Clarke;Gentry
 * Lee". A match the names read apart make ranks below the weak ones of the other rules ({@link Match#readApart}). None
 * of them is ever one that the name as it reads says it is not: "01 - Survival (1).epub" is number 1 of a series, so
 * not the fileless "Survival: Book 1" without a series number, which it would be with its " (1)" read as a copy. Nor
 * does reading the author names apart turn down a book that the names as stored agree with, nor what the names as
 * stored say against a book stop counting or count for more: where both readings turn a book down, the names as
 * stored say why. "Child of a Dead God - J.C. Hendee.pdf" is not by "Barb Hendee; J. C. Hendee" read as one Hendee,
 * so where a match of its other files by the rules before gives them that book, they claim it only as weakly as the
 * names read apart do ({@code BookGroupingService}). A rescan's other files then outweigh a weak match: the files that
 * would be one new book go to the book of their strong matches, so "Killing Floor.epub", weakly one of two alike
 * fileless "Killing Floor"s, goes with "The Killing Floor (1).epub" to the book of "01. Killing Floor - Lee Child
 * (2006).mobi" in their folder; and where strong and weak claims on a fileless book do not agree, the strong ones get
 * it ({@link #resolveShellClaims}), as the weak claims of the other rules do against those of the names read apart.
 * Nor does what only a " (1)" read as a copy finds against a book keep the files from one a strong match gives them:
 * "Good Omens - T. Pratchett (1).pdf" read as a copy is not the fileless "Good Omens" by Neil Gaiman, which the other
 * rules never looked at for it, so it still goes with "Good Omens.epub" to that book. That evidence only keeps files
 * from a book that weak matches alone give them ({@link Decision#refused}, {@link Decision#alsoRefused}).
 * <p>
 * The books with files in the file's folder are read by their authors as stored first too ({@link #matchInFolder}):
 * only where those find none does a book found with its stored author names read apart count, weakly, and after the
 * fileless books.
 * <p>
 * A rescan matches every new file against the same fileless books, so {@link #resolveShellClaims} then leaves alone a
 * fileless book claimed from more than one folder, or by two different files of one format. Files that match the same
 * alike books all claim the same one of them, so for those the rules hold as for one book. A file only sees the books
 * in its own library path or in none, though, so files of two library paths may see different ones of alike books and
 * each get one: with alike books in path 1 and in no path, a "Survival.epub" of path 1 gets the first and one of path
 * 2 the second, where a single book in no path would get neither. The watcher takes one file at a time and no longer
 * offers a book once a file was attached to it, so a later file that matches the same alike books gets the next of
 * them. With no other file to outweigh it there, a weak match attaches as any other.
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

    /**
     * What a file's name says of the existing books. Besides its book, if any, a decision names the books the file
     * says it is not, which keep the files it would be one book with from them too.
     */
    public sealed interface Decision permits Match, NoMatch, Ambiguous {
        String reason();

        /**
         * The books whose title the file reads as but which it says it is not, as the rules before the weak ones
         * ({@link Match#weak}) count them: it names another author, year or series number, or is in another library
         * path. A book turned down only for want of evidence (no author in the path) is not among them, nor one that
         * another reading of the name accepts without its " (1)" read as a copy; no reading as a copy accepts one of
         * them. A book the name accepts only with its stored author names read apart is among them when those names
         * as stored say the file is not it, as before: even a weak match's own book. Found only with its " (1)" read
         * as a copy, such a book is among {@link #alsoRefused} instead. A file of the same new book must not be
         * attached to these either, unless its own match with the names read apart is that book, which a group then
         * claims as weakly as that match.
         */
        Set<Long> refused();

        /**
         * The further books the file says it is not, which the rules before the weak ones did not count: found only
         * with its " (1)" read as a copy, or turned down by a name that reads as several books, which made the file
         * ambiguous and refused none. They keep the file and the files it would be one book with only from a book
         * that weak matches alone give them, which those rules gave none; a book a strong match gives them they never
         * take away.
         */
        Set<Long> alsoRefused();
    }

    /**
     * @param weak        whether only a rule that reads more into a name than its title says gives the file this
     *                    book: the choice of one of alike fileless books, a " (1)" read as a copy, or the book's
     *                    stored author names read apart where, each read as one person, they turn the book down, or
     *                    where only its title read with them apart is the name (its reason then says "(its stored
     *                    author names read apart)"), and a book of the folder that only they find. A weak match never
     *                    takes a file from the book the other rules give it, or give the files it would be one book
     *                    with: a rescan's group of files goes to the book of its strong matches ({@code
     *                    BookGroupingService}), and a fileless book claimed by strong and weak matches that do not
     *                    agree goes to the strong ones ({@link #resolveShellClaims}). The watcher, which takes one
     *                    file at a time, attaches it as any other match, a book of the folder only after the fileless
     *                    books
     * @param refused     for a weak match, the books the file says it is not as the other rules alone would have it,
     *                    for when the match yields to its group's: a {@link NoMatch}'s, or none where those rules find
     *                    several books, which make the file ambiguous and refuse none. It may hold the match's own
     *                    book, which the names as stored turned down: the file's group then claims it as weakly as a
     *                    match with the names read apart. Empty for a strong match
     * @param alsoRefused for a weak match, the books {@link Decision#alsoRefused}; empty for a strong match
     * @param readApart   for a weak match, whether only the stored author names read apart give the file this book,
     *                    where the weak rules before that reading (the choice among alike books, a " (1)" read as a
     *                    copy) gave it none. Such a match ranks below those: a rescan's group goes to the book of
     *                    their weak matches first, and their weak claims on a fileless book outweigh its claims
     *                    ({@link #resolveShellClaims}), so that reading the names apart only adds a book
     */
    public record Match(long bookId, Kind kind, String reason, boolean weak, Set<Long> refused, Set<Long> alsoRefused,
                        boolean readApart) implements Decision {
        public Match {
            refused = Set.copyOf(refused);
            alsoRefused = Set.copyOf(alsoRefused);
        }

        /** A match by the rules before the stored author names were read apart. */
        public Match(long bookId, Kind kind, String reason, boolean weak, Set<Long> refused, Set<Long> alsoRefused) {
            this(bookId, kind, reason, weak, refused, alsoRefused, false);
        }

        /** How strongly the match gives the file its book: strong (2), weak (1), only with the names read apart (0). */
        public int rank() {
            return !weak ? 2 : readApart ? 0 : 1;
        }

        /** A strong match: the one book whose title the name reads as. */
        public Match(long bookId, Kind kind, String reason) {
            this(bookId, kind, reason, false, Set.of(), Set.of());
        }
    }

    public record NoMatch(String reason, Set<Long> refused, Set<Long> alsoRefused) implements Decision {
        public NoMatch {
            refused = Set.copyOf(refused);
            alsoRefused = Set.copyOf(alsoRefused);
        }

        public NoMatch(String reason, Set<Long> refused) {
            this(reason, refused, Set.of());
        }

        public NoMatch(String reason) {
            this(reason, Set.of(), Set.of());
        }
    }

    /**
     * @param refused     none where the name reads as several books, as before the weak rules; where only a weak
     *                    rule finds several (its " (1)" read as a copy, or the books' stored author names read apart),
     *                    the books the name says it is not without the weak rules, as a {@link NoMatch} has them
     * @param alsoRefused {@link Decision#alsoRefused}
     */
    public record Ambiguous(List<Long> bookIds, String reason, Set<Long> refused, Set<Long> alsoRefused) implements Decision {
        public Ambiguous {
            refused = Set.copyOf(refused);
            alsoRefused = Set.copyOf(alsoRefused);
        }

        public Ambiguous(List<Long> bookIds, String reason) {
            this(bookIds, reason, Set.of(), Set.of());
        }
    }

    // ---- another format, part or copy of a book in the same folder ----

    /**
     * The book in the file's folder whose files read as its name, by their authors as stored. Only where those find
     * none do the books' stored author names read apart count ({@link BooksInFolder#readApart}): one book found so is a
     * weak match ({@link Match#readApart}), which a fileless book the file matches outweighs; several, or none, leave
     * the file as the names as stored had it, refusing what they refused.
     */
    public static Decision matchInFolder(FileEvidence file, BooksInFolder books) {
        if (books == null || books.isEmpty()) {
            return new NoMatch("no book has files in the folder");
        }
        Decision asStored = inFolder(file, books);
        BooksInFolder readApart = books.readApart();
        if (readApart != null && asStored instanceof NoMatch noMatch && inFolder(file, readApart) instanceof Match match) {
            return new Match(match.bookId(), Kind.SAME_FOLDER, match.reason() + AUTHOR_READING, true, noMatch.refused(),
                    noMatch.alsoRefused(), true);
        }
        return asStored;
    }

    private static Decision inFolder(FileEvidence file, BooksInFolder books) {
        // A book with a file of the same base name shares its sidecar files: they are one book whatever the names say
        Set<Long> matched = new LinkedHashSet<>(books.withBaseName(file.baseNameKey()));
        Set<Long> refused = new LinkedHashSet<>();
        List<TitleKey> readings = file.readings();
        for (int i = 0; i < readings.size(); i++) {
            TitleKey reading = readings.get(i);
            Evidence evidence = Evidence.of(file, reading, i == 0);
            if (evidence == null || reading.identity().isEmpty()) {
                // A name with no word or number left ("(Digital).cbz") is only the book of its own base name
                continue;
            }
            for (Entry entry : candidates(reading, books)) {
                if (!yearsAgree(reading, entry.key())) {
                    refused.add(entry.bookId());
                    continue;
                }
                List<PersonName> authors = books.authorsOf(entry.bookId());
                if (evidence.confirmed()) {
                    // The folders vouch for an author part that is none of this book's authors, or, for a book
                    // without authors, not the one its own file names ("Dune - B. Herbert" and "Dune - F. Herbert")
                    List<PersonName> known = authors.isEmpty() ? entry.key().namedAuthors(file.context()) : authors;
                    if (!evidence.claim().isEmpty() && !known.isEmpty() && PersonName.bestStrength(evidence.claim(), known) == 0) {
                        refused.add(entry.bookId());
                        continue;
                    }
                } else if (!agreesWithGivenName(evidence.claim(), authors)) {
                    // Only a reading that might have dropped an author: no evidence against the book
                    continue;
                }
                matched.add(entry.bookId());
            }
        }
        refused.removeAll(matched);
        if (matched.size() == 1) {
            return new Match(matched.iterator().next(), Kind.SAME_FOLDER, "same title in the same folder");
        }
        if (matched.size() > 1) {
            return new Ambiguous(List.copyOf(matched), matched.size() + " books in the folder have this title");
        }
        if (!refused.isEmpty()) {
            return new NoMatch("the books in the folder with this title " + refused + " are another author's or year's", refused);
        }
        return new NoMatch("no book in the folder has this title");
    }

    /**
     * The books' files that read as this name: the same identity, or the file a duplicate's copy marker points at,
     * either way round ("Title (1).epub" next to "Title.epub").
     */
    private static List<Entry> candidates(TitleKey reading, BooksInFolder books) {
        List<Entry> entries = new ArrayList<>(books.withIdentity(reading.identity()));
        if (reading.copy() != null && !reading.identityWithoutCopy().isEmpty()) {
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
        Findings findings = new Findings();
        List<TitleKey> readings = file.readings();
        for (int i = 0; i < readings.size(); i++) {
            consider(file, readings.get(i), i == 0, null, index, findings);
        }
        for (SeriesReading reading : file.seriesReadings()) {
            consider(file, reading.key(), reading.confirmed(), reading.seriesNumber(), index, findings);
        }
        Map<Long, Accepted> accepted = findings.accepted();
        // What the file says it is not as the rules before the weak ones have it
        Map<Long, Rejection> rejected = findings.rejectedAsStored();
        Map<Long, Accepted> doubted = findings.doubted();
        // The books the name reads as by their authors as stored decide first, as they did before the weak rules; then
        // the one its " (1)" read as a copy finds by them, as before the names were read apart; then those it reads as
        // with their stored author names read apart; then the rest found with its " (1)" read as a copy
        Map<Long, Accepted> byName = only(accepted, Accepted::strong);
        Map<Long, Accepted> byAuthorReading = only(accepted, a -> !a.asCopy() && a.byAuthorReading());
        List<Accepted> onlyAsCopy = new ArrayList<>(only(accepted, Accepted::asCopy).values());
        onlyAsCopy.addAll(doubted.values());
        if (!byName.isEmpty()) {
            String yielding = yieldingByAuthorReading(byAuthorReading.values()) + yieldingAsCopy(onlyAsCopy);
            if (byName.size() == 1) {
                Accepted only = byName.values().iterator().next();
                return new Match(only.shell().bookId(), Kind.FILELESS, agreement(only) + yielding);
            }
            // Ambiguous before the weak rules, the file refused none; what it says it is not still counts against a
            // book that only weak matches give its group
            Set<Long> alsoRefused = refused(rejected, Rejection::against);
            Accepted chosen = oneOfAlike(byName.values());
            if (chosen != null) {
                return new Match(chosen.shell().bookId(), Kind.FILELESS, oneOf(chosen, byName) + yielding,
                        true, Set.of(), alsoRefused, !alikeAsStored(byName.values()));
            }
            return new Ambiguous(List.copyOf(byName.keySet()), byName.size() + " fileless books qualify: "
                    + labels(byName.values()), Set.of(), alsoRefused);
        }
        // Only a weak rule finds a book, if one does: whatever it decides is weak, and the file says it is not the
        // books it said it was not before the weak rules, as a NoMatch. A book its stored author names read apart let
        // in may be one of them: what those names as stored said against it still counts where its group has a
        // strong match, and the group then claims the book weakly (BookGroupingService)
        Set<Long> refused = refused(rejected, Rejection::refuses);
        Set<Long> alsoRefused = refused(rejected, Rejection::refusesAsCopy);
        // The name read as a copy decides next by the authors as stored, as it did before their names were read apart:
        // the book it gives that way stays the file's, and only where it gives none may the names read apart add one
        Map<Long, Accepted> asStored = only(accepted, a -> !a.byAuthorReading());
        findings.copiesAsStored().forEach((bookId, a) -> {
            // Unless the name as it reads says it is not the book, which kept the copy reading from it then
            Rejection said = rejected.get(bookId);
            if (said == null || !said.refuses()) {
                asStored.putIfAbsent(bookId, a);
            }
        });
        Decision asBefore = null;
        if (!asStored.isEmpty()) {
            List<Accepted> readApart = new ArrayList<>(only(accepted, a -> a.byAuthorReading()
                    && !asStored.containsKey(a.shell().bookId())).values());
            readApart.addAll(only(doubted, Accepted::byAuthorReading).values());
            asBefore = amongWeak(asStored, only(doubted, a -> !a.byAuthorReading()), refused, alsoRefused,
                    yieldingByAuthorReading(readApart), false);
            if (asBefore instanceof Match) {
                return asBefore;
            }
        }
        if (!byAuthorReading.isEmpty()) {
            return amongWeak(byAuthorReading, Map.of(), refused, alsoRefused, yieldingAsCopy(onlyAsCopy), true);
        }
        if (asBefore != null) {
            return asBefore;
        }
        if (!accepted.isEmpty()) {
            return amongWeak(accepted, doubted, refused, alsoRefused, "", true);
        }
        if (findings.rejected().isEmpty()) {
            return new NoMatch("no fileless book has this title");
        }
        return new NoMatch(findings.rejected().values().stream().map(Rejection::why).collect(Collectors.joining("; ")),
                refused, alsoRefused);
    }

    /**
     * The weak decision among the books a weak rule finds, none of them strongly: one is the file's book, one of alike
     * ones too, and several others make it ambiguous; so do the books the " (1)" guard turned down besides.
     *
     * @param yielding  what the reason says of the books found by a weaker rule still, which yield
     * @param readApart whether only the stored author names read apart find these books ({@link Match#readApart})
     */
    private static Decision amongWeak(Map<Long, Accepted> accepted, Map<Long, Accepted> doubted, Set<Long> refused,
                                      Set<Long> alsoRefused, String yielding, boolean readApart) {
        if (!doubted.isEmpty()) {
            // Read as a copy, the books the " (1)" guard turned down qualify as well: the name does not say which
            List<Long> bookIds = new ArrayList<>(accepted.keySet());
            bookIds.addAll(doubted.keySet());
            return new Ambiguous(bookIds, bookIds.size() + " fileless books qualify: " + labels(accepted.values())
                    + ", and " + labels(doubted.values()) + " if \" (1)\" is a copy", refused, alsoRefused);
        }
        if (accepted.size() == 1) {
            Accepted only = accepted.values().iterator().next();
            return new Match(only.shell().bookId(), Kind.FILELESS, agreement(only) + yielding, true, refused, alsoRefused,
                    readApart);
        }
        Accepted chosen = oneOfAlike(accepted.values());
        if (chosen != null) {
            return new Match(chosen.shell().bookId(), Kind.FILELESS, oneOf(chosen, accepted) + yielding, true, refused,
                    alsoRefused, readApart || !alikeAsStored(accepted.values()));
        }
        return new Ambiguous(List.copyOf(accepted.keySet()), accepted.size() + " fileless books qualify: "
                + labels(accepted.values()), refused, alsoRefused);
    }

    /** The books {@code which} says, in the order they were found. */
    private static Map<Long, Accepted> only(Map<Long, Accepted> found, Predicate<Accepted> which) {
        Map<Long, Accepted> only = new LinkedHashMap<>();
        found.forEach((bookId, a) -> {
            if (which.test(a)) {
                only.put(bookId, a);
            }
        });
        return only;
    }

    private static String labels(Collection<Accepted> books) {
        return books.stream().map(a -> label(a.shell())).collect(Collectors.joining(", "));
    }

    /** The books turned down as {@code which} says. */
    private static Set<Long> refused(Map<Long, Rejection> rejected, Predicate<Rejection> which) {
        Set<Long> refused = new LinkedHashSet<>();
        rejected.forEach((bookId, rejection) -> {
            if (which.test(rejection)) {
                refused.add(bookId);
            }
        });
        return refused;
    }

    private static String oneOf(Accepted chosen, Map<Long, Accepted> alike) {
        return agreement(chosen) + ", one of " + alike.size() + " alike fileless books (" + alike.keySet().stream().sorted()
                .map(id -> "book#" + id).collect(Collectors.joining(", ")) + ")";
    }

    /**
     * What a match says of the books that qualify as it does but only with their stored author names read apart,
     * which yield to it.
     */
    private static String yieldingByAuthorReading(Collection<Accepted> byAuthorReading) {
        if (byAuthorReading.isEmpty()) {
            return "";
        }
        return "; " + labels(byAuthorReading)
                + (byAuthorReading.size() == 1 ? " qualifies only with its" : " qualify only with their")
                + " stored author names read apart, and " + (byAuthorReading.size() == 1 ? "yields" : "yield");
    }

    /** What a match by the name as it reads says of the books that qualify only with its " (1)" read as a copy. */
    private static String yieldingAsCopy(List<Accepted> onlyAsCopy) {
        if (onlyAsCopy.isEmpty()) {
            return "";
        }
        return "; " + labels(onlyAsCopy)
                + (onlyAsCopy.size() == 1 ? " qualifies" : " qualify") + " only if \" (1)\" is a copy, and "
                + (onlyAsCopy.size() == 1 ? "yields" : "yield");
    }

    /**
     * Why a fileless book is not a file's book.
     *
     * @param against whether the file says it is not that book (another author, series number or library path),
     *                rather than only failing to say that it is
     * @param asCopy  whether the book was found only with the name's " (1)" read as a copy, which the rules before
     *                that reading never looked at: evidence against it is then {@link Decision#alsoRefused}, not
     *                {@link Decision#refused}
     */
    private record Rejection(String why, boolean against, boolean asCopy) {

        Rejection(String why, boolean against) {
            this(why, against, false);
        }

        /** The same rejection, of a book found only with the name's " (1)" read as a copy, and saying so. */
        Rejection readAsCopy() {
            return new Rejection(why + COPY_READING, against, true);
        }

        /** Evidence against the book, as the rules before the " (1)" read as a copy count it. */
        boolean refuses() {
            return against && !asCopy;
        }

        /** Evidence against the book, which only the name's " (1)" read as a copy gives. */
        boolean refusesAsCopy() {
            return against && asCopy;
        }

        /**
         * Of two rejections of one book by different readings, the one to keep: evidence against the book outweighs
         * a want of evidence, and evidence the rules before the copy reading count outweighs evidence only that
         * reading gives; the first of two alike.
         */
        static Rejection weightier(Rejection first, Rejection next) {
            return next.weight() > first.weight() ? next : first;
        }

        private int weight() {
            return refuses() ? 2 : refusesAsCopy() ? 1 : 0;
        }
    }

    /** How a reason says that a book was found only with the name's " (1)" read as a copy. */
    private static final String COPY_READING = " (\" (1)\" read as a copy)";

    /**
     * How a reason says that a book qualifies only with its stored author names read apart ({@link
     * PersonName#parseAuthor}): one that packs several authors read as each of them, an HTML entity decoded, a part in
     * brackets left out.
     */
    private static final String AUTHOR_READING = " (its stored author names read apart)";

    /**
     * A fileless book that qualifies.
     *
     * @param asCopy          whether only the name without its " (1)" reads as the book's title
     * @param byAuthorReading whether the book qualifies only with its stored author names read apart ({@link
     *                        ShellCandidate#authors}), not each as one person ({@link ShellCandidate#authorsAsStored})
     */
    private record Accepted(ShellCandidate shell, boolean asCopy, boolean byAuthorReading) {

        /** Whether the rules before the weak ones accept the book: by the name as it reads and its authors as stored. */
        boolean strong() {
            return !asCopy && !byAuthorReading;
        }

        /**
         * How the book is found, the best first: by the name as it reads and the authors as stored (3), with the
         * stored author names read apart (2), with the " (1)" read as a copy (1), with both (0). Each yields to the
         * ones before it.
         */
        int strength() {
            return (asCopy ? 0 : 2) + (byAuthorReading ? 0 : 1);
        }

        static Accepted stronger(Accepted first, Accepted next) {
            return next.strength() > first.strength() ? next : first;
        }
    }

    /**
     * What the readings of one file found among the fileless books, by book id.
     *
     * @param accepted the books that qualify
     * @param rejected why each book that does not qualify was turned down
     * @param doubted  the books that qualify but for the " (1)" guard ({@link #anotherVolumeThanOne}), and qualify by
     *                 no reading: the file may be such a book's copy all the same, so where another book qualifies only
     *                 with the " (1)" read as a copy too, the name does not say which of them it is. As {@link
     *                 Accepted}, since one may qualify so only with its stored author names read apart
     * @param refusedAsStored for the books that qualify, or are doubted, only with their stored author names read
     *                 apart, what those names each read as one person say against them: the file names another author.
     *                 The rules before the weak ones turned the book down so, and their evidence still counts
     * @param copiesAsStored the books found with the " (1)" read as a copy by their authors as stored that {@link
     *                 #accepted} holds as found by the name with their names read apart, the better reading: before
     *                 the names were read apart they were found as a copy, which still decides first
     */
    private record Findings(Map<Long, Accepted> accepted, Map<Long, Rejection> rejected, Map<Long, Accepted> doubted,
                            Map<Long, Rejection> refusedAsStored, Map<Long, Accepted> copiesAsStored) {

        Findings() {
            this(new LinkedHashMap<>(), new LinkedHashMap<>(), new LinkedHashMap<>(), new LinkedHashMap<>(),
                    new LinkedHashMap<>());
        }

        /**
         * Why each book was turned down as the rules before the weak ones have it: {@link #rejected}, and what the
         * names as stored say against a book that qualifies, or is doubted, only with them read apart.
         */
        Map<Long, Rejection> rejectedAsStored() {
            Map<Long, Rejection> asStored = new LinkedHashMap<>(rejected);
            refusedAsStored.forEach((bookId, rejection) -> {
                Accepted a = accepted.get(bookId);
                if (a == null || a.byAuthorReading()) {
                    asStored.merge(bookId, rejection, Rejection::weightier);
                }
            });
            return asStored;
        }
    }

    private static void consider(FileEvidence file, TitleKey reading, boolean confirmed, Integer seriesNumber,
                                 FilelessShellIndex index, Findings findings) {
        Evidence evidence = Evidence.of(file, reading, confirmed);
        if (evidence == null) {
            return;
        }
        List<ShellCandidate> found = index.lookup(reading.identity());
        for (ShellCandidate shell : found) {
            judge(file, reading, evidence, seriesNumber, shell, false, findings);
        }
        if (readsAsACopy(reading)) {
            Set<Long> foundByNumber = found.stream().map(ShellCandidate::bookId).collect(Collectors.toSet());
            for (ShellCandidate shell : index.lookup(reading.identityWithoutCopy())) {
                if (!foundByNumber.contains(shell.bookId())) {
                    judge(file, reading, evidence, seriesNumber, shell, true, findings);
                }
            }
        }
    }

    /**
     * Whether the name also reads without its copy marker: " (1)", as a browser names a second download of
     * "Title.epub", and so "Title (1).epub" may be "Title" as well as its first volume. Not " (2)" to " (9)", which
     * stay volume numbers, nor a comic's " (1)", which is an issue.
     */
    private static boolean readsAsACopy(TitleKey reading) {
        TitleKey.CopyMarker copy = reading.copy();
        return copy != null && Integer.valueOf(1).equals(copy.number()) && !copy.issue()
                && !reading.identityWithoutCopy().isEmpty() && !reading.identityWithoutCopy().equals(reading.identity());
    }

    /**
     * Accepts the fileless book for this reading or notes why not.
     *
     * @param asCopy whether the book was found only under the name without its " (1)"
     */
    private static void judge(FileEvidence file, TitleKey reading, Evidence evidence, Integer seriesNumber,
                              ShellCandidate shell, boolean asCopy, Findings findings) {
        Accepted already = findings.accepted().get(shell.bookId());
        if (already != null && (already.strong() || asCopy && !already.byAuthorReading())) {
            // Accepted already, by a reading at least as good
            return;
        }
        Rejection earlier = findings.rejected().get(shell.bookId());
        if (asCopy && earlier != null && earlier.refuses()) {
            // The name as it reads says it is not this book ("01 - Survival (1).epub" is number 1, and "Survival:
            // Book 1" has no number), and no reading of its " (1)" as a copy makes it so
            return;
        }
        Rejection rejection = rejection(file, reading, evidence, seriesNumber, shell, shell.authors());
        Rejection asStored = null;
        boolean byAuthorReading = false;
        if (!shell.identitiesAsStored().contains(asCopy ? reading.identityWithoutCopy() : reading.identity())) {
            // Only its title read with the names apart is the name ("Child of a Dead God - John Hendee" by "Barb
            // Hendee; John Hendee" read as "Child of a Dead God"): before, the name did not find the book at all, so
            // whatever the names say, it qualifies only so, and nothing said here is evidence against it
            byAuthorReading = true;
            if (rejection != null) {
                rejection = new Rejection(rejection.why(), false);
            }
        } else if (shell.readsAuthorsApart()) {
            asStored = rejection(file, reading, evidence, seriesNumber, shell, shell.authorsAsStored());
            if (rejection == null) {
                // "Arthur C. Clarke;Gentry Lee" read as one Lee did not say the file is the book; read apart it does
                byAuthorReading = asStored != null;
            } else if (asStored == null) {
                // Each stored name read as one person said it is the book: reading them apart never takes that away
                rejection = null;
            } else {
                // Both readings turn it down: what the names as stored say decides, as before they were read apart.
                // Read apart they neither add evidence against the book nor take away what the names as stored said
                rejection = asStored;
            }
        }
        if (rejection != null && asCopy) {
            rejection = rejection.readAsCopy();
        }
        if (rejection == null && byAuthorReading && asStored != null && asStored.against()) {
            // What the names as stored said against the book still counts where the other rules alone decide
            Rejection said = new Rejection(label(shell) + " " + asStored.why(), true);
            findings.refusedAsStored().merge(shell.bookId(), asCopy ? said.readAsCopy() : said, Rejection::weightier);
        }
        if (rejection == null && asCopy && seriesNumber == null && reading.numbers().isEmpty()) {
            // "Saga (1).epub" may as well be volume 1 as a copy of "Saga.epub"; only a leading series number or
            // another number in the name would say which
            rejection = anotherVolumeThanOne(shell, reading);
            if (rejection != null && already == null) {
                findings.doubted().merge(shell.bookId(), new Accepted(shell, true, byAuthorReading), Accepted::stronger);
            }
        }
        if (rejection == null) {
            Accepted accepted = new Accepted(shell, asCopy, byAuthorReading);
            // A book found both by the name with its names read apart and as a copy by them as stored was found the
            // latter way before they were read apart: keep that, for the copy reading by the names as stored
            Accepted weaker = already == null ? null : accepted.strength() <= already.strength() ? accepted : already;
            if (weaker != null && weaker.asCopy() && !weaker.byAuthorReading()) {
                findings.copiesAsStored().putIfAbsent(shell.bookId(), weaker);
            }
            if (already != null && accepted.strength() <= already.strength()) {
                // Accepted already, by another reading as good
                return;
            }
            // A book accepted as a copy before and now by the name as it reads takes its place among the latter in
            // the order they were found, as it would without the copy reading
            findings.accepted().remove(shell.bookId());
            findings.accepted().put(shell.bookId(), accepted);
            findings.rejected().remove(shell.bookId());
            findings.doubted().remove(shell.bookId());
        } else if (already == null || already.asCopy() && rejection.refuses()) {
            // What the name as it reads says against a book outweighs its reading as a copy of it, accepted or
            // doubted, as the rules before that reading had it
            if (rejection.refuses()) {
                findings.accepted().remove(shell.bookId());
                findings.doubted().remove(shell.bookId());
            }
            Rejection labelled = new Rejection(label(shell) + " " + rejection.why(), rejection.against(), rejection.asCopy());
            // Evidence against the book from one reading outweighs a want of evidence from another
            findings.rejected().merge(shell.bookId(), labelled, Rejection::weightier);
        }
    }

    /**
     * Why the book is no volume 1, which a " (1)" may mean: its place in its series is another, fractions included, and
     * the name may call that series what the name without its " (1)" says; or its titles name volumes after a series
     * keyword and none of them is 1 ("Saga: Volume Two", "The Walking Dead: Vol. 2: Miles Behind Us"). The name may
     * call the series so when it has no name, or a name that reads as the name without its " (1)" ({@link
     * ShellCandidate#seriesIdentities}, which holds the series of a "(Series, #N)" suffix giving the book's place
     * too): "Saga (1).epub" may be volume 1 of "Saga", of the "Saga Series", or of the series "Saga (Saga, #2)" names
     * whatever its series name, but "The Testaments (1).epub" is no volume of The Handmaid's Tale. A bare number says
     * nothing: "World War II" or "1936" in a subtitle is what a book is about. Null when nothing says so. Never
     * evidence against the book, of which the file may be a copy all the same: so it only keeps the file from that
     * book when no other book qualifies, and makes the file ambiguous when one does as a copy too. A book the name
     * reads as without the copy reading goes before both.
     */
    private static Rejection anotherVolumeThanOne(ShellCandidate shell, TitleKey reading) {
        if (shell.seriesPlace() != null && shell.seriesPlace() != 1f) {
            String place = "is number " + number(shell.seriesPlace());
            if (shell.seriesIdentities().isEmpty()) {
                return new Rejection(place + " of its series, and \" (1)\" may mean volume 1", false, true);
            }
            if (shell.seriesIdentities().contains(reading.identityWithoutCopy())) {
                return new Rejection(place + " of a series of the same name, and \" (1)\" may mean volume 1", false, true);
            }
        }
        if (!shell.volumeNumbers().isEmpty() && !shell.volumeNumbers().contains("1")) {
            return new Rejection("names volume " + String.join(", ", shell.volumeNumbers())
                    + " in its title or subtitle, and \" (1)\" may mean volume 1", false, true);
        }
        return null;
    }

    /** 2 for 2.0, 2.5 for 2.5. */
    private static String number(float value) {
        return value == Math.rint(value) && Math.abs(value) < 1e9f ? String.valueOf((long) value) : String.valueOf(value);
    }

    /**
     * The book to give the file when every fileless book that qualifies is alike: the same titles, the same series
     * number and the same authors. They are one book entered twice, or the record of a paper copy next to the record
     * of the ebook, and the file is either one's. A book that is not physical goes first, since an ebook file belongs
     * with the record whose file was lost, then the lowest id. Null when one of them differs from the others, or they
     * have no authors to tell them by. Series numbers are compared as given: 1.5 and 2.5, or 0.5 and none, are two
     * places in a series, although neither is a whole number. Years in brackets, which no identity holds, are compared
     * too: "Rick Steves Italy (2020)" and "Rick Steves Italy (2023)" are two editions, and "(2020)" and none may be.
     * Titles and authors are alike as stored, each stored author name read as one person, or with the names read
     * apart: "J.R.R. Tolkien; Christopher Tolkien" and "J.R.R. Tolkien / Christopher Tolkien" are one Tolkien as
     * stored, and "Barb Hendee; J. C. Hendee" and the rows "Barb Hendee" and "J.C. Hendee" two Hendees read apart.
     */
    private static Accepted oneOfAlike(Collection<Accepted> qualifying) {
        ShellCandidate first = qualifying.iterator().next().shell();
        for (Accepted next : qualifying) {
            ShellCandidate shell = next.shell();
            if (!shell.years().equals(first.years()) || !Objects.equals(shell.seriesNumber(), first.seriesNumber())
                    || !Objects.equals(shell.seriesPlace(), first.seriesPlace())) {
                return null;
            }
        }
        if (!alikeAsStored(qualifying)
                && !alike(qualifying, ShellCandidate::identities, ShellCandidate::authors)) {
            return null;
        }
        return qualifying.stream()
                .min(Comparator.comparing((Accepted a) -> a.shell().physical()).thenComparingLong(a -> a.shell().bookId()))
                .orElseThrow();
    }

    /** Whether the books are alike by their titles and authors as stored, as the choice among alike books was before. */
    private static boolean alikeAsStored(Collection<Accepted> qualifying) {
        return alike(qualifying, ShellCandidate::identitiesAsStored, ShellCandidate::authorsAsStored);
    }

    /** Whether every book has the same titles and the same authors, read so; books without authors are never alike. */
    private static boolean alike(Collection<Accepted> qualifying, Function<ShellCandidate, Set<String>> identitiesOf,
                                 Function<ShellCandidate, List<PersonName>> authorsOf) {
        ShellCandidate first = qualifying.iterator().next().shell();
        Set<PersonName> authors = Set.copyOf(authorsOf.apply(first));
        return !authors.isEmpty() && qualifying.stream().map(Accepted::shell).allMatch(shell ->
                identitiesOf.apply(shell).equals(identitiesOf.apply(first))
                        && Set.copyOf(authorsOf.apply(shell)).equals(authors));
    }

    private static String agreement(Accepted accepted) {
        return "title and author agree with " + label(accepted.shell()) + (accepted.asCopy() ? COPY_READING : "")
                + (accepted.byAuthorReading() ? AUTHOR_READING : "");
    }

    /**
     * Why the fileless book is not this file's book, or null when it is.
     *
     * @param authors the book's authors to check the name against: {@link ShellCandidate#authors}, or {@link
     *                ShellCandidate#authorsAsStored}
     */
    private static Rejection rejection(FileEvidence file, TitleKey reading, Evidence evidence, Integer seriesNumber,
                                       ShellCandidate shell, List<PersonName> authors) {
        if (shell.libraryPathId() != null && !shell.libraryPathId().equals(file.libraryPathId())) {
            return new Rejection("is in another library path", true);
        }
        if (seriesNumber != null && !seriesNumber.equals(shell.seriesNumber())) {
            return new Rejection("is not number " + seriesNumber + " of its series", true);
        }
        if (authors.isEmpty()) {
            if (!shell.physical()) {
                return new Rejection("has no author to check the file against", false);
            }
            // Nothing to confirm an author part of the name against: only a part the folders vouch for may go
            return evidence.confirmed() ? null : new Rejection("has no author that '" + reading.droppedName() + "' could be", false);
        }
        if (!evidence.claim().isEmpty() && !agreesWithGivenName(evidence.claim(), authors)) {
            // Only a part the folders or its initials vouch for says who the file is by
            return new Rejection("is not by '" + reading.droppedName() + "'", evidence.confirmed());
        }
        List<PersonName> names = new ArrayList<>(file.context().folderNames());
        names.addAll(evidence.claim());
        boolean oneWord = !reading.title().contains(" ");
        if (!agreesWithGivenName(names, authors)) {
            return new Rejection("has none of its authors in the path", false);
        }
        if (oneWord && !agreesWithSameGivenName(names, authors)) {
            return new Rejection("has a one-word title, which needs its author's given name in the path as the book has it", false);
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
     * @param nameKey   {@link FileEvidence#fileNameKey}: files of one format and one name key are copies, or parts,
     *                  of one file
     * @param weak      whether only weak matches ({@link Match#weak}) give the file this book: its own, or those of
     *                  the files it would be one book with when none of them has a strong match. A file that goes with
     *                  a strong match of its group claims the book as strongly, whatever its own match
     * @param readApart for a weak claim, whether only the stored author names read apart give the file this book
     *                  ({@link Match#readApart}), or a group the rules before that reading turned down from it: such a
     *                  claim yields to the weak claims of those rules as well as to strong ones
     */
    public record Claim<F>(F file, String fileName, long bookId, String folderKey, String format, String nameKey,
                           boolean weak, boolean readApart) {

        /** A strong claim. */
        public Claim(F file, String fileName, long bookId, String folderKey, String format, String nameKey) {
            this(file, fileName, bookId, folderKey, format, nameKey, false, false);
        }

        /** A claim by the rules before the stored author names were read apart. */
        public Claim(F file, String fileName, long bookId, String folderKey, String format, String nameKey, boolean weak) {
            this(file, fileName, bookId, folderKey, format, nameKey, weak, false);
        }

        /** Strong (2), weak (1), weak only with the stored author names read apart (0), as {@link Match#rank}. */
        public int rank() {
            return !weak ? 2 : readApart ? 0 : 1;
        }
    }

    /**
     * @param accepted the files each fileless book gets, by book id
     * @param rejected why each fileless book that gets none was left alone, by book id
     * @param yielded  for each fileless book that gets the files of its strong claims only, the files of its weak
     *                 claims, which it does not get, and why; by book id
     */
    public record ClaimResolution<F>(Map<Long, List<F>> accepted, Map<Long, String> rejected, Map<Long, Yield<F>> yielded) {
    }

    /** The files whose weak claims on a fileless book yield to the strong claims of other files on it, and why. */
    public record Yield<F>(List<F> files, String why) {
        public Yield {
            files = List.copyOf(files);
        }
    }

    /**
     * Keeps a fileless book's claims only when they agree on one book: they all come from one folder, and no two of
     * them are different files of one format. Every file of a rescan is matched against the same fileless books, so
     * without the first rule one book would collect every "Survival.epub" of the library. Two different epubs are as
     * likely two books ("A Quiver Full of Arrows.epub" and "A Quiver Full of Arrows - Stories.epub" against a book
     * subtitled "Stories"; "King.epub" and "KING - Las Vegas Bad Boys.epub" in a folder "Las Vegas Bad Boys") as one,
     * and a fileless book is not the place to guess: such files become a new book, which can be merged by hand.
     * <p>
     * A weak claim ({@link Claim#weak}) never takes a book from strong ones: when a book's claims do not agree but its
     * strong claims alone do, the book gets the files of those, and the files of the weak claims become new books.
     * "A/Steve Alten/MEG/The MEG.epub" and the weak "A/Steve Alten/MEG (1).epub" claim a fileless "MEG" from two
     * folders, and "The MEG.epub" gets it, as it would without the weak rules. Strong claims that do not agree among
     * themselves leave the book alone, weak ones or not, and so do weak claims without a strong one.
     * <p>
     * A weak claim that only the stored author names read apart make ({@link Claim#readApart}) yields to every other
     * claim, weak ones included: where the claims the rules before that reading make agree among themselves, the book
     * gets theirs, as it did then, and those other files become new books. Only where none of those claims is left do
     * such claims on their own get a book, and only when they agree.
     */
    public static <F> ClaimResolution<F> resolveShellClaims(List<Claim<F>> claims) {
        Map<Long, List<Claim<F>>> byBook = new LinkedHashMap<>();
        for (Claim<F> claim : claims) {
            byBook.computeIfAbsent(claim.bookId(), k -> new ArrayList<>()).add(claim);
        }
        Map<Long, List<F>> accepted = new LinkedHashMap<>();
        Map<Long, String> rejected = new LinkedHashMap<>();
        Map<Long, Yield<F>> yielded = new LinkedHashMap<>();
        byBook.forEach((bookId, bookClaims) -> {
            String disagreement = disagreement(bookClaims);
            if (disagreement == null) {
                accepted.put(bookId, files(bookClaims));
                return;
            }
            // The claims of the rules before the names were read apart, then the strong ones alone, as before
            for (int rank = 1; rank <= 2; rank++) {
                int least = rank;
                List<Claim<F>> stronger = bookClaims.stream().filter(claim -> claim.rank() >= least).toList();
                if (!stronger.isEmpty() && disagreement(stronger) == null) {
                    List<Claim<F>> weak = bookClaims.stream().filter(claim -> claim.rank() < least).toList();
                    accepted.put(bookId, files(stronger));
                    yielded.put(bookId, new Yield<>(files(weak), disagreement + "; the weak claims of " + names(weak)
                            + " yield to the others"));
                    return;
                }
            }
            rejected.put(bookId, disagreement);
        });
        return new ClaimResolution<>(accepted, rejected, yielded);
    }

    /** Why the claims do not agree on one book, or null when they do. */
    private static String disagreement(List<? extends Claim<?>> claims) {
        Set<String> folders = new TreeSet<>();
        Map<String, Set<String>> namesByFormat = new HashMap<>();
        for (Claim<?> claim : claims) {
            folders.add(claim.folderKey());
            namesByFormat.computeIfAbsent(claim.format(), k -> new LinkedHashSet<>()).add(claim.nameKey());
        }
        if (folders.size() > 1) {
            return "claimed by files of " + folders.size() + " folders " + folders + ": " + names(claims);
        }
        if (namesByFormat.values().stream().anyMatch(names -> names.size() > 1)) {
            return "claimed by different files of one format: " + names(claims);
        }
        return null;
    }

    private static <F> List<F> files(List<Claim<F>> claims) {
        return claims.stream().map(Claim::file).toList();
    }

    private static String names(List<? extends Claim<?>> claims) {
        return claims.stream().map(Claim::fileName).collect(Collectors.joining(", "));
    }
}
