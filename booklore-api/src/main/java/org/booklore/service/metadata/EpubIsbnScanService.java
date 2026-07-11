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
import org.booklore.service.NotificationService;
import org.booklore.service.metadata.extractor.EpubIsbnScanner;
import org.booklore.service.opds.MagicShelfBookService;
import org.booklore.task.TaskCancellationManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.File;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Bulk-scans EPUB content pages for ISBN numbers and persists any newly found
 * values back to the book's metadata record.
 *
 * <p>Books are resolved from a library, a magic shelf, or an explicit list of IDs.
 * Only books whose primary file is an EPUB are processed.  By default books that
 * already carry an isbn13 or isbn10 value are skipped; set {@code overwriteExisting}
 * on the request to change this behaviour.
 */
@Slf4j
@AllArgsConstructor
@Service
public class EpubIsbnScanService {

    private final BookRepository bookRepository;
    private final EpubIsbnScanner epubIsbnScanner;
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

            // Load entities with metadata + files in one query
            List<BookEntity> allBooks = bookRepository.findAllWithMetadataByIds(bookIds);

            // Keep only EPUBs
            List<BookEntity> epubBooks = allBooks.stream()
                    .filter(b -> isEpub(b))
                    .collect(Collectors.toList());

            // Unless overwriteExisting, skip books that already have an ISBN
            List<BookEntity> toScan = request.isOverwriteExisting()
                    ? epubBooks
                    : epubBooks.stream().filter(b -> missingIsbn(b)).collect(Collectors.toList());

            int total = toScan.size();
            log.info("EpubIsbnScan [{}]: {} EPUBs to scan ({} skipped — already have ISBN)",
                    taskId, total, epubBooks.size() - total);

            if (total == 0) {
                sendProgress(taskId, 0, 0,
                        "All EPUBs already have ISBNs. Use 'overwrite existing' to re-scan.",
                        MetadataFetchTaskStatus.COMPLETED);
                return;
            }

            TransactionTemplate tx = new TransactionTemplate(transactionManager);
            AtomicInteger completed = new AtomicInteger(0);
            AtomicInteger updated  = new AtomicInteger(0);
            AtomicBoolean cancelled = new AtomicBoolean(false);

            for (BookEntity book : toScan) {
                if (cancellationManager.isTaskCancelled(taskId) || cancelled.get()) {
                    cancelled.set(true);
                    break;
                }

                String title = bookTitle(book);
                try {
                    File epubFile = resolveFile(book);
                    if (epubFile == null || !epubFile.exists()) {
                        log.debug("EpubIsbnScan: file not found for book {}", book.getId());
                        sendProgress(taskId, completed.incrementAndGet(), total,
                                "File not found: " + title, MetadataFetchTaskStatus.IN_PROGRESS);
                        continue;
                    }

                    Optional<EpubIsbnScanner.IsbnResult> result = epubIsbnScanner.scan(epubFile);
                    if (result.isEmpty()) {
                        log.debug("EpubIsbnScan: no ISBN found in '{}'", title);
                        sendProgress(taskId, completed.incrementAndGet(), total,
                                "No ISBN found: " + title, MetadataFetchTaskStatus.IN_PROGRESS);
                        continue;
                    }

                    EpubIsbnScanner.IsbnResult isbn = result.get();
                    boolean saved = Boolean.TRUE.equals(tx.execute(status -> {
                        BookEntity managed = bookRepository.findById(book.getId()).orElse(null);
                        if (managed == null) return false;
                        BookMetadataEntity meta = managed.getMetadata();
                        if (meta == null) return false;
                        if (isbn.isbn13() != null) meta.setIsbn13(isbn.isbn13());
                        if (isbn.isbn10() != null) meta.setIsbn10(isbn.isbn10());
                        bookRepository.save(managed);
                        return true;
                    }));

                    if (saved) {
                        updated.incrementAndGet();
                        log.info("EpubIsbnScan: saved isbn13={} isbn10={} for '{}'",
                                isbn.isbn13(), isbn.isbn10(), title);
                        sendProgress(taskId, completed.incrementAndGet(), total,
                                "Updated ISBN: " + title, MetadataFetchTaskStatus.IN_PROGRESS);
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
                        "ISBN scan complete. Updated " + updated.get() + " of " + total + " EPUB(s).",
                        MetadataFetchTaskStatus.COMPLETED);
            }

        } catch (Exception e) {
            log.error("EpubIsbnScan [{}]: fatal error: {}", taskId, e.getMessage(), e);
            sendProgress(taskId, 0, 0,
                    "Fatal error during ISBN scan: " + e.getMessage(), MetadataFetchTaskStatus.ERROR);
        }
    }

    // ── Private helpers ────────────────────────────────────────────────────────

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
