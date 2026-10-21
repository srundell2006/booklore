package org.booklore.service.library;

import org.booklore.model.dto.settings.LibraryFile;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Outcome of walking a library's paths.
 *
 * @param files           the book files found
 * @param incompletePaths directories or files the walk could not read (listing failed,
 *                        timed out, or an entry could not be inspected), held absolute and
 *                        normalized. Anything at or below one of these paths may not have
 *                        been seen, or not seen whole, so its absence from {@code files}
 *                        says nothing about whether it still exists on disk.
 * @param timedOut        true when some of the incomplete paths were given up on because
 *                        the filesystem did not respond in time, rather than failing
 * @param emptyLibraryPaths library paths that were read and hold nothing at all. They are
 *                        among the incomplete paths too, since a share that is not mounted
 *                        looks just the same.
 */
public record LibraryScanResult(List<LibraryFile> files, Set<Path> incompletePaths, boolean timedOut,
                                Set<Path> emptyLibraryPaths) {

    public LibraryScanResult {
        files = List.copyOf(files);
        incompletePaths = normalized(incompletePaths);
        emptyLibraryPaths = normalized(emptyLibraryPaths);
    }

    public LibraryScanResult(List<LibraryFile> files, Set<Path> incompletePaths) {
        this(files, incompletePaths, false, Set.of());
    }

    private static Set<Path> normalized(Set<Path> paths) {
        return paths.stream()
                .map(path -> path.toAbsolutePath().normalize())
                .collect(Collectors.toUnmodifiableSet());
    }

    public boolean isComplete() {
        return incompletePaths.isEmpty();
    }

    /** The incomplete paths that could not be read, as opposed to library paths found empty, in name order. */
    public List<Path> unreadPaths() {
        return incompletePaths.stream()
                .filter(path -> !emptyLibraryPaths.contains(path))
                .sorted()
                .toList();
    }

    /**
     * True when {@code path} is one of the incomplete paths or lies underneath one,
     * i.e. the walk cannot vouch for whether it exists. Looks up the path and each of
     * its ancestors, so the cost does not grow with the number of incomplete paths.
     */
    public boolean isUnderIncompletePath(Path path) {
        if (path == null || incompletePaths.isEmpty()) {
            return false;
        }
        for (Path candidate = path.toAbsolutePath().normalize(); candidate != null; candidate = candidate.getParent()) {
            if (incompletePaths.contains(candidate)) {
                return true;
            }
        }
        return false;
    }
}
