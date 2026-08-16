package org.booklore.service.comic;

import org.booklore.model.enums.BookFileType;

import java.util.Set;

/**
 * Flattened snapshot of everything detection needs about one book.
 *
 * <p>Built inside a read-only transaction so that lazy associations
 * (categories in particular) are resolved while a session is still open.
 * Scoring then runs on plain values — no entities, no session, no risk of
 * {@code LazyInitializationException}, and nothing large held in memory.
 */
public record ComicScanTarget(
        Long bookId,
        String title,
        String seriesName,
        String publisher,
        Set<String> categories,
        String filePath,
        String fileName,
        BookFileType fileType,
        boolean alreadyComic
) {
    public String displayTitle() {
        if (title != null && !title.isBlank()) return title;
        if (fileName != null && !fileName.isBlank()) return fileName;
        return "Book " + bookId;
    }
}
