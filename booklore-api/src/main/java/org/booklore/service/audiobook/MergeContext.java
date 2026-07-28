package org.booklore.service.audiobook;

import lombok.Builder;

/**
 * Everything a merge needs, captured as plain values inside a short
 * transaction. A merge runs for minutes to hours, so no JPA entity may be
 * touched after this point — the session is long gone by then.
 */
@Builder
public record MergeContext(
        long bookId,
        String sourcePath,
        boolean folderBased,
        String title,
        String seriesName,
        Float seriesNumber,
        String publisher,
        Integer publishedYear,
        String description
) {}
