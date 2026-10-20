package org.booklore.service.library.importer;

import org.booklore.service.fileprocessor.prepare.HeapReservation;
import org.booklore.service.fileprocessor.prepare.PrepareAbandonedException;
import org.booklore.service.fileprocessor.prepare.PrepareContext;
import org.booklore.service.fileprocessor.prepare.PrepareDeadlineException;
import org.booklore.service.library.importer.FileTask.State;
import org.booklore.service.library.importer.Outcome.SkipReason;
import org.booklore.util.ProcessWatchdog;
import org.booklore.util.TestProcesses;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImportExecutorTest {

    private static final long MIB = 1024 * 1024;

    @TempDir
    Path tempDir;

    private final List<ImportExecutor> executors = new ArrayList<>();
    /** Releases every task wedged by {@link #wedge()}; opened after each test at the latest. */
    private final CountDownLatch release = new CountDownLatch(1);

    @AfterEach
    void releaseWedgedTasks() {
        release.countDown();
        executors.forEach(ImportExecutor::shutdown);
    }

    private ImportExecutor executor(int io, Duration timeout, int maxStuck, int render, long heapBytes) {
        ImportExecutor executor = new ImportExecutor(new ImportSettings(true, io, 4 * io, timeout, maxStuck, render, heapBytes));
        executors.add(executor);
        return executor;
    }

    private ImportExecutor executor(int io, Duration timeout) {
        return executor(io, timeout, 16, 2, 64 * MIB);
    }

    private static <T> FileTask<T> submit(ImportExecutor executor, String label, PrepareWork<T> work) {
        return executor.submit(label, work, () -> false);
    }

    /** Blocks the way a thread stuck in a CIFS read does: interrupts do not end it, only {@link #release} does. */
    private String wedge() {
        boolean interrupted = false;
        while (true) {
            try {
                release.await();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        return "late";
    }

    private static <T> Outcome<T> outcome(FileTask<T> task) throws Exception {
        return task.result().get(10, TimeUnit.SECONDS);
    }

    private static <T> T valueOf(FileTask<T> task) throws Exception {
        Outcome<T> outcome = outcome(task);
        if (outcome instanceof Outcome.Done<T> done) {
            return done.value();
        }
        throw new AssertionError("Expected " + task.label() + " to be done but it ended " + outcome,
                outcome instanceof Outcome.Failed<T> failed ? failed.cause() : null);
    }

    private static void awaitUntil(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime()).as(what).isLessThan(deadline);
            Thread.sleep(5);
        }
    }

    @Test
    void runsAtMostIoParallelismTasksAtOnce() throws Exception {
        ImportExecutor executor = executor(4, Duration.ofSeconds(30));
        AtomicInteger running = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        CountDownLatch firstFour = new CountDownLatch(4);
        List<FileTask<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            int n = i;
            tasks.add(submit(executor, "task " + n, ctx -> {
                peak.accumulateAndGet(running.incrementAndGet(), Math::max);
                try {
                    if (n < 4) {
                        // The first four wait for each other, so the peak reaches the limit
                        firstFour.countDown();
                        firstFour.await(5, TimeUnit.SECONDS);
                    }
                    Thread.sleep(1);
                    return n;
                } finally {
                    running.decrementAndGet();
                }
            }));
        }

        for (int i = 0; i < tasks.size(); i++) {
            assertThat(valueOf(tasks.get(i))).isEqualTo(i);
        }
        assertThat(peak).hasValue(4);
        assertThat(executor.availableIoPermits()).isEqualTo(4);
    }

    @Test
    void deadlineStartsWhenTheTaskGetsItsSlot() throws Exception {
        ImportExecutor executor = executor(1, Duration.ofMillis(400));
        long started = System.nanoTime();
        List<FileTask<String>> tasks = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            tasks.add(submit(executor, "task " + i, ctx -> {
                Thread.sleep(100);
                return "done";
            }));
        }

        for (FileTask<String> task : tasks) {
            assertThat(valueOf(task)).isEqualTo("done");
        }
        // The last ones queued for far longer than the timeout, and still none timed out
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isGreaterThanOrEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void abandonsATaskPastItsDeadlineAndGivesItsSlotToTheNext() throws Exception {
        Duration timeout = Duration.ofMillis(300);
        ImportExecutor executor = executor(1, timeout);
        FileTask<String> wedged = submit(executor, "wedged", ctx -> wedge());

        Outcome<String> outcome = outcome(wedged);
        assertThat(outcome).isInstanceOfSatisfying(Outcome.TimedOut.class, timedOut ->
                assertThat(Duration.ofNanos(timedOut.elapsedNanos())).isBetween(timeout, timeout.plusMillis(250)));
        assertThat(wedged.state()).isEqualTo(State.ABANDONED);
        assertThat(executor.stuckThreads()).isEqualTo(1);
        assertThat(executor.availableIoPermits()).isEqualTo(1);
        // The only slot went to the next task while the wedged thread is still stuck
        FileTask<String> next = submit(executor, "next", ctx -> "ran");
        assertThat(valueOf(next)).isEqualTo("ran");

        release.countDown();
        awaitUntil("the stuck thread to return", () -> executor.stuckThreads() == 0);
        assertThat(executor.availableIoPermits()).isEqualTo(1);
        assertThat(executor.breakerOpen()).isFalse();
        // What it returned late is dropped
        assertThat(outcome(wedged)).isSameAs(outcome);
    }

    @Test
    void aTaskThatStopsAtCheckpointsReturnsAsSoonAsItIsAbandoned() throws Exception {
        ImportExecutor executor = executor(1, Duration.ofMillis(200));
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        FileTask<Object> task = submit(executor, "cooperative", ctx -> {
            try {
                while (true) {
                    ctx.checkpoint();
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
                }
            } catch (RuntimeException e) {
                thrown.set(e);
                throw e;
            }
        });

        assertThat(outcome(task)).isInstanceOf(Outcome.TimedOut.class);
        awaitUntil("the abandoned thread to return", () -> executor.stuckThreads() == 0);
        assertThat(thrown.get()).isInstanceOf(PrepareAbandonedException.class);
        assertThat(executor.availableIoPermits()).isEqualTo(1);
    }

    @Test
    void aTaskThatThrowsFailsWithItsException() throws Exception {
        ImportExecutor executor = executor(1, Duration.ofSeconds(30));
        IllegalStateException failure = new IllegalStateException("corrupt archive");

        Outcome<Object> outcome = outcome(submit(executor, "broken", ctx -> {
            throw failure;
        }));

        assertThat(outcome).isEqualTo(new Outcome.Failed<>("broken", failure));
        assertThat(executor.stuckThreads()).isZero();
        assertThat(executor.availableIoPermits()).isEqualTo(1);
    }

    @Test
    void breakerSkipsTasksWhileTooManyThreadsAreStuck() throws Exception {
        ImportExecutor executor = executor(3, Duration.ofMillis(300), 2, 2, 64 * MIB);
        List<FileTask<String>> wedged = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            wedged.add(submit(executor, "wedged " + i, ctx -> wedge()));
        }
        List<FileTask<String>> healthy = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            healthy.add(submit(executor, "healthy " + i, ctx -> {
                Thread.sleep(50);
                return "ok";
            }));
        }

        for (FileTask<String> task : wedged) {
            assertThat(outcome(task)).isInstanceOf(Outcome.TimedOut.class);
        }
        int skipped = 0;
        for (FileTask<String> task : healthy) {
            Outcome<String> outcome = outcome(task);
            if (outcome instanceof Outcome.Skipped<String> skip) {
                assertThat(skip.reason()).isEqualTo(SkipReason.STORAGE_STUCK);
                skipped++;
            } else {
                // Started after the first abandonment, before the second opened the breaker
                assertThat(outcome).isInstanceOfSatisfying(Outcome.Done.class, done -> assertThat(done.value()).isEqualTo("ok"));
            }
        }
        assertThat(skipped).isPositive();
        assertThat(executor.breakerOpen()).isTrue();
        assertThat(executor.stuckThreads()).isEqualTo(3);

        // Later submissions are skipped too, until the stuck threads return
        assertThat(outcome(submit(executor, "later", ctx -> "ran"))).isEqualTo(new Outcome.Skipped<>("later", SkipReason.STORAGE_STUCK));
        release.countDown();
        awaitUntil("the stuck threads to return", () -> executor.stuckThreads() == 0);
        assertThat(executor.breakerOpen()).isFalse();
        FileTask<String> after = submit(executor, "after", ctx -> "ran");
        assertThat(valueOf(after)).isEqualTo("ran");
        assertThat(executor.availableIoPermits()).isEqualTo(3);
    }

    @Test
    void stuckThreadsNeverExceedMaxStuckPlusIoMinusOne() throws Exception {
        int io = 3;
        int maxStuck = 2;
        ImportExecutor executor = executor(io, Duration.ofMillis(200), maxStuck, 2, 64 * MIB);
        List<FileTask<String>> tasks = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            tasks.add(submit(executor, "wedged " + i, ctx -> wedge()));
        }

        int timedOut = 0;
        for (FileTask<String> task : tasks) {
            Outcome<String> outcome = outcome(task);
            if (outcome instanceof Outcome.TimedOut) {
                timedOut++;
            } else {
                assertThat(outcome).isEqualTo(new Outcome.Skipped<>(task.label(), SkipReason.STORAGE_STUCK));
            }
        }
        // The first io all started; at most io - 1 more started before the breaker opened
        assertThat(timedOut).isBetween(io, maxStuck + io - 1);
        assertThat(executor.stuckThreads()).isEqualTo(timedOut);
    }

    @Test
    void abandoningARenderGivesItsPermitToTheNextRender() throws Exception {
        Duration timeout = Duration.ofMillis(300);
        ImportExecutor executor = executor(1, timeout, 16, 1, 64 * MIB);
        FileTask<String> wedged = submit(executor, "wedged render", ctx -> ctx.render(MIB, this::wedge));

        assertThat(outcome(wedged)).isInstanceOf(Outcome.TimedOut.class);
        assertThat(executor.availableRenderPermits()).isEqualTo(1);
        Outcome<String> next = outcome(submit(executor, "next render", ctx -> ctx.render(MIB, () -> "rendered")));
        // It rendered without waiting out its own deadline for the wedged task's permit
        assertThat(next).isInstanceOfSatisfying(Outcome.Done.class, done -> {
            assertThat(done.value()).isEqualTo("rendered");
            assertThat(Duration.ofNanos(done.elapsedNanos())).isLessThan(timeout);
        });

        release.countDown();
        awaitUntil("the stuck thread to return", () -> executor.stuckThreads() == 0);
        assertThat(executor.availableRenderPermits()).isEqualTo(1);
        assertThat(executor.availableHeapBytes()).isEqualTo(64 * MIB);
        assertThat(executor.orphanedHeapBytes()).isZero();
    }

    @Test
    void waitingForARenderPermitPastTheDeadlineFailsWithoutAStuckThread() throws Exception {
        ImportExecutor executor = executor(2, Duration.ofMillis(500), 16, 1, 64 * MIB);
        CountDownLatch waiterStarted = new CountDownLatch(1);
        CountDownLatch permitHeld = new CountDownLatch(1);
        FileTask<String> waiter = submit(executor, "waiter", ctx -> {
            waiterStarted.countDown();
            permitHeld.await();
            return ctx.render(MIB, () -> "rendered");
        });
        assertThat(waiterStarted.await(5, TimeUnit.SECONDS)).isTrue();
        // Started later, the holder's own deadline comes after the waiter's
        Thread.sleep(100);
        FileTask<Outcome<String>> holder = submit(executor, "holder", ctx -> ctx.render(MIB, () -> {
            permitHeld.countDown();
            return waiter.result().get(5, TimeUnit.SECONDS);
        }));

        assertThat(outcome(waiter)).isInstanceOfSatisfying(Outcome.Failed.class, failed ->
                assertThat(failed.cause()).isInstanceOf(PrepareDeadlineException.class));
        assertThat(waiter.state()).isEqualTo(State.FAILED);
        assertThat(executor.stuckThreads()).isZero();
        assertThat(valueOf(holder)).isInstanceOf(Outcome.Failed.class);
        assertThat(executor.availableRenderPermits()).isEqualTo(1);
        assertThat(executor.availableIoPermits()).isEqualTo(2);
        assertThat(executor.availableHeapBytes()).isEqualTo(64 * MIB);
    }

    @Test
    void reservationsOfAnySizeAllRunWithinTheHeapBudget() throws Exception {
        ImportExecutor executor = executor(8, Duration.ofSeconds(60), 16, 2, 64 * MIB);
        Random random = new Random(42);
        List<FileTask<Long>> tasks = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            long bytes = random.nextLong(96 * MIB + 1);
            boolean render = i % 2 == 0;
            tasks.add(submit(executor, "reserve " + bytes, ctx -> {
                if (render) {
                    return ctx.render(bytes, () -> {
                        LockSupport.parkNanos(200_000);
                        return bytes;
                    });
                }
                try (HeapReservation ignored = ctx.reserveHeap(bytes)) {
                    LockSupport.parkNanos(200_000);
                    return bytes;
                }
            }));
        }

        for (FileTask<Long> task : tasks) {
            valueOf(task);
        }
        assertThat(executor.availableHeapBytes()).isEqualTo(64 * MIB);
        assertThat(executor.availableRenderPermits()).isEqualTo(2);
    }

    @Test
    void rendersAndHeapReservationsCannotBeNested() throws Exception {
        ImportExecutor executor = executor(1, Duration.ofSeconds(30));
        FileTask<String> task = submit(executor, "nested", ctx -> {
            ctx.render(MIB, () -> {
                assertThatThrownBy(() -> ctx.reserveHeap(MIB)).isInstanceOf(IllegalStateException.class);
                assertThatThrownBy(() -> ctx.render(MIB, () -> null)).isInstanceOf(IllegalStateException.class);
                return null;
            });
            try (HeapReservation ignored = ctx.reserveHeap(MIB)) {
                assertThatThrownBy(() -> ctx.reserveHeap(MIB)).isInstanceOf(IllegalStateException.class);
                assertThatThrownBy(() -> ctx.render(MIB, () -> null)).isInstanceOf(IllegalStateException.class);
            }
            // Once released, the next one is fine
            return ctx.render(MIB, () -> "rendered");
        });

        assertThat(valueOf(task)).isEqualTo("rendered");
        assertThat(executor.availableHeapBytes()).isEqualTo(64 * MIB);
        assertThat(executor.availableRenderPermits()).isEqualTo(2);
    }

    @Test
    void heapOfAnAbandonedTaskCountsAsOrphanedUntilItsThreadReturns() throws Exception {
        ImportExecutor executor = executor(1, Duration.ofMillis(300), 16, 2, 64 * MIB);
        FileTask<String> task = submit(executor, "parse", ctx -> {
            try (HeapReservation ignored = ctx.reserveHeap(40 * MIB)) {
                return wedge();
            }
        });

        assertThat(outcome(task)).isInstanceOf(Outcome.TimedOut.class);
        assertThat(executor.orphanedHeapBytes()).isEqualTo(40 * MIB);
        // One stuck thread is far from the limit of 16; the orphaned heap alone opens the breaker
        assertThat(executor.breakerOpen()).isTrue();
        // The budget is whole again for other tasks
        assertThat(executor.availableHeapBytes()).isEqualTo(64 * MIB);

        release.countDown();
        awaitUntil("the stuck thread to return", () -> executor.stuckThreads() == 0);
        assertThat(executor.orphanedHeapBytes()).isZero();
        assertThat(executor.breakerOpen()).isFalse();
        assertThat(executor.availableHeapBytes()).isEqualTo(64 * MIB);
    }

    @Test
    void skipBeforeStartSkipsATaskWithoutWaitingForASlot() throws Exception {
        ImportExecutor executor = executor(1, Duration.ofSeconds(30));
        CountDownLatch holding = new CountDownLatch(1);
        FileTask<String> holder = submit(executor, "holder", ctx -> {
            holding.countDown();
            return wedge();
        });
        assertThat(holding.await(5, TimeUnit.SECONDS)).isTrue();
        AtomicBoolean ran = new AtomicBoolean();

        FileTask<Object> skipped = executor.submit("skipped", ctx -> ran.getAndSet(true), () -> true);

        // Skipped while the only slot is still taken
        assertThat(outcome(skipped)).isEqualTo(new Outcome.Skipped<>("skipped", SkipReason.GROUP_FAILED));
        assertThat(holder.state()).isEqualTo(State.RUNNING);
        release.countDown();
        assertThat(valueOf(holder)).isEqualTo("late");
        assertThat(ran).isFalse();
        assertThat(executor.availableIoPermits()).isEqualTo(1);
    }

    @Test
    void aTaskWhoseGroupFailsWhileItWaitsForASlotIsSkipped() throws Exception {
        ImportExecutor executor = executor(1, Duration.ofSeconds(30));
        CountDownLatch holding = new CountDownLatch(1);
        FileTask<String> holder = submit(executor, "holder", ctx -> {
            holding.countDown();
            return wedge();
        });
        assertThat(holding.await(5, TimeUnit.SECONDS)).isTrue();
        AtomicBoolean doomed = new AtomicBoolean();
        AtomicBoolean ran = new AtomicBoolean();
        FileTask<Object> waiting = executor.submit("attachment", ctx -> ran.getAndSet(true), doomed::get);
        // Let the dispatcher take it and wait for the slot
        Thread.sleep(100);

        doomed.set(true);
        release.countDown();

        assertThat(valueOf(holder)).isEqualTo("late");
        assertThat(outcome(waiting)).isEqualTo(new Outcome.Skipped<>("attachment", SkipReason.GROUP_FAILED));
        assertThat(ran).isFalse();
        assertThat(executor.availableIoPermits()).isEqualTo(1);
    }

    @Test
    void cancelSkipsAQueuedTaskButLetsARunningOneFinish() throws Exception {
        ImportExecutor executor = executor(1, Duration.ofSeconds(30));
        CountDownLatch holding = new CountDownLatch(1);
        FileTask<String> running = submit(executor, "running", ctx -> {
            holding.countDown();
            return wedge();
        });
        assertThat(holding.await(5, TimeUnit.SECONDS)).isTrue();
        AtomicBoolean ran = new AtomicBoolean();
        FileTask<Object> queued = submit(executor, "queued", ctx -> ran.getAndSet(true));

        queued.cancel();
        running.cancel();

        assertThat(outcome(queued)).isEqualTo(new Outcome.Skipped<>("queued", SkipReason.CANCELLED));
        assertThat(running.state()).isEqualTo(State.RUNNING);
        release.countDown();
        assertThat(valueOf(running)).isEqualTo("late");
        assertThat(ran).isFalse();
        assertThat(executor.availableIoPermits()).isEqualTo(1);
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void abandoningATaskKillsTheProcessesItStarted() throws Exception {
        ImportExecutor executor = executor(1, Duration.ofMillis(300));
        Path pids = tempDir.resolve("pids");
        FileTask<byte[]> task = submit(executor, "unrar", ctx -> {
            // The child sleep keeps the output open, so the read below ends only once both are killed
            Process process = new ProcessBuilder("sh", "-c", "echo $$ > '" + pids + "'; sleep 60 & echo $! >> '" + pids + "'; wait")
                    .redirectErrorStream(true)
                    .start();
            // Its own timeout is far off: only the abandonment kills it
            try (ProcessWatchdog.WatchedProcess ignored = ProcessWatchdog.watch(process, Duration.ofMinutes(5));
                 InputStream out = process.getInputStream()) {
                return out.readAllBytes();
            }
        });

        assertThat(outcome(task)).isInstanceOf(Outcome.TimedOut.class);
        long abandoned = System.nanoTime();
        TestProcesses.assertAllStopped(pids, 2);
        assertThat(Duration.ofNanos(System.nanoTime() - abandoned)).isLessThan(Duration.ofSeconds(1));
        awaitUntil("the reading thread to return", () -> executor.stuckThreads() == 0);
    }

    @Test
    void shutdownReturnsAtOnceAndEndsEveryTask() throws Exception {
        ImportExecutor executor = executor(2, Duration.ofMinutes(1));
        CountDownLatch running = new CountDownLatch(2);
        List<FileTask<String>> tasks = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            tasks.add(submit(executor, "wedged " + i, ctx -> {
                running.countDown();
                return wedge();
            }));
        }
        for (int i = 0; i < 3; i++) {
            tasks.add(submit(executor, "queued " + i, ctx -> "ran"));
        }
        assertThat(running.await(5, TimeUnit.SECONDS)).isTrue();

        long started = System.nanoTime();
        executor.shutdown();

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
        for (FileTask<String> task : tasks) {
            assertThat(outcome(task)).isEqualTo(new Outcome.Skipped<>(task.label(), SkipReason.SHUTDOWN));
        }
        assertThat(executor.isShuttingDown()).isTrue();
        assertThatThrownBy(() -> submit(executor, "late", ctx -> "ran")).isInstanceOf(RejectedExecutionException.class);
    }

    @Test
    void eachTaskRunsOnANewPlatformDaemonThreadWithItsContextBound() throws Exception {
        record Seen(Thread thread, boolean contextBound, boolean transactionActive) {
        }
        ImportExecutor executor = executor(1, Duration.ofSeconds(30));
        PrepareWork<Seen> look = ctx -> new Seen(Thread.currentThread(),
                PrepareContext.CURRENT.isBound() && PrepareContext.CURRENT.get() == ctx,
                TransactionSynchronizationManager.isActualTransactionActive());

        Seen first = valueOf(submit(executor, "first", look));
        Seen second = valueOf(submit(executor, "second", look));

        for (Seen seen : List.of(first, second)) {
            assertThat(seen.thread().isVirtual()).isFalse();
            assertThat(seen.thread().isDaemon()).isTrue();
            assertThat(seen.thread().getName()).startsWith("book-import-io-");
            assertThat(seen.contextBound()).isTrue();
            assertThat(seen.transactionActive()).isFalse();
        }
        assertThat(first.thread()).isNotSameAs(second.thread());
    }
}
