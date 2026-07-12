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
     * Lightweight result used for ISBN-by-title lookups.
     */
    public record OlIsbnResult(String isbn13, String isbn10, String title, String language, String authorKeys) {}

    /**
     * FULLTEXT search by title returning only editions that carry at least one ISBN.
     * Falls back to an exact-title match if the FULLTEXT index is unavailable or the
     * title is too short to tokenise.
     *
     * @param title the book title to search for (un-normalised; FULLTEXT handles stemming)
     * @return up to 15 candidate editions ordered by English-language preference
     */
    public List<OlIsbnResult> findIsbnByTitle(String title) {
        String sql =
                "SELECT isbn13, isbn10, title, language, author_keys " +
                "FROM ol_editions " +
                "WHERE MATCH(title) AGAINST(? IN BOOLEAN MODE) " +
                "AND (isbn13 IS NOT NULL OR isbn10 IS NOT NULL) " +
                "ORDER BY CASE WHEN language = 'eng' THEN 0 WHEN language IS NULL THEN 1 ELSE 2 END " +
                "LIMIT 15";
        try {
            List<OlIsbnResult> rows = jdbc.query(sql, this::mapIsbnRow, title);
            if (!rows.isEmpty()) return rows;
        } catch (Exception e) {
            log.debug("OL FULLTEXT search failed for '{}', falling back to exact match: {}", title, e.getMessage());
        }
        // Fallback: exact title match (slower but works on short/special titles)
        return jdbc.query(
                "SELECT isbn13, isbn10, title, language, author_keys " +
                "FROM ol_editions " +
                "WHERE LOWER(title) = LOWER(?) " +
                "AND (isbn13 IS NOT NULL OR isbn10 IS NOT NULL) " +
                "ORDER BY CASE WHEN language = 'eng' THEN 0 WHEN language IS NULL THEN 1 ELSE 2 END " +
                "LIMIT 15",
                this::mapIsbnRow, title
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

    private OlIsbnResult mapIsbnRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new OlIsbnResult(
                rs.getString("isbn13"),
                rs.getString("isbn10"),
                rs.getString("title"),
                rs.getString("language"),
                rs.getString("author_keys")
        );
    }

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
