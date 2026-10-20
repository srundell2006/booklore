package org.booklore.util;

import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@UtilityClass
public class UnrarHelper {

    private static final String UNRAR_BIN_ENV = "BOOKLORE_UNRAR_BIN";
    private static final String DEFAULT_UNRAR_BIN = "unrar";
    private static final int PROCESS_TIMEOUT_SECONDS = 120;
    private static final Duration AVAILABILITY_TIMEOUT = Duration.ofSeconds(5);
    private static volatile Boolean cachedAvailability;

    // Test seams: the binary in place of BOOKLORE_UNRAR_BIN, and a shorter process timeout
    static volatile String unrarBinOverride;
    static volatile Duration processTimeout = Duration.ofSeconds(PROCESS_TIMEOUT_SECONDS);

    public static boolean isAvailable() {
        if (cachedAvailability != null) {
            return cachedAvailability;
        }
        synchronized (UnrarHelper.class) {
            if (cachedAvailability != null) {
                return cachedAvailability;
            }
            try {
                Process process = new ProcessBuilder(getUnrarBin())
                        .redirectErrorStream(true)
                        .start();
                try (ProcessWatchdog.WatchedProcess watched = ProcessWatchdog.watch(process, AVAILABILITY_TIMEOUT)) {
                    try (InputStream is = process.getInputStream()) {
                        is.readAllBytes();
                    }
                    boolean finished = process.waitFor(AVAILABILITY_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                    cachedAvailability = finished && !watched.killed();
                }
            } catch (Exception e) {
                log.debug("unrar binary not available: {}", e.getMessage());
                cachedAvailability = false;
            }
            if (cachedAvailability) {
                log.info("unrar CLI detected, RAR5 fallback enabled");
            }
            return cachedAvailability;
        }
    }

    public static List<String> listEntries(Path rarPath) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(getUnrarBin(), "lb", rarPath.toAbsolutePath().toString());
        pb.redirectErrorStream(true);
        Process process = pb.start();
        String output;
        try (ProcessWatchdog.WatchedProcess watched = ProcessWatchdog.watch(process, processTimeout)) {
            try (InputStream is = process.getInputStream()) {
                output = new String(is.readAllBytes());
            }
            awaitExit(watched, "unrar list timed out for: " + rarPath, "unrar list interrupted");
        }
        if (process.exitValue() != 0) {
            throw new IOException("unrar list failed (exit " + process.exitValue() + ") for: " + rarPath);
        }
        return Arrays.stream(output.split("\n"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    public static void extractEntry(Path rarPath, String entryName, OutputStream out) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(
                getUnrarBin(), "p", "-inul", rarPath.toAbsolutePath().toString(), entryName
        );
        pb.redirectErrorStream(false);
        Process process = pb.start();
        try (ProcessWatchdog.WatchedProcess watched = ProcessWatchdog.watch(process, processTimeout)) {
            try (InputStream is = process.getInputStream()) {
                is.transferTo(out);
            }
            awaitExit(watched, "unrar extract timed out for entry: " + entryName, "unrar extract interrupted");
        }
        if (process.exitValue() != 0) {
            throw new IOException("unrar extract failed (exit " + process.exitValue() + ") for entry: " + entryName);
        }
    }

    public static byte[] extractEntryBytes(Path rarPath, String entryName) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        extractEntry(rarPath, entryName, baos);
        return baos.toByteArray();
    }

    public static void extractAll(Path rarPath, Path targetDir) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(
                getUnrarBin(), "x", "-o+", rarPath.toAbsolutePath().toString(), targetDir.toAbsolutePath() + "/"
        );
        pb.redirectErrorStream(true);
        Process process = pb.start();
        try (ProcessWatchdog.WatchedProcess watched = ProcessWatchdog.watch(process, processTimeout)) {
            try (InputStream is = process.getInputStream()) {
                is.readAllBytes();
            }
            awaitExit(watched, "unrar extractAll timed out for: " + rarPath, "unrar extractAll interrupted");
        }
        if (process.exitValue() != 0) {
            throw new IOException("unrar extractAll failed (exit " + process.exitValue() + ") for: " + rarPath);
        }
    }

    /**
     * Waits for the process to exit once its output is read. The watchdog has killed it if it was still running at
     * the timeout, which is what ended the read then, and that counts as a timeout too.
     */
    private static void awaitExit(ProcessWatchdog.WatchedProcess watched, String timedOutMessage, String interruptedMessage) throws IOException {
        try {
            boolean exited = watched.process().waitFor(processTimeout.toNanos(), TimeUnit.NANOSECONDS);
            if (!exited || watched.killed()) {
                throw new IOException(timedOutMessage);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(interruptedMessage, e);
        }
    }

    private static String getUnrarBin() {
        String override = unrarBinOverride;
        if (override != null) {
            return override;
        }
        String env = System.getenv(UNRAR_BIN_ENV);
        return (env != null && !env.isBlank()) ? env : DEFAULT_UNRAR_BIN;
    }
}
