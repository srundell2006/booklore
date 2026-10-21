package org.booklore.service.library.importer;

import org.booklore.service.fileprocessor.prepare.HeapReservation;
import org.booklore.service.fileprocessor.prepare.IoCallable;
import org.booklore.service.fileprocessor.prepare.PrepareAbandonedException;
import org.booklore.service.library.importer.FileTask.State;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A wait for a render permit or heap that is interrupted, as shutdown interrupts every task, ends the preparation:
 * the processors carry on without a cover on any other exception, which would read on from the share for nothing.
 */
class TaskContextTest {

    private static final long MIB = 1024 * 1024;

    private final Semaphore renderPermits = new Semaphore(1, true);
    private final HeapBudget heap = new HeapBudget(MIB);
    private final TaskContext ctx = runningContext();

    private TaskContext runningContext() {
        FileTask<Object> task = new FileTask<>("book.epub", c -> null, () -> false, renderPermits, heap);
        task.transition(State.QUEUED, State.RUNNING);
        long now = System.nanoTime();
        task.started(Thread.currentThread(), now, now + TimeUnit.SECONDS.toNanos(30));
        return task.context();
    }

    @Test
    void interruptedWaitForARenderPermit() throws Exception {
        renderPermits.acquire();

        assertStopsAndKeepsTheInterrupt(() -> ctx.render(MIB, () -> "rendered"));
        renderPermits.release();
        assertThat(renderPermits.availablePermits()).isEqualTo(1);
    }

    @Test
    void interruptedWaitForTheHeapOfARender() throws Exception {
        HeapBudget.Reservation all = heap.tryReserve(MIB, 0);

        assertStopsAndKeepsTheInterrupt(() -> ctx.render(MIB, () -> "rendered"));
        // The render permit it took is given back
        assertThat(renderPermits.availablePermits()).isEqualTo(1);
        all.release();
        assertThat(heap.availableBytes()).isEqualTo(MIB);
    }

    @Test
    void interruptedWaitForHeapToParse() throws Exception {
        HeapBudget.Reservation all = heap.tryReserve(MIB, 0);

        assertStopsAndKeepsTheInterrupt(() -> {
            try (HeapReservation ignored = ctx.reserveHeap(MIB)) {
                return "parsed";
            }
        });
        all.release();
        assertThat(heap.availableBytes()).isEqualTo(MIB);
    }

    private static void assertStopsAndKeepsTheInterrupt(IoCallable<String> step) throws InterruptedException {
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        AtomicBoolean interruptKept = new AtomicBoolean();
        Thread waiter = Thread.ofPlatform().daemon().start(() -> {
            try {
                step.call();
            } catch (Throwable e) {
                thrown.set(e);
            }
            interruptKept.set(Thread.currentThread().isInterrupted());
        });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (waiter.getState() != Thread.State.TIMED_WAITING) {
            assertThat(System.nanoTime()).as("the step to wait for capacity").isLessThan(deadline);
            Thread.sleep(5);
        }

        waiter.interrupt();
        waiter.join(5000);

        assertThat(waiter.isAlive()).isFalse();
        assertThat(thrown.get()).isInstanceOf(PrepareAbandonedException.class);
        assertThat(interruptKept).isTrue();
    }
}
