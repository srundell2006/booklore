package org.booklore.service.library;

import jakarta.transaction.Transactional;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.exception.ApiError;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.entity.LibraryPathEntity;
import org.booklore.repository.BookFileRepository;
import org.booklore.repository.BookRepository;
import org.booklore.repository.LibraryRepository;
import org.booklore.util.PathPatternResolver;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

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

        for (BookEntity book : books) {
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

        // Resolve name conflicts: if target exists and isn't the current file, suffix (1), (2), …
        targetPath = resolveConflict(targetPath, currentPath);

        // Already in the right place?
        if (currentPath.toAbsolutePath().normalize().equals(targetPath.toAbsolutePath().normalize())) {
            return false;
        }

        // Move the file
        Files.createDirectories(targetPath.getParent());
        Files.move(currentPath, targetPath);
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
     * If {@code targetPath} already exists and is not the source file,
     * appends (1), (2), … to the base name until a free slot is found.
     */
    private Path resolveConflict(Path targetPath, Path sourcePath) {
        if (!Files.exists(targetPath)
                || targetPath.toAbsolutePath().normalize().equals(sourcePath.toAbsolutePath().normalize())) {
            return targetPath;
        }

        String fileName = targetPath.getFileName().toString();
        Path targetDir = targetPath.getParent();
        int dotIdx = fileName.lastIndexOf('.');
        String base = dotIdx >= 0 ? fileName.substring(0, dotIdx) : fileName;
        String ext = dotIdx >= 0 ? fileName.substring(dotIdx) : "";

        int counter = 1;
        Path candidate;
        do {
            candidate = targetDir.resolve(base + " (" + counter + ")" + ext);
            counter++;
        } while (Files.exists(candidate));
        return candidate;
    }
}
