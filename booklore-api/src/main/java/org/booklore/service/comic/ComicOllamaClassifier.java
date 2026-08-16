package org.booklore.service.comic;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.model.entity.CategoryEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Asks the local LLM whether a title looks like a comic, used only to break ties
 * in the borderline band. Structural evidence always outranks this.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ComicOllamaClassifier {

    private static final String DEFAULT_BASE_URL = "http://ollama:11434";
    private static final String DEFAULT_MODEL = "llama3.1:8b";

    private final ObjectMapper objectMapper;
    private final RestClient restClient = RestClient.create();

    @Value("${ollama.base-url:" + DEFAULT_BASE_URL + "}")
    private String ollamaBaseUrl;

    @Value("${ollama.model:" + DEFAULT_MODEL + "}")
    private String ollamaModel;

    public void analyze(BookMetadataEntity metadata, ComicScoreCard card) {
        if (metadata == null) return;

        String title = safe(metadata.getTitle());
        if (title.isBlank()) return;

        String prompt = """
                You are classifying a book from a personal library catalogue.
                Decide whether it is a comic, graphic novel, manga, manhwa or webtoon \
                as opposed to a prose book (novel, non-fiction, textbook, poetry).

                Return ONLY a JSON object with exactly two fields:
                  "comic": true or false
                  "confidence": a number from 0 to 1

                No prose, no markdown, no code fences.

                Title: %s
                Series: %s
                Publisher: %s
                Categories: %s
                """.formatted(title,
                safe(metadata.getSeriesName()),
                safe(metadata.getPublisher()),
                categoryList(metadata));

        try {
            String content = extractContent(callOllama(prompt));
            if (content == null) return;

            content = content.replaceAll("(?s)```json\\s*", "").replaceAll("(?s)```\\s*", "").trim();
            JsonNode node = objectMapper.readTree(content);

            if (!node.has("comic")) return;
            boolean comic = node.get("comic").asBoolean(false);
            double confidence = node.has("confidence") ? node.get("confidence").asDouble(0d) : 0d;

            // Ignore low-confidence opinions rather than letting them nudge the score.
            if (confidence < 0.6d) {
                log.debug("ComicDetect: LLM unsure about '{}' (confidence {})", title, confidence);
                return;
            }

            if (comic) {
                card.add("LLM_COMIC", 25,
                        String.format("LLM classified as a comic (confidence %.2f)", confidence));
            } else {
                card.add("LLM_NOT_COMIC", -25,
                        String.format("LLM classified as prose (confidence %.2f)", confidence));
            }
        } catch (Exception e) {
            log.debug("ComicDetect: LLM classification failed for '{}': {}", title, e.getMessage());
        }
    }

    private String categoryList(BookMetadataEntity metadata) {
        Set<CategoryEntity> categories = metadata.getCategories();
        if (categories == null || categories.isEmpty()) return "(none)";
        return categories.stream()
                .map(CategoryEntity::getName)
                .filter(n -> n != null && !n.isBlank())
                .limit(8)
                .collect(Collectors.joining(", "));
    }

    private String callOllama(String prompt) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", ollamaModel);
        body.put("stream", false);
        body.put("format", "json");

        ArrayNode messages = objectMapper.createArrayNode();
        ObjectNode msg = objectMapper.createObjectNode();
        msg.put("role", "user");
        msg.put("content", prompt);
        messages.add(msg);
        body.set("messages", messages);

        return restClient.post()
                .uri(ollamaBaseUrl.replaceAll("/$", "") + "/api/chat")
                .header("Content-Type", "application/json")
                .body(body.toString())
                .retrieve()
                .body(String.class);
    }

    private String extractContent(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            JsonNode root = objectMapper.readTree(raw);
            if (root.has("message") && root.get("message").has("content")) {
                return root.get("message").get("content").asText();
            }
            return raw;
        } catch (Exception e) {
            return raw;
        }
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
