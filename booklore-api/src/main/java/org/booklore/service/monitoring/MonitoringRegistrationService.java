package org.booklore.service.monitoring;

import org.booklore.model.dto.Library;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

@Slf4j
@Service
@AllArgsConstructor
public class MonitoringRegistrationService {

    private final LibraryWatchService libraryWatchService;

    public boolean isPathMonitored(Path path) {
        return libraryWatchService.isPathMonitored(path);
    }

    public boolean isLibraryMonitored(Long libraryId) {
        return libraryWatchService.isLibraryMonitored(libraryId);
    }

    public void unregisterSpecificPath(Path path) {
        if (!Files.exists(path)) {
            log.debug("Path does not exist, attempting to unregister anyway: {}", path);
        }
        libraryWatchService.unregisterPath(path);
    }

    public void registerSpecificPath(Path path, Long libraryId) {
        if (!Files.exists(path)) {
            log.warn("Cannot register path that does not exist: {}", path);
            return;
        }
        if (!Files.isDirectory(path)) {
            log.warn("Cannot register path that is not a directory: {}", path);
            return;
        }
        libraryWatchService.registerPath(path, libraryId);
    }

    public void registerLibrary(Library library) {
        libraryWatchService.registerLibrary(library);
    }

    public void unregisterLibrary(Long libraryId) {
        libraryWatchService.unregisterLibrary(libraryId);
    }

    public void registerLibraryPaths(Long libraryId, Path libraryRoot) {
        libraryWatchService.registerLibraryPaths(libraryId, libraryRoot);
    }

    /**
     * Stops watching these libraries while files are written into them, so that the watcher does not take those files
     * for new books, and returns what it stopped for {@link #resume}. A watched library is unregistered in full. A
     * library whose watching is off loses just the folders of it that are registered (a book deletion, for one, leaves
     * its folder registered); resuming gives back those folders only, as registering its whole tree would walk every
     * folder of it (many minutes for a six-figure library on a network share) and watch it after all.
     */
    public MonitoringPause pauseLibraries(Collection<Long> libraryIds) {
        if (libraryIds == null || libraryIds.isEmpty()) {
            return MonitoringPause.NONE;
        }
        Set<Long> watched = new LinkedHashSet<>();
        Map<Path, Long> folders = new LinkedHashMap<>();
        for (Long libraryId : new LinkedHashSet<>(libraryIds)) {
            if (libraryId == null) {
                continue;
            }
            if (libraryWatchService.isLibraryMonitored(libraryId)) {
                libraryWatchService.unregisterLibrary(libraryId);
                watched.add(libraryId);
            } else {
                for (Path folder : libraryWatchService.getPathsForLibraries(Set.of(libraryId))) {
                    libraryWatchService.unregisterPath(folder);
                    folders.put(folder, libraryId);
                }
            }
        }
        return new MonitoringPause(watched, folders);
    }

    /**
     * Watches again what {@link #pauseLibraries} stopped: each watched library counts as watched and has its folders
     * registered under the roots {@code libraryRoots} gives (null: the library is gone, and is left out), and each single
     * folder that still exists is registered again on its own.
     */
    public void resume(MonitoringPause pause, Function<Long, Collection<Path>> libraryRoots) {
        if (pause == null || pause.isEmpty()) {
            return;
        }
        for (Long libraryId : pause.watchedLibraries()) {
            try {
                Collection<Path> roots = libraryRoots.apply(libraryId);
                if (roots == null) {
                    log.info("Library {} is gone; not watching it again", libraryId);
                    continue;
                }
                libraryWatchService.resumeLibrary(libraryId, roots);
            } catch (Exception e) {
                log.warn("Could not watch library {} again: {}", libraryId, e.getMessage());
            }
        }
        pause.folders().forEach((folder, libraryId) -> {
            if (Files.isDirectory(folder)) {
                libraryWatchService.registerPath(folder, libraryId);
            }
        });
    }

    public void registerLibraries(Map<Long, Path> libraries) {
        if (libraries == null || libraries.isEmpty()) {
            return;
        }
        libraries.forEach(this::registerLibraryPaths);
    }

    public void unregisterLibraries(Collection<Long> libraryIds) {
        if (libraryIds == null || libraryIds.isEmpty()) {
            return;
        }
        libraryIds.forEach(this::unregisterLibrary);
    }

    public Set<Path> getPathsForLibraries(Collection<Long> libraryIds) {
        if (libraryIds == null || libraryIds.isEmpty()) {
            return Set.of();
        }
        return libraryWatchService.getPathsForLibraries(new HashSet<>(libraryIds));
    }

    public boolean waitForEventsDrainedByPaths(Set<Path> paths, long timeoutMs) {
        return libraryWatchService.waitForEventsDrainedByPaths(paths, timeoutMs);
    }

    public boolean waitForEventsDrained(Collection<Long> libraryIds, long timeoutMs) {
        if (libraryIds == null || libraryIds.isEmpty()) {
            return true;
        }
        return libraryWatchService.waitForEventsDrained(new HashSet<>(libraryIds), timeoutMs);
    }
}
