package org.booklore.service.comic;

import lombok.extern.slf4j.Slf4j;
import org.booklore.config.security.service.AuthenticationService;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.MetadataBatchProgressNotification;
import org.booklore.model.dto.request.ComicDetectionRequest;
import org.booklore.model.dto.settings.ComicDetectionSettings;
import org.booklore.model.entity.ComicDetectionCandidateEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.model.enums.ComicCandidateStatus;
import org.booklore.model.enums.ComicVerdict;
import org.booklore.model.enums.MetadataFetchTaskStatus;
import org.booklore.model.websocket.Topic;
import org.booklore.repository.BookRepository;
import org.booklore.repository.ComicDetectionCandidateRepository;
import org.booklore.service.NotificationService;
import org.booklore.service.appsettings.AppSettingService;
import org.booklore.service.opds.MagicShelfBookService;
import org.booklore.task.TaskCancellationManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.File;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

/**
 * Scans books and decides which are comics, using layered evidence.
 *
 * <p>Stages run cheapest-first and stop early once conclusive:
 * <ol>
 *   <li>File type — CBZ/CBR/CB7 is definitive</li>
 *   <li>Container — an embedded ComicInfo.xml is definitive</li>
 *   <li>Structure — EPUB fixed-layout/image ratio/text density, PDF text layer and images</li>
 *   <li>Metadata — publisher, categories, title shape</li>
 *   <li>LLM — tiebreaker, borderline band only</li>
 * </ol>
 *
 * <p>Books are processed in chunks: each chunk is flattened into
 * {@link ComicScanTarget}s inside a short read-only transaction, then scored
 * with no session held open and no entities retained. That keeps a
 * whole-library scan flat in memory and keeps slow file I/O off the database
 * connection.
 */
@Slf4j
@Service
public class ComicDetectionService {

    /** Books flattened and scored per pass. */
    private static final int CHUNK_SIZE = 250;

    private final BookRepository bookRepository;
    private final ComicDetectionCandidateRepository candidateRepository;
    private final ComicScanTargetLoader scanTargetLoader;
    private final EpubComicAnalyzer epubComicAnalyzer;
    private final PdfComicAnalyzer pdfComicAnalyzer;
    private final ComicMetadataHeuristics metadataHeuristics;
    private final ComicOllamaClassifier ollamaClassifier;
    private final AppSettingService appSettingService;
    private final NotificationService notificationService;
    private final PlatformTransactionManager transactionManager;
    private final AuthenticationService authenticationService;
    private final TaskCancellationManager cancellationManager;
    private final MagicShelfBookService magicShelfBookService;

    public ComicDetectionService(BookRepository bookRepository,
                                 ComicDetectionCandidateRepository candidateRepository,
                                 ComicScanTargetLoader scanTargetLoader,
                                 EpubComicAnalyzer epubComicAnalyzer,
                                 PdfComicAnalyzer pdfComicAnalyzer,
                                 ComicMetadataHeuristics metadataHeuristics,
                                 ComicOllamaClassifier ollamaClassifier,
                                 AppSettingService appSettingService,
                                 NotificationService notificationService,
                                 PlatformTransactionManager transactionManager,
                                 AuthenticationService authenticationService,
                                 TaskCancellationManager cancellationManager,
                                 MagicShelfBookService magicShelfBookService) {
        this.bookRepository = bookRepository;
        this.candidateRepository = candidateRepository;
        this.scanTargetLoader = scanTargetLoader;
        this.epubComicAnalyzer = epubComicAnalyzer;
        this.pdfComicAnalyzer = pdfComicAnalyzer;
        this.metadataHeuristics = metadataHeuristics;
        this.ollamaClassifier = ollamaClassifier;
        this.appSettingService = appSettingService;
        this.notificationService = notificationService;
        this.transactionManager = transactionManager;
        this.authenticationService = authenticationService;
        this.cancellationManager = cancellationManager;
        this.magicShelfBookService = magicShelfBookService;
    }

    public void detect(ComicDetectionRequest request, String taskId) {
        BookLoreUser user = authenticationService.getAuthenticatedUser();
        Long userId = user != null ? user.getId() : null;

        ComicDetectionSettings settings = resolveSettings();
        boolean recheck = request.getRecheckExisting() != null
                ? request.getRecheckExisting()
                : settings.isRecheckExisting();

        try {
            Set<Long> bookIds = resolveBookIds(request, userId);
            if (bookIds.isEmpty()) {
                sendProgress(taskId, 0, 0, "No books found for the given scope.",
                        MetadataFetchTaskStatus.COMPLETED);
                return;
            }

            List<Long> ordered = new ArrayList<>(bookIds);
            int total = ordered.size();
            log.info("ComicDetect [{}]: scanning {} books in chunks of {}", taskId, total, CHUNK_SIZE);

            TransactionTemplate tx = new TransactionTemplate(transactionManager);
            int completed = 0;
            int marked = 0;
            int queued = 0;
            int skipped = 0;
            boolean cancelled = false;

            for (int offset = 0; offset < total && !cancelled; offset += CHUNK_SIZE) {
                List<Long> chunkIds = ordered.subList(offset, Math.min(offset + CHUNK_SIZE, total));
                List<ComicScanTarget> targets = scanTargetLoader.load(new LinkedHashSet<>(chunkIds));

                for (ComicScanTarget target : targets) {
                    if (cancellationManager.isTaskCancelled(taskId)) {
                        cancelled = true;
                        break;
                    }

                    completed++;
                    String displayTitle = target.displayTitle();

                    if (target.alreadyComic() && !recheck) {
                        skipped++;
                        continue;
                    }

                    try {
                        ComicScoreCard card = score(target, settings);
                        int scoreValue = card.score();
                        ComicVerdict verdict = verdictFor(scoreValue, settings);

                        if (request.isDryRun()) {
                            if (verdict != ComicVerdict.NOT_COMIC) {
                                log.info("ComicDetect [{}]: DRY RUN '{}' score={} verdict={} | {}",
                                        taskId, displayTitle, scoreValue, verdict,
                                        card.reasons().replace('\n', ';'));
                            }
                            sendProgress(taskId, completed, total,
                                    verdict + " (" + scoreValue + "): " + displayTitle,
                                    MetadataFetchTaskStatus.IN_PROGRESS);
                            continue;
                        }

                        switch (verdict) {
                            case COMIC -> {
                                persistComicFlag(tx, target.bookId(), card, scoreValue);
                                marked++;
                                log.info("ComicDetect [{}]: marked '{}' as comic (score {}) | {}",
                                        taskId, displayTitle, scoreValue,
                                        card.reasons().replace('\n', ';'));
                                sendProgress(taskId, completed, total,
                                        "Marked as comic (" + scoreValue + "): " + displayTitle,
                                        MetadataFetchTaskStatus.IN_PROGRESS);
                            }
                            case BORDERLINE -> {
                                persistCandidate(tx, target.bookId(), card, scoreValue);
                                queued++;
                                log.info("ComicDetect [{}]: queued '{}' for review (score {}) | {}",
                                        taskId, displayTitle, scoreValue,
                                        card.reasons().replace('\n', ';'));
                                sendProgress(taskId, completed, total,
                                        "Queued for review (" + scoreValue + "): " + displayTitle,
                                        MetadataFetchTaskStatus.IN_PROGRESS);
                            }
                            case NOT_COMIC -> sendProgress(taskId, completed, total,
                                    "Not a comic (" + scoreValue + "): " + displayTitle,
                                    MetadataFetchTaskStatus.IN_PROGRESS);
                        }

                    } catch (Exception e) {
                        log.warn("ComicDetect: error scanning '{}': {}", displayTitle, e.getMessage());
                        sendProgress(taskId, completed, total,
                                "Error: " + displayTitle + " — " + e.getMessage(),
                                MetadataFetchTaskStatus.IN_PROGRESS);
                    }
                }
            }

            String summary = String.format(
                    "Scan complete. Marked %d, queued %d for review, skipped %d already flagged, scanned %d of %d.",
                    marked, queued, skipped, completed, total);

            if (cancelled) {
                sendProgress(taskId, completed, total, "Scan cancelled. " + summary,
                        MetadataFetchTaskStatus.CANCELLED);
            } else {
                sendProgress(taskId, total, total, summary, MetadataFetchTaskStatus.COMPLETED);
            }
            log.info("ComicDetect [{}]: {}", taskId, summary);

        } catch (Exception e) {
            log.error("ComicDetect [{}]: fatal error: {}", taskId, e.getMessage(), e);
            sendProgress(taskId, 0, 0, "Fatal error: " + e.getMessage(),
                    MetadataFetchTaskStatus.ERROR);
        }
    }

    // ── scoring ───────────────────────────────────────────────────────────────

    private ComicScoreCard score(ComicScanTarget target, ComicDetectionSettings settings) {
        ComicScoreCard card = new ComicScoreCard();
        BookFileType type = target.fileType();

        if (BookFileType.CBX == type) {
            card.add("CBX_EXTENSION", 100, "File is a comic archive (CBZ/CBR/CB7)");
            return card;
        }

        if (settings.isStructuralAnalysis() && target.filePath() != null) {
            File file = Path.of(target.filePath()).toFile();
            if (file.exists()) {
                if (BookFileType.EPUB == type) {
                    epubComicAnalyzer.analyze(file, card);
                } else if (BookFileType.PDF == type) {
                    pdfComicAnalyzer.analyze(file, card);
                }
            }
        }

        if (card.isConclusive()) {
            return card;
        }

        if (settings.isMetadataHeuristics()) {
            metadataHeuristics.analyze(target, card);
        }

        // Only spend an LLM call when the cheap evidence left it ambiguous.
        if (settings.isLlmTiebreaker()) {
            int interim = card.score();
            if (interim >= settings.getReviewThreshold() && interim < settings.getAutoMarkThreshold()) {
                ollamaClassifier.analyze(target, card);
            }
        }

        return card;
    }

    private ComicVerdict verdictFor(int score, ComicDetectionSettings settings) {
        if (score >= settings.getAutoMarkThreshold()) return ComicVerdict.COMIC;
        if (score >= settings.getReviewThreshold()) return ComicVerdict.BORDERLINE;
        return ComicVerdict.NOT_COMIC;
    }

    // ── persistence ───────────────────────────────────────────────────────────

    private void persistComicFlag(TransactionTemplate tx, Long bookId, ComicScoreCard card, int score) {
        tx.executeWithoutResult(status -> {
            bookRepository.findById(bookId).ifPresent(entity -> {
                entity.setIsComic(Boolean.TRUE);
                bookRepository.save(entity);
            });
            upsertCandidate(bookId, card, score, ComicVerdict.COMIC, ComicCandidateStatus.ACCEPTED);
        });
    }

    private void persistCandidate(TransactionTemplate tx, Long bookId, ComicScoreCard card, int score) {
        tx.executeWithoutResult(status ->
                upsertCandidate(bookId, card, score, ComicVerdict.BORDERLINE, ComicCandidateStatus.PENDING));
    }

    private void upsertCandidate(Long bookId, ComicScoreCard card, int score,
                                 ComicVerdict verdict, ComicCandidateStatus status) {
        ComicDetectionCandidateEntity entity = candidateRepository.findByBookId(bookId)
                .orElseGet(() -> ComicDetectionCandidateEntity.builder().bookId(bookId).build());

        // Never resurrect something the user has already dismissed.
        if (entity.getId() != null && entity.getStatus() == ComicCandidateStatus.REJECTED) {
            return;
        }

        entity.setScore(score);
        entity.setVerdict(verdict);
        entity.setStatus(status);
        entity.setSignals(card.reasons());
        entity.setDetectedAt(Instant.now());
        if (status == ComicCandidateStatus.ACCEPTED) {
            entity.setResolvedAt(Instant.now());
        }
        candidateRepository.save(entity);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private ComicDetectionSettings resolveSettings() {
        try {
            ComicDetectionSettings settings = appSettingService.getAppSettings().getComicDetectionSettings();
            return settings != null ? settings : ComicDetectionSettings.builder().build();
        } catch (Exception e) {
            log.debug("ComicDetect: falling back to default settings: {}", e.getMessage());
            return ComicDetectionSettings.builder().build();
        }
    }

    private Set<Long> resolveBookIds(ComicDetectionRequest request, Long userId) {
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

    private void sendProgress(String taskId, int current, int total, String message,
                              MetadataFetchTaskStatus status) {
        notificationService.sendMessage(
                Topic.BOOK_METADATA_BATCH_PROGRESS,
                new MetadataBatchProgressNotification(
                        taskId, current, total, message, status.name(), false));
    }
}
