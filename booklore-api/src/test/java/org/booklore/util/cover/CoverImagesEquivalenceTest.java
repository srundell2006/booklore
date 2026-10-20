package org.booklore.util.cover;

import org.booklore.config.AppProperties;
import org.booklore.model.dto.settings.AppSettings;
import org.booklore.model.dto.settings.CoverCroppingSettings;
import org.booklore.service.appsettings.AppSettingService;
import org.booklore.util.FileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The stored cover files must stay byte for byte what FileService wrote before the cover pipelines moved into
 * CoverImages. {@link LegacyCoverWriter} is the old code, kept as the oracle.
 */
class CoverImagesEquivalenceTest {

    private static final CoverCroppingSettings NO_CROPPING = new CoverCroppingSettings(false, false, 2.5, false);
    private static final CoverCroppingSettings VERTICAL = new CoverCroppingSettings(true, false, 2.5, false);
    private static final CoverCroppingSettings VERTICAL_SMART = new CoverCroppingSettings(true, false, 2.5, true);
    private static final CoverCroppingSettings HORIZONTAL = new CoverCroppingSettings(false, true, 2.5, false);
    private static final CoverCroppingSettings HORIZONTAL_SMART = new CoverCroppingSettings(false, true, 2.5, true);
    private static final CoverCroppingSettings BOTH_SMART = new CoverCroppingSettings(true, true, 2.5, true);

    @TempDir
    Path tempDir;

    private AppSettingService appSettingService;
    private FileService fileService;

    @BeforeEach
    void setUp() {
        AppProperties appProperties = new AppProperties();
        appProperties.setPathConfig(tempDir.resolve("config").toString());
        appSettingService = mock(AppSettingService.class);
        fileService = new FileService(appProperties, null, appSettingService, null);
    }

    static Stream<Arguments> bookCovers() {
        return Stream.of(
                arguments("portrait", rgb(600, 900), BOTH_SMART),
                arguments("landscape", rgb(900, 600), BOTH_SMART),
                arguments("square-ish", rgb(700, 760), BOTH_SMART),
                arguments("tall, no cropping settings", tall(), null),
                arguments("tall, cropping off", tall(), NO_CROPPING),
                arguments("tall, vertical cropping", tall(), VERTICAL),
                arguments("tall, vertical smart cropping", tall(), VERTICAL_SMART),
                arguments("tall, horizontal cropping only", tall(), HORIZONTAL),
                arguments("wide, no cropping settings", wide(), null),
                arguments("wide, horizontal cropping", wide(), HORIZONTAL),
                arguments("wide, horizontal smart cropping", wide(), HORIZONTAL_SMART),
                arguments("wide, vertical cropping only", wide(), VERTICAL),
                arguments("png with alpha", (Supplier<BufferedImage>) () -> CoverFixtures.translucent(400, 600), BOTH_SMART),
                arguments("grayscale", (Supplier<BufferedImage>) () -> CoverFixtures.gray(400, 600), BOTH_SMART),
                arguments("cmyk jpeg", cmyk(), BOTH_SMART),
                arguments("tiny", rgb(200, 280), BOTH_SMART),
                arguments("3000x4500", bgr(3000, 4500), BOTH_SMART));
    }

    static Stream<Arguments> audiobookCovers() {
        return Stream.of(
                arguments("portrait", rgb(600, 900)),
                arguments("landscape", rgb(900, 600)),
                arguments("square", rgb(800, 800)),
                arguments("png with alpha", (Supplier<BufferedImage>) () -> CoverFixtures.translucent(400, 600)),
                arguments("grayscale", (Supplier<BufferedImage>) () -> CoverFixtures.gray(400, 600)),
                arguments("cmyk jpeg", cmyk()),
                arguments("tiny", rgb(200, 280)),
                arguments("3000x4500", bgr(3000, 4500)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("bookCovers")
    void bookCoverFilesMatchTheLegacyWriter(String name, Supplier<BufferedImage> source, CoverCroppingSettings cropping) throws IOException {
        when(appSettingService.getAppSettings()).thenReturn(AppSettings.builder().coverCroppingSettings(cropping).build());
        File legacyFolder = tempDir.resolve("legacy").toFile();
        BufferedImage image = source.get();
        try {
            boolean legacySaved = LegacyCoverWriter.saveCoverImages(image, legacyFolder, cropping);
            boolean saved = fileService.saveCoverImages(image, 1L);

            assertThat(saved).isEqualTo(legacySaved).isTrue();
            assertThat(Files.readAllBytes(Path.of(fileService.getCoverFile(1L))))
                    .isEqualTo(Files.readAllBytes(legacyFolder.toPath().resolve("cover.jpg")));
            assertThat(Files.readAllBytes(Path.of(fileService.getThumbnailFile(1L))))
                    .isEqualTo(Files.readAllBytes(legacyFolder.toPath().resolve("thumbnail.jpg")));
        } finally {
            image.flush();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("audiobookCovers")
    void audiobookCoverFilesMatchTheLegacyWriter(String name, Supplier<BufferedImage> source) throws IOException {
        File legacyFolder = tempDir.resolve("legacy").toFile();
        BufferedImage image = source.get();
        try {
            boolean legacySaved = LegacyCoverWriter.saveAudiobookCoverImages(image, legacyFolder);
            boolean saved = fileService.saveAudiobookCoverImages(image, 1L);

            assertThat(saved).isEqualTo(legacySaved).isTrue();
            assertThat(Files.readAllBytes(Path.of(fileService.getAudiobookCoverFile(1L))))
                    .isEqualTo(Files.readAllBytes(legacyFolder.toPath().resolve("audiobook-cover.jpg")));
            assertThat(Files.readAllBytes(Path.of(fileService.getAudiobookThumbnailFile(1L))))
                    .isEqualTo(Files.readAllBytes(legacyFolder.toPath().resolve("audiobook-thumbnail.jpg")));
        } finally {
            image.flush();
        }
    }

    private static Supplier<BufferedImage> rgb(int width, int height) {
        return () -> CoverFixtures.picture(width, height, BufferedImage.TYPE_INT_RGB, width * 31L + height);
    }

    private static Supplier<BufferedImage> bgr(int width, int height) {
        return () -> CoverFixtures.picture(width, height, BufferedImage.TYPE_3BYTE_BGR, width * 31L + height);
    }

    private static Supplier<BufferedImage> tall() {
        return () -> CoverFixtures.tallWithWhiteTop(300, 1500, 200);
    }

    private static Supplier<BufferedImage> wide() {
        return () -> CoverFixtures.wideWithWhiteLeft(1500, 300, 300);
    }

    private static Supplier<BufferedImage> cmyk() {
        return () -> {
            try {
                return LegacyImageDecoding.readImage(CoverFixtures.cmykJpeg(300, 450));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        };
    }
}
