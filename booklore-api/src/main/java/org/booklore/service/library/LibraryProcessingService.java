package org.booklore.service.library;

import org.booklore.exception.ApiError;
import org.booklore.model.dto.settings.LibraryFile;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.entity.LibraryPathEntity;
import org.booklore.model.websocket.LogNotification;
import org.booklore.model.websocket.Topic;
import org.booklore.repository.BookAdditionalFileRepository;
import org.booklore.repository.BookRepository;
import org.booklore.repository.LibraryRepository;
import org.booklore.repository.projection.BookFileScanRow;
import org.booklore.service.NotificationService;
import org.booklore.service.file.FileFingerprint;
import org.booklore.task.options.RescanLibraryContext;
import org.booklore.util.FileUtils;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Initial scans and rescans of a library.
 * <p>
 * Neither runs inside one big transaction. The disk walk, which on a network share can take
 * hours, runs with no transaction open. Detection works on {@link LibraryBookFiles} (one
 * projection row per book_file) rather than on the library's entities, and every phase that
 * changes something does so in its own short transaction, loading only the rows it changes.
 * Nothing stays managed between phases, so no phase can flush another phase's stale entities.
 * <p>
 * Since the database is not held at a snapshot while the walk runs, a rescan reads the rows
 * both before and after it: removals and restores are only decided for rows that did not
 * change in between (see {@link WalkCoverage}).
 */
@AllArgsConstructor
@Service
@Slf4j
public class LibraryProcessingService {

    private static final int MAX_LOGGED_INCOMPLETE_PATHS = 20;

    private final LibraryRepository libraryRepository;
    private final NotificationService notificationService;
    private final BookAdditionalFileRepository bookAdditionalFileRepository;
    private final BookRepository bookRepository;
    private final FileAsBookProcessor fileAsBookProcessor;
    private final BookRestorationService bookRestorationService;
    private final BookDeletionService bookDeletionService;
    private final LibraryFileHelper libraryFileHelper;
    private final BookGroupingService bookGroupingService;
    private final PlatformTransactionManager transactionManager;

    /**
     * Ids a rescan judged gone from disk, plus how many more it did not find but kept because
     * the walk could not vouch for them: they lie under a path it could not read, or they
     * changed while it ran.
     */
    record RemovalCandidates(List<Long> ids, int underUnreadPaths, int changedDuringWalk) {}

    public void processLibrary(long libraryId) {
        LibraryEntity libraryEntity = loadLibrary(libraryId);
        notificationService.sendMessage(Topic.LOG, LogNotification.info("Started processing library: " + libraryEntity.getName()));
        try {
            List<LibraryFile> libraryFiles = libraryFileHelper.getLibraryFiles(libraryEntity);
            List<LibraryFile> newFiles = detectNewBookPaths(libraryFiles, loadBookFiles(libraryEntity));

            // Use BookGroupingService for consistent grouping based on organization mode
            Map<String, List<LibraryFile>> groups = bookGroupingService.groupForInitialScan(newFiles, libraryEntity);
            fileAsBookProcessor.processLibraryFilesGrouped(groups, libraryEntity);

            notificationService.sendMessage(Topic.LOG, LogNotification.info("Finished processing library: " + libraryEntity.getName()));
        } catch (IOException e) {
            log.error("Failed to process library {}: {}", libraryEntity.getName(), e.getMessage(), e);
            notificationService.sendMessage(Topic.LOG, LogNotification.error("Failed to process library: " + libraryEntity.getName() + " - " + e.getMessage()));
            throw new UncheckedIOException("Library processing failed", e);
        }
    }

    public void rescanLibrary(RescanLibraryContext context) throws IOException {
        // Detached: only its own columns and the eagerly loaded library paths are used.
        LibraryEntity libraryEntity = loadLibrary(context.getLibraryId());
        notificationService.sendMessage(Topic.LOG, LogNotification.info("Started refreshing library: " + libraryEntity.getName()));

        validateLibraryPathsAccessible(libraryEntity);

        // Read before the walk: a book added, moved or soft-deleted while the walk runs may be
        // in a directory it has already listed, so the walk only judges rows still unchanged
        // when it is done.
        Set<BookFileScanRow> rowsBeforeWalk = Set.copyOf(
                bookAdditionalFileRepository.findScanRowsByLibraryId(libraryEntity.getId()));

        // No transaction is open here: on a network share the walk can take hours.
        LibraryScanResult scanResult = libraryFileHelper.scanAllLibraryFiles(libraryEntity);
        List<LibraryFile> allLibraryFiles = scanResult.files();
        List<LibraryFile> filteredFiles = libraryFileHelper.filterByAllowedFormats(
                allLibraryFiles, libraryEntity.getAllowedFormats());

        if (allLibraryFiles.isEmpty()) {
            long existingBookCount = bookRepository.countIncludingDeletedByLibraryId(libraryEntity.getId());
            if (existingBookCount > 0) {
                String paths = libraryEntity.getLibraryPaths().stream()
                        .map(LibraryPathEntity::getPath)
                        .collect(Collectors.joining(", "));
                log.error("Library '{}' has {} existing books but scan found 0 files. Paths may be offline: {}",
                        libraryEntity.getName(), existingBookCount, paths);
                throw ApiError.LIBRARY_PATH_NOT_ACCESSIBLE.createException(paths);
            }
        }

        Set<String> currentFileKeys = allLibraryFiles.stream()
                .map(this::generateUniqueKey)
                .collect(Collectors.toSet());
        Set<Path> currentFullPaths = allLibraryFiles.stream()
                .map(LibraryFile::getFullPath)
                .collect(Collectors.toSet());

        WalkCoverage coverage = new WalkCoverage(rowsBeforeWalk, scanResult);
        LibraryBookFiles bookFiles = loadBookFiles(libraryEntity);

        // Both are judged on the rows as they were before either deletion runs. Removed books
        // in particular must be: once the vanished file rows are gone, a book whose only file
        // vanished would look fileless, and fileless books are never deleted.
        RemovalCandidates removedFiles = detectDeletedAdditionalFiles(currentFileKeys, bookFiles, coverage);
        RemovalCandidates removedBooks = detectDeletedBookIds(currentFullPaths, bookFiles, libraryEntity, coverage);
        logKeptCandidates(libraryEntity, scanResult, removedBooks, removedFiles);

        boolean rowsChanged = false;
        if (!removedFiles.ids().isEmpty()) {
            log.info("Detected {} removed additional files in library: {}", removedFiles.ids().size(), libraryEntity.getName());
            bookDeletionService.deleteRemovedAdditionalFiles(removedFiles.ids());
            rowsChanged = true;
        }
        if (!removedBooks.ids().isEmpty()) {
            log.info("Detected {} removed books in library: {}", removedBooks.ids().size(), libraryEntity.getName());
            // Decided from the files these books have left once the deletion above is done.
            bookDeletionService.processDeletedLibraryFiles(removedBooks.ids());
            rowsChanged = true;
        }
        if (rowsChanged) {
            bookFiles = loadBookFiles(libraryEntity);
        }

        // Restoring only flips book.deleted, which neither the purge nor new-file detection
        // reads, so the rows stay current across it.
        bookRestorationService.restoreDeletedBooks(libraryEntity, bookFiles, currentFullPaths, coverage);
        if (bookDeletionService.purgeDisallowedFormats(libraryEntity, bookFiles)) {
            bookFiles = loadBookFiles(libraryEntity);
        }

        List<LibraryFile> newFiles = detectNewBookPaths(filteredFiles, bookFiles);

        // Use BookGroupingService to determine what to attach vs create new
        BookGroupingService.GroupingResult groupingResult = bookGroupingService.groupForRescan(newFiles, libraryEntity);

        // Auto-attach files to existing books
        groupingResult.filesToAttach().forEach(this::autoAttachFiles);

        // Process new book groups
        fileAsBookProcessor.processLibraryFilesGrouped(groupingResult.newBookGroups(), libraryEntity);

        notificationService.sendMessage(Topic.LOG, LogNotification.info("Finished refreshing library: " + libraryEntity.getName()));
    }

    private LibraryEntity loadLibrary(Long libraryId) {
        return libraryRepository.findById(libraryId)
                .orElseThrow(() -> ApiError.LIBRARY_NOT_FOUND.createException(libraryId));
    }

    private LibraryBookFiles loadBookFiles(LibraryEntity libraryEntity) {
        return new LibraryBookFiles(bookAdditionalFileRepository.findScanRowsByLibraryId(libraryEntity.getId()));
    }

    public void processLibraryFiles(List<LibraryFile> libraryFiles, LibraryEntity libraryEntity) {
        fileAsBookProcessor.processLibraryFiles(libraryFiles, libraryEntity);
    }

    private void validateLibraryPathsAccessible(LibraryEntity libraryEntity) {
        for (var pathEntity : libraryEntity.getLibraryPaths()) {
            Path path = Path.of(pathEntity.getPath());
            if (!Files.exists(path) || !Files.isDirectory(path) || !Files.isReadable(path)) {
                log.error("Library path not accessible: {}", path);
                throw ApiError.LIBRARY_PATH_NOT_ACCESSIBLE.createException(path.toString());
            }
        }
    }

    /**
     * Books whose primary file is not among the scanned paths. Soft-deleted books are skipped,
     * fileless books are never candidates, and a book is kept when the walk cannot vouch for
     * its primary file.
     */
    static RemovalCandidates detectDeletedBookIds(Set<Path> currentFullPaths, LibraryBookFiles bookFiles,
                                                  LibraryEntity libraryEntity, WalkCoverage coverage) {
        List<Long> ids = new ArrayList<>();
        int underUnreadPaths = 0;
        int changedDuringWalk = 0;
        for (var entry : bookFiles.primaryFiles(libraryEntity.getFormatPriority()).entrySet()) {
            BookFileScanRow primaryFile = entry.getValue();
            if (primaryFile.isBookDeleted()) {
                continue;
            }
            Path fullPath = primaryFile.fullPath();
            if (fullPath != null && currentFullPaths.contains(fullPath)) {
                continue;
            }
            if (coverage.mayHaveMissed(fullPath)) {
                underUnreadPaths++;
            } else if (coverage.changedDuringWalk(primaryFile)) {
                changedDuringWalk++;
            } else {
                ids.add(entry.getKey());
            }
        }
        return new RemovalCandidates(ids, underUnreadPaths, changedDuringWalk);
    }

    /**
     * Files already known to the library (any book_file row, soft-deleted books included)
     * are not new.
     */
    List<LibraryFile> detectNewBookPaths(List<LibraryFile> libraryFiles, LibraryBookFiles bookFiles) {
        Set<String> existingKeys = bookFiles.rows().stream()
                .map(this::generateUniqueKey)
                .collect(Collectors.toSet());

        return libraryFiles.stream()
                .filter(file -> !existingKeys.contains(generateUniqueKey(file)))
                .collect(Collectors.toList());
    }

    /**
     * Book-format files of the library that the scan did not find. Files the walk cannot vouch
     * for are kept.
     */
    RemovalCandidates detectDeletedAdditionalFiles(Set<String> currentFileKeys, LibraryBookFiles bookFiles,
                                                   WalkCoverage coverage) {
        List<Long> ids = new ArrayList<>();
        int underUnreadPaths = 0;
        int changedDuringWalk = 0;
        for (BookFileScanRow bookFile : bookFiles.rows()) {
            if (!bookFile.bookFormat() || currentFileKeys.contains(generateUniqueKey(bookFile))) {
                continue;
            }
            if (coverage.mayHaveMissed(bookFile.fullPath())) {
                underUnreadPaths++;
            } else if (coverage.changedDuringWalk(bookFile)) {
                changedDuringWalk++;
            } else {
                ids.add(bookFile.bookFileId());
            }
        }
        return new RemovalCandidates(ids, underUnreadPaths, changedDuringWalk);
    }

    private void logKeptCandidates(LibraryEntity libraryEntity, LibraryScanResult scanResult,
                                   RemovalCandidates removedBooks, RemovalCandidates removedFiles) {
        if (removedBooks.changedDuringWalk() > 0 || removedFiles.changedDuringWalk() > 0) {
            log.info("Library '{}': {} book(s) and {} book file(s) that the scan did not find changed while it ran, "
                            + "so they were kept; the next rescan checks them again",
                    libraryEntity.getName(), removedBooks.changedDuringWalk(), removedFiles.changedDuringWalk());
        }
        if (scanResult.isComplete()) {
            return;
        }
        List<String> paths = scanResult.incompletePaths().stream()
                .map(Path::toString)
                .sorted()
                .toList();
        String shown = String.join(", ", paths.subList(0, Math.min(paths.size(), MAX_LOGGED_INCOMPLETE_PATHS)));
        if (paths.size() > MAX_LOGGED_INCOMPLETE_PATHS) {
            shown += " ... and " + (paths.size() - MAX_LOGGED_INCOMPLETE_PATHS) + " more";
        }
        log.warn("Library '{}': the scan could not read {} path(s), so {} book(s) and {} book file(s) under them "
                        + "that were not seen were kept instead of being treated as deleted. Unreadable paths: {}",
                libraryEntity.getName(), paths.size(), removedBooks.underUnreadPaths(), removedFiles.underUnreadPaths(), shown);
    }

    /**
     * Attaches the files to one existing book in a transaction of its own. The book from
     * grouping is detached, so it is reloaded; a failure is logged and leaves other books alone.
     */
    private void autoAttachFiles(BookEntity matchedBook, List<LibraryFile> files) {
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                BookEntity book = bookRepository.findById(matchedBook.getId()).orElse(null);
                if (book == null) {
                    log.warn("Book id={} disappeared before its new files could be attached, skipping {} file(s)",
                            matchedBook.getId(), files.size());
                    return;
                }
                for (LibraryFile file : files) {
                    autoAttachFile(book, file);
                }
            });
        } catch (Exception e) {
            log.error("Error auto-attaching files to book id={}: {}", matchedBook.getId(), e.getMessage());
        }
    }

    private void autoAttachFile(BookEntity book, LibraryFile file) {
        // Check if file already exists to prevent duplicates during concurrent rescans
        var existing = bookAdditionalFileRepository.findByLibraryPath_IdAndFileSubPathAndFileName(
                file.getLibraryPathEntity().getId(), file.getFileSubPath(), file.getFileName());
        if (existing.isPresent()) {
            log.debug("Additional file already exists, skipping: {}", file.getFileName());
            return;
        }

        // Set libraryPath if not set (fileless books like physical books don't have one)
        if (book.getLibraryPath() == null) {
            book.setLibraryPath(file.getLibraryPathEntity());
        } else if (!book.getLibraryPath().getId().equals(file.getLibraryPathEntity().getId())) {
            // Book already has a different libraryPath - cannot attach files from different paths
            log.warn("Cannot attach file '{}' to book id={}: file is in libraryPath {} but book is in libraryPath {}",
                    file.getFileName(), book.getId(), file.getLibraryPathEntity().getId(), book.getLibraryPath().getId());
            return;
        }

        String hash = file.isFolderBased()
                ? FileFingerprint.generateFolderHash(file.getFullPath())
                : FileFingerprint.generateHash(file.getFullPath());
        Long fileSizeKb = file.isFolderBased()
                ? FileUtils.getFolderSizeInKb(file.getFullPath())
                : FileUtils.getFileSizeInKb(file.getFullPath());
        BookFileEntity additionalFile = BookFileEntity.builder()
                .book(book)
                .fileName(file.getFileName())
                .fileSubPath(file.getFileSubPath())
                .isBookFormat(true)
                .bookType(file.getBookFileType())
                .folderBased(file.isFolderBased())
                .fileSizeKb(fileSizeKb)
                .initialHash(hash)
                .currentHash(hash)
                .addedOn(Instant.now())
                .build();

        try {
            bookAdditionalFileRepository.save(additionalFile);
            String primaryFileName = book.hasFiles() ? book.getPrimaryBookFile().getFileName() : "book#" + book.getId();
            log.info("Auto-attached new format {} to existing book: {}", file.getFileName(), primaryFileName);
            fileAsBookProcessor.generateCoverFromAdditionalFile(book, file);
        } catch (Exception e) {
            log.error("Error auto-attaching file {}: {}", file.getFileName(), e.getMessage());
        }
    }

    // Keys use the owning book's library path, as BookFileEntity.getBook().getLibraryPath() did.
    private String generateUniqueKey(BookFileScanRow file) {
        return generateKey(file.libraryPathId(), file.fileSubPath(), file.fileName());
    }

    private String generateUniqueKey(LibraryFile file) {
        return generateKey(file.getLibraryPathEntity().getId(), file.getFileSubPath(), file.getFileName());
    }

    private String generateKey(Long libraryPathId, String subPath, String fileName) {
        String safeSubPath = (subPath == null) ? "" : subPath;
        return libraryPathId + ":" + safeSubPath + ":" + fileName;
    }
}
