package org.booklore.util;

import org.booklore.service.fileprocessor.prepare.HeapReservation;
import org.booklore.service.fileprocessor.prepare.IoCallable;
import org.booklore.service.fileprocessor.prepare.PrepareContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/** Checks on processes started by tests through scripts that write their process ids to a file. */
public final class TestProcesses {

    private TestProcesses() {
    }

    /** Waits up to five seconds for the {@code count} processes listed in {@code pidFile}, one id per line, to stop. */
    public static void assertAllStopped(Path pidFile, int count) throws IOException, InterruptedException {
        List<String> lines = Files.readAllLines(pidFile);
        assertThat(lines).hasSize(count);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        for (String line : lines) {
            long pid = Long.parseLong(line.trim());
            while (isRunning(pid)) {
                assertThat(System.nanoTime()).as("process %d to stop", pid).isLessThan(deadline);
                Thread.sleep(10);
            }
        }
    }

    /**
     * A context for running code as a file being prepared for import runs, bound as {@link PrepareContext#CURRENT}: it
     * hands each process registered with it to {@code onRegister}, such as a list, or {@link ProcessWatchdog#kill} as
     * the context of an import that has given up on its file does. It sets no limits.
     */
    public static PrepareContext importContext(Consumer<Process> onRegister) {
        return new PrepareContext() {
            @Override
            public void checkpoint() {
            }

            @Override
            public <T> T render(long estimatedBytes, IoCallable<T> work) throws Exception {
                return work.call();
            }

            @Override
            public HeapReservation reserveHeap(long bytes) {
                return () -> {
                };
            }

            @Override
            public void registerProcess(Process process) {
                onRegister.accept(process);
            }
        };
    }

    /**
     * Whether the process still runs. A killed process whose parent died first waits as a zombie until init reaps it,
     * which in a container can take a while; on Linux that counts as stopped.
     */
    private static boolean isRunning(long pid) {
        Path stat = Path.of("/proc", String.valueOf(pid), "stat");
        if (Files.exists(stat)) {
            try {
                String content = Files.readString(stat);
                char state = content.charAt(content.lastIndexOf(')') + 2);
                return state != 'Z' && state != 'X';
            } catch (IOException e) {
                return false;
            }
        }
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }
}
