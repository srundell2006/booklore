package org.booklore.tools;

import org.booklore.model.dto.settings.LibraryFile;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.entity.LibraryPathEntity;
import org.booklore.model.enums.BookFileExtension;
import org.booklore.model.enums.BookFileType;
import org.booklore.model.enums.LibraryOrganizationMode;
import org.booklore.repository.BookRepository;
import org.booklore.repository.projection.BookAuthorRow;
import org.booklore.repository.projection.BookFileScanRow;
import org.booklore.repository.projection.FilelessBookRow;
import org.booklore.service.library.BookGroupingService;
import org.booklore.service.library.BookGroupingService.Attachment;
import org.booklore.service.library.BookGroupingService.DecisionListener;
import org.booklore.service.library.BookGroupingService.GroupingResult;
import org.booklore.service.library.FilelessShells;
import org.booklore.service.library.LibraryBookFiles;
import org.booklore.service.library.matching.ExistingBookMatcher.Ambiguous;
import org.booklore.service.library.matching.ExistingBookMatcher.Decision;
import org.booklore.service.library.matching.ExistingBookMatcher.Kind;
import org.booklore.service.library.matching.ExistingBookMatcher.Match;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Says what a rescan would do with new files, without a database or the files themselves: which existing book each
 * file would be attached to, and why, or which new book it would become, and why not an existing one. It reads three
 * tab-separated exports of one library and runs them through the rescan's own grouping, {@link BookGroupingService}
 * with {@link FilelessShells}, {@code ExistingBookMatcher} and {@code BookFileGroupingUtils}, so it decides exactly as
 * {@code LibraryProcessingService.rescanLibrary} would. Nothing is written anywhere but the report. It lives with the
 * tests so that it is not part of the application's jar.
 * <p>
 * <b>Running it</b>, from {@code booklore-api}:
 * <pre>
 *   ./gradlew dryRunRescan --args='--dir /path/to/exports [--mode AUTO_DETECT] [--out report.tsv]'
 * </pre>
 * {@code --dir} is a folder holding {@code shells.tsv}, {@code existing.tsv} and {@code newfiles.tsv}; each can also be
 * given on its own with {@code --shells}, {@code --existing} and {@code --newfiles}. {@code --mode} is the library's
 * organization mode, AUTO_DETECT unless given. The report goes to standard output unless {@code --out} names a file.
 * <p>
 * <b>The exports</b>, tab-separated, one row per line, as {@code mariadb -B -N} writes them: NULL as {@code NULL},
 * and a tab, newline or backslash inside a value as {@code \t}, {@code \n} or {@code \\}. A first line naming the
 * columns is skipped. The SQL that writes them from MariaDB is in {@code README.md} next to this class.
 * <ul>
 *   <li>{@code shells.tsv}: the library's books without any book_file row, not deleted: id, library_path_id,
 *       is_physical, has_cover, title, subtitle, series_number, authors (each ending with the unit separator, 0x1F), and
 *       optionally series_name. Without it, a book's series has no name unless its title has a "(Series, #N)"
 *       suffix, and the rule for a lone " (1)" turns down more books than the rescan would.</li>
 *   <li>{@code existing.tsv}: the book formats of the library's books that are not deleted: book_id, library_path_id,
 *       file_sub_path, file_name, book_type, and optionally the book's authors, each ending with the unit separator.
 *       Without authors, same-folder matching cannot drop an author part of a name that only the book's authors
 *       vouch for.</li>
 *   <li>{@code newfiles.tsv}: the files the rescan would find new: library_path_id, file_sub_path, file_name. A name
 *       without a book extension is taken for a folder audiobook, as the walk reports one.</li>
 * </ul>
 * <p>
 * <b>The report</b> has one line per new file: decision, target, group key, reason, library_path_id, file_sub_path,
 * file_name and weak_match. The decision is one of
 * <ul>
 *   <li>{@code attach-shell}: attached to the fileless book {@code target};</li>
 *   <li>{@code attach-same-folder}: attached to {@code target}, a book with files in the file's folder;</li>
 *   <li>{@code ambiguous}: becomes a new book because several books qualify ({@code target} lists them), a fileless
 *       book's claims were rejected, the files it would be one book with matched different books, the book they
 *       matched turned one of them down, or its weak claim on a fileless book yields to the strong claims of other
 *       files, which get the book;</li>
 *   <li>{@code new-book}: becomes a new book, matching no existing one.</li>
 * </ul>
 * A weak match ({@code ExistingBookMatcher.Match.weak}: one of alike fileless books, or a " (1)" read as a copy)
 * yields to the strong matches of the files it would be one book with: such a file is attached with them, and its
 * reason says that its own weak match yields to theirs. A reason that turns a book down with the " (1)" read as a copy
 * says so, {@code (" (1)" read as a copy)}: that keeps the file's group only from a book that weak matches alone give
 * it, never from one a strong match gives it. So the reason does not tell the attaches only the newer rules make from
 * the others; weak_match does: what came of a weak match for the file, its own or, for a file without one, those its
 * group's book came from alone, as the summary counts it, or {@code -} when no weak match had a part in its decision.
 * The outcomes that start with {@code attached} are those attaches.
 * <p>
 * The group key is the new book the file would be part of, {@code <library_path_id>:<sub path>:<key>}; files attached
 * to an existing book show the group they would have formed. Lines starting with {@code #} close the report: the
 * count of each decision; for files that become new books, the count of each reason a book was turned down, with
 * book ids, numbers and quoted names taken out so that alike reasons count together; and for files whose decision a
 * weak match had a part in, the count of each outcome.
 */
public final class RescanDryRun {

    static final long LIBRARY_ID = 1L;

    /**
     * How the matcher's reason for a match by the name as it reads names the books found only with its " (1)" read as
     * a copy, which yield to it: "...; book#2 "Nico" qualifies only if " (1)" is a copy, and yields".
     */
    private static final String YIELDING_AS_COPY = "only if \" (1)\" is a copy, and yield";

    private RescanDryRun() {
    }

    public static void main(String[] args) throws IOException {
        Map<String, String> options = options(args);
        Path dir = options.containsKey("dir") ? Path.of(options.get("dir")) : null;
        Path shells = path(options, "shells", dir, "shells.tsv");
        Path existing = path(options, "existing", dir, "existing.tsv");
        Path newFiles = path(options, "newfiles", dir, "newfiles.tsv");
        LibraryOrganizationMode mode = LibraryOrganizationMode.valueOf(options.getOrDefault("mode", "AUTO_DETECT"));

        // The grouping logs every decision at debug; the report says it all, once
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME))
                .setLevel(ch.qos.logback.classic.Level.WARN);

        Report report = run(Exports.read(shells, existing, newFiles), mode);
        if (options.containsKey("out")) {
            try (PrintStream out = new PrintStream(Files.newOutputStream(Path.of(options.get("out"))), false, StandardCharsets.UTF_8)) {
                report.print(out);
            }
            System.err.println("Wrote " + report.lines().size() + " lines to " + options.get("out"));
            report.printSummary(System.err);
        } else {
            PrintStream out = new PrintStream(System.out, false, StandardCharsets.UTF_8);
            report.print(out);
            out.flush();
        }
    }

    private static Map<String, String> options(String[] args) {
        Map<String, String> options = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (!args[i].startsWith("--") || i + 1 >= args.length) {
                throw new IllegalArgumentException("Expected --name value pairs, got " + Arrays.toString(args)
                        + "; see the header of " + RescanDryRun.class.getName());
            }
            options.put(args[i].substring(2), args[++i]);
        }
        return options;
    }

    private static Path path(Map<String, String> options, String name, Path dir, String defaultName) {
        if (options.containsKey(name)) {
            return Path.of(options.get(name));
        }
        if (dir == null) {
            throw new IllegalArgumentException("Give --dir, or --" + name);
        }
        return dir.resolve(defaultName);
    }

    // ========== running the rescan's grouping ==========

    /** Groups the new files as a rescan of a library holding the exported books would, and says why. */
    public static Report run(Exports exports, LibraryOrganizationMode mode) {
        LibraryEntity library = LibraryEntity.builder().id(LIBRARY_ID).name("dry run").organizationMode(mode)
                .libraryPaths(new ArrayList<>()).build();
        Map<Long, LibraryPathEntity> paths = new TreeMap<>();
        List<LibraryFile> newFiles = new ArrayList<>();
        Map<LibraryFile, NewFile> rowOf = new IdentityHashMap<>();
        for (NewFile row : exports.newFiles()) {
            LibraryPathEntity path = paths.computeIfAbsent(row.libraryPathId(), id -> {
                LibraryPathEntity created = LibraryPathEntity.builder().id(id).library(library).path("/library-path-" + id).build();
                library.getLibraryPaths().add(created);
                return created;
            });
            BookFileType type = BookFileExtension.fromFileName(row.fileName()).map(BookFileExtension::getType).orElse(null);
            LibraryFile file = LibraryFile.builder()
                    .libraryEntity(library)
                    .libraryPathEntity(path)
                    .fileSubPath(row.fileSubPath())
                    .fileName(row.fileName())
                    .bookFileType(type == null ? BookFileType.AUDIOBOOK : type)
                    .folderBased(type == null)
                    .build();
            newFiles.add(file);
            rowOf.put(file, row);
        }

        BookRepository books = readOnlyRepository(exports);
        BookGroupingService grouping = new BookGroupingService(books, new FilelessShells(books));
        Trace trace = new Trace();
        GroupingResult result = grouping.groupForRescan(newFiles, library, exports.bookFiles(), trace);

        // The new book each file would be part of, from the same grouping of all new files the rescan starts from
        Map<LibraryFile, String> groupKeys = new IdentityHashMap<>();
        grouping.groupForInitialScan(newFiles, library).forEach((key, group) -> group.forEach(file -> groupKeys.put(file, key)));
        Map<LibraryFile, Attachment> attachments = new IdentityHashMap<>();
        for (Attachment attachment : result.filesToAttach()) {
            attachment.files().forEach(file -> attachments.put(file, attachment));
        }

        Map<String, List<LibraryFile>> groups = new LinkedHashMap<>();
        newFiles.forEach(file -> groups.computeIfAbsent(groupKeys.get(file), key -> new ArrayList<>()).add(file));

        List<Line> lines = new ArrayList<>();
        Map<String, Integer> decisions = new TreeMap<>();
        Map<String, Integer> rejections = new TreeMap<>();
        Map<String, Integer> weakMatches = new TreeMap<>();
        for (LibraryFile file : newFiles) {
            String weak = weakOutcome(file, attachments.get(file), groups.get(groupKeys.get(file)), trace);
            Line line = line(file, rowOf.get(file), groupKeys.get(file), attachments.get(file), trace).withWeakMatch(weak);
            lines.add(line);
            decisions.merge(line.decision(), 1, Integer::sum);
            if (line.decision().equals("new-book") || line.decision().equals("ambiguous")) {
                trace.rejections(file).forEach(reason -> rejections.merge(reason, 1, Integer::sum));
            }
            if (weak != null) {
                weakMatches.merge(weak, 1, Integer::sum);
            }
        }
        return new Report(lines, decisions, rejections, weakMatches);
    }

    /**
     * What came of a weak match ({@code Match.weak}: one of alike fileless books, or a " (1)" read as a copy) for the
     * file: its own, or, for a file without one, those that alone gave its group a book, which it follows to that book,
     * or into a claim that yields, a split, a refusal or rejected claims; or of the books only a " (1)" read as a copy
     * found, where a match by the name as it reads went before them. The outcomes that start with "attached" are the
     * attaches only the newer rules make. Null when no weak match had a part in the file's decision.
     */
    private static String weakOutcome(LibraryFile file, Attachment attachment, List<LibraryFile> group, Trace trace) {
        Decision fileless = trace.fileless.get(file);
        if (fileless instanceof Match own && !own.weak() && own.reason().contains(YIELDING_AS_COPY)) {
            return "a match by its name as it reads goes before books found with \" (1)\" read as a copy";
        }
        Match weak = fileless instanceof Match own && own.weak() ? own : null;
        if (attachment != null) {
            boolean strong = group.stream().anyMatch(other -> isStrongMatch(trace.inFolder.get(other), attachment.bookId())
                    || isStrongMatch(trace.fileless.get(other), attachment.bookId()));
            if (!strong) {
                return weak != null ? "attached by its own weak match" : "attached by a weak match of the files it would be one book with";
            }
            if (weak == null) {
                return null;
            }
            return weak.bookId() == attachment.bookId()
                    ? "its weak match agrees with the strong match of the files it would be one book with"
                    : "its weak match yields to the strong match of the files it would be one book with";
        }
        if (trace.yieldedClaims.containsKey(file)) {
            return weak != null
                    ? "its weak match yields to the strong claims of other files on its book"
                    : "follows a weak match that yields to the strong claims of other files on its book";
        }
        if (weak != null) {
            return "its weak match comes to nothing: its group is split or turned down, or its book's claims rejected";
        }
        // Without a match of its own, a file of a group that only weak matches gave a book followed them, there to be
        // split, turned down or rejected with them; where a strong match gave its group the book, theirs had no part
        return weakMatchesAlone(group, trace)
                ? "follows a weak match that comes to nothing: its group is split or turned down, or its book's claims rejected"
                : null;
    }

    private static boolean isStrongMatch(Decision decision, long bookId) {
        return decision instanceof Match match && !match.weak() && match.bookId() == bookId;
    }

    /**
     * Whether the group's book, or the books it is split over, came from weak matches alone: some file of it has one,
     * and none has a strong match.
     */
    private static boolean weakMatchesAlone(List<LibraryFile> group, Trace trace) {
        boolean anyWeak = group.stream().anyMatch(file -> trace.fileless.get(file) instanceof Match match && match.weak());
        boolean anyStrong = group.stream().anyMatch(file -> trace.inFolder.get(file) instanceof Match inFolder && !inFolder.weak()
                || trace.fileless.get(file) instanceof Match fileless && !fileless.weak());
        return anyWeak && !anyStrong;
    }

    private static Line line(LibraryFile file, NewFile row, String groupKey, Attachment attachment, Trace trace) {
        Decision inFolder = trace.inFolder.get(file);
        Decision fileless = trace.fileless.get(file);
        if (attachment != null) {
            String decision = attachment.kind() == Kind.FILELESS ? "attach-shell" : "attach-same-folder";
            Decision own = attachment.kind() == Kind.FILELESS ? fileless : inFolder;
            String reason;
            if (own instanceof Match match && match.bookId() == attachment.bookId()) {
                reason = match.reason();
            } else if (fileless instanceof Match weak && weak.weak()) {
                reason = "goes with the files it would be one book with; its own weak match yields to theirs: " + weak.reason();
            } else {
                reason = "goes with the files it would be one book with";
            }
            return new Line(decision, String.valueOf(attachment.bookId()), groupKey, reason, row);
        }
        if (trace.split.containsKey(file)) {
            return new Line("ambiguous", ids(trace.split.get(file)), groupKey,
                    "the files it would be one book with match different books", row);
        }
        if (trace.refusedGroups.containsKey(file)) {
            return new Line("ambiguous", String.valueOf(trace.refusedGroups.get(file)), groupKey,
                    "book#" + trace.refusedGroups.get(file) + " matched a file it would be one book with, and turned another of them down", row);
        }
        if (trace.rejectedClaims.containsKey(file)) {
            RejectedClaim claim = trace.rejectedClaims.get(file);
            return new Line("ambiguous", String.valueOf(claim.bookId()), groupKey,
                    "fileless book#" + claim.bookId() + " is " + claim.why(), row);
        }
        if (trace.yieldedClaims.containsKey(file)) {
            RejectedClaim claim = trace.yieldedClaims.get(file);
            return new Line("ambiguous", String.valueOf(claim.bookId()), groupKey, "its weak claim on fileless book#"
                    + claim.bookId() + " yields to the strong claims of other files, which get it: " + claim.why(), row);
        }
        if (inFolder instanceof Ambiguous ambiguous) {
            return new Line("ambiguous", ids(ambiguous.bookIds()), groupKey, "folder: " + ambiguous.reason(), row);
        }
        if (fileless instanceof Ambiguous ambiguous) {
            return new Line("ambiguous", ids(ambiguous.bookIds()), groupKey, "fileless: " + ambiguous.reason(), row);
        }
        List<String> why = new ArrayList<>();
        if (inFolder != null) {
            why.add("folder: " + inFolder.reason());
        }
        if (fileless != null) {
            why.add("fileless: " + fileless.reason());
        }
        return new Line("new-book", "-", groupKey, why.isEmpty() ? "-" : String.join(" | ", why), row);
    }

    private static String ids(Collection<Long> ids) {
        return ids.stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    /** A BookRepository that answers the two reads grouping makes from the exports, and refuses anything else. */
    private static BookRepository readOnlyRepository(Exports exports) {
        return (BookRepository) Proxy.newProxyInstance(BookRepository.class.getClassLoader(), new Class<?>[]{BookRepository.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "findFilelessBookRowsByLibraryId" -> exports.filelessRows();
                    case "findAuthorNamesByBookIds" -> {
                        @SuppressWarnings("unchecked")
                        Collection<Long> ids = (Collection<Long>) args[0];
                        yield exports.authorRows(ids);
                    }
                    case "toString" -> "the dry run's BookRepository";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(
                            "Rescan grouping asked for " + method.getName() + ", which the dry run has no export of");
                });
    }

    /** What the grouping said about each file on the way. */
    private static final class Trace implements DecisionListener {

        private static final Pattern BOOK = Pattern.compile("book#\\d+( \"[^\"]*\")?");
        private static final Pattern QUOTED = Pattern.compile("'[^']*'");
        /** A number, a fraction or a list of them ("2", "2.5", "2, 3"): one particular. */
        private static final Pattern NUMBER = Pattern.compile("\\d+(?:\\.\\d+)?(?:, \\d+(?:\\.\\d+)?)*");
        /** A reason's own words that hold a number, which is no particular: a " (1)" is always a " (1)". */
        private static final Pattern OWN_NUMBERS = Pattern.compile("\" \\(1\\)\" (?:may mean volume 1|read as a copy)");
        private static final Pattern ID_LIST = Pattern.compile("\\[[^\\]]*]");

        final Map<LibraryFile, Decision> inFolder = new IdentityHashMap<>();
        final Map<LibraryFile, Decision> fileless = new IdentityHashMap<>();
        final Map<LibraryFile, RejectedClaim> rejectedClaims = new IdentityHashMap<>();
        final Map<LibraryFile, RejectedClaim> yieldedClaims = new IdentityHashMap<>();
        final Map<LibraryFile, Set<Long>> split = new IdentityHashMap<>();
        final Map<LibraryFile, Long> refusedGroups = new IdentityHashMap<>();

        @Override
        public void inFolder(LibraryFile file, Decision decision) {
            inFolder.put(file, decision);
        }

        @Override
        public void fileless(LibraryFile file, Decision decision) {
            fileless.put(file, decision);
        }

        @Override
        public void claimsRejected(long bookId, List<LibraryFile> files, String why) {
            files.forEach(file -> rejectedClaims.put(file, new RejectedClaim(bookId, why)));
        }

        @Override
        public void claimsYielded(long bookId, List<LibraryFile> files, String why) {
            files.forEach(file -> yieldedClaims.put(file, new RejectedClaim(bookId, why)));
        }

        @Override
        public void groupSplit(List<LibraryFile> files, Set<Long> bookIds) {
            files.forEach(file -> split.put(file, bookIds));
        }

        @Override
        public void groupRefused(List<LibraryFile> files, long bookId, List<LibraryFile> refusing) {
            files.forEach(file -> refusedGroups.put(file, bookId));
        }

        /** Why the file got no existing book, each reason once and with its particulars taken out. */
        Set<String> rejections(LibraryFile file) {
            Set<String> reasons = new LinkedHashSet<>();
            if (split.containsKey(file)) {
                reasons.add("group: the files it would be one book with match different books");
            }
            if (refusedGroups.containsKey(file)) {
                reasons.add("group: the book a file it would be one book with matched turned another of them down");
            }
            if (rejectedClaims.containsKey(file)) {
                // The reason without the names of the files that made the claims
                reasons.add("claim: " + general(rejectedClaims.get(file).why()).replaceFirst(": .*", ""));
            }
            if (yieldedClaims.containsKey(file)) {
                reasons.add("claim: a weak claim yields to the strong claims of other files");
            }
            if (inFolder.get(file) instanceof Ambiguous ambiguous) {
                reasons.add("folder: " + general(ambiguous.reason()));
            }
            Decision shellDecision = fileless.get(file);
            if (shellDecision instanceof Ambiguous) {
                reasons.add("fileless: several fileless books qualify");
            } else if (shellDecision != null && !(shellDecision instanceof Match)) {
                for (String part : shellDecision.reason().split("; ")) {
                    reasons.add("fileless: " + general(part));
                }
            }
            return reasons;
        }

        private static String general(String reason) {
            String general = BOOK.matcher(reason).replaceAll("").trim();
            general = ID_LIST.matcher(general).replaceAll("[...]");
            general = QUOTED.matcher(general).replaceAll("'...'");
            StringBuilder out = new StringBuilder();
            Matcher own = OWN_NUMBERS.matcher(general);
            int last = 0;
            while (own.find()) {
                out.append(NUMBER.matcher(general.substring(last, own.start())).replaceAll("N")).append(own.group());
                last = own.end();
            }
            return out.append(NUMBER.matcher(general.substring(last)).replaceAll("N")).toString();
        }
    }

    private record RejectedClaim(long bookId, String why) {
    }

    // ========== the exports ==========

    /**
     * A fileless book of shells.tsv.
     *
     * @param seriesName the metadata's series name, or null when the export has none
     */
    public record Shell(long id, Long libraryPathId, boolean physical, boolean hasCover, String title, String subtitle,
                        Float seriesNumber, List<String> authors, String seriesName) {
    }

    /** A book format of existing.tsv. */
    public record ExistingFile(long bookId, long libraryPathId, String fileSubPath, String fileName, BookFileType bookType,
                               List<String> authors) {
    }

    /** A file of newfiles.tsv. */
    public record NewFile(long libraryPathId, String fileSubPath, String fileName) {
    }

    /** The three exports of one library. */
    public record Exports(List<Shell> shells, List<ExistingFile> existing, List<NewFile> newFiles) {

        public static Exports read(Path shells, Path existing, Path newFiles) throws IOException {
            return new Exports(
                    rows(shells, 8, 9).stream().map(Exports::shell).toList(),
                    rows(existing, 5, 6).stream().map(Exports::existingFile).toList(),
                    rows(newFiles, 3, 3).stream().map(Exports::newFile).toList());
        }

        /** The fileless books as BookRepository.findFilelessBookRowsByLibraryId gives them: one row per author. */
        List<FilelessBookRow> filelessRows() {
            List<FilelessBookRow> rows = new ArrayList<>();
            for (Shell shell : shells) {
                List<String> authors = shell.authors().isEmpty() ? java.util.Collections.singletonList(null) : shell.authors();
                for (String author : authors) {
                    rows.add(new FilelessBookRow(shell.id(), shell.libraryPathId(), shell.physical(),
                            shell.hasCover() ? "cover" : null, null, shell.title(), shell.subtitle(), shell.seriesName(),
                            shell.seriesNumber(), author));
                }
            }
            return rows;
        }

        List<BookAuthorRow> authorRows(Collection<Long> bookIds) {
            Set<Long> wanted = Set.copyOf(bookIds);
            Map<Long, List<String>> authors = new LinkedHashMap<>();
            existing.stream().filter(file -> wanted.contains(file.bookId()))
                    .forEach(file -> authors.putIfAbsent(file.bookId(), file.authors()));
            List<BookAuthorRow> rows = new ArrayList<>();
            authors.forEach((bookId, names) -> names.forEach(name -> rows.add(new BookAuthorRow(bookId, name))));
            return rows;
        }

        /** The existing books' files as the rescan reads them, every one a book format of a book not deleted. */
        LibraryBookFiles bookFiles() {
            List<BookFileScanRow> rows = new ArrayList<>(existing.size());
            long id = 0;
            for (ExistingFile file : existing) {
                // As for a new file, a name without a book extension is a folder audiobook
                boolean folderBased = BookFileExtension.fromFileName(file.fileName()).isEmpty();
                rows.add(new BookFileScanRow(++id, file.bookId(), file.libraryPathId(), "/library-path-" + file.libraryPathId(),
                        file.fileSubPath(), file.fileName(), true, file.bookType(), false, folderBased));
            }
            return new LibraryBookFiles(rows);
        }

        private static Shell shell(List<String> row) {
            return new Shell(Long.parseLong(row.get(0)), longOrNull(row.get(1)), flag(row.get(2)), flag(row.get(3)),
                    row.get(4), row.get(5), row.get(6) == null ? null : Float.valueOf(row.get(6)), names(row.get(7)),
                    row.size() > 8 ? row.get(8) : null);
        }

        private static ExistingFile existingFile(List<String> row) {
            return new ExistingFile(Long.parseLong(row.get(0)), Long.parseLong(row.get(1)), nullToEmpty(row.get(2)), row.get(3),
                    BookFileType.valueOf(row.get(4)), row.size() > 5 ? names(row.get(5)) : List.of());
        }

        private static NewFile newFile(List<String> row) {
            return new NewFile(Long.parseLong(row.get(0)), nullToEmpty(row.get(1)), row.get(2));
        }

        private static List<List<String>> rows(Path file, int minColumns, int maxColumns) throws IOException {
            List<List<String>> rows = new ArrayList<>();
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int n = 0; n < lines.size(); n++) {
                String line = lines.get(n);
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                List<String> fields = new ArrayList<>();
                for (String field : line.split("\t", -1)) {
                    fields.add(field.equals("NULL") ? null : unescape(field));
                }
                if (rows.isEmpty() && !fields.getFirst().matches("\\d+")) {
                    // A first line naming the columns
                    continue;
                }
                if (fields.size() < minColumns || fields.size() > maxColumns) {
                    throw new IllegalArgumentException(file + " line " + (n + 1) + " has " + fields.size() + " columns, not "
                            + (minColumns == maxColumns ? minColumns : minColumns + " to " + maxColumns));
                }
                rows.add(fields);
            }
            return rows;
        }

        /** Undoes mariadb -B's escapes: \t, \n, \0 and \\. */
        static String unescape(String field) {
            if (field.indexOf('\\') < 0) {
                return field;
            }
            StringBuilder out = new StringBuilder(field.length());
            for (int i = 0; i < field.length(); i++) {
                char c = field.charAt(i);
                if (c == '\\' && i + 1 < field.length()) {
                    char next = field.charAt(++i);
                    out.append(switch (next) {
                        case 't' -> '\t';
                        case 'n' -> '\n';
                        case '0' -> '\0';
                        default -> next;
                    });
                } else {
                    out.append(c);
                }
            }
            return out.toString();
        }

        /**
         * A book's author names, one per author row. Exports end each name with the unit separator (0x1F), since a
         * stored name may hold a ';' itself ("Arthur C. Clarke;Gentry Lee", "P&eacute;ter"), also when it is the book's
         * only one; older exports joined them with "; ", and are split at ';' as before.
         */
        private static List<String> names(String joined) {
            if (joined == null || joined.isBlank()) {
                return List.of();
            }
            String separator = joined.indexOf('\u001F') >= 0 ? "\u001F" : ";";
            return Arrays.stream(joined.split(separator)).map(String::trim).filter(name -> !name.isEmpty()).toList();
        }

        private static boolean flag(String value) {
            return value != null && (value.equals("1") || value.equalsIgnoreCase("true"));
        }

        private static Long longOrNull(String value) {
            return value == null || value.isEmpty() ? null : Long.valueOf(value);
        }

        private static String nullToEmpty(String value) {
            return value == null ? "" : value;
        }
    }

    // ========== the report ==========

    /**
     * One new file's decision.
     *
     * @param weakMatch what came of a weak match for the file, as the summary counts it: one of the outcomes of {@link
     *                  RescanDryRun#weakOutcome}, of which those that start with "attached" are the attaches only the
     *                  newer rules make. Null when no weak match had a part in the file's decision
     */
    public record Line(String decision, String target, String groupKey, String reason, NewFile file, String weakMatch) {

        Line(String decision, String target, String groupKey, String reason, NewFile file) {
            this(decision, target, groupKey, reason, file, null);
        }

        Line withWeakMatch(String weakMatch) {
            return new Line(decision, target, groupKey, reason, file, weakMatch);
        }
    }

    /**
     * @param decisions   how many files got each decision
     * @param rejections  for files that become new books, how many were turned down for each reason
     * @param weakMatches for files whose decision a weak match had a part in, how many had each outcome
     */
    public record Report(List<Line> lines, Map<String, Integer> decisions, Map<String, Integer> rejections,
                         Map<String, Integer> weakMatches) {

        public void print(PrintStream out) {
            out.println("# decision\ttarget\tgroup_key\treason\tlibrary_path_id\tfile_sub_path\tfile_name\tweak_match");
            for (Line line : lines) {
                out.println(String.join("\t", line.decision(), line.target(), clean(line.groupKey()), clean(line.reason()),
                        String.valueOf(line.file().libraryPathId()), clean(line.file().fileSubPath()), clean(line.file().fileName()),
                        line.weakMatch() == null ? "-" : line.weakMatch()));
            }
            printSummary(out);
        }

        public void printSummary(PrintStream out) {
            out.println("# " + lines.size() + " new files");
            out.println("# decisions:");
            decisions.forEach((decision, count) -> out.println("#   " + decision + "\t" + count));
            out.println("# reasons an existing book was turned down, for files that become new books:");
            rejections.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                    .forEach(entry -> out.println("#   " + entry.getValue() + "\t" + entry.getKey()));
            out.println("# weak matches (one of alike fileless books, or \" (1)\" read as a copy), by outcome:");
            weakMatches.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                    .forEach(entry -> out.println("#   " + entry.getValue() + "\t" + entry.getKey()));
        }

        private static String clean(String value) {
            return value == null ? "" : value.replace('\t', ' ').replace('\n', ' ');
        }
    }
}
