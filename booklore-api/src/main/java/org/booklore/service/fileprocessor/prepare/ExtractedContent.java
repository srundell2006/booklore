package org.booklore.service.fileprocessor.prepare;

import org.booklore.model.dto.BookMetadata;
import org.booklore.util.ArchiveUtils;
import org.booklore.util.cover.RenderedCover;

/**
 * What a processor read from a book file.
 *
 * @param metadata    null when the file gave none
 * @param cover       null when the file has no usable cover
 * @param archiveType the archive type of a comic, null for other formats
 */
public record ExtractedContent(BookMetadata metadata, RenderedCover cover, ArchiveUtils.ArchiveType archiveType) {
}
