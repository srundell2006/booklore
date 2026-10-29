package org.booklore.model.dto.catalog;

import org.booklore.model.dto.Book;

import java.util.List;

/**
 * @param seriesName the series name as the first book stores it, which the series page shows; the requested name
 *                   when no book matches
 * @param total      how many books match, which is more than {@code books} holds when the list was cut at the limit
 * @param books      in series order, each the list-view DTO {@code GET /api/v1/books} streams
 */
public record SeriesBooks(String seriesName, long total, List<Book> books) {
}
