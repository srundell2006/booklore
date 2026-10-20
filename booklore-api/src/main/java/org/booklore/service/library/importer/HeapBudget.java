package org.booklore.service.library.importer;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Heap that cover rendering and document parsing reserve before they allocate, shared by every import. It is counted
 * in KiB on a fair semaphore, so waiters are served in turn and a large request is not starved by small ones. A
 * request is cut to between 1 KiB and the whole budget: one larger than the budget waits until it can run alone
 * instead of waiting for ever.
 */
final class HeapBudget {

    private final int capacityKib;
    private final Semaphore availableKib;

    HeapBudget(long capacityBytes) {
        this.capacityKib = Math.clamp(capacityBytes / 1024, 1, Integer.MAX_VALUE);
        this.availableKib = new Semaphore(capacityKib, true);
    }

    long capacityBytes() {
        return capacityKib * 1024L;
    }

    long availableBytes() {
        return availableKib.availablePermits() * 1024L;
    }

    /**
     * Reserves {@code bytes}, cut to the budget, waiting at most {@code timeoutNanos}.
     *
     * @return the reservation, or null when the wait timed out
     */
    Reservation tryReserve(long bytes, long timeoutNanos) throws InterruptedException {
        int kib = Math.clamp(Math.ceilDiv(bytes, 1024), 1, capacityKib);
        return availableKib.tryAcquire(kib, timeoutNanos, TimeUnit.NANOSECONDS) ? new Reservation(kib) : null;
    }

    final class Reservation {
        private final int kib;
        private final AtomicBoolean released = new AtomicBoolean();

        private Reservation(int kib) {
            this.kib = kib;
        }

        /** What the reservation took from the budget. */
        long bytes() {
            return kib * 1024L;
        }

        /** Gives the heap back to the budget; only the first call does, and returns true. */
        boolean release() {
            if (!released.compareAndSet(false, true)) {
                return false;
            }
            availableKib.release(kib);
            return true;
        }
    }
}
