package org.booklore.util.cover;

/**
 * A cover and its thumbnail, encoded as JPEG and ready to be written. The arrays are not changed after construction.
 */
public record RenderedCover(CoverKind kind, byte[] cover, byte[] thumbnail) {
}
