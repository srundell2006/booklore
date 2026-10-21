package org.booklore.service.library.importer;

import org.booklore.model.dto.settings.LibraryFile;
import org.booklore.service.fileprocessor.BookFileProcessor;
import org.booklore.service.fileprocessor.prepare.AttachmentRequest;
import org.booklore.service.fileprocessor.prepare.PrepareRequest;

import java.util.List;

/**
 * How one group of new files becomes a book, worked out on the scan's thread before any of it is read.
 * Groups are saved in the order they are planned.
 *
 * @param names              the group's file names, for logs
 * @param request            what import threads read the primary file from, or null when its processor cannot
 *                           prepare files and reads it while saving it, on the scan's thread
 * @param additional         the group's other files, attached to the book in this order
 * @param attachmentRequests what import threads read each additional file from, in the same order
 */
record GroupPlan(String names, LibraryFile primary, BookFileProcessor processor, PrepareRequest request,
                 List<LibraryFile> additional, List<AttachmentRequest> attachmentRequests) {
}
