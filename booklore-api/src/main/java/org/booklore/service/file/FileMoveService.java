package org.booklore.service.file;

import jakarta.persistence.EntityManager;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.config.AppProperties;
import org.booklore.mapper.BookMapper;
import org.booklore.mapper.LibraryMapper;
import org.booklore.model.dto.FileMoveResult;
import org.booklore.model.dto.Library;
import org.booklore.model.dto.request.FileMoveRequest;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.entity.LibraryPathEntity;
import org.booklore.model.websocket.Topic;
import org.booklore.repository.BookAdditionalFileRepository;
import org.booklore.repository.BookRepository;
import org.booklore.repository.LibraryRepository;
import org.booklore.service.NotificationService;
import org.booklore.service.metadata.sidecar.SidecarMetadataWriter;
import org.booklore.service.monitoring.MonitoringRegistrationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.Normalizer;
import java.util.*;
import java.util.stream.Collectors;

@AllArgsConstructor
@Service
@Slf4j
public class FileMoveService {

    private static final long EVENT_DRAIN_TIMEOUT_MS = 300;

    private final AppProperties appProperties;
    private final BookRepository bookRepository;
    private final BookAdditionalFileRepository bookFileRepository;
    private final LibraryRepository libraryRepository;
    private final FileMoveHelper fileMoveHelper;
    private final MonitoringRegistrationService monitoringRegistrationService;
    private final LibraryMapper libraryMapper;
    private final BookMapper bookMapper;
    private final NotificationService notificationService;
    private final EntityManager entityManager;
    private final TransactionTemplate transactionTemplate;
    private final SidecarMetadataWriter sidecarMetadataWriter;


    public void bulkMoveFiles(FileMoveRequest request) {
        List<FileMoveRequest.Move> moves = request.getMoves();

        validateLocalStorage();

        Set<Long> allAffectedLibraryIds = collectAllAffectedLibraryIds(moves);
        Set<Path> libraryPaths = monitoringRegistrationService.getPathsForLibraries(allAffectedLibraryIds);

        log.info("Unregistering {} libraries before bulk file move", allAffectedLibraryIds.size());
        monitoringRegistrationService.unregisterLibraries(allAffectedLibraryIds);
        monitoringRegistrationService.waitForEventsDrainedByPaths(libraryPaths, EVENT_DRAIN_TIMEOUT_MS);

        try {
            for (FileMoveRequest.Move move : moves) {
                processSingleMove(move);
            }
            // Ensure any file system events from the moves are drained/ignored while we are still unregistered
            sleep(EVENT_DRAIN_TIMEOUT_MS);
        } finally {
            for (Long libraryId : allAffectedLibraryIds) {
                libraryRepository.findById(libraryId)
                        .ifPresent(library -> monitoringRegistrationService.registerLibrary(libraryMapper.toLibrary(library)));
            }
        }
    }

    private Set<Long> collectAllAffectedLibraryIds(List<FileMoveRequest.Move> moves) {
        Set<Long> libraryIds = new HashSet<>();
        
        for (FileMoveRequest.Move move : moves) {
            libraryIds.add(move.getTargetLibraryId());
            bookRepository.findById(move.getBookId())
                    .ifPresent(book -> libraryIds.add(book.getLibrary().getId()));
        }
        
        return libraryIds;
    }

    private void processSingleMove(FileMoveRequest.Move move) {
        Long bookId = move.getBookId();
        Long targetLibraryId = move.getTargetLibraryId();
        Long targetLibraryPathId = move.getTargetLibraryPathId();

        try {
            Optional<BookEntity> optionalBook = bookRepository.findByIdWithBookFiles(bookId);
            Optional<LibraryEntity> optionalLibrary = libraryRepository.findById(targetLibraryId);
            if (optionalBook.isEmpty()) {
                log.warn("Book not found for move operation: bookId={}", bookId);
                return;
            }
            if (optionalLibrary.isEmpty()) {
                log.warn("Target library not found for move operation: libraryId={}", targetLibraryId);
                return;
            }
            BookEntity bookEntity = optionalBook.get();
            LibraryEntity targetLibrary = optionalLibrary.get();

            Optional<LibraryPathEntity> optionalLibraryPathEntity = targetLibrary.getLibraryPaths().stream()
                    .filter(libraryPath -> Objects.equals(libraryPath.getId(), targetLibraryPathId))
                    .findFirst();
            if (optionalLibraryPathEntity.isEmpty()) {
                log.warn("Target library path not found for move operation: libraryId={}, pathId={}", targetLibraryId, targetLibraryPathId);
                return;
            }
            LibraryPathEntity libraryPathEntity = optionalLibraryPathEntity.get();

            if (bookEntity.getBookFiles() == null || bookEntity.getBookFiles().isEmpty()) {
                log.warn("Book has no files to move: bookId={}", bookId);
                return;
            }

            Path currentPrimaryFilePath = bookEntity.getFullFilePath();
            String pattern = fileMoveHelper.getFileNamingPattern(targetLibrary);
            Path newFilePath = fileMoveHelper.generateNewFilePath(bookEntity, libraryPathEntity, pattern);

            if (FileMoveHelper.isSamePathIgnoringNormalization(currentPrimaryFilePath, newFilePath)) {
                return;
            }

            Path targetParentDir = newFilePath.getParent();

            if (targetParentDir == null) {
                log.warn("Target parent directory could not be determined for move operation: bookId={}", bookId);
                return;
            }

            // Validate all source paths exist before attempting moves
            for (var bookFile : bookEntity.getBookFiles()) {
                Path sourcePath = bookFile.getFullFilePath();
                if (!fileMoveHelper.validateSourceExists(sourcePath, bookFile.isFolderBased())) {
                    log.warn("Source {} not found: bookId={}, path={}",
                            bookFile.isFolderBased() ? "folder" : "file", bookId, sourcePath);
                    return;
                }
            }

            // Work out every file's target, and leave the whole book in place if one of them is taken
            List<FileTarget> fileTargets = planFileTargets(bookEntity, libraryPathEntity, pattern, targetParentDir);
            if (fileTargets.isEmpty()) {
                return;
            }

            // Move the files FIRST before updating database
            if (!moveFiles(bookId, fileTargets)) {
                return;
            }
            Map<Long, Path> finalPathsByBookFileId = finalPaths(bookEntity, fileTargets);

            // Only update database after all file commits succeed
            try {
                transactionTemplate.executeWithoutResult(status -> {
                    for (var bookFile : bookEntity.getBookFiles()) {
                        Path finalPath = finalPathsByBookFileId.get(bookFile.getId());
                        bookFileRepository.updateFileNameAndSubPath(bookFile.getId(), finalPath.getFileName().toString(),
                                fileMoveHelper.extractSubPath(finalPath, libraryPathEntity));
                    }

                    bookRepository.updateLibrary(bookEntity.getId(), targetLibrary.getId(), libraryPathEntity);
                });
            } catch (Exception e) {
                log.error("Database update failed after files were moved. Attempting to rollback file moves for book ID {}", bookId, e);
                moveBack(fileTargets);
                throw e;
            }
            moveBackIfRolledBack(bookId, fileTargets);

            Set<Path> libraryRoots = targetLibrary.getLibraryPaths().stream()
                    .map(LibraryPathEntity::getPath)
                    .map(Paths::get)
                    .map(Path::toAbsolutePath)
                    .map(Path::normalize)
                    .collect(Collectors.toSet());
            
            for (Path sourceParent : sourceParents(fileTargets)) {
                fileMoveHelper.deleteEmptyParentDirsUpToLibraryFolders(sourceParent, libraryRoots);
            }

            moveSidecarFiles(bookId, currentPrimaryFilePath, newFilePath);

            entityManager.clear();

            BookEntity fresh = bookRepository.findById(bookId).orElseThrow();

            notificationService.sendMessage(Topic.BOOK_UPDATE, bookMapper.toBookWithDescription(fresh, false));

        } catch (Exception e) {
            log.error("Error moving file for book ID {}: {}", bookId, e.getMessage(), e);
        }
    }

    public FileMoveResult moveSingleFile(BookEntity bookEntity) {
        validateLocalStorage();

        Long libraryId = bookEntity.getLibraryPath().getLibrary().getId();
        Path libraryRoot = Paths.get(bookEntity.getLibraryPath().getPath()).toAbsolutePath().normalize();
        boolean isLibraryMonitoredWhenCalled = false;

        try {
            Set<Path> existingPaths = monitoringRegistrationService.getPathsForLibraries(Set.of(libraryId));
            isLibraryMonitoredWhenCalled = monitoringRegistrationService.isLibraryMonitored(libraryId) || !existingPaths.isEmpty();

            // Ensure bookFiles are loaded
            BookEntity bookWithFiles = bookEntity;
            if (bookEntity.getBookFiles() == null || bookEntity.getBookFiles().isEmpty()) {
                Optional<BookEntity> optionalBook = bookRepository.findByIdWithBookFiles(bookEntity.getId());
                if (optionalBook.isEmpty()) {
                    log.warn("Book not found for move operation: bookId={}", bookEntity.getId());
                    return FileMoveResult.builder().moved(false).build();
                }
                bookWithFiles = optionalBook.get();
            }

            if (bookWithFiles.getBookFiles() == null || bookWithFiles.getBookFiles().isEmpty()) {
                log.warn("Book has no files to move: bookId={}", bookEntity.getId());
                return FileMoveResult.builder().moved(false).build();
            }

            String pattern = fileMoveHelper.getFileNamingPattern(bookWithFiles.getLibraryPath().getLibrary());
            Path currentPrimaryFilePath = bookWithFiles.getFullFilePath();
            Path expectedPrimaryFilePath = fileMoveHelper.generateNewFilePath(bookWithFiles, bookWithFiles.getLibraryPath(), pattern);

            if (FileMoveHelper.isSamePathIgnoringNormalization(currentPrimaryFilePath, expectedPrimaryFilePath)) {
                return FileMoveResult.builder().moved(false).build();
            }

            log.info("Files for book ID {} need to be moved to match library pattern", bookWithFiles.getId());

            String newFileSubPath = fileMoveHelper.extractSubPath(expectedPrimaryFilePath, bookWithFiles.getLibraryPath());
            Path targetParentDir = expectedPrimaryFilePath.getParent();

            if (targetParentDir == null) {
                log.warn("Target parent directory could not be determined for move operation: bookId={}", bookWithFiles.getId());
                return FileMoveResult.builder().moved(false).build();
            }

            // Validate all source paths exist before attempting moves
            for (var bookFile : bookWithFiles.getBookFiles()) {
                Path sourcePath = bookFile.getFullFilePath();
                if (!fileMoveHelper.validateSourceExists(sourcePath, bookFile.isFolderBased())) {
                    log.warn("Source {} not found: bookId={}, path={}",
                            bookFile.isFolderBased() ? "folder" : "file", bookWithFiles.getId(), sourcePath);
                    return FileMoveResult.builder().moved(false).build();
                }
            }

            // Work out every file's target, and leave the whole book in place if one of them is taken
            List<FileTarget> fileTargets = planFileTargets(bookWithFiles, bookWithFiles.getLibraryPath(), pattern, targetParentDir);
            if (fileTargets.isEmpty() || !targetsAreFree(bookWithFiles.getId(), fileTargets)) {
                return FileMoveResult.builder().moved(false).build();
            }

            if (isLibraryMonitoredWhenCalled) {
                log.debug("Unregistering library {} before moving files", libraryId);
                fileMoveHelper.unregisterLibrary(libraryId);
                monitoringRegistrationService.waitForEventsDrainedByPaths(existingPaths, EVENT_DRAIN_TIMEOUT_MS);
            }

            // Move all files FIRST before updating database
            if (!moveFiles(bookWithFiles.getId(), fileTargets)) {
                return FileMoveResult.builder().moved(false).build();
            }
            Map<Long, Path> finalPathsByBookFileId = finalPaths(bookWithFiles, fileTargets);

            // Update database for ALL BookFileEntity records (only after all commits succeed)
            BookEntity finalBookWithFiles = bookWithFiles;
            try {
                transactionTemplate.executeWithoutResult(status -> {
                    for (var bookFile : finalBookWithFiles.getBookFiles()) {
                        Path finalPath = finalPathsByBookFileId.get(bookFile.getId());
                        bookFileRepository.updateFileNameAndSubPath(bookFile.getId(), finalPath.getFileName().toString(),
                                fileMoveHelper.extractSubPath(finalPath, finalBookWithFiles.getLibraryPath()));
                    }
                });
            } catch (Exception e) {
                log.error("Database update failed after files were moved. Attempting to rollback file moves for book ID {}", bookEntity.getId(), e);
                moveBack(fileTargets);
                throw e;
            }
            moveBackIfRolledBack(bookEntity.getId(), fileTargets);

            // Clean up empty parent directories
            Set<Path> libraryRoots = bookWithFiles.getLibraryPath().getLibrary().getLibraryPaths().stream()
                    .map(LibraryPathEntity::getPath)
                    .map(Paths::get)
                    .map(Path::toAbsolutePath)
                    .map(Path::normalize)
                    .collect(Collectors.toSet());
            
            for (Path sourceParent : sourceParents(fileTargets)) {
                fileMoveHelper.deleteEmptyParentDirsUpToLibraryFolders(sourceParent, libraryRoots);
            }

            moveSidecarFiles(bookEntity.getId(), currentPrimaryFilePath, expectedPrimaryFilePath);

            if (isLibraryMonitoredWhenCalled) {
                // Ensure any file system events from the move and cleanup are drained/ignored while we are still unregistered
                sleep(EVENT_DRAIN_TIMEOUT_MS);
            }

            String newFileName = expectedPrimaryFilePath.getFileName().toString();

            return FileMoveResult.builder()
                    .moved(true)
                    .newFileName(newFileName)
                    .newFileSubPath(newFileSubPath)
                    .build();
        } catch (Exception e) {
            log.error("Failed to move files for book ID {}: {}", bookEntity.getId(), e.getMessage(), e);
        } finally {
            if (isLibraryMonitoredWhenCalled) {
                log.debug("Registering library paths for library {} with root {}", libraryId, libraryRoot);
                LibraryEntity libraryEntity = bookEntity.getLibraryPath().getLibrary();
                Library library = libraryMapper.toLibrary(libraryEntity);
                library.setWatch(true);
                monitoringRegistrationService.registerLibrary(library);
            }
        }

        return FileMoveResult.builder().moved(false).build();
    }

    /** Where one of a book's files goes: never where it already is. */
    private record FileTarget(Long bookFileId, Path source, Path target) {}

    private List<FileTarget> planFileTargets(BookEntity book, LibraryPathEntity libraryPath, String pattern, Path targetParentDir) throws IOException {
        List<FileTarget> fileTargets = new ArrayList<>();
        for (var bookFile : book.getBookFiles()) {
            Path sourcePath = bookFile.getFullFilePath();
            Path targetPath;
            if (bookFile.isBook()) {
                targetPath = fileMoveHelper.generateNewFilePath(book, bookFile, libraryPath, pattern);
            } else {
                targetPath = targetParentDir.resolve(bookFile.getFileName());
            }

            if (!FileMoveHelper.isAlreadyInPlace(sourcePath, targetPath)) {
                fileTargets.add(new FileTarget(bookFile.getId(), sourcePath, targetPath));
            }
        }
        return fileTargets;
    }

    /**
     * Whether every target is free. A move never replaces another book's file or folder, so when
     * something other than a file's own source is at its target, or two of the book's files would
     * be moved to the same target (two EPUBs of one book, named by a pattern without
     * {currentFilename}), the whole book stays where it is.
     */
    private boolean targetsAreFree(Long bookId, List<FileTarget> fileTargets) throws IOException {
        Map<String, Path> sourcesByTarget = new HashMap<>();
        for (FileTarget fileTarget : fileTargets) {
            // Shares may ignore case and composed vs decomposed spelling, so targets differing only in those are the same
            String comparableTarget = Normalizer.normalize(fileTarget.target().toString(), Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
            Path otherSource = sourcesByTarget.putIfAbsent(comparableTarget, fileTarget.source());
            if (otherSource != null) {
                log.warn("Not moving book ID {}: {} and {} would both be moved to {}",
                        bookId, otherSource, fileTarget.source(), fileTarget.target());
                return false;
            }
            if (fileMoveHelper.isTakenByAnotherFile(fileTarget.target(), fileTarget.source())) {
                log.warn("Not moving book ID {}: {} would replace {}, which is another file",
                        bookId, fileTarget.source(), fileTarget.target());
                return false;
            }
        }
        return true;
    }

    /**
     * Moves every file to its target, by way of a temporary name next to its source. No other move
     * runs in between, and the targets are checked again first: another book may have been moved
     * onto one since they were planned. Returns false, moving nothing, when one is taken. When any
     * file fails to move, every file is put back where it was before the exception is rethrown, so
     * that a book is never left half moved.
     */
    private boolean moveFiles(Long bookId, List<FileTarget> fileTargets) throws IOException {
        synchronized (FileMoveHelper.MOVE_LOCK) {
            if (!targetsAreFree(bookId, fileTargets)) {
                return false;
            }
            Map<FileTarget, Path> tempPaths = new HashMap<>();
            Set<FileTarget> committed = new HashSet<>();
            try {
                for (FileTarget fileTarget : fileTargets) {
                    tempPaths.put(fileTarget, fileMoveHelper.moveFileWithBackup(fileTarget.source()));
                }
                for (FileTarget fileTarget : fileTargets) {
                    fileMoveHelper.commitMove(tempPaths.get(fileTarget), fileTarget.target());
                    committed.add(fileTarget);
                }
                return true;
            } catch (IOException | RuntimeException e) {
                for (FileTarget fileTarget : fileTargets) {
                    Path movedPath = committed.contains(fileTarget) ? fileTarget.target() : tempPaths.get(fileTarget);
                    if (movedPath != null) {
                        fileMoveHelper.rollbackMove(movedPath, fileTarget.source());
                    }
                }
                throw e;
            }
        }
    }

    /** Puts moved files back where they were, never over a file that has taken a source's place since. */
    private void moveBack(List<FileTarget> fileTargets) {
        synchronized (FileMoveHelper.MOVE_LOCK) {
            for (FileTarget fileTarget : fileTargets) {
                fileMoveHelper.rollbackMove(fileTarget.target(), fileTarget.source());
            }
        }
    }

    /**
     * The new paths may have been written in the caller's transaction (a metadata refresh), which
     * can still roll back after the move, and the rows with it. The files then go back too, so
     * that the rows never name files that are no longer there.
     */
    private void moveBackIfRolledBack(Long bookId, List<FileTarget> fileTargets) {
        if (!TransactionSynchronizationManager.isSynchronizationActive() || !TransactionSynchronizationManager.isActualTransactionActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    log.warn("Transaction rolled back after files were moved. Moving the files of book ID {} back", bookId);
                    moveBack(fileTargets);
                }
            }
        });
    }

    private Set<Path> sourceParents(List<FileTarget> fileTargets) {
        Set<Path> sourceParents = new HashSet<>();
        for (FileTarget fileTarget : fileTargets) {
            if (fileTarget.source().getParent() != null) {
                sourceParents.add(fileTarget.source().getParent());
            }
        }
        return sourceParents;
    }

    /**
     * Moves the book's sidecar files after it, unless their new names are taken: books of different
     * formats with the same name share sidecar names, and another book's sidecars are never replaced.
     */
    private void moveSidecarFiles(Long bookId, Path oldBookPath, Path newBookPath) {
        try {
            if (isSidecarTaken(sidecarMetadataWriter.getSidecarPath(oldBookPath), sidecarMetadataWriter.getSidecarPath(newBookPath))
                    || isSidecarTaken(sidecarMetadataWriter.getCoverPath(oldBookPath), sidecarMetadataWriter.getCoverPath(newBookPath))) {
                log.warn("Not moving the sidecar files of book ID {}: another book's sidecar files are already named after {}", bookId, newBookPath);
                return;
            }
            sidecarMetadataWriter.moveSidecarFiles(oldBookPath, newBookPath);
        } catch (Exception e) {
            log.warn("Failed to move sidecar files for book ID {}: {}", bookId, e.getMessage());
        }
    }

    private boolean isSidecarTaken(Path oldSidecarPath, Path newSidecarPath) throws IOException {
        return Files.exists(oldSidecarPath) && fileMoveHelper.isTakenByAnotherFile(newSidecarPath, oldSidecarPath);
    }

    /**
     * Where each of the book's files is once the move is done. A file left in place keeps the name
     * it has on disk, which may be spelt decomposed where its target is composed.
     */
    private Map<Long, Path> finalPaths(BookEntity book, List<FileTarget> fileTargets) {
        Map<Long, Path> finalPathsByBookFileId = new HashMap<>();
        for (var bookFile : book.getBookFiles()) {
            finalPathsByBookFileId.put(bookFile.getId(), bookFile.getFullFilePath());
        }
        for (FileTarget fileTarget : fileTargets) {
            finalPathsByBookFileId.put(fileTarget.bookFileId(), fileTarget.target());
        }
        return finalPathsByBookFileId;
    }

    protected void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Sleep interrupted", e);
        }
    }

    private void validateLocalStorage() {
        if (!appProperties.isLocalStorage()) {
            throw new IllegalStateException(
                    "File move operations are only supported on local storage. " +
                    "Current disk type is configured as: " + appProperties.getDiskType() + ". " +
                    "If you are using local storage, set DISK_TYPE=LOCAL in your environment."
            );
        }
    }
}
