package org.booklore.service.library.importer;

/** How an import task ended. Each task completes with exactly one. */
public sealed interface Outcome<T> {

    String label();

    /** The work returned {@code value}, {@code elapsedNanos} after the task got its IO slot. */
    record Done<T>(String label, T value, long elapsedNanos) implements Outcome<T> {
    }

    /**
     * The work threw {@code cause}. A {@link org.booklore.service.fileprocessor.prepare.PrepareDeadlineException}
     * means the deadline passed while it waited for a render permit or heap; its thread has returned.
     */
    record Failed<T>(String label, Throwable cause) implements Outcome<T> {
    }

    /** The work was still running at its deadline and was abandoned; whatever it returns later is dropped. */
    record TimedOut<T>(String label, long elapsedNanos) implements Outcome<T> {
    }

    /** The work never started, or for {@link SkipReason#SHUTDOWN} was abandoned when the application stopped. */
    record Skipped<T>(String label, SkipReason reason) implements Outcome<T> {
    }

    enum SkipReason {
        /** The caller's skip check said the task is no longer wanted, such as when its group already failed. */
        GROUP_FAILED,
        /** Too many import threads were stuck on storage to start another. */
        STORAGE_STUCK,
        /** The caller cancelled it while it was queued. */
        CANCELLED,
        /** The application is shutting down. */
        SHUTDOWN
    }
}
