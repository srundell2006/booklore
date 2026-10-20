package org.booklore.service.fileprocessor.prepare;

import org.booklore.util.cover.CoverImages;
import org.booklore.util.cover.CoverKind;
import org.booklore.util.cover.CropPolicy;
import org.booklore.util.cover.RenderedCover;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CoverMakerTest {

    private static final CropPolicy TALL_CROP = new CropPolicy(true, true, 1.5, false);

    private final RecordingContext ctx = new RecordingContext();

    @Test
    void bookCover_isWhatTheStoredBookCoverAlwaysWas() throws Exception {
        byte[] jpeg = jpeg(120, 400);

        RenderedCover cover = new CoverMaker(ctx, CoverKind.BOOK, TALL_CROP).fromEncoded(jpeg, false);

        RenderedCover expected = CoverImages.renderBookCover(CoverImages.decode(jpeg, false), TALL_CROP);
        assertThat(cover.kind()).isEqualTo(CoverKind.BOOK);
        assertThat(cover.cover()).isEqualTo(expected.cover());
        assertThat(cover.thumbnail()).isEqualTo(expected.thumbnail());
        assertThat(ctx.estimates).containsExactly(CoverImages.renderBytes(120 * 400));
    }

    @Test
    void audiobookCover_isWhatTheStoredAudiobookCoverAlwaysWas() throws Exception {
        byte[] jpeg = jpeg(300, 200);

        RenderedCover cover = new CoverMaker(ctx, CoverKind.AUDIOBOOK, TALL_CROP).fromEncoded(jpeg, true);

        RenderedCover expected = CoverImages.renderAudiobookCover(CoverImages.decode(jpeg, true));
        assertThat(cover.kind()).isEqualTo(CoverKind.AUDIOBOOK);
        assertThat(cover.cover()).isEqualTo(expected.cover());
        assertThat(cover.thumbnail()).isEqualTo(expected.thumbnail());
    }

    @Test
    void noBytes_noCoverAndNoRender() throws Exception {
        CoverMaker covers = new CoverMaker(ctx, CoverKind.BOOK, CropPolicy.NONE);

        assertThat(covers.fromEncoded(null, true)).isNull();
        assertThat(covers.fromEncoded(new byte[0], true)).isNull();
        assertThat(ctx.estimates).isEmpty();
    }

    @Test
    void unrecognisedBytes_noCoverAndNoRender() throws Exception {
        byte[] garbage = "not an image".getBytes(StandardCharsets.UTF_8);

        assertThat(new CoverMaker(ctx, CoverKind.BOOK, CropPolicy.NONE).fromEncoded(garbage, true)).isNull();
        assertThat(ctx.estimates).isEmpty();
        assertThatThrownBy(() -> new CoverMaker(ctx, CoverKind.BOOK, CropPolicy.NONE).fromEncodedOrThrow(garbage, true))
                .isInstanceOf(IOException.class);
    }

    @Test
    void imageOverTheDecodeLimit_isRejectedWhenLimitedAsFileServiceReadImageRejectsIt() throws Exception {
        // Only the header: 5000x5000 is past the 20 MP limit, which is checked before any pixel is read
        byte[] huge = pngHeader(5000, 5000);
        CoverMaker covers = new CoverMaker(ctx, CoverKind.BOOK, CropPolicy.NONE);

        assertThat(covers.fromEncoded(huge, true)).isNull();
        assertThatThrownBy(() -> covers.fromEncodedOrThrow(huge, true))
                .isInstanceOf(IOException.class)
                .hasMessage("Rejected image: dimensions 5000x5000 (25000000 pixels) exceed limit 20000000 — possible decompression bomb");
        // Rejected on its header alone, as decoding rejects it, so no render permit or heap is waited for
        assertThat(ctx.estimates).isEmpty();
        assertThatThrownBy(() -> CoverImages.decode(huge, true)).hasMessage(
                "Rejected image: dimensions 5000x5000 (25000000 pixels) exceed limit 20000000 — possible decompression bomb");
    }

    @Test
    void imageOverTheDecodeLimit_isStillDecodedInFullWhenNotLimitedAsImageIoReadDecodesIt() throws Exception {
        byte[] huge = pngHeader(5000, 5000);

        // The header has no pixel data behind it, so the full decode fails, inside the render
        assertThat(new CoverMaker(ctx, CoverKind.BOOK, CropPolicy.NONE).fromEncoded(huge, false)).isNull();
        assertThat(ctx.estimates).containsExactly(CoverImages.renderBytes(25_000_000L));
    }

    @Test
    void fromPixels_rendersTheRasterWithinTheRasterisersEstimate() throws Exception {
        BufferedImage page = image(200, 300);

        RenderedCover cover = new CoverMaker(ctx, CoverKind.BOOK, CropPolicy.NONE).fromPixels(60_000, 1000, () -> page);

        assertThat(cover.cover()).isEqualTo(CoverImages.renderBookCover(image(200, 300), CropPolicy.NONE).cover());
        assertThat(ctx.estimates).containsExactly(12 * 60_000L + 1000);
    }

    @Test
    void fromPixels_rasteriserWithoutImage_noCover() throws Exception {
        assertThat(new CoverMaker(ctx, CoverKind.BOOK, CropPolicy.NONE).fromPixels(1, 0, () -> null)).isNull();
    }

    @Test
    void contextThatGivesUp_endsTheRender() {
        PrepareContext deadline = new RecordingContext() {
            @Override
            public <T> T render(long estimatedBytes, IoCallable<T> work) {
                throw new PrepareDeadlineException("no render permit in time");
            }
        };

        assertThatThrownBy(() -> new CoverMaker(deadline, CoverKind.BOOK, CropPolicy.NONE).fromEncoded(jpeg(10, 10), true))
                .isInstanceOf(PrepareDeadlineException.class);
    }

    private static class RecordingContext implements PrepareContext {
        final List<Long> estimates = new ArrayList<>();

        @Override
        public void checkpoint() {
        }

        @Override
        public <T> T render(long estimatedBytes, IoCallable<T> work) throws Exception {
            estimates.add(estimatedBytes);
            return work.call();
        }

        @Override
        public HeapReservation reserveHeap(long bytes) {
            return () -> {
            };
        }

        @Override
        public void registerProcess(Process process) {
        }
    }

    private static BufferedImage image(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.ORANGE);
        g.fillRect(0, 0, width, height);
        g.setColor(Color.BLUE);
        g.fillOval(width / 4, height / 4, width / 2, height / 2);
        g.dispose();
        return image;
    }

    private static byte[] jpeg(int width, int height) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image(width, height), "jpg", out);
        return out.toByteArray();
    }

    /** A PNG with a valid header for an RGB image of this size and no pixel data. */
    private static byte[] pngHeader(int width, int height) {
        ByteBuffer ihdr = ByteBuffer.allocate(13).putInt(width).putInt(height).put((byte) 8).put((byte) 2)
                .put((byte) 0).put((byte) 0).put((byte) 0);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});
        chunk(out, "IHDR", ihdr.array());
        chunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    private static void chunk(ByteArrayOutputStream out, String type, byte[] data) {
        byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        out.writeBytes(ByteBuffer.allocate(4).putInt(data.length).array());
        out.writeBytes(typeBytes);
        out.writeBytes(data);
        out.writeBytes(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
    }
}
