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
import tools.jackson.databind.node.ObjectNode;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Metadata provider that queries a locally-hosted Ollama LLM to infer book metadata.
 *
 * Ollama is called with a structured-output prompt asking for ISBN, publisher, description,
 * and categories. The model uses its training-data knowledge — this works best for
 * commercially published books the model has seen.
 *
 * Configure via environment variables:
 *   OLLAMA_BASE_URL  (default: http://ollama:11434)
 *   OLLAMA_MODEL     (default: llama3.1:8b)
 */
@Slf4j
@Service
public class OllamaMetadataParser implements BookParser {

    private static final String DEFAULT_BASE_URL = "http://ollama:11434";
    private static final String DEFAULT_MODEL    = "llama3.1:8b";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    @Value("${ollama.base-url:" + DEFAULT_BASE_URL + "}")
    private String baseUrl;

    @Value("${ollama.model:" + DEFAULT_MODEL + "}")
    private String model;

    /** Runtime override — set by OllamaMetadataParser when settings are saved. */
    private String runtimeBaseUrl;
    private String runtimeModel;

    public OllamaMetadataParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.restClient   = RestClient.create();
    }

    public void configure(String baseUrl, String model) {
        this.runtimeBaseUrl = (baseUrl != null && !baseUrl.isBlank()) ? baseUrl : null;
        this.runtimeModel   = (model   != null && !model.isBlank())   ? model   : null;
    }

    @Override
    public List<BookMetadata> fetchMetadata(Book book, FetchMetadataRequest request) {
        BookMetadata result = fetchTopMetadata(book, request);
        return result == null ? Collections.emptyList() : List.of(result);
    }

    @Override
    public BookMetadata fetchTopMetadata(Book book, FetchMetadataRequest request) {
        String effectiveUrl   = runtimeBaseUrl != null ? runtimeBaseUrl : baseUrl;
        String effectiveModel = runtimeModel   != null ? runtimeModel   : model;

        String title  = request.getTitle()  != null ? request.getTitle()  :
                        (book.getMetadata() != null ? book.getMetadata().getTitle()  : null);
        String author = request.getAuthor() != null ? request.getAuthor() :
                        (book.getMetadata() != null && book.getMetadata().getAuthors() != null
                            ? String.join(", ", book.getMetadata().getAuthors()) : null);

        if (title == null || title.isBlank()) {
            log.debug("Ollama: skipping book id={} — no title available", book.getId());
            return null;
        }

        String prompt = buildPrompt(title, author, request.getIsbn());
        log.debug("Ollama: querying {} with model {} for book id={} title='{}'",
                effectiveUrl, effectiveModel, book.getId(), title);

        try {
            String responseJson = callOllama(effectiveUrl, effectiveModel, prompt);
            return parseResponse(responseJson, title);
        } catch (Exception e) {
            log.warn("Ollama: failed for book id={} title='{}': {}", book.getId(), title, e.getMessage());
            return null;
        }
    }

    // -----------------------------------------------------------------------
    // Prompt
    // -----------------------------------------------------------------------

    private String buildPrompt(String title, String author, String isbn) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are a book metadata assistant with extensive knowledge of published books.\n");
        sb.append("Given the book information below, return ONLY a valid JSON object — no prose, no markdown, no code fences.\n\n");
        sb.append("Book title: ").append(title).append("\n");
        if (author != null && !author.isBlank())
            sb.append("Author: ").append(author).append("\n");
        if (isbn != null && !isbn.isBlank())
            sb.append("Known ISBN hint: ").append(isbn).append("\n");
        sb.append("\nRespond with ONLY this JSON (use null for any unknown fields):\n");
        sb.append("{\n");
        sb.append("  \"isbn13\": \"978...\",\n");
        sb.append("  \"isbn10\": \"...\",\n");
        sb.append("  \"publisher\": \"...\",\n");
        sb.append("  \"publishedYear\": 2020,\n");
        sb.append("  \"description\": \"...\",\n");
        sb.append("  \"categories\": [\"Fiction\", \"Science Fiction\"],\n");
        sb.append("  \"language\": \"en\"\n");
        sb.append("}");
        return sb.toString();
    }

    // -----------------------------------------------------------------------
    // HTTP
    // -----------------------------------------------------------------------

    private String callOllama(String baseUrl, String model, String prompt) {
        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("model", model);
        requestBody.put("stream", false);

        ObjectNode message = objectMapper.createObjectNode();
        message.put("role", "user");
        message.put("content", prompt);
        requestBody.set("messages", objectMapper.createArrayNode().add(message));

        // Ask for JSON output
        ObjectNode format = objectMapper.createObjectNode();
        format.put("type", "json_object");
        requestBody.set("format", format);

        String endpoint = baseUrl.replaceAll("/$", "") + "/api/chat";

        return restClient.post()
                .uri(endpoint)
                .header("Content-Type", "application/json")
                .body(requestBody.toString())
                .retrieve()
                .body(String.class);
    }

    // -----------------------------------------------------------------------
    // Response parsing
    // -----------------------------------------------------------------------

    private BookMetadata parseResponse(String raw, String fallbackTitle) {
        if (raw == null || raw.isBlank()) return null;
        try {
            JsonNode root = objectMapper.readTree(raw);

            // Ollama wraps in {"message":{"content":"..."}}
            String content = null;
            if (root.has("message") && root.get("message").has("content")) {
                content = root.get("message").get("content").asText();
            } else if (root.has("content")) {
                content = root.get("content").asText();
            } else {
                content = raw;
            }

            if (content == null || content.isBlank()) return null;

            // Strip any accidental markdown fences
            content = content.replaceAll("(?s)```json\\s*", "").replaceAll("(?s)```\\s*", "").trim();

            JsonNode data = objectMapper.readTree(content);

            BookMetadata meta = new BookMetadata();
            meta.setProvider(MetadataProvider.Ollama);

            meta.setIsbn13(nullableText(data, "isbn13"));
            meta.setIsbn10(nullableText(data, "isbn10"));
            meta.setPublisher(nullableText(data, "publisher"));
            meta.setDescription(nullableText(data, "description"));
            meta.setLanguage(nullableText(data, "language"));

            if (data.has("publishedYear") && !data.get("publishedYear").isNull()) {
                int year = data.get("publishedYear").asInt(0);
                if (year > 0) meta.setPublishedDate(LocalDate.of(year, 1, 1));
            }

            if (data.has("categories") && data.get("categories").isArray()) {
                List<String> cats = new ArrayList<>();
                for (JsonNode cat : data.get("categories")) {
                    String val = cat.asText("").trim();
                    if (!val.isBlank()) cats.add(val);
                }
                if (!cats.isEmpty()) meta.setCategories(new LinkedHashSet<>(cats));
            }

            // Sanity check: if ISBN looks wrong, discard it
            if (meta.getIsbn13() != null && !meta.getIsbn13().replaceAll("[^0-9]", "").matches("\\d{13}")) {
                log.debug("Ollama: discarding malformed isbn13 '{}'", meta.getIsbn13());
                meta.setIsbn13(null);
            }
            if (meta.getIsbn10() != null && !meta.getIsbn10().replaceAll("[^0-9Xx]", "").matches("[0-9]{9}[0-9Xx]")) {
                log.debug("Ollama: discarding malformed isbn10 '{}'", meta.getIsbn10());
                meta.setIsbn10(null);
            }

            return meta;
        } catch (Exception e) {
            log.warn("Ollama: could not parse response JSON: {} — raw={}", e.getMessage(), raw);
            return null;
        }
    }

    private String nullableText(JsonNode node, String field) {
        if (!node.has(field) || node.get(field).isNull()) return null;
        String val = node.get(field).asText("").trim();
        return val.isBlank() || val.equalsIgnoreCase("null") ? null : val;
    }
}
