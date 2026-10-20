package org.booklore.service.fileprocessor.prepare;

import org.booklore.model.dto.BookMetadata;
import org.booklore.util.ArchiveUtils;
import org.booklore.util.cover.RenderedCover;

/**
 * Everything read from a new book's primary file, ready to be saved: no entity, no session, no file left to read.
 *
 * @param metadata    null when the file gave none
 * @param cover       the rendered cover, from the file or its folder, or null when there is none
 * @param archiveType the archive type of a comic, null for other formats
 */
public record PreparedBookFile(PrepareRequest request, FileFacts facts, BookMetadata metadata, RenderedCover cover,
                               ArchiveUtils.ArchiveType archiveType) {
}
