package org.booklore.service.book;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.config.security.service.AuthenticationService;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.MetadataBatchProgressNotification;
import org.booklore.model.dto.request.MissingFileScanRequest;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.model.entity.TagEntity;
import org.booklore.model.enums.MetadataFetchTaskStatus;
import org.booklore.model.websocket.Topic;
import org.booklore.repository.BookRepository;
import org.booklore.repository.TagRepository;
import org.booklore.service.NotificationService;
import org.booklore.service.opds.MagicShelfBookService;
import org.booklore.task.TaskCancellationManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Tags books whose file is no longer on disk, so they can be gathered with a
 * Magic Shelf rule on Tags.
 *
 * <p>Checks every {@link BookFileEntity} attached to a book, not just the
 * primary: a book whose EPUB is present but whose audiobook has vanished is
 * still partially broken and worth surfacing.
 *
 * <p>Runs in chunks and re-reads each chunk inside a short transaction, because
 * the tag collection is LAZY and the file checks are slow enough on network
 * storage that holding a session open across them would be wasteful.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MissingFileScanService {

    public static final String DEFAULT_TAG_NAME = "File Not Found";

    private static final int CHUNK_SIZE = 250;

    private final BookRepository bookRepository;
    private final TagRepository tagRepository;
    private final NotificationService notificationService;
    private final PlatformTransactionManager transactionManager;
    private final AuthenticationService authenticationService;
    private final TaskCancellationManager cancellationManager;
    private final MagicShelfBookService magicShelfBookService;

    public void scan(MissingFileScanRequest request, String taskId) {
        BookLoreUser user = authenticationService.getAuthenticatedUser();
        Long userId = user != null ? user.getId() : null;

        String tagName = request.getTagName() == null || request.getTagName().isBlank()
                ? DEFAULT_TAG_NAME
                : request.getTagName().trim();

        try {
            Set<Long> bookIds = resolveBookIds(request, userId);
            if (bookIds.isEmpty()) {
                sendProgress(taskId, 0, 0, "No books found for the given scope.",
                        MetadataFetchTaskStatus.COMPLETED);
                return;
            }

            List<Long> ordered = new ArrayList<>(bookIds);
            int total = ordered.size();
            log.info("MissingFileScan [{}]: checking {} books, tag '{}'{}",
                    taskId, total, tagName, request.isDryRun() ? " (dry run)" : "");

            TransactionTemplate tx = new TransactionTemplate(transactionManager);
            TagEntity tag = request.isDryRun() ? null : resolveTag(tx, tagName);

            int completed = 0;
            int missing = 0;
            int cleared = 0;
            boolean cancelled = false;

            for (int offset = 0; offset < total && !cancelled; offset += CHUNK_SIZE) {
                List<Long> chunk = ordered.subList(offset, Math.min(offset + CHUNK_SIZE, total));
                final int chunkStart = completed;
                final TagEntity finalTag = tag;

                int[] counts = tx.execute(status -> {
                    int localMissing = 0;
                    int localCleared = 0;
                    int localDone = 0;

                    for (BookEntity book : bookRepository.findAllWithMetadataByIds(new LinkedHashSet<>(chunk))) {
                        localDone++;
                        List<String> absent = missingPaths(book);
                        boolean fileMissing = !absent.isEmpty();
                        BookMetadataEntity meta = book.getMetadata();
                        if (meta == null) continue;

                        boolean tagged = hasTag(meta, tagName);

                        if (fileMissing && !tagged) {
                            if (!request.isDryRun()) {
                                if (meta.getTags() == null) meta.setTags(new HashSet<>());
                                meta.getTags().add(finalTag);
                                bookRepository.save(book);
                            }
                            localMissing++;
                            log.info("MissingFileScan [{}]: '{}' missing {} -> tagged",
                                    taskId, bookTitle(book), absent);
                        } else if (fileMissing) {
                            localMissing++;
                        } else if (tagged && request.isClearTagWhenPresent()) {
                            if (!request.isDryRun()) {
                                meta.getTags().removeIf(t -> t.getName() != null
                                        && t.getName().equalsIgnoreCase(tagName));
                                bookRepository.save(book);
                            }
                            localCleared++;
                            log.info("MissingFileScan [{}]: '{}' file present again -> tag removed",
                                    taskId, bookTitle(book));
                        }
                    }
                    return new int[]{localDone, localMissing, localCleared};
                });

                if (counts != null) {
                    completed += counts[0];
                    missing += counts[1];
                    cleared += counts[2];
                }

                sendProgress(taskId, completed, total,
                        String.format("Checked %d of %d — %d missing", completed, total, missing),
                        MetadataFetchTaskStatus.IN_PROGRESS);

                if (cancellationManager.isTaskCancelled(taskId)) {
                    cancelled = true;
                }
                if (completed == chunkStart) break; // defensive: nothing loaded, avoid spinning
            }

            String summary = String.format(
                    "%sFile check complete. %d missing (tagged '%s'), %d restored, %d checked.",
                    request.isDryRun() ? "DRY RUN — " : "", missing, tagName, cleared, completed);

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

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Filenames of every attached file that is not currently readable on disk. */
    private List<String> missingPaths(BookEntity book) {
        List<String> absent = new ArrayList<>();
        if (book.getBookFiles() == null) return absent;

        for (BookFileEntity file : book.getBookFiles()) {
            try {
                Path path = file.getFullFilePath();
                if (path == null || !Files.exists(path)) {
                    absent.add(file.getFileName() != null ? file.getFileName() : "(unnamed)");
                }
            } catch (Exception e) {
                // An unresolvable path is as good as a missing file for this purpose.
                absent.add(file.getFileName() != null ? file.getFileName() : "(unresolvable)");
            }
        }
        return absent;
    }

    private boolean hasTag(BookMetadataEntity meta, String tagName) {
        return meta.getTags() != null && meta.getTags().stream()
                .anyMatch(t -> t.getName() != null && t.getName().equalsIgnoreCase(tagName));
    }

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
