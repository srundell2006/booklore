package org.booklore.service.fileprocessor.prepare;

import org.booklore.util.cover.RenderedCover;

/**
 * An additional format read and ready to be attached to its book.
 *
 * @param crossTypeCover the cover the request asked for, or null when none was asked for or none could be made
 */
public record PreparedAttachment(AttachmentRequest request, FileFacts facts, RenderedCover crossTypeCover) {
}
