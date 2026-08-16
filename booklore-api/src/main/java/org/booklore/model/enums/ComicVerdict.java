package org.booklore.model.enums;

/** Outcome band a detection score falls into. */
public enum ComicVerdict {
    /** Score at or above the auto-mark threshold — is_comic set immediately. */
    COMIC,
    /** Score in the review band — queued as a candidate for human confirmation. */
    BORDERLINE,
    /** Below the review floor — not a comic, nothing recorded. */
    NOT_COMIC
}
