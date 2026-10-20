package org.booklore.service.fileprocessor.prepare;

import org.booklore.util.cover.CoverImages;
import org.booklore.util.cover.CoverKind;
import org.booklore.util.cover.CropPolicy;
import org.booklore.util.cover.RenderedCover;

import java.awt.image.BufferedImage;
import java.io.IOException;

/**
 * Renders one file's cover, from encoded image bytes or from a rasteriser, into the cover and thumbnail stored for it.
 * Decoding, rendering and encoding run inside {@link PrepareContext#render}, so the decoded image only exists while
 * the file holds a render permit and heap for it.
 */
public final class CoverMaker {

    private final PrepareContext ctx;
    private final CoverKind kind;
    private final CropPolicy crop;

    public CoverMaker(PrepareContext ctx, CoverKind kind, CropPolicy crop) {
        this.ctx = ctx;
        this.kind = kind;
        this.crop = crop;
    }

    /**
     * The cover rendered from an encoded image.
     *
     * @param limitPixels as for {@link CoverImages#decode}: true decodes as FileService.readImage does, false as
     *                    ImageIO.read does
     * @return null when there are no bytes or they cannot be decoded; the caller logs why
     */
    public RenderedCover fromEncoded(byte[] encoded, boolean limitPixels) throws Exception {
        if (encoded == null || encoded.length == 0) {
            return null;
        }
        return render(encoded, limitPixels, true);
    }

    /**
     * As {@link #fromEncoded}, but bytes that cannot be decoded throw.
     *
     * @throws IOException when the image cannot be decoded
     */
    public RenderedCover fromEncodedOrThrow(byte[] encoded, boolean limitPixels) throws Exception {
        return render(encoded, limitPixels, false);
    }

    /**
     * The cover rendered from an image a rasteriser draws, such as a PDF page.
     *
     * @param rasterPixels the size of the image the rasteriser draws
     * @param extraBytes   heap the rasteriser needs beyond the image itself
     */
    public RenderedCover fromPixels(long rasterPixels, long extraBytes, IoCallable<BufferedImage> rasterise) throws Exception {
        return ctx.render(12 * rasterPixels + extraBytes, () -> {
            BufferedImage image = rasterise.call();
            if (image == null) {
                return null;
            }
            try {
                return renderImage(image);
            } finally {
                image.flush();
            }
        });
    }

    private RenderedCover render(byte[] encoded, boolean limitPixels, boolean nullIfUndecodable) throws Exception {
        CoverImages.Probe probe;
        try {
            probe = CoverImages.probe(encoded);
            if (limitPixels) {
                // Rejected on its header, as decode would reject it, without waiting for heap it would never use
                CoverImages.checkPixelLimit(probe);
            }
        } catch (IOException e) {
            if (nullIfUndecodable) {
                return null;
            }
            throw e;
        }
        return ctx.render(CoverImages.renderBytes(probe.decodedPixels()), () -> {
            BufferedImage image;
            try {
                image = CoverImages.decode(encoded, limitPixels);
            } catch (IOException e) {
                if (nullIfUndecodable) {
                    return null;
                }
                throw e;
            }
            try {
                return renderImage(image);
            } finally {
                image.flush();
            }
        });
    }

    private RenderedCover renderImage(BufferedImage image) throws IOException {
        return kind == CoverKind.AUDIOBOOK
                ? CoverImages.renderAudiobookCover(image)
                : CoverImages.renderBookCover(image, crop);
    }
}
