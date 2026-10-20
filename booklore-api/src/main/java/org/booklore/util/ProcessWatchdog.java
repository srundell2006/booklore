package org.booklore.util;

import org.booklore.service.fileprocessor.prepare.PrepareContext;

import java.time.Duration;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Kills a child process that outlives its timeout. A thread reading a process's output to the end cannot be
 * interrupted, and a {@code waitFor} timeout only starts once the output is read, so without a watchdog a process that
 * hangs with its output open blocks its reader for good. Killing the process (and its children, which may hold the
 * output open too) closes the output and releases the reader.
 */
public final class ProcessWatchdog {

    private static final ScheduledThreadPoolExecutor TIMER = createTimer();

    private ProcessWatchdog() {
    }

    private static ScheduledThreadPoolExecutor createTimer() {
        ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1,
                Thread.ofPlatform().daemon(true).name("process-watchdog").factory());
        timer.setRemoveOnCancelPolicy(true);
        return timer;
    }

    /**
     * Kills {@code process} if it is still running after {@code timeout}. Close the result when done with the process:
     * that stops the timer, and kills the process if it is still running.
     * <p>
     * Called while preparing a file for import, the process is also registered with that preparation's
     * {@link PrepareContext}, so it is killed as soon as the import gives up on the file.
     */
    public static WatchedProcess watch(Process process, Duration timeout) {
        WatchedProcess watched = new WatchedProcess(process);
        watched.startTimer(timeout);
        if (PrepareContext.CURRENT.isBound()) {
            PrepareContext.CURRENT.get().registerProcess(process);
        }
        return watched;
    }

    /**
     * As {@link #watch} outside an import, wherever it is called: for a process that is not part of the file being
     * prepared, such as a check of which tools are installed whose answer the whole application keeps. Giving up on the
     * file being prepared does not kill it.
     */
    public static WatchedProcess watchIndependently(Process process, Duration timeout) {
        WatchedProcess watched = new WatchedProcess(process);
        watched.startTimer(timeout);
        return watched;
    }

    /** Kills the process and every process it started. */
    public static void kill(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    public static final class WatchedProcess implements AutoCloseable {

        private final Process process;
        private volatile boolean killed;
        private volatile ScheduledFuture<?> timer;

        private WatchedProcess(Process process) {
            this.process = process;
        }

        public Process process() {
            return process;
        }

        /** Whether the timeout passed with the process still running, so it was killed. */
        public boolean killed() {
            return killed;
        }

        private void startTimer(Duration timeout) {
            timer = TIMER.schedule(this::timeOut, timeout.toNanos(), TimeUnit.NANOSECONDS);
        }

        private void timeOut() {
            if (process.isAlive()) {
                killed = true;
                kill(process);
            }
        }

        @Override
        public void close() {
            ScheduledFuture<?> scheduled = timer;
            if (scheduled != null) {
                scheduled.cancel(false);
            }
            if (process.isAlive()) {
                kill(process);
            }
        }
    }
}
