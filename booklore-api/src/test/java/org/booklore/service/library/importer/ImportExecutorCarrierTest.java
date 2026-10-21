package org.booklore.service.library.importer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * On JDK 25 a virtual thread blocked in a file call keeps its carrier, so import tasks wedged on storage must not run
 * on virtual threads: a few of them would stop every virtual thread in the application. Opening a FIFO with no writer
 * blocks in the open call the same way a wedged CIFS open does. This fails if the workers become virtual threads.
 */
@EnabledOnOs(OS.LINUX)
class ImportExecutorCarrierTest {

    @TempDir
    Path tempDir;

    @Test
    void tasksBlockedInFileCallsLeaveVirtualThreadsRunning() throws Exception {
        int blocked = Runtime.getRuntime().availableProcessors() + 2;
        ImportExecutor executor = new ImportExecutor(new ImportSettings(true, blocked, 4 * blocked, Duration.ofMinutes(1),
                128, 1, 64L * 1024 * 1024));
        List<Path> fifos = new ArrayList<>();
        List<FileTask<Integer>> tasks = new ArrayList<>();
        try {
            for (int i = 0; i < blocked; i++) {
                Path fifo = tempDir.resolve("fifo-" + i);
                assertThat(new ProcessBuilder("mkfifo", fifo.toString()).start().waitFor()).isZero();
                fifos.add(fifo);
                tasks.add(executor.submit("fifo " + i, ctx -> {
                    // Blocks in open until a writer opens the FIFO
                    try (InputStream in = new FileInputStream(fifo.toFile())) {
                        return in.read();
                    }
                }, () -> false));
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!tasks.stream().allMatch(task -> task.state() == FileTask.State.RUNNING)) {
                assertThat(System.nanoTime()).as("every task to start").isLessThan(deadline);
                Thread.sleep(5);
            }
            // Time for each to reach its open call
            Thread.sleep(200);

            CountDownLatch ran = new CountDownLatch(1);
            Thread.ofVirtual().start(ran::countDown);
            assertThat(ran.await(1, TimeUnit.SECONDS)).as("a virtual thread to run while the import threads are blocked").isTrue();

            releaseReaders(fifos);
            for (FileTask<Integer> task : tasks) {
                // The writer closed without writing, so each reader saw the end of the stream
                assertThat(task.result().get(5, TimeUnit.SECONDS)).isInstanceOfSatisfying(Outcome.Done.class,
                        done -> assertThat(done.value()).isEqualTo(-1));
            }
        } finally {
            if (tasks.stream().anyMatch(task -> task.state() == FileTask.State.RUNNING)) {
                releaseReaders(fifos);
            }
            executor.shutdown();
        }
    }

    /** Opens each FIFO for writing, which completes its reader's open, on a platform thread the test does not wait on for good. */
    private static void releaseReaders(List<Path> fifos) throws InterruptedException {
        Thread writer = Thread.ofPlatform().daemon(true).start(() -> {
            for (Path fifo : fifos) {
                try (FileOutputStream ignored = new FileOutputStream(fifo.toFile())) {
                    // closing it ends the reader's stream
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }
        });
        writer.join(TimeUnit.SECONDS.toMillis(10));
    }
}
