package org.booklore.service.audiobook;

import lombok.extern.slf4j.Slf4j;
import org.booklore.exception.ApiError;
import org.booklore.mapper.BookMapper;
import org.booklore.model.dto.Book;
import org.booklore.model.dto.request.AudiobookVerificationRequest;
import org.booklore.model.dto.settings.AudiobookVerificationSettings;
import org.booklore.model.entity.AuthorEntity;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.model.enums.PermissionType;
import org.booklore.model.websocket.Topic;
import org.booklore.repository.AuthorRepository;
import org.booklore.repository.BookMetadataRepository;
import org.booklore.repository.BookRepository;
import org.booklore.service.NotificationService;
import org.booklore.service.appsettings.AppSettingService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Verifies that an audiobook's audio content matches its stored metadata (title / author).
 *
 * Pipeline:
 *   1. Transcribe the first N seconds of audio via the whisper-sidecar (/transcribe).
 *   2. Ask Ollama to extract title + author(s) from the transcript.
 *   3. Compare extracted values with the stored BookMetadataEntity using token-overlap.
 *   4. Persist the result (VERIFIED | MISMATCH | SKIPPED | ERROR) to book_metadata.
 *   5. Broadcast Topic.BOOK_UPDATE so the frontend refreshes without a manual reload.
 */
@Slf4j
@Service
public class AudiobookVerificationService {

    private final AppSettingService appSettingService;
    private final VerificationContextLoader contextLoader;
    private final BookMetadataRepository bookMetadataRepository;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;
    private final BookRepository bookRepository;
    private final BookMapper bookMapper;
    private final NotificationService notificationService;
    private final TransactionTemplate transactionTemplate;
    private final AuthorRepository authorRepository;

    @Value("${ollama.base-url:http://ollama:11434}")
    private String defaultOllamaUrl;

    /** Minimum token-overlap (Jaccard) score to consider a field a match. */
    private static final double MATCH_THRESHOLD = 0.4;
    /** Tokens shorter than this are ignored during comparison. */
    private static final int MIN_TOKEN_LENGTH = 3;

    public AudiobookVerificationService(AppSettingService appSettingService,
                                        VerificationContextLoader contextLoader,
                                        BookMetadataRepository bookMetadataRepository,
                                        ObjectMapper objectMapper,
                                        BookRepository bookRepository,
                                        BookMapper bookMapper,
                                        NotificationService notificationService,
                                        TransactionTemplate transactionTemplate,
                                        AuthorRepository authorRepository) {
        this.appSettingService = appSettingService;
        this.contextLoader = contextLoader;
        this.bookMetadataRepository = bookMetadataRepository;
        this.objectMapper = objectMapper;
        this.bookRepository = bookRepository;
        this.bookMapper = bookMapper;
        this.notificationService = notificationService;
        this.transactionTemplate = transactionTemplate;
        this.authorRepository = authorRepository;
        this.restClient = RestClient.create();
    }

    // -----------------------------------------------------------------------
    // Public entry points
    // -----------------------------------------------------------------------

    /**
     * Run verification according to the supplied request (called by AudiobookVerificationTask).
     */
    public void runVerification(AudiobookVerificationRequest request, String taskId) {
        AudiobookVerificationSettings settings = resolveSettings();
        if (settings == null) return;

        List<Long> bookIds = resolveBookIds(request, taskId);
        log.info("Verification task {}: processing {} books", taskId, bookIds.size());

        for (Long bookId : bookIds) {
            try {
                verifyBook(bookId, settings);
            } catch (Exception e) {
                log.error("Verification failed for book {}: {}", bookId, e.getMessage(), e);
                persistResult(bookId, "ERROR", null, null, "Exception: " + e.getMessage());
            }
        }
    }

    /**
     * Convenience entry point for fire-and-forget single-book verification
     * (e.g. called after an audiobook is imported via bookdrop).
     */
    public void scheduleVerificationForBook(long bookId) {
        AudiobookVerificationSettings settings = resolveSettings();
        if (settings == null) return;
        try {
            verifyBook(bookId, settings);
        } catch (Exception e) {
            log.error("Auto-verify failed for book {}: {}", bookId, e.getMessage(), e);
            persistResult(bookId, "ERROR", null, null, "Exception: " + e.getMessage());
        }
    }

    /**
     * Applies the Whisper/Ollama-detected title and authors to the book's actual metadata,
     * then flips verification_status to VERIFIED (the user is accepting the detected values).
     *
     * @param bookId the book whose detected metadata should be applied
     * @return the refreshed Book DTO after the update
     */
    public Book applyDetectedMetadata(long bookId) {
        Book bookDto = transactionTemplate.execute(tx -> {
            BookMetadataEntity metadata = bookMetadataRepository.findById(bookId)
                    .orElseThrow(() -> ApiError.GENERIC_NOT_FOUND.createException(
                            "Book not found: " + bookId));

            String detectedTitle   = metadata.getVerificationDetectedTitle();
            String detectedAuthors = metadata.getVerificationDetectedAuthors();

            if ((detectedTitle == null || detectedTitle.isBlank()) &&
                (detectedAuthors == null || detectedAuthors.isBlank())) {
                throw ApiError.GENERIC_BAD_REQUEST.createException(
                        "No detected metadata to apply for book " + bookId);
            }

            // Apply detected title
            if (detectedTitle != null && !detectedTitle.isBlank()) {
                metadata.setTitle(detectedTitle.trim());
            }

            // Apply detected authors — split on comma, find-or-create AuthorEntity for each
            if (detectedAuthors != null && !detectedAuthors.isBlank()) {
                List<AuthorEntity> resolvedAuthors = Arrays.stream(detectedAuthors.split(","))
                        .map(String::trim)
                        .filter(name -> !name.isEmpty())
                        .map(name -> authorRepository.findByNameIgnoreCase(name)
                                .orElseGet(() -> {
                                    AuthorEntity a = new AuthorEntity();
                                    a.setName(name);
                                    return authorRepository.save(a);
                                }))
                        .toList();

                if (metadata.getAuthors() == null) {
                    metadata.setAuthors(new ArrayList<>(resolvedAuthors));
                } else {
                    metadata.getAuthors().clear();
                    metadata.getAuthors().addAll(resolvedAuthors);
                }
            }

            // Accepting the detected values — flip status to VERIFIED, clear mismatch reason
            metadata.setVerificationStatus("VERIFIED");
            metadata.setVerificationMismatchReason(null);

            bookMetadataRepository.save(metadata);

            // Build the DTO within the transaction so lazy associations can be loaded
            return bookRepository.findByIdWithBookFiles(bookId)
                    .map(book -> bookMapper.toBookWithDescription(book, true))
                    .orElse(null);
        });

        // Send BOOK_UPDATE notification outside the transaction (same pattern as persistResult)
        if (bookDto != null) {
            try {
                notificationService.sendMessageToPermissions(
                        Topic.BOOK_UPDATE, bookDto, Set.of(PermissionType.DOWNLOAD));
            } catch (Exception e) {
                log.warn("Failed to send BOOK_UPDATE after applying detected metadata for book {}: {}",
                        bookId, e.getMessage());
            }
        }

        return bookDto;
    }

    // -----------------------------------------------------------------------
    // Core verification
    // -----------------------------------------------------------------------

    private void verifyBook(long bookId, AudiobookVerificationSettings settings) {
        VerificationContextLoader.VerificationContext ctx = contextLoader.load(bookId);
        if (ctx == null) {
            persistResult(bookId, "SKIPPED", null, null, "Not a verifiable audiobook or missing audio file");
            return;
        }

        log.info("Verifying book {}: title='{}', authors='{}'", bookId, ctx.title(), ctx.authors());

        // Step 1: transcribe
        String transcript = transcribeAudio(ctx.filePath(), settings);
        if (transcript == null || transcript.isBlank()) {
            persistResult(bookId, "ERROR", null, null, "Whisper returned an empty transcript");
            return;
        }
        log.debug("Book {}: transcript excerpt: {}", bookId,
                transcript.substring(0, Math.min(200, transcript.length())));

        // Step 2: extract metadata from transcript
        ExtractedMetadata extracted = extractMetadataFromTranscript(transcript, settings);
        if (extracted == null) {
            persistResult(bookId, "ERROR", null, null, "Ollama failed to extract metadata from transcript");
            return;
        }
        log.info("Book {}: Ollama detected title='{}', authors='{}'", bookId, extracted.title(), extracted.authors());

        // Step 3: compare
        boolean titleMatch = tokenOverlapMatch(ctx.title(), extracted.title());
        boolean authorMatch = isAuthorMatchIrrelevant(ctx, extracted)
                || tokenOverlapMatch(ctx.authors(), extracted.authors());

        if (titleMatch && authorMatch) {
            persistResult(bookId, "VERIFIED", extracted.title(), extracted.authors(), null);
            log.info("Book {}: VERIFIED", bookId);
        } else {
            String reason = buildMismatchReason(ctx, extracted, titleMatch, authorMatch);
            persistResult(bookId, "MISMATCH", extracted.title(), extracted.authors(), reason);
            log.info("Book {}: MISMATCH — {}", bookId, reason);
        }
    }

    // -----------------------------------------------------------------------
    // Whisper
    // -----------------------------------------------------------------------

    private String transcribeAudio(String filePath, AudiobookVerificationSettings settings) {
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("file_path", filePath);
            body.put("duration_seconds", settings.getExcerptSeconds());

            String whisperBase = settings.getWhisperUrl().replaceAll("/$", "");
            String raw = restClient.post()
                    .uri(whisperBase + "/transcribe")
                    .header("Content-Type", "application/json")
                    .body(body.toString())
                    .retrieve()
                    .body(String.class);

            if (raw == null) return null;
            JsonNode node = objectMapper.readTree(raw);
            return node.has("transcript") ? node.get("transcript").asText() : null;
        } catch (Exception e) {
            log.warn("Whisper transcription failed for '{}': {}", filePath, e.getMessage());
            return null;
        }
    }

    // -----------------------------------------------------------------------
    // Ollama extraction
    // -----------------------------------------------------------------------

    private ExtractedMetadata extractMetadataFromTranscript(String transcript,
                                                            AudiobookVerificationSettings settings) {
        String ollamaBase = resolveOllamaBase(settings);
        String ollamaModel = resolveOllamaModel(settings);

        String prompt = "You are an audiobook metadata extractor. " +
                "From the following transcript of the very beginning of an audiobook, " +
                "identify the book title and the author name(s). " +
                "Return ONLY a JSON object with exactly two fields:\n" +
                "  \"title\": (string or null)\n" +
                "  \"authors\": (comma-separated author names as a single string, or null)\n" +
                "Set a field to null if you cannot determine it with confidence.\n\n" +
                "Transcript:\n" +
                transcript.substring(0, Math.min(2000, transcript.length()));

        try {
            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.put("model", ollamaModel);
            requestBody.put("stream", false);
            requestBody.put("format", "json");

            ArrayNode messages = objectMapper.createArrayNode();
            ObjectNode message = objectMapper.createObjectNode();
            message.put("role", "user");
            message.put("content", prompt);
            messages.add(message);
            requestBody.set("messages", messages);

            String raw = restClient.post()
                    .uri(ollamaBase + "/api/chat")
                    .header("Content-Type", "application/json")
                    .body(requestBody.toString())
                    .retrieve()
                    .body(String.class);

            if (raw == null) return null;

            JsonNode root = objectMapper.readTree(raw);
            String content = null;
            if (root.has("message") && root.get("message").has("content")) {
                content = root.get("message").get("content").asText();
            } else if (root.has("content")) {
                content = root.get("content").asText();
            }
            if (content == null) return null;

            // Strip accidental markdown fences
            content = content.replaceAll("(?s)```json\\s*", "").replaceAll("(?s)```\\s*", "").trim();

            JsonNode data = objectMapper.readTree(content);
            return new ExtractedMetadata(nullableText(data, "title"), nullableText(data, "authors"));
        } catch (Exception e) {
            log.warn("Ollama metadata extraction failed: {}", e.getMessage());
            return null;
        }
    }

    // -----------------------------------------------------------------------
    // Comparison
    // -----------------------------------------------------------------------

    private boolean tokenOverlapMatch(String stored, String detected) {
        if (stored == null || stored.isBlank() || detected == null || detected.isBlank()) return false;
        Set<String> storedTokens = tokenize(stored);
        Set<String> detectedTokens = tokenize(detected);
        if (storedTokens.isEmpty() || detectedTokens.isEmpty()) return false;

        Set<String> intersection = new HashSet<>(storedTokens);
        intersection.retainAll(detectedTokens);

        Set<String> union = new HashSet<>(storedTokens);
        union.addAll(detectedTokens);

        double similarity = (double) intersection.size() / union.size();
        log.debug("Token overlap: stored='{}' detected='{}' similarity={}", stored, detected, similarity);
        return similarity >= MATCH_THRESHOLD;
    }

    private Set<String> tokenize(String text) {
        return Arrays.stream(text.toLowerCase().split("[^a-z0-9]+"))
                .filter(t -> t.length() >= MIN_TOKEN_LENGTH)
                .collect(Collectors.toSet());
    }

    /** Author match is irrelevant when either side has no author data to compare. */
    private boolean isAuthorMatchIrrelevant(VerificationContextLoader.VerificationContext ctx,
                                             ExtractedMetadata extracted) {
        return (ctx.authors() == null || ctx.authors().isBlank())
                || (extracted.authors() == null || extracted.authors().isBlank());
    }

    private String buildMismatchReason(VerificationContextLoader.VerificationContext ctx,
                                       ExtractedMetadata detected,
                                       boolean titleMatch,
                                       boolean authorMatch) {
        StringBuilder sb = new StringBuilder();
        if (!titleMatch) {
            sb.append("Title mismatch: stored='").append(ctx.title())
              .append("' detected='").append(detected.title()).append("'");
        }
        if (!authorMatch) {
            if (!sb.isEmpty()) sb.append("; ");
            sb.append("Author mismatch: stored='").append(ctx.authors())
              .append("' detected='").append(detected.authors()).append("'");
        }
        return sb.toString();
    }

    // -----------------------------------------------------------------------
    // Persistence + notification
    // -----------------------------------------------------------------------

    private void persistResult(long bookId, String status, String detectedTitle,
                                String detectedAuthors, String mismatchReason) {
        bookMetadataRepository.updateVerificationResult(
                bookId, status, detectedTitle, detectedAuthors, Instant.now(), mismatchReason);
        // Build the DTO inside a transaction so lazy collections (e.g. authors) can be
        // loaded by Hibernate. We capture the result and send the notification after the
        // transaction closes so we are not holding a DB connection during the WS send.
        //
        // We use sendMessageToPermissions instead of sendMessage because verification
        // tasks run in virtual threads with no Spring SecurityContext, which causes
        // sendMessage to silently no-op (getAuthenticatedUser() returns null).
        try {
            Book bookDto = transactionTemplate.execute(tx ->
                    bookRepository.findByIdWithBookFiles(bookId)
                            .map(book -> bookMapper.toBookWithDescription(book, true))
                            .orElse(null));
            if (bookDto != null) {
                notificationService.sendMessageToPermissions(
                        Topic.BOOK_UPDATE, bookDto, Set.of(PermissionType.DOWNLOAD));
            }
        } catch (Exception e) {
            log.warn("Failed to send BOOK_UPDATE for book {}: {}", bookId, e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private AudiobookVerificationSettings resolveSettings() {
        AudiobookVerificationSettings settings =
                appSettingService.getAppSettings().getAudiobookVerificationSettings();
        if (settings == null || !settings.isEnabled()) {
            log.debug("Audiobook verification is disabled — skipping");
            return null;
        }
        if (settings.getWhisperUrl() == null || settings.getWhisperUrl().isBlank()) {
            log.warn("Audiobook verification is enabled but whisperUrl is not configured — skipping");
            return null;
        }
        return settings;
    }

    private List<Long> resolveBookIds(AudiobookVerificationRequest request, String taskId) {
        if (request == null || request.getScanType() == AudiobookVerificationRequest.ScanType.ALL_UNVERIFIED) {
            List<Long> ids = bookMetadataRepository.findUnverifiedAudiobookIds();
            log.info("Verification task {}: found {} unverified audiobooks", taskId, ids.size());
            return ids;
        }
        List<Long> ids = request.getBookIds() != null ? request.getBookIds() : List.of();
        log.info("Verification task {}: {} specific books requested", taskId, ids.size());
        return ids;
    }

    private String resolveOllamaBase(AudiobookVerificationSettings settings) {
        String url = settings.getOllamaUrl();
        return (url != null && !url.isBlank()) ? url.replaceAll("/$", "") :
                defaultOllamaUrl.replaceAll("/$", "");
    }

    private String resolveOllamaModel(AudiobookVerificationSettings settings) {
        String model = settings.getOllamaModel();
        return (model != null && !model.isBlank()) ? model : "llama3.1:8b";
    }

    private String nullableText(JsonNode node, String field) {
        if (!node.has(field) || node.get(field).isNull()) return null;
        String val = node.get(field).asText("").trim();
        return (val.isBlank() || val.equalsIgnoreCase("null")) ? null : val;
    }

    private record ExtractedMetadata(String title, String authors) {
    }
}
