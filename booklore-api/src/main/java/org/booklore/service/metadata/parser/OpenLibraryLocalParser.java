package org.booklore.service.metadata.parser;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.model.dto.Book;
import org.booklore.model.dto.BookMetadata;
import org.booklore.model.dto.request.FetchMetadataRequest;
import org.booklore.model.enums.MetadataProvider;
import org.booklore.repository.OpenLibraryRepository;
import org.booklore.repository.OpenLibraryRepository.OlEditionRow;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Metadata provider that queries the locally-imported Open Library database tables
 * (ol_editions + ol_authors) instead of making external API calls.
 *
 * The tables are populated by import_open_library.py using bulk data dumps from
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

    @Override
    public MetadataProvider getProvider() {
        return MetadataProvider.OpenLibraryLocal;
    }

    @Override
    public List<BookMetadata> fetchMetadata(FetchMetadataRequest request, Book book) {
        List<OlEditionRow> rows = findRows(request, book);
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

    // -----------------------------------------------------------------------
    // Search strategy
    // -----------------------------------------------------------------------

    private List<OlEditionRow> findRows(FetchMetadataRequest request, Book book) {
        // 1. ISBN-13 from request or book metadata
        String isbn13 = request.getIsbn13();
        if (isbn13 == null && book.getMetadata() != null) isbn13 = book.getMetadata().getIsbn13();
        if (isbn13 != null && !isbn13.isBlank()) {
            List<OlEditionRow> rows = repository.findByIsbn13(isbn13.replaceAll("[^0-9]", ""));
            if (!rows.isEmpty()) return rows;
        }

        // 2. ISBN-10
        String isbn10 = request.getIsbn10();
        if (isbn10 == null && book.getMetadata() != null) isbn10 = book.getMetadata().getIsbn10();
        if (isbn10 != null && !isbn10.isBlank()) {
            List<OlEditionRow> rows = repository.findByIsbn10(isbn10.replaceAll("[^0-9Xx]", ""));
            if (!rows.isEmpty()) return rows;
        }

        // 3. Title
        String title = request.getTitle();
        if (title == null && book.getMetadata() != null) title = book.getMetadata().getTitle();
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
        meta.setTitle(row.title());
        meta.setSubtitle(row.subtitle());
        meta.setPublisher(row.publisher());
        meta.setPublishedDate(row.publishDate());
        meta.setDescription(row.description());
        meta.setLanguage(row.language());
        meta.setPageCount(row.pageCount());

        // Authors
        List<String> authorKeys = parseJsonArray(row.authorKeys());
        if (!authorKeys.isEmpty()) {
            List<String> names = repository.resolveAuthorNames(authorKeys);
            meta.setAuthors(names.isEmpty() ? null : names);
        }

        // Categories (subjects)
        List<String> subjects = parseJsonArray(row.subjects());
        if (!subjects.isEmpty()) {
            meta.setCategories(subjects.subList(0, Math.min(subjects.size(), SUBJECT_LIMIT)));
        }

        // Cover
        if (row.coverId() != null && row.coverId() > 0) {
            meta.setCover(COVERS_BASE_URL + row.coverId() + "-L.jpg");
        }

        return meta;
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
