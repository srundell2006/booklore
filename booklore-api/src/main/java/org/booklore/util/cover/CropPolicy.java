package org.booklore.util.cover;

import org.booklore.model.dto.settings.CoverCroppingSettings;

/**
 * The cover cropping settings as plain values, so a cover can be rendered without reading the app settings.
 *
 * @param vertical   crop images taller than {@code threshold} times their width
 * @param horizontal crop images wider than {@code threshold} times their height
 * @param threshold  the aspect ratio above which an image counts as extremely tall or wide
 * @param smart      start the crop where the content starts instead of at the top or left edge
 */
public record CropPolicy(boolean vertical, boolean horizontal, double threshold, boolean smart) {

    /** No cropping, which is what missing cropping settings mean. */
    public static final CropPolicy NONE = new CropPolicy(false, false, Double.MAX_VALUE, false);

    public static CropPolicy from(CoverCroppingSettings settings) {
        if (settings == null) {
            return NONE;
        }
        return new CropPolicy(settings.isVerticalCroppingEnabled(), settings.isHorizontalCroppingEnabled(),
                settings.getAspectRatioThreshold(), settings.isSmartCroppingEnabled());
    }
}
