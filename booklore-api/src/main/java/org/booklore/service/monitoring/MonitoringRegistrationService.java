package org.booklore.service.monitoring;

import org.booklore.model.dto.Library;
import org.booklore.repository.LibraryPathRepository;
import org.booklore.repository.LibraryRepository;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

@Slf4j
@Service
@AllArgsConstructor
public class MonitoringRegistrationService {

    private final LibraryWatchService libraryWatchService;
    private final LibraryRepository libraryRepository;
    private final LibraryPathRepository libraryPathRepository;
    private final PlatformTransactionManager transactionManager;

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
     * Watches again what {@link #pauseLibraries} stopped, as the database has the libraries now: a paused library that
     * is still there and still set to be watched counts as watched and has every folder under its paths registered, and
     * each single folder that still exists, in a library that is still there, is registered again on its own. A library
     * deleted, or set not to be watched, while it was paused stays unwatched. Never throws: it runs in finally blocks,
     * after the files were written, and a failure here must not turn their writing into a failure.
     */
    public void resume(MonitoringPause pause) {
        if (pause == null || pause.isEmpty()) {
            return;
        }
        for (Long libraryId : pause.watchedLibraries()) {
            try {
                List<Path> roots = readNow(() -> libraryRepository.findWatchById(libraryId).orElse(false)
                        ? libraryPathRepository.findPathsByLibraryId(libraryId).stream().map(Path::of).toList()
                        : null);
                if (roots == null) {
                    log.info("Library {} was deleted or set not to be watched while it was paused; not watching it again", libraryId);
                    continue;
                }
                libraryWatchService.resumeLibrary(libraryId, roots);
            } catch (Exception e) {
                log.warn("Could not watch library {} again: {}", libraryId, e.getMessage());
            }
        }
        Map<Long, Boolean> libraryExists = new HashMap<>();
        pause.folders().forEach((folder, libraryId) -> {
            try {
                if (Files.isDirectory(folder)
                        && libraryExists.computeIfAbsent(libraryId, id -> readNow(() -> libraryRepository.existsById(id)))) {
                    libraryWatchService.registerPath(folder, libraryId);
                }
            } catch (Exception e) {
                log.warn("Could not watch folder {} of library {} again: {}", folder, libraryId, e.getMessage());
            }
        });
    }

    /**
     * Reads the libraries as the database has them now, in a short read-only transaction of its own. Resume runs inside
     * the writer's transaction for some writers (an upload, a file move), and there MariaDB's repeatable read would give
     * the libraries as they were when that transaction first read, before the pause. Committed before any folder is
     * walked, so no connection is held through a walk.
     */
    private <T> T readNow(Supplier<T> read) {
        TransactionTemplate fresh = new TransactionTemplate(transactionManager);
        fresh.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        fresh.setReadOnly(true);
        return fresh.execute(status -> read.get());
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
