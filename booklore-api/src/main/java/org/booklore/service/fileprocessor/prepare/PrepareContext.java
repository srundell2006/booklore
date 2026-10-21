package org.booklore.service.fileprocessor.prepare;

/**
 * What a file's preparation may use while it reads the file: a check that it is still wanted, and the shared limits on
 * cover rendering and heap. An import task gives each file one with a deadline; a single file prepared inline gets
 * {@link #unbounded()}, which never limits or stops it.
 */
public interface PrepareContext {

    /** The context of the preparation running on the current thread, bound by the import task that runs it. */
    ScopedValue<PrepareContext> CURRENT = ScopedValue.newInstance();

    /**
     * Throws {@link PrepareAbandonedException} when the preparation is no longer wanted, such as when its deadline
     * passed or its thread was interrupted. Long preparations call this between steps.
     */
    void checkpoint();

    /**
     * Runs a decode, render or encode of a cover while holding a render permit and {@code estimatedBytes} of the heap
     * budget, both released when it returns.
     *
     * @throws PrepareDeadlineException  when the file's deadline passes while waiting for either
     * @throws PrepareAbandonedException when the preparation is given up on, or its thread interrupted, while waiting
     */
    <T> T render(long estimatedBytes, IoCallable<T> work) throws Exception;

    /**
     * Reserves heap for a step that is not a render, such as parsing a document. Close the reservation when done.
     *
     * @throws PrepareDeadlineException  when the file's deadline passes while waiting for it
     * @throws PrepareAbandonedException when the preparation is given up on, or its thread interrupted, while waiting
     */
    HeapReservation reserveHeap(long bytes);

    /**
     * A child process the preparation started, to be killed when the preparation is given up on. The preparation's
     * deadline then bounds the process: {@link org.booklore.util.ProcessWatchdog} gives it no timeout of its own.
     */
    void registerProcess(Process process);

    /** For a file prepared inline on the caller's thread: no permits, no heap budget, no deadline. */
    static PrepareContext unbounded() {
        return UnboundedPrepareContext.INSTANCE;
    }
}
