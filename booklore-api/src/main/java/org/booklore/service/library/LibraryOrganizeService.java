package org.booklore.service.library;

import jakarta.transaction.Transactional;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.exception.ApiError;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.entity.LibraryPathEntity;
import org.booklore.model.enums.BookFileExtension;
import org.booklore.repository.BookFileRepository;
import org.booklore.repository.BookRepository;
import org.booklore.repository.LibraryRepository;
import org.booklore.service.file.FileMoveHelper;
import org.booklore.util.PathPatternResolver;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
@AllArgsConstructor
public class LibraryOrganizeService {

    private final LibraryRepository libraryRepository;
    private final BookRepository bookRepository;
    private final BookFileRepository bookFileRepository;

    @Transactional
    public void organizeLibrary(Long libraryId) {
        LibraryEntity library = libraryRepository.findById(libraryId)
                .orElseThrow(() -> ApiError.LIBRARY_NOT_FOUND.createException(libraryId));

        String pattern = library.getFileNamingPattern();
        if (pattern == null || pattern.isBlank()) {
            throw new IllegalStateException(
                "Library '" + library.getName() + "' has no file naming pattern configured. " +
                "Please go to Settings → File Naming Pattern and set a per-library pattern first."
            );
        }

        List<BookEntity> books = bookRepository.findAllWithMetadataByLibraryId(libraryId);
        log.info("Organizing library '{}' ({} books) with pattern '{}'", library.getName(), books.size(), pattern);

        int moved = 0;
        int skipped = 0;
        int errors = 0;

        // Collect every library-root path we encounter so we can prune empty dirs afterward.
        Set<Path> libraryRoots = new LinkedHashSet<>();

        for (BookEntity book : books) {
            LibraryPathEntity lp = book.getLibraryPath();
            if (lp != null) {
                libraryRoots.add(Paths.get(lp.getPath()).toAbsolutePath().normalize());
            }
            for (BookFileEntity bookFile : book.getBookFiles()) {
                try {
                    boolean wasOrganized = organizeFile(book, bookFile, pattern);
                    if (wasOrganized) moved++;
                    else skipped++;
                } catch (Exception e) {
                    log.error("Failed to organize file '{}': {}", bookFile.getFileName(), e.getMessage(), e);
                    errors++;
                }
            }
        }

        log.info("Library '{}' organized: {} moved, {} already in place, {} errors",
                library.getName(), moved, skipped, errors);

        // Remove any subdirectories that are now empty as a result of moves.
        if (moved > 0) {
            deleteEmptyDirectories(libraryRoots);
        }
    }

    private boolean organizeFile(BookEntity book, BookFileEntity bookFile, String pattern) throws IOException {
        LibraryPathEntity libraryPath = book.getLibraryPath();
        Path currentPath = bookFile.getFullFilePath();

        if (!Files.exists(currentPath)) {
            log.warn("File does not exist on disk, skipping: {}", currentPath);
            return false;
        }

        // Delegate to the canonical resolver — same engine used by the metadata-update rename.
        // It handles all tokens, modifiers ({authors:initial}, {authors:sort}, etc.),
        // optional <> blocks, and auto-appends the file extension.
        String newRelativePathStr = PathPatternResolver.resolvePattern(book, bookFile, pattern, bookFile.isFolderBased());
        if (newRelativePathStr.startsWith("/") || newRelativePathStr.startsWith("\\")) {
            newRelativePathStr = newRelativePathStr.substring(1);
        }

        Path targetPath = Paths.get(libraryPath.getPath(), newRelativePathStr);

        // No other move may take the target between finding it free and moving there
        synchronized (FileMoveHelper.MOVE_LOCK) {
            // Resolve name conflicts: if target exists and isn't the current file, suffix (1), (2), …
            targetPath = resolveConflict(targetPath, currentPath, bookFile.isFolderBased());

            // Already in the right place? A name stored decomposed (by a macOS client) is the same name
            if (FileMoveHelper.isAlreadyInPlace(currentPath.toAbsolutePath().normalize(), targetPath.toAbsolutePath().normalize())) {
                return false;
            }

            // Move the file
            Files.createDirectories(targetPath.getParent());
            if (!moveFile(currentPath, targetPath)) {
                return false;
            }
        }
        log.debug("Moved '{}' -> '{}'", currentPath, targetPath);

        // Update DB: subPath = library-root-relative dir; fileName = bare filename
        Path libraryRoot = Paths.get(libraryPath.getPath()).toAbsolutePath().normalize();
        Path targetParent = targetPath.getParent().toAbsolutePath().normalize();
        String newSubPath = libraryRoot.relativize(targetParent).toString().replace('\\', '/');

        bookFile.setFileSubPath(newSubPath);
        bookFile.setFileName(targetPath.getFileName().toString());
        bookFileRepository.save(bookFile);

        return true;
    }

    /**
     * Walks each library root in reverse (deepest paths first) and deletes any
     * directory that is now empty — but never the root itself.
     */
    private void deleteEmptyDirectories(Set<Path> libraryRoots) {
        for (Path root : libraryRoots) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (var pathStream = Files.walk(root)) {
                pathStream
                    .sorted(Comparator.reverseOrder())
                    .filter(path -> !path.equals(root))
                    .filter(Files::isDirectory)
                    .forEach(dir -> {
                        try (var children = Files.newDirectoryStream(dir)) {
                            if (!children.iterator().hasNext()) {
                                Files.delete(dir);
                                log.debug("Deleted empty directory: {}", dir);
                            }
                        } catch (IOException e) {
                            log.warn("Could not check or delete directory '{}': {}", dir, e.getMessage());
                        }
                    });
            } catch (IOException e) {
                log.warn("Failed to walk library path '{}' for empty-directory cleanup: {}", root, e.getMessage());
            }
        }
    }

    /**
     * Moves the file or folder to {@code targetPath}, where nothing but the source itself can be.
     * When the source is already there under another spelling (a case-only rename on a
     * case-insensitive share), Files.move sees the same file and does nothing, so the rename goes
     * by way of a temporary name. Returns false, leaving the source in place, when the target is
     * instead another link to the same file.
     */
    private boolean moveFile(Path currentPath, Path targetPath) throws IOException {
        if (FileMoveHelper.isAbsent(targetPath)) {
            Files.move(currentPath, targetPath);
            return true;
        }
        Path tempPath = currentPath.resolveSibling(currentPath.getFileName() + ".tmp_move");
        Files.move(currentPath, tempPath);
        boolean moved = false;
        try {
            if (FileMoveHelper.isAbsent(targetPath)) {
                Files.move(tempPath, targetPath);
                moved = true;
            }
        } finally {
            if (!moved) {
                Files.move(tempPath, currentPath);
            }
        }
        return moved;
    }

    /**
     * If {@code targetPath} is taken by something other than the source itself,
     * appends (1), (2), … to the base name until a free slot is found. The
     * number goes before a book file's extension, and after the whole name of a
     * folder audiobook, whose dots are part of its name.
     */
    private Path resolveConflict(Path targetPath, Path sourcePath, boolean folderBased) throws IOException {
        if (isFreeFor(targetPath, sourcePath)) {
            return targetPath;
        }

        String fileName = targetPath.getFileName().toString();
        Path targetDir = targetPath.getParent();
        int baseLength = folderBased ? fileName.length() : baseNameLength(fileName);
        String base = fileName.substring(0, baseLength);
        String ext = fileName.substring(baseLength);

        int counter = 1;
        Path candidate;
        do {
            candidate = targetDir.resolve(base + " (" + counter + ")" + ext);
            counter++;
        } while (!isFreeFor(candidate, sourcePath));
        return candidate;
    }

    /**
     * Whether nothing but the source is at {@code path}: a case-insensitive or
     * normalization-insensitive share finds the source itself under another
     * spelling, and a source already numbered by an earlier run keeps its number.
     * A path that cannot be read throws rather than counting as free.
     */
    private boolean isFreeFor(Path path, Path sourcePath) throws IOException {
        return FileMoveHelper.isAbsent(path) || Files.isSameFile(path, sourcePath);
    }

    /** The length of a file name without its book extension, or without its last extension for other files. */
    private int baseNameLength(String fileName) {
        int bookExtensionLength = BookFileExtension.fromFileName(fileName)
                .map(extension -> extension.getExtension().length() + 1)
                .orElse(0);
        if (bookExtensionLength > 0 && bookExtensionLength < fileName.length()) {
            return fileName.length() - bookExtensionLength;
        }
        int dotIdx = fileName.lastIndexOf('.');
        return dotIdx > 0 ? dotIdx : fileName.length();
    }
}
