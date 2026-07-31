package org.booklore.model.dto.request;

import lombok.Data;

import java.util.List;

@Data
public class AudiobookVerificationRequest {

    /** Which audiobooks to verify. Defaults to BOOKS (specific IDs). */
    private ScanType scanType = ScanType.BOOKS;

    /** Book IDs to verify. Only used when scanType == BOOKS. */
    private List<Long> bookIds;

    public enum ScanType {
        /** Verify every audiobook whose verification_status is NULL. */
        ALL_UNVERIFIED,
        /** Verify the explicit list of book IDs in bookIds. */
        BOOKS
    }
}
