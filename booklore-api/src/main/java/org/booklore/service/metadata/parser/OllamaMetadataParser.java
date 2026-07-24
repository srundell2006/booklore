package org.booklore.service.metadata.parser;

import lombok.extern.slf4j.Slf4j;
import org.booklore.model.dto.Book;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.AuthorEntity;
import org.booklore.model.dto.BookMetadata;
import org.booklore.model.dto.request.FetchMetadataRequest;
import org.booklore.model.enums.MetadataProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Metadata provider that queries a locally-hosted Ollama LLM to infer book metadata.
 *
 * Pre-fetch mode (fast path): before the per-book parallel loop,
 * preFetchBooks() batches all books 30 at a time into single Ollama requests.
 * Results are cached; fetchTopMetadata() simply reads from cache.
 *
 * Single-book mode (fallback): if preFetchBooks() was not called (e.g. manual
 * single-book fetch), fetchTopMetadata() calls Ollama directly.
 *
 * Configure via Settings → Metadata Providers → Ollama, or env vars:
 *   OLLAMA_BASE_URL  (default: http://ollama:11434)
 *   OLLAMA_MODEL     (default: llama3.1:8b)
 */
@Slf4j
@Service
public class OllamaMetadataParser implements BookParser {

    private static final String DEFAULT_BASE_URL = "http://ollama:11434";
    private static final String DEFAULT_MODEL    = "llama3.1:8b";
    private static final int    BATCH_SIZE        = 10;
    private static final int    BATCH_CONCURRENCY = 5;

    /** Sentinel: book was in the batch but the model returned no usable data. Skip individual retry. */
    private static final BookMetadata BATCH_MISS = new BookMetadata();

    private final RestClient  restClient;
    private final ObjectMapper objectMapper;

    @Value("${ollama.base-url:" + DEFAULT_BASE_URL + "}")
    private String baseUrl;

    @Value("${ollama.model:" + DEFAULT_MODEL + "}")
    private String model;

    /** Runtime overrides set from provider settings. */
    private volatile String runtimeBaseUrl;
    private volatile String runtimeModel;

    /** Pre-fetch cache: bookId → metadata (populated by preFetchBooks). */
    private final Map<Long, BookMetadata> prefetchCache = new ConcurrentHashMap<>();

    public OllamaMetadataParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.restClient   = RestClient.create();
    }

    public void configure(String baseUrl, String model) {
        this.runtimeBaseUrl = (baseUrl != null && !baseUrl.isBlank()) ? baseUrl : null;
        this.runtimeModel   = (model   != null && !model.isBlank())   ? model   : null;
    }

    // -----------------------------------------------------------------------
    // Batch pre-fetch (called once before the parallel per-book loop)
    // -----------------------------------------------------------------------

    @Override
    public void preFetchBookEntities(java.util.Collection<BookEntity> books) {
        prefetchCache.clear();
        if (books == null || books.isEmpty()) return;
        List<BookEntity> bookList = new java.util.ArrayList<>(books);

        String effectiveUrl   = runtimeBaseUrl != null ? runtimeBaseUrl : baseUrl;
        String effectiveModel = runtimeModel   != null ? runtimeModel   : model;

        // Partition into individual 10-book batches
        List<List<BookEntity>> batches = new ArrayList<>();
        for (int i = 0; i < bookList.size(); i += BATCH_SIZE) {
            batches.add(Collections.unmodifiableList(
                    bookList.subList(i, Math.min(i + BATCH_SIZE, bookList.size()))));
        }

        log.info("Ollama: pre-fetching {} books — {} batches, {} concurrent",
                bookList.size(), batches.size(), BATCH_CONCURRENCY);

        // Fire up to BATCH_CONCURRENCY batch requests in parallel.
        // prefetchCache is ConcurrentHashMap so concurrent writes are safe.
        ExecutorService batchPool = Executors.newFixedThreadPool(BATCH_CONCURRENCY);
        try {
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (List<BookEntity> batch : batches) {
                futures.add(batchPool.submit(() -> {
                    try {
                        fetchBatch(effectiveUrl, effectiveModel, batch);
                    } catch (Exception e) {
                        log.warn("Ollama: batch failed: {}", e.getMessage());
                    }
                }));
            }
            for (java.util.concurrent.Future<?> f : futures) {
                try { f.get(); } catch (Exception e) {
                    log.warn("Ollama: batch join error: {}", e.getMessage());
                }
            }
        } finally {
            batchPool.shutdown();
        }
        log.info("Ollama: pre-fetch complete — {} cache entries", prefetchCache.size());
    }

    private void fetchBatch(String effectiveUrl, String effectiveModel, List<BookEntity> batch) {
        // Build a compact list of {id, title, author} for the prompt.
        // cleanTitle() strips series-number artefacts that confuse the model.
        StringBuilder bookList = new StringBuilder();
        for (BookEntity b : batch) {
            String rawTitle = b.getMetadata() != null ? b.getMetadata().getTitle() : null;
            String title    = cleanTitle(rawTitle);
            String author   = b.getMetadata() != null && b.getMetadata().getAuthors() != null
                    ? b.getMetadata().getAuthors().stream().map(AuthorEntity::getName)
                        .filter(java.util.Objects::nonNull)
                        .collect(java.util.stream.Collectors.joining(", ")) : null;
            bookList.append("  {\"id\":").append(b.getId())
                    .append(",\"title\":\"").append(escape(title)).append("\"");
            if (author != null) bookList.append(",\"author\":\"").append(escape(author)).append("\"");
            bookList.append("}\n");
        }

        String prompt = "You are a book metadata assistant. For EACH book below, return a JSON array.\n" +
                "Each element must have:\n" +
                "  id (integer, copy from input — required)\n" +
                "  description (string, 1-3 sentences about the book, or null if truly unknown)\n" +
                "  categories (array of genre strings, e.g. [\"Fiction\",\"Thriller\"], or null)\n" +
                "  publisher (string or null)\n" +
                "  publishedYear (integer or null)\n" +
                "  language (2-letter ISO code e.g. \"en\", or null)\n" +
                "  isbn13 (13-digit string — only include if you are certain; omit or null if unsure)\n" +
                "  isbn10 (10-char string — only include if you are certain; omit or null if unsure)\n" +
                "For books you don\'t recognise, include the id with null for all other fields.\n" +
                "Return ONLY the JSON array — no prose, no markdown, no code fences.\n\n" +
                "Books:\n[\n" + bookList + "]";

        String raw = callOllama(effectiveUrl, effectiveModel, prompt);
        if (raw == null) return;

        parseBatchResponse(raw, batch);
    }

    private void parseBatchResponse(String raw, List<BookEntity> batch) {
        // Build a fallback id-to-book map
        Map<Long, BookEntity> idMap = new java.util.HashMap<>();
        for (BookEntity b : batch) idMap.put(b.getId(), b);

        try {
            // Extract message content
            String content = extractContent(raw);
            if (content == null) return;

            // Strip accidental markdown fences
            content = content.replaceAll("(?s)```json\\s*", "").replaceAll("(?s)```\\s*", "").trim();

            JsonNode array = objectMapper.readTree(content);
            if (!array.isArray()) {
                log.warn("Ollama: batch response is not a JSON array — trying single-object fallback");
                // Some models return a single object even when asked for array
                if (array.isObject() && batch.size() == 1) {
                    BookEntity b = batch.get(0);
                    BookMetadata meta = nodeToMetadata(array);
                    if (meta != null) prefetchCache.put(b.getId(), meta);
                }
                return;
            }

            for (JsonNode node : array) {
                long id = node.has("id") ? node.get("id").asLong(-1) : -1;
                if (id <= 0) continue;
                BookMetadata meta = nodeToMetadata(node);
                if (meta != null) prefetchCache.put(id, meta);
            }
        } catch (Exception e) {
            log.warn("Ollama: failed to parse batch response: {}", e.getMessage());
        }
        // Mark every book that was in the batch but not cached as BATCH_MISS so
        // fetchTopMetadata skips the expensive individual fallback call.
        for (BookEntity b : batch) {
            prefetchCache.putIfAbsent(b.getId(), BATCH_MISS);
        }
    }

    // -----------------------------------------------------------------------
    // Per-book fetch (reads cache if available, otherwise calls Ollama directly)
    // -----------------------------------------------------------------------

    @Override
    public List<BookMetadata> fetchMetadata(Book book, FetchMetadataRequest request) {
        BookMetadata result = fetchTopMetadata(book, request);
        return result == null ? Collections.emptyList() : List.of(result);
    }

    @Override
    public BookMetadata fetchTopMetadata(Book book, FetchMetadataRequest request) {
        // This provider enriches books that already exist in the library; its cache is
        // keyed on book id. A caller without a persisted book (e.g. an add-new lookup)
        // has nothing for it to work with, and ConcurrentHashMap rejects null keys.
        if (book == null || book.getId() == null) {
            log.debug("Ollama: no persisted book id supplied — skipping");
            return null;
        }

        // Fast path: return cached result from preFetchBooks
        if (prefetchCache.containsKey(book.getId())) {
            BookMetadata cached = prefetchCache.get(book.getId());
            if (cached == BATCH_MISS) {
                log.debug("Ollama: batch already attempted book id={} with no result — skipping retry", book.getId());
                return null;
            }
            log.debug("Ollama: cache hit for book id={}", book.getId());
            return cached;
        }

        // Slow path: single-book request (manual fetch or preFetchBooks not called)
        String effectiveUrl   = runtimeBaseUrl != null ? runtimeBaseUrl : baseUrl;
        String effectiveModel = runtimeModel   != null ? runtimeModel   : model;

        String title  = request.getTitle()  != null ? request.getTitle()  :
                        (book.getMetadata() != null ? book.getMetadata().getTitle() : null);
        String author = request.getAuthor() != null ? request.getAuthor() :
                        (book.getMetadata() != null && book.getMetadata().getAuthors() != null
                            ? String.join(", ", book.getMetadata().getAuthors()) : null);

        if (title == null || title.isBlank()) {
            log.debug("Ollama: skipping book id={} — no title available", book.getId());
            return null;
        }

        String prompt = "You are a book metadata assistant with extensive knowledge of published books.\n" +
                "Return ONLY a valid JSON object for the book below — no prose, no markdown, no code fences.\n\n" +
                "Title: " + title + "\n" +
                (author != null ? "Author: " + author + "\n" : "") +
                (request.getIsbn() != null ? "Known ISBN: " + request.getIsbn() + "\n" : "") +
                "\nJSON fields (null if unknown): isbn13, isbn10, publisher, publishedYear, " +
                "description (max 300 chars), categories (array), language (2-letter code).";

        log.debug("Ollama: single-book query for id={} title='{}'", book.getId(), title);
        try {
            String raw = callOllama(effectiveUrl, effectiveModel, prompt);
            String content = extractContent(raw);
            if (content == null) return null;
            content = content.replaceAll("(?s)```json\\s*", "").replaceAll("(?s)```\\s*", "").trim();
            JsonNode data = objectMapper.readTree(content);
            return nodeToMetadata(data);
        } catch (Exception e) {
            log.warn("Ollama: failed for book id={} title='{}': {}", book.getId(), title, e.getMessage());
            return null;
        }
    }

    // -----------------------------------------------------------------------
    // HTTP
    // -----------------------------------------------------------------------

    private String callOllama(String baseUrl, String model, String prompt) {
        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("model", model);
        requestBody.put("stream", false);
        // Ollama JSON mode: plain string "json", not OpenAI's {"type":"json_object"}
        requestBody.put("format", "json");

        ArrayNode messages = objectMapper.createArrayNode();
        ObjectNode message = objectMapper.createObjectNode();
        message.put("role", "user");
        message.put("content", prompt);
        messages.add(message);
        requestBody.set("messages", messages);

        String endpoint = baseUrl.replaceAll("/$", "") + "/api/chat";
        return restClient.post()
                .uri(endpoint)
                .header("Content-Type", "application/json")
                .body(requestBody.toString())
                .retrieve()
                .body(String.class);
    }

    // -----------------------------------------------------------------------
    // Parsing helpers
    // -----------------------------------------------------------------------

    private String extractContent(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            JsonNode root = objectMapper.readTree(raw);
            if (root.has("message") && root.get("message").has("content"))
                return root.get("message").get("content").asText();
            if (root.has("content"))
                return root.get("content").asText();
            return raw;
        } catch (Exception e) {
            return raw;
        }
    }

    private BookMetadata nodeToMetadata(JsonNode node) {
        if (node == null || node.isNull()) return null;
        BookMetadata meta = new BookMetadata();
        meta.setProvider(MetadataProvider.Ollama);

        meta.setIsbn13(validateIsbn13(nullableText(node, "isbn13")));
        meta.setIsbn10(validateIsbn10(nullableText(node, "isbn10")));
        meta.setPublisher(nullableText(node, "publisher"));
        meta.setDescription(nullableText(node, "description"));
        meta.setLanguage(nullableText(node, "language"));

        if (node.has("publishedYear") && !node.get("publishedYear").isNull()) {
            int year = node.get("publishedYear").asInt(0);
            if (year > 1000 && year <= 2100) meta.setPublishedDate(LocalDate.of(year, 1, 1));
        }

        if (node.has("categories") && node.get("categories").isArray()) {
            List<String> cats = new ArrayList<>();
            for (JsonNode cat : node.get("categories")) {
                String val = cat.asText("").trim();
                if (!val.isBlank()) cats.add(val);
            }
            if (!cats.isEmpty()) meta.setCategories(new LinkedHashSet<>(cats));
        }

        // Return null only if we got absolutely nothing useful.
        // Accept if ANY of isbn, description, publisher, categories, or publishedDate is present —
        // categories and publication year are valuable even without an ISBN.
        boolean hasAnything = meta.getIsbn13() != null || meta.getIsbn10() != null
                || meta.getDescription() != null || meta.getPublisher() != null
                || meta.getPublishedDate() != null
                || (meta.getCategories() != null && !meta.getCategories().isEmpty());
        if (!hasAnything) return null;
        return meta;
    }

    private String nullableText(JsonNode node, String field) {
        if (!node.has(field) || node.get(field).isNull()) return null;
        String val = node.get(field).asText("").trim();
        return (val.isBlank() || val.equalsIgnoreCase("null") || val.equals("unknown")) ? null : val;
    }

    private String validateIsbn13(String isbn) {
        if (isbn == null) return null;
        String digits = isbn.replaceAll("[^0-9]", "");
        return digits.length() == 13 ? digits : null;
    }

    private String validateIsbn10(String isbn) {
        if (isbn == null) return null;
        String cleaned = isbn.replaceAll("[^0-9Xx]", "");
        return cleaned.length() == 10 ? cleaned : null;
    }

    /**
     * Strip common filename artefacts from book titles before sending to the LLM.
     * Examples:
     *   "[Bound and Bonded 03] • Total Submission"  → "Total Submission"
     *   "3 - Broken Crown"                          → "Broken Crown"
     *   "004 Batman - Got A Date"                   → "Batman - Got A Date"
     *   "3 Shades of Blue"                          → unchanged (real title)
     *
     * Only strips when the prefix pattern is clearly a series/file artefact:
     *   bracket-enclosed content followed by • separator, OR
     *   pure digit(s) followed by a dash/dot separator.
     * Does NOT strip bare "N word word" patterns to avoid destroying real titles.
     */
    private String cleanTitle(String title) {
        if (title == null) return null;
        // "[Series Name N] • Real Title" → "Real Title"
        String t = title.replaceAll("^\\[.*?\\]\\s*[•·]\\s*", "").trim();
        // "003 Title" or "03 - Title" → "Title" (digits-only prefix + separator or space-then-caps)
        t = t.replaceAll("^\\d{2,}\\s*[-–]\\s*", "").trim();
        return t.isBlank() ? title : t;
    }

    private String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", "");
    }
}
