package org.booklore.service.book;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.config.security.service.AuthenticationService;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.MetadataBatchProgressNotification;
import org.booklore.model.dto.request.MissingFileScanRequest;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.model.entity.TagEntity;
import org.booklore.model.enums.MetadataFetchTaskStatus;
import org.booklore.model.websocket.Topic;
import org.booklore.repository.BookRepository;
import org.booklore.repository.TagRepository;
import org.booklore.service.NotificationService;
import org.booklore.service.book.MissingFileScanLoader.FileCheckTarget;
import org.booklore.service.book.MissingFileScanLoader.FileRef;
import org.booklore.service.opds.MagicShelfBookService;
import org.booklore.task.TaskCancellationManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

/**
 * Tags books whose file is no longer on disk, so they can be gathered with a
 * Magic Shelf rule on Tags.
 *
 * <p>Existence checks run in parallel and each is bounded by a timeout. Both
 * matter on network storage: a single {@code Files.exists()} against the CIFS
 * share here takes roughly two seconds, so a serial pass over a six-figure
 * library would run for days, and a wedged SMB path blocks a stat call in
 * uninterruptible IO, stalling the whole scan.
 *
 * <p>Checking threads work from plain values produced by
 * {@link MissingFileScanLoader}: no entities, no session and no database
 * connection is held while waiting on storage.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MissingFileScanService {

    public static final String DEFAULT_TAG_NAME = "File Not Found";

    private static final int CHUNK_SIZE = 500;
    private static final int MAX_PARALLELISM = 128;

    private final BookRepository bookRepository;
    private final TagRepository tagRepository;
    private final MissingFileScanLoader scanLoader;
    private final NotificationService notificationService;
    private final PlatformTransactionManager transactionManager;
    private final AuthenticationService authenticationService;
    private final TaskCancellationManager cancellationManager;
    private final MagicShelfBookService magicShelfBookService;

    /** Outcome of checking one book's files. */
    private record CheckResult(Long bookId, String title, List<String> absent, boolean timedOut,
                               boolean alreadyTagged) {
        boolean isMissing() {
            return !absent.isEmpty();
        }
    }

    public void scan(MissingFileScanRequest request, String taskId) {
        BookLoreUser user = authenticationService.getAuthenticatedUser();
        Long userId = user != null ? user.getId() : null;

        String tagName = request.getTagName() == null || request.getTagName().isBlank()
                ? DEFAULT_TAG_NAME
                : request.getTagName().trim();

        int parallelism = Math.max(1, Math.min(MAX_PARALLELISM, request.getParallelism()));
        int timeoutSeconds = Math.max(1, request.getCheckTimeoutSeconds());

        try {
            Set<Long> bookIds = resolveBookIds(request, userId);
            if (bookIds.isEmpty()) {
                sendProgress(taskId, 0, 0, "No books found for the given scope.",
                        MetadataFetchTaskStatus.COMPLETED);
                return;
            }

            List<Long> ordered = new ArrayList<>(bookIds);
            int total = ordered.size();
            log.info("MissingFileScan [{}]: checking {} books, tag '{}', parallelism {}, timeout {}s{}",
                    taskId, total, tagName, parallelism, timeoutSeconds,
                    request.isDryRun() ? " (dry run)" : "");

            TransactionTemplate tx = new TransactionTemplate(transactionManager);
            TagEntity tag = request.isDryRun() ? null : resolveTag(tx, tagName);

            int completed = 0;
            int missing = 0;
            int cleared = 0;
            int unresolved = 0;
            boolean cancelled = false;
            long startedAt = System.currentTimeMillis();

            Semaphore permits = new Semaphore(parallelism);

            try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
                for (int offset = 0; offset < total && !cancelled; offset += CHUNK_SIZE) {
                    List<Long> chunk = ordered.subList(offset, Math.min(offset + CHUNK_SIZE, total));
                    List<FileCheckTarget> targets = scanLoader.load(new LinkedHashSet<>(chunk), tagName);

                    List<CheckResult> results = checkInParallel(targets, pool, permits, timeoutSeconds);
                    completed += results.size();

                    for (CheckResult r : results) {
                        if (r.timedOut()) unresolved++;
                    }

                    if (!request.isDryRun()) {
                        int[] applied = applyChunk(tx, results, tag, tagName, request.isClearTagWhenPresent());
                        missing += applied[0];
                        cleared += applied[1];
                    } else {
                        for (CheckResult r : results) {
                            if (r.isMissing()) missing++;
                        }
                    }

                    long elapsed = Math.max(1, (System.currentTimeMillis() - startedAt) / 1000);
                    sendProgress(taskId, completed, total,
                            String.format("Checked %d of %d — %d missing, %d unresolved (%d books/sec)",
                                    completed, total, missing, unresolved, completed / elapsed),
                            MetadataFetchTaskStatus.IN_PROGRESS);

                    if (cancellationManager.isTaskCancelled(taskId)) cancelled = true;
                }
            }

            String summary = String.format(
                    "%sFile check complete. %d missing (tagged '%s'), %d restored, %d unresolved, %d checked in %ds.",
                    request.isDryRun() ? "DRY RUN — " : "", missing, tagName, cleared, unresolved,
                    completed, (System.currentTimeMillis() - startedAt) / 1000);

            sendProgress(taskId, cancelled ? completed : total, total,
                    cancelled ? "Scan cancelled. " + summary : summary,
                    cancelled ? MetadataFetchTaskStatus.CANCELLED : MetadataFetchTaskStatus.COMPLETED);
            log.info("MissingFileScan [{}]: {}", taskId, summary);

        } catch (Exception e) {
            log.error("MissingFileScan [{}]: fatal error: {}", taskId, e.getMessage(), e);
            sendProgress(taskId, 0, 0, "Fatal error: " + e.getMessage(),
                    MetadataFetchTaskStatus.ERROR);
        }
    }

    // ── parallel checking ─────────────────────────────────────────────────────

    private List<CheckResult> checkInParallel(List<FileCheckTarget> targets,
                                              ExecutorService pool,
                                              Semaphore permits,
                                              int timeoutSeconds) {
        Map<Long, Future<List<String>>> futures = new LinkedHashMap<>();

        for (FileCheckTarget target : targets) {
            futures.put(target.bookId(), pool.submit(() -> {
                permits.acquire();
                try {
                    return absentFiles(target);
                } finally {
                    permits.release();
                }
            }));
        }

        List<CheckResult> results = new ArrayList<>(targets.size());
        for (FileCheckTarget target : targets) {
            Future<List<String>> future = futures.get(target.bookId());
            try {
                List<String> absent = future.get(timeoutSeconds, TimeUnit.SECONDS);
                results.add(new CheckResult(target.bookId(), target.title(), absent, false,
                        target.alreadyTagged()));
            } catch (TimeoutException e) {
                // Storage is not answering for this book. Cancel the attempt and
                // treat it as unknown rather than missing — tagging on a timeout
                // would mark healthy books when the share is merely slow.
                future.cancel(true);
                log.warn("MissingFileScan: timed out checking '{}' after {}s — left unchanged",
                        target.title(), timeoutSeconds);
                results.add(new CheckResult(target.bookId(), target.title(), List.of(), true,
                        target.alreadyTagged()));
            } catch (Exception e) {
                future.cancel(true);
                log.warn("MissingFileScan: error checking '{}': {}", target.title(), e.getMessage());
                results.add(new CheckResult(target.bookId(), target.title(), List.of(), true,
                        target.alreadyTagged()));
            }
        }
        return results;
    }

    private List<String> absentFiles(FileCheckTarget target) {
        List<String> absent = new ArrayList<>();
        for (FileRef file : target.files()) {
            Path path = file.path();
            if (path == null) {
                absent.add(file.name());
                continue;
            }
            try {
                if (!Files.exists(path)) absent.add(file.name());
            } catch (Exception e) {
                absent.add(file.name());
            }
        }
        return absent;
    }

    // ── persistence ───────────────────────────────────────────────────────────

    /** Returns {newlyMissing, cleared}. */
    private int[] applyChunk(TransactionTemplate tx, List<CheckResult> results,
                             TagEntity tag, String tagName, boolean clearWhenPresent) {
        List<CheckResult> toTag = results.stream()
                .filter(r -> !r.timedOut() && r.isMissing() && !r.alreadyTagged())
                .toList();
        List<CheckResult> toClear = clearWhenPresent
                ? results.stream().filter(r -> !r.timedOut() && !r.isMissing() && r.alreadyTagged()).toList()
                : List.of();

        int stillMissing = (int) results.stream().filter(r -> !r.timedOut() && r.isMissing()).count();
        if (toTag.isEmpty() && toClear.isEmpty()) {
            return new int[]{stillMissing, 0};
        }

        Integer clearedCount = tx.execute(status -> {
            for (CheckResult r : toTag) {
                bookRepository.findById(r.bookId()).ifPresent(book -> {
                    BookMetadataEntity meta = book.getMetadata();
                    if (meta == null) return;
                    if (meta.getTags() == null) meta.setTags(new HashSet<>());
                    meta.getTags().add(tag);
                    bookRepository.save(book);
                    log.info("MissingFileScan: '{}' missing {} -> tagged", r.title(), r.absent());
                });
            }
            int c = 0;
            for (CheckResult r : toClear) {
                Optional<BookEntity> found = bookRepository.findById(r.bookId());
                if (found.isEmpty()) continue;
                BookEntity book = found.get();
                BookMetadataEntity meta = book.getMetadata();
                if (meta == null || meta.getTags() == null) continue;
                if (meta.getTags().removeIf(t -> t.getName() != null
                        && t.getName().equalsIgnoreCase(tagName))) {
                    bookRepository.save(book);
                    log.info("MissingFileScan: '{}' file present again -> tag removed", r.title());
                    c++;
                }
            }
            return c;
        });

        return new int[]{stillMissing, clearedCount == null ? 0 : clearedCount};
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private TagEntity resolveTag(TransactionTemplate tx, String tagName) {
        return tx.execute(status -> tagRepository.findByNameIgnoreCase(tagName)
                .orElseGet(() -> tagRepository.save(TagEntity.builder().name(tagName).build())));
    }

    private Set<Long> resolveBookIds(MissingFileScanRequest request, Long userId) {
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
