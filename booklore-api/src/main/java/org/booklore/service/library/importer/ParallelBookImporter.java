package org.booklore.service.library.importer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.booklore.exception.ApiError;
import org.booklore.model.FileProcessResult;
import org.booklore.model.dto.Book;
import org.booklore.model.dto.settings.LibraryFile;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.repository.BookAdditionalFileRepository;
import org.booklore.repository.BookRepository;
import org.booklore.repository.LibraryRepository;
import org.booklore.service.event.BookEventBroadcaster;
import org.booklore.service.fileprocessor.BookFileProcessor;
import org.booklore.service.fileprocessor.BookFileProcessorRegistry;
import org.booklore.service.fileprocessor.PreparableBookFileProcessor;
import org.booklore.service.fileprocessor.prepare.AttachmentRequest;
import org.booklore.service.fileprocessor.prepare.CoverMaker;
import org.booklore.service.fileprocessor.prepare.FileFacts;
import org.booklore.service.fileprocessor.prepare.FolderSnapshot;
import org.booklore.service.fileprocessor.prepare.HeapReservation;
import org.booklore.service.fileprocessor.prepare.PersistedBook;
import org.booklore.service.fileprocessor.prepare.PrepareAbandonedException;
import org.booklore.service.fileprocessor.prepare.PrepareContext;
import org.booklore.service.fileprocessor.prepare.PrepareDeadlineException;
import org.booklore.service.fileprocessor.prepare.PrepareRequest;
import org.booklore.service.fileprocessor.prepare.PreparedAttachment;
import org.booklore.service.fileprocessor.prepare.PreparedBookFile;
import org.booklore.service.kobo.KoboAutoShelfService;
import org.booklore.service.library.importer.Outcome.SkipReason;
import org.booklore.service.metadata.extractor.AudiobookMetadataExtractor;
import org.booklore.service.metadata.extractor.FileMetadataExtractor;
import org.booklore.service.metadata.extractor.MetadataExtractorFactory;
import org.booklore.service.metadata.sidecar.SidecarMetadataWriter;
import org.booklore.util.BookCoverUtils;
import org.booklore.util.FileService;
import org.booklore.util.cover.CoverKind;
import org.booklore.util.cover.CropPolicy;
import org.booklore.util.cover.RenderedCover;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.File;
import java.io.IOException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Imports the new book groups of a library scan, reading many files at once and saving them one at a time.
 *
 * <p>The scan's thread plans each group (which file is the primary, which processor reads it) and hands its files to
 * the {@link ImportExecutor}, whose platform threads read them: hash, size, metadata and rendered cover, with no
 * entity, repository or session. It keeps up to {@code app.scan.import-window} groups in flight and saves them
 * strictly in the order they came, each as soon as its files are read, so book ids, find-or-create order and the
 * "Processing file" log stay as they were. All database work, and writing the cover files, happens on the scan's
 * thread: the book in a transaction of its own, then the add event, the Kobo shelves, and each additional format in a
 * transaction of its own. The scan's thread does not touch the library's storage, unless a processor cannot prepare
 * files and reads its file while saving it, as all of them did before.
 *
 * <p>A group whose primary file fails, or does not finish reading within its deadline, is skipped and stays new for
 * the next scan, and so is one whose additional format does not finish reading in time; one whose additional format
 * cannot be read is saved without it. When storage
 * wedges (see {@link ImportExecutor#breakerOpen()}), or the database cannot be reached to save
 * {@value #MAX_CONSECUTIVE_SAVE_FAILURES} books in a row, the scan stops planning, saves what has been read and
 * returns; what it did not get to is new again on the next scan. The returned {@link ImportSummary} counts all of this.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ParallelBookImporter {

    /** How often the scan's thread, while it waits for a file, checks for shutdown and logs progress. */
    private static final long POLL_SECONDS = 1;
    private static final long PROGRESS_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(30);
    private static final int MAX_CONSECUTIVE_SAVE_FAILURES = 20;
    /** Heap reserved around extractCover of formats whose extractor renders or decodes the cover itself. */
    private static final long EXTRACTOR_COVER_BYTES = 128L * 1024 * 1024;

    private final ImportExecutor executor;
    private final BookFileProcessorRegistry processorRegistry;
    private final PlatformTransactionManager transactionManager;
    private final LibraryRepository libraryRepository;
    private final BookRepository bookRepository;
    private final BookAdditionalFileRepository bookAdditionalFileRepository;
    private final BookEventBroadcaster bookEventBroadcaster;
    private final KoboAutoShelfService koboAutoShelfService;
    private final FileService fileService;
    private final SidecarMetadataWriter sidecarMetadataWriter;
    private final MetadataExtractorFactory metadataExtractorFactory;
    private final AudiobookMetadataExtractor audiobookMetadataExtractor;

    /**
     * Held from saving a group's book to attaching its last format, by every scan in the process: scans of different
     * libraries run at once, and their find-or-create of authors, categories and tags would race on the unique names.
     * Fair, so they take turns. Never held while waiting for a file to be read.
     */
    private final ReentrantLock persistGate = new ReentrantLock(true);

    public ImportSummary importGroups(Map<String, List<LibraryFile>> groups, LibraryEntity libraryEntity) {
        LibraryEntity library = ensureManaged(libraryEntity);
        // One snapshot per scan: the settings are not read on import threads
        Scan scan = new Scan(library, groups.size(), fileService.currentCropPolicy());
        Iterator<List<LibraryFile>> pending = groups.values().iterator();
        int window = executor.settings().window();
        try {
            if (executor.breakerOpen()) {
                scan.stopForStuckStorage();
            }
            while (true) {
                while (scan.accepting && scan.window.size() < window && pending.hasNext()) {
                    if (executor.isShuttingDown()) {
                        scan.stopForShutdown();
                    } else if (executor.breakerOpen()) {
                        scan.stopForStuckStorage();
                    } else {
                        planAndSubmit(pending.next(), scan);
                    }
                }
                GroupInFlight head = scan.window.pollFirst();
                if (head == null) {
                    break;
                }
                persist(head, scan);
                scan.done++;
                if (scan.consecutiveSaveFailures >= MAX_CONSECUTIVE_SAVE_FAILURES) {
                    scan.stoppedDatabaseErrors = true;
                    break;
                }
                logProgress(scan);
            }
        } catch (InterruptedException e) {
            scan.interruptedThread();
        }
        return finish(scan);
    }

    private LibraryEntity ensureManaged(LibraryEntity entity) {
        if (entity.getId() == null) return entity;
        return libraryRepository.findById(entity.getId())
                .orElseThrow(() -> ApiError.LIBRARY_NOT_FOUND.createException(entity.getId()));
    }

    private void planAndSubmit(List<LibraryFile> files, Scan scan) {
        scan.planned++;
        files.forEach(lf -> lf.setLibraryEntity(scan.library));
        GroupPlan plan;
        try {
            plan = plan(files, scan);
        } catch (Exception e) {
            log.error("Failed to process file group {}: {}", names(files), e.getMessage());
            scan.failed++;
            scan.done++;
            return;
        }
        if (plan == null) {
            scan.done++;
            return;
        }
        try {
            scan.window.addLast(submit(plan));
        } catch (RejectedExecutionException e) {
            scan.notStarted++;
            scan.stopForShutdown();
        }
    }

    /** The group's plan, or null when it has nothing to import (logged as before). */
    private GroupPlan plan(List<LibraryFile> files, Scan scan) {
        Optional<LibraryFile> primaryFile = ImportGroups.findBestPrimaryFile(files, scan.library);
        if (primaryFile.isEmpty()) {
            log.warn("No suitable book file found in group");
            return null;
        }
        LibraryFile primary = primaryFile.get();
        BookFileProcessor processor = processorRegistry.getProcessorOrThrow(primary.getBookFileType());
        PrepareRequest request = processor instanceof PreparableBookFileProcessor ? PrepareRequest.of(primary, scan.crop) : null;

        List<LibraryFile> additional = files.stream()
                .filter(f -> !f.equals(primary))
                .toList();
        // Decided as for a book whose primary file is this one, which it is once saved; checked again when attaching
        List<AttachmentRequest> attachmentRequests = additional.stream()
                .map(f -> AttachmentRequest.of(f, ImportGroups.crossTypeCover(primary.getBookFileType(), f.getBookFileType()), scan.crop))
                .toList();
        return new GroupPlan(names(files), primary, processor, request, additional, attachmentRequests);
    }

    /**
     * Hands the group's files to the executor. The work captures only the processor, a stateless singleton, and the
     * plain request, never an entity or the LibraryFile.
     */
    private GroupInFlight submit(GroupPlan plan) {
        GroupInFlight group = new GroupInFlight(plan);
        try {
            if (plan.request() != null) {
                PreparableBookFileProcessor processor = (PreparableBookFileProcessor) plan.processor();
                PrepareRequest request = plan.request();
                group.primary = executor.submit(label(plan.primary()), ctx -> processor.prepare(request, ctx), () -> false);
                group.primary.result().thenAccept(outcome -> {
                    if (!(outcome instanceof Outcome.Done)) {
                        group.doomed.set(true);
                    }
                });
            }
            for (int i = 0; i < plan.additional().size(); i++) {
                AttachmentRequest request = plan.attachmentRequests().get(i);
                group.attachments.add(executor.submit(label(plan.additional().get(i)),
                        ctx -> prepareAttachment(request, ctx), group.doomed::get));
            }
        } catch (RejectedExecutionException e) {
            group.cancel();
            throw e;
        }
        return group;
    }

    /** Saves the group at the head of the window once its files are read: its book, then each additional format. */
    private void persist(GroupInFlight group, Scan scan) throws InterruptedException {
        GroupPlan plan = group.plan;
        PreparedBookFile prepared = null;
        List<Outcome<PreparedAttachment>> attachments = new ArrayList<>();
        try {
            if (group.primary != null) {
                Outcome<PreparedBookFile> outcome = await(group.primary, scan);
                if (!(outcome instanceof Outcome.Done<PreparedBookFile> done)) {
                    group.cancel();
                    skipGroup(plan, outcome, scan);
                    return;
                }
                scan.fileRead(done.elapsedNanos());
                prepared = done.value();
            }
            for (int i = 0; i < group.attachments.size(); i++) {
                Outcome<PreparedAttachment> outcome = await(group.attachments.get(i), scan);
                if (outcome instanceof Outcome.Done<PreparedAttachment> done) {
                    scan.fileRead(done.elapsedNanos());
                } else if (mayBeReadNextTime(outcome)) {
                    group.cancel();
                    skipGroupForAdditionalFormat(plan, plan.additional().get(i), outcome, scan);
                    return;
                }
                attachments.add(outcome);
            }
            log.info("Processing file: {}", plan.primary().getFileName());
            persistGate.lockInterruptibly();
        } catch (InterruptedException e) {
            // Nothing of the group is saved, so it is new again on the next scan
            group.cancel();
            scan.notStarted++;
            throw e;
        }

        PersistedBook persisted;
        try {
            persisted = saveBook(plan, prepared, scan);
            if (persisted != null) {
                announce(persisted);
                attachAll(plan, persisted, attachments, scan);
            }
        } finally {
            persistGate.unlock();
        }
        if (persisted != null && persisted.sidecarJob() != null) {
            writeSidecar(persisted, scan);
        }
    }

    private void skipGroup(GroupPlan plan, Outcome<PreparedBookFile> outcome, Scan scan) {
        switch (outcome) {
            case Outcome.TimedOut<PreparedBookFile> timedOut -> {
                log.warn("Skipped {}: reading from storage did not finish within {}s; it will be retried on the next scan ({} import threads stuck)",
                        timedOut.label(), timeoutSeconds(), executor.stuckThreads());
                scan.timedOut++;
            }
            case Outcome.Failed<PreparedBookFile> failed when failed.cause() instanceof PrepareDeadlineException -> {
                log.warn("Skipped {}: no cover-rendering capacity within {}s; it will be retried on the next scan",
                        failed.label(), timeoutSeconds());
                scan.timedOut++;
            }
            case Outcome.Failed<PreparedBookFile> failed -> {
                log.error("Failed to process file group {}: {}", plan.names(), messageOf(failed.cause()));
                scan.failed++;
            }
            case Outcome.Skipped<PreparedBookFile> skipped -> notStarted(skipped.reason(), scan);
            case Outcome.Done<PreparedBookFile> done -> throw new IllegalArgumentException("Group " + plan.names() + " was read");
        }
    }

    /**
     * Skips a group whose primary file was read but one of whose additional formats was not, for want of time or
     * capacity. Saved without it, the format would come back on the next scan as a new file that is not always attached
     * to the book: one at the root of a library path, or one a full scan finds, becomes a book of its own. Skipped whole,
     * the group stays new and is read together again. A format that cannot be read at all is left out instead, so it
     * does not keep its book out for good.
     */
    private void skipGroupForAdditionalFormat(GroupPlan plan, LibraryFile file, Outcome<PreparedAttachment> outcome, Scan scan) {
        if (outcome instanceof Outcome.Skipped<PreparedAttachment> skipped) {
            notStarted(skipped.reason(), scan);
            return;
        }
        log.warn("Skipped {}: its additional format {} was not read ({}); it will be retried on the next scan ({} import threads stuck)",
                plan.names(), file.getFileName(), describe(outcome), executor.stuckThreads());
        scan.timedOut++;
    }

    /** Whether a file was not read for want of time or capacity, rather than because it cannot be read. */
    private static boolean mayBeReadNextTime(Outcome<?> outcome) {
        return outcome instanceof Outcome.TimedOut
                || outcome instanceof Outcome.Skipped
                || outcome instanceof Outcome.Failed<?> failed && failed.cause() instanceof PrepareDeadlineException;
    }

    private static void notStarted(SkipReason reason, Scan scan) {
        scan.notStarted++;
        if (reason == SkipReason.STORAGE_STUCK) {
            scan.stopForStuckStorage();
        } else if (reason == SkipReason.SHUTDOWN) {
            scan.stopForShutdown();
        }
    }

    /** Saves the group's book in a transaction of its own. Null when it was not saved, which is logged. */
    private PersistedBook saveBook(GroupPlan plan, PreparedBookFile prepared, Scan scan) {
        PersistedBook persisted;
        try {
            persisted = requiresNew().execute(status -> {
                // Hibernate's association management adds a new book to its library's lazily loaded books. The scan's
                // library was loaded outside any session, where that collection can be neither loaded nor added to,
                // so the book gets the library as this transaction's session has it.
                plan.primary().setLibraryEntity(ensureManaged(scan.library));
                return prepared != null
                        ? ((PreparableBookFileProcessor) plan.processor()).persistPrepared(plan.primary(), prepared)
                        : processInline(plan);
            });
        } catch (Exception e) {
            // Rolled back; a new book's cover files went with it
            log.error("Failed to process file group {}: {}", plan.names(), e.getMessage());
            scan.failed++;
            if (databaseUnreachable(e)) {
                scan.consecutiveSaveFailures++;
            }
            return null;
        } finally {
            // The scan's caller keeps its files until the scan ends, and with them the library each one was saved with:
            // a copy, with its paths, per book
            plan.primary().setLibraryEntity(scan.library);
        }
        if (persisted == null) {
            scan.failed++;
            return null;
        }
        scan.consecutiveSaveFailures = 0;
        scan.added++;
        return persisted;
    }

    /**
     * Whether saving failed because the database could not be reached or did not answer in time, rather than because of
     * the book. Only such failures, in a row, stop the import: a row the database rejects, such as for a value too long
     * for its column, is rejected again on every scan, and would stop every scan at the same files.
     */
    private static boolean databaseUnreachable(Throwable e) {
        return ExceptionUtils.getThrowableList(e).stream().anyMatch(cause -> cause instanceof CannotCreateTransactionException
                || cause instanceof DataAccessResourceFailureException
                || cause instanceof TransientDataAccessException
                || cause instanceof RecoverableDataAccessException
                || cause instanceof SQLTransientException
                || cause instanceof SQLRecoverableException
                || cause instanceof SQLNonTransientConnectionException);
    }

    /**
     * A processor that cannot prepare files reads and saves the primary file in a transaction of its own, inside the
     * caller's, as the sequential loop has it do.
     */
    private PersistedBook processInline(GroupPlan plan) {
        FileProcessResult result = plan.processor().processFile(plan.primary());
        if (result == null || result.getBook() == null) {
            log.warn("Failed to process primary file: {}", plan.primary().getFileName());
            return null;
        }
        Book book = result.getBook();
        return new PersistedBook(book, book.getId(), false, plan.primary().getFileName(), plan.primary().getBookFileType(), null);
    }

    /** Tells clients and Kobo shelves about the new book. A failure of either leaves the rest of the group alone. */
    private void announce(PersistedBook persisted) {
        try {
            bookEventBroadcaster.broadcastBookAddEvent(persisted.book());
        } catch (Exception e) {
            log.error("Failed to broadcast new book {}: {}", persisted.primaryFileName(), e.getMessage());
        }
        try {
            koboAutoShelfService.autoAddBookToKoboShelves(persisted.bookId());
        } catch (Exception e) {
            log.error("Failed to add book {} to Kobo shelves: {}", persisted.primaryFileName(), e.getMessage());
        }
    }

    private void attachAll(GroupPlan plan, PersistedBook persisted, List<Outcome<PreparedAttachment>> attachments, Scan scan) {
        for (int i = 0; i < plan.additional().size(); i++) {
            LibraryFile file = plan.additional().get(i);
            if (!(attachments.get(i) instanceof Outcome.Done<PreparedAttachment> done)) {
                log.warn("Skipped additional format {}: {}; it will be attached by the next rescan",
                        file.getFileName(), describe(attachments.get(i)));
                scan.attachmentsSkipped++;
                continue;
            }
            try {
                requiresNew().executeWithoutResult(status -> attach(persisted, file, done.value()));
            } catch (Exception e) {
                log.error("Error creating additional file {}: {}", file.getFileName(), e.getMessage());
                scan.attachmentsSkipped++;
            }
        }
    }

    /** Attaches one additional format to the saved book, and writes the cover it made for a book of the other kind. */
    private void attach(PersistedBook persisted, LibraryFile file, PreparedAttachment prepared) {
        Optional<BookFileEntity> existing = bookAdditionalFileRepository
                .findByLibraryPath_IdAndFileSubPathAndFileName(
                        file.getLibraryPathEntity().getId(), file.getFileSubPath(), file.getFileName());
        if (existing.isPresent()) {
            log.debug("Additional file already exists: {}", file.getFileName());
            return;
        }

        BookFileEntity additionalFile = BookFileEntity.builder()
                .book(bookRepository.getReferenceById(persisted.bookId()))
                .fileName(file.getFileName())
                .fileSubPath(file.getFileSubPath())
                .isBookFormat(true)
                .folderBased(file.isFolderBased())
                .bookType(file.getBookFileType())
                .fileSizeKb(prepared.facts().sizeKb())
                .initialHash(prepared.facts().hash())
                .currentHash(prepared.facts().hash())
                .addedOn(Instant.now())
                .build();
        bookAdditionalFileRepository.save(additionalFile);
        log.info("Attached additional format {} to book: {}", file.getFileName(), persisted.primaryFileName());

        RenderedCover cover = prepared.crossTypeCover();
        if (cover == null || cover.kind() != ImportGroups.crossTypeCover(persisted.primaryType(), file.getBookFileType())) {
            return;
        }
        boolean audiobook = cover.kind() == CoverKind.AUDIOBOOK;
        try {
            if (!fileService.writeRenderedCover(persisted.bookId(), cover)) {
                return;
            }
        } catch (IOException e) {
            log.warn("Error generating {} cover from {}: {}", audiobook ? "audiobook" : "ebook", file.getFileName(), e.getMessage());
            return;
        }
        BookEntity book = bookRepository.findById(persisted.bookId()).orElseThrow();
        if (audiobook) {
            book.getMetadata().setAudiobookCoverUpdatedOn(Instant.now());
            book.setAudiobookCoverHash(BookCoverUtils.generateCoverHash());
            bookRepository.save(book);
            log.info("Generated audiobook cover from additional file: {}", file.getFileName());
        } else {
            FileService.setBookCoverPath(book.getMetadata());
            book.setBookCoverHash(BookCoverUtils.generateCoverHash());
            bookRepository.save(book);
            log.info("Generated ebook cover from additional file: {}", file.getFileName());
        }
    }

    /**
     * Reads an additional format on an import thread: its hash and size, and the cover it makes for a book of the
     * other kind, if any. A cover that cannot be made is logged and left out; the format is still attached.
     */
    PreparedAttachment prepareAttachment(AttachmentRequest request, PrepareContext ctx) throws Exception {
        FolderSnapshot folder = request.folderBased() ? FolderSnapshot.scan(request.path()) : null;
        FileFacts facts = folder != null ? FileFacts.of(folder) : FileFacts.read(request.path());
        ctx.checkpoint();

        RenderedCover cover = null;
        if (request.crossTypeCover() != null) {
            try {
                cover = request.crossTypeCover() == CoverKind.AUDIOBOOK
                        ? audiobookCover(request, folder, ctx)
                        : ebookCover(request, ctx);
            } catch (PrepareAbandonedException | PrepareDeadlineException e) {
                throw e;
            } catch (Exception e) {
                log.warn("Failed to generate cover from additional file {}: {}", request.fileName(), e.getMessage());
            }
        }
        return new PreparedAttachment(request, facts, cover);
    }

    private RenderedCover audiobookCover(AttachmentRequest request, FolderSnapshot folder, PrepareContext ctx) throws Exception {
        // FileFacts has shown the file, or the folder's first audio file, to be there
        File audioFile = folder != null ? folder.audioFiles().getFirst().toFile() : request.path().toFile();
        byte[] coverData = audiobookMetadataExtractor.extractCover(audioFile);
        if (coverData == null) {
            log.debug("No cover image found in audiobook '{}'", request.fileName());
            return null;
        }
        // Decoded as FileService.readImage decoded it
        RenderedCover cover = new CoverMaker(ctx, CoverKind.AUDIOBOOK, CropPolicy.NONE).fromEncoded(coverData, true);
        if (cover == null) {
            log.warn("Failed to decode cover image for audiobook '{}'", request.fileName());
        }
        return cover;
    }

    private RenderedCover ebookCover(AttachmentRequest request, PrepareContext ctx) throws Exception {
        FileMetadataExtractor extractor = metadataExtractorFactory.getExtractor(request.type());
        if (extractor == null) {
            log.debug("No extractor available for file type: {}", request.type());
            return null;
        }
        File file = request.path().toFile();
        byte[] coverData;
        if (request.type() == BookFileType.PDF || request.type() == BookFileType.CBX) {
            // Their extractors render or decode an image to find the cover
            try (HeapReservation ignored = ctx.reserveHeap(EXTRACTOR_COVER_BYTES)) {
                coverData = extractor.extractCover(file);
            }
        } else {
            coverData = extractor.extractCover(file);
        }
        if (coverData == null) {
            log.debug("No cover image found in ebook '{}'", request.fileName());
            return null;
        }
        // Decoded as FileService.readImage decoded it
        RenderedCover cover = new CoverMaker(ctx, CoverKind.BOOK, request.crop()).fromEncoded(coverData, true);
        if (cover == null) {
            log.warn("Failed to decode cover image for ebook '{}'", request.fileName());
        }
        return cover;
    }

    /**
     * Writes the book's sidecar on an import thread, after its transaction committed. Up to a window of them are in
     * flight per scan; the import waits for them all before it returns.
     */
    private void writeSidecar(PersistedBook persisted, Scan scan) throws InterruptedException {
        if (scan.sidecars.size() >= executor.settings().window()) {
            awaitSidecar(scan.sidecars.removeFirst(), scan);
        }
        SidecarMetadataWriter.SidecarJob job = persisted.sidecarJob();
        try {
            FileTask<Void> task = executor.submit("sidecar " + job.sidecarPath(), ctx -> {
                sidecarMetadataWriter.execute(job);
                return null;
            }, () -> false);
            scan.sidecars.addLast(new SidecarWrite(persisted.bookId(), task));
        } catch (RejectedExecutionException e) {
            log.warn("Failed to write sidecar metadata for book ID {}: {}", persisted.bookId(), e.getMessage());
        }
    }

    private void awaitSidecar(SidecarWrite write, Scan scan) throws InterruptedException {
        Outcome<Void> outcome = await(write.task(), scan);
        if (!(outcome instanceof Outcome.Done)) {
            log.warn("Failed to write sidecar metadata for book ID {}: {}", write.bookId(), describe(outcome));
        }
    }

    /** Waits for a task, logging progress meanwhile. The executor completes every task, so this returns. */
    private <T> Outcome<T> await(FileTask<T> task, Scan scan) throws InterruptedException {
        while (true) {
            try {
                return task.result().get(POLL_SECONDS, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                if (executor.isShuttingDown()) {
                    // Shutdown ends every task, but this one may not have been reached yet
                    return new Outcome.Skipped<>(task.label(), SkipReason.SHUTDOWN);
                }
                logProgress(scan);
            } catch (ExecutionException e) {
                // Results are only ever completed normally
                return new Outcome.Failed<>(task.label(), e.getCause());
            }
        }
    }

    private ImportSummary finish(Scan scan) {
        // What is still in flight is not saved and stays new for the next scan. Files already being read finish or
        // time out by themselves; their results are dropped.
        scan.window.forEach(GroupInFlight::cancel);
        scan.notStarted += scan.window.size() + (scan.groups - scan.planned);
        scan.window.clear();
        if (!scan.interrupted) {
            try {
                while (!scan.sidecars.isEmpty()) {
                    awaitSidecar(scan.sidecars.removeFirst(), scan);
                }
            } catch (InterruptedException e) {
                scan.interruptedThread();
            }
        }

        String name = scan.library.getName();
        if (scan.stoppedStorageStuck) {
            log.error("Storage for library '{}' is not responding: {} import threads are stuck; {} new books were not started "
                            + "and will be picked up by the next scan. Check the share, or restart Booklore if it stays stuck.",
                    name, executor.stuckThreads(), scan.notStarted);
        } else if (scan.stoppedDatabaseErrors) {
            log.error("Stopped importing into library '{}' after {} books in a row could not be saved because the database "
                    + "could not be reached; {} new books were not started and will be picked up by the next scan.",
                    name, MAX_CONSECUTIVE_SAVE_FAILURES, scan.notStarted);
        }
        Duration elapsed = Duration.ofNanos(System.nanoTime() - scan.startNanos);
        long slowestMillis = TimeUnit.NANOSECONDS.toMillis(scan.slowestNanos);
        log.info("Finished processing library '{}': {} added, {} failed, {} timed out, {} not started, {} additional formats skipped in {}s; slowest file {} ms",
                name, scan.added, scan.failed, scan.timedOut, scan.notStarted, scan.attachmentsSkipped, elapsed.toSeconds(), slowestMillis);
        log.info("Finished processing library '{}'", name);
        if (scan.threadInterrupted) {
            Thread.currentThread().interrupt();
        }
        return new ImportSummary(scan.groups, scan.added, scan.failed, scan.timedOut, scan.notStarted, scan.attachmentsSkipped,
                scan.stoppedStorageStuck, scan.stoppedDatabaseErrors, scan.interrupted, slowestMillis, elapsed, false);
    }

    private void logProgress(Scan scan) {
        long now = System.nanoTime();
        if (now - scan.lastProgressNanos < PROGRESS_INTERVAL_NANOS) {
            return;
        }
        scan.lastProgressNanos = now;
        double minutes = (now - scan.startNanos) / 60e9;
        log.info("Importing into '{}': {}/{} groups done ({} added, {} failed, {} timed out), {} in flight, {} stuck import threads, {} books/min",
                scan.library.getName(), scan.done, scan.groups, scan.added, scan.failed, scan.timedOut, scan.window.size(),
                executor.stuckThreads(), Math.round(scan.added / minutes));
    }

    private TransactionTemplate requiresNew() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }

    private long timeoutSeconds() {
        return executor.settings().fileTimeout().toSeconds();
    }

    /** Why a file was not read, for the log line that says it was skipped. */
    private String describe(Outcome<?> outcome) {
        return switch (outcome) {
            case Outcome.TimedOut<?> timedOut -> "reading from storage did not finish within " + timeoutSeconds() + "s";
            case Outcome.Failed<?> failed when failed.cause() instanceof PrepareDeadlineException ->
                    "no cover-rendering capacity within " + timeoutSeconds() + "s";
            case Outcome.Failed<?> failed -> messageOf(failed.cause());
            case Outcome.Skipped<?> skipped -> "it was not started (" + skipped.reason() + ")";
            case Outcome.Done<?> done -> "done";
        };
    }

    private static String messageOf(Throwable e) {
        return e.getMessage() != null ? e.getMessage() : e.toString();
    }

    private static String label(LibraryFile file) {
        return file.getFullPath().toString();
    }

    private static String names(List<LibraryFile> files) {
        return files.stream().map(LibraryFile::getFileName).toList().toString();
    }

    private record SidecarWrite(long bookId, FileTask<Void> task) {
    }

    /** One import's state. The importer itself is shared by every scan in the process. */
    private static final class Scan {
        final LibraryEntity library;
        final int groups;
        final CropPolicy crop;
        final long startNanos = System.nanoTime();
        long lastProgressNanos = startNanos;
        /** Planned groups not saved or skipped yet, in the order they are saved. */
        final Deque<GroupInFlight> window = new ArrayDeque<>();
        final Deque<SidecarWrite> sidecars = new ArrayDeque<>();
        /** Whether more groups are planned; false once the import is stopping. */
        boolean accepting = true;
        int planned;
        int done;
        int added;
        int failed;
        int timedOut;
        int notStarted;
        int attachmentsSkipped;
        /** Books in a row that could not be saved because the database could not be reached. */
        int consecutiveSaveFailures;
        boolean stoppedStorageStuck;
        boolean stoppedDatabaseErrors;
        /** The import stopped early: its thread was interrupted, or the executor is shutting down. */
        boolean interrupted;
        /** Its thread was interrupted; the interrupt is restored when the import returns. */
        boolean threadInterrupted;
        long slowestNanos;

        Scan(LibraryEntity library, int groups, CropPolicy crop) {
            this.library = library;
            this.groups = groups;
            this.crop = crop;
        }

        void fileRead(long elapsedNanos) {
            slowestNanos = Math.max(slowestNanos, elapsedNanos);
        }

        void stopForStuckStorage() {
            accepting = false;
            stoppedStorageStuck = true;
        }

        void stopForShutdown() {
            accepting = false;
            interrupted = true;
        }

        void interruptedThread() {
            stopForShutdown();
            threadInterrupted = true;
        }
    }
}
