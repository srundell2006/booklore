package org.booklore.service.fileprocessor.prepare;

import org.booklore.model.dto.Book;
import org.booklore.model.enums.BookFileType;
import org.booklore.service.metadata.sidecar.SidecarMetadataWriter;

/**
 * A book saved from a prepared file.
 *
 * @param created    false when the file's book already existed and was updated instead
 * @param restored   true when that book was deleted and the file brought it back
 * @param sidecarJob the sidecar still to be written for the book, or null for none
 */
public record PersistedBook(Book book, long bookId, boolean created, boolean restored, String primaryFileName,
                            BookFileType primaryType, SidecarMetadataWriter.SidecarJob sidecarJob) {
}
