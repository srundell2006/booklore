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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

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
 *   <li>Directories are listed on platform threads, at most
 *       {@code app.scan.walk-parallelism} at a time, across all of a library's
 *       paths together. Not virtual threads: on JDK 25 a virtual thread blocked in a
 *       file call keeps its carrier, so a few listings wedged on a network share would
 *       stall every virtual thread in the application, request handling included.</li>
 *   <li>A listing whose current filesystem call has not returned within
 *       {@code app.scan.directory-timeout-seconds} is abandoned and the walk carries
 *       on. The limit is on progress, not on the whole listing, so a large directory
 *       on a slow share still finishes. Stuck reading the directory itself, the
 *       listing gives up the whole directory, which is reported incomplete. Stuck
 *       inspecting one entry (a file whose oplock break never completes, say), it
 *       gives up that entry only: the entry is reported as if its attributes could not
 *       be read, and a fresh listing carries on with the rest, keeping what the stuck
 *       one found. The stuck thread holds the open directory stream, so the fresh
 *       listing reads the directory again, skipping the entries already looked at,
 *       which keeps every entry looked at as the listing returns it. Should the fresh
 *       listing get stuck again before it has looked at any other entry, or once
 *       {@value #MAX_STUCK_ENTRIES_PER_DIRECTORY} entries of the directory have been
 *       given up on, the directory seems stuck as a whole (a folder whose every file
 *       another client holds): the rest of it is given up, and it is reported incomplete
 *       with what was found in it kept. So a directory stuck as a whole costs two
 *       timeouts, and no one directory can use up the timeouts of its library path.</li>
 *   <li>An abandoned thread cannot be reclaimed while it is stuck (it ignores
 *       interrupts while in IO), but it stops at its next filesystem call once the
 *       stuck one returns. So that stuck threads cannot pile up, after
 *       {@value #MAX_TIMEOUTS_PER_PATH} timeouts (of directories or entries) under one
 *       library path the walk stops listing that path, and while
 *       {@value #MAX_STRANDED_LISTINGS} abandoned listings of this or earlier walks are
 *       still stuck, no walk lists anything more. Whatever is left out is reported
 *       incomplete.</li>
 * </ul>
 *
 * <p>Listing threads only read the filesystem and hand back what they found. They
 * are given the directory to list, the directories above it and what earlier
 * listings of it found, nothing of the tree, so a thread stuck for good holds on to
 * little. The calling thread owns the tree, the queue and every count, so a listing
 * that returns after it was abandoned is simply never looked at.
 */
@Slf4j
@Component
public class LibraryDirectoryWalker {

    static final int MAX_PARALLELISM = 64;
    static final int MAX_TIMEOUTS_PER_PATH = 10;
    /** Entries of one directory given up on one at a time at most; past that, the rest of the directory is. */
    static final int MAX_STUCK_ENTRIES_PER_DIRECTORY = MAX_TIMEOUTS_PER_PATH / 2;
    /** Each stuck listing holds a platform thread until its filesystem call returns. */
    static final int MAX_STRANDED_LISTINGS = 64;

    private static final String IGNORE_MARKER = ".ignore";
    private static final Consumer<Findings> NOTHING = findings -> {
    };

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
     * @param timedOut        true when some of that was given up on because the filesystem
     *                        did not respond in time
     * @param empty           true when the library path was listed and holds nothing at all,
     *                        which may be a share that is not mounted; it is then among the
     *                        incomplete paths too
     */
    record WalkedPath(Path path, Directory root, Set<Path> incompletePaths, boolean timedOut, boolean empty) {
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
        /** Some of it was given up on because the filesystem did not respond in time. */
        private boolean timedOut;
        /** The library path holds nothing at all. */
        private boolean empty;
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
     * A directory waiting to be listed: where its contents go, its lineage, which is null
     * for a library path (its listing reads its attributes first), and what listings of it
     * given up on found, null until one is.
     */
    private record Pending(PathWalk walk, Directory directory, Lineage lineage, Findings findings) {
    }

    /**
     * What the listings of one directory have found so far. One listing normally finds it
     * all. A listing given up on while it inspects an entry hands its findings over, and a
     * fresh listing reads the directory again and inspects only the entries not looked at
     * yet. They belong to one listing at a time: the walking thread takes them from a
     * listing stuck on an entry (see {@link Listing#giveUpStuckEntry(long)}) and hands them to
     * the next, or attaches them to the tree itself when it gives up the rest of the directory.
     */
    private static final class Findings {
        private final boolean libraryPath;
        /** The directory and those above it; for a library path, known once its attributes are read. */
        private Lineage directory;
        /** Names of the entries looked at, whatever they turned out to be. */
        private final Set<String> inspected = new HashSet<>();
        private final List<Lineage> subdirectories = new ArrayList<>();
        private final List<String> bookFiles = new ArrayList<>();
        private final List<Path> incompletePaths = new ArrayList<>();
        /** Book files that could not be looked at or read. */
        private final List<Path> unseenBookFiles = new ArrayList<>();
        /** Audio files among all the book files, seen or not. */
        private int audioFiles;
        /** Entries given up on while they were being inspected. */
        private int stuckEntries;
        /** How many entries had been looked at when the last one was given up on. */
        private int inspectedWhenLastStuck;

        Findings(Lineage directory) {
            this.libraryPath = directory == null;
            this.directory = directory;
        }

        /** Adds what inspecting {@code entry} found, so that no later listing looks at it again. */
        void record(Path entry, Consumer<Findings> found) {
            inspected.add(entry.getFileName().toString());
            found.accept(this);
        }

        void bookFile(String name, boolean audio) {
            bookFiles.add(name);
            if (audio) {
                audioFiles++;
            }
        }

        void unseenBookFile(Path file, boolean audio) {
            unseenBookFiles.add(file);
            if (audio) {
                audioFiles++;
            }
        }

        /** An entry whose attributes could not be read, or that was given up on while it was being inspected. */
        void couldNotInspect(Path entry) {
            Optional<BookFileType> bookType = bookFileType(entry.getFileName().toString());
            if (bookType.isPresent()) {
                unseenBookFile(entry, bookType.get() == BookFileType.AUDIOBOOK);
            } else {
                // It may be a directory full of books, so it must not pass for absent.
                incompletePaths.add(entry);
            }
        }

        /**
         * Adds {@code entry}, given up on while it was being inspected, and returns whether a fresh
         * listing is to carry on with the rest of the directory. Not when the listing that carried on
         * after the last entry given up on got stuck before looking at any other, nor once
         * {@value #MAX_STUCK_ENTRIES_PER_DIRECTORY} entries have been given up on: the directory itself
         * then seems stuck, and each further listing would only strand another thread.
         */
        boolean stuckOn(Path entry) {
            boolean stuckAgainAtOnce = stuckEntries > 0 && inspected.size() == inspectedWhenLastStuck;
            record(entry, found -> found.couldNotInspect(entry));
            stuckEntries++;
            inspectedWhenLastStuck = inspected.size();
            return !stuckAgainAtOnce && stuckEntries < MAX_STUCK_ENTRIES_PER_DIRECTORY;
        }

        /** What the listings found, once every entry of the directory at {@code path} has been looked at. */
        Contents contents(Path path) {
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
            return contents(incompletePaths);
        }

        /**
         * What the listings found before the rest of the directory at {@code path} was given up on. The
         * entries not looked at may be anything, so the whole directory is in doubt.
         */
        Contents givenUp(Path path) {
            return contents(List.of(path));
        }

        private Contents contents(List<Path> incomplete) {
            subdirectories.sort(Comparator.comparing(lineage -> lineage.path().getFileName().toString()));
            bookFiles.sort(Comparator.naturalOrder());
            return new Contents(List.copyOf(subdirectories), List.copyOf(bookFiles), List.copyOf(incomplete), false);
        }
    }

    /**
     * What a listing thread is given. It stamps {@code lastProgress} each time a
     * filesystem call returns, and the walking thread reads it to spot a stall.
     */
    @RequiredArgsConstructor
    private static final class Listing {
        private final Path path;
        private final Findings findings;
        private volatile long lastProgress = System.nanoTime();
        /** The entry being inspected, or null while the directory itself is being read. */
        private final AtomicReference<Path> inspecting = new AtomicReference<>();

        /** Called after each filesystem call; throws once the walk has given up on this listing. */
        void progressed() throws InterruptedIOException {
            lastProgress = System.nanoTime();
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedIOException("Gave up listing " + path);
            }
        }

        /** True when the current filesystem call has not returned within {@code timeoutNanos}. */
        boolean stalled(long timeoutNanos) {
            return System.nanoTime() - lastProgress >= timeoutNanos;
        }

        /** Called once the listing has returned {@code entry}, before the first filesystem call about it. */
        void startInspecting(Path entry) {
            inspecting.set(entry);
        }

        /**
         * Called once {@code entry} is inspected, before what it found is added to the findings;
         * throws when the walk has given up on the entry meanwhile, and the findings with it.
         */
        void finishInspecting(Path entry) throws InterruptedIOException {
            if (!inspecting.compareAndSet(entry, null)) {
                throw new InterruptedIOException("Gave up inspecting " + entry);
            }
        }

        /**
         * Gives up on the entry this listing has been stuck inspecting for {@code timeoutNanos}
         * and returns it. The findings go with it: the listing's thread no longer touches them,
         * so the walking thread can hand them on. Null when the listing is reading the directory
         * itself, or is not stuck.
         */
        Path giveUpStuckEntry(long timeoutNanos) {
            Path entry = inspecting.get();
            // Checked after reading the entry: a listing that moved on to another entry made progress first, and
            // one that finishes this entry now makes the exchange below fail.
            if (entry == null || !stalled(timeoutNanos)) {
                return null;
            }
            return inspecting.compareAndSet(entry, null) ? entry : null;
        }
    }

    /**
     * What one listing found, for the walking thread to attach to the tree. {@code empty} is true for a
     * library path that holds nothing at all.
     */
    private record Contents(List<Lineage> subdirectories, List<String> bookFiles, List<Path> incompletePaths, boolean empty) {
        static final Contents SKIPPED = new Contents(List.of(), List.of(), List.of(), false);
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
                queue.add(new Pending(walk, new Directory(path), null, null));
            }
        }

        ExecutorService executor = Executors.newThreadPerTaskExecutor(Thread.ofPlatform().daemon(true).name("library-walk-", 0).factory());
        BlockingQueue<ListingTask> completed = new LinkedBlockingQueue<>();
        Map<ListingTask, Pending> running = new HashMap<>();
        try {
            while (!queue.isEmpty() || !running.isEmpty()) {
                while (running.size() < parallelism && !queue.isEmpty()) {
                    Pending pending = queue.poll();
                    PathWalk walk = pending.walk();
                    if (!walk.stopped && strandedListings.get() >= MAX_STRANDED_LISTINGS) {
                        stop(walk);
                        walk.timedOut = true;
                        log.warn("Stopped walking library path [{}]: {} directory listings given up on by this or earlier "
                                        + "scans are still stuck in filesystem calls, so the rest of it was not scanned",
                                walk.path, strandedListings.get());
                    }
                    if (walk.stopped) {
                        finished(walk);
                        continue;
                    }
                    Findings findings = pending.findings() != null ? pending.findings() : new Findings(pending.lineage());
                    ListingTask task = new ListingTask(new Listing(pending.directory().getPath(), findings), completed);
                    executor.execute(task);
                    running.put(task, pending);
                }
                if (running.isEmpty()) {
                    break;
                }

                ListingTask done = completed.poll(nanosUntilStall(running.keySet()), TimeUnit.NANOSECONDS);
                if (done == null) {
                    abandonStalled(running, queue);
                } else {
                    // Null for a listing given up on earlier, whose cancellation (or end, had it just ended) is
                    // queued too; a late return is not.
                    Pending pending = running.remove(done);
                    if (pending != null) {
                        collect(pending, done, queue);
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
            results.add(new WalkedPath(walk.path, walk.root, Set.copyOf(walk.incompletePaths), walk.timedOut, walk.empty));
        }
        return results;
    }

    /** Takes what a listing that ended found, or reports its directory incomplete when it failed. */
    private static void collect(Pending pending, ListingTask done, Deque<Pending> queue) throws InterruptedException {
        Contents contents;
        try {
            contents = done.get();
        } catch (ExecutionException e) {
            Path path = pending.directory().getPath();
            Throwable cause = e.getCause();
            pending.walk().incompletePaths.add(path);
            if (cause instanceof IOException) {
                log.warn("Could not list directory [{}], so nothing under it was scanned: {}", path, cause.toString());
            } else {
                log.warn("Could not list directory [{}], so nothing under it was scanned", path, cause);
            }
            finished(pending.walk());
            return;
        }
        attach(pending, contents, queue);
    }

    /** Puts what the listings of a directory found into the tree, and queues its subdirectories. */
    private static void attach(Pending pending, Contents contents, Deque<Pending> queue) {
        PathWalk walk = pending.walk();
        Directory directory = pending.directory();
        List<Directory> subdirectories = new ArrayList<>(contents.subdirectories().size());
        for (Lineage lineage : contents.subdirectories()) {
            Directory subdirectory = new Directory(lineage.path());
            subdirectories.add(subdirectory);
            if (!walk.stopped) {
                walk.outstanding++;
                queue.add(new Pending(walk, subdirectory, lineage, null));
            }
        }
        directory.subdirectories = List.copyOf(subdirectories);
        directory.bookFiles = contents.bookFiles();
        if (pending.lineage() == null) {
            walk.root = directory;
            walk.empty = contents.empty();
        }
        walk.directoriesListed++;
        walk.bookFiles += contents.bookFiles().size();
        walk.incompletePaths.addAll(contents.incompletePaths());
        finished(walk);
    }

    private void abandonStalled(Map<ListingTask, Pending> running, Deque<Pending> queue) {
        long timeout = directoryTimeout.toNanos();
        Iterator<Map.Entry<ListingTask, Pending>> it = running.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<ListingTask, Pending> entry = it.next();
            ListingTask task = entry.getKey();
            Listing listing = task.listing;
            Pending pending = entry.getValue();
            PathWalk walk = pending.walk();
            Path path = pending.directory().getPath();
            Path stuckEntry = listing.giveUpStuckEntry(timeout);
            if (stuckEntry != null) {
                // False when its thread has returned meanwhile and stopped on finding the entry given up on.
                task.abandon();
                it.remove();
                Findings findings = listing.findings;
                if (findings.stuckOn(stuckEntry)) {
                    log.warn("Gave up inspecting [{}] after {} ms without filesystem progress, so it was not scanned; "
                            + "carrying on with the rest of [{}]", stuckEntry, directoryTimeout.toMillis(), path);
                    // The directory is still outstanding: a fresh listing carries on, ahead of directories not listed yet.
                    queue.addFirst(new Pending(walk, pending.directory(), pending.lineage(), findings));
                } else {
                    log.warn("Gave up inspecting [{}] after {} ms without filesystem progress; [{}] seems stuck as a whole, "
                                    + "so the rest of it was not scanned and nothing under it is treated as deleted",
                            stuckEntry, directoryTimeout.toMillis(), path);
                    attach(pending, findings.givenUp(path), queue);
                }
            } else if (listing.stalled(timeout) && task.abandon()) {
                // Stuck reading the directory itself. A listing that finished meanwhile is not abandoned: it waits
                // in the completion queue for the next poll.
                it.remove();
                walk.incompletePaths.add(path);
                log.warn("Gave up listing directory [{}] after {} ms without filesystem progress, so nothing under it was scanned",
                        path, directoryTimeout.toMillis());
                finished(walk);
            } else {
                continue;
            }
            walk.timedOut = true;
            walk.timeouts++;
            if (walk.timeouts == MAX_TIMEOUTS_PER_PATH) {
                stop(walk);
                log.warn("Stopped walking library path [{}] after {} timeouts; the rest of it was not scanned",
                        walk.path, walk.timeouts);
            }
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

    /**
     * Runs on a listing thread: reads one directory, or what is left of it after a listing
     * given up on, and returns what it holds.
     */
    private Contents list(Listing listing) throws IOException {
        Path path = listing.path;
        Findings findings = listing.findings;
        if (findings.directory == null) {
            BasicFileAttributes attrs = readAttributes(path, listing);
            if (!attrs.isDirectory()) {
                throw new NotDirectoryException(path.toString());
            }
            findings.directory = new Lineage(path, attrs.fileKey(), null);
        }

        // A listing carrying on after one given up on knows the directory has entries.
        boolean empty = findings.inspected.isEmpty();
        try (DirectoryStream<Path> stream = fileAccess.newDirectoryStream(path)) {
            listing.progressed();
            for (Path entry : stream) {
                listing.progressed();
                empty = false;
                String name = entry.getFileName().toString();
                if (findings.inspected.contains(name)) {
                    // Looked at by the listing this one carries on from.
                    continue;
                }
                // The library path itself is scanned even when it holds a marker.
                if (!findings.libraryPath && name.equalsIgnoreCase(IGNORE_MARKER)) {
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

                listing.startInspecting(entry);
                Consumer<Findings> found = inspect(entry, findings.directory, listing);
                listing.finishInspecting(entry);
                findings.record(entry, found);
            }
        } catch (DirectoryIteratorException e) {
            throw e.getCause();
        }

        if (findings.libraryPath && empty) {
            log.warn("Library path [{}] is empty; reporting it as not scanned in case it is a share that is not mounted", path);
            return new Contents(List.of(), List.of(), List.of(path), true);
        }
        return findings.contents(path);
    }

    /**
     * Looks at one entry of {@code directory} and returns what it adds to the directory's
     * findings, which the listing adds only once it knows the walk has not given up on the
     * entry meanwhile.
     */
    private Consumer<Findings> inspect(Path entry, Lineage directory, Listing listing) throws InterruptedIOException {
        String name = entry.getFileName().toString();
        BasicFileAttributes attrs;
        try {
            attrs = readAttributes(entry, listing);
        } catch (NoSuchFileException e) {
            // Removed since the listing: genuinely gone.
            return NOTHING;
        } catch (InterruptedIOException e) {
            throw e;
        } catch (IOException e) {
            log.warn("Could not read attributes of [{}]: {}", entry, e.toString());
            return findings -> findings.couldNotInspect(entry);
        }

        if (attrs.isDirectory()) {
            if (isLoop(directory, entry, attrs.fileKey(), listing)) {
                log.error("Failed read path [{}]: symbolic link loop", entry);
                return NOTHING;
            }
            Lineage subdirectory = new Lineage(entry, attrs.fileKey(), directory);
            return findings -> findings.subdirectories.add(subdirectory);
        }
        Optional<BookFileType> bookType = bookFileType(name);
        if (bookType.isEmpty() || !attrs.isRegularFile() || attrs.size() == 0) {
            // Anything else (other files, empty files, broken links) is not a book and is skipped as before.
            return NOTHING;
        }
        boolean audio = bookType.get() == BookFileType.AUDIOBOOK;
        boolean readable = fileAccess.isReadable(entry);
        listing.progressed();
        if (!readable) {
            log.warn("Book file is not readable, so it was left out of the scan: {}", entry);
            return findings -> findings.unseenBookFile(entry, audio);
        }
        return findings -> findings.bookFile(name, audio);
    }

    private static Optional<BookFileType> bookFileType(String fileName) {
        return BookFileExtension.fromFileName(fileName).map(BookFileExtension::getType);
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
