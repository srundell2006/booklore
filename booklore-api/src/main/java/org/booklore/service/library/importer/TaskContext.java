package org.booklore.service.library.importer;

import lombok.extern.slf4j.Slf4j;
import org.booklore.service.fileprocessor.prepare.HeapReservation;
import org.booklore.service.fileprocessor.prepare.IoCallable;
import org.booklore.service.fileprocessor.prepare.PrepareAbandonedException;
import org.booklore.service.fileprocessor.prepare.PrepareContext;
import org.booklore.service.fileprocessor.prepare.PrepareDeadlineException;
import org.booklore.util.ProcessWatchdog;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The {@link PrepareContext} of one import task. Its checkpoints stop the work once the task is abandoned, and it
 * hands out the shared render permits and heap, waiting for them no longer than the task's deadline.
 *
 * <p>Everything the task holds is released exactly once: by the task when it is done with it, or when the task is
 * abandoned, by {@link #revoke()} for its render permit, which goes to the next task at once, and by
 * {@link #revokeHeap()} for its heap, which the executor calls only if the task's thread does not return soon. Each
 * render permit and reservation is released through its own compare-and-set, so the two can race safely.
 */
@Slf4j
final class TaskContext implements PrepareContext {

    private final FileTask<?> task;
    private final Semaphore renderPermits;
    private final HeapBudget heap;
    private final AtomicBoolean renderHeld = new AtomicBoolean();
    // Guarded by this: abandonment revokes them from the watchdog thread
    private final List<HeapBudget.Reservation> reservations = new ArrayList<>();
    private final List<Process> processes = new ArrayList<>();
    private boolean revoked;
    private long orphanedBytes;
    /**
     * Whether the task is waiting for a render permit or heap, or ran out of time waiting and is failing with
     * PrepareDeadlineException. Either way it ends by itself at its deadline, without leaving a stuck thread.
     */
    private volatile boolean awaitingCapacity;
    private volatile long deadlineNanos;

    TaskContext(FileTask<?> task, Semaphore renderPermits, HeapBudget heap) {
        this.task = task;
        this.renderPermits = renderPermits;
        this.heap = heap;
    }

    @Override
    public void checkpoint() {
        if (!task.isRunning() || Thread.currentThread().isInterrupted()) {
            throw new PrepareAbandonedException("Gave up on " + task.label());
        }
    }

    @Override
    public <T> T render(long estimatedBytes, IoCallable<T> work) throws Exception {
        requireNothingHeld();
        awaitingCapacity = true;
        boolean acquired;
        try {
            acquired = renderPermits.tryAcquire(remainingNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            throw abandonedByInterrupt();
        }
        if (!acquired) {
            // Still awaiting capacity as far as the watchdog is concerned, so it leaves the task to fail by itself
            throw new PrepareDeadlineException("No cover-rendering capacity for " + task.label() + " before its deadline");
        }
        awaitingCapacity = false;
        renderHeld.set(true);
        try {
            checkpoint();
            try (HeapReservation ignored = reserve(estimatedBytes)) {
                return work.call();
            }
        } finally {
            if (renderHeld.compareAndSet(true, false)) {
                renderPermits.release();
            }
        }
    }

    @Override
    public HeapReservation reserveHeap(long bytes) {
        requireNothingHeld();
        checkpoint();
        return reserve(bytes);
    }

    @Override
    public void registerProcess(Process process) {
        boolean kill;
        synchronized (this) {
            kill = revoked;
            if (!kill) {
                processes.add(process);
            }
        }
        if (kill) {
            ProcessWatchdog.kill(process);
        }
    }

    void setDeadline(long deadlineNanos) {
        this.deadlineNanos = deadlineNanos;
    }

    boolean awaitingCapacity() {
        return awaitingCapacity;
    }

    /**
     * Takes back the render permit of an abandoned task for other tasks to use, and kills any process it registers from
     * now on. Its heap stays reserved for it until {@link #revokeHeap()}.
     */
    synchronized void revoke() {
        revoked = true;
        if (renderHeld.compareAndSet(true, false)) {
            renderPermits.release();
        }
    }

    /**
     * Takes back the heap an abandoned task still holds for other tasks to use. Its thread may still be using that heap,
     * so the bytes count as orphaned until it returns.
     *
     * @return the heap taken back, in bytes
     */
    synchronized long revokeHeap() {
        long bytes = 0;
        for (HeapBudget.Reservation reservation : reservations) {
            if (reservation.release()) {
                bytes += reservation.bytes();
            }
        }
        reservations.clear();
        orphanedBytes += bytes;
        return bytes;
    }

    /** The heap {@link #revokeHeap()} took back while the task's thread was still running. */
    synchronized long orphanedBytes() {
        return orphanedBytes;
    }

    /** Releases what the task still holds once its thread has returned. */
    synchronized void releaseAll() {
        if (renderHeld.compareAndSet(true, false)) {
            renderPermits.release();
        }
        reservations.forEach(HeapBudget.Reservation::release);
        reservations.clear();
        processes.clear();
    }

    /** Kills the processes the task registered, and everything they started. */
    void destroyProcesses() {
        List<Process> registered;
        synchronized (this) {
            registered = List.copyOf(processes);
            processes.clear();
        }
        for (Process process : registered) {
            try {
                ProcessWatchdog.kill(process);
            } catch (RuntimeException e) {
                log.warn("Could not kill process {} of abandoned import task {}: {}", process.pid(), task.label(), e.getMessage());
            }
        }
    }

    private HeapReservation reserve(long bytes) {
        awaitingCapacity = true;
        HeapBudget.Reservation reservation;
        try {
            reservation = heap.tryReserve(bytes, remainingNanos());
        } catch (InterruptedException e) {
            throw abandonedByInterrupt();
        }
        if (reservation == null) {
            throw new PrepareDeadlineException("No heap for " + task.label() + " before its deadline");
        }
        awaitingCapacity = false;
        synchronized (this) {
            if (revoked) {
                reservation.release();
                throw new PrepareAbandonedException("Gave up on " + task.label());
            }
            reservations.add(reservation);
        }
        return () -> release(reservation);
    }

    /**
     * The task's thread is interrupted when the task is given up on, which can come while it waits for capacity. It
     * ends the preparation as a checkpoint would: as a checked InterruptedException, the processors would take it for a
     * cover that failed, log it as an error and carry on reading the file. The interrupt is kept for what comes after.
     */
    private PrepareAbandonedException abandonedByInterrupt() {
        Thread.currentThread().interrupt();
        return new PrepareAbandonedException("Gave up on " + task.label());
    }

    private synchronized void release(HeapBudget.Reservation reservation) {
        reservations.remove(reservation);
        reservation.release();
    }

    /**
     * A task holds at most one render permit with its heap, or one heap reservation, at a time. With the IO slot
     * always taken first, nobody waits for capacity while holding capacity someone else waits for, so the waits
     * cannot deadlock.
     */
    private void requireNothingHeld() {
        boolean holding;
        synchronized (this) {
            holding = renderHeld.get() || !reservations.isEmpty();
        }
        if (holding) {
            throw new IllegalStateException("A render or heap reservation cannot be nested in another, in " + task.label());
        }
    }

    private long remainingNanos() {
        return Math.max(0, deadlineNanos - System.nanoTime());
    }
}
