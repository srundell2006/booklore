package org.booklore.util;

import org.booklore.service.fileprocessor.prepare.PrepareContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * An unrar that hangs with its output open used to block its caller for good: the output was read to the end before
 * the timeout started. The watchdog now kills it, and its children, at the timeout.
 */
@EnabledOnOs({OS.LINUX, OS.MAC})
class UnrarHelperWatchdogTest {

    @TempDir
    Path tempDir;

    private Path pids;

    @BeforeEach
    void setUp() throws IOException {
        pids = tempDir.resolve("pids");
        Path script = tempDir.resolve("hanging-unrar");
        Files.writeString(script, "#!/bin/sh\necho $$ > '" + pids + "'\nsleep 60 &\necho $! >> '" + pids + "'\nwait\n");
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
        UnrarHelper.unrarBinOverride = script.toString();
        UnrarHelper.processTimeout = Duration.ofMillis(500);
    }

    @AfterEach
    void tearDown() {
        UnrarHelper.unrarBinOverride = null;
        UnrarHelper.processTimeout = Duration.ofSeconds(120);
        // Checked again with the real binary by whoever asks next
        UnrarHelper.cachedAvailability = null;
    }

    @Test
    void hangingListTimesOut() throws Exception {
        long started = System.nanoTime();

        assertThatThrownBy(() -> UnrarHelper.listEntries(tempDir.resolve("book.cbr")))
                .isInstanceOf(IOException.class)
                .hasMessage("unrar list timed out for: " + tempDir.resolve("book.cbr"));

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
        TestProcesses.assertAllStopped(pids, 2);
    }

    @Test
    void hangingExtractTimesOut() throws Exception {
        assertThatThrownBy(() -> UnrarHelper.extractEntryBytes(tempDir.resolve("book.cbr"), "001.jpg"))
                .isInstanceOf(IOException.class)
                .hasMessage("unrar extract timed out for entry: 001.jpg");

        TestProcesses.assertAllStopped(pids, 2);
    }

    @Test
    void hangingExtractAllTimesOut() throws Exception {
        assertThatThrownBy(() -> UnrarHelper.extractAll(tempDir.resolve("book.cbr"), tempDir))
                .isInstanceOf(IOException.class)
                .hasMessage("unrar extractAll timed out for: " + tempDir.resolve("book.cbr"));

        TestProcesses.assertAllStopped(pids, 2);
    }

    @Test
    void failingUnrarStillReportsItsExitCode() throws IOException {
        Path script = tempDir.resolve("failing-unrar");
        Files.writeString(script, "#!/bin/sh\necho 'Cannot open the file'\nexit 3\n");
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
        UnrarHelper.unrarBinOverride = script.toString();

        assertThatThrownBy(() -> UnrarHelper.listEntries(tempDir.resolve("book.cbr")))
                .isInstanceOf(IOException.class)
                .hasMessage("unrar list failed (exit 3) for: " + tempDir.resolve("book.cbr"));
    }

    @Test
    void workingUnrarListsItsOutput() throws IOException {
        Path script = tempDir.resolve("listing-unrar");
        Files.writeString(script, "#!/bin/sh\nprintf 'ComicInfo.xml\\n\\npage_001.jpg\\n'\n");
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
        UnrarHelper.unrarBinOverride = script.toString();

        assertThat(UnrarHelper.listEntries(tempDir.resolve("book.cbr"))).containsExactly("ComicInfo.xml", "page_001.jpg");
    }

    @Test
    void slowListWhilePreparingAFileForImportIsLeftToTheFilesDeadline() throws Exception {
        Path script = tempDir.resolve("slow-unrar");
        // Takes longer than the process timeout of 500 ms, as a working unrar can on slow storage
        Files.writeString(script, "#!/bin/sh\nsleep 1\nprintf 'ComicInfo.xml\\npage_001.jpg\\n'\n");
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
        UnrarHelper.unrarBinOverride = script.toString();
        List<Process> registered = new ArrayList<>();

        List<String> entries = ScopedValue.where(PrepareContext.CURRENT, TestProcesses.importContext(registered::add))
                .call(() -> UnrarHelper.listEntries(tempDir.resolve("book.cbr")));

        assertThat(entries).containsExactly("ComicInfo.xml", "page_001.jpg");
        // The import kills it if the file's deadline passes first
        assertThat(registered).hasSize(1);
    }

    @Test
    void availabilityFirstAskedByAnImportThatWasGivenUpOnIsStillTheBinarysOwn() throws Exception {
        Path script = tempDir.resolve("usage-unrar");
        Files.writeString(script, "#!/bin/sh\necho 'UNRAR 7.01 freeware'\n");
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
        UnrarHelper.unrarBinOverride = script.toString();
        UnrarHelper.cachedAvailability = null;
        // As on the thread of an import task that was given up on: it is interrupted, and its context kills every
        // process registered with it at once
        PrepareContext givenUp = TestProcesses.importContext(ProcessWatchdog::kill);
        Thread.currentThread().interrupt();
        boolean available;
        boolean stillInterrupted;
        try {
            available = ScopedValue.where(PrepareContext.CURRENT, givenUp).call(UnrarHelper::isAvailable);
        } finally {
            stillInterrupted = Thread.interrupted();
        }

        assertThat(available).isTrue();
        assertThat(stillInterrupted).isTrue();
        // What is kept for good is the binary's answer
        assertThat(UnrarHelper.isAvailable()).isTrue();
    }

    @Test
    void availabilityCheckInterruptedWhileItWaitsForUnrarToExitIsStillTheBinarysOwn() throws Exception {
        Path script = tempDir.resolve("slow-exiting-unrar");
        // Closes its output at once but exits only a second later, so the check is waiting for the exit when the
        // interrupt of an import given up on meanwhile comes
        Files.writeString(script, "#!/bin/sh\necho 'UNRAR 7.01 freeware'\nexec >&- 2>&-\nsleep 1\n");
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
        UnrarHelper.unrarBinOverride = script.toString();
        UnrarHelper.cachedAvailability = null;
        AtomicBoolean available = new AtomicBoolean();
        AtomicBoolean stillInterrupted = new AtomicBoolean();
        Thread checker = Thread.ofPlatform().start(() -> {
            available.set(UnrarHelper.isAvailable());
            stillInterrupted.set(Thread.interrupted());
        });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (checker.getState() != Thread.State.TIMED_WAITING && checker.isAlive()) {
            assertThat(System.nanoTime()).as("the check to wait for unrar to exit").isLessThan(deadline);
            Thread.sleep(5);
        }

        checker.interrupt();
        checker.join(10_000);

        assertThat(checker.isAlive()).isFalse();
        assertThat(available).isTrue();
        // The interrupt is kept for the import to see
        assertThat(stillInterrupted).isTrue();
        assertThat(UnrarHelper.isAvailable()).isTrue();
    }
}
