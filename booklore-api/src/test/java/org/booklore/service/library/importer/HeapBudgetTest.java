package org.booklore.service.library.importer;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;

class HeapBudgetTest {

    private static final long MIB = 1024 * 1024;

    private final HeapBudget heap = new HeapBudget(64 * MIB);

    @Test
    void requestsAreRoundedUpToWholeKib() throws Exception {
        assertThat(heap.tryReserve(0, 0).bytes()).isEqualTo(1024);
        assertThat(heap.tryReserve(-5, 0).bytes()).isEqualTo(1024);
        assertThat(heap.tryReserve(1025, 0).bytes()).isEqualTo(2048);
        assertThat(heap.availableBytes()).isEqualTo(64 * MIB - 4 * 1024);
    }

    @Test
    void requestLargerThanTheBudgetRunsAloneInsteadOfWaitingForEver() throws Exception {
        HeapBudget.Reservation small = heap.tryReserve(1024, 0);
        // Cut to the whole budget, it has to wait while anything else is reserved
        assertThat(heap.tryReserve(10_000 * MIB, TimeUnit.MILLISECONDS.toNanos(50))).isNull();
        small.release();

        HeapBudget.Reservation large = heap.tryReserve(10_000 * MIB, 0);

        assertThat(large.bytes()).isEqualTo(64 * MIB);
        assertThat(heap.availableBytes()).isZero();
        large.release();
        assertThat(heap.availableBytes()).isEqualTo(64 * MIB);
    }

    @Test
    void reservationIsReleasedOnce() throws Exception {
        HeapBudget.Reservation reservation = heap.tryReserve(10 * MIB, 0);

        assertThat(reservation.release()).isTrue();
        assertThat(reservation.release()).isFalse();
        assertThat(heap.availableBytes()).isEqualTo(64 * MIB);
    }

    @Test
    void waitingRequestIsNotOvertakenBySmallerOnes() throws Exception {
        HeapBudget.Reservation held = heap.tryReserve(40 * MIB, 0);
        CompletableFuture<HeapBudget.Reservation> large = new CompletableFuture<>();
        Thread.ofPlatform().daemon(true).start(() -> {
            try {
                large.complete(heap.tryReserve(64 * MIB, TimeUnit.SECONDS.toNanos(5)));
            } catch (InterruptedException e) {
                large.completeExceptionally(e);
            }
        });
        // Time for the large request to start waiting
        Thread.sleep(100);

        // 24 MiB are free, but the large request was first in line
        assertThat(heap.tryReserve(MIB, TimeUnit.MILLISECONDS.toNanos(50))).isNull();
        held.release();
        assertThat(large.get(5, TimeUnit.SECONDS).bytes()).isEqualTo(64 * MIB);
    }

    @Test
    void reservedHeapNeverExceedsTheBudget() throws Exception {
        AtomicLong inUse = new AtomicLong();
        AtomicLong peak = new AtomicLong();
        Random random = new Random(7);
        List<Callable<Void>> work = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            long bytes = random.nextLong(96 * MIB + 1);
            work.add(() -> {
                HeapBudget.Reservation reservation = heap.tryReserve(bytes, TimeUnit.SECONDS.toNanos(10));
                assertThat(reservation).as("a reservation of %d bytes", bytes).isNotNull();
                long now = inUse.addAndGet(reservation.bytes());
                peak.accumulateAndGet(now, Math::max);
                if (reservation.bytes() == heap.capacityBytes()) {
                    assertThat(now).as("a reservation of the whole budget runs alone").isEqualTo(heap.capacityBytes());
                }
                LockSupport.parkNanos(200_000);
                inUse.addAndGet(-reservation.bytes());
                reservation.release();
                return null;
            });
        }

        ExecutorService threads = Executors.newFixedThreadPool(8);
        try {
            for (Future<Void> done : threads.invokeAll(work)) {
                done.get(30, TimeUnit.SECONDS);
            }
        } finally {
            threads.shutdownNow();
        }
        assertThat(peak.get()).isLessThanOrEqualTo(64 * MIB);
        assertThat(heap.availableBytes()).isEqualTo(64 * MIB);
    }
}
