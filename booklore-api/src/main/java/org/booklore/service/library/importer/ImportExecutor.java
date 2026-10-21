package org.booklore.service.library.importer;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.booklore.config.AppProperties;
import org.booklore.service.fileprocessor.prepare.PrepareContext;
import org.booklore.service.library.importer.FileTask.State;
import org.booklore.service.library.importer.Outcome.SkipReason;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/**
 * Runs the file reads of new-book imports, for every library scan in the process, a bounded number at a time.
 *
 * <p>It is shaped by network storage, where one filesystem call can take seconds and a wedged path can hold a thread
 * in uninterruptible IO for good:
 * <ul>
 *   <li>Each task that starts gets a new platform daemon thread. Not virtual threads: on JDK 25 a virtual thread
 *       blocked in a file call keeps its carrier, so a few reads wedged on a share would stall every virtual thread in
 *       the application, request handling included. A thread of its own also means a late interrupt can only reach
 *       the task it was meant for.</li>
 *   <li>At most {@code app.scan.import-io-parallelism} tasks run at once. Queued tasks hold no thread: one dispatcher
 *       thread starts them in submission order as IO slots free up.</li>
 *   <li>A task's deadline ({@code app.scan.import-file-timeout-seconds}) starts when it gets its slot, so time spent
 *       queued never counts against it. A task still running at its deadline is abandoned: its slot and render permit
 *       go to the next task at once, its child processes are killed and its thread is interrupted, from a thread of
 *       its own because that can block. That frees a thread in an interruptible wait, but not one stuck in a CIFS read;
 *       such a thread counts as stuck until it returns, and whatever it returns is dropped. Its heap stays reserved
 *       for another file timeout, and is given back and counted as orphaned only if the thread has not returned by
 *       then.</li>
 *   <li>While {@code app.scan.import-max-stuck-threads} abandoned threads are still stuck, or orphaned heap reaches
 *       half the budget, the breaker is open and tasks are skipped instead of started, so stuck threads cannot pile
 *       up: there are never more than max-stuck-threads + io-parallelism - 1, each with at most one more thread
 *       blocked interrupting it.</li>
 *   <li>Cover decodes, renders and encodes share {@code app.scan.import-render-parallelism} permits and a heap
 *       budget, through each task's {@link PrepareContext}; a PDF page render, which reads the file as it draws, takes
 *       only heap. Waits for them end at the task's deadline.</li>
 * </ul>
 *
 * <p>Nothing here waits for a worker thread to end, shutdown included, because a thread wedged in IO may never end.
 */
@Slf4j
@Component
public class ImportExecutor {

    /**
     * How long past its deadline a task waiting for a render permit or heap is left to give up by itself. Its wait
     * ends at the deadline, so it fails without leaving a stuck thread; it is only abandoned if it does not.
     */
    private static final Duration CAPACITY_WAIT_GRACE = Duration.ofSeconds(1);

    private final ImportSettings settings;
    private final long timeoutNanos;
    private final Semaphore ioPermits;
    private final Semaphore renderPermits;
    private final HeapBudget heap;
    private final AtomicInteger stuck = new AtomicInteger();
    private final AtomicLong orphanedHeapBytes = new AtomicLong();
    private final BlockingQueue<FileTask<?>> queue = new LinkedBlockingQueue<>();
    /** Started tasks whose threads have not returned, abandoned ones included. */
    private final Set<FileTask<?>> live = ConcurrentHashMap.newKeySet();
    private final ThreadFactory workers = Thread.ofPlatform().daemon(true).name("book-import-io-", 0).factory();
    private final ThreadFactory interrupters = Thread.ofPlatform().daemon(true).name("book-import-interrupt-", 0).factory();
    private final ScheduledThreadPoolExecutor watchdog;
    private final Thread dispatcher;
    private volatile boolean shuttingDown;

    @Autowired
    public ImportExecutor(AppProperties appProperties) {
        this(ImportSettings.from(appProperties.getScan(), Runtime.getRuntime().availableProcessors(), Runtime.getRuntime().maxMemory()));
    }

    ImportExecutor(ImportSettings settings) {
        this.settings = settings;
        this.timeoutNanos = settings.fileTimeout().toNanos();
        this.ioPermits = new Semaphore(settings.ioParallelism(), true);
        this.renderPermits = new Semaphore(settings.renderParallelism(), true);
        this.heap = new HeapBudget(settings.heapBudgetBytes());
        this.watchdog = new ScheduledThreadPoolExecutor(1, Thread.ofPlatform().daemon(true).name("book-import-watchdog").factory());
        this.watchdog.setRemoveOnCancelPolicy(true);
        this.dispatcher = Thread.ofPlatform().daemon(true).name("book-import-dispatch").start(this::dispatchLoop);
    }

    public ImportSettings settings() {
        return settings;
    }

    /**
     * Queues {@code work} to run on an import thread once an IO slot is free. Never blocks.
     *
     * @param label           names the task in logs and in its outcome
     * @param skipBeforeStart checked before the task starts; true skips it with {@link SkipReason#GROUP_FAILED}
     * @throws RejectedExecutionException once the executor is shutting down
     */
    public <T> FileTask<T> submit(String label, PrepareWork<T> work, BooleanSupplier skipBeforeStart) {
        if (shuttingDown) {
            throw new RejectedExecutionException("Book import is shutting down");
        }
        FileTask<T> task = new FileTask<>(label, work, skipBeforeStart, renderPermits, heap);
        queue.add(task);
        if (shuttingDown) {
            // shutdown() may have emptied the queue just before this task joined it
            task.skip(SkipReason.SHUTDOWN);
        }
        return task;
    }

    /** Whether storage looks wedged, so tasks are skipped instead of started. */
    public boolean breakerOpen() {
        return stuck.get() >= settings.maxStuckThreads() || orphanedHeapBytes.get() >= settings.heapBudgetBytes() / 2;
    }

    /** Abandoned tasks whose threads have not returned yet. */
    public int stuckThreads() {
        return stuck.get();
    }

    /** Heap taken back from abandoned tasks whose threads may still be using it. */
    public long orphanedHeapBytes() {
        return orphanedHeapBytes.get();
    }

    public boolean isShuttingDown() {
        return shuttingDown;
    }

    /**
     * Stops starting tasks: queued ones and running ones end as {@link SkipReason#SHUTDOWN} at once, running ones are
     * abandoned (interrupted, their processes killed), and later submissions are rejected. Returns without waiting
     * for any thread.
     */
    @PreDestroy
    void shutdown() {
        shuttingDown = true;
        dispatcher.interrupt();
        skipQueued();
        live.forEach(task -> abandon(task, true));
        // Not close() or awaitTermination(): both wait for threads, and a thread wedged in IO may never end
        watchdog.shutdownNow();
    }

    int availableIoPermits() {
        return ioPermits.availablePermits();
    }

    int availableRenderPermits() {
        return renderPermits.availablePermits();
    }

    long availableHeapBytes() {
        return heap.availableBytes();
    }

    private void dispatchLoop() {
        try {
            while (!shuttingDown) {
                FileTask<?> task = queue.take();
                try {
                    dispatch(task);
                } catch (InterruptedException e) {
                    task.skip(SkipReason.SHUTDOWN);
                    throw e;
                } catch (RuntimeException e) {
                    // One bad task must not stop the dispatcher every scan depends on
                    log.error("Could not start import task {}", task.label(), e);
                    task.failToStart(e);
                }
            }
        } catch (InterruptedException e) {
            // shutdown() interrupts the dispatcher
        }
        skipQueued();
    }

    private void dispatch(FileTask<?> task) throws InterruptedException {
        if (task.state() != State.QUEUED) {
            return; // cancelled while queued
        }
        if (task.skipBeforeStart().getAsBoolean()) {
            task.skip(SkipReason.GROUP_FAILED);
            return;
        }

        ioPermits.acquire();
        boolean handedOver = false;
        try {
            SkipReason skip = shuttingDown ? SkipReason.SHUTDOWN
                    : breakerOpen() ? SkipReason.STORAGE_STUCK
                    // The group may have failed while the task waited for its slot
                    : task.skipBeforeStart().getAsBoolean() ? SkipReason.GROUP_FAILED
                    : null;
            if (skip != null) {
                task.skip(skip);
            } else if (task.transition(State.QUEUED, State.RUNNING)) {
                // From here on the slot is the task's: whoever ends it releases the slot
                handedOver = true;
                start(task);
            }
        } finally {
            if (!handedOver) {
                ioPermits.release();
            }
        }
    }

    private <T> void start(FileTask<T> task) {
        try {
            Thread thread = workers.newThread(() -> run(task));
            long now = System.nanoTime();
            task.started(thread, now, now + timeoutNanos);
            live.add(task);
            task.setDeadline(watchdog.schedule(() -> onDeadline(task), timeoutNanos, TimeUnit.NANOSECONDS));
            thread.start();
        } catch (RuntimeException | Error e) {
            // No thread could be created, or the watchdog has shut down
            log.error("Could not start import task {}: {}", task.label(), e.toString());
            settle(task, task.transition(State.RUNNING, State.FAILED), new Outcome.Failed<>(task.label(), e));
            return;
        }
        if (shuttingDown) {
            // shutdown() may have gone through the live tasks just before this one joined them
            abandon(task, true);
        }
    }

    private <T> void run(FileTask<T> task) {
        boolean finished;
        Outcome<T> outcome;
        try {
            T value = ScopedValue.where(PrepareContext.CURRENT, task.context()).call(() -> task.work().run(task.context()));
            finished = task.transition(State.RUNNING, State.DONE);
            outcome = new Outcome.Done<>(task.label(), value, task.elapsedNanos());
        } catch (Throwable e) {
            finished = task.transition(State.RUNNING, State.FAILED);
            outcome = new Outcome.Failed<>(task.label(), e);
        }
        settle(task, finished, outcome);
    }

    /**
     * Cleans up after a task whose thread has returned, or could not be started. {@code finished} says whether it
     * ended the task itself; if not, the task was abandoned first, which already published its outcome and released
     * its slot.
     */
    private <T> void settle(FileTask<T> task, boolean finished, Outcome<T> outcome) {
        try {
            task.context().releaseAll();
            task.cancelDeadline();
            live.remove(task);
            if (!finished) {
                returnedAfterAbandon(task);
            }
        } finally {
            if (finished) {
                // Published before the slot is given up: the dispatcher may be waiting for it to start the next file of
                // this task's group, and must see in its skipBeforeStart that the group failed
                task.complete(outcome);
                ioPermits.release();
            }
        }
    }

    private void returnedAfterAbandon(FileTask<?> task) {
        // Abandonment counts the task under this lock, so this cannot run before it is counted
        synchronized (task) {
            stuck.decrementAndGet();
            orphanedHeapBytes.addAndGet(-task.context().orphanedBytes());
        }
        log.debug("Abandoned import task {} returned after {} s", task.label(), TimeUnit.NANOSECONDS.toSeconds(task.elapsedNanos()));
    }

    private void onDeadline(FileTask<?> task) {
        if (task.context().awaitingCapacity()) {
            // Abandoning it would count a thread as stuck that is about to return by itself
            task.setDeadline(watchdog.schedule(() -> abandon(task, false), CAPACITY_WAIT_GRACE.toNanos(), TimeUnit.NANOSECONDS));
            return;
        }
        abandon(task, false);
    }

    private <T> void abandon(FileTask<T> task, boolean shutdown) {
        synchronized (task) {
            if (!task.transition(State.RUNNING, State.ABANDONED)) {
                return; // it ended first
            }
            stuck.incrementAndGet();
            task.context().revoke();
        }
        log.debug("Gave up on import task {} after {} ms; {} import threads stuck",
                task.label(), TimeUnit.NANOSECONDS.toMillis(task.elapsedNanos()), stuck.get());
        // Published before the slot is given up, as in settle()
        task.complete(shutdown
                ? new Outcome.Skipped<>(task.label(), SkipReason.SHUTDOWN)
                : new Outcome.TimedOut<>(task.label(), task.elapsedNanos()));
        // The slot is forfeited so scans keep their parallelism; the worker will not release it again
        ioPermits.release();
        task.context().destroyProcesses();
        interruptElsewhere(task);
        if (shutdown) {
            orphanHeap(task);
        } else {
            orphanHeapLater(task);
        }
    }

    /**
     * Gives an abandoned task's heap to other tasks once its thread has had another file timeout to return. Given up on
     * while it decodes a large image or reads a slow share, a thread returns once the decode is done or the interrupt
     * ends the read, and until then it may well be using all of that heap. Handed back at once, one such file could
     * hold more than half the budget and open the breaker by itself, stopping every scan although storage is fine.
     */
    private void orphanHeapLater(FileTask<?> task) {
        try {
            task.setDeadline(watchdog.schedule(() -> orphanHeap(task), timeoutNanos, TimeUnit.NANOSECONDS));
        } catch (RejectedExecutionException e) {
            // The watchdog has shut down with the executor, so no task starts that could use the heap
            orphanHeap(task);
        }
    }

    /** Gives back the heap an abandoned task still holds, counted as orphaned until its thread returns, if ever. */
    private void orphanHeap(FileTask<?> task) {
        // Under the lock returnedAfterAbandon takes, so it takes back exactly what was counted
        synchronized (task) {
            orphanedHeapBytes.addAndGet(task.context().revokeHeap());
        }
    }

    /**
     * Interrupts an abandoned task's thread from a thread of its own. Interrupting a thread blocked in a FileChannel
     * read (PDFBox, zip and 7z archives, audio tags) closes the channel, and the close waits for the read to end, which
     * a read wedged on a share never does. Neither the watchdog nor the shutdown thread may wait with it, so a new
     * thread does, blocked exactly as long as the read.
     */
    private void interruptElsewhere(FileTask<?> task) {
        try {
            interrupters.newThread(task::interrupt).start();
        } catch (RuntimeException | Error e) {
            // No thread could be created; the task is given up on all the same
            log.warn("Could not interrupt abandoned import task {}: {}", task.label(), e.toString());
        }
    }

    private void skipQueued() {
        FileTask<?> task;
        while ((task = queue.poll()) != null) {
            task.skip(SkipReason.SHUTDOWN);
        }
    }
}
