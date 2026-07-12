package org.booklore.service.metadata.parser;

import lombok.extern.slf4j.Slf4j;
import org.booklore.model.dto.Book;
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
    private static final int    BATCH_SIZE       = 30;

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
    public void preFetchBooks(List<Book> books) {
        prefetchCache.clear();
        if (books == null || books.isEmpty()) return;

        String effectiveUrl   = runtimeBaseUrl != null ? runtimeBaseUrl : baseUrl;
        String effectiveModel = runtimeModel   != null ? runtimeModel   : model;

        log.info("Ollama: batch pre-fetching metadata for {} books in groups of {}", books.size(), BATCH_SIZE);

        for (int i = 0; i < books.size(); i += BATCH_SIZE) {
            List<Book> batch = books.subList(i, Math.min(i + BATCH_SIZE, books.size()));
            try {
                fetchBatch(effectiveUrl, effectiveModel, batch);
            } catch (Exception e) {
                log.warn("Ollama: batch {}/{} failed: {}", (i / BATCH_SIZE) + 1,
                        (books.size() + BATCH_SIZE - 1) / BATCH_SIZE, e.getMessage());
            }
        }
        log.info("Ollama: pre-fetch complete — {} books cached", prefetchCache.size());
    }

    private void fetchBatch(String effectiveUrl, String effectiveModel, List<Book> batch) {
        // Build a compact list of {id, title, author} for the prompt
        StringBuilder bookList = new StringBuilder();
        for (Book b : batch) {
            String title  = b.getMetadata() != null ? b.getMetadata().getTitle()  : b.getTitle();
            String author = b.getMetadata() != null && b.getMetadata().getAuthors() != null
                    ? String.join(", ", b.getMetadata().getAuthors()) : null;
            bookList.append("  {\"id\":").append(b.getId())
                    .append(",\"title\":\"").append(escape(title)).append("\"");
            if (author != null) bookList.append(",\"author\":\"").append(escape(author)).append("\"");
            bookList.append("}\n");
        }

        String prompt = "You are a book metadata assistant with extensive knowledge of published books.\n" +
                "For EACH book in the list below, return a JSON array where each element contains:\n" +
                "id (integer, copy from input), isbn13 (string or null), isbn10 (string or null), " +
                "publisher (string or null), publishedYear (integer or null), " +
                "description (string or null, max 300 chars), " +
                "categories (array of strings or null), language (2-letter code or null).\n" +
                "Return ONLY the JSON array — no prose, no markdown, no code fences.\n\n" +
                "Books:\n[\n" + bookList + "]";

        String raw = callOllama(effectiveUrl, effectiveModel, prompt);
        if (raw == null) return;

        parseBatchResponse(raw, batch);
    }

    private void parseBatchResponse(String raw, List<Book> batch) {
        // Build a fallback id-to-book map
        Map<Long, Book> idMap = new java.util.HashMap<>();
        for (Book b : batch) idMap.put(b.getId(), b);

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
                    Book b = batch.get(0);
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
        // Fast path: return cached result from preFetchBooks
        if (prefetchCache.containsKey(book.getId())) {
            BookMetadata cached = prefetchCache.get(book.getId());
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

        // Return null if we got nothing useful
        if (meta.getIsbn13() == null && meta.getIsbn10() == null
                && meta.getDescription() == null && meta.getPublisher() == null) {
            return null;
        }
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

    private String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", "");
    }
}
