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
 *       is_physical, has_cover, title, subtitle, series_number, authors (joined with "; ").</li>
 *   <li>{@code existing.tsv}: the book formats of the library's books that are not deleted: book_id, library_path_id,
 *       file_sub_path, file_name, book_type, and optionally the book's authors joined with "; ". Without authors,
 *       same-folder matching cannot drop an author part of a name that only the book's authors vouch for.</li>
 *   <li>{@code newfiles.tsv}: the files the rescan would find new: library_path_id, file_sub_path, file_name. A name
 *       without a book extension is taken for a folder audiobook, as the walk reports one.</li>
 * </ul>
 * <p>
 * <b>The report</b> has one line per new file: decision, target, group key, reason, library_path_id, file_sub_path,
 * file_name. The decision is one of
 * <ul>
 *   <li>{@code attach-shell}: attached to the fileless book {@code target};</li>
 *   <li>{@code attach-same-folder}: attached to {@code target}, a book with files in the file's folder;</li>
 *   <li>{@code ambiguous}: becomes a new book because several books qualify ({@code target} lists them), a fileless
 *       book's claims were rejected, the files it would be one book with matched different books, or the book they
 *       matched turned one of them down;</li>
 *   <li>{@code new-book}: becomes a new book, matching no existing one.</li>
 * </ul>
 * The group key is the new book the file would be part of, {@code <library_path_id>:<sub path>:<key>}; files attached
 * to an existing book show the group they would have formed. Lines starting with {@code #} close the report: the
 * count of each decision, and for files that become new books, the count of each reason a book was turned down, with
 * book ids, numbers and quoted names taken out so that alike reasons count together.
 */
public final class RescanDryRun {

    static final long LIBRARY_ID = 1L;

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

        List<Line> lines = new ArrayList<>();
        Map<String, Integer> decisions = new TreeMap<>();
        Map<String, Integer> rejections = new TreeMap<>();
        for (LibraryFile file : newFiles) {
            Line line = line(file, rowOf.get(file), groupKeys.get(file), attachments.get(file), trace);
            lines.add(line);
            decisions.merge(line.decision(), 1, Integer::sum);
            if (line.decision().equals("new-book") || line.decision().equals("ambiguous")) {
                trace.rejections(file).forEach(reason -> rejections.merge(reason, 1, Integer::sum));
            }
        }
        return new Report(lines, decisions, rejections);
    }

    private static Line line(LibraryFile file, NewFile row, String groupKey, Attachment attachment, Trace trace) {
        Decision inFolder = trace.inFolder.get(file);
        Decision fileless = trace.fileless.get(file);
        if (attachment != null) {
            String decision = attachment.kind() == Kind.FILELESS ? "attach-shell" : "attach-same-folder";
            Decision own = attachment.kind() == Kind.FILELESS ? fileless : inFolder;
            String reason = own instanceof Match match && match.bookId() == attachment.bookId()
                    ? match.reason()
                    : "goes with the files it would be one book with";
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
        private static final Pattern NUMBER = Pattern.compile("\\d+");
        private static final Pattern ID_LIST = Pattern.compile("\\[[^\\]]*]");

        final Map<LibraryFile, Decision> inFolder = new IdentityHashMap<>();
        final Map<LibraryFile, Decision> fileless = new IdentityHashMap<>();
        final Map<LibraryFile, RejectedClaim> rejectedClaims = new IdentityHashMap<>();
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
            return NUMBER.matcher(general).replaceAll("N");
        }
    }

    private record RejectedClaim(long bookId, String why) {
    }

    // ========== the exports ==========

    /** A fileless book of shells.tsv. */
    public record Shell(long id, Long libraryPathId, boolean physical, boolean hasCover, String title, String subtitle,
                        Float seriesNumber, List<String> authors) {
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
                    rows(shells, 8, 8).stream().map(Exports::shell).toList(),
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
                            shell.hasCover() ? "cover" : null, null, shell.title(), shell.subtitle(), shell.seriesNumber(), author));
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
                    row.get(4), row.get(5), row.get(6) == null ? null : Float.valueOf(row.get(6)), names(row.get(7)));
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

        private static List<String> names(String joined) {
            if (joined == null || joined.isBlank()) {
                return List.of();
            }
            return Arrays.stream(joined.split(";")).map(String::trim).filter(name -> !name.isEmpty()).toList();
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

    /** One new file's decision. */
    public record Line(String decision, String target, String groupKey, String reason, NewFile file) {
    }

    /**
     * @param decisions  how many files got each decision
     * @param rejections for files that become new books, how many were turned down for each reason
     */
    public record Report(List<Line> lines, Map<String, Integer> decisions, Map<String, Integer> rejections) {

        public void print(PrintStream out) {
            out.println("# decision\ttarget\tgroup_key\treason\tlibrary_path_id\tfile_sub_path\tfile_name");
            for (Line line : lines) {
                out.println(String.join("\t", line.decision(), line.target(), clean(line.groupKey()), clean(line.reason()),
                        String.valueOf(line.file().libraryPathId()), clean(line.file().fileSubPath()), clean(line.file().fileName())));
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
        }

        private static String clean(String value) {
            return value == null ? "" : value.replace('\t', ' ').replace('\n', ' ');
        }
    }
}
