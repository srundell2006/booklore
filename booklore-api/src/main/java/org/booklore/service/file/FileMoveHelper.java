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
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

@Slf4j
@Component
@AllArgsConstructor
public class FileMoveHelper {

    /**
     * Held from checking a move's targets until its files are moved or put back. Java cannot rename
     * without replacing whatever is at the target, so two books moved at the same time (metadata
     * refreshes run in parallel) could otherwise both find a target free and the second replace the
     * first.
     */
    public static final Object MOVE_LOCK = new Object();

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

        Path tempPath = source.resolveSibling(source.getFileName().toString() + ".tmp_move");
        log.info("Moving file from {} to temporary location {}", source, tempPath);
        // A file already there is one a failed rollback left behind, and may be the only copy of a book
        executeWithRetry(() -> Files.move(source, tempPath));
        return tempPath;
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
            Files.move(tempPath, target);
        });
    }

    /**
     * Moves a staged or committed file or folder back to its source. Whatever has taken the
     * source's place since is never replaced: the file is then left where it is, and logged.
     */
    public void rollbackMove(Path tempPath, Path originalSource) {
        if (!Files.exists(tempPath)) {
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

    private void executeWithRetry(FileOperation operation) throws IOException {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
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
