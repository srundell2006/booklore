package org.booklore.service.fileprocessor.prepare;

final class UnboundedPrepareContext implements PrepareContext {

    static final PrepareContext INSTANCE = new UnboundedPrepareContext();

    private static final HeapReservation NO_RESERVATION = () -> {
    };

    private UnboundedPrepareContext() {
    }

    @Override
    public void checkpoint() {
    }

    @Override
    public <T> T render(long estimatedBytes, IoCallable<T> work) throws Exception {
        return work.call();
    }

    @Override
    public HeapReservation reserveHeap(long bytes) {
        return NO_RESERVATION;
    }

    @Override
    public void registerProcess(Process process) {
        // ProcessWatchdog still enforces the process's own timeout
    }
}
