package org.booklore.service.library;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.exception.ApiError;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.entity.LibraryPathEntity;
import org.booklore.model.enums.BookFileExtension;
import org.booklore.repository.BookAdditionalFileRepository;
import org.booklore.repository.BookRepository;
import org.booklore.repository.LibraryRepository;
import org.booklore.service.file.FileMoveHelper;
import org.booklore.util.PathPatternResolver;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Slf4j
@Service
@AllArgsConstructor
public class LibraryOrganizeService {

    private final LibraryRepository libraryRepository;
    private final BookRepository bookRepository;
    private final BookAdditionalFileRepository bookFileRepository;
    private final PlatformTransactionManager transactionManager;

    /**
     * Moves every book of the library to where its naming pattern puts it. The books are read in
     * one read-only transaction, and each moved file's row is written in a transaction of its own
     * as soon as the file has moved: a run over a large library that stops partway (a restart, an
     * error) must never take the rows of the files already moved back to their old paths, or the
     * next rescan would delete those books and import their files as new ones.
     */
    @Transactional(readOnly = true)
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
            if (isNamedRight(book, pattern)) {
                skipped += book.getBookFiles().size();
                continue;
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

    /**
     * Whether every file of the book is where the pattern puts it, once both names are
     * NFC-composed. A book named right in a folder a macOS client stored decomposed is left
     * alone, even where the composed folder would be another one: its files follow into that
     * folder only when one of them really moves.
     */
    private boolean isNamedRight(BookEntity book, String pattern) {
        try {
            for (BookFileEntity bookFile : book.getBookFiles()) {
                Path currentPath = bookFile.getFullFilePath().toAbsolutePath().normalize();
                if (!FileMoveHelper.isSamePathIgnoringNormalization(currentPath, targetPath(book, bookFile, pattern).toAbsolutePath().normalize())) {
                    return false;
                }
            }
            return true;
        } catch (RuntimeException e) {
            // Organized file by file instead, which logs what is wrong with the file
            return false;
        }
    }

    private Path targetPath(BookEntity book, BookFileEntity bookFile, String pattern) {
        // Delegate to the canonical resolver — same engine used by the metadata-update rename.
        // It handles all tokens, modifiers ({authors:initial}, {authors:sort}, etc.),
        // optional <> blocks, and auto-appends the file extension.
        String newRelativePathStr = PathPatternResolver.resolvePattern(book, bookFile, pattern, bookFile.isFolderBased());
        if (newRelativePathStr.startsWith("/") || newRelativePathStr.startsWith("\\")) {
            newRelativePathStr = newRelativePathStr.substring(1);
        }
        return Paths.get(book.getLibraryPath().getPath(), newRelativePathStr);
    }

    private boolean organizeFile(BookEntity book, BookFileEntity bookFile, String pattern) throws IOException {
        LibraryPathEntity libraryPath = book.getLibraryPath();
        Path currentPath = bookFile.getFullFilePath();

        if (!Files.exists(currentPath)) {
            log.warn("File does not exist on disk, skipping: {}", currentPath);
            return false;
        }

        Path targetPath = targetPath(book, bookFile, pattern);

        // No other move may take the target between finding it free and moving there
        synchronized (FileMoveHelper.MOVE_LOCK) {
            // Resolve name conflicts: if target exists and isn't the current file, suffix (1), (2), …
            targetPath = resolveConflict(targetPath, currentPath, bookFile.isFolderBased());

            // Already in the right place? A name stored decomposed (by a macOS client) is the same name
            if (FileMoveHelper.isAlreadyInPlace(currentPath.toAbsolutePath().normalize(), targetPath.toAbsolutePath().normalize())) {
                return false;
            }

            if (bookFile.isFolderBased() && carriesAnotherFile(book, bookFile)) {
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

        recordMove(bookFile, currentPath, targetPath, newSubPath);
        return true;
    }

    /**
     * Whether the folder audiobook holds a file of any book, its own included, other than
     * itself. Moving the folder would carry that file along while its row still named the old
     * place, and a rescan would then drop it, or delete its book and import it anew.
     */
    private boolean carriesAnotherFile(BookEntity book, BookFileEntity folder) {
        List<BookEntity> books = new ArrayList<>(bookRepository.findAllByLibraryPathIdAndFileSubPathStartingWith(
                book.getLibraryPath().getId(), FileMoveHelper.folderSubPath(folder)));
        books.add(book);
        Optional<Path> carried = FileMoveHelper.fileInsideFolder(folder, books);
        carried.ifPresent(path -> log.warn("Not moving the folder '{}': it would carry along '{}', which is not moved with it",
                folder.getFullFilePath(), path));
        return carried.isPresent();
    }

    /**
     * Writes the moved file's new place in a transaction of its own, so that it stays written
     * whatever becomes of the rest of the run. When that fails, the file goes back to where its
     * row still says it is.
     */
    private void recordMove(BookFileEntity bookFile, Path currentPath, Path targetPath, String newSubPath) {
        String newFileName = targetPath.getFileName().toString();
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        try {
            transactionTemplate.executeWithoutResult(status ->
                    bookFileRepository.updateFileNameAndSubPath(bookFile.getId(), newFileName, newSubPath));
        } catch (RuntimeException e) {
            log.error("Failed to record the move of '{}' to '{}'. Moving it back", currentPath, targetPath);
            moveBack(targetPath, currentPath);
            throw e;
        }
        // The rest of the run sees the file where it now is
        bookFile.setFileSubPath(newSubPath);
        bookFile.setFileName(newFileName);
    }

    /** Moves a file back to where it was, never over a file that has taken its place since. */
    private void moveBack(Path movedPath, Path originalPath) {
        synchronized (FileMoveHelper.MOVE_LOCK) {
            try {
                if (!moveFile(movedPath, originalPath)) {
                    log.error("Failed to move '{}' back to '{}': another file has taken its place", movedPath, originalPath);
                }
            } catch (IOException e) {
                log.error("Failed to move '{}' back to '{}'", movedPath, originalPath, e);
            }
        }
    }

    /**
     * Walks each library root and deletes any directory that is now empty, deepest first, but
     * never the root itself. A directory that cannot be read, or an entry gone by the time it is
     * looked at (a client creating and deleting ._ files), is passed over instead of ending the
     * cleanup.
     */
    private void deleteEmptyDirectories(Set<Path> libraryRoots) {
        for (Path root : libraryRoots) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try {
                Files.walkFileTree(root, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException e) {
                        log.warn("Could not check '{}' for empty-directory cleanup: {}", file, e.getMessage());
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult postVisitDirectory(Path dir, IOException e) {
                        if (!dir.equals(root)) {
                            deleteIfEmpty(dir);
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException e) {
                log.warn("Failed to walk library path '{}' for empty-directory cleanup: {}", root, e.getMessage());
            }
        }
    }

    private void deleteIfEmpty(Path dir) {
        try (var children = Files.newDirectoryStream(dir)) {
            if (!children.iterator().hasNext()) {
                Files.delete(dir);
                log.debug("Deleted empty directory: {}", dir);
            }
        } catch (IOException | DirectoryIteratorException e) {
            log.warn("Could not check or delete directory '{}': {}", dir, e.getMessage());
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
        Path tempPath = FileMoveHelper.temporaryPath(currentPath);
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
