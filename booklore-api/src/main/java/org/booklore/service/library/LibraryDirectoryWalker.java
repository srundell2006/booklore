package org.booklore.service.library;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.config.AppProperties;
import org.booklore.model.enums.BookFileExtension;
import org.booklore.util.FileUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Lists the directories under a library's paths into an in-memory tree, which
 * {@link LibraryFileHelper} then turns into library files.
 *
 * <p>The walk is shaped by network storage, where every filesystem call is a
 * round trip that can take seconds and a wedged SMB path can block a thread in
 * uninterruptible IO:
 * <ul>
 *   <li>Calls are kept to what the walk needs: one listing per directory, one
 *       attribute read per entry, and a readability check for book files only.
 *       Entries the ignore rules drop by name (hidden files, system folders, temp
 *       files) are never touched, and a {@code .ignore} marker is spotted in the
 *       listing itself.</li>
 *   <li>Directories are listed on virtual threads, at most
 *       {@code app.scan.walk-parallelism} at a time, across all of a library's
 *       paths together.</li>
 *   <li>A listing whose current filesystem call has not returned within
 *       {@code app.scan.directory-timeout-seconds} is abandoned: the directory is
 *       reported incomplete and the walk carries on. The limit is on progress, not
 *       on the whole listing, so a large directory on a slow share still finishes.
 *       An abandoned thread cannot be reclaimed (it ignores interrupts while stuck
 *       in IO), so after {@value #MAX_TIMEOUTS_PER_PATH} timeouts under one library
 *       path the walk stops listing that path and reports all of it incomplete,
 *       rather than strand a thread per directory of a dead share.</li>
 * </ul>
 *
 * <p>Listing threads only read the filesystem and hand back what they found. The
 * calling thread owns the tree, the queue and every count, so a listing that
 * returns after it was abandoned is simply never looked at.
 */
@Slf4j
@Component
public class LibraryDirectoryWalker {

    static final int MAX_PARALLELISM = 64;
    static final int MAX_TIMEOUTS_PER_PATH = 10;

    private static final String IGNORE_MARKER = ".ignore";

    private final int parallelism;
    private final Duration directoryTimeout;
    private final FileAccess fileAccess;

    @Autowired
    public LibraryDirectoryWalker(AppProperties appProperties) {
        this(appProperties.getScan().getWalkParallelism(),
                Duration.ofSeconds(Math.max(1, appProperties.getScan().getDirectoryTimeoutSeconds())),
                new FileAccess());
    }

    LibraryDirectoryWalker(int parallelism, Duration directoryTimeout, FileAccess fileAccess) {
        this.parallelism = Math.clamp(parallelism, 1, MAX_PARALLELISM);
        this.directoryTimeout = directoryTimeout;
        this.fileAccess = fileAccess;
    }

    /**
     * What the walk saw under one library path.
     *
     * @param path            the library path, as given
     * @param root            its directory tree, or null when it could not be listed or
     *                        the ignore rules exclude it
     * @param incompletePaths directories that could not be listed and entries that could
     *                        not be inspected; nothing at or below them was seen
     */
    record WalkedPath(Path path, Directory root, Set<Path> incompletePaths) {
    }

    /**
     * A directory of the walked tree, holding only what building library files needs.
     * Ignored entries are already gone, and subdirectories and book files are each in
     * name order ({@link String#compareTo}), so output order never depends on the order
     * the filesystem lists entries in.
     */
    @Getter
    @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
    static final class Directory {
        private final Path path;
        @Getter(AccessLevel.NONE)
        private final Directory parent;
        @Getter(AccessLevel.NONE)
        private final Object fileKey;
        /** Names of the regular, non-empty, readable files with a book extension. */
        private List<String> bookFiles = List.of();
        private List<Directory> subdirectories = List.of();
    }

    /**
     * The filesystem calls a walk makes. Package-private so tests can stand in a share
     * that fails, stalls or hides files.
     */
    static class FileAccess {

        DirectoryStream<Path> newDirectoryStream(Path dir) throws IOException {
            return Files.newDirectoryStream(dir);
        }

        /** Follows links, falling back to the link itself when its target is gone, as walkFileTree does. */
        BasicFileAttributes readAttributes(Path path) throws IOException {
            try {
                return Files.readAttributes(path, BasicFileAttributes.class);
            } catch (IOException e) {
                return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            }
        }

        boolean isReadable(Path file) {
            return Files.isReadable(file);
        }

        boolean isSameFile(Path path, Path other) throws IOException {
            return Files.isSameFile(path, other);
        }
    }

    /** Walk state of one library path. Only the walking thread touches it. */
    @RequiredArgsConstructor
    private static final class PathWalk {
        private final Path path;
        private final Set<Path> incompletePaths = new HashSet<>();
        private Directory root;
        private int outstanding;
        private int directoriesListed;
        private int bookFiles;
        private int timeouts;
        private boolean stopped;
        private long finishedAt;
    }

    /**
     * One directory to list; {@code directory} is null for a library path itself, whose
     * attributes the listing reads first. The listing thread stamps {@code lastProgress}
     * each time a filesystem call returns, and the walking thread reads it to spot a stall.
     */
    @RequiredArgsConstructor
    private static final class Listing {
        private final PathWalk walk;
        private final Path path;
        private final Directory directory;
        private volatile long lastProgress;

        void progressed() {
            lastProgress = System.nanoTime();
        }
    }

    /** What one listing found, for the walking thread to attach to the tree. */
    private record Contents(Directory directory, List<Directory> subdirectories, List<String> bookFiles,
                            List<Path> incompletePaths) {
    }

    private record Entry(Path path, String name) {
    }

    /**
     * Walks the given library paths together and returns one result per path, in the
     * same order.
     *
     * @throws InterruptedIOException if the calling thread is interrupted while waiting
     */
    List<WalkedPath> walk(List<Path> paths) throws InterruptedIOException {
        long startedAt = System.nanoTime();
        List<PathWalk> walks = new ArrayList<>(paths.size());
        Deque<Listing> queue = new ArrayDeque<>();
        for (Path path : paths) {
            PathWalk walk = new PathWalk(path);
            walks.add(walk);
            if (FileUtils.shouldIgnore(path)) {
                log.warn("Library path {} matches the scan's ignore rules, so nothing under it is scanned", path);
                walk.finishedAt = startedAt;
            } else {
                walk.outstanding++;
                queue.add(new Listing(walk, path, null));
            }
        }

        ExecutorService executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("library-walk-", 0).factory());
        try {
            CompletionService<Contents> completions = new ExecutorCompletionService<>(executor);
            Map<Future<Contents>, Listing> running = new HashMap<>();
            while (!queue.isEmpty() || !running.isEmpty()) {
                while (running.size() < parallelism && !queue.isEmpty()) {
                    Listing listing = queue.poll();
                    if (listing.walk.stopped) {
                        finished(listing.walk);
                        continue;
                    }
                    listing.progressed();
                    running.put(completions.submit(() -> list(listing)), listing);
                }
                if (running.isEmpty()) {
                    break;
                }

                Future<Contents> done = completions.poll(nanosUntilStall(running.values()), TimeUnit.NANOSECONDS);
                if (done == null) {
                    abandonStalled(running);
                } else {
                    // Null for a listing abandoned earlier, whose cancellation is queued too (a late return is not).
                    Listing listing = running.remove(done);
                    if (listing != null) {
                        attach(listing, done, queue);
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Interrupted while walking " + paths);
        } finally {
            // Not close(): that waits for every thread, including any still wedged in IO.
            executor.shutdownNow();
        }

        List<WalkedPath> results = new ArrayList<>(walks.size());
        for (PathWalk walk : walks) {
            log.info("Walked library path {}: {} directories listed, {} book files found, {} incomplete paths, {} ms",
                    walk.path, walk.directoriesListed, walk.bookFiles, walk.incompletePaths.size(),
                    TimeUnit.NANOSECONDS.toMillis(walk.finishedAt - startedAt));
            results.add(new WalkedPath(walk.path, walk.root, Set.copyOf(walk.incompletePaths)));
        }
        return results;
    }

    private void attach(Listing listing, Future<Contents> done, Deque<Listing> queue) throws InterruptedException {
        PathWalk walk = listing.walk;
        try {
            Contents contents = done.get();
            Directory directory = contents.directory();
            directory.subdirectories = contents.subdirectories();
            directory.bookFiles = contents.bookFiles();
            if (listing.directory == null) {
                walk.root = directory;
            }
            walk.directoriesListed++;
            walk.bookFiles += contents.bookFiles().size();
            walk.incompletePaths.addAll(contents.incompletePaths());
            if (!walk.stopped) {
                for (Directory subdirectory : contents.subdirectories()) {
                    walk.outstanding++;
                    queue.add(new Listing(walk, subdirectory.path, subdirectory));
                }
            }
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            walk.incompletePaths.add(listing.path);
            if (cause instanceof IOException) {
                log.warn("Could not list directory [{}], so nothing under it was scanned: {}", listing.path, cause.toString());
            } else {
                log.warn("Could not list directory [{}], so nothing under it was scanned", listing.path, cause);
            }
        }
        finished(walk);
    }

    private void abandonStalled(Map<Future<Contents>, Listing> running) {
        long now = System.nanoTime();
        Iterator<Map.Entry<Future<Contents>, Listing>> it = running.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Future<Contents>, Listing> entry = it.next();
            Listing listing = entry.getValue();
            // A listing that finished meanwhile is waiting in the completion queue for the next poll.
            if (entry.getKey().isDone() || now - listing.lastProgress < directoryTimeout.toNanos()) {
                continue;
            }
            it.remove();
            // Frees a thread blocked interruptibly; one stuck in a CIFS call stays stuck and is just never waited for.
            entry.getKey().cancel(true);

            PathWalk walk = listing.walk;
            walk.incompletePaths.add(listing.path);
            walk.timeouts++;
            log.warn("Gave up listing directory [{}] after {} ms without filesystem progress, so nothing under it was scanned",
                    listing.path, directoryTimeout.toMillis());
            if (walk.timeouts == MAX_TIMEOUTS_PER_PATH) {
                walk.stopped = true;
                walk.incompletePaths.add(walk.path);
                log.warn("Stopped walking library path [{}] after {} directory timeouts; the rest of it was not scanned",
                        walk.path, walk.timeouts);
            }
            finished(walk);
        }
    }

    private long nanosUntilStall(Collection<Listing> running) {
        long now = System.nanoTime();
        long timeout = directoryTimeout.toNanos();
        long wait = timeout;
        for (Listing listing : running) {
            wait = Math.min(wait, timeout - (now - listing.lastProgress));
        }
        return Math.max(0, wait);
    }

    private static void finished(PathWalk walk) {
        if (--walk.outstanding == 0) {
            walk.finishedAt = System.nanoTime();
        }
    }

    /** Runs on a listing thread: reads one directory and returns what it holds. */
    private Contents list(Listing listing) throws IOException {
        Path path = listing.path;
        Directory directory = listing.directory;
        if (directory == null) {
            BasicFileAttributes attrs = fileAccess.readAttributes(path);
            listing.progressed();
            if (!attrs.isDirectory()) {
                throw new NotDirectoryException(path.toString());
            }
            directory = new Directory(path, null, attrs.fileKey());
        }

        List<Entry> entries = new ArrayList<>();
        boolean hasIgnoreMarker = false;
        try (DirectoryStream<Path> stream = fileAccess.newDirectoryStream(path)) {
            listing.progressed();
            for (Path entry : stream) {
                String name = entry.getFileName().toString();
                hasIgnoreMarker |= IGNORE_MARKER.equals(name);
                entries.add(new Entry(entry, name));
                listing.progressed();
            }
        } catch (DirectoryIteratorException e) {
            throw e.getCause();
        }
        // The library path itself is scanned even when it holds a marker.
        if (hasIgnoreMarker && directory.parent != null) {
            log.debug("Skipping directory with .ignore file: {}", path);
            return new Contents(directory, List.of(), List.of(), List.of());
        }

        entries.sort(Comparator.comparing(Entry::name));
        List<Directory> subdirectories = new ArrayList<>();
        List<String> bookFiles = new ArrayList<>();
        List<Path> incompletePaths = new ArrayList<>();
        for (Entry entry : entries) {
            if (FileUtils.shouldIgnore(entry.path())) {
                continue;
            }

            BasicFileAttributes attrs;
            try {
                attrs = fileAccess.readAttributes(entry.path());
            } catch (NoSuchFileException e) {
                // Removed since the listing: genuinely gone.
                continue;
            } catch (IOException e) {
                // It may be a directory full of books, so it must not pass for absent.
                log.warn("Could not read attributes of [{}]: {}", entry.path(), e.toString());
                incompletePaths.add(entry.path());
                continue;
            } finally {
                listing.progressed();
            }

            if (attrs.isDirectory()) {
                if (isLoop(directory, entry.path(), attrs.fileKey(), listing)) {
                    log.error("Failed read path [{}]: symbolic link loop", entry.path());
                } else {
                    subdirectories.add(new Directory(entry.path(), directory, attrs.fileKey()));
                }
            } else if (BookFileExtension.fromFileName(entry.name()).isPresent()
                    && attrs.isRegularFile() && attrs.size() > 0) {
                boolean readable = fileAccess.isReadable(entry.path());
                listing.progressed();
                if (readable) {
                    bookFiles.add(entry.name());
                } else {
                    log.warn("Book file is not readable, so it was left out of the scan: {}", entry.path());
                    incompletePaths.add(entry.path());
                }
            }
            // Anything else (other files, empty files, broken links) is not a book and is skipped as before.
        }
        return new Contents(directory, List.copyOf(subdirectories), List.copyOf(bookFiles), incompletePaths);
    }

    /** True when {@code dir} is one of its own ancestors: the cycle test walkFileTree makes when following links. */
    private boolean isLoop(Directory parent, Path dir, Object fileKey, Listing listing) {
        for (Directory ancestor = parent; ancestor != null; ancestor = ancestor.parent) {
            if (fileKey != null && ancestor.fileKey != null) {
                if (fileKey.equals(ancestor.fileKey)) {
                    return true;
                }
            } else {
                // No file keys on this filesystem: compare the files themselves.
                try {
                    if (fileAccess.isSameFile(dir, ancestor.path)) {
                        return true;
                    }
                } catch (IOException | SecurityException e) {
                    // Not provably the same directory, so not a loop.
                } finally {
                    listing.progressed();
                }
            }
        }
        return false;
    }
}
