package org.booklore.service.book;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.config.security.service.AuthenticationService;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.MetadataBatchProgressNotification;
import org.booklore.model.dto.request.MissingFileScanRequest;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.model.entity.TagEntity;
import org.booklore.model.enums.MetadataFetchTaskStatus;
import org.booklore.model.websocket.Topic;
import org.booklore.repository.BookRepository;
import org.booklore.repository.TagRepository;
import org.booklore.service.NotificationService;
import org.booklore.service.book.MissingFileScanLoader.FileCheckTarget;
import org.booklore.service.book.MissingFileScanLoader.FileRef;
import org.booklore.service.opds.MagicShelfBookService;
import org.booklore.task.TaskCancellationManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tags books whose file is no longer on disk, so they can be gathered with a
 * Magic Shelf rule on Tags.
 *
 * <p>Existence checks run in parallel and each is bounded by a timeout. Both
 * matter on network storage: a single {@code Files.exists()} against the CIFS
 * share here takes roughly two seconds, so a serial pass over a six-figure
 * library would run for days, and a wedged SMB path blocks a stat call in
 * uninterruptible IO, stalling the whole scan.
 *
 * <p>How the checks run is shaped by that wedge, as in the library directory walker:
 * <ul>
 *   <li>Checks run on platform threads, at most {@code parallelism} at a time. Not
 *       virtual threads: on JDK 25 a virtual thread blocked in a file call keeps its
 *       carrier, so a few checks wedged on the share would stall every virtual thread
 *       in the application, request handling included.</li>
 *   <li>A check still running {@code checkTimeoutSeconds} after it started is
 *       abandoned: its book is reported unresolved, never missing, and its slot goes
 *       to the next book. The abandoned thread cannot be reclaimed while it is stuck
 *       (it ignores interrupts while in IO), so while {@value #MAX_STRANDED_CHECKS}
 *       abandoned checks of this or earlier scans are still stuck, no more checks
 *       start. The scan waits up to {@link #STORAGE_RECOVERY_WAIT} for them to return,
 *       as they do once a share that dropped out reconnects, and otherwise stops and
 *       reports that storage is not responding rather than put more threads at risk.
 *       Once its call returns, an abandoned check stops without checking the book's
 *       other files.</li>
 *   <li>A book is missing only when storage says a file is not there and the book's
 *       library path is there with something in it: a share that is not mounted
 *       leaves an empty mount point, or none, under which every file is "not there".
 *       A check that fails with any other error (the share reconnecting, an IO error)
 *       is reported unresolved, like one that timed out.</li>
 * </ul>
 *
 * <p>Checking threads work from plain values produced by
 * {@link MissingFileScanLoader}: no entities, no session and no database
 * connection is held while waiting on storage.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MissingFileScanService {

    public static final String DEFAULT_TAG_NAME = "File Not Found";

    private static final int CHUNK_SIZE = 500;
    private static final int MAX_PARALLELISM = 128;
    /** Each stuck check holds a platform thread until its filesystem call returns. */
    static final int MAX_STRANDED_CHECKS = 64;
    /**
     * Long enough to ride out a CIFS reconnect, during which every call to the share blocks:
     * the client reconnects to an unresponsive server after twice its echo interval, 60 s by
     * default.
     */
    static final Duration STORAGE_RECOVERY_WAIT = Duration.ofMinutes(2);
    private static final long RECOVERY_POLL_NANOS = TimeUnit.SECONDS.toNanos(1);

    private final BookRepository bookRepository;
    private final TagRepository tagRepository;
    private final MissingFileScanLoader scanLoader;
    private final NotificationService notificationService;
    private final PlatformTransactionManager transactionManager;
    private final AuthenticationService authenticationService;
    private final TaskCancellationManager cancellationManager;
    private final MagicShelfBookService magicShelfBookService;

    /** Checks abandoned by any scan whose threads have not returned yet. */
    private final AtomicInteger strandedChecks = new AtomicInteger();

    /**
     * Thrown once a scan stops because storage is not responding, after reporting it, so the
     * task that ran the scan is recorded as failed rather than completed.
     */
    static final class StorageNotRespondingException extends IllegalStateException {
        StorageNotRespondingException(String message) {
            super(message);
        }
    }

    /** Outcome of checking one book's files. Unresolved when storage could not say. */
    private record CheckResult(Long bookId, String title, List<String> absent, boolean unresolved,
                               boolean alreadyTagged) {
        boolean isMissing() {
            return !absent.isEmpty();
        }
    }

    /**
     * What checking a chunk came to. When it stopped short because too many checks stayed
     * stuck, {@code stuckChecks} is how many were stuck when it stopped; otherwise 0.
     */
    private record ChunkCheck(List<CheckResult> results, int stuckChecks) {
    }

    /**
     * One book's check, which queues itself for the scanning thread once done. Whether
     * its thread outlives the scan's interest is settled exactly once: by the check
     * finishing, or by the scan abandoning it first, in which case the thread counts as
     * stranded until it returns.
     */
    private final class CheckTask extends FutureTask<CheckResult> {
        private final FileCheckTarget target;
        private final Queue<CheckTask> completed;
        private final long startedAt = System.nanoTime();
        private final AtomicBoolean settled = new AtomicBoolean();

        CheckTask(FileCheckTarget target, Map<Path, Boolean> libraryPaths, Queue<CheckTask> completed) {
            super(() -> check(target, libraryPaths));
            this.target = target;
            this.completed = completed;
        }

        @Override
        public void run() {
            try {
                super.run();
            } finally {
                if (!settled.compareAndSet(false, true)) {
                    strandedChecks.decrementAndGet();
                }
            }
        }

        @Override
        protected void done() {
            completed.add(this);
        }

        /**
         * Gives up on the check and interrupts it, which a thread stuck in a CIFS call
         * ignores; it is just never waited for. False when the check has finished and
         * waits to be collected instead.
         */
        boolean abandon() {
            if (!settled.compareAndSet(false, true)) {
                return false;
            }
            strandedChecks.incrementAndGet();
            return cancel(true);
        }
    }

    /** Abandoned checks whose threads have not returned yet. */
    int strandedChecks() {
        return strandedChecks.get();
    }

    public void scan(MissingFileScanRequest request, String taskId) {
        BookLoreUser user = authenticationService.getAuthenticatedUser();
        Long userId = user != null ? user.getId() : null;

        String tagName = request.getTagName() == null || request.getTagName().isBlank()
                ? DEFAULT_TAG_NAME
                : request.getTagName().trim();

        int parallelism = Math.max(1, Math.min(MAX_PARALLELISM, request.getParallelism()));
        int timeoutSeconds = Math.max(1, request.getCheckTimeoutSeconds());
        Duration timeout = Duration.ofSeconds(timeoutSeconds);

        try {
            Set<Long> bookIds = resolveBookIds(request, userId);
            if (bookIds.isEmpty()) {
                sendProgress(taskId, 0, 0, "No books found for the given scope.",
                        MetadataFetchTaskStatus.COMPLETED);
                return;
            }

            List<Long> ordered = new ArrayList<>(bookIds);
            int total = ordered.size();
            log.info("MissingFileScan [{}]: checking {} books, tag '{}', parallelism {}, timeout {}s{}",
                    taskId, total, tagName, parallelism, timeoutSeconds,
                    request.isDryRun() ? " (dry run)" : "");

            TransactionTemplate tx = new TransactionTemplate(transactionManager);
            TagEntity tag = request.isDryRun() ? null : resolveTag(tx, tagName);

            int completed = 0;
            int missing = 0;
            int cleared = 0;
            int unresolved = 0;
            boolean cancelled = false;
            int stuckChecks = 0;
            long startedAt = System.currentTimeMillis();

            // Threads are reused, as a scan runs one check per book.
            ExecutorService pool = Executors.newCachedThreadPool(
                    Thread.ofPlatform().daemon(true).name("missing-file-check-", 0).factory());
            try {
                for (int offset = 0; offset < total && !cancelled && stuckChecks == 0; offset += CHUNK_SIZE) {
                    List<Long> chunk = ordered.subList(offset, Math.min(offset + CHUNK_SIZE, total));
                    List<FileCheckTarget> targets = scanLoader.load(new LinkedHashSet<>(chunk), tagName);

                    ChunkCheck checked = checkInParallel(targets, pool, parallelism, timeout, taskId);
                    List<CheckResult> results = checked.results();
                    stuckChecks = checked.stuckChecks();
                    completed += results.size();

                    for (CheckResult r : results) {
                        if (r.unresolved()) unresolved++;
                    }

                    if (!request.isDryRun()) {
                        int[] applied = applyChunk(tx, results, tag, tagName, request.isClearTagWhenPresent());
                        missing += applied[0];
                        cleared += applied[1];
                    } else {
                        for (CheckResult r : results) {
                            if (r.isMissing()) missing++;
                        }
                    }

                    long elapsed = Math.max(1, (System.currentTimeMillis() - startedAt) / 1000);
                    sendProgress(taskId, completed, total,
                            String.format("Checked %d of %d — %d missing, %d unresolved (%d books/sec)",
                                    completed, total, missing, unresolved, completed / elapsed),
                            MetadataFetchTaskStatus.IN_PROGRESS);

                    if (cancellationManager.isTaskCancelled(taskId)) cancelled = true;
                }
            } finally {
                // Not close(): that waits for every thread, including any still wedged in IO.
                pool.shutdownNow();
            }

            String prefix = request.isDryRun() ? "DRY RUN — " : "";
            String counts = String.format("%d missing (tagged '%s'), %d restored, %d unresolved, %d checked in %ds.",
                    missing, tagName, cleared, unresolved, completed, (System.currentTimeMillis() - startedAt) / 1000);

            if (stuckChecks > 0) {
                String message = String.format(
                        "%sStorage is not responding: %d file checks got stuck waiting on it and did not return, "
                                + "so the scan stopped. %s",
                        prefix, stuckChecks, counts);
                sendProgress(taskId, completed, total, message, MetadataFetchTaskStatus.ERROR);
                log.error("MissingFileScan [{}]: {}", taskId, message);
                throw new StorageNotRespondingException(message);
            }

            String summary = prefix + "File check complete. " + counts;
            sendProgress(taskId, cancelled ? completed : total, total,
                    cancelled ? "Scan cancelled. " + summary : summary,
                    cancelled ? MetadataFetchTaskStatus.CANCELLED : MetadataFetchTaskStatus.COMPLETED);
            log.info("MissingFileScan [{}]: {}", taskId, summary);

        } catch (StorageNotRespondingException e) {
            throw e;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            // The task runner logs the stack trace and records the task as failed.
            log.error("MissingFileScan [{}]: fatal error: {}", taskId, e.getMessage());
            sendProgress(taskId, 0, 0, "Fatal error: " + e.getMessage(),
                    MetadataFetchTaskStatus.ERROR);
            throw e instanceof RuntimeException runtime ? runtime : new IllegalStateException(e.getMessage(), e);
        }
    }

    // ── parallel checking ─────────────────────────────────────────────────────

    /**
     * Checks a chunk's books, at most {@code parallelism} at a time, each given
     * {@code timeout} from when it starts. Starts no checks while too many abandoned
     * ones are stuck, and reports the chunk cut short if they stay stuck or the scan
     * is cancelled meanwhile.
     */
    private ChunkCheck checkInParallel(List<FileCheckTarget> targets, ExecutorService pool, int parallelism,
                                       Duration timeout, String taskId) throws InterruptedException {
        BlockingQueue<CheckTask> completed = new LinkedBlockingQueue<>();
        Set<CheckTask> running = new HashSet<>();
        List<CheckResult> results = new ArrayList<>(targets.size());
        Iterator<FileCheckTarget> pending = targets.iterator();
        Map<Path, Boolean> libraryPaths = new ConcurrentHashMap<>();
        try {
            while (true) {
                while (running.size() < parallelism && pending.hasNext()
                        && strandedChecks.get() < MAX_STRANDED_CHECKS) {
                    CheckTask task = new CheckTask(pending.next(), libraryPaths, completed);
                    pool.execute(task);
                    running.add(task);
                }
                if (running.isEmpty()) {
                    if (!pending.hasNext() || cancellationManager.isTaskCancelled(taskId)) {
                        return new ChunkCheck(results, 0);
                    }
                    int stuck = awaitStuckChecks(taskId);
                    if (stuck > 0) {
                        return new ChunkCheck(results, stuck);
                    }
                    continue;
                }

                CheckTask done = completed.poll(nanosUntilTimeout(running, timeout), TimeUnit.NANOSECONDS);
                if (done == null) {
                    abandonTimedOut(running, results, timeout);
                } else if (running.remove(done)) {
                    // Not for a check abandoned earlier, whose cancellation is queued too (a late return is not).
                    results.add(collect(done));
                }
            }
        } finally {
            // Whatever still runs is given up on, so it counts as stranded until its thread returns.
            running.forEach(CheckTask::abandon);
        }
    }

    /**
     * Waits while too many abandoned checks are stuck, for enough of them to return. Returns
     * 0 once they have, or the scan is cancelled; otherwise how many are still stuck when
     * the wait runs out.
     */
    private int awaitStuckChecks(String taskId) throws InterruptedException {
        Duration wait = storageRecoveryWait();
        log.warn("MissingFileScan [{}]: {} file checks given up on by this or earlier scans are still stuck, so no "
                + "more are started; waiting up to {}s for storage to answer", taskId, strandedChecks.get(),
                wait.toSeconds());
        long deadline = System.nanoTime() + wait.toNanos();
        while (true) {
            int stuck = strandedChecks.get();
            if (stuck < MAX_STRANDED_CHECKS) {
                log.info("MissingFileScan [{}]: storage is answering again, checking carries on", taskId);
                return 0;
            }
            if (cancellationManager.isTaskCancelled(taskId)) {
                return 0;
            }
            long left = deadline - System.nanoTime();
            if (left <= 0) {
                return stuck;
            }
            TimeUnit.NANOSECONDS.sleep(Math.min(left, RECOVERY_POLL_NANOS));
        }
    }

    private CheckResult collect(CheckTask task) throws InterruptedException {
        try {
            return task.get();
        } catch (ExecutionException e) {
            // Storage could not say whether the files are there: as with a timeout, leave the book alone.
            log.warn("MissingFileScan: error checking '{}' — left unchanged: {}",
                    task.target.title(), e.getCause().toString());
            return unresolved(task.target);
        }
    }

    private void abandonTimedOut(Set<CheckTask> running, List<CheckResult> results, Duration timeout) {
        long now = System.nanoTime();
        Iterator<CheckTask> it = running.iterator();
        while (it.hasNext()) {
            CheckTask task = it.next();
            // A check that finished meanwhile is waiting in the completion queue for the next poll.
            if (now - task.startedAt < timeout.toNanos() || !task.abandon()) {
                continue;
            }
            it.remove();
            // Storage is not answering for this book. Give up on it and treat it as
            // unknown rather than missing — tagging on a timeout would mark healthy
            // books when the share is merely slow.
            log.warn("MissingFileScan: timed out checking '{}' after {}s — left unchanged",
                    task.target.title(), timeout.toSeconds());
            results.add(unresolved(task.target));
        }
    }

    private static long nanosUntilTimeout(Collection<CheckTask> running, Duration timeout) {
        long now = System.nanoTime();
        long limit = timeout.toNanos();
        long wait = limit;
        for (CheckTask task : running) {
            wait = Math.min(wait, limit - (now - task.startedAt));
        }
        return Math.max(0, wait);
    }

    private static CheckResult unresolved(FileCheckTarget target) {
        return new CheckResult(target.bookId(), target.title(), List.of(), true, target.alreadyTagged());
    }

    /**
     * Runs on a checking thread: which of the target's files are not there, or unresolved
     * when its library path is not there either. Throws when storage cannot say, so the
     * book is left unresolved. Also throws once the scan has given up on the check, so a
     * thread that was stuck makes no more calls to the share for a result nobody is
     * waiting for.
     *
     * @param libraryPaths whether each library path is there, shared by a chunk's checks
     */
    private CheckResult check(FileCheckTarget target, Map<Path, Boolean> libraryPaths) throws IOException {
        List<String> absent = new ArrayList<>();
        for (FileRef file : target.files()) {
            Path path = file.path();
            if (path == null) {
                absent.add(file.name());
                continue;
            }
            stopIfAbandoned(target);
            if (!exists(path)) absent.add(file.name());
        }
        if (!absent.isEmpty() && target.libraryPath() != null) {
            stopIfAbandoned(target);
            if (!libraryPathIsThere(target.libraryPath(), libraryPaths)) {
                return unresolved(target);
            }
        }
        return new CheckResult(target.bookId(), target.title(), absent, false, target.alreadyTagged());
    }

    private static void stopIfAbandoned(FileCheckTarget target) throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Gave up checking " + target.title());
        }
    }

    /**
     * Whether a library path is there with something in it. Looked up by the first of a
     * chunk's checks to find a file missing (or the first few, when they find one at once),
     * so a share that drops out mid-scan is noticed by the next chunk.
     */
    private boolean libraryPathIsThere(Path libraryPath, Map<Path, Boolean> libraryPaths) throws IOException {
        Boolean there = libraryPaths.get(libraryPath);
        if (there == null) {
            there = hasEntries(libraryPath);
            if (libraryPaths.putIfAbsent(libraryPath, there) == null && !there) {
                log.warn("MissingFileScan: library path {} is missing or empty, so its books are left unchanged. "
                        + "Is the share mounted?", libraryPath);
            }
        }
        return there;
    }

    /**
     * The storage call a check makes: false only when the file is not there. Package-private
     * so tests can stand in a share that stalls.
     *
     * @throws IOException when storage cannot say, e.g. the share is reconnecting or failing
     *                     reads. {@code Files.exists} makes the same {@code access} call but
     *                     answers false for any error, which would tag healthy books.
     */
    boolean exists(Path path) throws IOException {
        try {
            path.getFileSystem().provider().checkAccess(path);
            return true;
        } catch (NoSuchFileException e) {
            return false;
        }
    }

    /** The storage call behind {@link #libraryPathIsThere}. Package-private, like {@link #exists}. */
    boolean hasEntries(Path dir) throws IOException {
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir)) {
            return entries.iterator().hasNext();
        } catch (NoSuchFileException | NotDirectoryException e) {
            return false;
        }
    }

    /** Package-private so tests need not wait {@link #STORAGE_RECOVERY_WAIT}. */
    Duration storageRecoveryWait() {
        return STORAGE_RECOVERY_WAIT;
    }

    // ── persistence ───────────────────────────────────────────────────────────

    /** Returns {newlyMissing, cleared}. */
    private int[] applyChunk(TransactionTemplate tx, List<CheckResult> results,
                             TagEntity tag, String tagName, boolean clearWhenPresent) {
        List<CheckResult> toTag = results.stream()
                .filter(r -> !r.unresolved() && r.isMissing() && !r.alreadyTagged())
                .toList();
        List<CheckResult> toClear = clearWhenPresent
                ? results.stream().filter(r -> !r.unresolved() && !r.isMissing() && r.alreadyTagged()).toList()
                : List.of();

        int stillMissing = (int) results.stream().filter(r -> !r.unresolved() && r.isMissing()).count();
        if (toTag.isEmpty() && toClear.isEmpty()) {
            return new int[]{stillMissing, 0};
        }

        Integer clearedCount = tx.execute(status -> {
            for (CheckResult r : toTag) {
                bookRepository.findById(r.bookId()).ifPresent(book -> {
                    BookMetadataEntity meta = book.getMetadata();
                    if (meta == null) return;
                    if (meta.getTags() == null) meta.setTags(new HashSet<>());
                    meta.getTags().add(tag);
                    bookRepository.save(book);
                    log.info("MissingFileScan: '{}' missing {} -> tagged", r.title(), r.absent());
                });
            }
            int c = 0;
            for (CheckResult r : toClear) {
                Optional<BookEntity> found = bookRepository.findById(r.bookId());
                if (found.isEmpty()) continue;
                BookEntity book = found.get();
                BookMetadataEntity meta = book.getMetadata();
                if (meta == null || meta.getTags() == null) continue;
                if (meta.getTags().removeIf(t -> t.getName() != null
                        && t.getName().equalsIgnoreCase(tagName))) {
                    bookRepository.save(book);
                    log.info("MissingFileScan: '{}' file present again -> tag removed", r.title());
                    c++;
                }
            }
            return c;
        });

        return new int[]{stillMissing, clearedCount == null ? 0 : clearedCount};
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private TagEntity resolveTag(TransactionTemplate tx, String tagName) {
        return tx.execute(status -> tagRepository.findByNameIgnoreCase(tagName)
                .orElseGet(() -> tagRepository.save(TagEntity.builder().name(tagName).build())));
    }

    private Set<Long> resolveBookIds(MissingFileScanRequest request, Long userId) {
        return switch (request.getRefreshType()) {
            case LIBRARY -> {
                if (request.getLibraryId() == null)
                    throw new IllegalArgumentException("libraryId required for LIBRARY scope");
                yield bookRepository.findBookIdsByLibraryId(request.getLibraryId());
            }
            case MAGIC_SHELF -> {
                if (request.getMagicShelfId() == null || userId == null)
                    throw new IllegalArgumentException(
                            "magicShelfId and authenticated user required for MAGIC_SHELF scope");
                yield new LinkedHashSet<>(magicShelfBookService.getBookIdsByMagicShelfId(
                        userId, request.getMagicShelfId()));
            }
            case BOOKS -> {
                if (request.getBookIds() == null || request.getBookIds().isEmpty())
                    throw new IllegalArgumentException("bookIds required for BOOKS scope");
                yield request.getBookIds();
            }
        };
    }

    private void sendProgress(String taskId, int current, int total, String message,
                              MetadataFetchTaskStatus status) {
        notificationService.sendMessage(
                Topic.BOOK_METADATA_BATCH_PROGRESS,
                new MetadataBatchProgressNotification(
                        taskId, current, total, message, status.name(), false));
    }
}
