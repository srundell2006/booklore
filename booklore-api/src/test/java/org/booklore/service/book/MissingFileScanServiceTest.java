package org.booklore.service.book;

import org.booklore.config.security.service.AuthenticationService;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MissingFileScanServiceTest {

    @TempDir
    Path tempDir;

    private final BookRepository bookRepository = mock(BookRepository.class);
    private final TagRepository tagRepository = mock(TagRepository.class);
    private final MissingFileScanLoader scanLoader = mock(MissingFileScanLoader.class);
    private final NotificationService notificationService = mock(NotificationService.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final AuthenticationService authenticationService = mock(AuthenticationService.class);
    private final TaskCancellationManager cancellationManager = mock(TaskCancellationManager.class);
    private final MagicShelfBookService magicShelfBookService = mock(MagicShelfBookService.class);

    /** What the loader hands out, by book id. */
    private final Map<Long, FileCheckTarget> targets = new HashMap<>();
    /** Checks of a "stuck" file block, ignoring interrupts, until this opens: a stat call on a dead share. */
    private final CountDownLatch shareAnswers = new CountDownLatch(1);

    /** How long a scan waits for stuck checks to return before it stops. */
    private Duration recoveryWait = Duration.ofMillis(500);

    /** What one check asks of storage, in place of the filesystem. */
    private interface Storage {
        boolean exists(Path path) throws Exception;

        /** Whether a library path has anything in it: the share is mounted, unless a test says otherwise. */
        default boolean hasEntries(Path dir) throws Exception {
            return true;
        }
    }

    @AfterEach
    void releaseStuckChecks() {
        shareAnswers.countDown();
    }

    private void loadTargets() {
        when(scanLoader.load(any(), anyString())).thenAnswer(inv -> {
            Set<Long> ids = inv.getArgument(0);
            return ids.stream().map(targets::get).toList();
        });
    }

    /** A service checking the real filesystem. */
    private MissingFileScanService service() {
        loadTargets();
        return new MissingFileScanService(bookRepository, tagRepository, scanLoader, notificationService,
                transactionManager, authenticationService, cancellationManager, magicShelfBookService);
    }

    private MissingFileScanService service(Storage storage) {
        loadTargets();
        return new MissingFileScanService(bookRepository, tagRepository, scanLoader, notificationService,
                transactionManager, authenticationService, cancellationManager, magicShelfBookService) {
            @Override
            boolean exists(Path path) throws IOException {
                return ask(() -> storage.exists(path));
            }

            @Override
            boolean hasEntries(Path dir) throws IOException {
                return ask(() -> storage.hasEntries(dir));
            }

            @Override
            Duration storageRecoveryWait() {
                return recoveryWait;
            }
        };
    }

    private static boolean ask(Callable<Boolean> call) throws IOException {
        try {
            return call.call();
        } catch (IOException | RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Set<Long> books(long fromId, int count, String fileName) {
        Set<Long> ids = new LinkedHashSet<>();
        LongStream.range(fromId, fromId + count).forEach(id -> {
            targets.put(id, target(id, Path.of("/library", id + "-" + fileName), false));
            ids.add(id);
        });
        return ids;
    }

    private static FileCheckTarget target(long id, Path file, boolean alreadyTagged) {
        return target(id, List.of(file), alreadyTagged);
    }

    private static FileCheckTarget target(long id, List<Path> files, boolean alreadyTagged) {
        return target(id, Path.of("/library"), files, alreadyTagged);
    }

    private static FileCheckTarget target(long id, Path libraryPath, List<Path> files, boolean alreadyTagged) {
        List<FileRef> refs = files.stream().map(file -> new FileRef(file.getFileName().toString(), file)).toList();
        return new FileCheckTarget(id, "Book " + id, libraryPath, refs, alreadyTagged);
    }

    private static MissingFileScanRequest request(Set<Long> bookIds, int parallelism, boolean dryRun) {
        return MissingFileScanRequest.builder()
                .refreshType(MissingFileScanRequest.RefreshType.BOOKS)
                .bookIds(bookIds)
                .parallelism(parallelism)
                .checkTimeoutSeconds(1)
                .dryRun(dryRun)
                .build();
    }

    private void blockUntilTheShareAnswers() {
        boolean interrupted = false;
        while (true) {
            try {
                shareAnswers.await();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private List<MetadataBatchProgressNotification> progress() {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(notificationService, atLeastOnce())
                .sendMessage(eq(Topic.BOOK_METADATA_BATCH_PROGRESS), captor.capture());
        return captor.getAllValues().stream().map(MetadataBatchProgressNotification.class::cast).toList();
    }

    private MetadataBatchProgressNotification finalProgress() {
        return progress().getLast();
    }

    private static void awaitNoStrandedChecks(MissingFileScanService service) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (service.strandedChecks() > 0 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(service.strandedChecks()).isZero();
    }

    @Test
    void checkThatNeverReturnsIsUnresolvedAndTheScanFinishesPromptly() throws Exception {
        Set<Long> ids = books(1, 10, "book.epub");
        // Book 3 is tagged already and its share is stuck; book 5's file is gone.
        targets.put(3L, target(3L, Path.of("/library/3-stuck.epub"), true));
        targets.put(5L, target(5L, Path.of("/library/5-gone.epub"), false));
        TagEntity tag = TagEntity.builder().id(1L).name(MissingFileScanService.DEFAULT_TAG_NAME).build();
        when(tagRepository.findByNameIgnoreCase(MissingFileScanService.DEFAULT_TAG_NAME)).thenReturn(Optional.of(tag));
        BookEntity gone = BookEntity.builder().id(5L).metadata(BookMetadataEntity.builder().tags(new HashSet<>()).build()).build();
        when(bookRepository.findById(5L)).thenReturn(Optional.of(gone));
        Set<Thread> checkThreads = ConcurrentHashMap.newKeySet();

        MissingFileScanService service = service(path -> {
            checkThreads.add(Thread.currentThread());
            if (path.toString().endsWith("stuck.epub")) {
                blockUntilTheShareAnswers();
            }
            return !path.toString().endsWith("gone.epub");
        });

        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> service.scan(request(ids, 4, false), "task-1"));

        MetadataBatchProgressNotification done = finalProgress();
        assertThat(done.getStatus()).isEqualTo(MetadataFetchTaskStatus.COMPLETED.name());
        assertThat(done.getMessage()).startsWith("File check complete. 1 missing (tagged 'File Not Found'), 0 restored, "
                + "1 unresolved, 10 checked in ");
        // Only the book whose file is gone is tagged; the one that could not be checked keeps its tag.
        assertThat(gone.getMetadata().getTags()).containsExactly(tag);
        verify(bookRepository).save(gone);
        verify(bookRepository, never()).findById(3L);
        assertThat(checkThreads).allSatisfy(thread -> {
            assertThat(thread.isVirtual()).isFalse();
            assertThat(thread.isDaemon()).isTrue();
            assertThat(thread.getName()).startsWith("missing-file-check-");
        });

        // The abandoned check holds its thread until the call returns, then lets go of it.
        assertThat(service.strandedChecks()).isEqualTo(1);
        shareAnswers.countDown();
        awaitNoStrandedChecks(service);
    }

    @Test
    void stuckCheckGivesItsSlotToTheNextBookExactlyOnce() throws Exception {
        Set<Long> ids = new LinkedHashSet<>();
        ids.add(0L);
        targets.put(0L, target(0L, Path.of("/library/0-stuck.epub"), false));
        ids.addAll(books(1, 90, "book.epub"));
        AtomicInteger started = new AtomicInteger();
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        AtomicInteger maxActiveWhileStuck = new AtomicInteger();

        MissingFileScanService service = service(path -> {
            if (path.toString().endsWith("stuck.epub")) {
                blockUntilTheShareAnswers();
                return true;
            }
            // While the stuck check holds a slot, at most 26 of these can start within its one-second timeout. The
            // 50th starts well after it was given up on, and lets it return late, with plenty of checks still to run.
            if (started.incrementAndGet() == 50) {
                maxActiveWhileStuck.set(maxActive.get());
                shareAnswers.countDown();
            }
            maxActive.accumulateAndGet(active.incrementAndGet(), Math::max);
            try {
                Thread.sleep(40);
            } finally {
                active.decrementAndGet();
            }
            return true;
        });

        assertTimeoutPreemptively(Duration.ofSeconds(15), () -> service.scan(request(ids, 2, true), "task-2"));

        MetadataBatchProgressNotification done = finalProgress();
        assertThat(done.getStatus()).isEqualTo(MetadataFetchTaskStatus.COMPLETED.name());
        assertThat(done.getMessage()).startsWith("DRY RUN — File check complete. 0 missing (tagged 'File Not Found'), "
                + "0 restored, 1 unresolved, 91 checked in ");
        // Once the stuck check is given up on, its slot goes to the other books while it is still stuck...
        assertThat(maxActiveWhileStuck.get()).isEqualTo(2);
        // ...and its late return frees no second one.
        assertThat(maxActive.get()).isEqualTo(2);
        awaitNoStrandedChecks(service);
    }

    @Test
    void abandonedCheckMakesNoMoreCallsToTheShareOnceItsStuckCallReturns() throws Exception {
        Set<Long> ids = new LinkedHashSet<>(List.of(1L, 2L));
        targets.put(1L, target(1L, List.of(Path.of("/library/1-stuck.epub"), Path.of("/library/1-book.pdf"),
                Path.of("/library/1-book.mobi")), false));
        targets.put(2L, target(2L, List.of(Path.of("/library/2-book.epub"), Path.of("/library/2-gone.pdf")), false));
        List<Path> asked = new CopyOnWriteArrayList<>();
        MissingFileScanService service = service(path -> {
            asked.add(path);
            if (path.toString().endsWith("stuck.epub")) {
                blockUntilTheShareAnswers();
            }
            return !path.toString().endsWith("gone.pdf");
        });

        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> service.scan(request(ids, 2, true), "task-8"));

        assertThat(finalProgress().getMessage()).startsWith("DRY RUN — File check complete. 1 missing (tagged "
                + "'File Not Found'), 0 restored, 1 unresolved, 2 checked in ");
        // The stuck call returns long after the check was given up on, and the book's other files are left alone.
        shareAnswers.countDown();
        awaitNoStrandedChecks(service);
        assertThat(asked).containsExactlyInAnyOrder(Path.of("/library/1-stuck.epub"),
                Path.of("/library/2-book.epub"), Path.of("/library/2-gone.pdf"));
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void fileIsMissingOnlyWhenStorageSaysItIsNotThere() throws Exception {
        Path present = Files.createFile(tempDir.resolve("present.epub"));
        Path gone = tempDir.resolve("gone.epub");
        // A path through a regular file: not there either.
        Path underAFile = present.resolve("child.epub");
        // Any other error means storage could not say, as when a CIFS mount is reconnecting (EHOSTDOWN) or failing
        // reads (EIO). A symlink loop (ELOOP) stands in for those here.
        Path loop = tempDir.resolve("loop.epub");
        Path loopBack = tempDir.resolve("loop-back.epub");
        Files.createSymbolicLink(loop, loopBack);
        Files.createSymbolicLink(loopBack, loop);
        targets.put(1L, target(1L, tempDir, List.of(present), false));
        targets.put(2L, target(2L, tempDir, List.of(present, gone), false));
        targets.put(3L, target(3L, tempDir, List.of(underAFile), false));
        targets.put(4L, target(4L, tempDir, List.of(loop), false));
        targets.put(5L, target(5L, tempDir, List.of(present, loop), true));
        TagEntity tag = TagEntity.builder().id(1L).name(MissingFileScanService.DEFAULT_TAG_NAME).build();
        when(tagRepository.findByNameIgnoreCase(MissingFileScanService.DEFAULT_TAG_NAME)).thenReturn(Optional.of(tag));
        BookEntity book2 = BookEntity.builder().id(2L).metadata(BookMetadataEntity.builder().tags(new HashSet<>()).build()).build();
        BookEntity book3 = BookEntity.builder().id(3L).metadata(BookMetadataEntity.builder().tags(new HashSet<>()).build()).build();
        when(bookRepository.findById(2L)).thenReturn(Optional.of(book2));
        when(bookRepository.findById(3L)).thenReturn(Optional.of(book3));
        MissingFileScanService service = service();

        service.scan(request(new LinkedHashSet<>(List.of(1L, 2L, 3L, 4L, 5L)), 2, false), "task-9");

        assertThat(finalProgress().getMessage()).startsWith("File check complete. 2 missing (tagged 'File Not Found'), "
                + "0 restored, 2 unresolved, 5 checked in ");
        assertThat(book2.getMetadata().getTags()).containsExactly(tag);
        assertThat(book3.getMetadata().getTags()).containsExactly(tag);
        // Neither tagged nor, for the book tagged already, cleared.
        verify(bookRepository, never()).findById(4L);
        verify(bookRepository, never()).findById(5L);
    }

    @Test
    void eachCheckIsTimedFromWhenItStarts() throws Exception {
        // One at a time, each taking most of the one-second timeout: together they take well over it.
        Set<Long> ids = books(1, 2, "book.epub");
        MissingFileScanService service = service(path -> {
            Thread.sleep(700);
            return true;
        });

        service.scan(request(ids, 1, true), "task-3");

        assertThat(finalProgress().getMessage()).startsWith("DRY RUN — File check complete. 0 missing (tagged 'File Not Found'), "
                + "0 restored, 0 unresolved, 2 checked in ");
    }

    @Test
    void stopsWhenTooManyChecksStayStuckAndStartsNoMoreUntilTheyReturn() throws Exception {
        int limit = MissingFileScanService.MAX_STRANDED_CHECKS;
        int parallelism = limit;
        Set<Long> dead = books(1, limit + parallelism + 36, "stuck.epub");
        Set<Long> healthy = books(1000, 5, "book.epub");
        AtomicInteger calls = new AtomicInteger();
        MissingFileScanService service = service(path -> {
            calls.incrementAndGet();
            if (path.toString().endsWith("stuck.epub")) {
                blockUntilTheShareAnswers();
            }
            return true;
        });

        // Every check gets stuck and is given up on, until so many are stuck that the scan stops starting them. It
        // waits for them to return and, as they do not, stops.
        long started = System.nanoTime();
        Throwable failure = assertTimeoutPreemptively(Duration.ofSeconds(10),
                () -> catchThrowable(() -> service.scan(request(dead, parallelism, true), "task-4")));
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isGreaterThanOrEqualTo(recoveryWait);

        int stuck = calls.get();
        assertThat(stuck).isBetween(limit, limit + parallelism - 1).isLessThan(dead.size());
        assertThat(service.strandedChecks()).isEqualTo(stuck);
        MetadataBatchProgressNotification stopped = finalProgress();
        assertThat(stopped.getStatus()).isEqualTo(MetadataFetchTaskStatus.ERROR.name());
        assertThat(stopped.getMessage()).startsWith("DRY RUN — Storage is not responding: " + stuck
                + " file checks got stuck waiting on it and did not return, so the scan stopped. 0 missing (tagged "
                + "'File Not Found'), 0 restored, " + stuck + " unresolved, " + stuck + " checked in ");
        assertThat(stopped.getCompleted()).isEqualTo(stuck);
        assertThat(stopped.getTotal()).isEqualTo(dead.size());
        // The task running the scan fails with the same message, rather than completing.
        assertThat(failure).isInstanceOf(MissingFileScanService.StorageNotRespondingException.class)
                .hasMessage(stopped.getMessage());

        // No more threads are put at risk while they are stuck, not even for books on healthy storage.
        assertThat(assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> catchThrowable(() -> service.scan(request(healthy, 4, true), "task-5"))))
                .isInstanceOf(MissingFileScanService.StorageNotRespondingException.class);
        assertThat(finalProgress().getStatus()).isEqualTo(MetadataFetchTaskStatus.ERROR.name());
        assertThat(finalProgress().getMessage()).contains("Storage is not responding: " + stuck + " file checks");
        assertThat(calls.get()).isEqualTo(stuck);

        // Once the share answers again the stuck threads return, and scans check again.
        shareAnswers.countDown();
        awaitNoStrandedChecks(service);
        service.scan(request(healthy, 4, true), "task-6");
        assertThat(finalProgress().getStatus()).isEqualTo(MetadataFetchTaskStatus.COMPLETED.name());
        assertThat(calls.get()).isEqualTo(stuck + healthy.size());
    }

    @Test
    void carriesOnWhenStuckChecksReturnWhileItWaitsForThem() throws Exception {
        // The share drops out for a while, as when it reconnects: every check started meanwhile gets stuck.
        int limit = MissingFileScanService.MAX_STRANDED_CHECKS;
        Set<Long> ids = books(1, limit + limit + 36, "stuck.epub");
        ids.addAll(books(1000, 40, "book.epub"));
        recoveryWait = Duration.ofSeconds(30);
        AtomicInteger calls = new AtomicInteger();
        MissingFileScanService service = service(path -> {
            calls.incrementAndGet();
            if (path.toString().endsWith("stuck.epub")) {
                blockUntilTheShareAnswers();
            }
            return true;
        });

        CompletableFuture<Void> scan =
                CompletableFuture.runAsync(() -> service.scan(request(ids, limit, true), "task-10"));
        // So many get stuck that the scan stops starting checks; then the share answers again.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (service.strandedChecks() < limit && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(service.strandedChecks()).isGreaterThanOrEqualTo(limit);
        shareAnswers.countDown();

        // The scan carries on long before its wait runs out, and checks every book.
        scan.get(10, TimeUnit.SECONDS);
        MetadataBatchProgressNotification done = finalProgress();
        assertThat(done.getStatus()).isEqualTo(MetadataFetchTaskStatus.COMPLETED.name());
        Matcher counts = Pattern.compile("0 restored, (\\d+) unresolved, (\\d+) checked in ")
                .matcher(done.getMessage());
        assertThat(counts.find()).isTrue();
        assertThat(Integer.parseInt(counts.group(1))).isBetween(limit, limit + limit - 1);
        assertThat(Integer.parseInt(counts.group(2))).isEqualTo(ids.size());
        assertThat(calls.get()).isEqualTo(ids.size());
        awaitNoStrandedChecks(service);
    }

    @Test
    void cancellingWhileItWaitsForStuckChecksEndsTheScan() throws Exception {
        int limit = MissingFileScanService.MAX_STRANDED_CHECKS;
        Set<Long> ids = books(1, 3 * limit, "stuck.epub");
        recoveryWait = Duration.ofSeconds(30);
        // Cancelled once the scan is waiting.
        when(cancellationManager.isTaskCancelled("task-11")).thenReturn(false, true);
        MissingFileScanService service = service(path -> {
            blockUntilTheShareAnswers();
            return true;
        });

        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> service.scan(request(ids, limit, true), "task-11"));

        MetadataBatchProgressNotification done = finalProgress();
        assertThat(done.getStatus()).isEqualTo(MetadataFetchTaskStatus.CANCELLED.name());
        assertThat(done.getMessage()).startsWith("Scan cancelled. DRY RUN — File check complete. 0 missing");
        assertThat(done.getCompleted()).isEqualTo(service.strandedChecks()).isLessThan(ids.size());
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void booksUnderALibraryPathThatIsMissingOrEmptyAreLeftUnchanged() throws Exception {
        // A share that is not mounted leaves an empty mount point, or none, under which every file is "not there".
        Path mounted = Files.createDirectory(tempDir.resolve("mounted"));
        Path present = Files.createFile(mounted.resolve("present.epub"));
        Path unmounted = Files.createDirectory(tempDir.resolve("unmounted"));
        Path noMountPoint = tempDir.resolve("no-mount-point");
        targets.put(1L, target(1L, mounted, List.of(present, mounted.resolve("gone.epub")), false));
        targets.put(2L, target(2L, unmounted, List.of(unmounted.resolve("Author/book.epub")), false));
        targets.put(3L, target(3L, noMountPoint, List.of(noMountPoint.resolve("Author/book.epub")), false));
        // Tagged by an earlier scan, while the share was there.
        targets.put(4L, target(4L, unmounted, List.of(unmounted.resolve("Author/gone.epub")), true));
        TagEntity tag = TagEntity.builder().id(1L).name(MissingFileScanService.DEFAULT_TAG_NAME).build();
        when(tagRepository.findByNameIgnoreCase(MissingFileScanService.DEFAULT_TAG_NAME)).thenReturn(Optional.of(tag));
        BookEntity book1 = BookEntity.builder().id(1L).metadata(BookMetadataEntity.builder().tags(new HashSet<>()).build()).build();
        when(bookRepository.findById(1L)).thenReturn(Optional.of(book1));

        service().scan(request(new LinkedHashSet<>(List.of(1L, 2L, 3L, 4L)), 2, false), "task-12");

        assertThat(finalProgress().getMessage()).startsWith("File check complete. 1 missing (tagged 'File Not Found'), "
                + "0 restored, 3 unresolved, 4 checked in ");
        assertThat(book1.getMetadata().getTags()).containsExactly(tag);
        // Neither tagged nor, for the book tagged already, cleared.
        verify(bookRepository, never()).findById(2L);
        verify(bookRepository, never()).findById(3L);
        verify(bookRepository, never()).findById(4L);
    }

    @Test
    void libraryPathIsLookedUpOncePerChunkAndOnlyWhenAFileIsMissing() throws Exception {
        // More than one chunk's worth, checked one at a time so that no two look it up at once.
        Set<Long> ids = books(1, 600, "gone.epub");
        ids.addAll(books(1000, 5, "book.epub"));
        List<Path> listed = new CopyOnWriteArrayList<>();
        MissingFileScanService service = service(new Storage() {
            @Override
            public boolean exists(Path path) {
                return !path.toString().endsWith("gone.epub");
            }

            @Override
            public boolean hasEntries(Path dir) {
                listed.add(dir);
                return true;
            }
        });

        service.scan(request(ids, 1, true), "task-13");

        assertThat(finalProgress().getMessage()).startsWith("DRY RUN — File check complete. 600 missing (tagged "
                + "'File Not Found'), 0 restored, 0 unresolved, 605 checked in ");
        assertThat(listed).containsExactly(Path.of("/library"), Path.of("/library"));

        listed.clear();
        service.scan(request(books(2000, 5, "book.epub"), 1, true), "task-14");
        assertThat(listed).isEmpty();
    }

    @Test
    void libraryPathLookupThatNeverReturnsLeavesTheBookUnresolved() throws Exception {
        Set<Long> ids = books(1, 1, "gone.epub");
        ids.addAll(books(2, 3, "book.epub"));
        MissingFileScanService service = service(new Storage() {
            @Override
            public boolean exists(Path path) {
                return !path.toString().endsWith("gone.epub");
            }

            @Override
            public boolean hasEntries(Path dir) {
                blockUntilTheShareAnswers();
                return true;
            }
        });

        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> service.scan(request(ids, 2, true), "task-15"));

        assertThat(finalProgress().getMessage()).startsWith("DRY RUN — File check complete. 0 missing (tagged "
                + "'File Not Found'), 0 restored, 1 unresolved, 4 checked in ");
        shareAnswers.countDown();
        awaitNoStrandedChecks(service);
    }

    @Test
    void fatalErrorFailsTheScanAfterReportingIt() {
        MissingFileScanRequest request = MissingFileScanRequest.builder()
                .refreshType(MissingFileScanRequest.RefreshType.LIBRARY)
                .build();

        assertThatThrownBy(() -> service().scan(request, "task-16"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("libraryId required for LIBRARY scope");
        MetadataBatchProgressNotification failed = finalProgress();
        assertThat(failed.getStatus()).isEqualTo(MetadataFetchTaskStatus.ERROR.name());
        assertThat(failed.getMessage()).isEqualTo("Fatal error: libraryId required for LIBRARY scope");
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void checksBlockedInFileCallsDoNotStallVirtualThreads() throws Exception {
        // More checks blocked in open() than there are carrier threads for virtual threads.
        int wedged = Runtime.getRuntime().availableProcessors() + 2;
        Set<Long> ids = new LinkedHashSet<>();
        List<Path> fifos = new ArrayList<>();
        for (long id = 1; id <= wedged; id++) {
            Path fifo = tempDir.resolve(id + "-fifo.epub");
            fifos.add(fifo);
            targets.put(id, target(id, fifo, false));
            ids.add(id);
        }
        List<String> mkfifo = new ArrayList<>(List.of("mkfifo"));
        fifos.forEach(fifo -> mkfifo.add(fifo.toString()));
        assertThat(new ProcessBuilder(mkfifo).inheritIO().start().waitFor()).isZero();

        CountDownLatch opening = new CountDownLatch(wedged);
        CountDownLatch opened = new CountDownLatch(wedged);
        MissingFileScanService service = service(path -> {
            opening.countDown();
            // Opening a FIFO for reading blocks in the open call until a writer opens it.
            try (FileInputStream ignored = new FileInputStream(path.toFile())) {
                opened.countDown();
                return true;
            }
        });

        Thread scan = Thread.ofPlatform().daemon(true).start(() -> service.scan(request(ids, wedged, true), "task-7"));
        try {
            assertThat(opening.await(10, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(200);
            assertThat(opened.getCount()).isEqualTo(wedged);

            CompletableFuture<Void> ran = new CompletableFuture<>();
            Thread.ofVirtual().start(() -> ran.complete(null));
            ran.get(1, TimeUnit.SECONDS);
        } finally {
            // Opening read-write never blocks on Linux, and lets every reader blocked in open() through.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!opened.await(100, TimeUnit.MILLISECONDS) && System.nanoTime() < deadline) {
                for (Path fifo : fifos) {
                    new RandomAccessFile(fifo.toFile(), "rw").close();
                }
            }
            scan.join(10_000);
        }
        assertThat(scan.isAlive()).isFalse();
        awaitNoStrandedChecks(service);
    }
}
