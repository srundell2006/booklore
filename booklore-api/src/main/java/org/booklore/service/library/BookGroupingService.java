package org.booklore.service.library;

import org.booklore.model.dto.settings.LibraryFile;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.model.enums.LibraryOrganizationMode;
import org.booklore.repository.BookRepository;
import org.booklore.repository.projection.BookAuthorRow;
import org.booklore.repository.projection.BookFileScanRow;
import org.booklore.service.library.matching.BooksInFolder;
import org.booklore.service.library.matching.BooksInFolder.FolderBook;
import org.booklore.service.library.matching.BooksInFolder.FolderFile;
import org.booklore.service.library.matching.ExistingBookMatcher;
import org.booklore.service.library.matching.ExistingBookMatcher.Ambiguous;
import org.booklore.service.library.matching.ExistingBookMatcher.Claim;
import org.booklore.service.library.matching.ExistingBookMatcher.ClaimResolution;
import org.booklore.service.library.matching.ExistingBookMatcher.Decision;
import org.booklore.service.library.matching.ExistingBookMatcher.Kind;
import org.booklore.service.library.matching.ExistingBookMatcher.Match;
import org.booklore.service.library.matching.FileEvidence;
import org.booklore.service.library.matching.FilelessShellIndex;
import org.booklore.service.library.matching.NameContext;
import org.booklore.util.BookFileGroupingUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
@Slf4j
@RequiredArgsConstructor
public class BookGroupingService {

    /** Books whose authors one query reads, so an IN list stays short. */
    static final int AUTHOR_QUERY_BATCH = 1000;

    private final BookRepository bookRepository;
    private final FilelessShells filelessShells;

    /**
     * New files to attach to one book the library already has.
     *
     * @param kind whether the book had files ({@link Kind#SAME_FOLDER}) or none ({@link Kind#FILELESS}) when matched
     */
    public record Attachment(long bookId, Kind kind, List<LibraryFile> files) {
        public Attachment {
            files = List.copyOf(files);
        }
    }

    /**
     * @param filesToAttach files for books the library already has, by book id, so that attaching needs nothing
     *                      grouping read
     * @param newBookGroups the remaining files, grouped into new books
     */
    public record GroupingResult(List<Attachment> filesToAttach, Map<String, List<LibraryFile>> newBookGroups) {
    }

    public Map<String, List<LibraryFile>> groupForInitialScan(List<LibraryFile> newFiles, LibraryEntity libraryEntity) {
        return newBookGroups(getOrganizationMode(libraryEntity), newFiles);
    }

    /**
     * Decides, for a rescan's new files, which belong to books the library already has and which become new books.
     * <p>
     * Every decision is made from data read up front, with no transaction held: the books with files in each folder
     * come from the scan rows the rescan already read ({@code bookFiles}), the authors of those books from a few batched
     * queries, and the library's fileless books from one projection query. Nothing here queries per folder or per file,
     * and a new file costs a few lookups whatever the size of the library.
     * <p>
     * Which book a file belongs to is {@link ExistingBookMatcher}'s decision, per organization mode:
     * <ul>
     *   <li>AUTO_DETECT: a book with files in the file's folder whose file names read as the same title, else a
     *       fileless book whose title and author agree.</li>
     *   <li>BOOK_PER_FOLDER: the one book with files in the file's folder (none when there are several); for an
     *       audiobook in a folder without books, the one book with an ebook in the nearest folder above that has one;
     *       else a fileless book, as in AUTO_DETECT.</li>
     *   <li>BOOK_PER_FILE: only a fileless book, which then gets that one file.</li>
     * </ul>
     * A fileless book claimed from more than one folder, or by two different files of one format, gets none of them.
     * Files that would be one new book (one group of the mode's grouping) go to one book: a file without a match of its
     * own follows the files of its group that have one ("Title (1).epub" its original, the parts of an audiobook each
     * other), and a group whose files matched different books becomes a new book instead.
     */
    public GroupingResult groupForRescan(List<LibraryFile> newFiles, LibraryEntity libraryEntity, LibraryBookFiles bookFiles) {
        if (newFiles.isEmpty()) {
            return new GroupingResult(List.of(), Map.of());
        }
        LibraryOrganizationMode mode = getOrganizationMode(libraryEntity);
        FilelessShellIndex shells = filelessShells.load(libraryEntity.getId());
        FoldersOfBooks folders = new FoldersOfBooks(bookFiles);
        if (mode == LibraryOrganizationMode.AUTO_DETECT) {
            folders.loadAuthors(newFiles);
        }

        Map<LibraryFile, Integer> indexOf = new IdentityHashMap<>();
        Long[] targets = new Long[newFiles.size()];
        Map<Long, Kind> kinds = new HashMap<>();
        List<Claim<Integer>> claims = new ArrayList<>();
        Map<String, NameContext> contexts = new HashMap<>();
        for (int i = 0; i < newFiles.size(); i++) {
            LibraryFile file = newFiles.get(i);
            indexOf.put(file, i);
            String folder = subPath(file);
            FileEvidence evidence = FileEvidence.of(pathId(file), folder, file.getFileName(), isAudio(file),
                    contexts.computeIfAbsent(folder, NameContext::forFolder));

            Decision inFolder = switch (mode) {
                case AUTO_DETECT -> ExistingBookMatcher.matchInFolder(evidence, folders.booksIn(pathId(file), folder));
                case BOOK_PER_FOLDER -> folders.folderBook(file);
                case BOOK_PER_FILE -> null;
            };
            if (inFolder instanceof Match match) {
                targets[i] = match.bookId();
                kinds.put(match.bookId(), Kind.SAME_FOLDER);
                log.debug("'{}/{}' belongs to book#{} in its folder", folder, file.getFileName(), match.bookId());
                continue;
            }
            if (inFolder instanceof Ambiguous ambiguous) {
                log.info("New file '{}/{}' is left for a new book: {} (books {})", folder, file.getFileName(),
                        ambiguous.reason(), ambiguous.bookIds());
                continue;
            }
            Decision fileless = ExistingBookMatcher.matchShell(evidence, shells);
            if (fileless instanceof Match match) {
                boolean oneFilePerBook = mode == LibraryOrganizationMode.BOOK_PER_FILE;
                claims.add(new Claim<>(i, file.getFileName(), match.bookId(), evidence.folderKey(),
                        oneFilePerBook ? "file" : String.valueOf(file.getBookFileType()),
                        oneFilePerBook ? evidence.folderKey() + "/" + file.getFileName() : evidence.fileNameKey()));
            } else if (fileless instanceof Ambiguous ambiguous) {
                log.info("New file '{}/{}' is left for a new book: {}", folder, file.getFileName(), ambiguous.reason());
            } else {
                log.debug("'{}/{}' matches no fileless book: {}", folder, file.getFileName(), fileless.reason());
            }
        }

        ClaimResolution<Integer> resolution = ExistingBookMatcher.resolveShellClaims(claims);
        resolution.rejected().forEach((bookId, why) ->
                log.info("Fileless book#{} is left as it is: {}; those files become new books", bookId, why));
        resolution.accepted().forEach((bookId, indexes) -> {
            indexes.forEach(i -> targets[i] = bookId);
            kinds.put(bookId, Kind.FILELESS);
        });

        Map<String, List<LibraryFile>> newBookGroups = new LinkedHashMap<>();
        newBookGroups(mode, newFiles).forEach((key, group) -> {
            Set<Long> groupTargets = new LinkedHashSet<>();
            group.forEach(file -> {
                Long target = targets[indexOf.get(file)];
                if (target != null) {
                    groupTargets.add(target);
                }
            });
            if (groupTargets.size() == 1) {
                Long target = groupTargets.iterator().next();
                group.forEach(file -> targets[indexOf.get(file)] = target);
                return;
            }
            if (groupTargets.size() > 1) {
                log.info("New files {} read as one book but match different books {}; they become a new book",
                        group.stream().map(LibraryFile::getFileName).toList(), groupTargets);
                group.forEach(file -> targets[indexOf.get(file)] = null);
            }
            newBookGroups.put(key, group);
        });

        Map<Long, List<LibraryFile>> filesByBook = new LinkedHashMap<>();
        for (int i = 0; i < newFiles.size(); i++) {
            if (targets[i] != null) {
                filesByBook.computeIfAbsent(targets[i], id -> new ArrayList<>()).add(newFiles.get(i));
            }
        }
        List<Attachment> attachments = new ArrayList<>();
        filesByBook.forEach((bookId, files) -> attachments.add(new Attachment(bookId, kinds.get(bookId), files)));

        log.info("Rescan of '{}' grouped {} new files: {} for {} books with files, {} for {} fileless books, {} into {} new books",
                libraryEntity.getName(), newFiles.size(),
                count(attachments, Kind.SAME_FOLDER, true), count(attachments, Kind.SAME_FOLDER, false),
                count(attachments, Kind.FILELESS, true), count(attachments, Kind.FILELESS, false),
                newBookGroups.values().stream().mapToInt(List::size).sum(), newBookGroups.size());
        return new GroupingResult(attachments, newBookGroups);
    }

    private static int count(List<Attachment> attachments, Kind kind, boolean files) {
        return attachments.stream().filter(a -> a.kind() == kind).mapToInt(a -> files ? a.files().size() : 1).sum();
    }

    private Map<String, List<LibraryFile>> newBookGroups(LibraryOrganizationMode mode, List<LibraryFile> files) {
        return switch (mode) {
            case BOOK_PER_FILE -> groupByFile(files);
            case BOOK_PER_FOLDER -> groupByFolder(files);
            case AUTO_DETECT -> BookFileGroupingUtils.groupByBaseName(files);
        };
    }

    private Map<String, List<LibraryFile>> groupByFile(List<LibraryFile> files) {
        Map<String, List<LibraryFile>> result = new LinkedHashMap<>();

        for (LibraryFile file : files) {
            String key = file.getLibraryPathEntity().getId() + ":" +
                    (file.getFileSubPath() == null ? "" : file.getFileSubPath()) + ":" +
                    file.getFileName();
            result.computeIfAbsent(key, k -> new ArrayList<>()).add(file);
        }

        log.debug("BOOK_PER_FILE grouping: {} files into {} groups", files.size(), result.size());
        return result;
    }

    private Map<String, List<LibraryFile>> groupByFolder(List<LibraryFile> files) {
        Map<String, List<LibraryFile>> result = new LinkedHashMap<>();

        Set<String> ebookFolders = new HashSet<>();
        for (LibraryFile file : files) {
            if (file.getBookFileType() != BookFileType.AUDIOBOOK) {
                String folderKey = file.getLibraryPathEntity().getId() + ":" +
                        (file.getFileSubPath() == null ? "" : file.getFileSubPath());
                ebookFolders.add(folderKey);
            }
        }

        for (LibraryFile file : files) {
            Long pathId = file.getLibraryPathEntity().getId();
            String subPath = file.getFileSubPath() == null ? "" : file.getFileSubPath();

            if (subPath.isEmpty()) {
                String key = pathId + "::" + file.getFileName();
                result.computeIfAbsent(key, k -> new ArrayList<>()).add(file);
            } else if (file.getBookFileType() == BookFileType.AUDIOBOOK) {
                // Folder-based audiobook entries represent a collapsed directory (e.g., subPath="Author",
                // fileName="audiobook-folder"). Their subPath points to the parent because getRelativeSubPath
                // takes .getParent() (designed for files, not directories). Reconstruct the actual folder path
                // so sibling audiobook folders stay separate and absorption searches from the correct level.
                String effectiveSubPath = file.isFolderBased()
                        ? subPath + "/" + file.getFileName()
                        : subPath;
                String ancestorKey = findNearestEbookAncestor(pathId, effectiveSubPath, ebookFolders);
                if (ancestorKey != null) {
                    result.computeIfAbsent(ancestorKey, k -> new ArrayList<>()).add(file);
                } else {
                    String key = pathId + ":" + effectiveSubPath;
                    result.computeIfAbsent(key, k -> new ArrayList<>()).add(file);
                }
            } else {
                String key = pathId + ":" + subPath;
                result.computeIfAbsent(key, k -> new ArrayList<>()).add(file);
            }
        }

        log.debug("BOOK_PER_FOLDER grouping: {} files into {} groups", files.size(), result.size());
        return result;
    }

    private String findNearestEbookAncestor(Long pathId, String subPath, Set<String> ebookFolders) {
        String current = subPath;
        while ((current = parent(current)) != null) {
            String candidateKey = pathId + ":" + current;
            if (ebookFolders.contains(candidateKey)) {
                return candidateKey;
            }
        }
        return null;
    }

    /** The folder above, or null at the top: "A/B" is in "A", and "A" in no folder that holds books in this mode. */
    private static String parent(String subPath) {
        int lastSep = Math.max(subPath.lastIndexOf('/'), subPath.lastIndexOf('\\'));
        return lastSep <= 0 ? null : subPath.substring(0, lastSep);
    }

    private LibraryOrganizationMode getOrganizationMode(LibraryEntity library) {
        LibraryOrganizationMode mode = library.getOrganizationMode();
        return mode != null ? mode : LibraryOrganizationMode.AUTO_DETECT;
    }

    private static Long pathId(LibraryFile file) {
        return file.getLibraryPathEntity().getId();
    }

    private static String subPath(LibraryFile file) {
        return file.getFileSubPath() == null ? "" : file.getFileSubPath();
    }

    private static boolean isAudio(LibraryFile file) {
        return file.getBookFileType() == BookFileType.AUDIOBOOK;
    }

    /**
     * The books with book files in each folder, from the scan rows of the library: soft-deleted books and files that
     * are not book formats (a cover, notes) left out.
     */
    private final class FoldersOfBooks {

        private final Map<String, Map<Long, List<BookFileScanRow>>> rowsByFolder = new HashMap<>();
        private final Set<Long> booksWithAnEbook = new HashSet<>();
        private final Map<Long, List<String>> authorsByBook = new HashMap<>();
        private final Map<String, BooksInFolder> indexes = new HashMap<>();

        FoldersOfBooks(LibraryBookFiles bookFiles) {
            for (BookFileScanRow row : bookFiles.rows()) {
                if (!row.bookFormat() || row.isBookDeleted()) {
                    continue;
                }
                rowsByFolder.computeIfAbsent(folderKey(row.libraryPathId(), row.fileSubPath()), k -> new LinkedHashMap<>())
                        .computeIfAbsent(row.bookId(), id -> new ArrayList<>()).add(row);
                if (row.bookType() != BookFileType.AUDIOBOOK) {
                    booksWithAnEbook.add(row.bookId());
                }
            }
        }

        private static String folderKey(Long libraryPathId, String subPath) {
            return libraryPathId + ":" + (subPath == null ? "" : subPath);
        }

        private Map<Long, List<BookFileScanRow>> rowsIn(Long libraryPathId, String subPath) {
            return rowsByFolder.getOrDefault(folderKey(libraryPathId, subPath), Map.of());
        }

        /** Reads the authors of the books in the new files' folders, a batch of books per query. */
        void loadAuthors(List<LibraryFile> newFiles) {
            Set<Long> bookIds = new TreeSet<>();
            Set<String> seen = new HashSet<>();
            for (LibraryFile file : newFiles) {
                if (seen.add(folderKey(pathId(file), subPath(file)))) {
                    bookIds.addAll(rowsIn(pathId(file), subPath(file)).keySet());
                }
            }
            List<Long> ids = List.copyOf(bookIds);
            for (int from = 0; from < ids.size(); from += AUTHOR_QUERY_BATCH) {
                List<Long> batch = ids.subList(from, Math.min(ids.size(), from + AUTHOR_QUERY_BATCH));
                for (BookAuthorRow row : bookRepository.findAuthorNamesByBookIds(batch)) {
                    authorsByBook.computeIfAbsent(row.bookId(), id -> new ArrayList<>()).add(row.authorName());
                }
            }
        }

        /** The books with files in the folder, their files there read once for every new file of the folder. */
        BooksInFolder booksIn(Long libraryPathId, String subPath) {
            return indexes.computeIfAbsent(folderKey(libraryPathId, subPath), key -> {
                Map<Long, List<BookFileScanRow>> books = rowsIn(libraryPathId, subPath);
                if (books.isEmpty()) {
                    return BooksInFolder.EMPTY;
                }
                List<FolderBook> folderBooks = new ArrayList<>(books.size());
                books.forEach((bookId, rows) -> folderBooks.add(new FolderBook(bookId,
                        rows.stream().map(row -> new FolderFile(row.fileName(), row.bookType() == BookFileType.AUDIOBOOK)).toList(),
                        authorsByBook.getOrDefault(bookId, List.of()))));
                return BooksInFolder.of(subPath, folderBooks);
            });
        }

        /**
         * BOOK_PER_FOLDER: a folder is one book, so a new file belongs to the one book with files in its folder. With
         * several there, which one is a guess, and it belongs to none. An audiobook in a folder without books belongs
         * to the one book with an ebook in the nearest folder above that has one ("Title/audio/01.mp3" to the book of
         * "Title/Title.epub"). Files at the library's root are books of their own.
         */
        Decision folderBook(LibraryFile file) {
            String subPath = subPath(file);
            if (subPath.isEmpty()) {
                return null;
            }
            Set<Long> inFolder = rowsIn(pathId(file), subPath).keySet();
            if (inFolder.size() == 1) {
                return new Match(inFolder.iterator().next(), Kind.SAME_FOLDER, "the book of its folder");
            }
            if (inFolder.size() > 1) {
                return new Ambiguous(List.copyOf(inFolder), inFolder.size() + " books have files in its folder");
            }
            if (!isAudio(file)) {
                return null;
            }
            String current = subPath;
            while ((current = parent(current)) != null) {
                List<Long> withEbook = rowsIn(pathId(file), current).keySet().stream().filter(booksWithAnEbook::contains).toList();
                if (withEbook.size() == 1) {
                    return new Match(withEbook.getFirst(), Kind.SAME_FOLDER, "the book with an ebook in '" + current + "'");
                }
                if (withEbook.size() > 1) {
                    return new Ambiguous(withEbook, withEbook.size() + " books have ebooks in '" + current + "'");
                }
            }
            return null;
        }
    }
}
