package org.booklore.util;

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
     */
    public static WatchedProcess watch(Process process, Duration timeout) {
        WatchedProcess watched = new WatchedProcess(process);
        watched.timer = TIMER.schedule(watched::timeOut, timeout.toNanos(), TimeUnit.NANOSECONDS);
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
