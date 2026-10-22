package org.booklore.repository.projection;

/**
 * One row per author of a fileless book (one row with a null author for a book without any), with the few columns
 * rescan and watcher matching need to tell whether a new file is that book. Lets them hold every fileless book of a
 * library without loading entities.
 *
 * @param libraryPathId the book's library path, null for a book that has none yet
 * @param physical      the book's is_physical flag, which may be null
 */
public record FilelessBookRow(Long bookId,
                              Long libraryPathId,
                              Boolean physical,
                              String bookCoverHash,
                              String audiobookCoverHash,
                              String title,
                              String subtitle,
                              Float seriesNumber,
                              String authorName) {
}
