package org.booklore.service.metadata;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.config.security.service.AuthenticationService;
import org.booklore.model.MetadataUpdateContext;
import org.booklore.model.MetadataUpdateWrapper;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.BookMetadata;
import org.booklore.model.dto.MetadataBatchProgressNotification;
import org.booklore.model.dto.request.FilenameAuthorExtractRequest;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.enums.MetadataFetchTaskStatus;
import org.booklore.model.enums.MetadataReplaceMode;
import org.booklore.model.websocket.Topic;
import org.booklore.repository.BookRepository;
import org.booklore.service.NotificationService;
import org.booklore.service.opds.MagicShelfBookService;
import org.booklore.task.TaskCancellationManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Parses book filenames to extract missing author names without any external API calls.
 *
 * <p>Supported naming conventions (in priority order):
 * <ol>
 *   <li><b>Last, First — Title</b>: {@code "Card, Orson Scott - Ender's Game (1985).epub"}</li>
 *   <li><b>Title — Author</b>: {@code "Ender's Game - Orson Scott Card (1985).epub"}
 *       resolved via fuzzy match against the book's known title.</li>
 *   <li><b>Author — Title</b>: {@code "Orson Scott Card - Ender's Game.epub"}
 *       also resolved via fuzzy match.</li>
 *   <li><b>Heuristic</b>: when the right-hand side of " - " looks like a person name
 *       but title matching is not possible.</li>
 * </ol>
 *
 * <p>Only books with a missing (null or empty) authors list are processed by default.
 */
@Slf4j
@AllArgsConstructor
@Service
public class FilenameAuthorExtractService {

    /** Year in parentheses or brackets, anywhere in the string. */
    private static final Pattern YEAR_PATTERN =
            Pattern.compile("\\s*[\\(\\[](\\d{4})[\\)\\]]\\s*");

    /** Comma that looks like a volume/series number rather than Last, First. */
    private static final Pattern SERIES_COMMA =
            Pattern.compile(",\\s*(?:vol|book|part|no|#)?\\s*\\d+", Pattern.CASE_INSENSITIVE);

    private final BookRepository              bookRepository;
    private final BookMetadataUpdater         bookMetadataUpdater;
    private final NotificationService         notificationService;
    private final PlatformTransactionManager  transactionManager;
    private final AuthenticationService       authenticationService;
    private final TaskCancellationManager     cancellationManager;
    private final MagicShelfBookService       magicShelfBookService;

    // ── Public entry point ────────────────────────────────────────────────────

    public void extract(FilenameAuthorExtractRequest request, String taskId) {
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

            List<BookEntity> toProcess = request.isOverwriteExisting()
                    ? allBooks
                    : allBooks.stream().filter(this::hasMissingAuthor).collect(Collectors.toList());

            int total = toProcess.size();
            log.info("FilenameAuthorExtract [{}]: {} books to process ({} skipped — already have authors)",
                    taskId, total, allBooks.size() - total);

            if (total == 0) {
                sendProgress(taskId, 0, 0,
                        "All books already have authors. Use 'overwrite existing' to re-parse.",
                        MetadataFetchTaskStatus.COMPLETED);
                return;
            }

            TransactionTemplate tx        = new TransactionTemplate(transactionManager);
            AtomicInteger       completed = new AtomicInteger(0);
            AtomicInteger       updated   = new AtomicInteger(0);
            AtomicBoolean       cancelled = new AtomicBoolean(false);

            for (BookEntity book : toProcess) {
                if (cancellationManager.isTaskCancelled(taskId) || cancelled.get()) {
                    cancelled.set(true);
                    break;
                }

                String displayTitle = bookTitle(book);

                try {
                    String filename = primaryFilename(book);
                    if (filename == null) {
                        log.debug("FilenameAuthorExtract: no file for book {}", book.getId());
                        sendProgress(taskId, completed.incrementAndGet(), total,
                                "No file: " + displayTitle, MetadataFetchTaskStatus.IN_PROGRESS);
                        continue;
                    }

                    String knownTitle = book.getMetadata() != null
                            ? book.getMetadata().getTitle() : null;
                    ParseResult result = parseAuthorFromFilename(filename, knownTitle);

                    if (result == null) {
                        log.debug("FilenameAuthorExtract: no pattern matched '{}' for '{}'",
                                filename, displayTitle);
                        sendProgress(taskId, completed.incrementAndGet(), total,
                                "No pattern matched: " + displayTitle,
                                MetadataFetchTaskStatus.IN_PROGRESS);
                        continue;
                    }

                    final String parsedAuthor = result.author();
                    final String strategy     = result.strategy();
                    final MetadataReplaceMode mode = request.isOverwriteExisting()
                            ? MetadataReplaceMode.REPLACE_ALL
                            : MetadataReplaceMode.REPLACE_MISSING;

                    Boolean saved = tx.execute(status -> {
                        List<BookEntity> managed = bookRepository.findAllWithMetadataByIds(
                                Set.of(book.getId()));
                        if (managed.isEmpty()) return false;

                        BookMetadata authorMetadata = BookMetadata.builder()
                                .authors(List.of(parsedAuthor))
                                .build();

                        MetadataUpdateContext context = MetadataUpdateContext.builder()
                                .bookEntity(managed.get(0))
                                .metadataUpdateWrapper(MetadataUpdateWrapper.builder()
                                        .metadata(authorMetadata)
                                        .build())
                                .updateThumbnail(false)
                                .mergeCategories(false)
                                .mergeMoods(false)
                                .mergeTags(false)
                                .replaceMode(mode)
                                .autoFetch(false)
                                .build();

                        bookMetadataUpdater.setBookMetadata(context);
                        return true;
                    });

                    if (Boolean.TRUE.equals(saved)) {
                        updated.incrementAndGet();
                        log.info("FilenameAuthorExtract [{}]: set author='{}' via [{}] for '{}'",
                                taskId, parsedAuthor, strategy, displayTitle);
                        sendProgress(taskId, completed.incrementAndGet(), total,
                                "Set author '" + parsedAuthor + "': " + displayTitle,
                                MetadataFetchTaskStatus.IN_PROGRESS);
                    } else {
                        sendProgress(taskId, completed.incrementAndGet(), total,
                                "Could not save: " + displayTitle, MetadataFetchTaskStatus.IN_PROGRESS);
                    }

                } catch (Exception e) {
                    log.warn("FilenameAuthorExtract: error processing '{}': {}",
                            displayTitle, e.getMessage());
                    sendProgress(taskId, completed.incrementAndGet(), total,
                            "Error: " + displayTitle + " — " + e.getMessage(),
                            MetadataFetchTaskStatus.IN_PROGRESS);
                }
            }

            if (cancelled.get()) {
                sendProgress(taskId, completed.get(), total,
                        "Extraction cancelled. Updated " + updated.get() + " book(s).",
                        MetadataFetchTaskStatus.CANCELLED);
            } else {
                sendProgress(taskId, total, total,
                        "Extraction complete. Updated " + updated.get() + " of " + total + " book(s).",
                        MetadataFetchTaskStatus.COMPLETED);
            }

        } catch (Exception e) {
            log.error("FilenameAuthorExtract [{}]: fatal error: {}", taskId, e.getMessage(), e);
            sendProgress(taskId, 0, 0,
                    "Fatal error: " + e.getMessage(), MetadataFetchTaskStatus.ERROR);
        }
    }

    // ── Filename parsing ──────────────────────────────────────────────────────

    /**
     * Attempts to extract an author name from the given filename.
     *
     * @param filename   Raw file name including extension.
     * @param knownTitle The book's current metadata title (may be null).
     * @return A {@link ParseResult} with the extracted author name,
     *         or {@code null} if no recognisable pattern was found.
     */
    ParseResult parseAuthorFromFilename(String filename, String knownTitle) {
        // Strip file extension
        String stem = filename.replaceAll("\\.[^.]+$", "").strip();

        // Split on first " - " only; titles may themselves contain " - "
        String[] parts = stem.split(" - ", 2);
        if (parts.length < 2) {
            return null; // No separator — cannot determine author
        }

        String p0 = stripYear(parts[0]).strip();
        String p1 = stripYear(parts[1]).strip();

        if (p0.isBlank() || p1.isBlank()) {
            return null;
        }

        // ── Strategy 1: "Last, First — Title" ────────────────────────────────
        // A comma in p0 that is NOT a series-number comma indicates Last, First.
        if (p0.contains(",") && !SERIES_COMMA.matcher(p0).find()) {
            String author = normalizeLastFirst(p0);
            if (isPlausibleAuthorName(author)) {
                return new ParseResult(author, "Last,First");
            }
        }

        // ── Strategy 2 & 3: Use known title to resolve which side is title ───
        if (knownTitle != null && !knownTitle.isBlank()) {
            String normTitle = normalizeText(knownTitle);
            String normP0    = normalizeText(p0);
            String normP1    = normalizeText(p1);

            if (isTitleMatch(normP0, normTitle) && isPlausibleAuthorName(p1)) {
                // Title on left — author on right
                return new ParseResult(p1, "TitleLeft");
            }
            if (isTitleMatch(normP1, normTitle) && isPlausibleAuthorName(p0)) {
                // Author on left — title on right
                return new ParseResult(p0, "AuthorLeft");
            }
        }

        // ── Strategy 4: Heuristic ─────────────────────────────────────────────
        // Common convention is "Title - Author". Prefer the side that looks like
        // a person name (short word count, all words start with a letter).
        boolean p1Name = isPlausibleAuthorName(p1);
        boolean p0Name = isPlausibleAuthorName(p0);

        if (p1Name && !p0Name) {
            return new ParseResult(p1, "Heuristic:AuthorRight");
        }
        if (p0Name && !p1Name) {
            return new ParseResult(p0, "Heuristic:AuthorLeft");
        }

        // Both sides are ambiguous — skip
        return null;
    }

    // ── Text helpers ──────────────────────────────────────────────────────────

    /** Removes all year tokens like {@code (2023)} or {@code [2023]}. */
    private String stripYear(String s) {
        return YEAR_PATTERN.matcher(s).replaceAll(" ").strip();
    }

    /**
     * Converts "Last, First Middle" to "First Middle Last".
     * Only the first comma is treated as the Last/First separator.
     */
    private String normalizeLastFirst(String s) {
        int idx = s.indexOf(',');
        if (idx < 0) return s.strip();
        String last  = s.substring(0, idx).strip();
        String first = s.substring(idx + 1).strip();
        return (first.isBlank() ? last : first + " " + last).strip();
    }

    /**
     * Normalises a string for fuzzy comparison:
     * lowercase, collapse runs of non-alphanumeric to a space.
     */
    private String normalizeText(String s) {
        if (s == null) return "";
        return s.toLowerCase()
                .replaceAll("[^a-z0-9]+", " ")
                .strip();
    }

    /**
     * Returns {@code true} when the normalised filename part is a plausible
     * representation of {@code normTitle} — exact match, containment, or
     * a prefix match for the first 20 significant characters.
     */
    private boolean isTitleMatch(String normPart, String normTitle) {
        if (normPart.isEmpty() || normTitle.isEmpty()) return false;
        if (normPart.equals(normTitle)) return true;
        if (normPart.contains(normTitle) || normTitle.contains(normPart)) return true;
        // Allow a short prefix match to handle subtitle truncation in filenames
        int prefixLen = Math.min(normTitle.length(), 20);
        return normTitle.length() > 10
                && normPart.startsWith(normTitle.substring(0, prefixLen));
    }

    /**
     * Heuristic check: a plausible person name has 1–6 space-separated tokens,
     * each starting with a letter, and is not excessively long.
     */
    private boolean isPlausibleAuthorName(String s) {
        if (s == null || s.isBlank() || s.length() > 60) return false;
        String[] words = s.split("\\s+");
        if (words.length < 1 || words.length > 6) return false;
        for (String word : words) {
            if (word.isEmpty() || !Character.isLetter(word.charAt(0))) return false;
        }
        return true;
    }

    // ── Entity helpers ────────────────────────────────────────────────────────

    private boolean hasMissingAuthor(BookEntity book) {
        if (book.getMetadata() == null) return true;
        return book.getMetadata().getAuthors() == null
                || book.getMetadata().getAuthors().isEmpty();
    }

    private String primaryFilename(BookEntity book) {
        BookFileEntity f = book.getPrimaryBookFile();
        return f != null ? f.getFileName() : null;
    }

    private String bookTitle(BookEntity book) {
        if (book.getMetadata() != null && book.getMetadata().getTitle() != null)
            return book.getMetadata().getTitle();
        BookFileEntity f = book.getPrimaryBookFile();
        return f != null ? f.getFileName() : "Book #" + book.getId();
    }

    private Set<Long> resolveBookIds(FilenameAuthorExtractRequest request, Long userId) {
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

    private void sendProgress(String taskId, int current, int total, String message,
                              MetadataFetchTaskStatus status) {
        notificationService.sendMessage(
                Topic.BOOK_METADATA_BATCH_PROGRESS,
                new MetadataBatchProgressNotification(
                        taskId, current, total, message, status.name(), false));
    }

    // ── Value object ──────────────────────────────────────────────────────────

    record ParseResult(String author, String strategy) {}
}
