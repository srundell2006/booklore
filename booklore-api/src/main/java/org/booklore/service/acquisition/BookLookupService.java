package org.booklore.service.acquisition;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.model.dto.Book;
import org.booklore.model.dto.BookMetadata;
import org.booklore.model.dto.request.FetchMetadataRequest;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.model.entity.WantedBookEntity;
import org.booklore.model.enums.MetadataProvider;
import org.booklore.repository.BookRepository;
import org.booklore.repository.WantedBookRepository;
import org.booklore.service.metadata.BookMetadataService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

/**
 * Sonarr/Radarr-style "add new" lookup: searches configured metadata providers
 * by free text and marks which results already exist locally.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BookLookupService {

    private static final int MAX_RESULTS = 40;

    private final BookMetadataService bookMetadataService;
    private final BookRepository bookRepository;
    private final WantedBookRepository wantedBookRepository;

    @Transactional(readOnly = true)
    public List<BookLookupResult> lookup(String query) {
        if (query == null || query.trim().length() < 2) {
            return List.of();
        }
        String trimmedQuery = query.trim();
        List<MetadataProvider> providers = bookMetadataService.getConfiguredProviderChain();
        if (providers.isEmpty()) {
            log.warn("Book lookup requested but no metadata providers are configured");
            return List.of();
        }

        FetchMetadataRequest request = FetchMetadataRequest.builder()
                .title(trimmedQuery)
                .providers(providers)
                .build();
        Book emptyBook = Book.builder().build();

        // Query providers in parallel; a slow provider shouldn't stall the type-ahead.
        List<BookMetadata> allResults = new ArrayList<>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<List<BookMetadata>>> futures = providers.stream()
                    .map(provider -> executor.submit(() -> {
                        try {
                            List<BookMetadata> results =
                                    bookMetadataService.fetchMetadataListFromAProvider(provider, emptyBook, request);
                            return results == null ? List.<BookMetadata>of() : results;
                        } catch (Exception e) {
                            log.warn("Lookup failed for provider {}: {}", provider, e.getMessage());
                            return List.<BookMetadata>of();
                        }
                    }))
                    .toList();
            for (Future<List<BookMetadata>> future : futures) {
                try {
                    allResults.addAll(future.get());
                } catch (Exception ignored) {
                    // individual provider failure already logged
                }
            }
        }

        List<BookLookupResult> deduped = dedupe(allResults);
        annotateLocalState(deduped);

        return deduped.stream()
                .sorted(Comparator
                        .comparing((BookLookupResult r) -> relevance(r, trimmedQuery)).reversed())
                .limit(MAX_RESULTS)
                .collect(Collectors.toList());
    }

    /** Collapses the same book returned by multiple providers, preferring the richest record. */
    private List<BookLookupResult> dedupe(List<BookMetadata> results) {
        Map<String, BookLookupResult> byKey = new LinkedHashMap<>();
        for (BookMetadata metadata : results) {
            if (metadata == null || metadata.getTitle() == null || metadata.getTitle().isBlank()) {
                continue;
            }
            String key = dedupeKey(metadata);
            BookLookupResult candidate = toResult(metadata);
            BookLookupResult existing = byKey.get(key);
            if (existing == null || richness(candidate) > richness(existing)) {
                byKey.put(key, candidate);
            }
        }
        return new ArrayList<>(byKey.values());
    }

    private String dedupeKey(BookMetadata metadata) {
        if (metadata.getIsbn13() != null && !metadata.getIsbn13().isBlank()) {
            return "isbn:" + metadata.getIsbn13().trim();
        }
        String author = metadata.getAuthors() == null || metadata.getAuthors().isEmpty()
                ? "" : normalize(metadata.getAuthors().getFirst());
        return "ta:" + normalize(metadata.getTitle()) + "|" + author;
    }

    private int richness(BookLookupResult result) {
        int score = 0;
        if (result.getThumbnailUrl() != null) score += 3;
        if (result.getDescription() != null && !result.getDescription().isBlank()) score += 2;
        if (result.getIsbn13() != null) score += 2;
        if (result.getAuthors() != null && !result.getAuthors().isEmpty()) score += 2;
        if (result.getPublishedYear() != null) score += 1;
        return score;
    }

    private BookLookupResult toResult(BookMetadata metadata) {
        return BookLookupResult.builder()
                .title(metadata.getTitle())
                .authors(metadata.getAuthors())
                .description(metadata.getDescription())
                .publisher(metadata.getPublisher())
                .publishedYear(metadata.getPublishedDate() == null ? null : metadata.getPublishedDate().getYear())
                .isbn13(metadata.getIsbn13())
                .isbn10(metadata.getIsbn10())
                .asin(metadata.getAsin())
                .thumbnailUrl(metadata.getThumbnailUrl())
                .provider(metadata.getProvider())
                .build();
    }

    /** Marks results that already exist in the library or on the wanted list. */
    private void annotateLocalState(List<BookLookupResult> results) {
        if (results.isEmpty()) return;

        List<BookEntity> libraryBooks = bookRepository.findAllWithMetadata();
        Map<String, Long> byIsbn = new LinkedHashMap<>();
        Map<String, Long> byTitleAuthor = new LinkedHashMap<>();
        for (BookEntity book : libraryBooks) {
            BookMetadataEntity meta = book.getMetadata();
            if (meta == null || meta.getTitle() == null) continue;
            if (meta.getIsbn13() != null && !meta.getIsbn13().isBlank()) {
                byIsbn.putIfAbsent(meta.getIsbn13().trim(), book.getId());
            }
            byTitleAuthor.putIfAbsent(normalize(meta.getTitle()), book.getId());
        }

        Set<String> wantedTitles = wantedBookRepository.findAll().stream()
                .map(WantedBookEntity::getTitle)
                .filter(java.util.Objects::nonNull)
                .map(this::normalize)
                .collect(Collectors.toSet());

        for (BookLookupResult result : results) {
            Long existingId = null;
            if (result.getIsbn13() != null && !result.getIsbn13().isBlank()) {
                existingId = byIsbn.get(result.getIsbn13().trim());
            }
            if (existingId == null) {
                existingId = byTitleAuthor.get(normalize(result.getTitle()));
            }
            if (existingId != null) {
                result.setInLibrary(true);
                result.setExistingBookId(existingId);
            }
            result.setAlreadyWanted(wantedTitles.contains(normalize(result.getTitle())));
        }
    }

    /** Higher is better: exact/prefix title matches float to the top. */
    private int relevance(BookLookupResult result, String query) {
        String title = normalize(result.getTitle());
        String normalizedQuery = normalize(query);
        int score = richness(result);
        if (title.equals(normalizedQuery)) {
            score += 20;
        } else if (title.startsWith(normalizedQuery)) {
            score += 12;
        } else if (title.contains(normalizedQuery)) {
            score += 6;
        }
        return score;
    }

    private String normalize(String value) {
        if (value == null) return "";
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
    }
}
