package org.booklore.service.acquisition;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.model.dto.Book;
import org.booklore.model.dto.BookMetadata;
import org.booklore.model.dto.request.FetchMetadataRequest;
import org.booklore.model.dto.settings.MetadataProviderSettings;
import org.booklore.service.appsettings.AppSettingService;
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

    /**
     * Providers capable of answering a free-text title search for a book that is
     * NOT yet in the library. Deliberately excludes:
     *  - Ollama: enriches books already in the database (keys its cache on book id)
     *  - OpenLibraryLocal: searches the local index, not the outside world
     *  - Comicvine / Ranobedb: niche catalogues that mostly return noise here
     */
    private static final Set<MetadataProvider> SEARCHABLE_PROVIDERS = Set.of(
            MetadataProvider.Google,
            MetadataProvider.OpenLibrary,
            MetadataProvider.Hardcover,
            MetadataProvider.Amazon,
            MetadataProvider.GoodReads,
            MetadataProvider.Audible,
            MetadataProvider.Douban,
            MetadataProvider.Lubimyczytac
    );

    /** Used when the configured chain contains nothing usable for free-text search. */
    private static final List<MetadataProvider> DEFAULT_SEARCH_PROVIDERS =
            List.of(MetadataProvider.Google, MetadataProvider.OpenLibrary);

    private final BookMetadataService bookMetadataService;
    private final BookRepository bookRepository;
    private final WantedBookRepository wantedBookRepository;
    private final AppSettingService appSettingService;

    @Transactional(readOnly = true)
    public List<BookLookupResult> lookup(String query) {
        if (query == null || query.trim().length() < 2) {
            return List.of();
        }
        String trimmedQuery = query.trim();
        List<MetadataProvider> providers = resolveSearchProviders();
        if (providers.isEmpty()) {
            log.warn("Book lookup requested but no search-capable metadata providers are enabled");
            return List.of();
        }
        log.info("Book lookup '{}' querying providers: {}", trimmedQuery, providers);

        FetchMetadataRequest request = FetchMetadataRequest.builder()
                .title(trimmedQuery)
                .providers(providers)
                .build();
        // Parsers read title/author off the book as a fallback, and some key caches
        // on book id — give them a well-formed stub rather than a bare empty object.
        Book emptyBook = Book.builder()
                .title(trimmedQuery)
                .metadata(BookMetadata.builder().title(trimmedQuery).build())
                .build();

        // Query providers in parallel; a slow provider shouldn't stall the type-ahead.
        List<BookMetadata> allResults = new ArrayList<>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<List<BookMetadata>>> futures = providers.stream()
                    .map(provider -> executor.submit(() -> {
                        try {
                            List<BookMetadata> results =
                                    bookMetadataService.fetchMetadataListFromAProvider(provider, emptyBook, request);
                            int count = results == null ? 0 : results.size();
                            log.info("Book lookup: provider {} returned {} result(s)", provider, count);
                            return results == null ? List.<BookMetadata>of() : results;
                        } catch (Exception e) {
                            log.warn("Lookup failed for provider {}: {}", provider, e.toString());
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

    /**
     * Builds the provider list for a free-text lookup: the user's configured chain,
     * restricted to providers that can actually search by title and are enabled,
     * falling back to sensible defaults when that leaves nothing.
     */
    private List<MetadataProvider> resolveSearchProviders() {
        MetadataProviderSettings providerSettings = appSettingService.getAppSettings().getMetadataProviderSettings();

        List<MetadataProvider> configured = bookMetadataService.getConfiguredProviderChain().stream()
                .filter(SEARCHABLE_PROVIDERS::contains)
                .filter(provider -> isEnabled(provider, providerSettings))
                .distinct()
                .collect(Collectors.toList());

        if (!configured.isEmpty()) {
            return configured;
        }

        List<MetadataProvider> fallback = DEFAULT_SEARCH_PROVIDERS.stream()
                .filter(provider -> isEnabled(provider, providerSettings))
                .collect(Collectors.toList());
        // If even the defaults are disabled, try Google anyway rather than returning nothing.
        return fallback.isEmpty() ? List.of(MetadataProvider.Google) : fallback;
    }

    private boolean isEnabled(MetadataProvider provider, MetadataProviderSettings settings) {
        if (settings == null) {
            return true;
        }
        return switch (provider) {
            case Amazon -> settings.getAmazon() == null || settings.getAmazon().isEnabled();
            case Google -> settings.getGoogle() == null || settings.getGoogle().isEnabled();
            case GoodReads -> settings.getGoodReads() == null || settings.getGoodReads().isEnabled();
            case Hardcover -> settings.getHardcover() == null || settings.getHardcover().isEnabled();
            case OpenLibrary -> settings.getOpenLibrary() == null || settings.getOpenLibrary().isEnabled();
            case Douban -> settings.getDouban() == null || settings.getDouban().isEnabled();
            case Lubimyczytac -> settings.getLubimyczytac() == null || settings.getLubimyczytac().isEnabled();
            case Audible -> settings.getAudible() == null || settings.getAudible().isEnabled();
            default -> true;
        };
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
