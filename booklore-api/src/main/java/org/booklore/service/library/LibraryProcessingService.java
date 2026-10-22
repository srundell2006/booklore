package org.booklore.service.library;

import org.booklore.exception.APIException;
import org.booklore.exception.ApiError;
import org.booklore.model.dto.settings.LibraryFile;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.entity.LibraryPathEntity;
import org.booklore.model.websocket.LogNotification;
import org.booklore.model.websocket.Topic;
import org.booklore.repository.BookAdditionalFileRepository;
import org.booklore.repository.BookRepository;
import org.booklore.repository.LibraryRepository;
import org.booklore.repository.projection.BookFileScanRow;
import org.booklore.service.NotificationService;
import org.booklore.service.library.importer.ImportSummary;
import org.booklore.task.options.RescanLibraryContext;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
    private final ExistingBookAttacher existingBookAttacher;

    /**
     * Ids a rescan judged gone from disk, plus how many more it did not find but kept because
     * the walk could not vouch for them: they lie under a path it could not read, or they
     * changed while it ran.
     */
    record RemovalCandidates(List<Long> ids, int underUnreadPaths, int changedDuringWalk) {}

    /** An initial scan. A failure is left to the caller to report, as for {@link #rescanLibrary}. */
    public void processLibrary(long libraryId) throws IOException {
        LibraryEntity libraryEntity = loadLibrary(libraryId);
        notificationService.sendMessage(Topic.LOG, LogNotification.info("Started processing library: " + libraryEntity.getName()));
        List<LibraryFile> libraryFiles = libraryFileHelper.getLibraryFiles(libraryEntity);
        List<LibraryFile> newFiles = detectNewBookPaths(libraryFiles, loadBookFiles(libraryEntity));

        // Use BookGroupingService for consistent grouping based on organization mode
        Map<String, List<LibraryFile>> groups = bookGroupingService.groupForInitialScan(newFiles, libraryEntity);
        notifySkipped(libraryEntity, fileAsBookProcessor.processLibraryFilesGrouped(groups, libraryEntity));

        notificationService.sendMessage(Topic.LOG, LogNotification.info("Finished processing library: " + libraryEntity.getName()));
    }

    /**
     * A rescan. One that cannot vouch for the library's files (a library path it cannot read, or
     * a walk that finds none of the library's books) stops before changing anything, with an
     * {@link APIException} whose message says why, for the caller to report.
     */
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
                // More likely a share that is down, or that the walk could not read, than every book deleted.
                List<Path> unread = scanResult.unreadPaths();
                if (!unread.isEmpty()) {
                    throw rescanStopped(libraryEntity, couldNotBeRead(unread, scanResult.timedOut()));
                }
                // The paths were read and hold no books: an empty mount point, most likely.
                String paths = libraryEntity.getLibraryPaths().stream()
                        .map(LibraryPathEntity::getPath)
                        .collect(Collectors.joining(", "));
                throw rescanStopped(libraryEntity, "no book files were found in " + paths + ", which may not be mounted");
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

        // A restored book is live again, and grouping below matches new files only to live books: a new
        // format next to it must go to it, not become a second book.
        if (bookRestorationService.restoreDeletedBooks(libraryEntity, bookFiles, currentFullPaths, coverage)) {
            bookFiles = loadBookFiles(libraryEntity);
        }
        if (bookDeletionService.purgeDisallowedFormats(libraryEntity, bookFiles)) {
            bookFiles = loadBookFiles(libraryEntity);
        }

        List<LibraryFile> newFiles = detectNewBookPaths(filteredFiles, bookFiles);

        // Which new files belong to books the library has, decided from the rows already read
        BookGroupingService.GroupingResult groupingResult = bookGroupingService.groupForRescan(newFiles, libraryEntity, bookFiles);

        groupingResult.filesToAttach().forEach(this::autoAttachFiles);

        // Process new book groups
        notifySkipped(libraryEntity, fileAsBookProcessor.processLibraryFilesGrouped(groupingResult.newBookGroups(), libraryEntity));

        notifyIncomplete(libraryEntity, scanResult, rowsBeforeWalk);
        notificationService.sendMessage(Topic.LOG, LogNotification.info("Finished refreshing library: " + libraryEntity.getName()));
    }

    /**
     * Tells the user what a rescan that went ahead left alone because the walk could not vouch for
     * it: paths it could not read, and library paths found empty that held books, which may be
     * shares that are not mounted. An empty library path that held none (a new library, say) is
     * not worth a warning.
     */
    private void notifyIncomplete(LibraryEntity libraryEntity, LibraryScanResult scanResult, Set<BookFileScanRow> rowsBeforeWalk) {
        List<Path> unread = scanResult.unreadPaths();
        if (!unread.isEmpty()) {
            notificationService.sendMessage(Topic.LOG, LogNotification.warn("Refreshed library '" + libraryEntity.getName()
                    + "', but " + couldNotBeRead(unread, scanResult.timedOut()) + "; no books there were removed."));
        }
        List<String> emptiedOfBooks = scanResult.emptyLibraryPaths().stream()
                .filter(path -> rowsBeforeWalk.stream().anyMatch(row -> isUnder(row.fullPath(), path)))
                .map(Path::toString)
                .sorted()
                .toList();
        if (!emptiedOfBooks.isEmpty()) {
            notificationService.sendMessage(Topic.LOG, LogNotification.warn("Refreshed library '" + libraryEntity.getName()
                    + "', but no book files were found in " + String.join(", ", emptiedOfBooks)
                    + ", which may not be mounted; no books there were removed."));
        }
    }

    private static boolean isUnder(Path file, Path directory) {
        return file != null && file.toAbsolutePath().normalize().startsWith(directory);
    }

    private static APIException rescanStopped(LibraryEntity libraryEntity, String reason) {
        return new APIException("Library '" + libraryEntity.getName() + "' was not rescanned: " + reason
                + "; no books were removed.", ApiError.LIBRARY_PATH_NOT_ACCESSIBLE.getStatus());
    }

    /** E.g. "/books/A and 2 other paths could not be read in time", for at least one path. */
    private static String couldNotBeRead(List<Path> paths, boolean timedOut) {
        int others = paths.size() - 1;
        return paths.getFirst()
                + (others == 0 ? "" : " and " + others + (others == 1 ? " other path" : " other paths"))
                + " could not be read" + (timedOut ? " in time" : "");
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

    /**
     * Tells the user when an import left new files for the next scan. The sequential import keeps no counts, so it
     * never does.
     */
    private void notifySkipped(LibraryEntity libraryEntity, ImportSummary summary) {
        if (summary == null || summary.legacy()) {
            return;
        }
        String name = libraryEntity.getName();
        int skipped = summary.skippedForRetry();
        if (summary.stoppedStorageStuck()) {
            notificationService.sendMessage(Topic.LOG, LogNotification.error("Storage for library '" + name + "' stopped responding; "
                    + skipped + " new files were not added. They will be retried on the next scan. "
                    + "Check the share, or restart Booklore if it stays stuck."));
        } else if (summary.stoppedDatabaseErrors()) {
            notificationService.sendMessage(Topic.LOG, LogNotification.error("Saving new books in library '" + name
                    + "' kept failing, so the import stopped; " + skipped + " new files were not added. "
                    + "They will be retried on the next scan."));
        } else if (skipped > 0) {
            notificationService.sendMessage(Topic.LOG, LogNotification.warn(skipped + " new files in library '" + name
                    + "' were not added because storage did not respond in time; they will be retried on the next scan."));
        }
    }

    private void validateLibraryPathsAccessible(LibraryEntity libraryEntity) {
        for (var pathEntity : libraryEntity.getLibraryPaths()) {
            Path path = Path.of(pathEntity.getPath());
            if (!Files.exists(path) || !Files.isDirectory(path) || !Files.isReadable(path)) {
                throw rescanStopped(libraryEntity, path + " is not accessible");
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
     * Attaches the files to one existing book in a transaction of its own. A failure is logged and leaves other books
     * alone; files not attached are new again to the next scan.
     */
    private void autoAttachFiles(BookGroupingService.Attachment attachment) {
        try {
            existingBookAttacher.attach(attachment.bookId(), attachment.kind(), attachment.files());
        } catch (Exception e) {
            log.error("Error auto-attaching {} to book#{}: {}", attachment.files().stream().map(LibraryFile::getFileName).toList(),
                    attachment.bookId(), e.getMessage());
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
