package org.booklore.service.metadata;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.config.security.service.AuthenticationService;
import org.booklore.exception.ApiError;
import org.booklore.mapper.BookMapper;
import org.booklore.model.MetadataUpdateContext;
import org.booklore.model.MetadataUpdateWrapper;
import org.booklore.model.dto.*;
import org.booklore.model.dto.request.FetchMetadataRequest;
import org.booklore.model.dto.request.MetadataRefreshOptions;
import org.booklore.model.dto.request.MetadataRefreshRequest;
import org.booklore.model.dto.settings.AppSettings;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.entity.MetadataFetchJobEntity;
import org.booklore.model.entity.MetadataFetchProposalEntity;
import org.booklore.model.enums.FetchedMetadataProposalStatus;
import org.booklore.model.enums.MetadataFetchTaskStatus;
import org.booklore.model.enums.MetadataProvider;
import org.booklore.model.enums.MetadataReplaceMode;
import org.booklore.model.websocket.Topic;
import org.booklore.repository.BookRepository;
import org.booklore.repository.LibraryRepository;
import org.booklore.repository.MetadataFetchJobRepository;
import org.booklore.service.NotificationService;
import org.booklore.service.appsettings.AppSettingService;
import org.booklore.service.metadata.parser.BookParser;
import org.booklore.task.TaskCancellationManager;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static org.booklore.model.enums.MetadataProvider.*;

@Slf4j
@AllArgsConstructor
@Service
public class MetadataRefreshService {

    private final LibraryRepository libraryRepository;
    private final MetadataFetchJobRepository metadataFetchJobRepository;
    private final BookMapper bookMapper;
    private final BookMetadataUpdater bookMetadataUpdater;
    private final NotificationService notificationService;
    private final AppSettingService appSettingService;
    private final Map<MetadataProvider, BookParser> parserMap;
    private final ObjectMapper objectMapper;
    private final BookRepository bookRepository;
    private final PlatformTransactionManager transactionManager;
    private final AuthenticationService authenticationService;
    private final TaskCancellationManager cancellationManager;

    /** Serialises Goodreads rate-limit delays across parallel book-processing threads. */
    private static final Object GOODREADS_LOCK = new Object();


    public void refreshMetadata(MetadataRefreshRequest request, String jobId) {
        BookLoreUser user = authenticationService.getAuthenticatedUser();
        Long userId = user != null ? user.getId() : null;
        final Set<Long> bookIds = null;
        final int totalBooks;
        try {
            AppSettings appSettings = appSettingService.getAppSettings();

            final boolean isLibraryRefresh = request.getRefreshType() == MetadataRefreshRequest.RefreshType.LIBRARY;
            final MetadataRefreshOptions requestRefreshOptions = request.getRefreshOptions();

            final boolean useRequestOptions = requestRefreshOptions != null;
            final MetadataRefreshOptions libraryRefreshOptions = !useRequestOptions && isLibraryRefresh ? resolveMetadataRefreshOptions(request.getLibraryId(), appSettings) : null;
            final List<MetadataProvider> fixedProviders = useRequestOptions ?
                    prepareProviders(requestRefreshOptions) :
                    (isLibraryRefresh ? prepareProviders(libraryRefreshOptions) : null);

            final Set<Long> actualBookIds = getBookEntities(request);
            totalBooks = actualBookIds.size();

            MetadataRefreshOptions reviewModeOptions = requestRefreshOptions != null ?
                    requestRefreshOptions :
                    (libraryRefreshOptions != null ? libraryRefreshOptions : appSettings.getDefaultMetadataRefreshOptions());
            boolean isReviewMode = Boolean.TRUE.equals(reviewModeOptions.getReviewBeforeApply());

            MetadataFetchJobEntity task = MetadataFetchJobEntity.builder()
                    .taskId(jobId)
                    .userId(userId)
                    .status(MetadataFetchTaskStatus.IN_PROGRESS)
                    .startedAt(Instant.now())
                    .totalBooksCount(totalBooks)
                    .completedBooks(0)
                    .build();
            metadataFetchJobRepository.save(task);

            // ── Speed improvement #1 ──────────────────────────────────────────────
            // Pre-load ALL books in one query instead of one query per book.
            // The map is used for: (a) ISBN collection for batch pre-fetch, and
            // (b) lock-check outside the per-book transaction.
            Map<Long, BookEntity> preloadedBooks = bookRepository.findAllWithMetadataByIds(actualBookIds)
                    .stream()
                    .collect(Collectors.toMap(BookEntity::getId, Function.identity()));

            List<String> allIsbns = preloadedBooks.values().stream()
                    .filter(b -> b.getMetadata() != null)
                    .map(b -> {
                        String isbn = b.getMetadata().getIsbn13();
                        if (isbn == null || isbn.isBlank()) isbn = b.getMetadata().getIsbn10();
                        return isbn;
                    })
                    .filter(Objects::nonNull)
                    .filter(isbn -> !isbn.isBlank())
                    .toList();

            // Bulk pre-fetch: one HTTP request per 20 books instead of one per book.
            if (fixedProviders != null && fixedProviders.contains(OpenLibrary) && !allIsbns.isEmpty()) {
                parserMap.get(OpenLibrary).preFetchByIsbn(allIsbns);
            }
            // ── Speed improvement #6 ──────────────────────────────────────────────
            // Google Books batch pre-fetch (5 ISBNs per request via OR query).
            if (fixedProviders != null && fixedProviders.contains(Google) && !allIsbns.isEmpty()) {
                parserMap.get(Google).preFetchByIsbn(allIsbns);
            }

            TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
            AtomicInteger completedCount = new AtomicInteger(0);
            AtomicBoolean cancelled = new AtomicBoolean(false);

            // ── Speed improvement #2 ──────────────────────────────────────────────
            // Raise default parallelism from 3 → 10. Virtual threads are cheap; the
            // old default was overly conservative for I/O-bound HTTP providers.
            int parallelism = (requestRefreshOptions != null && requestRefreshOptions.getParallelism() > 0)
                    ? requestRefreshOptions.getParallelism() : 10;
            Semaphore semaphore = new Semaphore(parallelism);

            // ── Speed improvement #3 ──────────────────────────────────────────────
            // Cache per-library refresh options so resolveMetadataRefreshOptions()
            // is called at most once per library, not once per book.
            Map<Long, MetadataRefreshOptions> libraryOptionsCache = new ConcurrentHashMap<>();
            Map<Long, List<MetadataProvider>> libraryProvidersCache = new ConcurrentHashMap<>();

            // ── Speed improvement #4 ──────────────────────────────────────────────
            // Debounce progress DB saves + WebSocket pushes: at most one per 500 ms
            // instead of one per book (which serialised all virtual threads through
            // the synchronized reportProgressIfNeeded method).
            AtomicLong lastProgressMs = new AtomicLong(0);

            // Capture the SecurityContext from the request thread so virtual threads
            // (which start with an empty ThreadLocal) can route WebSocket notifications.
            SecurityContext inheritedSecurityContext = SecurityContextHolder.getContext();
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                List<CompletableFuture<Void>> futures = actualBookIds.stream().map(bookId ->
                    CompletableFuture.runAsync(() -> {
                        SecurityContextHolder.setContext(inheritedSecurityContext);
                        try {
                        if (cancelled.get()) return;
                        try {
                            semaphore.acquire();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        try {
                            if (cancellationManager.isTaskCancelled(jobId) || cancelled.get()) {
                                cancelled.set(true);
                                return;
                            }
                            int currentCount = completedCount.get();

                            // ── Speed improvement #1 (continued) ─────────────────
                            // Use pre-loaded entity for the lock check — no transaction
                            // or DB query needed. areAllFieldsLocked() only reads scalar
                            // boolean columns which are always eagerly loaded.
                            BookEntity preloadedBook = preloadedBooks.get(bookId);
                            if (preloadedBook == null) {
                                log.warn("Book {} missing from pre-loaded cache, skipping", bookId);
                                sendBatchProgressNotification(jobId, currentCount, totalBooks, "Book not found: " + bookId, MetadataFetchTaskStatus.IN_PROGRESS, isReviewMode);
                                completedCount.incrementAndGet();
                                return;
                            }
                            if (preloadedBook.getMetadata() != null && preloadedBook.getMetadata().areAllFieldsLocked()) {
                                log.info("Skipping locked book: {}", getBookIdentifier(preloadedBook));
                                sendBatchProgressNotification(jobId, currentCount, totalBooks, "Skipped locked book: " + preloadedBook.getMetadata().getTitle(), MetadataFetchTaskStatus.IN_PROGRESS, isReviewMode);
                                completedCount.incrementAndGet();
                                return;
                            }

                            // ── Speed improvement #3 (continued) ─────────────────
                            // Resolve options outside the transaction. For LIBRARY and
                            // REQUEST types options are fixed. For BOOKS type, compute
                            // once per library and cache.
                            final MetadataRefreshOptions outerRefreshOptions;
                            final List<MetadataProvider> outerProviders;
                            if (useRequestOptions) {
                                outerRefreshOptions = requestRefreshOptions;
                                outerProviders = fixedProviders;
                            } else if (isLibraryRefresh) {
                                outerRefreshOptions = libraryRefreshOptions;
                                outerProviders = fixedProviders;
                            } else {
                                // preloadedBook.getLibrary() is a Hibernate proxy; .getId()
                                // returns the FK value without hitting the DB.
                                Long libId = preloadedBook.getLibrary().getId();
                                outerRefreshOptions = libraryOptionsCache.computeIfAbsent(
                                        libId, id -> resolveMetadataRefreshOptions(id, appSettings));
                                outerProviders = libraryProvidersCache.computeIfAbsent(
                                        libId, id -> prepareProviders(outerRefreshOptions));
                            }

                            txTemplate.execute(status -> {
                                BookEntity book = bookRepository.findAllWithMetadataByIds(Collections.singleton(bookId))
                                        .stream().findFirst()
                                        .orElseThrow(() -> ApiError.BOOK_NOT_FOUND.createException(bookId));
                                try {
                                    // areAllFieldsLocked already checked above; skip here.

                                    // Use the options resolved outside the transaction.
                                    MetadataRefreshOptions refreshOptions = outerRefreshOptions;
                                    List<MetadataProvider> providers = outerProviders;

                                    if (refreshOptions != null && refreshOptions.isSkipComplete()
                                            && isMetadataComplete(book, refreshOptions)) {
                                        log.debug("Skipping complete book: {}", getBookIdentifier(book));
                                        sendBatchProgressNotification(jobId, currentCount, totalBooks, "Skipped (already complete): " + book.getMetadata().getTitle(), MetadataFetchTaskStatus.IN_PROGRESS, isReviewMode);
                                        return null;
                                    }

                                    reportProgressIfNeeded(task, jobId, currentCount, totalBooks, book, isReviewMode, lastProgressMs);
                                    Map<MetadataProvider, BookMetadata> metadataMap = fetchMetadataForBook(providers, book);

                                    // Only sleep when GoodReads actually returned results,
                                    // and serialise through a shared lock so parallel threads
                                    // don't all hammer the rate-limit simultaneously.
                                    if (metadataMap.containsKey(GoodReads)) {
                                        synchronized (GOODREADS_LOCK) {
                                            try {
                                                Thread.sleep(ThreadLocalRandom.current().nextLong(500, 1500));
                                            } catch (InterruptedException e) {
                                                Thread.currentThread().interrupt();
                                                status.setRollbackOnly();
                                                return null;
                                            }
                                        }
                                    }

                                    if (metadataMap.isEmpty()) {
                                        log.info("No providers returned data for '{}'. Skipping update to prevent data loss.", book.getMetadata().getTitle());
                                        sendBatchProgressNotification(jobId, currentCount, totalBooks, "No data found: " + book.getMetadata().getTitle(), MetadataFetchTaskStatus.IN_PROGRESS, isReviewMode);
                                        return null;
                                    }

                                    BookMetadata fetched = null;
                                    boolean bookReviewMode = false;
                                    if (refreshOptions != null) {
                                        fetched = buildFetchMetadata(bookMapper.toBook(book).getMetadata(), book.getId(), refreshOptions, metadataMap);
                                        bookReviewMode = Boolean.TRUE.equals(refreshOptions.getReviewBeforeApply());
                                    }

                                    if (bookReviewMode) {
                                        saveProposal(task, book.getId(), fetched);
                                    } else {
                                        MetadataReplaceMode replaceMode = refreshOptions.getReplaceMode() != null
                                                ? refreshOptions.getReplaceMode()
                                                : MetadataReplaceMode.REPLACE_MISSING;
                                        updateBookMetadata(book, fetched, refreshOptions.isRefreshCovers(), refreshOptions.isMergeCategories(), replaceMode, true);
                                    }

                                    sendBatchProgressNotification(jobId, currentCount + 1, totalBooks, "Processed: " + book.getMetadata().getTitle(), MetadataFetchTaskStatus.IN_PROGRESS, bookReviewMode);
                                } catch (Exception e) {
                                    if (Thread.currentThread().isInterrupted()) {
                                        log.info("Processing interrupted for book: {}", getBookIdentifier(book));
                                        status.setRollbackOnly();
                                        return null;
                                    }
                                    log.error("Metadata update failed for book: {}", getBookIdentifier(book), e);
                                    sendBatchProgressNotification(jobId, currentCount, totalBooks, String.format("Failed to process: %s - %s", book.getMetadata().getTitle(), e.getMessage()), MetadataFetchTaskStatus.ERROR, isReviewMode);
                                }
                                // ── Speed improvement #5 ─────────────────────────
                                // Replace saveAndFlush with save: the transaction
                                // template commits on exit, which triggers an automatic
                                // flush. The explicit flush round-trip is unnecessary.
                                bookRepository.save(book);
                                return null;
                            });
                            completedCount.incrementAndGet();
                        } catch (Exception e) {
                            log.error("Unexpected error processing bookId {}: {}", bookId, e.getMessage(), e);
                        } finally {
                            semaphore.release();
                        }
                        } finally {
                            SecurityContextHolder.clearContext();
                        }
                    }, executor)
                ).toList();

                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            }

            if (cancellationManager.isTaskCancelled(jobId) || cancelled.get()) {
                log.info("RefreshMetadataTask {} was cancelled, stopping execution", jobId);
                cancelTask(task);
                cancellationManager.clearCancellation(jobId);
                return;
            }

            completeTask(task, completedCount.get(), totalBooks, isReviewMode);
            cancellationManager.clearCancellation(jobId);
            log.info("Metadata refresh task {} completed successfully", jobId);

        } catch (RuntimeException e) {
            cancellationManager.clearCancellation(jobId);
            if (e.getCause() instanceof InterruptedException) {
                log.info("Metadata refresh task {} cancelled successfully", jobId);
                return;
            }
            log.error("Fatal error during metadata refresh", e);
            int totalBooksForError = 0;
            sendBatchProgressNotification(jobId, 0, totalBooksForError, "Fatal error during metadata refresh: " + e.getMessage(), MetadataFetchTaskStatus.ERROR, false);
            throw e;
        } catch (Exception fatal) {
            cancellationManager.clearCancellation(jobId);
            log.error("Fatal error during metadata refresh", fatal);
            int totalBooksForError = bookIds != null ? bookIds.size() : 0;
            sendBatchProgressNotification(jobId, 0, totalBooksForError, "Fatal error during metadata refresh: " + fatal.getMessage(), MetadataFetchTaskStatus.ERROR, false);
            throw fatal;
        }
    }

    MetadataRefreshOptions resolveMetadataRefreshOptions(Long libraryId, AppSettings appSettings) {
        MetadataRefreshOptions defaultOptions = appSettings.getDefaultMetadataRefreshOptions();
        List<MetadataRefreshOptions> libraryOptions = appSettings.getLibraryMetadataRefreshOptions();

        if (libraryId != null && libraryOptions != null) {
            return libraryOptions.stream()
                    .filter(options -> libraryId.equals(options.getLibraryId()))
                    .findFirst()
                    .orElse(defaultOptions);
        }

        return defaultOptions;
    }

    public Map<MetadataProvider, BookMetadata> fetchMetadataForBook(List<MetadataProvider> providers, Book book) {
        // Query all providers in parallel using virtual threads
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<BookMetadata>> futures = providers.stream()
                    .map(provider -> executor.submit(() -> fetchTopMetadataFromAProvider(provider, book)))
                    .toList();
            return futures.stream()
                    .map(f -> {
                        try { return f.get(); } catch (Exception e) { return null; }
                    })
                    .filter(Objects::nonNull)
                    .collect(Collectors.toMap(
                            BookMetadata::getProvider,
                            metadata -> metadata,
                            (existing, replacement) -> existing
                    ));
        }
    }

    public Map<MetadataProvider, BookMetadata> fetchMetadataForBook(List<MetadataProvider> providers, BookEntity bookEntity) {
        return fetchMetadataForBook(providers, bookMapper.toBook(bookEntity));
    }

    /**
     * Reports fetch progress to the DB and WebSocket, throttled to at most one
     * update per 500 ms. The old synchronized method serialised all virtual threads
     * for every book; the CAS + time-gate here allows threads to bail out cheaply
     * when a recent update has already been sent.
     */
    private void reportProgressIfNeeded(MetadataFetchJobEntity task, String taskId, int completedCount, int total,
                                        BookEntity book, boolean isReviewMode, AtomicLong lastProgressMs) {
        if (task == null) return;
        long now = System.currentTimeMillis();
        long last = lastProgressMs.get();
        // Always fire on the final book; otherwise throttle to 500 ms.
        if (completedCount < total && now - last < 500) return;
        // Only one thread wins the CAS and performs the update.
        if (!lastProgressMs.compareAndSet(last, now)) return;
        synchronized (task) {
            task.setCompletedBooks(completedCount);
            metadataFetchJobRepository.save(task);
        }
        String message = String.format("Processing '%s'", book.getMetadata().getTitle());
        sendBatchProgressNotification(taskId, completedCount, total, message, MetadataFetchTaskStatus.IN_PROGRESS, isReviewMode);
    }

    private String getBookIdentifier(BookEntity book) {
        if (book.getPrimaryBookFile() != null && book.getPrimaryBookFile().getFileName() != null) {
            return book.getPrimaryBookFile().getFileName();
        }
        if (book.getMetadata() != null && book.getMetadata().getTitle() != null) {
            return book.getMetadata().getTitle();
        }
        return "Book ID: " + book.getId();
    }

    private void sendBatchProgressNotification(String taskId, int current, int total, String message, MetadataFetchTaskStatus status, boolean isReview) {
        notificationService.sendMessage(Topic.BOOK_METADATA_BATCH_PROGRESS, new MetadataBatchProgressNotification(taskId, current, total, message, status.name(), isReview));
    }

    private void completeTask(MetadataFetchJobEntity task, int completed, int total, boolean isReviewMode) {
        task.setStatus(MetadataFetchTaskStatus.COMPLETED);
        task.setCompletedAt(Instant.now());
        task.setCompletedBooks(completed);
        metadataFetchJobRepository.save(task);
        sendBatchProgressNotification(task.getTaskId(), completed, total, "Batch metadata fetch successfully completed!", MetadataFetchTaskStatus.COMPLETED, isReviewMode);
    }

    private void cancelTask(MetadataFetchJobEntity task) {
        task.setStatus(MetadataFetchTaskStatus.CANCELLED);
        task.setCompletedAt(Instant.now());
        metadataFetchJobRepository.save(task);
        sendBatchProgressNotification(task.getTaskId(), task.getCompletedBooks(), task.getTotalBooksCount(), "Task cancelled by user", MetadataFetchTaskStatus.CANCELLED, false);
    }

    private synchronized void saveProposal(MetadataFetchJobEntity job, Long bookId, BookMetadata metadata) throws JacksonException {
        MetadataFetchProposalEntity proposal = MetadataFetchProposalEntity.builder()
                .job(job)
                .bookId(bookId)
                .metadataJson(objectMapper.writeValueAsString(metadata))
                .status(FetchedMetadataProposalStatus.FETCHED)
                .fetchedAt(Instant.now())
                .build();
        job.getProposals().add(proposal);
    }


    public void updateBookMetadata(BookEntity bookEntity, BookMetadata metadata, boolean replaceCover, boolean mergeCategories) {
        updateBookMetadata(bookEntity, metadata, replaceCover, mergeCategories, MetadataReplaceMode.REPLACE_MISSING);
    }

    public void updateBookMetadata(BookEntity bookEntity, BookMetadata metadata, boolean replaceCover, boolean mergeCategories, MetadataReplaceMode replaceMode) {
        updateBookMetadata(bookEntity, metadata, replaceCover, mergeCategories, replaceMode, false);
    }

    public void updateBookMetadata(BookEntity bookEntity, BookMetadata metadata, boolean replaceCover, boolean mergeCategories, MetadataReplaceMode replaceMode, boolean autoFetch) {
        MetadataUpdateContext context = MetadataUpdateContext.builder()
                .bookEntity(bookEntity)
                .metadataUpdateWrapper(MetadataUpdateWrapper.builder()
                        .metadata(metadata)
                        .build())
                .updateThumbnail(replaceCover)
                .mergeCategories(mergeCategories)
                .replaceMode(replaceMode)
                .mergeMoods(true)
                .mergeTags(true)
                .autoFetch(autoFetch)
                .build();

        updateBookMetadata(context);
    }

    public void updateBookMetadata(MetadataUpdateContext context) {
        if (context.getMetadataUpdateWrapper() != null && context.getMetadataUpdateWrapper().getMetadata() != null) {
            bookMetadataUpdater.setBookMetadata(context);

            Book book = bookMapper.toBookWithDescription(context.getBookEntity(), true);
            
            BookLoreUser user = authenticationService.getAuthenticatedUser();
            if (user != null && book.getShelves() != null) {
                book.setShelves(filterShelvesByUserId(book.getShelves(), user.getId()));
            }
            
            notificationService.sendMessage(Topic.BOOK_METADATA_UPDATE, book);
        }
    }

    public List<MetadataProvider> prepareProviders(MetadataRefreshOptions refreshOptions) {
        AppSettings appSettings = appSettingService.getAppSettings();
        Set<MetadataProvider> allProviders = EnumSet.noneOf(MetadataProvider.class);
        allProviders.addAll(getAllProvidersUsingIndividualFields(refreshOptions, appSettings));
        return new ArrayList<>(allProviders);
    }

    protected Set<MetadataProvider> getAllProvidersUsingIndividualFields(MetadataRefreshOptions refreshOptions, AppSettings appSettings) {
        MetadataRefreshOptions.FieldOptions fieldOptions = refreshOptions.getFieldOptions();
        Set<MetadataProvider> uniqueProviders = EnumSet.noneOf(MetadataProvider.class);

        if (fieldOptions != null) {
            addProviderToSet(fieldOptions.getTitle(), uniqueProviders, appSettings);
            addProviderToSet(fieldOptions.getSubtitle(), uniqueProviders, appSettings);
            addProviderToSet(fieldOptions.getDescription(), uniqueProviders, appSettings);
            addProviderToSet(fieldOptions.getAuthors(), uniqueProviders, appSettings);
            addProviderToSet(fieldOptions.getPublisher(), uniqueProviders, appSettings);
            addProviderToSet(fieldOptions.getPublishedDate(), uniqueProviders, appSettings);
            addProviderToSet(fieldOptions.getSeriesName(), uniqueProviders, appSettings);
            addProviderToSet(fieldOptions.getSeriesNumber(), uniqueProviders, appSettings);
            addProviderToSet(fieldOptions.getSeriesTotal(), uniqueProviders, appSettings);
            addProviderToSet(fieldOptions.getIsbn13(), uniqueProviders, appSettings);
            addProviderToSet(fieldOptions.getIsbn10(), uniqueProviders, appSettings);
            addProviderToSet(fieldOptions.getLanguage(), uniqueProviders, appSettings);
            addProviderToSet(fieldOptions.getCategories(), uniqueProviders, appSettings);
            addProviderToSet(fieldOptions.getCover(), uniqueProviders, appSettings);
            addProviderToSet(fieldOptions.getPageCount(), uniqueProviders, appSettings);
            addProviderToSet(fieldOptions.getAsin(), uniqueProviders, appSettings);
            addProviderToSet(fieldOptions.getGoodreadsId(), uniqueProviders, appSettings);
            addProviderToSet(fieldOptions.getComicvineId(), uniqueProviders, appSettings);
            addProviderToSet(fieldOptions.getHardcoverId(), uniqueProviders, appSettings);
            addProviderToSet(fieldOptions.getGoogleId(), uniqueProviders, appSettings);
            addProviderToSet(fieldOptions.getLubimyczytacId(), uniqueProviders, appSettings);
            addProviderToSet(fieldOptions.getAmazonRating(), uniqueProviders, appSettings);
            addProviderToSet(fieldOptions.getAmazonReviewCount(), uniqueProviders, appSettings);
            addProviderToSet(fieldOptions.getGoodreadsRating(), uniqueProviders, appSettings);
            addProviderToSet(fieldOptions.getGoodreadsReviewCount(), uniqueProviders, appSettings);
            addProviderToSet(fieldOptions.getHardcoverRating(), uniqueProviders, appSettings);
            addProviderToSet(fieldOptions.getHardcoverReviewCount(), uniqueProviders, appSettings);
            addProviderToSet(fieldOptions.getLubimyczytacRating(), uniqueProviders, appSettings);
            addProviderToSet(fieldOptions.getRanobedbId(), uniqueProviders, appSettings);
            addProviderToSet(fieldOptions.getRanobedbRating(), uniqueProviders, appSettings);
            addProviderToSet(fieldOptions.getMoods(), uniqueProviders, appSettings);
            addProviderToSet(fieldOptions.getTags(), uniqueProviders, appSettings);
        }

        return uniqueProviders;
    }

    protected void addProviderToSet(MetadataRefreshOptions.FieldProvider fieldProvider, Set<MetadataProvider> providerSet, AppSettings appSettings) {
        if (fieldProvider != null) {
            if (fieldProvider.getP1() != null && isProviderEnabled(fieldProvider.getP1(), appSettings)) providerSet.add(fieldProvider.getP1());
            if (fieldProvider.getP2() != null && isProviderEnabled(fieldProvider.getP2(), appSettings)) providerSet.add(fieldProvider.getP2());
            if (fieldProvider.getP3() != null && isProviderEnabled(fieldProvider.getP3(), appSettings)) providerSet.add(fieldProvider.getP3());
            if (fieldProvider.getP4() != null && isProviderEnabled(fieldProvider.getP4(), appSettings)) providerSet.add(fieldProvider.getP4());
        }
    }

    protected boolean isProviderEnabled(MetadataProvider provider, AppSettings appSettings) {
        if (provider == null || appSettings == null || appSettings.getMetadataProviderSettings() == null) {
            return true;
        }

        var settings = appSettings.getMetadataProviderSettings();
        return switch (provider) {
            case Amazon -> settings.getAmazon() != null && settings.getAmazon().isEnabled();
            case Google -> settings.getGoogle() != null && settings.getGoogle().isEnabled();
            case GoodReads -> settings.getGoodReads() != null && settings.getGoodReads().isEnabled();
            case Hardcover -> settings.getHardcover() != null && settings.getHardcover().isEnabled();
            case OpenLibrary -> settings.getOpenLibrary() != null && settings.getOpenLibrary().isEnabled();
            case Comicvine -> settings.getComicvine() != null && settings.getComicvine().isEnabled();
            case Ranobedb -> settings.getRanobedb() != null && settings.getRanobedb().isEnabled();
            case Douban -> settings.getDouban() != null && settings.getDouban().isEnabled();
            case Lubimyczytac -> settings.getLubimyczytac() != null && settings.getLubimyczytac().isEnabled();
            case OpenLibraryLocal -> settings.getOpenLibraryLocal() != null && settings.getOpenLibraryLocal().isEnabled();
            default -> true;
        };
    }

    public BookMetadata fetchTopMetadataFromAProvider(MetadataProvider provider, Book book) {
        return getParser(provider).fetchTopMetadata(book, buildFetchMetadataRequestFromBook(book));
    }

    private BookParser getParser(MetadataProvider provider) {
        BookParser parser = parserMap.get(provider);
        if (parser == null) {
            throw ApiError.METADATA_SOURCE_NOT_IMPLEMENT_OR_DOES_NOT_EXIST.createException();
        }
        return parser;
    }

    private FetchMetadataRequest buildFetchMetadataRequestFromBook(Book book) {
        BookMetadata metadata = book.getMetadata();
        if (metadata == null) {
            return FetchMetadataRequest.builder()
                    .bookId(book.getId())
                    .build();
        }
        String isbn = metadata.getIsbn13();
        if (isbn == null || isbn.isBlank()) {
            isbn = metadata.getIsbn10();
        }
        return FetchMetadataRequest.builder()
                .isbn(isbn)
                .asin(metadata.getAsin())
                .author(metadata.getAuthors() != null ? String.join(", ", metadata.getAuthors()) : null)
                .title(metadata.getTitle())
                .bookId(book.getId())
                .build();
    }

    public BookMetadata buildFetchMetadata(BookMetadata existingMetadata, Long bookId, MetadataRefreshOptions refreshOptions, Map<MetadataProvider, BookMetadata> metadataMap) {
        BookMetadata metadata = BookMetadata.builder().bookId(bookId).build();

        MetadataRefreshOptions.FieldOptions fieldOptions = refreshOptions.getFieldOptions();
        if (fieldOptions == null) {
            fieldOptions = new MetadataRefreshOptions.FieldOptions();
        }

        MetadataRefreshOptions.EnabledFields enabledFields = refreshOptions.getEnabledFields();
        if (enabledFields == null) {
            enabledFields = new MetadataRefreshOptions.EnabledFields();
        }
        
        MetadataReplaceMode replaceMode = refreshOptions.getReplaceMode();
        boolean isReplaceAll = replaceMode == MetadataReplaceMode.REPLACE_ALL;

        if (enabledFields.isTitle()) {
            metadata.setTitle(resolveFieldAsString(metadataMap, fieldOptions.getTitle(), BookMetadata::getTitle));
        } else if (isReplaceAll && existingMetadata != null) {
            metadata.setTitle(existingMetadata.getTitle());
        }
        
        if (enabledFields.isSubtitle()) {
            metadata.setSubtitle(resolveFieldAsString(metadataMap, fieldOptions.getSubtitle(), BookMetadata::getSubtitle));
        } else if (isReplaceAll && existingMetadata != null) {
            metadata.setSubtitle(existingMetadata.getSubtitle());
        }
        
        if (enabledFields.isDescription()) {
            metadata.setDescription(resolveFieldAsString(metadataMap, fieldOptions.getDescription(), BookMetadata::getDescription));
        } else if (isReplaceAll && existingMetadata != null) {
            metadata.setDescription(existingMetadata.getDescription());
        }
        
        if (enabledFields.isAuthors()) {
            metadata.setAuthors(resolveFieldAsList(metadataMap, fieldOptions.getAuthors(), BookMetadata::getAuthors));
        } else if (isReplaceAll && existingMetadata != null) {
            metadata.setAuthors(existingMetadata.getAuthors());
        }
        
        if (enabledFields.isPublisher()) {
            metadata.setPublisher(resolveFieldAsString(metadataMap, fieldOptions.getPublisher(), BookMetadata::getPublisher));
        } else if (isReplaceAll && existingMetadata != null) {
            metadata.setPublisher(existingMetadata.getPublisher());
        }
        
        if (enabledFields.isPublishedDate()) {
            metadata.setPublishedDate(resolveField(metadataMap, fieldOptions.getPublishedDate(), BookMetadata::getPublishedDate));
        } else if (isReplaceAll && existingMetadata != null) {
            metadata.setPublishedDate(existingMetadata.getPublishedDate());
        }
        
        if (enabledFields.isSeriesName()) {
            metadata.setSeriesName(resolveFieldAsString(metadataMap, fieldOptions.getSeriesName(), BookMetadata::getSeriesName));
        } else if (isReplaceAll && existingMetadata != null) {
            metadata.setSeriesName(existingMetadata.getSeriesName());
        }
        
        if (enabledFields.isSeriesNumber()) {
            metadata.setSeriesNumber(resolveField(metadataMap, fieldOptions.getSeriesNumber(), BookMetadata::getSeriesNumber));
        } else if (isReplaceAll && existingMetadata != null) {
            metadata.setSeriesNumber(existingMetadata.getSeriesNumber());
        }
        
        if (enabledFields.isSeriesTotal()) {
            metadata.setSeriesTotal(resolveFieldAsInteger(metadataMap, fieldOptions.getSeriesTotal(), BookMetadata::getSeriesTotal));
        } else if (isReplaceAll && existingMetadata != null) {
            metadata.setSeriesTotal(existingMetadata.getSeriesTotal());
        }
        
        if (enabledFields.isIsbn13()) {
            metadata.setIsbn13(resolveFieldAsString(metadataMap, fieldOptions.getIsbn13(), BookMetadata::getIsbn13));
        } else if (isReplaceAll && existingMetadata != null) {
            metadata.setIsbn13(existingMetadata.getIsbn13());
        }
        
        if (enabledFields.isIsbn10()) {
            metadata.setIsbn10(resolveFieldAsString(metadataMap, fieldOptions.getIsbn10(), BookMetadata::getIsbn10));
        } else if (isReplaceAll && existingMetadata != null) {
            metadata.setIsbn10(existingMetadata.getIsbn10());
        }
        
        if (enabledFields.isLanguage()) {
            metadata.setLanguage(resolveFieldAsString(metadataMap, fieldOptions.getLanguage(), BookMetadata::getLanguage));
        } else if (isReplaceAll && existingMetadata != null) {
            metadata.setLanguage(existingMetadata.getLanguage());
        }
        
        if (enabledFields.isPageCount()) {
            metadata.setPageCount(resolveFieldAsInteger(metadataMap, fieldOptions.getPageCount(), BookMetadata::getPageCount));
        } else if (isReplaceAll && existingMetadata != null) {
            metadata.setPageCount(existingMetadata.getPageCount());
        }
        
        if (enabledFields.isCover()) {
            metadata.setThumbnailUrl(resolveFieldAsString(metadataMap, fieldOptions.getCover(), BookMetadata::getThumbnailUrl));
        } else if (isReplaceAll && existingMetadata != null) {
            metadata.setThumbnailUrl(existingMetadata.getThumbnailUrl());
        }
        if (enabledFields.isAmazonRating()) {
            if (metadataMap.containsKey(Amazon)) {
                metadata.setAmazonRating(metadataMap.get(Amazon).getAmazonRating());
            }
        } else if (isReplaceAll && existingMetadata != null) {
            metadata.setAmazonRating(existingMetadata.getAmazonRating());
        }

        if (enabledFields.isAmazonReviewCount()) {
            if (metadataMap.containsKey(Amazon)) {
                metadata.setAmazonReviewCount(metadataMap.get(Amazon).getAmazonReviewCount());
            }
        } else if (isReplaceAll && existingMetadata != null) {
            metadata.setAmazonReviewCount(existingMetadata.getAmazonReviewCount());
        }

        if (enabledFields.isGoodreadsRating()) {
            if (metadataMap.containsKey(GoodReads)) {
                metadata.setGoodreadsRating(metadataMap.get(GoodReads).getGoodreadsRating());
            }
        } else if (isReplaceAll && existingMetadata != null) {
            metadata.setGoodreadsRating(existingMetadata.getGoodreadsRating());
        }

        if (enabledFields.isGoodreadsReviewCount()) {
            if (metadataMap.containsKey(GoodReads)) {
                metadata.setGoodreadsReviewCount(metadataMap.get(GoodReads).getGoodreadsReviewCount());
            }
        } else if (isReplaceAll && existingMetadata != null) {
            metadata.setGoodreadsReviewCount(existingMetadata.getGoodreadsReviewCount());
        }

        if (enabledFields.isHardcoverRating()) {
            if (metadataMap.containsKey(Hardcover)) {
                metadata.setHardcoverRating(metadataMap.get(Hardcover).getHardcoverRating());
            }
        } else if (isReplaceAll && existingMetadata != null) {
            metadata.setHardcoverRating(existingMetadata.getHardcoverRating());
        }

        if (enabledFields.isHardcoverReviewCount()) {
            if (metadataMap.containsKey(Hardcover)) {
                metadata.setHardcoverReviewCount(metadataMap.get(Hardcover).getHardcoverReviewCount());
            }
        } else if (isReplaceAll && existingMetadata != null) {
            metadata.setHardcoverReviewCount(existingMetadata.getHardcoverReviewCount());
        }

        if (enabledFields.isAsin()) {
            if (metadataMap.containsKey(Amazon)) {
                metadata.setAsin(metadataMap.get(Amazon).getAsin());
            }
        } else if (isReplaceAll && existingMetadata != null) {
            metadata.setAsin(existingMetadata.getAsin());
        }
        if (enabledFields.isGoodreadsId()) {
            if (metadataMap.containsKey(GoodReads)) {
                metadata.setGoodreadsId(metadataMap.get(GoodReads).getGoodreadsId());
            }
        } else if (isReplaceAll && existingMetadata != null) {
            metadata.setGoodreadsId(existingMetadata.getGoodreadsId());
        }

        if (enabledFields.isHardcoverId()) {
            if (metadataMap.containsKey(Hardcover)) {
                metadata.setHardcoverId(metadataMap.get(Hardcover).getHardcoverId());
                metadata.setHardcoverBookId(metadataMap.get(Hardcover).getHardcoverBookId());
            }
        } else if (isReplaceAll && existingMetadata != null) {
            metadata.setHardcoverId(existingMetadata.getHardcoverId());
            metadata.setHardcoverBookId(existingMetadata.getHardcoverBookId());
        }

        if (enabledFields.isGoogleId()) {
            if (metadataMap.containsKey(Google)) {
                metadata.setGoogleId(metadataMap.get(Google).getGoogleId());
            }
        } else if (isReplaceAll && existingMetadata != null) {
            metadata.setGoogleId(existingMetadata.getGoogleId());
        }

        if (enabledFields.isComicvineId()) {
            if (metadataMap.containsKey(Comicvine)) {
                metadata.setComicvineId(metadataMap.get(Comicvine).getComicvineId());
            }
        } else if (isReplaceAll && existingMetadata != null) {
            metadata.setComicvineId(existingMetadata.getComicvineId());
        }

        if (metadataMap.containsKey(Comicvine) && metadataMap.get(Comicvine).getComicMetadata() != null) {
            metadata.setComicMetadata(metadataMap.get(Comicvine).getComicMetadata());
        }

        if (enabledFields.isLubimyczytacId()) {
            if (metadataMap.containsKey(Lubimyczytac)) {
                metadata.setLubimyczytacId(metadataMap.get(Lubimyczytac).getLubimyczytacId());
            }
        } else if (isReplaceAll && existingMetadata != null) {
            metadata.setLubimyczytacId(existingMetadata.getLubimyczytacId());
        }

        if (enabledFields.isLubimyczytacRating()) {
            if (metadataMap.containsKey(Lubimyczytac)) {
                metadata.setLubimyczytacRating(metadataMap.get(Lubimyczytac).getLubimyczytacRating());
            }
        } else if (isReplaceAll && existingMetadata != null) {
            metadata.setLubimyczytacRating(existingMetadata.getLubimyczytacRating());
        }

        if (enabledFields.isRanobedbId()) {
            if (metadataMap.containsKey(Ranobedb)) {
                metadata.setRanobedbId(metadataMap.get(Ranobedb).getRanobedbId());
            }
        } else if (isReplaceAll && existingMetadata != null) {
            metadata.setRanobedbId(existingMetadata.getRanobedbId());
        }

        if (enabledFields.isRanobedbRating()) {
            if (metadataMap.containsKey(Ranobedb)) {
                metadata.setRanobedbRating(metadataMap.get(Ranobedb).getRanobedbRating());
            }
        } else if (isReplaceAll && existingMetadata != null) {
            metadata.setRanobedbRating(existingMetadata.getRanobedbRating());
        }

        if (enabledFields.isMoods()) {
            if (metadataMap.containsKey(Hardcover)) {
                metadata.setMoods(metadataMap.get(Hardcover).getMoods());
            }
        } else if (isReplaceAll && existingMetadata != null) {
            metadata.setMoods(existingMetadata.getMoods());
        }

        if (enabledFields.isTags()) {
            if (metadataMap.containsKey(Hardcover)) {
                metadata.setTags(metadataMap.get(Hardcover).getTags());
            }
        } else if (isReplaceAll && existingMetadata != null) {
            metadata.setTags(existingMetadata.getTags());
        }

        if (enabledFields.isCategories()) {
            if (refreshOptions.isMergeCategories()) {
                metadata.setCategories(getAllCategories(metadataMap, fieldOptions.getCategories(), BookMetadata::getCategories));
            } else {
                metadata.setCategories(resolveFieldAsSet(metadataMap, fieldOptions.getCategories(), BookMetadata::getCategories));
            }
        } else if (isReplaceAll && existingMetadata != null) {
            metadata.setCategories(existingMetadata.getCategories());
        }

        List<BookReview> allReviews = metadataMap.values().stream()
                .filter(Objects::nonNull)
                .flatMap(md -> Optional.ofNullable(md.getBookReviews()).stream().flatMap(Collection::stream))
                .collect(Collectors.toList());
        if (!allReviews.isEmpty()) {
            metadata.setBookReviews(allReviews);
        }

        return metadata;
    }

    protected <T > T resolveField(Map < MetadataProvider, BookMetadata > metadataMap, MetadataRefreshOptions.FieldProvider fieldProvider, Function < BookMetadata, T > extractor) {
        return resolveFieldWithProviders(metadataMap, fieldProvider, extractor, Objects::nonNull);
    }

    protected Integer resolveFieldAsInteger (Map < MetadataProvider, BookMetadata > metadataMap, MetadataRefreshOptions.FieldProvider fieldProvider, Function < BookMetadata, Integer > fieldValueExtractor){
        return resolveFieldWithProviders(metadataMap, fieldProvider, fieldValueExtractor, Objects::nonNull);
    }

    protected String resolveFieldAsString (Map < MetadataProvider, BookMetadata > metadataMap, MetadataRefreshOptions.FieldProvider fieldProvider, FieldValueExtractor fieldValueExtractor){
        return resolveFieldWithProviders(metadataMap, fieldProvider, fieldValueExtractor::extract, Objects::nonNull);
    }

    protected List<String> resolveFieldAsList (Map < MetadataProvider, BookMetadata > metadataMap, MetadataRefreshOptions.FieldProvider fieldProvider, FieldValueExtractorList fieldValueExtractor){
        Collection<String> result = resolveFieldWithProviders(metadataMap, fieldProvider, fieldValueExtractor::extract, (value) -> value != null && !value.isEmpty());
        if (result == null) return null;
        return result instanceof List<String> list ? list : new ArrayList<>(result);
    }

    protected Set<String> resolveFieldAsSet (Map < MetadataProvider, BookMetadata > metadataMap, MetadataRefreshOptions.FieldProvider fieldProvider, FieldValueExtractorList fieldValueExtractor){
        Collection<String> result = resolveFieldWithProviders(metadataMap, fieldProvider, fieldValueExtractor::extract, (value) -> value != null && !value.isEmpty());
        if (result == null) return null;
        return result instanceof Set<String> set ? set : new HashSet<>(result);
    }

    private <T > T resolveFieldWithProviders(Map < MetadataProvider, BookMetadata > metadataMap, MetadataRefreshOptions.FieldProvider fieldProvider, Function < BookMetadata, T > extractor, Predicate < T > isValidValue) {
        if (fieldProvider == null) {
            return null;
        }
        MetadataProvider[] providers = {
                fieldProvider.getP1(),
                fieldProvider.getP2(),
                fieldProvider.getP3(),
                fieldProvider.getP4()
        };
        for (MetadataProvider provider : providers) {
            if (provider != null && metadataMap.containsKey(provider)) {
                T value = extractor.apply(metadataMap.get(provider));
                if (isValidValue.test(value)) {
                    return value;
                }
            }
        }
        return null;
    }

    Set<String> getAllCategories (Map < MetadataProvider, BookMetadata > metadataMap, MetadataRefreshOptions.FieldProvider fieldProvider, FieldValueExtractorList fieldValueExtractor){
        Set<String> uniqueCategories = new HashSet<>();
        if (fieldProvider == null) {
            return uniqueCategories;
        }

        MetadataProvider[] providers = {
                fieldProvider.getP1(),
                fieldProvider.getP2(),
                fieldProvider.getP3(),
                fieldProvider.getP4()
        };

        for (MetadataProvider provider : providers) {
            if (provider != null && metadataMap.containsKey(provider)) {
                Collection<String> extracted = fieldValueExtractor.extract(metadataMap.get(provider));
                if (extracted != null) {
                    uniqueCategories.addAll(extracted);
                }
            }
        }

        return uniqueCategories;
    }

    protected Set<Long> getBookEntities (MetadataRefreshRequest request){
        MetadataRefreshRequest.RefreshType refreshType = request.getRefreshType();
        if (refreshType != MetadataRefreshRequest.RefreshType.LIBRARY && refreshType != MetadataRefreshRequest.RefreshType.BOOKS) {
            throw ApiError.INVALID_REFRESH_TYPE.createException();
        }
        return switch (refreshType) {
            case LIBRARY -> {
                LibraryEntity libraryEntity = libraryRepository.findById(request.getLibraryId()).orElseThrow(() -> ApiError.LIBRARY_NOT_FOUND.createException(request.getLibraryId()));
                yield bookRepository.findBookIdsByLibraryId(libraryEntity.getId());
            }
            case BOOKS -> request.getBookIds();
        };
    }

    /**
     * Returns true when every enabled field in the book's existing metadata already
     * has a non-empty value.  Used to short-circuit processing when skipComplete=true.
     */
    private boolean isMetadataComplete(BookEntity book, MetadataRefreshOptions options) {
        if (options == null) return false;
        MetadataRefreshOptions.EnabledFields enabled = options.getEnabledFields();
        if (enabled == null) return false;
        BookMetadata meta = bookMapper.toBook(book).getMetadata();
        if (meta == null) return false;

        if (enabled.isTitle() && isBlankField(meta.getTitle())) return false;
        if (enabled.isDescription() && isBlankField(meta.getDescription())) return false;
        if (enabled.isAuthors() && (meta.getAuthors() == null || meta.getAuthors().isEmpty())) return false;
        if (enabled.isPublisher() && isBlankField(meta.getPublisher())) return false;
        if (enabled.isPublishedDate() && meta.getPublishedDate() == null) return false;
        if (enabled.isSeriesName() && isBlankField(meta.getSeriesName())) return false;
        if (enabled.isPageCount() && (meta.getPageCount() == null || meta.getPageCount() == 0)) return false;
        if (enabled.isIsbn13() && isBlankField(meta.getIsbn13())) return false;
        return true;
    }

    private boolean isBlankField(String s) {
        return s == null || s.isBlank();
    }

    private Set<Shelf> filterShelvesByUserId(Set<Shelf> shelves, Long userId) {
        if (shelves == null) return Collections.emptySet();
        return shelves.stream()
                .filter(shelf -> userId.equals(shelf.getUserId()))
                .collect(Collectors.toSet());
    }
}
