package org.booklore.service.reader;

import org.booklore.util.FileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FfprobeServiceTest {

    @Mock
    FileService fileService;

    @InjectMocks
    FfprobeService ffprobeService;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        when(fileService.getToolsFfprobePath()).thenReturn(tempDir.resolve("tools/ffprobe").toString());
    }

    @Test
    void getFfprobeBinary_returnsExistingBinary() throws IOException {
        Path toolsDir = tempDir.resolve("tools/ffprobe");
        Files.createDirectories(toolsDir);

        String expectedName = getExpectedBinaryName();
        Path binaryPath = toolsDir.resolve(expectedName);
        Files.writeString(binaryPath, "fake-binary");

        Path result = ffprobeService.getFfprobeBinary();

        assertThat(result).isNotNull();
        assertThat(result.getFileName().toString()).isEqualTo(expectedName);
        assertThat(result).exists();
    }

    @Test
    void getFfprobeBinary_createsDirectoryIfMissing() throws IOException {
        Path toolsDir = tempDir.resolve("tools/ffprobe");

        String expectedName = getExpectedBinaryName();
        Path binaryPath = toolsDir.resolve(expectedName);
        Files.createDirectories(toolsDir);
        Files.writeString(binaryPath, "fake-binary");

        Path result = ffprobeService.getFfprobeBinary();

        assertThat(result).isNotNull();
        assertThat(toolsDir).exists();
    }

    @Test
    void getFfprobeBinary_returnsNullOnFailure() {
        when(fileService.getToolsFfprobePath()).thenReturn("/nonexistent\0path");

        Path result = ffprobeService.getFfprobeBinary();

        assertThat(result).isNull();
    }

    @Test
    void getFfprobeBinary_setsExecutablePermission() throws IOException {
        Path toolsDir = tempDir.resolve("tools/ffprobe");
        Files.createDirectories(toolsDir);

        String expectedName = getExpectedBinaryName();
        Path binaryPath = toolsDir.resolve(expectedName);
        Files.writeString(binaryPath, "fake-binary");

        Path result = ffprobeService.getFfprobeBinary();

        assertThat(result).isNotNull();
        assertThat(result.toFile().canExecute()).isTrue();
    }

    @Test
    void concurrentCallersShareOneDownload() throws Exception {
        AtomicInteger downloads = new AtomicInteger();
        FfprobeService service = new FfprobeService(fileService) {
            @Override
            InputStream openDownload(String url) throws IOException {
                downloads.incrementAndGet();
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    throw new IOException(e);
                }
                return new ByteArrayInputStream("complete-binary".getBytes());
            }
        };
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        Set<Path> results = ConcurrentHashMap.newKeySet();
        for (int i = 0; i < 16; i++) {
            threads.add(Thread.ofPlatform().start(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    return;
                }
                Path binary = service.getFfprobeBinary();
                if (binary != null) {
                    results.add(binary);
                }
            }));
        }

        start.countDown();
        for (Thread thread : threads) {
            thread.join(10_000);
        }

        Path toolsDir = tempDir.resolve("tools/ffprobe");
        assertThat(downloads).hasValue(1);
        assertThat(results).containsExactly(toolsDir.resolve(getExpectedBinaryName()));
        assertThat(Files.readString(toolsDir.resolve(getExpectedBinaryName()))).isEqualTo("complete-binary");
        assertThat(toolsDir.resolve(getExpectedBinaryName()).toFile().canExecute()).isTrue();
        try (Stream<Path> files = Files.list(toolsDir)) {
            assertThat(files).extracting(file -> file.getFileName().toString()).containsExactly(getExpectedBinaryName());
        }
    }

    @Test
    void failedDownloadIsNotRetriedAtOnce() throws IOException {
        AtomicInteger downloads = new AtomicInteger();
        FfprobeService service = new FfprobeService(fileService) {
            @Override
            InputStream openDownload(String url) throws IOException {
                downloads.incrementAndGet();
                return new InputStream() {
                    @Override
                    public int read() throws IOException {
                        throw new IOException("connection reset");
                    }
                };
            }
        };

        assertThat(service.getFfprobeBinary()).isNull();
        assertThat(service.getFfprobeBinary()).isNull();

        assertThat(downloads).hasValue(1);
        try (Stream<Path> files = Files.list(tempDir.resolve("tools/ffprobe"))) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    void waiterThatIsInterruptedReturnsAtOnce() throws Exception {
        CountDownLatch downloading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        FfprobeService service = new FfprobeService(fileService) {
            @Override
            InputStream openDownload(String url) throws IOException {
                downloading.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    throw new IOException(e);
                }
                return new ByteArrayInputStream("binary".getBytes());
            }
        };
        Thread downloader = Thread.ofPlatform().start(service::getFfprobeBinary);
        assertThat(downloading.await(5, TimeUnit.SECONDS)).isTrue();

        AtomicReference<Path> waiterResult = new AtomicReference<>(Path.of("unset"));
        AtomicBoolean waiterInterrupted = new AtomicBoolean();
        Thread waiter = Thread.ofPlatform().start(() -> {
            waiterResult.set(service.getFfprobeBinary());
            waiterInterrupted.set(Thread.currentThread().isInterrupted());
        });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (waiter.getState() != Thread.State.WAITING) {
            assertThat(System.nanoTime()).as("the waiter to wait for the download").isLessThan(deadline);
            Thread.sleep(10);
        }

        waiter.interrupt();
        waiter.join(1000);

        assertThat(waiter.isAlive()).isFalse();
        assertThat(waiterResult.get()).isNull();
        assertThat(waiterInterrupted).isTrue();
        release.countDown();
        downloader.join(5000);
        assertThat(service.getFfprobeBinary()).isEqualTo(tempDir.resolve("tools/ffprobe").resolve(getExpectedBinaryName()));
    }

    @Test
    void existingBinaryIsMadeExecutableOnceAndDeletedOneIsSetUpAgain() throws IOException {
        AtomicInteger downloads = new AtomicInteger();
        FfprobeService service = new FfprobeService(fileService) {
            @Override
            InputStream openDownload(String url) {
                downloads.incrementAndGet();
                return new ByteArrayInputStream("downloaded".getBytes());
            }
        };
        Path toolsDir = Files.createDirectories(tempDir.resolve("tools/ffprobe"));
        Path binaryPath = Files.writeString(toolsDir.resolve(getExpectedBinaryName()), "fake-binary");

        assertThat(service.getFfprobeBinary()).isEqualTo(binaryPath);
        assertThat(binaryPath.toFile().canExecute()).isTrue();
        Files.setPosixFilePermissions(binaryPath, PosixFilePermissions.fromString("rw-------"));
        assertThat(service.getFfprobeBinary()).isEqualTo(binaryPath);
        assertThat(binaryPath.toFile().canExecute()).isFalse();
        assertThat(downloads).hasValue(0);

        Files.delete(binaryPath);
        assertThat(service.getFfprobeBinary()).isEqualTo(binaryPath);
        assertThat(Files.readString(binaryPath)).isEqualTo("downloaded");
        assertThat(downloads).hasValue(1);
    }

    private String getExpectedBinaryName() {
        String osName = System.getProperty("os.name").toLowerCase();
        String osArch = System.getProperty("os.arch").toLowerCase();

        if (osName.contains("mac") || osName.contains("darwin")) {
            if (osArch.contains("arm") || osArch.contains("aarch64")) {
                return "ffprobe-darwin-arm64";
            }
            return "ffprobe-darwin-64";
        } else if (osName.contains("linux")) {
            if (osArch.contains("arm64") || osArch.contains("aarch64")) {
                return "ffprobe-linux-arm64";
            }
            return "ffprobe-linux-64";
        }
        throw new IllegalStateException("Unsupported OS for test: " + osName);
    }
}
