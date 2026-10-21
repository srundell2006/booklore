package org.booklore.service.reader;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.util.FileService;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Provides the ffprobe binary, downloading it on first use. Callers on many threads at once share one setup, which
 * downloads to a temporary file and renames it into place while they all wait for it. A failed setup is not retried
 * for ten minutes, so a missing network does not stall every audiobook import on its own download attempt.
 * <p>
 * The setup runs on a thread of its own, and callers wait for it in a way an interrupt ends. An import that is given up
 * on at its file's deadline, and interrupted, then stops waiting, but does not stop the download, which on a slow link
 * can take longer than one file's deadline: stopped, it would fail every audiobook after it for ten minutes, and they
 * would be saved without their chapters.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FfprobeService {

    private static final String FFPROBE_GITHUB_BASE_URL = "https://github.com/booklore-app/booklore-tools/raw/main/ffprobe/";

    private static final String BIN_DARWIN_ARM64 = "ffprobe-darwin-arm64";
    private static final String BIN_DARWIN_X64 = "ffprobe-darwin-64";
    private static final String BIN_LINUX_X64 = "ffprobe-linux-64";
    private static final String BIN_LINUX_ARM64 = "ffprobe-linux-arm64";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration RETRY_FAILED_SETUP_AFTER = Duration.ofMinutes(10);

    private final FileService fileService;

    private final ReentrantLock setupLock = new ReentrantLock();
    // Guarded by setupLock: the setup running now, or the last one
    private Future<Path> setup;
    private volatile Path binary;
    private volatile boolean setupFailed;
    private volatile long retrySetupAtNanos;

    public Path getFfprobeBinary() {
        Path ready = readyBinary();
        if (ready != null || setupFailedRecently()) {
            return ready;
        }
        try {
            return currentSetup().get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException e) {
            log.warn("Failed to set up ffprobe binary: {}", e.getCause().toString());
            return null;
        }
    }

    /** The setup running now, started if there is none, or the outcome of the last one if it is still good. */
    private Future<Path> currentSetup() {
        setupLock.lock();
        try {
            if (setup == null || setup.isDone()) {
                // Another caller's setup may have finished, or failed, since this one looked
                Path ready = readyBinary();
                if (ready != null || setupFailedRecently()) {
                    return CompletableFuture.completedFuture(ready);
                }
                FutureTask<Path> task = new FutureTask<>(this::runSetup);
                Thread.ofPlatform().daemon(true).name("ffprobe-setup").start(task);
                setup = task;
            }
            return setup;
        } finally {
            setupLock.unlock();
        }
    }

    private Path runSetup() {
        try {
            binary = setupFfprobeBinary();
            setupFailed = false;
            return binary;
        } catch (Exception e) {
            log.warn("Failed to set up ffprobe binary: {}", e.getMessage());
            retrySetupAtNanos = System.nanoTime() + RETRY_FAILED_SETUP_AFTER.toNanos();
            setupFailed = true;
            return null;
        }
    }

    /** The binary set up earlier, as long as it is still there. */
    private Path readyBinary() {
        Path ready = binary;
        return ready != null && Files.exists(ready) ? ready : null;
    }

    private boolean setupFailedRecently() {
        return setupFailed && System.nanoTime() - retrySetupAtNanos < 0;
    }

    private Path setupFfprobeBinary() throws Exception {
        String binaryName = getFfprobeBinaryName();
        Path toolsDir = Paths.get(fileService.getToolsFfprobePath());
        if (!Files.exists(toolsDir)) {
            Files.createDirectories(toolsDir);
        }
        Path binaryPath = toolsDir.resolve(binaryName);

        if (!Files.exists(binaryPath)) {
            String downloadUrl = FFPROBE_GITHUB_BASE_URL + binaryName;
            log.info("Downloading ffprobe binary '{}' from {}", binaryName, downloadUrl);
            // Download beside the binary and rename it into place, so no one ever runs half a binary
            Path partial = toolsDir.resolve(binaryName + ".part-" + UUID.randomUUID());
            try {
                try (InputStream in = openDownload(downloadUrl)) {
                    Files.copy(in, partial);
                }
                if (!partial.toFile().setExecutable(true)) {
                    log.warn("Failed to set executable permission for '{}'", partial.toAbsolutePath());
                }
                Files.move(partial, binaryPath, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(partial);
            }
            log.info("Downloaded ffprobe binary to {}", binaryPath.toAbsolutePath());
        } else {
            if (!binaryPath.toFile().setExecutable(true)) {
                log.warn("Failed to set executable permission for '{}'", binaryPath.toAbsolutePath());
            }
            log.debug("Using existing ffprobe binary at {}", binaryPath.toAbsolutePath());
        }
        return binaryPath;
    }

    // Seam for tests that must not download
    InputStream openDownload(String url) throws IOException {
        URLConnection connection = URI.create(url).toURL().openConnection();
        connection.setConnectTimeout((int) CONNECT_TIMEOUT.toMillis());
        connection.setReadTimeout((int) READ_TIMEOUT.toMillis());
        return connection.getInputStream();
    }

    private String getFfprobeBinaryName() {
        String osName = System.getProperty("os.name").toLowerCase();
        String osArch = System.getProperty("os.arch").toLowerCase();

        log.debug("Detected OS: {} ({})", osName, osArch);

        if (osName.contains("mac") || osName.contains("darwin")) {
            if (osArch.contains("arm") || osArch.contains("aarch64")) {
                return BIN_DARWIN_ARM64;
            } else {
                return BIN_DARWIN_X64;
            }
        } else if (osName.contains("linux")) {
            if (osArch.contains("arm64") || osArch.contains("aarch64")) {
                return BIN_LINUX_ARM64;
            } else if (osArch.contains("64")) {
                return BIN_LINUX_X64;
            }
        }
        throw new IllegalStateException("Unsupported operating system or architecture: " + osName + " / " + osArch);
    }
}
