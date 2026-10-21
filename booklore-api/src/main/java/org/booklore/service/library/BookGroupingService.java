package org.booklore.service.library;

import org.booklore.model.dto.settings.LibraryFile;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.model.enums.LibraryOrganizationMode;
import org.booklore.repository.BookRepository;
import org.booklore.util.BookFileGroupingUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
@Slf4j
@RequiredArgsConstructor
public class BookGroupingService {

    private static final double FILELESS_MATCH_THRESHOLD = 0.85;

    private final BookRepository bookRepository;

    public record GroupingResult(
            Map<BookEntity, List<LibraryFile>> filesToAttach,
            Map<String, List<LibraryFile>> newBookGroups
    ) {}

    public Map<String, List<LibraryFile>> groupForInitialScan(List<LibraryFile> newFiles, LibraryEntity libraryEntity) {
        LibraryOrganizationMode mode = getOrganizationMode(libraryEntity);

        return switch (mode) {
            case BOOK_PER_FILE -> groupByFile(newFiles);
            case BOOK_PER_FOLDER -> groupByFolder(newFiles);
            case AUTO_DETECT -> BookFileGroupingUtils.groupByBaseName(newFiles);
        };
    }

    /**
     * Runs in one read-only transaction so every lookup shares a persistence context: the same
     * book is the same instance across lookups, and the returned books are detached afterwards.
     */
    @Transactional(readOnly = true)
    public GroupingResult groupForRescan(List<LibraryFile> newFiles, LibraryEntity libraryEntity) {
        LibraryOrganizationMode mode = getOrganizationMode(libraryEntity);
        RescanLookups lookups = new RescanLookups();

        Map<BookEntity, List<LibraryFile>> filesToAttach = new LinkedHashMap<>();
        List<LibraryFile> unmatched = new ArrayList<>();

        for (LibraryFile file : newFiles) {
            BookEntity match = findMatchingBook(file, mode, lookups);
            if (match != null) {
                filesToAttach.computeIfAbsent(match, k -> new ArrayList<>()).add(file);
            } else {
                unmatched.add(file);
            }
        }

        Map<String, List<LibraryFile>> newBookGroups;
        if (unmatched.isEmpty()) {
            newBookGroups = Collections.emptyMap();
        } else {
            newBookGroups = switch (mode) {
                case BOOK_PER_FILE -> groupByFile(unmatched);
                case BOOK_PER_FOLDER -> groupByFolder(unmatched);
                case AUTO_DETECT -> BookFileGroupingUtils.groupByBaseName(unmatched);
            };
        }

        return new GroupingResult(filesToAttach, newBookGroups);
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
        while (true) {
            int lastSep = current.lastIndexOf('/');
            if (lastSep == -1) {
                lastSep = current.lastIndexOf('\\');
            }
            if (lastSep <= 0) {
                break;
            }
            current = current.substring(0, lastSep);
            String candidateKey = pathId + ":" + current;
            if (ebookFolders.contains(candidateKey)) {
                return candidateKey;
            }
        }
        return null;
    }

    private BookEntity findMatchingBook(LibraryFile file, LibraryOrganizationMode mode, RescanLookups lookups) {
        List<BookEntity> filelessBooks = lookups.filelessBooks(file.getLibraryEntity().getId());
        BookEntity filelessMatch = switch (mode) {
            case BOOK_PER_FILE, BOOK_PER_FOLDER -> findExactFilelessMatch(file, filelessBooks);
            case AUTO_DETECT -> findMatchingFilelessBook(file, filelessBooks);
        };
        if (filelessMatch != null) {
            return filelessMatch;
        }

        if (mode == LibraryOrganizationMode.BOOK_PER_FILE) {
            return null;
        }

        String fileSubPath = file.getFileSubPath();
        if (fileSubPath == null || fileSubPath.isEmpty()) {
            return null;
        }

        Long libraryPathId = file.getLibraryPathEntity().getId();
        List<BookEntity> booksInDirectory = lookups.booksInDirectory(libraryPathId, fileSubPath);

        List<BookEntity> activeBooksInDirectory = booksInDirectory.stream()
                .filter(book -> book.getDeleted() == null || !book.getDeleted())
                .toList();

        if (activeBooksInDirectory.isEmpty()) {
            if (mode == LibraryOrganizationMode.BOOK_PER_FOLDER && file.getBookFileType() == BookFileType.AUDIOBOOK) {
                return findNearestAncestorBookWithEbook(libraryPathId, fileSubPath, lookups);
            }
            return null;
        }

        return switch (mode) {
            case BOOK_PER_FILE -> null;
            case BOOK_PER_FOLDER -> findMatchBookPerFolderWithAbsorption(file, activeBooksInDirectory);
            case AUTO_DETECT -> findMatchAutoDetect(file, activeBooksInDirectory);
        };
    }

    private BookEntity findExactFilelessMatch(LibraryFile file, List<BookEntity> filelessBooks) {
        if (filelessBooks.isEmpty()) {
            return null;
        }

        String fileBaseName = BookFileGroupingUtils.extractGroupingKey(file.getFileName());
        Long fileLibraryPathId = file.getLibraryPathEntity().getId();

        for (BookEntity book : filelessBooks) {
            if (book.getLibraryPath() != null && !book.getLibraryPath().getId().equals(fileLibraryPathId)) {
                continue;
            }

            if (book.getMetadata() != null && book.getMetadata().getTitle() != null) {
                String bookTitle = BookFileGroupingUtils.extractGroupingKey(book.getMetadata().getTitle());
                if (fileBaseName.equals(bookTitle)) {
                    log.debug("Exact matched file '{}' to fileless book '{}' (title: {})",
                            file.getFileName(), book.getId(), book.getMetadata().getTitle());
                    return book;
                }
            }
        }
        return null;
    }

    private BookEntity findMatchingFilelessBook(LibraryFile file, List<BookEntity> filelessBooks) {
        if (filelessBooks.isEmpty()) {
            return null;
        }

        String fileBaseName = BookFileGroupingUtils.extractGroupingKey(file.getFileName());
        Long fileLibraryPathId = file.getLibraryPathEntity().getId();

        for (BookEntity book : filelessBooks) {
            if (book.getLibraryPath() != null && !book.getLibraryPath().getId().equals(fileLibraryPathId)) {
                continue;
            }

            if (book.getMetadata() != null && book.getMetadata().getTitle() != null) {
                String bookTitle = BookFileGroupingUtils.extractGroupingKey(book.getMetadata().getTitle());
                double similarity = BookFileGroupingUtils.calculateSimilarity(fileBaseName, bookTitle);
                if (similarity >= FILELESS_MATCH_THRESHOLD) {
                    log.debug("Matched file '{}' to fileless book '{}' (title: {})",
                            file.getFileName(), book.getId(), book.getMetadata().getTitle());
                    return book;
                }
            }
        }
        return null;
    }

    private BookEntity findMatchBookPerFolderWithAbsorption(LibraryFile file, List<BookEntity> booksInDirectory) {
        List<BookEntity> booksWithFiles = booksInDirectory.stream()
                .filter(BookEntity::hasFiles)
                .toList();

        if (booksWithFiles.isEmpty()) {
            return null;
        }

        if (booksWithFiles.size() == 1) {
            return booksWithFiles.getFirst();
        }

        return booksWithFiles.stream()
                .max(Comparator.comparingLong(book -> book.getBookFiles() != null ? book.getBookFiles().size() : 0))
                .orElse(null);
    }

    private BookEntity findNearestAncestorBookWithEbook(Long libraryPathId, String subPath, RescanLookups lookups) {
        String current = subPath;
        while (true) {
            int lastSep = current.lastIndexOf('/');
            if (lastSep == -1) {
                lastSep = current.lastIndexOf('\\');
            }
            if (lastSep <= 0) {
                break;
            }
            current = current.substring(0, lastSep);

            List<BookEntity> booksAtLevel = lookups.booksInDirectory(libraryPathId, current);
            for (BookEntity book : booksAtLevel) {
                if (book.getDeleted() != null && book.getDeleted()) {
                    continue;
                }
                if (book.getBookFiles() != null && book.getBookFiles().stream()
                        .anyMatch(bf -> bf.getBookType() != BookFileType.AUDIOBOOK)) {
                    log.debug("BOOK_PER_FOLDER: Audio absorption matched to ancestor book id={} at '{}'",
                            book.getId(), current);
                    return book;
                }
            }
        }
        return null;
    }

    private BookEntity findMatchAutoDetect(LibraryFile file, List<BookEntity> booksInDirectory) {
        List<BookEntity> booksWithFiles = booksInDirectory.stream()
                .filter(BookEntity::hasFiles)
                .toList();

        if (booksWithFiles.isEmpty()) {
            return null;
        }

        if (booksWithFiles.size() == 1) {
            BookEntity book = booksWithFiles.getFirst();
            if (isFileNameCompatible(file, book)) {
                log.debug("AUTO_DETECT: Single book in folder '{}', attaching '{}' to '{}'",
                        file.getFileSubPath(), file.getFileName(), book.getPrimaryBookFile().getFileName());
                return book;
            }
            return null;
        }

        return findBestMatch(file, booksWithFiles);
    }

    private BookEntity findBestMatch(LibraryFile file, List<BookEntity> booksWithFiles) {
        String fileGroupingKey = BookFileGroupingUtils.extractGroupingKey(file.getFileName());
        BookEntity bestMatch = null;
        double bestSimilarity = 0;

        for (BookEntity book : booksWithFiles) {
            BookFileEntity primaryFile = book.getPrimaryBookFile();
            String existingGroupingKey = BookFileGroupingUtils.extractGroupingKey(primaryFile.getFileName());

            if (fileGroupingKey.equals(existingGroupingKey)) {
                return book;
            }

            double similarity = BookFileGroupingUtils.calculateSimilarity(fileGroupingKey, existingGroupingKey);
            if (similarity >= 0.85 && similarity > bestSimilarity) {
                bestSimilarity = similarity;
                bestMatch = book;
            }
        }

        if (bestMatch != null) {
            log.debug("Fuzzy matched '{}' to '{}' (similarity: {})",
                    file.getFileName(), bestMatch.getPrimaryBookFile().getFileName(), bestSimilarity);
        }
        return bestMatch;
    }

    private boolean isFileNameCompatible(LibraryFile file, BookEntity book) {
        BookFileEntity primaryFile = book.getPrimaryBookFile();
        if (primaryFile == null) {
            return false;
        }
        String fileKey = BookFileGroupingUtils.extractGroupingKey(file.getFileName());
        String bookKey = BookFileGroupingUtils.extractGroupingKey(primaryFile.getFileName());
        if (fileKey.equals(bookKey)) {
            return true;
        }
        return BookFileGroupingUtils.calculateSimilarity(fileKey, bookKey) >= 0.85;
    }

    private LibraryOrganizationMode getOrganizationMode(LibraryEntity library) {
        LibraryOrganizationMode mode = library.getOrganizationMode();
        return mode != null ? mode : LibraryOrganizationMode.AUTO_DETECT;
    }

    /**
     * Database lookups for one groupForRescan call. Grouping writes nothing, so a library's
     * fileless books and a directory's books cannot change between files: each is queried
     * at most once instead of once per new file (and per ancestor directory).
     */
    private final class RescanLookups {

        private final Map<Long, List<BookEntity>> filelessBooksByLibrary = new HashMap<>();
        private final Map<DirectoryKey, List<BookEntity>> booksByDirectory = new HashMap<>();

        List<BookEntity> filelessBooks(Long libraryId) {
            return filelessBooksByLibrary.computeIfAbsent(libraryId, bookRepository::findFilelessBooksWithMetadataByLibraryId);
        }

        List<BookEntity> booksInDirectory(Long libraryPathId, String fileSubPath) {
            return booksByDirectory.computeIfAbsent(new DirectoryKey(libraryPathId, fileSubPath),
                    key -> bookRepository.findAllWithFilesByLibraryPathIdAndFileSubPath(key.libraryPathId(), key.fileSubPath()));
        }
    }

    private record DirectoryKey(Long libraryPathId, String fileSubPath) {}
}
