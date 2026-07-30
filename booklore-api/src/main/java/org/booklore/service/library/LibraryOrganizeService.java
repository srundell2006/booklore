package org.booklore.service.library;

import jakarta.transaction.Transactional;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.exception.ApiError;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.repository.BookFileRepository;
import org.booklore.repository.BookRepository;
import org.booklore.repository.LibraryRepository;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

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
            log.warn("Library {} has no file naming pattern configured; skipping organize.", libraryId);
            return;
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
                    log.error("Failed to organize file '{}': {}", bookFile.getFullFilePath(), e.getMessage(), e);
                    errors++;
                }
            }
        }

        log.info("Library '{}' organized: {} moved, {} already in place, {} errors",
                library.getName(), moved, skipped, errors);
    }

    private boolean organizeFile(BookEntity book, BookFileEntity bookFile, String pattern) throws IOException {
        String libraryRootPath = book.getLibraryPath().getPath();
        Path currentPath = bookFile.getFullFilePath();

        if (!Files.exists(currentPath)) {
            log.warn("File does not exist on disk, skipping: {}", currentPath);
            return false;
        }

        // Preserve the original file extension
        String originalFileName = bookFile.getFileName();
        int dotIdx = originalFileName.lastIndexOf('.');
        String extension = dotIdx >= 0 ? originalFileName.substring(dotIdx) : "";

        // Resolve pattern tokens into a relative path string (no extension)
        String resolved = resolvePattern(pattern, book, originalFileName);

        // Split by any path separator, sanitize each segment, drop blanks
        String[] segments = Arrays.stream(resolved.split("[/\\\\]+"))
                .map(this::sanitizeSegment)
                .filter(s -> !s.isBlank())
                .toArray(String[]::new);

        if (segments.length == 0) {
            log.warn("Pattern resolved to empty path for book id={}; skipping.", book.getId());
            return false;
        }

        // Last segment = base filename; preceding segments = subdirectory components
        String targetBaseName = segments[segments.length - 1];
        Path targetSubDir;
        if (segments.length == 1) {
            targetSubDir = Paths.get("");
        } else {
            String first = segments[0];
            String[] rest = Arrays.copyOfRange(segments, 1, segments.length - 1);
            targetSubDir = rest.length > 0 ? Paths.get(first, rest) : Paths.get(first);
        }

        Path targetDir = Paths.get(libraryRootPath).resolve(targetSubDir);
        String targetFileName = targetBaseName + extension;
        Path targetPath = resolveConflict(targetDir, targetFileName, currentPath);

        // Already in the right place?
        if (currentPath.toAbsolutePath().normalize().equals(targetPath.toAbsolutePath().normalize())) {
            return false;
        }

        // Move the file
        Files.createDirectories(targetDir);
        Files.move(currentPath, targetPath);
        log.debug("Moved '{}' -> '{}'", currentPath, targetPath);

        // Update DB record
        bookFile.setFileSubPath(targetSubDir.toString());
        bookFile.setFileName(targetPath.getFileName().toString());
        bookFileRepository.save(bookFile);

        return true;
    }

    /**
     * Replaces pattern tokens with values derived from the book's metadata.
     * Supported tokens: {title}, {author}, {authors}, {series}, {series_number}, {series_index}
     */
    private String resolvePattern(String pattern, BookEntity book, String originalFileName) {
        var metadata = book.getMetadata();

        String title = (metadata != null && metadata.getTitle() != null)
                ? metadata.getTitle()
                : stripExtension(originalFileName);

        String author = "";
        String authors = "";
        if (metadata != null && metadata.getAuthors() != null && !metadata.getAuthors().isEmpty()) {
            author = metadata.getAuthors().get(0).getName();
            authors = metadata.getAuthors().stream()
                    .map(a -> a.getName())
                    .collect(Collectors.joining(", "));
        }

        String series = "";
        String seriesNumber = "";
        String seriesIndex = "";
        if (metadata != null) {
            if (metadata.getSeriesName() != null && !metadata.getSeriesName().isBlank()) {
                series = metadata.getSeriesName();
            }
            if (metadata.getSeriesNumber() != null) {
                float num = metadata.getSeriesNumber();
                if (num == Math.floor(num)) {
                    seriesNumber = String.valueOf((int) num);
                    seriesIndex = String.format("%03d", (int) num);
                } else {
                    seriesNumber = String.valueOf(num);
                    seriesIndex = seriesNumber;
                }
            }
        }

        return pattern
                .replace("{title}", title)
                .replace("{author}", author)
                .replace("{authors}", authors)
                .replace("{series}", series)
                .replace("{series_number}", seriesNumber)
                .replace("{series_index}", seriesIndex);
    }

    /**
     * Removes filesystem-unsafe characters from a single path segment and trims
     * trailing whitespace/dots (for Windows compatibility).
     */
    private String sanitizeSegment(String segment) {
        return segment
                .replaceAll("[\\\\/:*?\"<>|]", "_")
                .replaceAll("[\\s.]+$", "")
                .trim();
    }

    private String stripExtension(String fileName) {
        int idx = fileName.lastIndexOf('.');
        return idx >= 0 ? fileName.substring(0, idx) : fileName;
    }

    /**
     * If targetDir/targetFileName already exists and is not the source file,
     * appends (1), (2), … to the base name until a free slot is found.
     */
    private Path resolveConflict(Path targetDir, String targetFileName, Path sourcePath) {
        Path candidate = targetDir.resolve(targetFileName);
        if (!Files.exists(candidate)
                || candidate.toAbsolutePath().normalize().equals(sourcePath.toAbsolutePath().normalize())) {
            return candidate;
        }

        int dotIdx = targetFileName.lastIndexOf('.');
        String base = dotIdx >= 0 ? targetFileName.substring(0, dotIdx) : targetFileName;
        String ext = dotIdx >= 0 ? targetFileName.substring(dotIdx) : "";

        int counter = 1;
        do {
            candidate = targetDir.resolve(base + " (" + counter + ")" + ext);
            counter++;
        } while (Files.exists(candidate));
        return candidate;
    }
}
