package org.booklore.service.metadata.parser;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.model.dto.Book;
import org.booklore.model.dto.BookMetadata;
import org.booklore.model.dto.request.FetchMetadataRequest;
import org.booklore.model.enums.MetadataProvider;
import org.booklore.repository.OpenLibraryRepository;
import org.booklore.repository.OpenLibraryRepository.OlEditionRow;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Metadata provider that queries the locally-imported Open Library database tables
 * (ol_editions + ol_authors) instead of making external API calls.
 *
 * Tables are populated by import_open_library.py using bulk data dumps from
 * https://openlibrary.org/data/openlibrary-dump-latest.txt.gz
 *
 * Search order: ISBN-13 → ISBN-10 → title.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpenLibraryLocalParser implements BookParser {

    private static final String COVERS_BASE_URL = "https://covers.openlibrary.org/b/id/";
    private static final int SUBJECT_LIMIT = 10;

    private final OpenLibraryRepository repository;
    private final ObjectMapper objectMapper;

    public MetadataProvider getProvider() {
        return MetadataProvider.OpenLibraryLocal;
    }

    @Override
    public List<BookMetadata> fetchMetadata(Book book, FetchMetadataRequest request) {
        List<OlEditionRow> rows = findRows(book, request);
        if (rows.isEmpty()) {
            log.debug("OpenLibraryLocal: no results for book id={}", book.getId());
            return Collections.emptyList();
        }
        List<BookMetadata> results = new ArrayList<>();
        for (OlEditionRow row : rows) {
            results.add(toMetadata(row));
        }
        return results;
    }

    @Override
    public BookMetadata fetchTopMetadata(Book book, FetchMetadataRequest request) {
        List<BookMetadata> results = fetchMetadata(book, request);
        return results.isEmpty() ? null : results.get(0);
    }

    // -----------------------------------------------------------------------
    // Search strategy
    // -----------------------------------------------------------------------

    private List<OlEditionRow> findRows(Book book, FetchMetadataRequest request) {
        BookMetadata existingMeta = book.getMetadata();

        // isbn from request may be ISBN-13 or ISBN-10 — try both columns
        String reqIsbn = request.getIsbn();
        if (reqIsbn != null && !reqIsbn.isBlank()) {
            String cleaned = reqIsbn.replaceAll("[^0-9Xx]", "");
            if (cleaned.length() == 13) {
                List<OlEditionRow> rows = repository.findByIsbn13(cleaned);
                if (!rows.isEmpty()) return rows;
            } else if (cleaned.length() == 10) {
                List<OlEditionRow> rows = repository.findByIsbn10(cleaned);
                if (!rows.isEmpty()) return rows;
            }
            // ISBN was provided but not found locally — skip title fallback
            log.info("OpenLibraryLocal: ISBN {} not found in local DB; skipping title/author fallback because ISBN was provided.", reqIsbn);
            return Collections.emptyList();
        }

        // Fall back to ISBN values already on the book
        if (existingMeta != null) {
            String isbn13 = existingMeta.getIsbn13();
            if (isbn13 != null && !isbn13.isBlank()) {
                List<OlEditionRow> rows = repository.findByIsbn13(isbn13.replaceAll("[^0-9]", ""));
                if (!rows.isEmpty()) return rows;
            }
            String isbn10 = existingMeta.getIsbn10();
            if (isbn10 != null && !isbn10.isBlank()) {
                List<OlEditionRow> rows = repository.findByIsbn10(isbn10.replaceAll("[^0-9Xx]", ""));
                if (!rows.isEmpty()) return rows;
            }
        }

        // Title search (request title, then book title)
        String title = request.getTitle();
        if (title == null && existingMeta != null) title = existingMeta.getTitle();
        if (title != null && !title.isBlank()) {
            return repository.findByTitle(title);
        }

        return Collections.emptyList();
    }

    // -----------------------------------------------------------------------
    // Mapping
    // -----------------------------------------------------------------------

    private BookMetadata toMetadata(OlEditionRow row) {
        BookMetadata meta = new BookMetadata();
        meta.setProvider(MetadataProvider.OpenLibraryLocal);
        meta.setTitle(row.title());
        meta.setSubtitle(row.subtitle());
        meta.setPublisher(row.publisher());
        meta.setPublishedDate(parseDate(row.publishDate()));
        meta.setDescription(row.description());
        meta.setLanguage(row.language());
        meta.setPageCount(row.pageCount());

        // Authors
        List<String> authorKeys = parseJsonArray(row.authorKeys());
        if (!authorKeys.isEmpty()) {
            List<String> names = repository.resolveAuthorNames(authorKeys);
            meta.setAuthors(names.isEmpty() ? null : names);
        }

        // Categories (subjects) — field type is Set<String>
        List<String> subjects = parseJsonArray(row.subjects());
        if (!subjects.isEmpty()) {
            List<String> limited = subjects.subList(0, Math.min(subjects.size(), SUBJECT_LIMIT));
            meta.setCategories(new LinkedHashSet<>(limited));
        }

        // Cover — field is thumbnailUrl
        if (row.coverId() != null && row.coverId() > 0) {
            meta.setThumbnailUrl(COVERS_BASE_URL + row.coverId() + "-L.jpg");
        }

        return meta;
    }

    /** Parse the first 4 characters as a year, returning Jan 1 of that year. */
    private LocalDate parseDate(String date) {
        if (date == null || date.isBlank() || date.length() < 4) return null;
        try {
            return LocalDate.of(Integer.parseInt(date.substring(0, 4)), 1, 1);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private List<String> parseJsonArray(String json) {
        if (json == null || json.isBlank() || json.equals("[]")) return Collections.emptyList();
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.debug("Could not parse JSON array: {}", json);
            return Collections.emptyList();
        }
    }
}
