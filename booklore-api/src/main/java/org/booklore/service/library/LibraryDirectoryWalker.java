package org.booklore.service.library;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.config.AppProperties;
import org.booklore.model.enums.BookFileExtension;
import org.booklore.model.enums.BookFileType;
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
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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
 *       Each entry is looked at as soon as the listing returns it, while a network
 *       client still has the attributes the listing brought along cached. Entries
 *       the ignore rules drop by name (hidden files, system folders, temp files)
 *       are never touched, and a {@code .ignore} marker is spotted in the listing
 *       itself.</li>
 *   <li>Directories are listed on virtual threads, at most
 *       {@code app.scan.walk-parallelism} at a time, across all of a library's
 *       paths together.</li>
 *   <li>A listing whose current filesystem call has not returned within
 *       {@code app.scan.directory-timeout-seconds} is abandoned: the directory is
 *       reported incomplete and the walk carries on. The limit is on progress, not
 *       on the whole listing, so a large directory on a slow share still finishes.
 *       An abandoned thread cannot be reclaimed while it is stuck (it ignores
 *       interrupts while in IO), but it stops at its next filesystem call once the
 *       stuck one returns. So that stuck threads cannot pile up, after
 *       {@value #MAX_TIMEOUTS_PER_PATH} timeouts under one library path the walk
 *       stops listing that path, and while {@value #MAX_STRANDED_LISTINGS} abandoned
 *       listings of this or earlier walks are still stuck, no walk lists anything
 *       more. Whatever is left out is reported incomplete.</li>
 * </ul>
 *
 * <p>Listing threads only read the filesystem and hand back what they found. They
 * are given the directory to list and the directories above it, nothing of the
 * tree, so a thread stuck for good holds on to little. The calling thread owns the
 * tree, the queue and every count, so a listing that returns after it was
 * abandoned is simply never looked at.
 */
@Slf4j
@Component
public class LibraryDirectoryWalker {

    static final int MAX_PARALLELISM = 64;
    static final int MAX_TIMEOUTS_PER_PATH = 10;
    /**
     * Each stuck listing holds a carrier thread of the virtual thread scheduler, which
     * has at most 256 by default, so they are capped well below that.
     */
    static final int MAX_STRANDED_LISTINGS = 64;

    private static final String IGNORE_MARKER = ".ignore";

    private final int parallelism;
    private final Duration directoryTimeout;
    private final FileAccess fileAccess;
    /** Listings abandoned by any walk whose threads have not returned yet. */
    private final AtomicInteger strandedListings = new AtomicInteger();

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
     * @param incompletePaths paths the walk cannot vouch for: directories that could not be
     *                        listed, entries that could not be inspected (or, where how its
     *                        audio files group depends on them, their directory), and a
     *                        library path that turned out empty
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

        BasicFileAttributes readAttributes(Path path, LinkOption... options) throws IOException {
            return Files.readAttributes(path, BasicFileAttributes.class, options);
        }

        boolean isReadable(Path file) {
            return Files.isReadable(file);
        }

        boolean exists(Path path) {
            return Files.exists(path);
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
     * A directory on the way down a library path, with the directories above it: all
     * that spotting a symbolic link loop takes. {@code fileKey} is null where the
     * filesystem has none.
     */
    private record Lineage(Path path, Object fileKey, Lineage parent) {
    }

    /**
     * A directory waiting to be listed: where its contents go, and its lineage, which is
     * null for a library path (its listing reads its attributes first).
     */
    private record Pending(PathWalk walk, Directory directory, Lineage lineage) {
    }

    /**
     * What a listing thread is given. It stamps {@code lastProgress} each time a
     * filesystem call returns, and the walking thread reads it to spot a stall.
     */
    @RequiredArgsConstructor
    private static final class Listing {
        private final Path path;
        private final Lineage lineage;
        private volatile long lastProgress = System.nanoTime();

        /** Called after each filesystem call; throws once the walk has given up on this listing. */
        void progressed() throws InterruptedIOException {
            lastProgress = System.nanoTime();
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedIOException("Gave up listing " + path);
            }
        }
    }

    /** What one listing found, for the walking thread to attach to the tree. */
    private record Contents(List<Lineage> subdirectories, List<String> bookFiles, List<Path> incompletePaths) {
        static final Contents SKIPPED = new Contents(List.of(), List.of(), List.of());
    }

    /**
     * Runs one listing and queues itself for the walking thread once done. Whether its
     * thread outlives the walk's interest is settled exactly once: by the listing
     * finishing, or by the walk abandoning it first, in which case the thread counts as
     * stranded until it returns.
     */
    private final class ListingTask extends FutureTask<Contents> {
        private final Listing listing;
        private final Queue<ListingTask> completed;
        private final AtomicBoolean settled = new AtomicBoolean();

        ListingTask(Listing listing, Queue<ListingTask> completed) {
            super(() -> list(listing));
            this.listing = listing;
            this.completed = completed;
        }

        @Override
        public void run() {
            try {
                super.run();
            } finally {
                if (!settled.compareAndSet(false, true)) {
                    strandedListings.decrementAndGet();
                }
            }
        }

        @Override
        protected void done() {
            completed.add(this);
        }

        /**
         * Gives up on the listing and interrupts it, which frees a thread blocked
         * interruptibly; one stuck in a CIFS call stays stuck and is just never waited
         * for. False when the listing has finished and waits to be collected instead.
         */
        boolean abandon() {
            if (!settled.compareAndSet(false, true)) {
                return false;
            }
            strandedListings.incrementAndGet();
            return cancel(true);
        }
    }

    /** Abandoned listings whose threads have not returned yet. */
    int strandedListings() {
        return strandedListings.get();
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
        Deque<Pending> queue = new ArrayDeque<>();
        for (Path path : paths) {
            PathWalk walk = new PathWalk(path);
            walks.add(walk);
            if (FileUtils.shouldIgnore(path)) {
                log.warn("Library path {} matches the scan's ignore rules, so nothing under it is scanned", path);
                walk.finishedAt = startedAt;
            } else {
                walk.outstanding++;
                queue.add(new Pending(walk, new Directory(path), null));
            }
        }

        ExecutorService executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("library-walk-", 0).factory());
        BlockingQueue<ListingTask> completed = new LinkedBlockingQueue<>();
        Map<ListingTask, Pending> running = new HashMap<>();
        try {
            while (!queue.isEmpty() || !running.isEmpty()) {
                while (running.size() < parallelism && !queue.isEmpty()) {
                    Pending pending = queue.poll();
                    PathWalk walk = pending.walk();
                    if (!walk.stopped && strandedListings.get() >= MAX_STRANDED_LISTINGS) {
                        stop(walk);
                        log.warn("Stopped walking library path [{}]: {} directory listings given up on by this or earlier "
                                        + "scans are still stuck in filesystem calls, so the rest of it was not scanned",
                                walk.path, strandedListings.get());
                    }
                    if (walk.stopped) {
                        finished(walk);
                        continue;
                    }
                    ListingTask task = new ListingTask(new Listing(pending.directory().getPath(), pending.lineage()), completed);
                    executor.execute(task);
                    running.put(task, pending);
                }
                if (running.isEmpty()) {
                    break;
                }

                ListingTask done = completed.poll(nanosUntilStall(running.keySet()), TimeUnit.NANOSECONDS);
                if (done == null) {
                    abandonStalled(running);
                } else {
                    // Null for a listing abandoned earlier, whose cancellation is queued too (a late return is not).
                    Pending pending = running.remove(done);
                    if (pending != null) {
                        attach(pending, done, queue);
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Interrupted while walking " + paths);
        } finally {
            // Whatever still runs is given up on, so it counts as stranded until its thread returns.
            running.keySet().forEach(ListingTask::abandon);
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

    private void attach(Pending pending, ListingTask done, Deque<Pending> queue) throws InterruptedException {
        PathWalk walk = pending.walk();
        Directory directory = pending.directory();
        try {
            Contents contents = done.get();
            List<Directory> subdirectories = new ArrayList<>(contents.subdirectories().size());
            for (Lineage lineage : contents.subdirectories()) {
                Directory subdirectory = new Directory(lineage.path());
                subdirectories.add(subdirectory);
                if (!walk.stopped) {
                    walk.outstanding++;
                    queue.add(new Pending(walk, subdirectory, lineage));
                }
            }
            directory.subdirectories = List.copyOf(subdirectories);
            directory.bookFiles = contents.bookFiles();
            if (pending.lineage() == null) {
                walk.root = directory;
            }
            walk.directoriesListed++;
            walk.bookFiles += contents.bookFiles().size();
            walk.incompletePaths.addAll(contents.incompletePaths());
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            walk.incompletePaths.add(directory.getPath());
            if (cause instanceof IOException) {
                log.warn("Could not list directory [{}], so nothing under it was scanned: {}", directory.getPath(), cause.toString());
            } else {
                log.warn("Could not list directory [{}], so nothing under it was scanned", directory.getPath(), cause);
            }
        }
        finished(walk);
    }

    private void abandonStalled(Map<ListingTask, Pending> running) {
        long now = System.nanoTime();
        Iterator<Map.Entry<ListingTask, Pending>> it = running.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<ListingTask, Pending> entry = it.next();
            ListingTask task = entry.getKey();
            // A listing that finished meanwhile is waiting in the completion queue for the next poll.
            if (now - task.listing.lastProgress < directoryTimeout.toNanos() || !task.abandon()) {
                continue;
            }
            it.remove();

            PathWalk walk = entry.getValue().walk();
            Path path = entry.getValue().directory().getPath();
            walk.incompletePaths.add(path);
            walk.timeouts++;
            log.warn("Gave up listing directory [{}] after {} ms without filesystem progress, so nothing under it was scanned",
                    path, directoryTimeout.toMillis());
            if (walk.timeouts == MAX_TIMEOUTS_PER_PATH) {
                stop(walk);
                log.warn("Stopped walking library path [{}] after {} directory timeouts; the rest of it was not scanned",
                        walk.path, walk.timeouts);
            }
            finished(walk);
        }
    }

    private long nanosUntilStall(Collection<ListingTask> running) {
        long now = System.nanoTime();
        long timeout = directoryTimeout.toNanos();
        long wait = timeout;
        for (ListingTask task : running) {
            wait = Math.min(wait, timeout - (now - task.listing.lastProgress));
        }
        return Math.max(0, wait);
    }

    /** Lists nothing more under the library path, which as a whole is reported incomplete. */
    private static void stop(PathWalk walk) {
        walk.stopped = true;
        walk.incompletePaths.add(walk.path);
    }

    private static void finished(PathWalk walk) {
        if (--walk.outstanding == 0) {
            walk.finishedAt = System.nanoTime();
        }
    }

    /** Runs on a listing thread: reads one directory and returns what it holds. */
    private Contents list(Listing listing) throws IOException {
        Path path = listing.path;
        Lineage directory = listing.lineage;
        boolean libraryPath = directory == null;
        if (libraryPath) {
            BasicFileAttributes attrs = readAttributes(path, listing);
            if (!attrs.isDirectory()) {
                throw new NotDirectoryException(path.toString());
            }
            directory = new Lineage(path, attrs.fileKey(), null);
        }

        List<Lineage> subdirectories = new ArrayList<>();
        List<String> bookFiles = new ArrayList<>();
        List<Path> incompletePaths = new ArrayList<>();
        List<Path> unseenBookFiles = new ArrayList<>();
        // Audio files among all the book files, seen or not.
        int audioFiles = 0;
        boolean empty = true;
        try (DirectoryStream<Path> stream = fileAccess.newDirectoryStream(path)) {
            listing.progressed();
            for (Path entry : stream) {
                listing.progressed();
                empty = false;
                String name = entry.getFileName().toString();
                // The library path itself is scanned even when it holds a marker.
                if (!libraryPath && name.equalsIgnoreCase(IGNORE_MARKER)) {
                    boolean marked = name.equals(IGNORE_MARKER);
                    if (!marked) {
                        // In another case it is a marker only where the filesystem ignores case, as a lookup tells.
                        marked = fileAccess.exists(path.resolve(IGNORE_MARKER));
                        listing.progressed();
                    }
                    if (marked) {
                        log.debug("Skipping directory with .ignore file: {}", path);
                        return Contents.SKIPPED;
                    }
                }
                if (FileUtils.shouldIgnore(entry)) {
                    continue;
                }

                Optional<BookFileType> bookType = BookFileExtension.fromFileName(name).map(BookFileExtension::getType);
                boolean audio = bookType.orElse(null) == BookFileType.AUDIOBOOK;
                BasicFileAttributes attrs;
                try {
                    attrs = readAttributes(entry, listing);
                } catch (NoSuchFileException e) {
                    // Removed since the listing: genuinely gone.
                    continue;
                } catch (InterruptedIOException e) {
                    throw e;
                } catch (IOException e) {
                    log.warn("Could not read attributes of [{}]: {}", entry, e.toString());
                    if (bookType.isPresent()) {
                        unseenBookFiles.add(entry);
                        if (audio) {
                            audioFiles++;
                        }
                    } else {
                        // It may be a directory full of books, so it must not pass for absent.
                        incompletePaths.add(entry);
                    }
                    continue;
                }

                if (attrs.isDirectory()) {
                    if (isLoop(directory, entry, attrs.fileKey(), listing)) {
                        log.error("Failed read path [{}]: symbolic link loop", entry);
                    } else {
                        subdirectories.add(new Lineage(entry, attrs.fileKey(), directory));
                    }
                } else if (bookType.isPresent() && attrs.isRegularFile() && attrs.size() > 0) {
                    boolean readable = fileAccess.isReadable(entry);
                    listing.progressed();
                    if (readable) {
                        bookFiles.add(name);
                    } else {
                        log.warn("Book file is not readable, so it was left out of the scan: {}", entry);
                        unseenBookFiles.add(entry);
                    }
                    if (audio) {
                        audioFiles++;
                    }
                }
                // Anything else (other files, empty files, broken links) is not a book and is skipped as before.
            }
        } catch (DirectoryIteratorException e) {
            throw e.getCause();
        }

        if (libraryPath && empty) {
            log.warn("Library path [{}] is empty; reporting it as not scanned in case it is a share that is not mounted", path);
            incompletePaths.add(path);
        }
        if (!unseenBookFiles.isEmpty()) {
            if (!libraryPath && audioFiles >= LibraryFileHelper.MIN_AUDIO_FILES_FOR_FOLDER_AUDIOBOOK) {
                // Whether the audio files here make one folder audiobook, a series or separate books depends on
                // every book file of the directory, so one that could not be seen puts all of its books in doubt.
                incompletePaths.add(path);
            } else {
                // Each book file here is a book of its own, so only the unseen ones are in doubt.
                incompletePaths.addAll(unseenBookFiles);
            }
        }
        subdirectories.sort(Comparator.comparing(lineage -> lineage.path().getFileName().toString()));
        bookFiles.sort(Comparator.naturalOrder());
        return new Contents(List.copyOf(subdirectories), List.copyOf(bookFiles), incompletePaths);
    }

    /**
     * Reads attributes following links. Only a link whose target does not exist falls
     * back to the link itself, as walkFileTree does, and then passes for a broken link.
     * Any other failure is thrown, so a link onto a share that is down is reported
     * incomplete instead (and so is a chain of links that loops on itself).
     */
    private BasicFileAttributes readAttributes(Path path, Listing listing) throws IOException {
        try {
            return fileAccess.readAttributes(path);
        } catch (NoSuchFileException e) {
            listing.progressed();
            // Throws again for an entry removed since the listing.
            return fileAccess.readAttributes(path, LinkOption.NOFOLLOW_LINKS);
        } finally {
            listing.progressed();
        }
    }

    /** True when {@code dir} is one of its own ancestors: the cycle test walkFileTree makes when following links. */
    private boolean isLoop(Lineage parent, Path dir, Object fileKey, Listing listing) throws InterruptedIOException {
        for (Lineage ancestor = parent; ancestor != null; ancestor = ancestor.parent()) {
            if (fileKey != null && ancestor.fileKey() != null) {
                if (fileKey.equals(ancestor.fileKey())) {
                    return true;
                }
            } else {
                // No file keys on this filesystem: compare the files themselves.
                boolean same;
                try {
                    same = fileAccess.isSameFile(dir, ancestor.path());
                } catch (IOException | SecurityException e) {
                    // Not provably the same directory, so not a loop.
                    same = false;
                }
                listing.progressed();
                if (same) {
                    return true;
                }
            }
        }
        return false;
    }
}
