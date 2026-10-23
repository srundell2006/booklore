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
import org.booklore.repository.projection.BookFileScanRow;
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
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.stream.Stream;

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
     * once it stops writing. The wait counts from the last file seen arriving or growing in the library, book file or
     * not: a mover that is silent for this long, writing nothing the watcher can see, is taken to be done.
     */
    static final long COPY_SOURCE_WAIT_MS = 30000L;
    /**
     * The longest such a file waits while files keep arriving or growing in its library: a day. A move of a large folder
     * across devices copies the whole tree before it removes any of it, which takes hours for a large library on a slow
     * disk or share, and the watcher takes each finished folder of the tree as it comes; one whose wait ended before its
     * source was removed would be a second book, and the source's removal would then delete the first. A real copy made
     * while the library is this busy only appears once it is quiet.
     */
    static final long COPY_SOURCE_MAX_WAIT_MS = TimeUnit.HOURS.toMillis(24);

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
    /** New files handed on while still being written, once they reached the settling limit, until they are written. */
    private final Set<Path> growingFiles = ConcurrentHashMap.newKeySet();
    /**
     * What copies a stored file still there, by path, until that file goes or the wait is over: a new file, a folder
     * audiobook, or the new files of a BOOK_PER_FOLDER folder, which wait as one. Changed under {@link #copyLock}, so a
     * timer and a deletion cannot both hand one back, and a timer that fires as it is replaced sees the replacement.
     * Kept only in memory: a file already on disk gives no event, so one still waiting when the application stops is
     * announced again once it is back, by {@link #announceFilesWithoutBooks}, and waits anew.
     */
    private final ConcurrentMap<Path, PendingCopy> pendingCopies = new ConcurrentHashMap<>();
    private final Object copyLock = new Object();
    /** Numbers each waiting copy's timer, so a timer can tell whether the copy still waits on it. Under copyLock. */
    private long copyTimers;
    /** The paths of each library whose book files without a book are being looked for. */
    private final ConcurrentMap<Long, Set<Path>> catchingUp = new ConcurrentHashMap<>();
    /** When each library last saw a file arrive, or one still being written, on {@link #clock}. */
    private final ConcurrentMap<Long, Long> lastWriteSeen = new ConcurrentHashMap<>();
    private long copySourceWaitMs = COPY_SOURCE_WAIT_MS;
    private long copySourceMaxWaitMs = COPY_SOURCE_MAX_WAIT_MS;
    private long stabilityMaxWaitMs = STABILITY_MAX_WAIT_MS;
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

    /** For tests, which cannot wait the full time. */
    void setStabilityMaxWaitMs(long stabilityMaxWaitMs) {
        this.stabilityMaxWaitMs = stabilityMaxWaitMs;
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

    /**
     * A file that is no book file arrived in a library: nothing is imported from it, but a mover is at work there for
     * as long as it writes it. A mover that copies a tree removes the sources only once it has written all of it, its
     * cover art, extras and temporary files too, so the copies waiting in the library wait while it writes them.
     */
    public void noteFileArrived(long libraryId, Path fullPath) {
        noteWrite(libraryId);
        Path path = fullPath.toAbsolutePath().normalize();
        scheduler.execute(() -> noteWritesWhileGrowing(libraryId, path));
    }

    /**
     * Announces, as the watcher announces what arrives, the book files of a watched library that no book has: those
     * that arrived while the application was down, and those that still waited when it stopped, as copies, settling or
     * queued, as a file already on disk gives no event. Each is handled as it would have been: one that copies a stored
     * file still there waits for that file to go again, so a mover that removes the source once the application is
     * back still moves its book, and one whose stored file is gone is that file moved. The files are those a rescan
     * would import, each announced in its folder, as a new folder is, unless that folder holds books' files, where it
     * is announced on its own, as a file arriving there is. A file in a stored folder audiobook's folder, and a folder
     * audiobook whose folder holds books' files, only group otherwise than their books do, and are left to a rescan.
     * The library's deletions are held while the files are looked for. Run once the library's folders are watched.
     */
    public void announceFilesWithoutBooks(long libraryId) {
        LibraryEntity library = libraryRepository.findById(libraryId).orElse(null);
        if (library == null) {
            return;
        }
        Set<Path> roots = libraryPaths(library);
        catchingUp.put(libraryId, roots);
        try {
            List<LibraryFile> files = libraryProcessingService.findFilesWithoutBooks(libraryId);
            Map<String, List<BookFileScanRow>> rowsByFolder = new HashMap<>();
            Function<LibraryFile, List<BookFileScanRow>> rowsOfOwnFolder = file -> {
                long libraryPathId = file.getLibraryPathEntity().getId();
                String folder = BookFileScanRow.ownFolder(file.getFileSubPath(), file.getFileName(), file.isFolderBased());
                return rowsByFolder.computeIfAbsent(libraryPathId + ":" + folder, key -> rowsOwnedByFolder(libraryPathId, folder));
            };
            Set<Path> folders = new TreeSet<>();
            Set<Path> singleFiles = new TreeSet<>();
            for (LibraryFile file : files) {
                Path path = file.getFullPath().toAbsolutePath().normalize();
                if (file.isFolderBased()) {
                    if (rowsOfOwnFolder.apply(file).isEmpty()) {
                        folders.add(path);
                    }
                } else if (roots.contains(path.getParent())) {
                    singleFiles.add(path);
                } else {
                    List<BookFileScanRow> rows = rowsOfOwnFolder.apply(file);
                    if (rows.isEmpty()) {
                        folders.add(path.getParent());
                    } else if (rows.stream().noneMatch(BookFileScanRow::folderBased)) {
                        singleFiles.add(path);
                    }
                }
            }
            // A folder sorts before what it holds, and its handling takes in all of that
            List<Path> announcedFolders = new ArrayList<>();
            for (Path folder : folders) {
                if (announcedFolders.stream().noneMatch(folder::startsWith)) {
                    announcedFolders.add(folder);
                }
            }
            List<Path> announcedFiles = singleFiles.stream()
                    .filter(file -> announcedFolders.stream().noneMatch(file::startsWith))
                    .toList();
            announcedFolders.forEach(folder -> processEvent(StandardWatchEventKinds.ENTRY_CREATE, libraryId, folder, true));
            announcedFiles.forEach(file -> processEvent(StandardWatchEventKinds.ENTRY_CREATE, libraryId, file, false));
            if (!files.isEmpty()) {
                log.info("[CATCH_UP] Library '{}' has {} book files without a book; announced {} folders and {} files: {}",
                        library.getName(), files.size(), announcedFolders.size(), announcedFiles.size(),
                        Stream.concat(announcedFolders.stream(), announcedFiles.stream()).limit(50).toList());
            }
        } catch (Exception e) {
            log.warn("[CATCH_UP] Could not look for the book files without a book of library '{}': {}", library.getName(), e.getMessage());
        } finally {
            // Once what it announced is pending, which holds the deletions in their turn
            catchingUp.remove(libraryId);
        }
    }

    /** The book formats, of live books or not, whose own folder is this one: files stored in it, or it as a folder audiobook. */
    private List<BookFileScanRow> rowsOwnedByFolder(long libraryPathId, String folder) {
        int lastSep = folder.lastIndexOf('/');
        String parent = lastSep < 0 ? "" : folder.substring(0, lastSep);
        String leaf = folder.substring(lastSep + 1);
        return bookRepository.findBookFormatRowsOwnedByFolder(libraryPathId, folder, parent, leaf).stream()
                .filter(row -> folder.equals(row.ownFolder()))
                .toList();
    }

    private void handleEvent(FileEvent event) {
        Path path = event.fullPath();
        String fileName = path.getFileName().toString();
        if (event.waited() == Wait.SETTLING) {
            log.debug("[PROCESS] '{}' event for '{}' (directory, again)", event.eventKind().name(), fileName);
        } else {
            log.info("[PROCESS] '{}' event for '{}'{}", event.eventKind().name(), fileName,
                    event.isDirectory() ? " (directory)" : "");
        }

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
                waitAsCopy(libraryId, path, Set.of(copy.hash()), false, waited, since,
                        "copies a file of book id=" + copy.bookId()));
    }

    /**
     * Leaves something new that copies a stored file still there to wait. It is handed back at once when a deletion
     * of one of its contents comes in, which may make it the file moved; otherwise when its library has seen no file
     * arrive or grow for the wait, or after the longest wait, as a copy.
     *
     * @param waited how it came back from waiting as a copy, if it did: one handed back for a deletion, and still a
     *               copy, waits on from when it began to wait
     * @param since  when it began that wait
     */
    private void waitAsCopy(long libraryId, Path path, Set<String> hashes, boolean folder, Wait waited, long since, String what) {
        long began = waited == Wait.COPY ? since : clock.getAsLong();
        boolean waitsOn;
        synchronized (copyLock) {
            PendingCopy previous = pendingCopies.get(path);
            waitsOn = previous != null || waited == Wait.COPY;
            long from = previous != null ? Math.min(previous.since(), began) : began;
            if (previous != null) {
                previous.recheck().cancel(false);
            }
            long delay = Math.max(0L, from + copySourceWaitMs - clock.getAsLong());
            pendingCopies.put(path, armed(path, libraryId, hashes, folder, from, delay));
        }
        if (waitsOn) {
            log.debug("[FILE_CREATE] '{}' {}; waits on for it to go", path, what);
        } else {
            log.info("[FILE_CREATE] '{}' {}; waits for it to go until its library has seen no file arrive or grow for {}ms, "
                    + "and is then taken for a copy", path, what, copySourceWaitMs);
        }
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
                        ScheduledFuture<?> timer = scheduleExpiry(libraryPaths(library), path,
                                () -> pendingDeletionPool.expireFileDeletion(path), PENDING_DELETION_GRACE_MS, clock.getAsLong());
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
            case BOOK_PER_FILE -> handleEachFile(library.getId(), bookFilesUnder(library.getId(), folderPath));
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
        for (Path file : bookFilesUnder(library.getId(), folderPath)) {
            filesByFolder.computeIfAbsent(file.getParent(), parent -> new ArrayList<>()).add(file);
        }
        Set<Path> libraryPaths = libraryPaths(library);

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
        if (now - from >= stabilityMaxWaitMs) {
            log.warn("[STABILITY] '{}' is still being written after {}ms; handling folder '{}' anyway",
                    written.getFirst(), stabilityMaxWaitMs, folderPath);
            written.forEach(file -> noteWritesWhileGrowing(libraryId, file));
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

    /**
     * The book files in the folder and below it, but for those in a folder marked to be ignored. Other files there
     * still being written are noted as writes while they grow: a mover writes the first file of a new folder before
     * the folder is watched, so no event comes for it, and a large one can outlast a copy's wait.
     */
    private List<Path> bookFilesUnder(long libraryId, Path folderPath) {
        try (var stream = Files.walk(folderPath)) {
            List<Path> bookFiles = new ArrayList<>();
            stream.filter(Files::isRegularFile).forEach(p -> {
                if (!isBookFile(p.getFileName().toString())) {
                    if (beingWritten(p)) {
                        noteWritesWhileGrowing(libraryId, p);
                    }
                } else if (!isUnderIgnoredDirectory(p, folderPath)) {
                    bookFiles.add(p);
                }
            });
            bookFiles.sort(Comparator.naturalOrder());
            return bookFiles;
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
                waitAsCopy(libraryId, folderPath, Set.of(copy.hash()), true, waited, since,
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
        List<Path> bookFiles = bookFilesUnder(library.getId(), folderPath).stream()
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
                waitAsCopy(library.getId(), folderPath, copied.keySet(), true, event.waited(), event.since(),
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

    /**
     * Expires a deletion held in the pool once its grace period is over, unless new files in its library still wait to
     * be handled: a new folder whose handling each file copied into it puts off again, a folder waiting for a file
     * still being written, a new file settling, a create queued, or the files without a book that the library is
     * looked through for once the application is back (see {@link #announceFilesWithoutBooks}). A mover that copies
     * files into a new folder and removes each source as soon as it copied it leaves the book in the pool while its
     * file waits there; the book was moved, and is not to be deleted for the path it left. Puts the expiry off for as
     * long as a copy waits at most.
     *
     * @param heldSince when the deletion came in
     */
    private ScheduledFuture<?> scheduleExpiry(Set<Path> libraryPaths, Path path, Runnable expire, long delayMs, long heldSince) {
        AtomicReference<ScheduledFuture<?>> timer = new AtomicReference<>();
        timer.set(scheduler.schedule(() -> {
            if (newFilesWaitIn(libraryPaths) && clock.getAsLong() - heldSince < copySourceMaxWaitMs) {
                ScheduledFuture<?> later = scheduleExpiry(libraryPaths, path, expire, STABILITY_CHECK_INTERVAL_MS, heldSince);
                if (!pendingDeletionPool.retime(path, timer.get(), later)) {
                    // Matched by a new file meanwhile, or held again for a later deletion with a timer of its own
                    later.cancel(false);
                }
                return;
            }
            expire.run();
        }, delayMs, TimeUnit.MILLISECONDS));
        return timer.get();
    }

    /**
     * Whether a new folder or file in one of these library paths waits to be handled, or the library's files without
     * a book are still being looked for.
     */
    private boolean newFilesWaitIn(Set<Path> libraryPaths) {
        Predicate<Path> inLibrary = p -> libraryPaths.stream().anyMatch(p::startsWith);
        return catchingUp.values().stream().anyMatch(paths -> !Collections.disjoint(paths, libraryPaths))
                || pendingFolderCreates.keySet().stream().anyMatch(inLibrary)
                || settlingFiles.stream().anyMatch(inLibrary)
                || eventQueue.stream().anyMatch(e -> e.eventKind() == StandardWatchEventKinds.ENTRY_CREATE && inLibrary.test(e.fullPath()));
    }

    private static Set<Path> libraryPaths(LibraryEntity library) {
        Set<Path> libraryPaths = new HashSet<>();
        library.getLibraryPaths().forEach(lp -> libraryPaths.add(Paths.get(lp.getPath()).toAbsolutePath().normalize()));
        return libraryPaths;
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

            ScheduledFuture<?> timer = scheduleExpiry(libraryPaths(library), folderPath,
                    () -> pendingDeletionPool.expireFolderDeletion(folderPath), PENDING_DELETION_GRACE_MS, clock.getAsLong());
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
            if (elapsed >= stabilityMaxWaitMs) {
                log.warn("[STABILITY] Max wait exceeded for '{}', processing anyway", path);
                noteWritesWhileGrowing(libraryId, path);
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

    /**
     * Goes on noting the writes to a file handed on while still being written, once it reached the settling limit,
     * until it is written: the copies waiting in its library wait as long as a mover still writes, as it removes the
     * sources it copied only once it is done. A file is written to while it changes from one check to the next: its
     * modification time alone can be ahead of this host's clock, as a share whose clock runs ahead stamps it, and would
     * hold the copies until the clock caught up. Bounded by the longest wait of a copy, which is all it serves.
     */
    private void noteWritesWhileGrowing(long libraryId, Path file) {
        FileState state = FileState.of(file);
        if (state != null && growingFiles.add(file)) {
            noteWrite(libraryId);
            long since = clock.getAsLong();
            scheduler.schedule(() -> stillGrowing(libraryId, file, state, since), STABILITY_CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS);
        }
    }

    private void stillGrowing(long libraryId, Path file, FileState before, long since) {
        FileState state = FileState.of(file);
        if (state != null && !state.equals(before) && clock.getAsLong() - since < copySourceMaxWaitMs) {
            noteWrite(libraryId);
            scheduler.schedule(() -> stillGrowing(libraryId, file, state, since), STABILITY_CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS);
        } else {
            growingFiles.remove(file);
        }
    }

    /** A file's size and modification time, which change while it is written. */
    private record FileState(long size, long modified) {

        /** Null when the file cannot be read, as when it is gone. */
        static FileState of(Path file) {
            try {
                BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
                return new FileState(attributes.size(), attributes.lastModifiedTime().toMillis());
            } catch (IOException e) {
                return null;
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
        // What waits is kept only in memory, and a file already on disk gives no event once the application is back up
        Set<Path> unhandled = new TreeSet<>(pendingCopies.keySet());
        unhandled.addAll(settlingFiles);
        unhandled.addAll(pendingFolderCreates.keySet());
        eventQueue.stream().filter(e -> e.eventKind() == StandardWatchEventKinds.ENTRY_CREATE)
                .forEach(e -> unhandled.add(e.fullPath()));
        if (!unhandled.isEmpty()) {
            log.warn("[SHUTDOWN] {} new files or folders were still waiting and are not imported; they are announced "
                    + "again when the application starts while their library is watched, and a rescan of their library "
                    + "imports them: {}", unhandled.size(), unhandled.stream().limit(50).toList());
        }
        log.info("Shutting down LibraryFileEventProcessor...");
    }

    /**
     * Whether the watcher still has work on a path under one of these: an event queued or debounced, a new file
     * settling, a new file or folder waiting as a copy, or a deletion held in the pool. An in-app move waits for these
     * first: a copy handed back while the move has moved a book's file but not yet stored its new path would find that
     * file gone, and take the book for its own. Work is queued before it leaves the pending sets (handBack,
     * scheduleFolderCreate, scheduleWithStabilityCheck), so a drain finds it in one or the other; no test pins that
     * order, as the window it closes is a few instructions wide. An event being handled is in neither.
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