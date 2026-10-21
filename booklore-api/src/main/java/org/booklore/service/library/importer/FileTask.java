package org.booklore.service.library.importer;

import org.booklore.service.library.importer.Outcome.SkipReason;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * One file's work submitted to the {@link ImportExecutor}. It moves through its states once: QUEUED, then RUNNING
 * when it gets its IO slot, or CANCELLED when it is skipped before that; a running task ends DONE or FAILED when its
 * work returns, or ABANDONED when its deadline passes first. Whoever makes the move that ends it completes
 * {@link #result()}, so the result is completed exactly once.
 */
final class FileTask<T> {

    enum State { QUEUED, RUNNING, DONE, FAILED, CANCELLED, ABANDONED }

    private final String label;
    private final PrepareWork<T> work;
    private final BooleanSupplier skipBeforeStart;
    private final TaskContext context;
    private final AtomicReference<State> state = new AtomicReference<>(State.QUEUED);
    private final CompletableFuture<Outcome<T>> result = new CompletableFuture<>();
    private volatile Thread thread;
    private volatile long startedNanos;
    private volatile ScheduledFuture<?> deadline;

    FileTask(String label, PrepareWork<T> work, BooleanSupplier skipBeforeStart, Semaphore renderPermits, HeapBudget heap) {
        this.label = label;
        this.work = work;
        this.skipBeforeStart = skipBeforeStart;
        this.context = new TaskContext(this, renderPermits, heap);
    }

    /** Completes once the task has ended; never with the late result of an abandoned task. */
    CompletableFuture<Outcome<T>> result() {
        return result;
    }

    /** Skips the task if it has not started yet. A running task carries on, and its deadline still applies. */
    void cancel() {
        skip(SkipReason.CANCELLED);
    }

    State state() {
        return state.get();
    }

    String label() {
        return label;
    }

    PrepareWork<T> work() {
        return work;
    }

    BooleanSupplier skipBeforeStart() {
        return skipBeforeStart;
    }

    TaskContext context() {
        return context;
    }

    boolean isRunning() {
        return state.get() == State.RUNNING;
    }

    boolean transition(State from, State to) {
        return state.compareAndSet(from, to);
    }

    /** Ends a task that has not started with {@link Outcome.Skipped}. */
    void skip(SkipReason reason) {
        if (transition(State.QUEUED, State.CANCELLED)) {
            result.complete(new Outcome.Skipped<>(label, reason));
        }
    }

    /** Ends a task that could not be started with {@link Outcome.Failed}. */
    void failToStart(Throwable cause) {
        if (transition(State.QUEUED, State.FAILED)) {
            result.complete(new Outcome.Failed<>(label, cause));
        }
    }

    void started(Thread thread, long startedNanos, long deadlineNanos) {
        this.thread = thread;
        this.startedNanos = startedNanos;
        context.setDeadline(deadlineNanos);
    }

    long elapsedNanos() {
        return System.nanoTime() - startedNanos;
    }

    void setDeadline(ScheduledFuture<?> deadline) {
        this.deadline = deadline;
    }

    void cancelDeadline() {
        ScheduledFuture<?> scheduled = deadline;
        if (scheduled != null) {
            scheduled.cancel(false);
        }
    }

    /** Interrupts the task's thread, which runs this task only, so a late interrupt cannot reach another task. */
    void interrupt() {
        Thread running = thread;
        if (running != null) {
            running.interrupt();
        }
    }

    void complete(Outcome<T> outcome) {
        result.complete(outcome);
    }
}
