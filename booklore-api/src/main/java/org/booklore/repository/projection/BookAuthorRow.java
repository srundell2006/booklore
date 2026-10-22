package org.booklore.repository.projection;

/** One author of one book, as its metadata lists it. */
public record BookAuthorRow(Long bookId, String authorName) {
}
