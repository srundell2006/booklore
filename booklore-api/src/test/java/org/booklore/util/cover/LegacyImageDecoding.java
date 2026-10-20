package org.booklore.util.cover;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;

/**
 * Oracle for the decode tests: the image decoders callers used before {@link CoverImages}, copied verbatim.
 * Do not "fix" this class; it pins the old output.
 */
final class LegacyImageDecoding {

    private static final long MAX_IMAGE_PIXELS = 20_000_000L;

    private LegacyImageDecoding() {
    }

    /** FileService.readImage(byte[]) before it moved to in-memory streams. */
    static BufferedImage readImage(byte[] imageData) throws IOException {
        if (imageData == null || imageData.length == 0) {
            throw new IOException("Image data is null or empty");
        }

        try (ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(imageData))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (readers.hasNext()) {
                ImageReader reader = readers.next();
                try {
                    reader.setInput(iis);
                    int width = reader.getWidth(0);
                    int height = reader.getHeight(0);

                    long pixelCount = (long) width * height;
                    if (pixelCount > MAX_IMAGE_PIXELS) {
                        throw new IOException(String.format("Rejected image: dimensions %dx%d (%d pixels) exceed limit %d — possible decompression bomb",
                                width, height, pixelCount, MAX_IMAGE_PIXELS));
                    }

                    return reader.read(0);
                } finally {
                    reader.dispose();
                }
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("ImageIO decode failed (possibly unsupported format): " + e.getMessage(), e);
        }

        throw new IOException("Unable to decode image, likely unsupported format");
    }

    /** What the EPUB cover and ebook folder image paths decoded with. */
    static BufferedImage imageIoRead(byte[] bytes) throws IOException {
        try (ByteArrayInputStream bais = new ByteArrayInputStream(bytes)) {
            return ImageIO.read(bais);
        }
    }

    /** CbxMetadataExtractor.canDecode before it stopped decoding the whole image. */
    static boolean canDecode(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return false;
        try (ByteArrayInputStream bais = new ByteArrayInputStream(bytes)) {
            BufferedImage img = ImageIO.read(bais);
            return img != null;
        } catch (IOException e) {
            return false;
        }
    }
}
