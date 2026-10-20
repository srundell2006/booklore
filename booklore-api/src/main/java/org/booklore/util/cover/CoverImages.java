package org.booklore.util.cover;

import lombok.extern.slf4j.Slf4j;
import org.booklore.util.FileService;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;

/**
 * Decoding cover images and rendering the stored cover and thumbnail from them, all in memory: no files are read or
 * written, and no ImageIO cache files are created. The pixel pipelines are the ones FileService.saveCoverImages and
 * saveAudiobookCoverImages always used, so covers come out byte for byte the same.
 */
@Slf4j
public final class CoverImages {

    /** The size above which {@link FileService#readImage(byte[])} rejects an image as a possible decompression bomb. */
    public static final long MAX_DECODE_PIXELS = 20_000_000L;

    // At most this much of an image is kept while canDecode checks that it decodes
    private static final long CHECK_DECODE_PIXELS = 1_000_000L;

    private static final double TARGET_COVER_ASPECT_RATIO = 1.5;
    private static final int SMART_CROP_COLOR_TOLERANCE = 30;
    private static final double SMART_CROP_MARGIN_PERCENT = 0.02;

    // @formatter:off
    private static final int    THUMBNAIL_WIDTH       = 250;
    private static final int    THUMBNAIL_HEIGHT      = 350;
    private static final int    SQUARE_THUMBNAIL_SIZE = 250;
    private static final int    MAX_ORIGINAL_WIDTH    = 1000;
    private static final int    MAX_ORIGINAL_HEIGHT   = 1500;
    private static final int    MAX_SQUARE_SIZE       = 1000;
    private static final String IMAGE_FORMAT          = "JPEG";
    // @formatter:on

    private CoverImages() {
    }

    /** An image's size as its header gives it. */
    public record Probe(int width, int height) {
        public long decodedPixels() {
            return (long) width * height;
        }
    }

    /**
     * Reads only the header of an image.
     *
     * @throws IOException when no reader recognises the data or the header cannot be read
     */
    public static Probe probe(byte[] encoded) throws IOException {
        if (encoded == null || encoded.length == 0) {
            throw new IOException("Image data is null or empty");
        }
        try (ImageInputStream iis = memoryStream(encoded)) {
            ImageReader reader = firstReader(iis);
            try {
                reader.setInput(iis, true, true);
                return new Probe(reader.getWidth(0), reader.getHeight(0));
            } finally {
                reader.dispose();
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("ImageIO decode failed (possibly unsupported format): " + e.getMessage(), e);
        }
    }

    /**
     * Decodes an image the way its caller decoded it before this class, so the pixels stay the same.
     *
     * @param limitPixels true decodes as {@link FileService#readImage(byte[])} always has: with the image's metadata,
     *                    rejecting images over {@link #MAX_DECODE_PIXELS}. False decodes as {@code ImageIO.read}: metadata
     *                    ignored, any size.
     * @throws IOException when no reader recognises the data, the image cannot be decoded, or it is rejected
     */
    public static BufferedImage decode(byte[] encoded, boolean limitPixels) throws IOException {
        if (encoded == null || encoded.length == 0) {
            throw new IOException("Image data is null or empty");
        }
        try (ImageInputStream iis = memoryStream(encoded)) {
            ImageReader reader = firstReader(iis);
            try {
                if (!limitPixels) {
                    reader.setInput(iis, true, true);
                    return reader.read(0, reader.getDefaultReadParam());
                }
                reader.setInput(iis);
                checkPixelLimit(new Probe(reader.getWidth(0), reader.getHeight(0)));
                return reader.read(0);
            } finally {
                reader.dispose();
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("ImageIO decode failed (possibly unsupported format): " + e.getMessage(), e);
        }
    }

    /**
     * Rejects an image too large for {@link #decode} with {@code limitPixels}, with the same exception, so a caller that
     * has probed the image can reject it before reserving anything to decode it.
     *
     * @throws IOException when the image has more than {@link #MAX_DECODE_PIXELS} pixels
     */
    public static void checkPixelLimit(Probe probe) throws IOException {
        long pixelCount = probe.decodedPixels();
        if (pixelCount > MAX_DECODE_PIXELS) {
            throw new IOException(String.format("Rejected image: dimensions %dx%d (%d pixels) exceed limit %d — possible decompression bomb",
                    probe.width(), probe.height(), pixelCount, MAX_DECODE_PIXELS));
        }
    }

    /**
     * Whether {@code ImageIO.read} would decode the image, answered without holding all of it: the reader still decodes
     * the image to its last row and column, but keeps only an evenly spaced sample of about a megapixel. Like
     * {@code ImageIO.read} it ignores metadata, gives false when no reader recognises the data, and lets a reader's
     * unchecked exceptions through.
     *
     * @throws IOException when the image cannot be decoded
     */
    public static boolean canDecode(byte[] encoded) throws IOException {
        if (encoded == null || encoded.length == 0) {
            return false;
        }
        try (ImageInputStream iis = memoryStream(encoded)) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) {
                return false;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(iis, true, true);
                ImageReadParam param = reader.getDefaultReadParam();
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                long pixels = (long) width * height;
                if (pixels > CHECK_DECODE_PIXELS) {
                    int step = (int) Math.ceil(Math.sqrt((double) pixels / CHECK_DECODE_PIXELS));
                    // Offset the sample grid so it takes the last row and column, and the reader decodes to the end
                    param.setSourceSubsampling(step, step, (width - 1) % step, (height - 1) % step);
                }
                reader.read(0, param).flush();
                return true;
            } finally {
                reader.dispose();
            }
        }
    }

    /** Heap a decode, render and encode of an image with this many pixels is estimated to need. */
    public static long renderBytes(long decodedPixels) {
        return 12 * decodedPixels + 16L * 1024 * 1024;
    }

    /**
     * The book cover and thumbnail FileService.saveCoverImages stores: flattened onto white, cropped by {@code crop}
     * when extremely tall or wide, scaled down to fit 1000x1500, and a 250x350 thumbnail (250x250 for square-ish
     * covers). The caller keeps ownership of {@code coverImage}.
     *
     * @return null when no JPEG writer is available
     */
    public static RenderedCover renderBookCover(BufferedImage coverImage, CropPolicy crop) throws IOException {
        BufferedImage rgbImage = null;
        BufferedImage cropped = null;
        BufferedImage resized = null;
        BufferedImage thumb = null;
        try {
            rgbImage = new BufferedImage(
                    coverImage.getWidth(),
                    coverImage.getHeight(),
                    BufferedImage.TYPE_INT_RGB
            );
            Graphics2D g = rgbImage.createGraphics();
            g.drawImage(coverImage, 0, 0, Color.WHITE, null);
            g.dispose();

            cropped = applyCoverCropping(rgbImage, crop);
            if (cropped != rgbImage) {
                rgbImage.flush();
                rgbImage = cropped;
            }

            // Resize original image if too large to prevent OOM
            double scale = Math.min(
                    (double) MAX_ORIGINAL_WIDTH / rgbImage.getWidth(),
                    (double) MAX_ORIGINAL_HEIGHT / rgbImage.getHeight()
            );
            if (scale < 1.0) {
                resized = FileService.resizeImage(rgbImage, (int) (rgbImage.getWidth() * scale), (int) (rgbImage.getHeight() * scale));
                rgbImage.flush(); // Release resources of the original large image
                rgbImage = resized;
            }

            byte[] cover = encodeJpeg(rgbImage);

            // Determine thumbnail dimensions based on source aspect ratio
            int thumbWidth, thumbHeight;
            double aspectRatio = (double) rgbImage.getWidth() / rgbImage.getHeight();
            if (aspectRatio >= 0.85 && aspectRatio <= 1.15) {
                // Square-ish image (e.g., audiobook covers) - keep square
                thumbWidth = THUMBNAIL_WIDTH;
                thumbHeight = THUMBNAIL_WIDTH;
            } else {
                // Portrait/landscape - use standard dimensions
                thumbWidth = THUMBNAIL_WIDTH;
                thumbHeight = THUMBNAIL_HEIGHT;
            }
            thumb = FileService.resizeImage(rgbImage, thumbWidth, thumbHeight);
            byte[] thumbnail = encodeJpeg(thumb);

            return cover == null || thumbnail == null ? null : new RenderedCover(CoverKind.BOOK, cover, thumbnail);
        } finally {
            // Note: cropped/resized may equal rgbImage after reassignment, avoid double-flush
            if (rgbImage != null) {
                rgbImage.flush();
            }
            if (cropped != null && cropped != rgbImage) {
                cropped.flush();
            }
            if (resized != null && resized != rgbImage) {
                resized.flush();
            }
            if (thumb != null) {
                thumb.flush();
            }
        }
    }

    /**
     * The audiobook cover and thumbnail FileService.saveAudiobookCoverImages stores: flattened onto white, the centred
     * square, scaled down to at most 1000x1000, and a 250x250 thumbnail. The caller keeps ownership of
     * {@code coverImage}.
     *
     * @return null when no JPEG writer is available
     */
    public static RenderedCover renderAudiobookCover(BufferedImage coverImage) throws IOException {
        BufferedImage rgbImage = null;
        BufferedImage resized = null;
        BufferedImage thumb = null;
        try {
            rgbImage = new BufferedImage(
                    coverImage.getWidth(),
                    coverImage.getHeight(),
                    BufferedImage.TYPE_INT_RGB
            );
            Graphics2D g = rgbImage.createGraphics();
            g.drawImage(coverImage, 0, 0, Color.WHITE, null);
            g.dispose();

            // Resize to square if needed, maintaining 1:1 aspect ratio
            int size = Math.min(rgbImage.getWidth(), rgbImage.getHeight());
            int x = (rgbImage.getWidth() - size) / 2;
            int y = (rgbImage.getHeight() - size) / 2;
            BufferedImage cropped = rgbImage.getSubimage(x, y, size, size);

            // Resize if too large
            if (size > MAX_SQUARE_SIZE) {
                resized = FileService.resizeImage(cropped, MAX_SQUARE_SIZE, MAX_SQUARE_SIZE);
            } else {
                resized = cropped;
            }

            byte[] cover = encodeJpeg(resized);

            // Create square thumbnail
            thumb = FileService.resizeImage(resized, SQUARE_THUMBNAIL_SIZE, SQUARE_THUMBNAIL_SIZE);
            byte[] thumbnail = encodeJpeg(thumb);

            return cover == null || thumbnail == null ? null : new RenderedCover(CoverKind.AUDIOBOOK, cover, thumbnail);
        } finally {
            if (rgbImage != null) {
                rgbImage.flush();
            }
            if (resized != null && resized != rgbImage) {
                resized.flush();
            }
            if (thumb != null) {
                thumb.flush();
            }
        }
    }

    /**
     * The bytes {@code ImageIO.write(image, "JPEG", file)} writes to a file: the same writer with its default
     * parameters, into memory.
     *
     * @return null when no JPEG writer can encode the image
     */
    static byte[] encodeJpeg(BufferedImage image) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        boolean written;
        try (MemoryCacheImageOutputStream out = new MemoryCacheImageOutputStream(baos)) {
            written = ImageIO.write(image, IMAGE_FORMAT, out);
        }
        return written ? baos.toByteArray() : null;
    }

    private static ImageInputStream memoryStream(byte[] encoded) {
        return new MemoryCacheImageInputStream(new ByteArrayInputStream(encoded));
    }

    private static ImageReader firstReader(ImageInputStream iis) throws IOException {
        Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
        if (!readers.hasNext()) {
            throw new IOException("Unable to decode image, likely unsupported format");
        }
        return readers.next();
    }

    private static BufferedImage applyCoverCropping(BufferedImage image, CropPolicy crop) {
        int width = image.getWidth();
        int height = image.getHeight();
        double heightToWidthRatio = (double) height / width;
        double widthToHeightRatio = (double) width / height;
        double threshold = crop.threshold();
        boolean smartCrop = crop.smart();

        boolean isExtremelyTall = crop.vertical() && heightToWidthRatio > threshold;
        if (isExtremelyTall) {
            int croppedHeight = (int) (width * TARGET_COVER_ASPECT_RATIO);
            log.debug("Cropping tall image: {}x{} (ratio {}) -> {}x{}, smartCrop={}",
                    width, height, String.format("%.2f", heightToWidthRatio), width, croppedHeight, smartCrop);
            return cropFromTop(image, width, croppedHeight, smartCrop);
        }

        boolean isExtremelyWide = crop.horizontal() && widthToHeightRatio > threshold;
        if (isExtremelyWide) {
            int croppedWidth = (int) (height / TARGET_COVER_ASPECT_RATIO);
            log.debug("Cropping wide image: {}x{} (ratio {}) -> {}x{}, smartCrop={}",
                    width, height, String.format("%.2f", widthToHeightRatio), croppedWidth, height, smartCrop);
            return cropFromLeft(image, croppedWidth, height, smartCrop);
        }

        return image;
    }

    private static BufferedImage cropFromTop(BufferedImage image, int targetWidth, int targetHeight, boolean smartCrop) {
        int startY = 0;
        if (smartCrop) {
            int contentStartY = findContentStartY(image);
            int margin = (int) (targetHeight * SMART_CROP_MARGIN_PERCENT);
            startY = Math.max(0, contentStartY - margin);

            int maxStartY = image.getHeight() - targetHeight;
            startY = Math.min(startY, maxStartY);
        }
        return image.getSubimage(0, startY, targetWidth, targetHeight);
    }

    private static BufferedImage cropFromLeft(BufferedImage image, int targetWidth, int targetHeight, boolean smartCrop) {
        int startX = 0;
        if (smartCrop) {
            int contentStartX = findContentStartX(image);
            int margin = (int) (targetWidth * SMART_CROP_MARGIN_PERCENT);
            startX = Math.max(0, contentStartX - margin);

            int maxStartX = image.getWidth() - targetWidth;
            startX = Math.min(startX, maxStartX);
        }
        return image.getSubimage(startX, 0, targetWidth, targetHeight);
    }

    private static int findContentStartY(BufferedImage image) {
        for (int y = 0; y < image.getHeight(); y++) {
            if (!isRowUniformColor(image, y)) {
                return y;
            }
        }
        return 0;
    }

    private static int findContentStartX(BufferedImage image) {
        for (int x = 0; x < image.getWidth(); x++) {
            if (!isColumnUniformColor(image, x)) {
                return x;
            }
        }
        return 0;
    }

    private static boolean isRowUniformColor(BufferedImage image, int y) {
        int firstPixel = image.getRGB(0, y);
        for (int x = 1; x < image.getWidth(); x++) {
            if (!colorsAreSimilar(firstPixel, image.getRGB(x, y))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isColumnUniformColor(BufferedImage image, int x) {
        int firstPixel = image.getRGB(x, 0);
        for (int y = 1; y < image.getHeight(); y++) {
            if (!colorsAreSimilar(firstPixel, image.getRGB(x, y))) {
                return false;
            }
        }
        return true;
    }

    private static boolean colorsAreSimilar(int rgb1, int rgb2) {
        int r1 = (rgb1 >> 16) & 0xFF, g1 = (rgb1 >> 8) & 0xFF, b1 = rgb1 & 0xFF;
        int r2 = (rgb2 >> 16) & 0xFF, g2 = (rgb2 >> 8) & 0xFF, b2 = rgb2 & 0xFF;
        return Math.abs(r1 - r2) <= SMART_CROP_COLOR_TOLERANCE
                && Math.abs(g1 - g2) <= SMART_CROP_COLOR_TOLERANCE
                && Math.abs(b1 - b2) <= SMART_CROP_COLOR_TOLERANCE;
    }
}
