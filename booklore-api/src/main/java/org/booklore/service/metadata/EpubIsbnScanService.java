package org.booklore.service.metadata;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.config.security.service.AuthenticationService;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.MetadataBatchProgressNotification;
import org.booklore.model.dto.request.IsbnScanRequest;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.model.enums.MetadataFetchTaskStatus;
import org.booklore.model.websocket.Topic;
import org.booklore.repository.BookRepository;
import org.booklore.repository.OpenLibraryRepository;
import org.booklore.repository.OpenLibraryRepository.OlIsbnResult;
import org.booklore.service.NotificationService;
import org.booklore.service.metadata.extractor.EpubIsbnScanner;
import org.booklore.service.metadata.extractor.EpubIsbnScanner.IsbnResult;
import org.booklore.service.opds.MagicShelfBookService;
import org.booklore.task.TaskCancellationManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.File;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Bulk ISBN scanner.  For each book in the requested scope the service tries
 * two strategies in order, stopping as soon as one succeeds:
 *
 * <ol>
 *   <li><b>Open Library local DB</b> — matches on normalised title; uses author
 *       names to disambiguate when multiple editions are returned.  Works for
 *       any book format (EPUB, PDF, CBR, …).</li>
 *   <li><b>EPUB content scan</b> — delegates to {@link EpubIsbnScanner} which
 *       itself tries OPF dc:identifier, the copyright page via guide/landmarks,
 *       the first N spine items, and finally every remaining manifest HTML file.
 *       Only attempted when the primary file is an EPUB.</li>
 * </ol>
 */
@Slf4j
@AllArgsConstructor
@Service
public class EpubIsbnScanService {

    private final BookRepository bookRepository;
    private final EpubIsbnScanner epubIsbnScanner;
    private final OpenLibraryRepository openLibraryRepository;
    private final NotificationService notificationService;
    private final PlatformTransactionManager transactionManager;
    private final AuthenticationService authenticationService;
    private final TaskCancellationManager cancellationManager;
    private final MagicShelfBookService magicShelfBookService;

    public void scan(IsbnScanRequest request, String taskId) {
        BookLoreUser user = authenticationService.getAuthenticatedUser();
        Long userId = user != null ? user.getId() : null;

        try {
            Set<Long> bookIds = resolveBookIds(request, userId);
            if (bookIds.isEmpty()) {
                sendProgress(taskId, 0, 0, "No books found for the given scope.", MetadataFetchTaskStatus.COMPLETED);
                return;
            }

            List<BookEntity> allBooks = bookRepository.findAllWithMetadataByIds(bookIds);

            // Unless overwriteExisting, skip books that already have an ISBN
            List<BookEntity> toScan = request.isOverwriteExisting()
                    ? allBooks
                    : allBooks.stream().filter(this::missingIsbn).collect(Collectors.toList());

            int total = toScan.size();
            log.info("EpubIsbnScan [{}]: {} books to scan ({} skipped — already have ISBN)",
                    taskId, total, allBooks.size() - total);

            if (total == 0) {
                sendProgress(taskId, 0, 0,
                        "All books already have ISBNs. Use 'overwrite existing' to re-scan.",
                        MetadataFetchTaskStatus.COMPLETED);
                return;
            }

            TransactionTemplate tx = new TransactionTemplate(transactionManager);
            AtomicInteger completed = new AtomicInteger(0);
            AtomicInteger updated = new AtomicInteger(0);
            AtomicBoolean cancelled = new AtomicBoolean(false);

            for (BookEntity book : toScan) {
                if (cancellationManager.isTaskCancelled(taskId) || cancelled.get()) {
                    cancelled.set(true);
                    break;
                }

                String title = bookTitle(book);

                try {
                    IsbnResult isbn = null;
                    String source = null;

                    // ── Strategy 1: Open Library local DB ────────────────────
                    String rawTitle = book.getMetadata() != null ? book.getMetadata().getTitle() : null;
                    if (rawTitle != null && !rawTitle.isBlank()) {
                        isbn = lookupFromOpenLibrary(book, rawTitle, tx);
                        if (isbn != null) source = "OpenLibrary";
                    }

                    // ── Strategy 2: EPUB content scan ─────────────────────────
                    if (isbn == null && isEpub(book)) {
                        File epubFile = resolveFile(book);
                        if (epubFile != null && epubFile.exists()) {
                            Optional<IsbnResult> result = epubIsbnScanner.scan(epubFile);
                            if (result.isPresent()) {
                                isbn = result.get();
                                source = "EPUB";
                            }
                        } else {
                            log.debug("EpubIsbnScan: file not found for book {}", book.getId());
                        }
                    }

                    if (isbn == null) {
                        log.debug("EpubIsbnScan: no ISBN found for '{}'", title);
                        sendProgress(taskId, completed.incrementAndGet(), total,
                                "No ISBN found: " + title, MetadataFetchTaskStatus.IN_PROGRESS);
                        continue;
                    }

                    // ── Persist ───────────────────────────────────────────────
                    final IsbnResult finalIsbn = isbn;
                    final String finalSource = source;
                    boolean saved = Boolean.TRUE.equals(tx.execute(status -> {
                        BookEntity managed = bookRepository.findById(book.getId()).orElse(null);
                        if (managed == null) return false;
                        BookMetadataEntity meta = managed.getMetadata();
                        if (meta == null) return false;
                        if (finalIsbn.isbn13() != null) meta.setIsbn13(finalIsbn.isbn13());
                        if (finalIsbn.isbn10() != null) meta.setIsbn10(finalIsbn.isbn10());
                        bookRepository.save(managed);
                        return true;
                    }));

                    if (saved) {
                        updated.incrementAndGet();
                        log.info("EpubIsbnScan [{}]: saved isbn13={} isbn10={} via {} for '{}'",
                                taskId, finalIsbn.isbn13(), finalIsbn.isbn10(), finalSource, title);
                        sendProgress(taskId, completed.incrementAndGet(), total,
                                "Updated ISBN (" + finalSource + "): " + title, MetadataFetchTaskStatus.IN_PROGRESS);
                    } else {
                        sendProgress(taskId, completed.incrementAndGet(), total,
                                "Could not save: " + title, MetadataFetchTaskStatus.IN_PROGRESS);
                    }

                } catch (Exception e) {
                    log.warn("EpubIsbnScan: error processing '{}': {}", title, e.getMessage());
                    sendProgress(taskId, completed.incrementAndGet(), total,
                            "Error: " + title + " — " + e.getMessage(), MetadataFetchTaskStatus.IN_PROGRESS);
                }
            }

            if (cancelled.get()) {
                sendProgress(taskId, completed.get(), total,
                        "Scan cancelled. Updated " + updated.get() + " book(s).",
                        MetadataFetchTaskStatus.CANCELLED);
            } else {
                sendProgress(taskId, total, total,
                        "ISBN scan complete. Updated " + updated.get() + " of " + total + " book(s).",
                        MetadataFetchTaskStatus.COMPLETED);
            }

        } catch (Exception e) {
            log.error("EpubIsbnScan [{}]: fatal error: {}", taskId, e.getMessage(), e);
            sendProgress(taskId, 0, 0,
                    "Fatal error during ISBN scan: " + e.getMessage(), MetadataFetchTaskStatus.ERROR);
        }
    }

    // ── Open Library lookup ───────────────────────────────────────────────────

    /**
     * Queries the local Open Library DB by title and optionally narrows by author.
     *
     * <p>Algorithm:
     * <ol>
     *   <li>FULLTEXT search on title returning up to 15 candidates that carry an ISBN.</li>
     *   <li>If exactly one candidate, accept it (high confidence).</li>
     *   <li>If multiple candidates, load the book's authors inside a transaction and
     *       pick the candidate whose resolved author names best match.</li>
     *   <li>If no candidate passes author filtering, return {@code null}.</li>
     * </ol>
     */
    private IsbnResult lookupFromOpenLibrary(BookEntity book, String title, TransactionTemplate tx) {
        List<OlIsbnResult> candidates = openLibraryRepository.findIsbnByTitle(title);
        if (candidates.isEmpty()) return null;

        // Filter: must have at least one ISBN
        candidates = candidates.stream()
                .filter(r -> r.isbn13() != null || r.isbn10() != null)
                .collect(Collectors.toList());
        if (candidates.isEmpty()) return null;

        // Single result — accept without author check
        if (candidates.size() == 1) {
            OlIsbnResult r = candidates.get(0);
            return new IsbnResult(r.isbn13(), r.isbn10());
        }

        // Multiple candidates — load authors inside a transaction and disambiguate
        List<String> bookAuthors = tx.execute(status -> {
            BookEntity managed = bookRepository.findById(book.getId()).orElse(null);
            if (managed == null || managed.getMetadata() == null) return Collections.emptyList();
            List<org.booklore.model.entity.AuthorEntity> authors = managed.getMetadata().getAuthors();
            if (authors == null) return Collections.emptyList();
            return authors.stream()
                    .map(a -> normalise(a.getName()))
                    .filter(s -> s != null && !s.isBlank())
                    .collect(Collectors.toList());
        });

        if (bookAuthors == null || bookAuthors.isEmpty()) {
            // No author data — take first English-preferred result
            OlIsbnResult r = candidates.get(0);
            return new IsbnResult(r.isbn13(), r.isbn10());
        }

        for (OlIsbnResult candidate : candidates) {
            List<String> olAuthorKeys = parseAuthorKeys(candidate.authorKeys());
            if (olAuthorKeys.isEmpty()) continue;

            List<String> olAuthorNames = openLibraryRepository.resolveAuthorNames(olAuthorKeys)
                    .stream().map(this::normalise).collect(Collectors.toList());

            if (authorsMatch(bookAuthors, olAuthorNames)) {
                log.debug("EpubIsbnScan: OL author match — title='{}' isbn13={}", title, candidate.isbn13());
                return new IsbnResult(candidate.isbn13(), candidate.isbn10());
            }
        }

        log.debug("EpubIsbnScan: OL candidates found for '{}' but none matched authors", title);
        return null;
    }

    /**
     * Returns {@code true} when at least one book author name overlaps with
     * at least one OL author name (normalised substring match).
     */
    private boolean authorsMatch(List<String> bookAuthors, List<String> olAuthors) {
        for (String book : bookAuthors) {
            for (String ol : olAuthors) {
                if (book.isEmpty() || ol.isEmpty()) continue;
                if (ol.contains(book) || book.contains(ol)) return true;
                // Last-name-only match: check if either token appears in the other
                String[] bookTokens = book.split("\\s+");
                String[] olTokens   = ol.split("\\s+");
                for (String bt : bookTokens) {
                    if (bt.length() < 3) continue;
                    for (String ot : olTokens) {
                        if (bt.equals(ot)) return true;
                    }
                }
            }
        }
        return false;
    }

    /** Lowercase + strip all non-alphanumeric except spaces. */
    private String normalise(String s) {
        if (s == null) return "";
        return s.toLowerCase().replaceAll("[^a-z0-9 ]", "").trim();
    }

    /**
     * Parse {@code ["/authors/OL1234A", ...]} JSON into a list of OL keys using a
     * simple regex — avoids any Jackson version dependency.
     */
    private static final java.util.regex.Pattern AUTHOR_KEY_PATTERN =
            java.util.regex.Pattern.compile("\"(/authors/[^\"]+)\"");

    private List<String> parseAuthorKeys(String json) {
        if (json == null || json.isBlank()) return Collections.emptyList();
        List<String> keys = new ArrayList<>();
        java.util.regex.Matcher m = AUTHOR_KEY_PATTERN.matcher(json);
        while (m.find()) {
            keys.add(m.group(1));
        }
        return keys;
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private Set<Long> resolveBookIds(IsbnScanRequest request, Long userId) {
        return switch (request.getRefreshType()) {
            case LIBRARY -> {
                if (request.getLibraryId() == null)
                    throw new IllegalArgumentException("libraryId required for LIBRARY scope");
                yield bookRepository.findBookIdsByLibraryId(request.getLibraryId());
            }
            case MAGIC_SHELF -> {
                if (request.getMagicShelfId() == null || userId == null)
                    throw new IllegalArgumentException("magicShelfId and authenticated user required for MAGIC_SHELF scope");
                yield new HashSet<>(magicShelfBookService.getBookIdsByMagicShelfId(userId, request.getMagicShelfId()));
            }
            case BOOKS -> {
                if (request.getBookIds() == null || request.getBookIds().isEmpty())
                    throw new IllegalArgumentException("bookIds required for BOOKS scope");
                yield request.getBookIds();
            }
        };
    }

    private boolean isEpub(BookEntity book) {
        BookFileEntity primary = book.getPrimaryBookFile();
        return primary != null && BookFileType.EPUB == primary.getBookType();
    }

    private boolean missingIsbn(BookEntity book) {
        BookMetadataEntity meta = book.getMetadata();
        if (meta == null) return true;
        boolean hasIsbn13 = meta.getIsbn13() != null && !meta.getIsbn13().isBlank();
        boolean hasIsbn10 = meta.getIsbn10() != null && !meta.getIsbn10().isBlank();
        return !hasIsbn13 && !hasIsbn10;
    }

    private File resolveFile(BookEntity book) {
        try {
            return book.getPrimaryBookFile().getFullFilePath().toFile();
        } catch (Exception e) {
            return null;
        }
    }

    private String bookTitle(BookEntity book) {
        if (book.getMetadata() != null && book.getMetadata().getTitle() != null) {
            return book.getMetadata().getTitle();
        }
        BookFileEntity f = book.getPrimaryBookFile();
        return f != null ? f.getFileName() : "Book #" + book.getId();
    }

    private void sendProgress(String taskId, int current, int total, String message,
                              MetadataFetchTaskStatus status) {
        notificationService.sendMessage(
                Topic.BOOK_METADATA_BATCH_PROGRESS,
                new MetadataBatchProgressNotification(taskId, current, total, message, status.name(), false));
    }
}
