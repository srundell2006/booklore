package org.booklore.util.cover;

import org.booklore.model.dto.settings.CoverCroppingSettings;
import org.booklore.util.FileService;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

/**
 * Oracle for the cover tests: FileService.saveCoverImages and saveAudiobookCoverImages exactly as they were before
 * their pixel pipelines moved to {@link CoverImages}, writing into a given folder with given crop settings instead of
 * the book's images folder and the app settings, and without the debug logging. Do not "fix" this class; it pins
 * the old output.
 */
final class LegacyCoverWriter {

    private static final double TARGET_COVER_ASPECT_RATIO = 1.5;
    private static final int SMART_CROP_COLOR_TOLERANCE = 30;
    private static final double SMART_CROP_MARGIN_PERCENT = 0.02;
    private static final String THUMBNAIL_FILENAME = "thumbnail.jpg";
    private static final String COVER_FILENAME = "cover.jpg";
    private static final String AUDIOBOOK_THUMBNAIL_FILENAME = "audiobook-thumbnail.jpg";
    private static final String AUDIOBOOK_COVER_FILENAME = "audiobook-cover.jpg";
    private static final int THUMBNAIL_WIDTH = 250;
    private static final int THUMBNAIL_HEIGHT = 350;
    private static final int SQUARE_THUMBNAIL_SIZE = 250;
    private static final int MAX_ORIGINAL_WIDTH = 1000;
    private static final int MAX_ORIGINAL_HEIGHT = 1500;
    private static final int MAX_SQUARE_SIZE = 1000;
    private static final String IMAGE_FORMAT = "JPEG";

    private LegacyCoverWriter() {
    }

    static boolean saveAudiobookCoverImages(BufferedImage coverImage, File folder) throws IOException {
        BufferedImage rgbImage = null;
        BufferedImage resized = null;
        BufferedImage thumb = null;
        try {
            if (!folder.exists() && !folder.mkdirs()) {
                throw new IOException("Failed to create directory: " + folder.getAbsolutePath());
            }

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

            File originalFile = new File(folder, AUDIOBOOK_COVER_FILENAME);
            boolean originalSaved = ImageIO.write(resized, IMAGE_FORMAT, originalFile);

            // Create square thumbnail
            thumb = FileService.resizeImage(resized, SQUARE_THUMBNAIL_SIZE, SQUARE_THUMBNAIL_SIZE);
            File thumbnailFile = new File(folder, AUDIOBOOK_THUMBNAIL_FILENAME);
            boolean thumbnailSaved = ImageIO.write(thumb, IMAGE_FORMAT, thumbnailFile);

            return originalSaved && thumbnailSaved;
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

    static boolean saveCoverImages(BufferedImage coverImage, File folder, CoverCroppingSettings settings) throws IOException {
        BufferedImage rgbImage = null;
        BufferedImage cropped = null;
        BufferedImage resized = null;
        BufferedImage thumb = null;
        try {
            if (!folder.exists() && !folder.mkdirs()) {
                throw new IOException("Failed to create directory: " + folder.getAbsolutePath());
            }

            rgbImage = new BufferedImage(
                    coverImage.getWidth(),
                    coverImage.getHeight(),
                    BufferedImage.TYPE_INT_RGB
            );
            Graphics2D g = rgbImage.createGraphics();
            g.drawImage(coverImage, 0, 0, Color.WHITE, null);
            g.dispose();
            // Note: coverImage is not flushed here - caller is responsible for its lifecycle

            cropped = applyCoverCropping(rgbImage, settings);
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

            File originalFile = new File(folder, COVER_FILENAME);
            boolean originalSaved = ImageIO.write(rgbImage, IMAGE_FORMAT, originalFile);

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
            File thumbnailFile = new File(folder, THUMBNAIL_FILENAME);
            boolean thumbnailSaved = ImageIO.write(thumb, IMAGE_FORMAT, thumbnailFile);

            return originalSaved && thumbnailSaved;
        } finally {
            // Cleanup resources created within this method
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

    private static BufferedImage applyCoverCropping(BufferedImage image, CoverCroppingSettings settings) {
        if (settings == null) {
            return image;
        }

        int width = image.getWidth();
        int height = image.getHeight();
        double heightToWidthRatio = (double) height / width;
        double widthToHeightRatio = (double) width / height;
        double threshold = settings.getAspectRatioThreshold();
        boolean smartCrop = settings.isSmartCroppingEnabled();

        boolean isExtremelyTall = settings.isVerticalCroppingEnabled() && heightToWidthRatio > threshold;
        if (isExtremelyTall) {
            int croppedHeight = (int) (width * TARGET_COVER_ASPECT_RATIO);
            return cropFromTop(image, width, croppedHeight, smartCrop);
        }

        boolean isExtremelyWide = settings.isHorizontalCroppingEnabled() && widthToHeightRatio > threshold;
        if (isExtremelyWide) {
            int croppedWidth = (int) (height / TARGET_COVER_ASPECT_RATIO);
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
