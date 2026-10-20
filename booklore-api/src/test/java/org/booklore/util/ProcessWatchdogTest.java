package org.booklore.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledOnOs({OS.LINUX, OS.MAC})
class ProcessWatchdogTest {

    @TempDir
    Path tempDir;

    @Test
    void hungProcessIsKilledWithItsChildrenAndItsReaderReleased() throws Exception {
        Path pids = tempDir.resolve("pids");
        // The child sleep keeps the output pipe open, so only killing it too lets the read below end
        Process process = new ProcessBuilder("sh", "-c", "echo $$ > '" + pids + "'; sleep 60 & echo $! >> '" + pids + "'; wait")
                .redirectErrorStream(true)
                .start();

        long started = System.nanoTime();
        try (ProcessWatchdog.WatchedProcess watched = ProcessWatchdog.watch(process, Duration.ofMillis(300))) {
            try (InputStream out = process.getInputStream()) {
                out.readAllBytes();
            }
            assertThat(watched.killed()).isTrue();
        }

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
        TestProcesses.assertAllStopped(pids, 2);
    }

    @Test
    void processThatFinishesInTimeIsLeftAlone() throws Exception {
        Process process = new ProcessBuilder("sh", "-c", "echo done").start();

        try (ProcessWatchdog.WatchedProcess watched = ProcessWatchdog.watch(process, Duration.ofSeconds(30))) {
            try (InputStream out = process.getInputStream()) {
                assertThat(new String(out.readAllBytes()).trim()).isEqualTo("done");
            }
            assertThat(process.waitFor(5, TimeUnit.SECONDS)).isTrue();
            assertThat(watched.killed()).isFalse();
            assertThat(watched.process()).isSameAs(process);
        }
        assertThat(process.exitValue()).isZero();
    }

    @Test
    void closingKillsAProcessStillRunning() throws Exception {
        Path pids = tempDir.resolve("pids");
        Process process = new ProcessBuilder("sh", "-c", "echo $$ > '" + pids + "'; sleep 60 & echo $! >> '" + pids + "'; wait").start();
        awaitPids(pids);

        try (ProcessWatchdog.WatchedProcess watched = ProcessWatchdog.watch(process, Duration.ofSeconds(30))) {
            assertThat(watched.killed()).isFalse();
        }

        assertThat(process.waitFor(5, TimeUnit.SECONDS)).isTrue();
        TestProcesses.assertAllStopped(pids, 2);
    }

    private static void awaitPids(Path pids) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!Files.exists(pids) || Files.readAllLines(pids).size() < 2) {
            assertThat(System.nanoTime()).as("the script to start").isLessThan(deadline);
            Thread.sleep(20);
        }
    }
}
