package org.booklore.service.metadata;

import lombok.extern.slf4j.Slf4j;
import org.booklore.config.security.service.AuthenticationService;
import org.booklore.model.MetadataUpdateContext;
import org.booklore.model.MetadataUpdateWrapper;
import org.booklore.model.dto.Book;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.BookMetadata;
import org.booklore.model.dto.MetadataBatchProgressNotification;
import org.booklore.model.dto.request.EpubTextIdentifyRequest;
import org.booklore.model.dto.request.FetchMetadataRequest;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.model.enums.MetadataFetchTaskStatus;
import org.booklore.model.enums.MetadataReplaceMode;
import org.booklore.model.websocket.Topic;
import org.booklore.repository.BookRepository;
import org.booklore.service.NotificationService;
import org.booklore.service.metadata.extractor.EpubOpeningTextExtractor;
import org.booklore.service.metadata.parser.AmazonBookParser;
import org.booklore.service.metadata.parser.BookParser;
import org.booklore.service.metadata.parser.GoodReadsParser;
import org.booklore.service.metadata.parser.OpenLibraryParser;
import org.booklore.service.opds.MagicShelfBookService;
import org.booklore.task.TaskCancellationManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.File;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Identifies EPUB title and author by reading the book's opening pages, asking
 * an Ollama LLM to extract them, then fetching full metadata
 * from Amazon, then GoodReads, then Open Library, and saving the result via
 * BookMetadataUpdater.
 *
 * <p>Processing order per book:
 * <ol>
 *   <li>Extract opening text (first {@link EpubOpeningTextExtractor#DEFAULT_MAX_SPINE_ITEMS} spine items)</li>
 *   <li>POST text to Ollama → receive {@code {"title":"...","author":"..."}}</li>
 *   <li>Query Amazon with identified title + author</li>
 *   <li>Fall through to GoodReads, then Open Library, until one returns data</li>
 *   <li>Apply best result via {@link BookMetadataUpdater#setBookMetadata}</li>
 * </ol>
 */
@Slf4j
@Service
public class EpubTextIdentifyService {

    private static final String DEFAULT_BASE_URL = "http://ollama:11434";
    private static final String DEFAULT_MODEL    = "llama3.1:8b";

    private final BookRepository bookRepository;
    private final EpubOpeningTextExtractor epubOpeningTextExtractor;
    /** Ordered lookup chain applied after Ollama identifies title/author. */
    private final Map<String, BookParser> providerChain;
    private final BookMetadataUpdater bookMetadataUpdater;
    private final NotificationService notificationService;
    private final PlatformTransactionManager transactionManager;
    private final AuthenticationService authenticationService;
    private final TaskCancellationManager cancellationManager;
    private final MagicShelfBookService magicShelfBookService;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    @Value("${ollama.base-url:" + DEFAULT_BASE_URL + "}")
    private String ollamaBaseUrl;

    @Value("${ollama.model:" + DEFAULT_MODEL + "}")
    private String ollamaModel;

    public EpubTextIdentifyService(
            BookRepository bookRepository,
            EpubOpeningTextExtractor epubOpeningTextExtractor,
            AmazonBookParser amazonBookParser,
            GoodReadsParser goodReadsParser,
            OpenLibraryParser openLibraryParser,
            BookMetadataUpdater bookMetadataUpdater,
            NotificationService notificationService,
            PlatformTransactionManager transactionManager,
            AuthenticationService authenticationService,
            TaskCancellationManager cancellationManager,
            MagicShelfBookService magicShelfBookService,
            ObjectMapper objectMapper) {
        this.bookRepository             = bookRepository;
        this.epubOpeningTextExtractor   = epubOpeningTextExtractor;
        this.providerChain              = new LinkedHashMap<>();
        this.providerChain.put("Amazon",       amazonBookParser);
        this.providerChain.put("GoodReads",    goodReadsParser);
        this.providerChain.put("Open Library", openLibraryParser);
        this.bookMetadataUpdater        = bookMetadataUpdater;
        this.notificationService        = notificationService;
        this.transactionManager         = transactionManager;
        this.authenticationService      = authenticationService;
        this.cancellationManager        = cancellationManager;
        this.magicShelfBookService      = magicShelfBookService;
        this.objectMapper               = objectMapper;
        this.restClient                 = RestClient.create();
    }

    // ── Public entry point ────────────────────────────────────────────────────

    public void identify(EpubTextIdentifyRequest request, String taskId) {
        BookLoreUser user   = authenticationService.getAuthenticatedUser();
        Long         userId = user != null ? user.getId() : null;

        try {
            Set<Long> bookIds = resolveBookIds(request, userId);
            if (bookIds.isEmpty()) {
                sendProgress(taskId, 0, 0, "No books found for the given scope.",
                        MetadataFetchTaskStatus.COMPLETED);
                return;
            }

            List<BookEntity> allBooks = bookRepository.findAllWithMetadataByIds(bookIds);

            // Filter to EPUB files only
            List<BookEntity> epubBooks = allBooks.stream()
                    .filter(this::isEpub)
                    .collect(Collectors.toList());

            // Unless overwriteExisting, skip books that already have title + authors
            List<BookEntity> toProcess = request.isOverwriteExisting()
                    ? epubBooks
                    : epubBooks.stream().filter(this::hasMissingMetadata).collect(Collectors.toList());

            int total = toProcess.size();
            log.info("EpubTextIdentify [{}]: {} EPUBs to process ({} skipped — already have metadata)",
                    taskId, total, epubBooks.size() - total);

            if (total == 0) {
                sendProgress(taskId, 0, 0,
                        "All EPUBs already have metadata. Use 'overwrite existing' to re-identify.",
                        MetadataFetchTaskStatus.COMPLETED);
                return;
            }

            TransactionTemplate tx         = new TransactionTemplate(transactionManager);
            AtomicInteger       completed  = new AtomicInteger(0);
            AtomicInteger       updated    = new AtomicInteger(0);
            AtomicBoolean       cancelled  = new AtomicBoolean(false);
            MetadataReplaceMode replaceMode = request.isOverwriteExisting()
                    ? MetadataReplaceMode.REPLACE_ALL
                    : MetadataReplaceMode.REPLACE_MISSING;

            for (BookEntity book : toProcess) {
                if (cancellationManager.isTaskCancelled(taskId) || cancelled.get()) {
                    cancelled.set(true);
                    break;
                }

                String displayTitle = bookTitle(book);

                try {
                    // ── Step 1: EPUB text extraction ─────────────────────────
                    File epubFile = resolveFile(book);
                    if (epubFile == null || !epubFile.exists()) {
                        log.debug("EpubTextIdentify: file not found for book {}", book.getId());
                        sendProgress(taskId, completed.incrementAndGet(), total,
                                "File not found: " + displayTitle, MetadataFetchTaskStatus.IN_PROGRESS);
                        continue;
                    }

                    Optional<String> openingText = epubOpeningTextExtractor.extract(epubFile);
                    if (openingText.isEmpty()) {
                        sendProgress(taskId, completed.incrementAndGet(), total,
                                "No text extracted: " + displayTitle, MetadataFetchTaskStatus.IN_PROGRESS);
                        continue;
                    }

                    // ── Step 2: Ollama title/author identification ────────────
                    IdentifiedBook identified = identifyViaOllama(openingText.get());
                    if (identified == null
                            || (identified.title() == null && identified.author() == null)) {
                        log.debug("EpubTextIdentify: Ollama could not identify '{}'", displayTitle);
                        sendProgress(taskId, completed.incrementAndGet(), total,
                                "Could not identify: " + displayTitle, MetadataFetchTaskStatus.IN_PROGRESS);
                        continue;
                    }

                    log.info("EpubTextIdentify: '{}' → title='{}' author='{}'",
                            displayTitle, identified.title(), identified.author());

                    // ── Step 3: Metadata lookup (Amazon → GoodReads → Open Library) ──
                    FetchMetadataRequest fetchRequest = FetchMetadataRequest.builder()
                            .title(identified.title())
                            .author(identified.author())
                            .build();
                    Book bookDto = buildBookDto(book, identified);

                    BookMetadata metadata = null;
                    String       source   = null;

                    for (Map.Entry<String, BookParser> provider : providerChain.entrySet()) {
                        try {
                            BookMetadata candidate =
                                    provider.getValue().fetchTopMetadata(bookDto, fetchRequest);
                            if (candidate != null) {
                                metadata = candidate;
                                source   = provider.getKey();
                                break;
                            }
                        } catch (Exception e) {
                            log.debug("EpubTextIdentify: {} failed for '{}': {}",
                                    provider.getKey(), displayTitle, e.getMessage());
                        }
                    }

                    if (metadata == null) {
                        sendProgress(taskId, completed.incrementAndGet(), total,
                                "No metadata found: " + displayTitle, MetadataFetchTaskStatus.IN_PROGRESS);
                        continue;
                    }

                    // ── Step 5: Persist via BookMetadataUpdater ───────────────
                    final BookMetadata   finalMetadata   = metadata;
                    final String         finalSource     = source;
                    final MetadataReplaceMode finalMode  = replaceMode;

                    Boolean saved = tx.execute(status -> {
                        List<BookEntity> managed = bookRepository.findAllWithMetadataByIds(
                                Set.of(book.getId()));
                        if (managed.isEmpty()) return false;

                        MetadataUpdateContext context = MetadataUpdateContext.builder()
                                .bookEntity(managed.get(0))
                                .metadataUpdateWrapper(MetadataUpdateWrapper.builder()
                                        .metadata(finalMetadata)
                                        .build())
                                .updateThumbnail(true)
                                .mergeCategories(false)
                                .mergeMoods(false)
                                .mergeTags(false)
                                .replaceMode(finalMode)
                                .autoFetch(true)
                                .build();

                        bookMetadataUpdater.setBookMetadata(context);
                        return true;
                    });

                    if (Boolean.TRUE.equals(saved)) {
                        updated.incrementAndGet();
                        log.info("EpubTextIdentify [{}]: saved via {} for '{}'",
                                taskId, finalSource, displayTitle);
                        sendProgress(taskId, completed.incrementAndGet(), total,
                                "Updated (" + finalSource + "): " + displayTitle,
                                MetadataFetchTaskStatus.IN_PROGRESS);
                    } else {
                        sendProgress(taskId, completed.incrementAndGet(), total,
                                "Could not save: " + displayTitle, MetadataFetchTaskStatus.IN_PROGRESS);
                    }

                } catch (Exception e) {
                    log.warn("EpubTextIdentify: error processing '{}': {}", displayTitle, e.getMessage());
                    sendProgress(taskId, completed.incrementAndGet(), total,
                            "Error: " + displayTitle + " — " + e.getMessage(),
                            MetadataFetchTaskStatus.IN_PROGRESS);
                }
            }

            if (cancelled.get()) {
                sendProgress(taskId, completed.get(), total,
                        "Identify cancelled. Updated " + updated.get() + " book(s).",
                        MetadataFetchTaskStatus.CANCELLED);
            } else {
                sendProgress(taskId, total, total,
                        "Identify complete. Updated " + updated.get() + " of " + total + " book(s).",
                        MetadataFetchTaskStatus.COMPLETED);
            }

        } catch (Exception e) {
            log.error("EpubTextIdentify [{}]: fatal error: {}", taskId, e.getMessage(), e);
            sendProgress(taskId, 0, 0,
                    "Fatal error: " + e.getMessage(), MetadataFetchTaskStatus.ERROR);
        }
    }

    // ── Ollama helpers ────────────────────────────────────────────────────────

    /**
     * Sends the opening text to Ollama and parses the returned JSON for
     * {@code title} and {@code author} fields.
     */
    private IdentifiedBook identifyViaOllama(String text) {
        String prompt =
                "You are a book identification assistant. The text below is extracted from the " +
                "opening pages of an ebook. Identify the book's title and primary author.\n" +
                "Return ONLY a JSON object with exactly two fields: \"title\" and \"author\". " +
                "Use null for a field if you cannot determine it with confidence. " +
                "No prose, no markdown, no code fences.\n\n" +
                "Text:\n" + text;

        try {
            String raw     = callOllama(prompt);
            String content = extractContent(raw);
            if (content == null) return null;

            // Strip accidental markdown fences
            content = content.replaceAll("(?s)```json\\s*", "").replaceAll("(?s)```\\s*", "").trim();

            JsonNode node = objectMapper.readTree(content);
            String   title  = nullableText(node, "title");
            String   author = nullableText(node, "author");
            return new IdentifiedBook(title, author);
        } catch (Exception e) {
            log.warn("EpubTextIdentify: Ollama parse error: {}", e.getMessage());
            return null;
        }
    }

    private String callOllama(String prompt) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model",  ollamaModel);
        body.put("stream", false);
        body.put("format", "json");

        ArrayNode messages = objectMapper.createArrayNode();
        ObjectNode msg = objectMapper.createObjectNode();
        msg.put("role",    "user");
        msg.put("content", prompt);
        messages.add(msg);
        body.set("messages", messages);

        String endpoint = ollamaBaseUrl.replaceAll("/$", "") + "/api/chat";
        return restClient.post()
                .uri(endpoint)
                .header("Content-Type", "application/json")
                .body(body.toString())
                .retrieve()
                .body(String.class);
    }

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

    private String nullableText(JsonNode node, String field) {
        if (!node.has(field) || node.get(field).isNull()) return null;
        String val = node.get(field).asText("").trim();
        return (val.isBlank() || val.equalsIgnoreCase("null") || val.equalsIgnoreCase("unknown"))
                ? null : val;
    }

    // ── Scope resolution ──────────────────────────────────────────────────────

    private Set<Long> resolveBookIds(EpubTextIdentifyRequest request, Long userId) {
        return switch (request.getRefreshType()) {
            case LIBRARY -> {
                if (request.getLibraryId() == null)
                    throw new IllegalArgumentException("libraryId required for LIBRARY scope");
                yield bookRepository.findBookIdsByLibraryId(request.getLibraryId());
            }
            case MAGIC_SHELF -> {
                if (request.getMagicShelfId() == null || userId == null)
                    throw new IllegalArgumentException(
                            "magicShelfId and authenticated user required for MAGIC_SHELF scope");
                yield new HashSet<>(magicShelfBookService.getBookIdsByMagicShelfId(
                        userId, request.getMagicShelfId()));
            }
            case BOOKS -> {
                if (request.getBookIds() == null || request.getBookIds().isEmpty())
                    throw new IllegalArgumentException("bookIds required for BOOKS scope");
                yield request.getBookIds();
            }
        };
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private boolean isEpub(BookEntity book) {
        BookFileEntity primary = book.getPrimaryBookFile();
        return primary != null && BookFileType.EPUB == primary.getBookType();
    }

    /**
     * Returns true when the book's title or authors list is missing, meaning
     * it would benefit from text-based identification.
     */
    private boolean hasMissingMetadata(BookEntity book) {
        if (book.getMetadata() == null) return true;
        boolean missingTitle   = book.getMetadata().getTitle() == null
                || book.getMetadata().getTitle().isBlank();
        boolean missingAuthors = book.getMetadata().getAuthors() == null
                || book.getMetadata().getAuthors().isEmpty();
        return missingTitle || missingAuthors;
    }

    private File resolveFile(BookEntity book) {
        try {
            return book.getPrimaryBookFile().getFullFilePath().toFile();
        } catch (Exception e) {
            return null;
        }
    }

    private String bookTitle(BookEntity book) {
        if (book.getMetadata() != null && book.getMetadata().getTitle() != null)
            return book.getMetadata().getTitle();
        BookFileEntity f = book.getPrimaryBookFile();
        return f != null ? f.getFileName() : "Book #" + book.getId();
    }

    /** Builds a minimal Book DTO for passing to parsers. */
    private Book buildBookDto(BookEntity book, IdentifiedBook identified) {
        return Book.builder()
                .id(book.getId())
                .metadata(BookMetadata.builder()
                        .title(identified.title())
                        .authors(identified.author() != null
                                ? List.of(identified.author())
                                : List.of())
                        .build())
                .build();
    }

    private void sendProgress(String taskId, int current, int total, String message,
                              MetadataFetchTaskStatus status) {
        notificationService.sendMessage(
                Topic.BOOK_METADATA_BATCH_PROGRESS,
                new MetadataBatchProgressNotification(
                        taskId, current, total, message, status.name(), false));
    }

    // ── Value object ──────────────────────────────────────────────────────────

    private record IdentifiedBook(String title, String author) {}
}
