package org.booklore.service.watcher;

import org.booklore.exception.ApiError;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.entity.LibraryPathEntity;
import org.booklore.model.dto.settings.LibraryFile;
import org.booklore.model.enums.BookFileExtension;
import org.booklore.model.enums.BookFileType;
import org.booklore.model.enums.LibraryOrganizationMode;
import org.booklore.repository.BookRepository;
import org.booklore.repository.LibraryRepository;
import org.booklore.service.file.FileFingerprint;
import org.booklore.service.library.LibraryProcessingService;
import org.booklore.util.FileUtils;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

@Slf4j
@Service
@RequiredArgsConstructor
public class LibraryFileEventProcessor {

    private static final long DEBOUNCE_MS = 500L;
    private static final long FOLDER_CREATE_DEBOUNCE_MS = 5000L;
    private static final long PENDING_DELETION_GRACE_MS = 8000L;
    private static final long STABILITY_CHECK_INTERVAL_MS = 3000L;
    private static final long STABILITY_MAX_WAIT_MS = 120000L;
    private static final int MIN_AUDIO_FILES_FOR_FOLDER_AUDIOBOOK = 2;
    /**
     * How long a new file with the content of a stored file that is still there waits for that file to go before it
     * is taken for a copy: a mover that writes the destination before it removes the source removes it within this
     * once it stops writing. The wait counts from the last file seen arriving or growing in the library.
     */
    static final long COPY_SOURCE_WAIT_MS = 30000L;
    /**
     * The longest such a file waits while files keep arriving in its library. A move of a large folder across devices
     * copies the whole tree before it removes any of it, and the watcher takes each finished folder of the tree as it
     * comes.
     */
    static final long COPY_SOURCE_MAX_WAIT_MS = 600000L;

    private final BlockingQueue<FileEvent> eventQueue = new LinkedBlockingQueue<>();
    private final LibraryRepository libraryRepository;
    private final BookRepository bookRepository;
    private final BookFileTransactionalHandler bookFileTransactionalHandler;
    private final BookFilePersistenceService bookFilePersistenceService;
    private final LibraryProcessingService libraryProcessingService;
    private final PendingDeletionPool pendingDeletionPool;

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private final ConcurrentMap<Path, ScheduledFuture<?>> pendingDeletes = new ConcurrentHashMap<>();
    private final ConcurrentMap<Path, ScheduledFuture<?>> pendingFolderCreates = new ConcurrentHashMap<>();
    /** New files whose stability check has not found them written in full yet. */
    private final Set<Path> settlingFiles = ConcurrentHashMap.newKeySet();
    /**
     * What copies a stored file still there, by path, until that file goes or the wait is over: a new file, a folder
     * audiobook, or the new files of a BOOK_PER_FOLDER folder, which wait as one. Changed under {@link #copyLock}, so a
     * timer and a deletion cannot both hand one back, and a timer that fires as it is replaced sees the replacement.
     */
    private final ConcurrentMap<Path, PendingCopy> pendingCopies = new ConcurrentHashMap<>();
    private final Object copyLock = new Object();
    /** Numbers each waiting copy's timer, so a timer can tell whether the copy still waits on it. Under copyLock. */
    private long copyTimers;
    /** When each library last saw a file arrive, or one still being written, on {@link #clock}. */
    private final ConcurrentMap<Long, Long> lastWriteSeen = new ConcurrentHashMap<>();
    private long copySourceWaitMs = COPY_SOURCE_WAIT_MS;
    private long copySourceMaxWaitMs = COPY_SOURCE_MAX_WAIT_MS;
    /**
     * Now, in milliseconds, for timing the waits: the clock the scheduler counts its delays on. The wall clock can be
     * stepped back or ahead, by an NTP correction or a VM resumed, and a wait timed on it then ends too soon or never.
     */
    private LongSupplier clock = () -> TimeUnit.NANOSECONDS.toMillis(System.nanoTime());

    /**
     * @param hashes  the contents it copies; a deletion of one of them hands it back at once
     * @param folder  whether it is a folder, handed back as a folder create
     * @param since   when it began to wait
     * @param timerId the number of the timer it waits on
     */
    private record PendingCopy(long libraryId, Set<String> hashes, boolean folder, long since, long timerId,
                               ScheduledFuture<?> recheck) {
    }

    /** For tests, which cannot wait the full time. */
    void setCopySourceWaitMs(long copySourceWaitMs) {
        this.copySourceWaitMs = copySourceWaitMs;
    }

    /** For tests, which cannot wait the full time. */
    void setCopySourceMaxWaitMs(long copySourceMaxWaitMs) {
        this.copySourceMaxWaitMs = copySourceMaxWaitMs;
    }

    /** For tests, which step the clock. */
    void setClock(LongSupplier clock) {
        this.clock = clock;
    }

    /** Whether the file or folder at this path waits as a copy of a stored file. For tests. */
    boolean waitsAsCopy(Path path) {
        return pendingCopies.containsKey(path.toAbsolutePath().normalize());
    }

    @PostConstruct
    public void init() {
        // A platform thread, as handling an event reads library storage: on JDK 25 a virtual thread blocked in a
        // file call keeps its carrier, so a wedged network share would stall every virtual thread in the application.
        Thread.ofPlatform().daemon(true).name("library-file-events").start(() -> {
            log.info("LibraryFileEventProcessor thread started.");
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    handleEvent(eventQueue.take());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("LibraryFileEventProcessor thread interrupted.");
                } catch (Exception e) {
                    log.error("Error while processing file event", e);
                }
            }
        });
    }

    public void processEvent(WatchEvent.Kind<?> eventKind, long libraryId, Path fullPath, boolean isDirectory) {
        Path path = fullPath.toAbsolutePath().normalize();

        if (eventKind == StandardWatchEventKinds.ENTRY_DELETE) {
            ScheduledFuture<?> existing = pendingDeletes.put(path, scheduler.schedule(() -> {
                eventQueue.offer(new FileEvent(eventKind, libraryId, path, isDirectory));
                pendingDeletes.remove(path);
            }, DEBOUNCE_MS, TimeUnit.MILLISECONDS));

            if (existing != null) existing.cancel(false);
        } else if (eventKind == StandardWatchEventKinds.ENTRY_CREATE) {
            noteWrite(libraryId);
            ScheduledFuture<?> pendingDelete = pendingDeletes.remove(path);
            if (pendingDelete != null) {
                pendingDelete.cancel(false);
                log.debug("[DEBOUNCE] CREATE after pending DELETE for '{}', treating as file modification", path);
            }

            if (isDirectory) {
                log.debug("[DEBOUNCE] Scheduling folder create for '{}' with {}ms delay", path, FOLDER_CREATE_DEBOUNCE_MS);
                scheduleFolderCreate(libraryId, path, FOLDER_CREATE_DEBOUNCE_MS, Wait.NONE, 0L);
            } else {
                boolean insidePendingFolder = false;
                for (var entry : pendingFolderCreates.entrySet()) {
                    if (path.startsWith(entry.getKey())) {
                        insidePendingFolder = true;
                        Path folderPath = entry.getKey();
                        scheduleFolderCreate(libraryId, folderPath, FOLDER_CREATE_DEBOUNCE_MS, Wait.NONE, 0L);
                        log.debug("[DEBOUNCE] File '{}' tracked, reset folder debounce for '{}'", path.getFileName(), folderPath);
                        break;
                    }
                }
                if (!insidePendingFolder) {
                    scheduleWithStabilityCheck(libraryId, path, () ->
                            eventQueue.offer(new FileEvent(eventKind, libraryId, path, false)));
                }
            }
        } else {
            eventQueue.offer(new FileEvent(eventKind, libraryId, path, false));
        }
    }

    /**
     * Queues a folder's create once the delay is over, unless something puts it off again first. It is queued before it
     * leaves the pending folders, so a drain always finds it in one or the other, and it leaves them only as itself, so
     * a create that puts the folder off again while it is handled stays pending.
     */
    private void scheduleFolderCreate(long libraryId, Path folderPath, long delayMs, Wait waited, long since) {
        AtomicReference<ScheduledFuture<?>> timer = new AtomicReference<>();
        timer.set(scheduler.schedule(() -> {
            eventQueue.offer(new FileEvent(StandardWatchEventKinds.ENTRY_CREATE, libraryId, folderPath, true, waited, since));
            pendingFolderCreates.remove(folderPath, timer.get());
        }, delayMs, TimeUnit.MILLISECONDS));
        ScheduledFuture<?> previous = pendingFolderCreates.put(folderPath, timer.get());
        if (previous != null) {
            previous.cancel(false);
        }
    }

    /** A file arrived in the library, or one is still being written: a copy into it may still be going on. */
    private void noteWrite(long libraryId) {
        lastWriteSeen.put(libraryId, clock.getAsLong());
    }

    private void handleEvent(FileEvent event) {
        Path path = event.fullPath();
        String fileName = path.getFileName().toString();
        log.info("[PROCESS] '{}' event for '{}'{}", event.eventKind().name(), fileName,
                event.isDirectory() ? " (directory)" : "");

        LibraryEntity library = libraryRepository.findById(event.libraryId())
                .orElseThrow(() -> ApiError.LIBRARY_NOT_FOUND.createException(event.libraryId()));

        if (library.getLibraryPaths().stream().noneMatch(lp -> path.startsWith(lp.getPath()))) {
            log.warn("[SKIP] Path outside of library: '{}'", path);
            return;
        }

        boolean isDirectory = event.isDirectory();

        if (isDirectory) {
            switch (event.eventKind().name()) {
                case "ENTRY_CREATE" -> handleFolderCreate(library, path, event);
                case "ENTRY_DELETE" -> handleFolderDelete(library, path);
                default -> log.warn("[SKIP] Folder event '{}' ignored for '{}'", event.eventKind().name(), fileName);
            }
            return;
        }

        if (!isBookFile(fileName)) {
            log.debug("[SKIP] Ignored non-book file '{}'", fileName);
            return;
        }

        switch (event.eventKind().name()) {
            case "ENTRY_CREATE" -> handleFileCreate(library, path, event);
            case "ENTRY_DELETE" -> handleFileDelete(library, path);
            default -> log.debug("[SKIP] File event '{}' ignored for '{}'", event.eventKind().name(), fileName);
        }
    }

    private void handleFileCreate(LibraryEntity library, Path path, FileEvent event) {
        if (!fileHasContent(path)) {
            log.debug("[SKIP] Zero-byte file: '{}'", path);
            return;
        }
        log.info("[FILE_CREATE] '{}'", path);
        handleNewFile(library.getId(), path, event.waited(), event.since());
    }

    /**
     * A new file, on its own or found in a new folder. One with the content of a stored file that is still at its path
     * is a copy only if that file stays: it waits until that file's deletion comes in, which makes it the file moved,
     * or until the wait is over.
     *
     * @param waited how the file comes back from waiting as a copy, if it does
     * @param since  when it began that wait
     */
    private void handleNewFile(long libraryId, Path path, Wait waited, long since) {
        if (waited == Wait.COPY_OVER) {
            bookFileTransactionalHandler.handleNewBookFile(libraryId, path);
            return;
        }
        bookFileTransactionalHandler.handleNewBookFileUnlessCopy(libraryId, path).ifPresent(copy ->
                waitAsCopy(libraryId, path, Set.of(copy.hash()), false, waitingSince(waited, since),
                        "copies a file of book id=" + copy.bookId()));
    }

    /** When something began to wait as a copy: now, unless it comes back from that wait. */
    private long waitingSince(Wait waited, long since) {
        return waited == Wait.COPY ? since : clock.getAsLong();
    }

    /**
     * Leaves something new that copies a stored file still there to wait. It is handed back at once when a deletion
     * of one of its contents comes in, which may make it the file moved; otherwise when its library has seen no file
     * arrive or grow for the wait, or after the longest wait, as a copy.
     */
    private void waitAsCopy(long libraryId, Path path, Set<String> hashes, boolean folder, long since, String what) {
        synchronized (copyLock) {
            PendingCopy previous = pendingCopies.get(path);
            long from = previous != null ? Math.min(previous.since(), since) : since;
            if (previous != null) {
                previous.recheck().cancel(false);
            }
            long delay = Math.max(0L, from + copySourceWaitMs - clock.getAsLong());
            pendingCopies.put(path, armed(path, libraryId, hashes, folder, from, delay));
        }
        log.info("[FILE_CREATE] '{}' {}; taken for a copy unless that file goes within {}ms", path, what, copySourceWaitMs);
    }

    /** A waiting copy with a timer of its own, which ends the wait unless the copy is handed back or waits anew first. */
    private PendingCopy armed(Path path, long libraryId, Set<String> hashes, boolean folder, long since, long delayMs) {
        long timerId = ++copyTimers;
        return new PendingCopy(libraryId, Set.copyOf(hashes), folder, since, timerId,
                scheduler.schedule(() -> copyWaitEnds(path, timerId), delayMs, TimeUnit.MILLISECONDS));
    }

    /**
     * A waiting copy's wait is over, unless files kept arriving or growing in its library: a mover that copies a
     * large tree removes the source only once it is all written, which can take longer than the wait. A timer that
     * finds the wait not over, as one would after the clock was stepped, waits again for the rest.
     */
    private void copyWaitEnds(Path path, long timerId) {
        synchronized (copyLock) {
            PendingCopy pending = pendingCopies.get(path);
            if (pending == null || pending.timerId() != timerId) {
                // Handed back already, or waiting anew on a timer of its own
                return;
            }
            long now = clock.getAsLong();
            long quietSince = Math.max(pending.since(), lastWriteSeen.getOrDefault(pending.libraryId(), pending.since()));
            long over = Math.min(quietSince + copySourceWaitMs, pending.since() + copySourceMaxWaitMs);
            if (now < over) {
                pendingCopies.put(path, armed(path, pending.libraryId(), pending.hashes(), pending.folder(),
                        pending.since(), over - now));
                log.debug("[FILE_CREATE] '{}' waits on as a copy: files are still arriving in its library", path);
                return;
            }
            handBack(path, Wait.COPY_OVER);
        }
    }

    /** Hands a waiting copy back to the queue, once. It is queued before it stops waiting, for a drain to find. */
    private void handBack(Path path, Wait waited) {
        synchronized (copyLock) {
            PendingCopy pending = pendingCopies.get(path);
            if (pending != null) {
                pending.recheck().cancel(false);
                eventQueue.offer(new FileEvent(StandardWatchEventKinds.ENTRY_CREATE, pending.libraryId(), path,
                        pending.folder(), waited, pending.since()));
                pendingCopies.remove(path);
            }
        }
    }

    /**
     * Hands back at once what waits as a copy of one of these contents, whose deletion just came in: the pool now holds
     * the book it may have been moved from. What is still a copy then waits on, as the deletions of the other files a
     * mover copied may be about to follow.
     */
    private void handBackCopiesOf(Collection<String> hashes) {
        pendingCopies.forEach((path, pending) -> {
            if (!Collections.disjoint(pending.hashes(), hashes)) {
                noteWrite(pending.libraryId());
                handBack(path, Wait.COPY);
            }
        });
    }

    private void handleFileDelete(LibraryEntity library, Path path) {
        log.info("[FILE_DELETE] '{}'", path);
        try {
            String libPath = bookFilePersistenceService.findMatchingLibraryPath(library, path);
            LibraryPathEntity libPathEntity = bookFilePersistenceService.getLibraryPathEntityForFile(library, libPath);

            Path relPath = Paths.get(libPathEntity.getPath()).relativize(path);
            String fileName = relPath.getFileName().toString();
            String fileSubPath = Optional.ofNullable(relPath.getParent()).map(Path::toString).orElse("");

            bookFilePersistenceService.findBookFileByLibraryPathSubPathAndFileName(libPathEntity.getId(), fileSubPath, fileName)
                    .ifPresentOrElse(bookFile -> {
                        var book = bookFile.getBook();
                        ScheduledFuture<?> timer = scheduler.schedule(
                                () -> pendingDeletionPool.expireFileDeletion(path),
                                PENDING_DELETION_GRACE_MS, TimeUnit.MILLISECONDS);
                        pendingDeletionPool.addFileDeletion(path, library.getId(), bookFile, book, timer);
                        log.info("[PENDING_DELETE] Book '{}' deferred deletion (grace period {}ms)", fileName, PENDING_DELETION_GRACE_MS);
                        // A new file waiting as its copy is where it was moved: the pool now hands it the book
                        if (bookFile.getCurrentHash() != null) {
                            handBackCopiesOf(Set.of(bookFile.getCurrentHash()));
                        }
                    }, () -> log.debug("[NOT_FOUND] BookFile for deleted path '{}' not found (likely renamed/moved)", path));

        } catch (Exception e) {
            log.warn("[ERROR] While handling file delete '{}': {}", path, e.getMessage());
        }
    }

    /**
     * A new folder, or one that comes back from waiting. Each of its book files is handled as a new file is, as soon as
     * it is written: one still being written goes through the stability check of a single new file, so the files
     * already written are not held back for it. A mover that removes each source as soon as it copied it leaves the
     * book in the pool only for the grace period, and a book held past it is deleted. What groups the folder's files
     * waits for the files it groups: a folder audiobook, and a BOOK_PER_FOLDER folder's new files.
     */
    private void handleFolderCreate(LibraryEntity library, Path folderPath, FileEvent event) {
        if (event.waited() == Wait.SETTLING) {
            log.debug("[FOLDER_CREATE] '{}' again, once its files are written", folderPath);
        } else {
            log.info("[FOLDER_CREATE] '{}'", folderPath);
        }

        pendingFolderCreates.entrySet().removeIf(entry -> {
            if (entry.getKey().startsWith(folderPath) && !entry.getKey().equals(folderPath)) {
                entry.getValue().cancel(false);
                log.debug("[DEBOUNCE] Cancelled child folder debounce for '{}'", entry.getKey());
                return true;
            }
            return false;
        });

        if (Files.exists(folderPath.resolve(".ignore"))) {
            log.debug("[SKIP] Folder has .ignore file: '{}'", folderPath);
            return;
        }

        var mode = library.getOrganizationMode() != null
                ? library.getOrganizationMode() : LibraryOrganizationMode.AUTO_DETECT;
        switch (mode) {
            case BOOK_PER_FILE -> handleEachFile(library.getId(), bookFilesUnder(folderPath));
            case BOOK_PER_FOLDER -> processFilesInFolderAsOneBook(library, folderPath, event);
            case AUTO_DETECT -> processFilesInFolderAutoDetected(library, folderPath, event);
        }
    }

    /**
     * AUTO_DETECT, grouped as a library scan groups files: a folder whose own book files are two audio files or more,
     * and not a series of single-file audiobooks, is a folder audiobook, at whatever depth of the new folder it is, as
     * in Author/Title/tracks; every other book file is a book of its own. A folder that can be a folder audiobook waits
     * while an audio file of its own is still being written, as what it is, and its hash, depend on all of them.
     */
    private void processFilesInFolderAutoDetected(LibraryEntity library, Path folderPath, FileEvent event) {
        Map<Path, List<Path>> filesByFolder = new LinkedHashMap<>();
        for (Path file : bookFilesUnder(folderPath)) {
            filesByFolder.computeIfAbsent(file.getParent(), parent -> new ArrayList<>()).add(file);
        }
        Set<Path> libraryPaths = new HashSet<>();
        library.getLibraryPaths().forEach(lp -> libraryPaths.add(Paths.get(lp.getPath()).toAbsolutePath().normalize()));

        filesByFolder.forEach((folder, files) -> {
            // A folder with a book file other than audio, or a library path's root: each file is a book of its own
            if (libraryPaths.contains(folder) || !files.stream().allMatch(LibraryFileEventProcessor::isAudioFile)) {
                handleEachFile(library.getId(), files);
                return;
            }
            // Only the event's own folder comes back from a wait
            Wait waited = folder.equals(folderPath) ? event.waited() : Wait.NONE;
            long since = folder.equals(folderPath) ? event.since() : 0L;
            if (putOffWhileAFileIsWritten(library.getId(), folder, files, waited, since)) {
                handleFilesMovedFromAPendingDeletion(library.getId(), files);
                return;
            }
            if (files.size() >= MIN_AUDIO_FILES_FOR_FOLDER_AUDIOBOOK && !FileUtils.isSeriesFolder(files)) {
                log.info("[FOLDER_AUDIOBOOK] Detected folder-based audiobook: {} ({} audio files)",
                        folder.getFileName(), files.size());
                try {
                    handleNewFolderAudiobook(library.getId(), folder, waited, since);
                } catch (Exception e) {
                    log.warn("[ERROR] Processing folder audiobook '{}': {}", folder, e.getMessage());
                }
            } else {
                handleEachFile(library.getId(), files);
            }
        });
    }

    /**
     * Hands each file on as a new file: at once if it is written, and after the stability check a single new file
     * goes through if it is still being written.
     */
    private void handleEachFile(long libraryId, List<Path> files) {
        for (Path file : files) {
            if (beingWritten(file)) {
                scheduleWithStabilityCheck(libraryId, file, () ->
                        eventQueue.offer(new FileEvent(StandardWatchEventKinds.ENTRY_CREATE, libraryId, file, false)));
            } else if (fileHasContent(file)) {
                try {
                    handleNewFile(libraryId, file, Wait.NONE, 0L);
                } catch (Exception e) {
                    log.warn("[ERROR] Processing file '{}': {}", file, e.getMessage());
                }
            }
        }
    }

    /**
     * Handles at once those written files, of a folder put off, with the content of a book file whose deletion the pool
     * holds: a book's single-file audiobook moved here by a mover that removes each source as it goes, which the
     * folder's wait would otherwise hold until that book was deleted.
     */
    private void handleFilesMovedFromAPendingDeletion(long libraryId, List<Path> files) {
        for (Path file : files) {
            if (beingWritten(file) || !fileHasContent(file)) {
                continue;
            }
            try {
                if (pendingDeletionPool.holds(FileFingerprint.generateHash(file))) {
                    handleNewFile(libraryId, file, Wait.NONE, 0L);
                }
            } catch (Exception e) {
                log.warn("[ERROR] Processing file '{}': {}", file, e.getMessage());
            }
        }
    }

    /**
     * Puts a folder's create off while one of these files, which the folder groups, is still being written, as when a
     * copy into the folder outlasts its debounce: a file half written would be grouped with a content no stored file
     * has. Waits as long as a single new file waits to settle; a folder that comes back from waiting as a copy is not
     * put off.
     *
     * @return whether the create was put off
     */
    private boolean putOffWhileAFileIsWritten(long libraryId, Path folderPath, List<Path> files, Wait waited, long since) {
        if (waited != Wait.NONE && waited != Wait.SETTLING) {
            return false;
        }
        List<Path> written = files.stream().filter(this::beingWritten).toList();
        if (written.isEmpty()) {
            return false;
        }
        long now = clock.getAsLong();
        long from = waited == Wait.SETTLING ? since : now;
        if (now - from >= STABILITY_MAX_WAIT_MS) {
            log.warn("[STABILITY] '{}' is still being written after {}ms; handling folder '{}' anyway",
                    written.getFirst(), STABILITY_MAX_WAIT_MS, folderPath);
            return false;
        }
        noteWrite(libraryId);
        if (waited == Wait.NONE) {
            log.info("[FOLDER_CREATE] '{}' is still being written; folder '{}' waits for it", written.getFirst().getFileName(), folderPath);
        } else {
            log.debug("[FOLDER_CREATE] '{}' is still being written; folder '{}' waits on", written.getFirst().getFileName(), folderPath);
        }
        scheduleFolderCreate(libraryId, folderPath, STABILITY_CHECK_INTERVAL_MS, Wait.SETTLING, from);
        return true;
    }

    /** The book files in the folder and below it, but for those in a folder marked to be ignored. */
    private List<Path> bookFilesUnder(Path folderPath) {
        try (var stream = Files.walk(folderPath)) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> !isUnderIgnoredDirectory(p, folderPath))
                    .filter(p -> isBookFile(p.getFileName().toString()))
                    .sorted()
                    .toList();
        } catch (IOException | UncheckedIOException e) {
            log.warn("[ERROR] Walking folder '{}': {}", folderPath, e.getMessage());
            return List.of();
        }
    }

    /** Whether a file was written to within the stability interval, as one still being copied is. */
    private boolean beingWritten(Path file) {
        try {
            return System.currentTimeMillis() - Files.getLastModifiedTime(file).toMillis() < STABILITY_CHECK_INTERVAL_MS;
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean isAudioFile(Path file) {
        return BookFileExtension.fromFileName(file.getFileName().toString())
                .filter(ext -> ext.getType() == BookFileType.AUDIOBOOK)
                .isPresent();
    }

    /** A folder audiobook, which waits as a new file does when a stored folder audiobook still there has its content. */
    private void handleNewFolderAudiobook(long libraryId, Path folderPath, Wait waited, long since) {
        if (waited == Wait.COPY_OVER) {
            bookFileTransactionalHandler.handleNewFolderAudiobook(libraryId, folderPath);
            return;
        }
        bookFileTransactionalHandler.handleNewFolderAudiobookUnlessCopy(libraryId, folderPath).ifPresent(copy ->
                waitAsCopy(libraryId, folderPath, Set.of(copy.hash()), true, waitingSince(waited, since),
                        "copies the folder audiobook of book id=" + copy.bookId()));
    }

    /**
     * BOOK_PER_FOLDER: the folder's new files as one book. The files written are matched at once, while the pool still
     * holds the books whose sources a mover removed as it went; the new files then wait for those still being written,
     * as they are one book. A file that a stored file, still there, has the content of makes all the new files wait, so
     * that a folder of copies of a book's files moves that book once the files they copy go, and a folder that is a
     * copy becomes one book after the wait, as it did at once before.
     */
    private void processFilesInFolderAsOneBook(LibraryEntity library, Path folderPath, FileEvent event) {
        List<Path> bookFiles = bookFilesUnder(folderPath).stream()
                // A file a book has at its path is no new file: it would be taken for a copy of itself, and a folder
                // that comes back from waiting, or from a wait for its files to be written, holds the files the pool
                // gave their books meanwhile
                .filter(p -> !bookFilePersistenceService.isStoredByALiveBook(library, p))
                .toList();
        if (bookFiles.isEmpty()) {
            return;
        }

        List<Path> written = bookFiles.stream().filter(p -> !beingWritten(p)).toList();
        Map<Path, String> unmatched = new LinkedHashMap<>(matchMovedFiles(library, folderPath, written));
        if (putOffWhileAFileIsWritten(library.getId(), folderPath, bookFiles, event.waited(), event.since())) {
            return;
        }
        // Those written since, and those still being written once the folder waited as long as a new file does
        unmatched.putAll(matchMovedFiles(library, folderPath, bookFiles.stream().filter(p -> !written.contains(p)).toList()));
        if (unmatched.isEmpty()) {
            return;
        }

        if (event.waited() != Wait.COPY_OVER) {
            Instant cutoff = Instant.now().minus(60, ChronoUnit.SECONDS);
            Map<String, Long> copied = new LinkedHashMap<>();
            unmatched.forEach((p, hash) -> {
                if (hash != null) {
                    bookFilePersistenceService.findBookCopied(hash, cutoff).ifPresent(bookId -> copied.put(hash, bookId));
                }
            });
            if (!copied.isEmpty()) {
                waitAsCopy(library.getId(), folderPath, copied.keySet(), true, waitingSince(event.waited(), event.since()),
                        "has " + unmatched.size() + " new files, of which " + copied.size() + " copy files of books "
                                + new TreeSet<>(copied.values()) + "; they wait together");
                return;
            }
        }

        String libraryPath = bookFilePersistenceService.findMatchingLibraryPath(library, folderPath);
        LibraryPathEntity libPathEntity = bookFilePersistenceService.getLibraryPathEntityForFile(library, libraryPath);

        List<LibraryFile> libraryFiles = new ArrayList<>();
        for (Path p : unmatched.keySet()) {
            String fileName = p.getFileName().toString();
            var ext = BookFileExtension.fromFileName(fileName);
            if (ext.isPresent()) {
                libraryFiles.add(LibraryFile.builder()
                        .libraryEntity(library)
                        .libraryPathEntity(libPathEntity)
                        .fileSubPath(FileUtils.getRelativeSubPath(libPathEntity.getPath(), p))
                        .fileName(fileName)
                        .bookFileType(ext.get().getType())
                        .build());
            }
        }

        if (!libraryFiles.isEmpty()) {
            libraryProcessingService.processLibraryFiles(libraryFiles, library);
        }
    }

    /**
     * Gives the books whose deletions the pool holds, or whose files were found moved, their files among these, and
     * returns the rest, with their hashes where they could be read: the folder's new files.
     */
    private Map<Path, String> matchMovedFiles(LibraryEntity library, Path folderPath, List<Path> files) {
        Map<Path, String> fileHashes = new LinkedHashMap<>();
        for (Path p : files) {
            if (!fileHasContent(p)) {
                continue;
            }
            try {
                fileHashes.put(p, FileFingerprint.generateHash(p));
            } catch (Exception e) {
                log.warn("[ERROR] Hash computation for '{}': {}", p, e.getMessage());
                fileHashes.put(p, null);
            }
        }
        if (fileHashes.isEmpty()) {
            return fileHashes;
        }

        // Try folder-level pool matching first
        Map<Path, String> hashed = new HashMap<>();
        fileHashes.forEach((p, hash) -> {
            if (hash != null) {
                hashed.put(p, hash);
            }
        });
        Optional<PendingDeletionPool.FolderMatchResult> folderMatch = pendingDeletionPool.matchFolderByHashes(hashed);
        if (folderMatch.isPresent()) {
            var match = folderMatch.get();
            String matchedLibPath = bookFilePersistenceService.findMatchingLibraryPath(library, folderPath);
            LibraryPathEntity matchedLibPathEntity = bookFilePersistenceService.getLibraryPathEntityForFile(library, matchedLibPath);

            for (var bookEntry : match.pending().affectedBooks().entrySet()) {
                var bookSnap = bookEntry.getValue();
                bookFilePersistenceService.recoverFolderBook(bookSnap, matchedLibPathEntity, folderPath, hashed);
            }
            log.info("[FOLDER_CREATE] Folder '{}' matched pending deletion, recovered {} books",
                    folderPath, match.pending().affectedBooks().size());
            return Map.of();
        }

        // Try hash-based move detection for each file
        Map<Path, String> unmatched = new LinkedHashMap<>();
        fileHashes.forEach((p, hash) -> {
            try {
                if (hash == null) {
                    unmatched.put(p, null);
                    return;
                }

                Optional<PendingDeletionPool.MatchResult> poolMatch = pendingDeletionPool.matchByHash(hash);
                if (poolMatch.isPresent()) {
                    var pmatch = poolMatch.get();
                    String libPath = bookFilePersistenceService.findMatchingLibraryPath(library, p);
                    LibraryPathEntity lpEntity = bookFilePersistenceService.getLibraryPathEntityForFile(library, libPath);
                    String subPath = FileUtils.getRelativeSubPath(lpEntity.getPath(), p);
                    pendingDeletionPool.recoverBook(pmatch, lpEntity, subPath, p.getFileName().toString(), hash);
                    log.info("[FOLDER_CREATE] File '{}' matched pending deletion, recovered book id={}", p, pmatch.book().bookId());
                    return;
                }

                Optional<BookFileEntity> movedFrom = bookFilePersistenceService.findMovedFile(
                        hash, p, Instant.now().minus(60, ChronoUnit.SECONDS));
                if (movedFrom.isPresent()) {
                    bookFilePersistenceService.updatePathIfChanged(movedFrom.get(), library, p);
                    log.info("[FOLDER_CREATE] File '{}' recognized as moved file, updated existing book's path", p);
                } else {
                    unmatched.put(p, hash);
                }
            } catch (Exception e) {
                log.warn("[ERROR] Hash check for '{}': {}", p, e.getMessage());
                unmatched.put(p, hash);
            }
        });
        return unmatched;
    }

    private void handleFolderDelete(LibraryEntity library, Path folderPath) {
        log.info("[FOLDER_DELETE] '{}'", folderPath);
        try {
            String libPath = bookFilePersistenceService.findMatchingLibraryPath(library, folderPath);
            LibraryPathEntity libPathEntity = bookFilePersistenceService.getLibraryPathEntityForFile(library, libPath);

            String relativePrefix = Path.of(libPathEntity.getPath()).relativize(folderPath).toString().replace("\\", "/");
            // The lookup compares paths through the database's collation, which ignores case and possibly accents; a
            // folder that only compares equal to the deleted one is another folder, and its books stay
            List<BookEntity> booksWithFiles = bookRepository.findBooksWithFilesUnderPath(libPathEntity.getId(), relativePrefix).stream()
                    .filter(book -> hasBookFormatUnder(book, relativePrefix))
                    .toList();

            if (booksWithFiles.isEmpty()) {
                log.debug("[FOLDER_DELETE] No books found under '{}'", folderPath);
                return;
            }

            ScheduledFuture<?> timer = scheduler.schedule(
                    () -> pendingDeletionPool.expireFolderDeletion(folderPath),
                    PENDING_DELETION_GRACE_MS, TimeUnit.MILLISECONDS);
            pendingDeletionPool.addFolderDeletion(folderPath, library.getId(), booksWithFiles, timer);
            log.info("[PENDING_DELETE] {} books under '{}' deferred deletion (grace period {}ms)",
                    booksWithFiles.size(), folderPath, PENDING_DELETION_GRACE_MS);
            // What waits as a copy of one of their files is where it was moved: the pool now hands it the book. A
            // mover that removes a tree it copied may report the folder's deletion without those of its files.
            handBackCopiesOf(booksWithFiles.stream()
                    .flatMap(book -> book.getBookFiles().stream())
                    .filter(BookFileEntity::isBookFormat)
                    .map(BookFileEntity::getCurrentHash)
                    .filter(Objects::nonNull)
                    .toList());
        } catch (Exception e) {
            log.warn("[ERROR] Folder delete '{}': {}", folderPath, e.getMessage());
        }
    }

    /** Whether the book has a book format in the folder or below it, or is the folder audiobook it names. */
    private static boolean hasBookFormatUnder(BookEntity book, String folder) {
        return book.getBookFiles().stream().anyMatch(bf -> bf.isBookFormat() && bf.getFileSubPath() != null
                && (bf.getFileSubPath().equals(folder)
                || bf.getFileSubPath().startsWith(folder + "/")
                || bf.isFolderBased() && (bf.getFileSubPath() + "/" + bf.getFileName()).equals(folder)));
    }

    private boolean fileHasContent(Path path) {
        try {
            return Files.size(path) > 0;
        } catch (IOException e) {
            return false;
        }
    }

    private boolean isUnderIgnoredDirectory(Path filePath, Path root) {
        Path parent = filePath.getParent();
        while (parent != null && !parent.equals(root)) {
            if (Files.exists(parent.resolve(".ignore"))) {
                return true;
            }
            parent = parent.getParent();
        }
        return false;
    }

    private boolean isBookFile(String fileName) {
        return BookFileExtension.fromFileName(fileName).isPresent();
    }

    private void scheduleWithStabilityCheck(long libraryId, Path path, Runnable onStable) {
        settlingFiles.add(path);
        // Queued before it leaves the settling files, so a drain always finds it in one or the other
        Runnable queue = () -> {
            onStable.run();
            settlingFiles.remove(path);
        };
        scheduler.schedule(() -> checkStability(libraryId, path, queue, clock.getAsLong()), 0, TimeUnit.MILLISECONDS);
    }

    private void checkStability(long libraryId, Path path, Runnable onStable, long startTime) {
        try {
            if (!Files.exists(path)) {
                onStable.run();
                return;
            }

            long elapsed = clock.getAsLong() - startTime;
            if (elapsed >= STABILITY_MAX_WAIT_MS) {
                log.warn("[STABILITY] Max wait exceeded for '{}', processing anyway", path);
                onStable.run();
                return;
            }

            FileTime mtime = Files.getLastModifiedTime(path);
            long msSinceMod = System.currentTimeMillis() - mtime.toMillis();

            if (msSinceMod < STABILITY_CHECK_INTERVAL_MS) {
                log.debug("[STABILITY] File '{}' modified {}ms ago, rechecking in {}ms", path, msSinceMod, STABILITY_CHECK_INTERVAL_MS);
                noteWrite(libraryId);
                scheduler.schedule(() -> checkStability(libraryId, path, onStable, startTime),
                        STABILITY_CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS);
            } else {
                onStable.run();
            }
        } catch (IOException e) {
            log.debug("[STABILITY] Error checking mtime for '{}': {}", path, e.getMessage());
            onStable.run();
        }
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
        log.info("Shutting down LibraryFileEventProcessor...");
    }

    /**
     * Whether the watcher still has work on a path under one of these: an event queued or debounced, a new file
     * settling, a new file or folder waiting as a copy, or a deletion held in the pool. An in-app move waits for these
     * first: a copy handed back while the move has moved a book's file but not yet stored its new path would find that
     * file gone, and take the book for its own.
     */
    public boolean hasPendingEventsForPaths(Set<Path> paths) {
        boolean inQueue = eventQueue.stream()
                .anyMatch(e -> paths.stream().anyMatch(p -> e.fullPath().startsWith(p)));
        boolean inDeletes = pendingDeletes.keySet().stream()
                .anyMatch(p -> paths.stream().anyMatch(p::startsWith));
        boolean inFolderCreates = pendingFolderCreates.keySet().stream()
                .anyMatch(p -> paths.stream().anyMatch(p::startsWith));
        boolean settling = settlingFiles.stream()
                .anyMatch(p -> paths.stream().anyMatch(p::startsWith));
        boolean inCopies = pendingCopies.keySet().stream()
                .anyMatch(p -> paths.stream().anyMatch(p::startsWith));
        boolean inPool = pendingDeletionPool.hasPendingForPaths(paths);
        return inQueue || inDeletes || inFolderCreates || settling || inCopies || inPool;
    }

    /**
     * @param waited how the event comes back to the queue after waiting, if it does
     * @param since  when that wait began
     */
    public record FileEvent(WatchEvent.Kind<?> eventKind, long libraryId, Path fullPath, boolean isDirectory,
                            Wait waited, long since) {
        public FileEvent(WatchEvent.Kind<?> eventKind, long libraryId, Path fullPath, boolean isDirectory) {
            this(eventKind, libraryId, fullPath, isDirectory, Wait.NONE, 0L);
        }
    }

    /** How an event comes back to the queue after waiting. */
    public enum Wait {
        /** It did not wait. */
        NONE,
        /** A folder's create, put off while a file in the folder was still being written. */
        SETTLING,
        /**
         * Something new that copies a stored file still there, handed back as a deletion of its content came in. What
         * is still such a copy waits on, from when it began to wait.
         */
        COPY,
        /** The same, handed back as its wait is over: what is still such a copy is taken for a copy. */
        COPY_OVER
    }
}