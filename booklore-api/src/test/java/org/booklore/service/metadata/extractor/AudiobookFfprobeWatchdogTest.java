package org.booklore.service.metadata.extractor;

import org.booklore.model.dto.AudiobookMetadata;
import org.booklore.model.dto.BookMetadata;
import org.booklore.service.reader.FfprobeService;
import org.booklore.util.TestProcesses;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ffprobe used to be waited for with no bound, after its output was read to the end, so one that hung blocked the
 * import for good. It is now killed at its timeout and the book gets the default chapter.
 */
@EnabledOnOs({OS.LINUX, OS.MAC})
class AudiobookFfprobeWatchdogTest {

    private static final Path MP3 = Path.of("src/test/resources/audio/fixture.mp3");

    @TempDir
    Path tempDir;

    @Test
    void hangingFfprobeIsKilledAndTheBookGetsTheDefaultChapter() throws Exception {
        Path pids = tempDir.resolve("pids");
        Path ffprobe = script("hanging-ffprobe", "echo $$ > '" + pids + "'\nsleep 60 &\necho $! >> '" + pids + "'\nwait\n");
        File book = Files.copy(MP3, tempDir.resolve("book.mp3")).toFile();

        long started = System.nanoTime();
        BookMetadata metadata = extractorUsing(ffprobe).extractMetadata(book);

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(10));
        assertThat(metadata.getTitle()).isEqualTo("Fixture_Album");
        assertThat(metadata.getAudiobookMetadata().getChapters()).singleElement()
                .extracting(AudiobookMetadata.ChapterInfo::getTitle).isEqualTo("Full Audiobook");
        TestProcesses.assertAllStopped(pids, 2);
    }

    @Test
    void ffprobeThatAnswersGivesItsChapters() throws IOException {
        Path ffprobe = script("answering-ffprobe", """
                echo '{"chapters": [{"start_time": "0.000", "end_time": "0.600", "tags": {"title": "Opening"}},'
                echo '{"start": 600, "end": 1200, "time_base": "1/1000"}]}'
                """);
        File book = Files.copy(MP3, tempDir.resolve("book.mp3")).toFile();

        List<AudiobookMetadata.ChapterInfo> chapters = extractorUsing(ffprobe).extractMetadata(book).getAudiobookMetadata().getChapters();

        assertThat(chapters).extracting(AudiobookMetadata.ChapterInfo::getTitle).containsExactly("Opening", "Chapter 2");
        assertThat(chapters).extracting(AudiobookMetadata.ChapterInfo::getEndTimeMs).containsExactly(600L, 1200L);
    }

    @Test
    void ffprobeThatFailsGivesTheDefaultChapter() throws IOException {
        Path ffprobe = script("failing-ffprobe", "echo '{\"chapters\": []}'\nexit 1\n");
        File book = Files.copy(MP3, tempDir.resolve("book.mp3")).toFile();

        assertThat(extractorUsing(ffprobe).extractMetadata(book).getAudiobookMetadata().getChapters()).singleElement()
                .extracting(AudiobookMetadata.ChapterInfo::getTitle).isEqualTo("Full Audiobook");
    }

    private AudiobookMetadataExtractor extractorUsing(Path ffprobe) {
        FfprobeService ffprobeService = mock(FfprobeService.class);
        when(ffprobeService.getFfprobeBinary()).thenReturn(ffprobe);
        return new AudiobookMetadataExtractor(ffprobeService) {
            @Override
            Duration ffprobeTimeout() {
                return Duration.ofMillis(500);
            }
        };
    }

    private Path script(String name, String body) throws IOException {
        Path script = tempDir.resolve(name);
        Files.writeString(script, "#!/bin/sh\n" + body);
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
        return script;
    }
}
