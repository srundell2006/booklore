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
import java.io.RandomAccessFile;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
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

    /** What one check asks of storage, in place of the filesystem. */
    private interface Storage {
        boolean exists(Path path) throws Exception;
    }

    @AfterEach
    void releaseStuckChecks() {
        shareAnswers.countDown();
    }

    private MissingFileScanService service(Storage storage) {
        when(scanLoader.load(any(), anyString())).thenAnswer(inv -> {
            Set<Long> ids = inv.getArgument(0);
            return ids.stream().map(targets::get).toList();
        });
        return new MissingFileScanService(bookRepository, tagRepository, scanLoader, notificationService,
                transactionManager, authenticationService, cancellationManager, magicShelfBookService) {
            @Override
            boolean exists(Path path) {
                try {
                    return storage.exists(path);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
        };
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
        return new FileCheckTarget(id, "Book " + id, List.of(new FileRef(file.getFileName().toString(), file)), alreadyTagged);
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
    void stopsWhenTooManyChecksAreStuckAndStartsNoMoreUntilTheyReturn() throws Exception {
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

        // Every check gets stuck and is given up on, until so many are stuck that the scan stops starting them.
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> service.scan(request(dead, parallelism, true), "task-4"));

        int stuck = calls.get();
        assertThat(stuck).isBetween(limit, limit + parallelism - 1).isLessThan(dead.size());
        assertThat(service.strandedChecks()).isEqualTo(stuck);
        MetadataBatchProgressNotification stopped = finalProgress();
        assertThat(stopped.getStatus()).isEqualTo(MetadataFetchTaskStatus.ERROR.name());
        assertThat(stopped.getMessage()).startsWith("DRY RUN — Storage is not responding: " + stuck
                + " file checks are stuck waiting on it, so the scan stopped. 0 missing (tagged 'File Not Found'), "
                + "0 restored, " + stuck + " unresolved, " + stuck + " checked in ");
        assertThat(stopped.getCompleted()).isEqualTo(stuck);
        assertThat(stopped.getTotal()).isEqualTo(dead.size());

        // No more threads are put at risk while they are stuck, not even for books on healthy storage.
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> service.scan(request(healthy, 4, true), "task-5"));
        assertThat(finalProgress().getStatus()).isEqualTo(MetadataFetchTaskStatus.ERROR.name());
        assertThat(finalProgress().getMessage()).contains("Storage is not responding");
        assertThat(calls.get()).isEqualTo(stuck);

        // Once the share answers again the stuck threads return, and scans check again.
        shareAnswers.countDown();
        awaitNoStrandedChecks(service);
        service.scan(request(healthy, 4, true), "task-6");
        assertThat(finalProgress().getStatus()).isEqualTo(MetadataFetchTaskStatus.COMPLETED.name());
        assertThat(calls.get()).isEqualTo(stuck + healthy.size());
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
