package org.booklore.model.dto.catalog;

import org.booklore.model.dto.Book;

import java.util.List;

/**
 * @param books in row order, each the list-view DTO {@code GET /api/v1/books} streams
 */
public record DashboardRow(List<Book> books) {
}
