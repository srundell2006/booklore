package org.booklore.util.cover;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.*;
import java.awt.color.ColorSpace;
import java.awt.color.ICC_Profile;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Random;

/**
 * Deterministic images for the cover tests: noisy gradients with shapes, so that scaling, cropping and JPEG encoding
 * all have detail to work on.
 */
final class CoverFixtures {

    private CoverFixtures() {
    }

    static BufferedImage picture(int width, int height, int type, long seed) {
        BufferedImage image = new BufferedImage(width, height, type);
        Random random = new Random(seed);
        int[] row = new int[width];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int r = (x * 255 / Math.max(1, width - 1) + random.nextInt(24)) & 0xFF;
                int g = (y * 255 / Math.max(1, height - 1) + random.nextInt(24)) & 0xFF;
                int b = ((x + y) * 7 + random.nextInt(24)) & 0xFF;
                row[x] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
            image.setRGB(0, y, width, 1, row, 0, width);
        }
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(new Color(20, 40, 160));
            g.fillOval(width / 5, height / 5, width / 2, height / 3);
            g.setColor(new Color(230, 200, 30));
            g.fillRect(width / 3, height / 2, width / 3, height / 6);
        } finally {
            g.dispose();
        }
        return image;
    }

    /** An extremely tall image whose top {@code band} rows are plain white, as smart cropping looks for. */
    static BufferedImage tallWithWhiteTop(int width, int height, int band) {
        BufferedImage image = picture(width, height, BufferedImage.TYPE_INT_RGB, 11);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, width, band);
        } finally {
            g.dispose();
        }
        return image;
    }

    /** An extremely wide image whose left {@code band} columns are plain white. */
    static BufferedImage wideWithWhiteLeft(int width, int height, int band) {
        BufferedImage image = picture(width, height, BufferedImage.TYPE_INT_RGB, 12);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, band, height);
        } finally {
            g.dispose();
        }
        return image;
    }

    static BufferedImage translucent(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.drawImage(picture(width, height, BufferedImage.TYPE_INT_RGB, 13), 0, 0, null);
            g.setComposite(AlphaComposite.Clear);
            g.fillRect(0, 0, width / 4, height);
            g.setComposite(AlphaComposite.Src);
            g.setColor(new Color(255, 0, 0, 100));
            g.fillRect(width / 4, 0, width / 4, height);
        } finally {
            g.dispose();
        }
        return image;
    }

    static BufferedImage gray(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
        WritableRaster raster = image.getRaster();
        Random random = new Random(14);
        int[] row = new int[width];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                row[x] = (x * 127 / Math.max(1, width - 1) + y * 127 / Math.max(1, height - 1) + random.nextInt(8)) & 0xFF;
            }
            raster.setSamples(0, y, width, 1, 0, row);
        }
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(Color.DARK_GRAY);
            g.fillOval(width / 5, height / 5, width / 2, height / 3);
        } finally {
            g.dispose();
        }
        return image;
    }

    static byte[] encode(BufferedImage image, String format) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!ImageIO.write(image, format, out)) {
                throw new IllegalStateException("No " + format + " writer for image type " + image.getType());
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A four-channel (CMYK) JPEG, as written by print workflows; only TwelveMonkeys' reader can decode it. */
    static byte[] cmykJpeg(int width, int height) {
        WritableRaster raster = Raster.createInterleavedRaster(DataBuffer.TYPE_BYTE, width, height, 4, null);
        Random random = new Random(15);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                raster.setSample(x, y, 0, (x * 255 / width + random.nextInt(16)) & 0xFF);
                raster.setSample(x, y, 1, (y * 255 / height + random.nextInt(16)) & 0xFF);
                raster.setSample(x, y, 2, ((x + y) * 3) & 0xFF);
                raster.setSample(x, y, 3, random.nextInt(40));
            }
        }
        try {
            ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (MemoryCacheImageOutputStream stream = new MemoryCacheImageOutputStream(out)) {
                writer.setOutput(stream);
                writer.write(null, new IIOImage(raster, null, null), null);
            } finally {
                writer.dispose();
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** {@code jpeg} with a linear-RGB ICC profile embedded as an APP2 segment right after SOI. */
    static byte[] withLinearRgbProfile(byte[] jpeg) {
        byte[] profile = ICC_Profile.getInstance(ColorSpace.CS_LINEAR_RGB).getData();
        byte[] marker = "ICC_PROFILE\0".getBytes(StandardCharsets.ISO_8859_1);
        int length = 2 + marker.length + 2 + profile.length;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(jpeg, 0, 2);
        out.write(0xFF);
        out.write(0xE2);
        out.write(length >> 8);
        out.write(length & 0xFF);
        out.writeBytes(marker);
        out.write(1);
        out.write(1);
        out.writeBytes(profile);
        out.write(jpeg, 2, jpeg.length - 2);
        return out.toByteArray();
    }

    static void assertSamePixels(BufferedImage expected, BufferedImage actual) {
        if (expected.getWidth() != actual.getWidth() || expected.getHeight() != actual.getHeight()) {
            throw new AssertionError("Size " + actual.getWidth() + "x" + actual.getHeight()
                    + " instead of " + expected.getWidth() + "x" + expected.getHeight());
        }
        if (expected.getType() != actual.getType()) {
            throw new AssertionError("Image type " + actual.getType() + " instead of " + expected.getType());
        }
        int width = expected.getWidth();
        int[] expectedRow = new int[width];
        int[] actualRow = new int[width];
        for (int y = 0; y < expected.getHeight(); y++) {
            expected.getRGB(0, y, width, 1, expectedRow, 0, width);
            actual.getRGB(0, y, width, 1, actualRow, 0, width);
            for (int x = 0; x < width; x++) {
                if (expectedRow[x] != actualRow[x]) {
                    throw new AssertionError(String.format("Pixel (%d,%d) is %08x instead of %08x", x, y, actualRow[x], expectedRow[x]));
                }
            }
        }
    }
}
