package org.booklore.util.cover;

import org.booklore.model.dto.settings.CoverCroppingSettings;
import org.booklore.util.FileService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * CoverImages decodes exactly as the decoder each caller used before: {@code decode(bytes, true)} as
 * FileService.readImage and {@code decode(bytes, false)} as ImageIO.read. {@link LegacyImageDecoding} holds the old
 * decoders as the oracle.
 */
class CoverImagesDecodeTest {

    @TempDir
    Path tempDir;

    static Stream<Arguments> images() {
        return Stream.of(
                arguments("png rgb", encoded(() -> CoverFixtures.picture(300, 450, BufferedImage.TYPE_INT_RGB, 1), "png")),
                arguments("png with alpha", encoded(() -> CoverFixtures.translucent(300, 450), "png")),
                arguments("png grayscale", encoded(() -> CoverFixtures.gray(300, 450), "png")),
                arguments("gif (indexed)", encoded(() -> CoverFixtures.picture(300, 450, BufferedImage.TYPE_INT_RGB, 2), "gif")),
                arguments("bmp", encoded(() -> CoverFixtures.picture(300, 450, BufferedImage.TYPE_INT_RGB, 3), "bmp")),
                arguments("jpeg rgb", encoded(() -> CoverFixtures.picture(300, 450, BufferedImage.TYPE_INT_RGB, 4), "jpeg")),
                arguments("jpeg grayscale", encoded(() -> CoverFixtures.gray(300, 450), "jpeg")),
                arguments("jpeg cmyk", (Supplier<byte[]>) () -> CoverFixtures.cmykJpeg(300, 450)),
                arguments("jpeg with icc profile", (Supplier<byte[]>) () -> CoverFixtures.withLinearRgbProfile(
                        CoverFixtures.encode(CoverFixtures.picture(300, 450, BufferedImage.TYPE_INT_RGB, 5), "jpeg"))),
                arguments("cmyk jpeg with icc profile", (Supplier<byte[]>) () -> CoverFixtures.withLinearRgbProfile(CoverFixtures.cmykJpeg(300, 450))),
                arguments("jpeg of exactly 20 MP", encoded(() -> CoverFixtures.gray(4000, 5000), "jpeg")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("images")
    void limitedDecodeIsReadImage(String name, Supplier<byte[]> image) throws IOException {
        byte[] bytes = image.get();
        BufferedImage expected = LegacyImageDecoding.readImage(bytes);
        BufferedImage actual = CoverImages.decode(bytes, true);

        CoverFixtures.assertSamePixels(expected, actual);
        CoverFixtures.assertSamePixels(expected, FileService.readImage(bytes));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("images")
    void unlimitedDecodeIsImageIoRead(String name, Supplier<byte[]> image) throws IOException {
        byte[] bytes = image.get();
        BufferedImage expected = LegacyImageDecoding.imageIoRead(bytes);
        BufferedImage actual = CoverImages.decode(bytes, false);

        CoverFixtures.assertSamePixels(expected, actual);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("images")
    void probeReadsTheSize(String name, Supplier<byte[]> image) throws IOException {
        byte[] bytes = image.get();
        BufferedImage decoded = LegacyImageDecoding.imageIoRead(bytes);

        CoverImages.Probe probe = CoverImages.probe(bytes);

        assertThat(probe.width()).isEqualTo(decoded.getWidth());
        assertThat(probe.height()).isEqualTo(decoded.getHeight());
        assertThat(probe.decodedPixels()).isEqualTo((long) decoded.getWidth() * decoded.getHeight());
    }

    @Test
    void overTwentyMegapixelsLimitedDecodeRejectsAndUnlimitedDecodesInFull() throws IOException {
        // 4500x4500 is 20.25 MP; grayscale keeps the decoded images at 20 MB each
        byte[] bytes = CoverFixtures.encode(CoverFixtures.gray(4500, 4500), "png");

        IOException legacy = null;
        try {
            LegacyImageDecoding.readImage(bytes);
        } catch (IOException e) {
            legacy = e;
        }
        assertThat(legacy).isNotNull();
        assertThatThrownBy(() -> CoverImages.decode(bytes, true))
                .isInstanceOf(IOException.class)
                .hasMessage(legacy.getMessage())
                .hasMessageStartingWith("Rejected image: dimensions 4500x4500 (20250000 pixels) exceed limit 20000000");
        assertThatThrownBy(() -> FileService.readImage(bytes)).hasMessage(legacy.getMessage());

        BufferedImage expected = LegacyImageDecoding.imageIoRead(bytes);
        BufferedImage actual = CoverImages.decode(bytes, false);
        assertThat(actual.getWidth()).isEqualTo(4500);
        CoverFixtures.assertSamePixels(expected, actual);
    }

    @Test
    void probeNeedsOnlyTheHeader() throws IOException {
        byte[] png = CoverFixtures.encode(CoverFixtures.picture(300, 450, BufferedImage.TYPE_INT_RGB, 6), "png");
        byte[] headerOnly = Arrays.copyOf(png, 100);

        assertThat(CoverImages.probe(headerOnly)).isEqualTo(new CoverImages.Probe(300, 450));
        assertThatThrownBy(() -> CoverImages.decode(headerOnly, false)).isInstanceOf(IOException.class);
    }

    @Test
    void garbageIsAnIoException() {
        byte[] garbage = "this is not an image at all".getBytes();

        assertThatThrownBy(() -> CoverImages.decode(garbage, true))
                .isInstanceOf(IOException.class).hasMessage("Unable to decode image, likely unsupported format");
        assertThatThrownBy(() -> CoverImages.decode(garbage, false)).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> CoverImages.probe(garbage)).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> CoverImages.decode(null, false)).isInstanceOf(IOException.class)
                .hasMessage("Image data is null or empty");
        assertThatThrownBy(() -> CoverImages.decode(new byte[0], true)).isInstanceOf(IOException.class)
                .hasMessage("Image data is null or empty");
    }

    static Stream<Arguments> decodabilityCases() {
        // Over a megapixel, so canDecode keeps only a sample of them
        Supplier<byte[]> bigJpeg = once(encoded(() -> CoverFixtures.picture(3000, 4500, BufferedImage.TYPE_3BYTE_BGR, 7), "jpeg"));
        Supplier<byte[]> bigPng = once(encoded(() -> CoverFixtures.picture(1600, 2000, BufferedImage.TYPE_INT_RGB, 8), "png"));
        Supplier<byte[]> smallPng = once(encoded(() -> CoverFixtures.picture(300, 450, BufferedImage.TYPE_INT_RGB, 9), "png"));
        Supplier<byte[]> smallJpeg = once(encoded(() -> CoverFixtures.picture(300, 450, BufferedImage.TYPE_INT_RGB, 10), "jpeg"));
        return Stream.concat(
                images(),
                Stream.of(
                        arguments("large jpeg", bigJpeg),
                        arguments("large png", bigPng),
                        arguments("large png cut off before its last rows", truncated(bigPng, 0.97)),
                        arguments("large png cut off half way", truncated(bigPng, 0.5)),
                        arguments("large jpeg cut off half way", truncated(bigJpeg, 0.5)),
                        arguments("png cut off half way", truncated(smallPng, 0.5)),
                        arguments("png header only", truncated(smallPng, 0.02)),
                        arguments("jpeg cut off half way", truncated(smallJpeg, 0.5)),
                        arguments("garbage", (Supplier<byte[]>) () -> "not an image".getBytes()),
                        arguments("empty", (Supplier<byte[]>) () -> new byte[0]),
                        arguments("null", (Supplier<byte[]>) () -> null)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("decodabilityCases")
    void canDecodeAnswersAsAFullImageIoReadDid(String name, Supplier<byte[]> image) {
        byte[] bytes = image.get();
        boolean expected = LegacyImageDecoding.canDecode(bytes);

        boolean actual;
        try {
            actual = CoverImages.canDecode(bytes);
        } catch (IOException e) {
            actual = false;
        }

        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void largePngMissingOnlyItsLastRowsIsNotDecodable() throws IOException {
        byte[] png = CoverFixtures.encode(CoverFixtures.picture(1600, 2000, BufferedImage.TYPE_INT_RGB, 8), "png");
        byte[] cutOff = Arrays.copyOf(png, png.length - png.length / 50);

        assertThat(LegacyImageDecoding.canDecode(cutOff)).isFalse();
        assertThatThrownBy(() -> CoverImages.canDecode(cutOff)).isInstanceOf(IOException.class);
        assertThat(CoverImages.canDecode(png)).isTrue();
    }

    @Test
    void imageIoCreatesNoCacheFiles() throws IOException {
        byte[] jpeg = CoverFixtures.encode(CoverFixtures.picture(200, 300, BufferedImage.TYPE_INT_RGB, 11), "jpeg");
        byte[] png = CoverFixtures.encode(CoverFixtures.translucent(120, 180), "png");
        File cacheDirectory = Files.createDirectory(tempDir.resolve("imageio-cache")).toFile();
        boolean useCache = ImageIO.getUseCache();
        File previousDirectory = ImageIO.getCacheDirectory();
        try {
            ImageIO.setUseCache(true);
            ImageIO.setCacheDirectory(cacheDirectory);
            // With its cache directory gone, any ImageIO stream that caches to a file fails to open
            Files.delete(cacheDirectory.toPath());
            assertThatThrownBy(() -> LegacyImageDecoding.imageIoRead(jpeg)).isInstanceOf(IllegalArgumentException.class);

            for (int i = 0; i < 100; i++) {
                byte[] bytes = i % 2 == 0 ? jpeg : png;
                BufferedImage limited = CoverImages.decode(bytes, true);
                BufferedImage unlimited = CoverImages.decode(bytes, false);
                assertThat(CoverImages.probe(bytes).width()).isEqualTo(limited.getWidth());
                assertThat(CoverImages.canDecode(bytes)).isTrue();
                assertThat(CoverImages.renderBookCover(limited, CropPolicy.NONE)).isNotNull();
                assertThat(CoverImages.renderAudiobookCover(unlimited)).isNotNull();
                assertThat(FileService.readImage(new ByteArrayInputStream(bytes))).isNotNull();
                limited.flush();
                unlimited.flush();
            }
        } finally {
            ImageIO.setCacheDirectory(previousDirectory);
            ImageIO.setUseCache(useCache);
        }
    }

    @Test
    void renderEstimateGrowsWithThePixels() {
        assertThat(CoverImages.renderBytes(0)).isEqualTo(16L * 1024 * 1024);
        assertThat(CoverImages.renderBytes(1_000_000)).isEqualTo(12_000_000L + 16L * 1024 * 1024);
    }

    @Test
    void cropPolicyFromSettings() {
        assertThat(CropPolicy.from(null)).isEqualTo(CropPolicy.NONE);
        assertThat(CropPolicy.from(new CoverCroppingSettings(true, false, 2.5, true)))
                .isEqualTo(new CropPolicy(true, false, 2.5, true));
    }

    private static Supplier<byte[]> encoded(Supplier<BufferedImage> image, String format) {
        return () -> {
            BufferedImage source = image.get();
            try {
                return CoverFixtures.encode(source, format);
            } finally {
                source.flush();
            }
        };
    }

    private static Supplier<byte[]> once(Supplier<byte[]> image) {
        return new Supplier<>() {
            private byte[] bytes;

            @Override
            public byte[] get() {
                if (bytes == null) {
                    bytes = image.get();
                }
                return bytes;
            }
        };
    }

    private static Supplier<byte[]> truncated(Supplier<byte[]> image, double keep) {
        return () -> {
            byte[] bytes = image.get();
            return Arrays.copyOf(bytes, (int) (bytes.length * keep));
        };
    }
}
