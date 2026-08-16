package org.booklore.service.comic;

/**
 * A single piece of evidence contributing to a comic-detection score.
 *
 * @param code    stable identifier, e.g. {@code EPUB_FIXED_LAYOUT}
 * @param points  positive or negative contribution to the 0-100 score
 * @param detail  human-readable explanation shown in the review queue
 */
public record ComicSignal(String code, int points, String detail) {

    @Override
    public String toString() {
        return detail + " (" + (points >= 0 ? "+" : "") + points + ")";
    }
}
