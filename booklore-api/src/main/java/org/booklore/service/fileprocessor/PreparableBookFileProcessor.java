package org.booklore.service.fileprocessor;

import org.booklore.model.dto.settings.LibraryFile;
import org.booklore.service.fileprocessor.prepare.PersistedBook;
import org.booklore.service.fileprocessor.prepare.PrepareContext;
import org.booklore.service.fileprocessor.prepare.PrepareRequest;
import org.booklore.service.fileprocessor.prepare.PreparedBookFile;

/**
 * A processor whose new-book work is split in two: reading the file, which touches only the file, and saving what was
 * read, which touches only the database. {@link #processFile} does both in turn; an importer can read many files at
 * once and save them one by one.
 */
public interface PreparableBookFileProcessor extends BookFileProcessor {

    /**
     * Reads everything a new book is made from: hash, size, metadata and rendered cover. Safe to run on any thread and
     * on several files at once: it uses no entity, repository or session.
     */
    PreparedBookFile prepare(PrepareRequest request, PrepareContext ctx) throws Exception;

    /**
     * Saves a prepared file as a new book, or updates the book already there for it, inside the caller's transaction,
     * and writes its cover files. Reads no book file.
     */
    PersistedBook persistPrepared(LibraryFile libraryFile, PreparedBookFile prepared);
}
