package org.booklore.util;

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
}
