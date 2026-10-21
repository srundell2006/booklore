package org.booklore.service.file;

import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.entity.LibraryPathEntity;
import org.booklore.service.appsettings.AppSettingService;
import org.booklore.service.monitoring.MonitoringRegistrationService;
import org.booklore.util.PathPatternResolver;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.Normalizer;
import java.time.Duration;
import java.util.Collection;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.CRC32;

@Slf4j
@Component
@AllArgsConstructor
public class FileMoveHelper {

    /**
     * Held from checking a move's targets until its files are moved or put back. Java cannot rename
     * without replacing whatever is at the target, so two books moved at the same time (metadata
     * refreshes run in parallel) could otherwise both find a target free and the second replace the
     * first. Taken with {@link #lockMoves}, which waits for it only so long: a move stuck in IO on
     * the share never lets go of it.
     */
    private static final ReentrantLock MOVE_LOCK = new ReentrantLock(true);
    /**
     * How long a move waits for the moves ahead of it. Long enough for them to ride out a CIFS
     * reconnect, during which every call to the share blocks for up to two minutes.
     */
    public static final Duration MOVE_LOCK_WAIT = Duration.ofMinutes(5);
    /** How many times the move lock has been taken; written only by its holder. */
    private static volatile long moveLockHolds;
    /** The hold of the move lock that a move gave up waiting for. */
    private static volatile long abandonedHold = -1;

    private final MonitoringRegistrationService monitoringRegistrationService;
    private final AppSettingService appSettingService;

    private static final int MAX_ATTEMPTS = 3;
    private static final long RETRY_DELAY_MS = 100;
    private static final Set<Class<? extends Exception>> RETRYABLE_EXCEPTIONS = Set.of(
            NoSuchFileException.class,
            AccessDeniedException.class,
            FileSystemException.class,
            DirectoryNotEmptyException.class
    );
    private static final Set<String> IGNORED_FILENAMES = Set.of(".DS_Store", "Thumbs.db");
    private static final String TEMP_SUFFIX = ".tmp_move";
    private static final int MAX_NAME_BYTES = 255;
    // "~" and 8 hex digits
    private static final int HASH_SUFFIX_BYTES = 9;

    /**
     * Takes the move lock, waiting for it at most {@code wait}. Returns false, without the lock,
     * when it is still held then, and at once while it is held by a move an earlier wait gave up
     * on: that move is stuck, and every move after it would otherwise wait out its time too.
     */
    public static boolean lockMoves(Duration wait) {
        if (moveLockHolds == abandonedHold && MOVE_LOCK.isLocked() && !MOVE_LOCK.isHeldByCurrentThread()) {
            return false;
        }
        try {
            if (!MOVE_LOCK.tryLock(wait.toNanos(), TimeUnit.NANOSECONDS)) {
                abandonedHold = moveLockHolds;
                return false;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        if (MOVE_LOCK.getHoldCount() == 1) {
            moveLockHolds++;
        }
        return true;
    }

    public static void unlockMoves() {
        MOVE_LOCK.unlock();
    }

    public boolean waitForFileAccessible(Path path) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            if (Files.exists(path) && Files.isReadable(path)) {
                return true;
            }
            if (attempt < MAX_ATTEMPTS) {
                log.debug("File not accessible on attempt {}/{}, waiting {}ms: {}",
                        attempt, MAX_ATTEMPTS, RETRY_DELAY_MS, path);
                waitBeforeRetry();
            }
        }
        return false;
    }

    public boolean validateSourceExists(Path sourcePath, boolean isFolderBased) {
        if (isFolderBased) {
            return Files.isDirectory(sourcePath);
        }
        return Files.exists(sourcePath);
    }

    public void moveFile(Path source, Path target) throws IOException {
        if (!waitForFileAccessible(source)) {
            throw new NoSuchFileException(source.toString(), null, "Source file not accessible after retries");
        }

        if (target.getParent() != null) {
            Files.createDirectories(target.getParent());
        }

        log.info("Moving file from {} to {}", source, target);
        executeWithRetry(() -> Files.move(source, target, StandardCopyOption.REPLACE_EXISTING));
    }

    public Path moveFileWithBackup(Path source) throws IOException {
        if (!waitForFileAccessible(source)) {
            throw new NoSuchFileException(source.toString(), null, "Source file not accessible after retries");
        }

        Path tempPath = temporaryPath(source);
        log.info("Moving file from {} to temporary location {}", source, tempPath);
        // A file already there is one a failed rollback left behind, and may be the only copy of a book
        executeWithRetry(() -> rename(source, tempPath), () -> hasMoved(source, tempPath));
        return tempPath;
    }

    /**
     * The temporary name a file or folder is moved aside to, next to it: its own name and
     * ".tmp_move". A name too long to take that within 255 bytes is cut short at a character
     * boundary and ends in "~" and a hash of the whole name, so that it can still be moved and
     * two long names never share a temporary name.
     */
    public static Path temporaryPath(Path source) {
        String name = source.getFileName().toString();
        byte[] bytes = name.getBytes(StandardCharsets.UTF_8);
        if (bytes.length + TEMP_SUFFIX.length() <= MAX_NAME_BYTES) {
            return source.resolveSibling(name + TEMP_SUFFIX);
        }
        CRC32 hash = new CRC32();
        hash.update(bytes);
        return source.resolveSibling(PathPatternResolver.truncatePathComponent(name, MAX_NAME_BYTES - TEMP_SUFFIX.length() - HASH_SUFFIX_BYTES)
                + "~" + HexFormat.of().toHexDigits((int) hash.getValue()) + TEMP_SUFFIX);
    }

    /**
     * Whether {@code target} is taken by a file or folder other than {@code source}. A target that
     * finds the source itself is not: a case-only rename on a case-insensitive share, or a composed
     * name that finds the decomposed file on a normalization-insensitive one.
     */
    public boolean isTakenByAnotherFile(Path target, Path source) throws IOException {
        return !isAbsent(target) && !Files.isSameFile(source, target);
    }

    /**
     * Whether nothing at all is at {@code path}, not even a dangling link. Files.exists reads an
     * error (a share dropping out for a moment) as "no file"; this throws it instead, so that a
     * target is never taken to be free only because it could not be read.
     */
    public static boolean isAbsent(Path path) throws IOException {
        try {
            Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return false;
        } catch (NoSuchFileException e) {
            return true;
        }
    }

    /**
     * Whether two paths name the same location once both are NFC-composed. Resolved targets are
     * composed, but files created by macOS clients are often stored decomposed, and a file whose
     * name differs from its target only in that is already in place.
     */
    public static boolean isSamePathIgnoringNormalization(Path first, Path second) {
        return Normalizer.normalize(first.toString(), Normalizer.Form.NFC)
                .equals(Normalizer.normalize(second.toString(), Normalizer.Form.NFC));
    }

    /**
     * Whether one of a book's files is already at its target: the same path once both are
     * NFC-composed, in the directory the rest of the book goes to. On a normalization-insensitive
     * share a decomposed directory is the composed one; on a sensitive file system the composed
     * directory is another one, and the file moves there with the rest of its book.
     */
    public static boolean isAlreadyInPlace(Path source, Path target) throws IOException {
        if (!isSamePathIgnoringNormalization(source, target)) {
            return false;
        }
        Path sourceDir = source.getParent();
        Path targetDir = target.getParent();
        return Objects.equals(sourceDir, targetDir) || (!isAbsent(targetDir) && Files.isSameFile(sourceDir, targetDir));
    }

    /** Where a folder audiobook's folder is, relative to its library path, as file sub paths are stored. */
    public static String folderSubPath(BookFileEntity folder) {
        String subPath = folder.getFileSubPath();
        return subPath == null || subPath.isEmpty() ? folder.getFileName() : subPath + "/" + folder.getFileName();
    }

    /**
     * A file of one of {@code books}, other than the folder audiobook {@code folder} itself,
     * that is inside its folder: another book's file in a subfolder ("extras/booklet.pdf"), or
     * one of its own book's files. Moving the folder would carry that file along to somewhere
     * its row does not say it is.
     */
    public static Optional<Path> fileInsideFolder(BookFileEntity folder, Collection<BookEntity> books) {
        Path folderPath = folder.getFullFilePath().toAbsolutePath().normalize();
        for (BookEntity book : books) {
            for (BookFileEntity file : book.getBookFiles()) {
                if (file == folder || (file.getId() != null && file.getId().equals(folder.getId()))) {
                    continue;
                }
                Path path = file.getFullFilePath().toAbsolutePath().normalize();
                if (path.startsWith(folderPath)) {
                    return Optional.of(path);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Moves a staged file or folder to its target, which must be free: the source has been moved
     * aside, so whatever is at the target is something else, and it is never replaced. The
     * FileAlreadyExistsException lets the caller roll the source back.
     */
    public void commitMove(Path tempPath, Path target) throws IOException {
        if (!waitForFileAccessible(tempPath)) {
            throw new NoSuchFileException(tempPath.toString(), null, "Temporary file not accessible before commit");
        }

        if (target.getParent() != null) {
            Files.createDirectories(target.getParent());
        }
        log.info("Committing move from temporary location {} to {}", tempPath, target);
        executeWithRetry(() -> {
            // Files.move alone would also do nothing, without failing, when the target is another link to the same file
            if (!isAbsent(target)) {
                throw new FileAlreadyExistsException(target.toString(), null, "Not replacing an existing file");
            }
            rename(tempPath, target);
        }, () -> hasMoved(tempPath, target));
    }

    /** Renames {@code source} to {@code target}, never replacing anything there. Tests override it to stand in for a share. */
    protected void rename(Path source, Path target) throws IOException {
        Files.move(source, target);
    }

    /**
     * Whether a move that failed has taken effect all the same: its source is gone and its
     * target is there. A share that reconnects can lose the reply to a rename the server has
     * done, and retrying it then fails on the missing source. The target was free when the move
     * began, and one of the two names is the temporary one only this move uses, so what is at
     * the target is the moved file.
     */
    private static boolean hasMoved(Path source, Path target) throws IOException {
        return isAbsent(source) && !isAbsent(target);
    }

    /**
     * Moves a staged or committed file or folder back to its source. Whatever has taken the
     * source's place since is never replaced: the file is then left where it is, and logged.
     */
    public void rollbackMove(Path tempPath, Path originalSource) {
        if (!Files.exists(tempPath)) {
            log.error("Failed to rollback file move from {} to {}: nothing is left at {} to move back", tempPath, originalSource, tempPath);
            return;
        }
        
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                log.info("Rolling back move from {} to {}", tempPath, originalSource);
                if (originalSource.getParent() != null) {
                    Files.createDirectories(originalSource.getParent());
                }
                Files.move(tempPath, originalSource);
                return;
            } catch (FileAlreadyExistsException e) {
                log.error("Failed to rollback file move from {} to {}: another file has taken its place. " +
                        "The moved file may need moving back by hand: {}", tempPath, originalSource, tempPath);
                return;
            } catch (IOException e) {
                if (attempt == MAX_ATTEMPTS) {
                    log.error("Failed to rollback file move from {} to {} after {} attempts. " +
                            "Orphaned temp file may need manual cleanup: {}", 
                            tempPath, originalSource, MAX_ATTEMPTS, tempPath, e);
                    return;
                }
                log.warn("Rollback attempt {}/{} failed, retrying: {}", attempt, MAX_ATTEMPTS, e.getMessage());
                waitBeforeRetry();
            }
        }
    }

    public String extractSubPath(Path filePath, LibraryPathEntity libraryPathEntity) {
        Path libraryRoot = Paths.get(libraryPathEntity.getPath()).toAbsolutePath().normalize();
        Path parentDir = filePath.getParent().toAbsolutePath().normalize();
        Path relativeSubPath = libraryRoot.relativize(parentDir);
        return relativeSubPath.toString().replace('\\', '/');
    }

    public void unregisterLibrary(Long libraryId) {
        monitoringRegistrationService.unregisterLibrary(libraryId);
    }

    public void registerLibraryPaths(Long libraryId, Path libraryRoot) {
        log.debug("Registering library paths for library {} with root {}", libraryId, libraryRoot);
        monitoringRegistrationService.registerLibraryPaths(libraryId, libraryRoot);
    }

    public String getFileNamingPattern(LibraryEntity library) {
        String pattern = library.getFileNamingPattern();
        if (pattern == null || pattern.trim().isEmpty()) {
            try {
                pattern = appSettingService.getAppSettings().getUploadPattern();
                log.debug("Using default pattern for library {} as no custom pattern is set", library.getName());
            } catch (Exception e) {
                log.warn("Failed to get default upload pattern for library {}: {}", library.getName(), e.getMessage());
            }
        }
        if (pattern == null || pattern.trim().isEmpty()) {
            pattern = "{currentFilename}";
            log.info("No file naming pattern available for library {}. Using fallback pattern: {currentFilename}", library.getName());
        }
        if (pattern.endsWith("/") || pattern.endsWith("\\")) {
            pattern += "{currentFilename}";
        }
        return pattern;
    }

    public Path generateNewFilePath(BookEntity book, LibraryPathEntity libraryPathEntity, String pattern) {
        String newRelativePathStr = PathPatternResolver.resolvePattern(book, pattern);
        if (newRelativePathStr.startsWith("/") || newRelativePathStr.startsWith("\\")) {
            newRelativePathStr = newRelativePathStr.substring(1);
        }
        String path = libraryPathEntity.getPath();
        return Paths.get(path, newRelativePathStr);
    }

    public Path generateNewFilePath(BookEntity book, BookFileEntity bookFile, LibraryPathEntity libraryPathEntity, String pattern) {
        String newRelativePathStr = PathPatternResolver.resolvePattern(book, bookFile, pattern, bookFile.isFolderBased());
        if (newRelativePathStr.startsWith("/") || newRelativePathStr.startsWith("\\")) {
            newRelativePathStr = newRelativePathStr.substring(1);
        }
        String path = libraryPathEntity.getPath();
        return Paths.get(path, newRelativePathStr);
    }

    public void deleteEmptyParentDirsUpToLibraryFolders(Path currentDir, Set<Path> libraryRoots) {
        Path dir = currentDir.toAbsolutePath().normalize();
        Set<Path> normalizedRoots = new HashSet<>();
        for (Path root : libraryRoots) {
            normalizedRoots.add(root.toAbsolutePath().normalize());
        }
        while (dir != null) {
            if (isLibraryRoot(dir, normalizedRoots)) {
                break;
            }
            if (deleteIfEffectivelyEmpty(dir, normalizedRoots)) {
                dir = dir.getParent();
            } else {
                break;
            }
        }
    }

    private boolean deleteIfEffectivelyEmpty(Path dir, Set<Path> libraryRoots) {
        if (isLibraryRoot(dir, libraryRoots)) {
            return false;
        }

        File[] contents = dir.toFile().listFiles();
        if (contents == null) {
            log.warn("Cannot read directory: {}. Stopping cleanup.", dir);
            return false;
        }

        boolean deletedAnySubdirectory = recursivelyDeleteEmptySubdirectories(contents, libraryRoots);
        
        if (deletedAnySubdirectory) {
            waitBeforeRetry();
        }

        File[] remainingContents = dir.toFile().listFiles();
        if (remainingContents == null) {
            log.warn("Cannot read directory after subdirectory cleanup: {}", dir);
            return false;
        }

        if (isSafeToDelete(remainingContents)) {
            deleteIgnoredFilesAndDirectory(remainingContents, dir);
            return true;
        }

        return false;
    }

    private boolean recursivelyDeleteEmptySubdirectories(File[] contents, Set<Path> libraryRoots) {
        boolean deletedAny = false;
        for (File file : contents) {
            if (isNonSymlinkDirectory(file)) {
                if (deleteIfEffectivelyEmpty(file.toPath(), libraryRoots)) {
                    deletedAny = true;
                }
            }
        }
        return deletedAny;
    }

    private boolean isNonSymlinkDirectory(File file) {
        return file.isDirectory() && !Files.isSymbolicLink(file.toPath());
    }

    private boolean isLibraryRoot(Path currentDir, Set<Path> normalizedRoots) {
        for (Path root : normalizedRoots) {
            try {
                if (Files.isSameFile(root, currentDir)) {
                    return true;
                }
            } catch (IOException e) {
                log.warn("Failed to compare paths: {} and {}", root, currentDir);
            }
        }
        return false;
    }

    private boolean isSafeToDelete(File[] files) {
        for (File file : files) {
            if (Files.isSymbolicLink(file.toPath()) 
                    || file.isDirectory() 
                    || !IGNORED_FILENAMES.contains(file.getName())) {
                return false;
            }
        }
        return true;
    }

    private void deleteIgnoredFilesAndDirectory(File[] files, Path currentDir) {
        for (File file : files) {
            try {
                Files.delete(file.toPath());
                log.info("Deleted ignored file: {}", file.getAbsolutePath());
            } catch (IOException e) {
                log.warn("Failed to delete ignored file: {}", file.getAbsolutePath());
            }
        }
        try {
            executeWithRetry(() -> Files.delete(currentDir));
            log.info("Deleted empty directory: {}", currentDir);
        } catch (IOException e) {
            log.warn("Failed to delete directory: {}", currentDir, e);
        }
    }

    @FunctionalInterface
    private interface FileOperation {
        void execute() throws IOException;
    }

    @FunctionalInterface
    private interface FileCheck {
        boolean test() throws IOException;
    }

    private void executeWithRetry(FileOperation operation) throws IOException {
        executeWithRetry(operation, () -> false);
    }

    /** Retries {@code operation}, unless {@code tookEffect} finds that an attempt which failed has done it all the same. */
    private void executeWithRetry(FileOperation operation, FileCheck tookEffect) throws IOException {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                if (attempt > 1 && tookEffect.test()) {
                    log.info("File operation took effect although attempt {}/{} reported a failure", attempt - 1, MAX_ATTEMPTS);
                    return;
                }
                operation.execute();
                return;
            } catch (IOException e) {
                if (!isRetryableException(e) || attempt == MAX_ATTEMPTS) {
                    throw e;
                }

                log.warn("File operation failed (attempt {}/{}), retrying in {}ms: {}",
                        attempt, MAX_ATTEMPTS, RETRY_DELAY_MS, e.getMessage());

                waitBeforeRetry();
            }
        }
    }

    private boolean isRetryableException(IOException e) {
        // A FileSystemException too, but the file in the way stays there however often the move is retried
        if (e instanceof FileAlreadyExistsException) {
            return false;
        }
        return RETRYABLE_EXCEPTIONS.stream().anyMatch(type -> type.isInstance(e));
    }

    private void waitBeforeRetry() {
        try {
            Thread.sleep(RETRY_DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
