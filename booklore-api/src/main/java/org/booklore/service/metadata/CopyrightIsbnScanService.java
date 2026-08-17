package org.booklore.service.metadata;

import lombok.extern.slf4j.Slf4j;
import org.booklore.config.security.service.AuthenticationService;
import org.booklore.model.MetadataUpdateContext;
import org.booklore.model.MetadataUpdateWrapper;
import org.booklore.model.dto.Book;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.BookMetadata;
import org.booklore.model.dto.MetadataBatchProgressNotification;
import org.booklore.model.dto.request.CopyrightIsbnScanRequest;
import org.booklore.model.dto.request.FetchMetadataRequest;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.model.enums.MetadataFetchTaskStatus;
import org.booklore.model.enums.MetadataReplaceMode;
import org.booklore.model.websocket.Topic;
import org.booklore.repository.BookRepository;
import org.booklore.service.NotificationService;
import org.booklore.service.metadata.extractor.EpubIsbnScanner;
import org.booklore.service.metadata.extractor.EpubIsbnScanner.IsbnResult;
import org.booklore.service.metadata.parser.AmazonBookParser;
import org.booklore.service.metadata.parser.BookParser;
import org.booklore.service.metadata.parser.GoodReadsParser;
import org.booklore.service.metadata.parser.OpenLibraryParser;
import org.booklore.service.opds.MagicShelfBookService;
import org.booklore.task.TaskCancellationManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.File;
import java.util.*;

/**
 * Reads an ISBN from each EPUB's declared copyright page, then fetches and
 * applies full metadata for that ISBN.
 *
 * <p>Deliberately narrower than {@link EpubIsbnScanService}: that one falls back
 * to sweeping body text, which can return a valid ISBN belonging to a different
 * book advertised in the front matter. For books with no title to check the
 * result against, a wrong-but-plausible ISBN is worse than no ISBN, so this task
 * only trusts what the book declares as its copyright page.
 *
 * <p>Lookup order matches the rest of the app: Amazon, then GoodReads, then
 * Open Library. Google Books is excluded — it returns HTTP 429 on every
 * unauthenticated call.
 */
@Slf4j
@Service
public class CopyrightIsbnScanService {

    private final BookRepository bookRepository;
    private final EpubIsbnScanner epubIsbnScanner;
    private final BookMetadataUpdater bookMetadataUpdater;
    private final NotificationService notificationService;
    private final PlatformTransactionManager transactionManager;
    private final AuthenticationService authenticationService;
    private final TaskCancellationManager cancellationManager;
    private final MagicShelfBookService magicShelfBookService;
    private final Map<String, BookParser> providerChain;

    public CopyrightIsbnScanService(BookRepository bookRepository,
                                    EpubIsbnScanner epubIsbnScanner,
                                    BookMetadataUpdater bookMetadataUpdater,
                                    NotificationService notificationService,
                                    PlatformTransactionManager transactionManager,
                                    AuthenticationService authenticationService,
                                    TaskCancellationManager cancellationManager,
                                    MagicShelfBookService magicShelfBookService,
                                    AmazonBookParser amazonBookParser,
                                    GoodReadsParser goodReadsParser,
                                    OpenLibraryParser openLibraryParser) {
        this.bookRepository = bookRepository;
        this.epubIsbnScanner = epubIsbnScanner;
        this.bookMetadataUpdater = bookMetadataUpdater;
        this.notificationService = notificationService;
        this.transactionManager = transactionManager;
        this.authenticationService = authenticationService;
        this.cancellationManager = cancellationManager;
        this.magicShelfBookService = magicShelfBookService;
        this.providerChain = new LinkedHashMap<>();
        this.providerChain.put("Amazon", amazonBookParser);
        this.providerChain.put("GoodReads", goodReadsParser);
        this.providerChain.put("Open Library", openLibraryParser);
    }

    public void scan(CopyrightIsbnScanRequest request, String taskId) {
        BookLoreUser user = authenticationService.getAuthenticatedUser();
        Long userId = user != null ? user.getId() : null;

        try {
            Set<Long> bookIds = resolveBookIds(request, userId);
            if (bookIds.isEmpty()) {
                sendProgress(taskId, 0, 0, "No books found for the given scope.",
                        MetadataFetchTaskStatus.COMPLETED);
                return;
            }

            List<BookEntity> allBooks = bookRepository.findAllWithMetadataByIds(bookIds);

            List<BookEntity> toScan = allBooks.stream()
                    .filter(this::isEpub)
                    .filter(b -> request.isOverwriteExisting() || missingIsbn(b))
                    .toList();

            int total = toScan.size();
            log.info("CopyrightIsbn [{}]: {} EPUBs to scan ({} skipped — not EPUB or already have an ISBN)",
                    taskId, total, allBooks.size() - total);

            if (total == 0) {
                sendProgress(taskId, 0, 0,
                        "Nothing to scan. Only EPUBs without an ISBN are considered; "
                                + "use 'overwrite existing' to re-scan.",
                        MetadataFetchTaskStatus.COMPLETED);
                return;
            }

            TransactionTemplate tx = new TransactionTemplate(transactionManager);
            int completed = 0;
            int isbnFound = 0;
            int enriched = 0;
            boolean cancelled = false;

            for (BookEntity book : toScan) {
                if (cancellationManager.isTaskCancelled(taskId)) {
                    cancelled = true;
                    break;
                }

                completed++;
                String displayTitle = bookTitle(book);

                try {
                    File epubFile = resolveFile(book);
                    if (epubFile == null || !epubFile.exists()) {
                        sendProgress(taskId, completed, total,
                                "File not found: " + displayTitle, MetadataFetchTaskStatus.IN_PROGRESS);
                        continue;
                    }

                    Optional<IsbnResult> result = epubIsbnScanner.scanCopyrightPageOnly(epubFile);
                    if (result.isEmpty()) {
                        sendProgress(taskId, completed, total,
                                "No copyright-page ISBN: " + displayTitle,
                                MetadataFetchTaskStatus.IN_PROGRESS);
                        continue;
                    }

                    IsbnResult isbn = result.get();
                    if (!persistIsbn(tx, book.getId(), isbn)) {
                        sendProgress(taskId, completed, total,
                                "Could not save ISBN: " + displayTitle,
                                MetadataFetchTaskStatus.IN_PROGRESS);
                        continue;
                    }
                    isbnFound++;
                    log.info("CopyrightIsbn [{}]: '{}' -> isbn13={} isbn10={}",
                            taskId, displayTitle, isbn.isbn13(), isbn.isbn10());

                    if (request.isIsbnOnly()) {
                        sendProgress(taskId, completed, total,
                                "ISBN recorded: " + displayTitle, MetadataFetchTaskStatus.IN_PROGRESS);
                        continue;
                    }

                    String lookupIsbn = isbn.isbn13() != null ? isbn.isbn13() : isbn.isbn10();
                    String source = applyMetadata(tx, book, lookupIsbn);
                    if (source != null) {
                        enriched++;
                        log.info("CopyrightIsbn [{}]: applied metadata via {} for '{}' (isbn {})",
                                taskId, source, displayTitle, lookupIsbn);
                        sendProgress(taskId, completed, total,
                                "Updated (" + source + "): " + displayTitle,
                                MetadataFetchTaskStatus.IN_PROGRESS);
                    } else {
                        sendProgress(taskId, completed, total,
                                "ISBN " + lookupIsbn + " found but no provider matched: " + displayTitle,
                                MetadataFetchTaskStatus.IN_PROGRESS);
                    }

                } catch (Exception e) {
                    log.warn("CopyrightIsbn: error processing '{}': {}", displayTitle, e.getMessage());
                    sendProgress(taskId, completed, total,
                            "Error: " + displayTitle + " — " + e.getMessage(),
                            MetadataFetchTaskStatus.IN_PROGRESS);
                }
            }

            String summary = String.format(
                    "Copyright-page scan complete. Found %d ISBN(s), applied metadata to %d, scanned %d of %d.",
                    isbnFound, enriched, completed, total);

            if (cancelled) {
                sendProgress(taskId, completed, total, "Scan cancelled. " + summary,
                        MetadataFetchTaskStatus.CANCELLED);
            } else {
                sendProgress(taskId, total, total, summary, MetadataFetchTaskStatus.COMPLETED);
            }
            log.info("CopyrightIsbn [{}]: {}", taskId, summary);

        } catch (Exception e) {
            log.error("CopyrightIsbn [{}]: fatal error: {}", taskId, e.getMessage(), e);
            sendProgress(taskId, 0, 0, "Fatal error: " + e.getMessage(),
                    MetadataFetchTaskStatus.ERROR);
        }
    }

    // ── steps ─────────────────────────────────────────────────────────────────

    private boolean persistIsbn(TransactionTemplate tx, Long bookId, IsbnResult isbn) {
        return Boolean.TRUE.equals(tx.execute(status -> {
            BookEntity managed = bookRepository.findById(bookId).orElse(null);
            if (managed == null || managed.getMetadata() == null) return false;
            BookMetadataEntity meta = managed.getMetadata();
            if (isbn.isbn13() != null) meta.setIsbn13(isbn.isbn13());
            if (isbn.isbn10() != null) meta.setIsbn10(isbn.isbn10());
            bookRepository.save(managed);
            return true;
        }));
    }

    /** Returns the provider name that supplied metadata, or null when none matched. */
    private String applyMetadata(TransactionTemplate tx, BookEntity book, String isbn) {
        FetchMetadataRequest fetchRequest = FetchMetadataRequest.builder()
                .bookId(book.getId())
                .isbn(isbn)
                .build();
        Book bookDto = Book.builder()
                .id(book.getId())
                .metadata(BookMetadata.builder().isbn13(isbn).build())
                .build();

        for (Map.Entry<String, BookParser> provider : providerChain.entrySet()) {
            BookMetadata metadata;
            try {
                metadata = provider.getValue().fetchTopMetadata(bookDto, fetchRequest);
            } catch (Exception e) {
                log.debug("CopyrightIsbn: {} failed for isbn {}: {}",
                        provider.getKey(), isbn, e.getMessage());
                continue;
            }
            if (metadata == null) continue;

            Boolean saved = tx.execute(status -> {
                List<BookEntity> managed = bookRepository.findAllWithMetadataByIds(Set.of(book.getId()));
                if (managed.isEmpty()) return false;
                bookMetadataUpdater.setBookMetadata(MetadataUpdateContext.builder()
                        .bookEntity(managed.get(0))
                        .metadataUpdateWrapper(MetadataUpdateWrapper.builder().metadata(metadata).build())
                        .updateThumbnail(true)
                        .mergeCategories(false)
                        .mergeMoods(false)
                        .mergeTags(false)
                        .replaceMode(MetadataReplaceMode.REPLACE_MISSING)
                        .autoFetch(true)
                        .build());
                return true;
            });

            if (Boolean.TRUE.equals(saved)) return provider.getKey();
        }
        return null;
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Set<Long> resolveBookIds(CopyrightIsbnScanRequest request, Long userId) {
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
                yield new LinkedHashSet<>(magicShelfBookService.getBookIdsByMagicShelfId(
                        userId, request.getMagicShelfId()));
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
        boolean noIsbn13 = meta.getIsbn13() == null || meta.getIsbn13().isBlank();
        boolean noIsbn10 = meta.getIsbn10() == null || meta.getIsbn10().isBlank();
        return noIsbn13 && noIsbn10;
    }

    private File resolveFile(BookEntity book) {
        try {
            return book.getPrimaryBookFile().getFullFilePath().toFile();
        } catch (Exception e) {
            return null;
        }
    }

    private String bookTitle(BookEntity book) {
        if (book.getMetadata() != null && book.getMetadata().getTitle() != null
                && !book.getMetadata().getTitle().isBlank()) {
            return book.getMetadata().getTitle();
        }
        BookFileEntity primary = book.getPrimaryBookFile();
        return primary != null && primary.getFileName() != null
                ? primary.getFileName()
                : "Book " + book.getId();
    }

    private void sendProgress(String taskId, int current, int total, String message,
                              MetadataFetchTaskStatus status) {
        notificationService.sendMessage(
                Topic.BOOK_METADATA_BATCH_PROGRESS,
                new MetadataBatchProgressNotification(
                        taskId, current, total, message, status.name(), false));
    }
}
