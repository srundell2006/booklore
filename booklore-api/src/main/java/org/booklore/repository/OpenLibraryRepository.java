package org.booklore.repository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Queries the locally-imported Open Library tables (ol_editions, ol_authors)
 * that are populated by the import_open_library.py bulk-import script.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class OpenLibraryRepository {

    private final JdbcTemplate jdbc;

    // -----------------------------------------------------------------------
    // Row type
    // -----------------------------------------------------------------------

    public record OlEditionRow(
            String olKey,
            String isbn13,
            String isbn10,
            String title,
            String subtitle,
            String publisher,
            String publishDate,
            String description,
            String subjects,      // JSON array string
            String authorKeys,    // JSON array string  e.g. ["/authors/OL1234A"]
            String language,
            Integer pageCount,
            Long coverId
    ) {}

    // -----------------------------------------------------------------------
    // Lookups
    // -----------------------------------------------------------------------

    public List<OlEditionRow> findByIsbn13(String isbn13) {
        return jdbc.query(
                "SELECT ol_key, isbn13, isbn10, title, subtitle, publisher, publish_date, " +
                "description, subjects, author_keys, language, page_count, cover_id " +
                "FROM ol_editions WHERE isbn13 = ? " +
                "ORDER BY CASE WHEN language = 'eng' THEN 0 WHEN language IS NULL THEN 1 ELSE 2 END " +
                "LIMIT 5",
                this::mapRow, isbn13
        );
    }

    public List<OlEditionRow> findByIsbn10(String isbn10) {
        return jdbc.query(
                "SELECT ol_key, isbn13, isbn10, title, subtitle, publisher, publish_date, " +
                "description, subjects, author_keys, language, page_count, cover_id " +
                "FROM ol_editions WHERE isbn10 = ? " +
                "ORDER BY CASE WHEN language = 'eng' THEN 0 WHEN language IS NULL THEN 1 ELSE 2 END " +
                "LIMIT 5",
                this::mapRow, isbn10
        );
    }

    /**
     * Full-text search on title, falling back to LIKE if FULLTEXT returns nothing.
     */
    public List<OlEditionRow> findByTitle(String title) {
        try {
            List<OlEditionRow> rows = jdbc.query(
                    "SELECT ol_key, isbn13, isbn10, title, subtitle, publisher, publish_date, " +
                    "description, subjects, author_keys, language, page_count, cover_id " +
                    "FROM ol_editions WHERE MATCH(title) AGAINST(? IN BOOLEAN MODE) " +
                    "ORDER BY CASE WHEN language = 'eng' THEN 0 WHEN language IS NULL THEN 1 ELSE 2 END " +
                    "LIMIT 10",
                    this::mapRow, title
            );
            if (!rows.isEmpty()) return rows;
        } catch (Exception e) {
            log.debug("FULLTEXT search failed, falling back to LIKE: {}", e.getMessage());
        }
        return jdbc.query(
                "SELECT ol_key, isbn13, isbn10, title, subtitle, publisher, publish_date, " +
                "description, subjects, author_keys, language, page_count, cover_id " +
                "FROM ol_editions WHERE title LIKE ? " +
                "ORDER BY CASE WHEN language = 'eng' THEN 0 WHEN language IS NULL THEN 1 ELSE 2 END " +
                "LIMIT 10",
                this::mapRow, "%" + title + "%"
        );
    }

    /**
     * Resolve a list of OL author keys (e.g. ["/authors/OL1234A"]) to display names.
     */
    public List<String> resolveAuthorNames(List<String> authorKeys) {
        if (authorKeys == null || authorKeys.isEmpty()) return Collections.emptyList();
        String placeholders = authorKeys.stream().map(k -> "?").collect(Collectors.joining(", "));
        return jdbc.queryForList(
                "SELECT author_name FROM ol_authors WHERE ol_key IN (" + placeholders + ")",
                String.class,
                authorKeys.toArray()
        );
    }

    // -----------------------------------------------------------------------
    // Private
    // -----------------------------------------------------------------------

    private OlEditionRow mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new OlEditionRow(
                rs.getString("ol_key"),
                rs.getString("isbn13"),
                rs.getString("isbn10"),
                rs.getString("title"),
                rs.getString("subtitle"),
                rs.getString("publisher"),
                rs.getString("publish_date"),
                rs.getString("description"),
                rs.getString("subjects"),
                rs.getString("author_keys"),
                rs.getString("language"),
                rs.getObject("page_count") != null ? rs.getInt("page_count") : null,
                rs.getObject("cover_id") != null ? rs.getLong("cover_id") : null
        );
    }
}
