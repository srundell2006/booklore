package org.booklore.service.metadata.parser;

import lombok.extern.slf4j.Slf4j;
import org.booklore.model.dto.Book;
import org.booklore.model.dto.BookMetadata;
import org.booklore.model.dto.request.FetchMetadataRequest;
import org.booklore.model.dto.response.OpenLibraryApiResponse;
import org.booklore.model.dto.response.OpenLibraryWorkResponse;
import org.booklore.model.enums.MetadataProvider;
import org.booklore.util.BookUtils;
import org.jsoup.Jsoup;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.ObjectMapper;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
public class OpenLibraryParser implements BookParser {

    private static final String BASE_URL = "https://openlibrary.org";
    private static final String COVERS_BASE_URL = "https://covers.openlibrary.org";
    private static final String SEARCH_FIELDS = "key,title,author_name,first_publish_year,isbn,cover_i,publisher,language,number_of_pages_median,subject";
    private static final int SEARCH_LIMIT = 10;
    private static final int SUBJECT_LIMIT = 10;
    private static final Pattern WORK_PREFIX = Pattern.compile("^/works/");
    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");
    private static final String BOOKS_API_URL = BASE_URL + "/api/books";
    private static final int BULK_BATCH_SIZE = 20;

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    /** Cache populated by preFetchByIsbn(); keyed by cleaned ISBN (10 or 13 digit). */
    private final ConcurrentHashMap<String, BookMetadata> bulkCache = new ConcurrentHashMap<>();

    @Autowired
    public OpenLibraryParser(ObjectMapper objectMapper) {
        this(objectMapper, HttpClient.newHttpClient());
    }

    public OpenLibraryParser(ObjectMapper objectMapper, HttpClient httpClient) {
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    @Override
    public List<BookMetadata> fetchMetadata(Book book, FetchMetadataRequest fetchMetadataRequest) {
        URI uri = buildSearchUri(book, fetchMetadataRequest);
        if (uri == null) {
            return List.of();
        }

        try {
            log.info("Open Library API URL: {}", uri);
            HttpResponse<String> response = httpClient.send(
                    HttpRequest.newBuilder().uri(uri).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            if (response.statusCode() != 200) {
                log.warn("Open Library API request failed. Status: {}, Response: {}", response.statusCode(), response.body());
                return List.of();
            }

            OpenLibraryApiResponse searchResponse = objectMapper.readValue(response.body(), OpenLibraryApiResponse.class);
            if (searchResponse == null || searchResponse.getDocs() == null) {
                return List.of();
            }

            return searchResponse.getDocs().stream()
                    .map(this::mapDoc)
                    .filter(this::isUsableResult)
                    .toList();
        } catch (IOException e) {
            log.error("IO error while fetching metadata from Open Library API: {}", e.getMessage());
            return List.of();
        } catch (InterruptedException e) {
            log.error("Request to Open Library API was interrupted");
            Thread.currentThread().interrupt();
            return List.of();
        }
    }

    @Override
    public BookMetadata fetchTopMetadata(Book book, FetchMetadataRequest fetchMetadataRequest) {
        // Check bulk cache first (populated by preFetchByIsbn)
        String isbn = ParserUtils.cleanIsbn(fetchMetadataRequest.getIsbn());
        if (isbn != null && !isbn.isBlank()) {
            BookMetadata cached = bulkCache.remove(isbn);
            if (cached != null) {
                log.debug("OpenLibrary bulk cache hit for ISBN {}", isbn);
                String workId = extractWorkId(cached.getExternalUrl());
                if (workId == null) return cached;
                BookMetadata workMetadata = fetchWorkMetadata(workId);
                return workMetadata == null ? cached : merge(cached, workMetadata);
            }
        }

        // Cache miss — fall back to per-book search
        List<BookMetadata> results = fetchMetadata(book, fetchMetadataRequest);
        if (results.isEmpty()) {
            return null;
        }

        BookMetadata top = results.getFirst();
        String workId = extractWorkId(top.getExternalUrl());
        if (workId == null) {
            return top;
        }

        BookMetadata workMetadata = fetchWorkMetadata(workId);
        return workMetadata == null ? top : merge(top, workMetadata);
    }

    /**
     * Bulk pre-fetch OpenLibrary metadata for the supplied ISBNs using the
     * {@code /api/books} endpoint (up to {@value BULK_BATCH_SIZE} per HTTP request).
     * Results are stored in an internal cache; subsequent {@link #fetchTopMetadata}
     * calls consume cache entries and skip the individual search request.
     */
    @Override
    public void preFetchByIsbn(Collection<String> rawIsbns) {
        List<String> cleanIsbns = rawIsbns.stream()
                .map(ParserUtils::cleanIsbn)
                .filter(Objects::nonNull)
                .filter(isbn -> !isbn.isBlank())
                .distinct()
                .collect(Collectors.toList());

        if (cleanIsbns.isEmpty()) return;

        log.info("OpenLibrary bulk pre-fetch: {} ISBNs in batches of {}", cleanIsbns.size(), BULK_BATCH_SIZE);
        for (int i = 0; i < cleanIsbns.size(); i += BULK_BATCH_SIZE) {
            List<String> batch = cleanIsbns.subList(i, Math.min(i + BULK_BATCH_SIZE, cleanIsbns.size()));
            try {
                fetchBulkBatch(batch);
            } catch (Exception e) {
                log.warn("OpenLibrary bulk batch {}-{} failed: {}", i, i + batch.size(), e.getMessage());
            }
        }
        log.info("OpenLibrary bulk pre-fetch complete: {} entries cached", bulkCache.size());
    }

    private void fetchBulkBatch(List<String> isbns) throws IOException, InterruptedException {
        String bibkeys = isbns.stream()
                .map(isbn -> "ISBN:" + isbn)
                .collect(Collectors.joining(","));

        URI uri = UriComponentsBuilder.fromUriString(BOOKS_API_URL)
                .queryParam("bibkeys", bibkeys)
                .queryParam("format", "json")
                .queryParam("jscmd", "data")
                .build().toUri();

        log.info("OpenLibrary bulk API URL ({}): {}", isbns.size(), uri);
        HttpResponse<String> response = httpClient.send(
                HttpRequest.newBuilder().uri(uri).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );

        if (response.statusCode() != 200) {
            log.warn("OpenLibrary bulk API returned status {}: {}", response.statusCode(), response.body());
            return;
        }

        Map<String, JsonNode> result = objectMapper.readValue(
                response.body(), new TypeReference<Map<String, JsonNode>>() {});

        for (Map.Entry<String, JsonNode> entry : result.entrySet()) {
            String bibkey = entry.getKey();           // e.g. "ISBN:9780451524935"
            if (!bibkey.startsWith("ISBN:")) continue;
            String isbn = bibkey.substring(5);        // strip "ISBN:"
            BookMetadata metadata = mapBulkEntry(entry.getValue(), isbn);
            if (metadata != null) {
                bulkCache.put(isbn, metadata);
            }
        }
    }

    /**
     * Maps a single entry from the /api/books?jscmd=data response into a BookMetadata.
     * The structure differs from /search.json: authors are [{name, url}],
     * publishers are [{name}], cover has {small, medium, large} URLs, etc.
     */
    private BookMetadata mapBulkEntry(JsonNode node, String isbn) {
        if (node == null || node.isNull()) return null;

        String title = cleanString(node.path("title").asText(null));
        if (title == null || title.isBlank()) return null;

        // Authors: array of {name, url}
        List<String> authors = new ArrayList<>();
        for (JsonNode a : node.path("authors")) {
            String name = cleanString(a.path("name").asText(null));
            if (name != null && !name.isBlank()) authors.add(name);
        }

        // Publisher: first element of [{name}]
        String publisher = null;
        JsonNode publishers = node.path("publishers");
        if (publishers.isArray() && !publishers.isEmpty()) {
            publisher = cleanString(publishers.get(0).path("name").asText(null));
        }

        // Published date (free-form string like "September 1, 1990" or "1990")
        LocalDate publishedDate = parseDate(node.path("publish_date").asText(null));

        // Page count
        int pageCount = node.path("number_of_pages").asInt(0);

        // ISBNs from identifiers block (prefer explicit; fall back to the bibkey ISBN)
        JsonNode identifiers = node.path("identifiers");
        String isbn10 = null;
        String isbn13 = isbn;
        JsonNode isbn10List = identifiers.path("isbn_10");
        JsonNode isbn13List = identifiers.path("isbn_13");
        if (isbn10List.isArray() && !isbn10List.isEmpty()) isbn10 = isbn10List.get(0).asText(null);
        if (isbn13List.isArray() && !isbn13List.isEmpty()) isbn13 = isbn13List.get(0).asText(null);

        // Cover: prefer large, fall back to medium
        String coverUrl = null;
        JsonNode cover = node.path("cover");
        if (!cover.isMissingNode() && !cover.isNull()) {
            String large = cover.path("large").asText(null);
            coverUrl = (large != null && !large.isBlank()) ? large : cover.path("medium").asText(null);
            if (coverUrl != null && coverUrl.isBlank()) coverUrl = null;
        }

        // Subjects: array of {name, url}
        Set<String> subjects = new LinkedHashSet<>();
        for (JsonNode s : node.path("subjects")) {
            String name = cleanString(s.path("name").asText(null));
            if (name != null && !name.isBlank()) {
                subjects.add(name);
                if (subjects.size() >= SUBJECT_LIMIT) break;
            }
        }

        // Work key → external URL  (used later to fetch description via /works/<id>.json)
        String externalUrl = null;
        JsonNode works = node.path("works");
        if (works.isArray() && !works.isEmpty()) {
            String workKey = works.get(0).path("key").asText(null);
            if (workKey != null && !workKey.isBlank()) {
                String workId = stripWorkPrefix(workKey);
                if (workId != null) externalUrl = BASE_URL + "/works/" + workId;
            }
        }

        return BookMetadata.builder()
                .provider(MetadataProvider.OpenLibrary)
                .title(title)
                .authors(authors.isEmpty() ? null : authors)
                .publisher(publisher)
                .publishedDate(publishedDate)
                .pageCount(pageCount == 0 ? null : pageCount)
                .isbn10(isbn10)
                .isbn13(isbn13)
                .thumbnailUrl(coverUrl)
                .categories(subjects.isEmpty() ? null : subjects)
                .externalUrl(externalUrl)
                .build();
    }

    private URI buildSearchUri(Book book, FetchMetadataRequest request) {
        String isbn = ParserUtils.cleanIsbn(request.getIsbn());
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(BASE_URL + "/search.json")
                .queryParam("limit", SEARCH_LIMIT)
                .queryParam("fields", SEARCH_FIELDS);

        if (isbn != null && !isbn.isBlank()) {
            return builder.queryParam("isbn", isbn).build().toUri();
        }

        String title = Optional.ofNullable(request.getTitle())
                .filter(value -> !value.isBlank())
                .orElseGet(() -> Optional.ofNullable(book.getPrimaryFile())
                        .map(primaryFile -> primaryFile.getFileName())
                        .filter(fileName -> !fileName.isBlank())
                        .map(BookUtils::cleanFileName)
                        .orElse(null));

        if (title == null || title.isBlank()) {
            return null;
        }

        builder.queryParam("title", title);
        if (request.getAuthor() != null && !request.getAuthor().isBlank()) {
            builder.queryParam("author", request.getAuthor());
        }

        return builder.build().toUri();
    }

    private BookMetadata fetchWorkMetadata(String workId) {
        URI uri = URI.create(BASE_URL + "/works/" + workId + ".json");

        try {
            log.info("Open Library work API URL: {}", uri);
            HttpResponse<String> response = httpClient.send(
                    HttpRequest.newBuilder().uri(uri).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            if (response.statusCode() != 200) {
                log.warn("Open Library work request failed. Status: {}, Response: {}", response.statusCode(), response.body());
                return null;
            }

            OpenLibraryWorkResponse work = objectMapper.readValue(response.body(), OpenLibraryWorkResponse.class);
            return work == null ? null : mapWork(work);
        } catch (IOException e) {
            log.error("IO error while fetching Open Library work metadata: {}", e.getMessage());
            return null;
        } catch (InterruptedException e) {
            log.error("Open Library work request was interrupted");
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private BookMetadata mapDoc(OpenLibraryApiResponse.Doc doc) {
        String workId = stripWorkPrefix(doc.getKey());
        Map<String, String> isbns = extractIsbns(doc.getIsbn());

        return BookMetadata.builder()
                .provider(MetadataProvider.OpenLibrary)
                .title(cleanString(doc.getTitle()))
                .authors(cleanList(doc.getAuthorName()))
                .publishedDate(parseYear(doc.getFirstPublishYear()))
                .isbn10(isbns.get("ISBN_10"))
                .isbn13(isbns.get("ISBN_13"))
                .publisher(first(doc.getPublisher()))
                .language(cleanString(first(doc.getLanguage())))
                .pageCount(doc.getNumberOfPagesMedian())
                .categories(cleanSubjects(doc.getSubject()))
                .thumbnailUrl(coverUrl(doc.getCoverI()))
                .externalUrl(workId == null ? null : BASE_URL + "/works/" + workId)
                .build();
    }

    private BookMetadata mapWork(OpenLibraryWorkResponse work) {
        String workId = stripWorkPrefix(work.getKey());
        Integer coverId = work.getCovers() == null || work.getCovers().isEmpty() ? null : work.getCovers().getFirst();

        return BookMetadata.builder()
                .provider(MetadataProvider.OpenLibrary)
                .title(cleanString(work.getTitle()))
                .description(extractDescription(work.getDescription()))
                .publishedDate(parseDate(work.getFirstPublishDate()))
                .categories(cleanSubjects(work.getSubjects()))
                .thumbnailUrl(coverUrl(coverId))
                .externalUrl(workId == null ? null : BASE_URL + "/works/" + workId)
                .build();
    }

    private BookMetadata merge(BookMetadata searchMetadata, BookMetadata workMetadata) {
        return searchMetadata.toBuilder()
                .title(firstNonBlank(workMetadata.getTitle(), searchMetadata.getTitle()))
                .description(firstNonBlank(workMetadata.getDescription(), searchMetadata.getDescription()))
                .publishedDate(workMetadata.getPublishedDate() != null ? workMetadata.getPublishedDate() : searchMetadata.getPublishedDate())
                .categories(firstNonEmpty(workMetadata.getCategories(), searchMetadata.getCategories()))
                .thumbnailUrl(firstNonBlank(workMetadata.getThumbnailUrl(), searchMetadata.getThumbnailUrl()))
                .externalUrl(firstNonBlank(workMetadata.getExternalUrl(), searchMetadata.getExternalUrl()))
                .build();
    }

    private boolean isUsableResult(BookMetadata metadata) {
        return metadata.getTitle() != null && !metadata.getTitle().isBlank();
    }

    private Map<String, String> extractIsbns(List<String> rawIsbns) {
        if (rawIsbns == null || rawIsbns.isEmpty()) {
            return Map.of();
        }

        Map<String, String> result = new HashMap<>();
        for (String rawIsbn : rawIsbns) {
            String isbn = ParserUtils.cleanIsbn(rawIsbn);
            if (isbn == null) {
                continue;
            }
            if (!result.containsKey("ISBN_10") && isbn.length() == 10) {
                result.put("ISBN_10", isbn);
            } else if (!result.containsKey("ISBN_13") && isbn.length() == 13) {
                result.put("ISBN_13", isbn);
            }
            if (result.containsKey("ISBN_10") && result.containsKey("ISBN_13")) {
                break;
            }
        }
        return result;
    }

    private String extractDescription(Object rawDescription) {
        if (rawDescription == null) {
            return null;
        }
        String description;
        if (rawDescription instanceof String value) {
            description = value;
        } else if (rawDescription instanceof Map<?, ?> valueMap && valueMap.get("value") instanceof String value) {
            description = value;
        } else {
            return null;
        }

        String cleaned = Jsoup.parse(description).text();
        cleaned = WHITESPACE_PATTERN.matcher(cleaned.trim()).replaceAll(" ");
        return cleaned.isBlank() ? null : cleaned;
    }

    private Set<String> cleanSubjects(List<String> subjects) {
        if (subjects == null || subjects.isEmpty()) {
            return Set.of();
        }
        return subjects.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(subject -> !subject.isBlank())
                .limit(SUBJECT_LIMIT)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private List<String> cleanList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .toList();
    }

    private LocalDate parseYear(Integer year) {
        if (year == null || year < 1000 || year > 9999) {
            return null;
        }
        return LocalDate.of(year, 1, 1);
    }

    private LocalDate parseDate(String date) {
        if (date == null || date.isBlank() || date.length() < 4) {
            return null;
        }
        try {
            int year = Integer.parseInt(date.substring(0, 4));
            return parseYear(year);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String coverUrl(Integer coverId) {
        return coverId == null ? null : COVERS_BASE_URL + "/b/id/" + coverId + "-L.jpg";
    }

    private String stripWorkPrefix(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        return WORK_PREFIX.matcher(key).replaceFirst("");
    }

    private String extractWorkId(String externalUrl) {
        if (externalUrl == null || externalUrl.isBlank()) {
            return null;
        }
        int index = externalUrl.lastIndexOf("/works/");
        if (index < 0) {
            return null;
        }
        String id = externalUrl.substring(index + "/works/".length());
        return id.isBlank() ? null : id;
    }

    private String cleanString(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return WHITESPACE_PATTERN.matcher(value.trim()).replaceAll(" ");
    }

    private String first(List<String> values) {
        return values == null || values.isEmpty() ? null : cleanString(values.getFirst());
    }

    private String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    private <T extends Collection<String>> T firstNonEmpty(T first, T second) {
        return first != null && !first.isEmpty() ? first : second;
    }
}
