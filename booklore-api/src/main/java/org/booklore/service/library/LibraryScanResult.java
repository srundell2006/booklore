package org.booklore.service.library;

import org.booklore.model.dto.settings.LibraryFile;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * Outcome of walking a library's paths.
 *
 * @param files           the book files found
 * @param incompletePaths directories or files the walk could not read (listing failed,
 *                        timed out, or an entry could not be inspected). Anything at or
 *                        below one of these paths was not seen, so its absence from
 *                        {@code files} says nothing about whether it still exists on disk.
 */
public record LibraryScanResult(List<LibraryFile> files, Set<Path> incompletePaths) {

    public LibraryScanResult {
        files = List.copyOf(files);
        incompletePaths = Set.copyOf(incompletePaths);
    }

    public boolean isComplete() {
        return incompletePaths.isEmpty();
    }

    /**
     * True when {@code path} is one of the incomplete paths or lies underneath one,
     * i.e. the walk cannot vouch for whether it exists.
     */
    public boolean isUnderIncompletePath(Path path) {
        if (path == null || incompletePaths.isEmpty()) {
            return false;
        }
        Path normalized = path.toAbsolutePath().normalize();
        for (Path incomplete : incompletePaths) {
            if (normalized.startsWith(incomplete.toAbsolutePath().normalize())) {
                return true;
            }
        }
        return false;
    }
}
