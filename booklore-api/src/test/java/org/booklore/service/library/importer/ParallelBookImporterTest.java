package org.booklore.service.library.importer;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.booklore.model.FileProcessResult;
import org.booklore.model.dto.Book;
import org.booklore.model.dto.settings.LibraryFile;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.entity.LibraryPathEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.model.enums.FileProcessStatus;
import org.booklore.repository.BookAdditionalFileRepository;
import org.booklore.repository.BookRepository;
import org.booklore.repository.LibraryRepository;
import org.booklore.service.event.BookEventBroadcaster;
import org.booklore.service.fileprocessor.BookFileProcessor;
import org.booklore.service.fileprocessor.BookFileProcessorRegistry;
import org.booklore.service.fileprocessor.PreparableBookFileProcessor;
import org.booklore.service.fileprocessor.prepare.FileFacts;
import org.booklore.service.fileprocessor.prepare.PersistedBook;
import org.booklore.service.fileprocessor.prepare.PrepareRequest;
import org.booklore.service.fileprocessor.prepare.PreparedBookFile;
import org.booklore.service.kobo.KoboAutoShelfService;
import org.booklore.service.metadata.extractor.AudiobookMetadataExtractor;
import org.booklore.service.metadata.extractor.MetadataExtractorFactory;
import org.booklore.service.metadata.sidecar.SidecarMetadataWriter;
import org.booklore.util.FileService;
import org.booklore.util.cover.CropPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The importer with a real {@link ImportExecutor} and a fake preparable processor whose reads take random time; the
 * database side is mocked. Additional formats are read from real files, as the importer reads them itself.
 */
class ParallelBookImporterTest {

    private static final long MIB = 1024 * 1024;

    @TempDir
    Path libraryRoot;

    private final BookFileProcessorRegistry processorRegistry = mock(BookFileProcessorRegistry.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final LibraryRepository libraryRepository = mock(LibraryRepository.class);
    private final BookRepository bookRepository = mock(BookRepository.class);
    private final BookAdditionalFileRepository bookAdditionalFileRepository = mock(BookAdditionalFileRepository.class);
    private final BookEventBroadcaster bookEventBroadcaster = mock(BookEventBroadcaster.class);
    private final KoboAutoShelfService koboAutoShelfService = mock(KoboAutoShelfService.class);
    private final FileService fileService = mock(FileService.class);
    private final SidecarMetadataWriter sidecarMetadataWriter = mock(SidecarMetadataWriter.class);
    private final MetadataExtractorFactory metadataExtractorFactory = mock(MetadataExtractorFactory.class);
    private final AudiobookMetadataExtractor audiobookMetadataExtractor = mock(AudiobookMetadataExtractor.class);
    private final PreparableBookFileProcessor processor = mock(PreparableBookFileProcessor.class);

    private final List<ImportExecutor> executors = new ArrayList<>();
    /** Releases every preparation wedged by {@link #wedge()}; opened after each test at the latest. */
    private final CountDownLatch release = new CountDownLatch(1);
    private final AtomicLong lastBookId = new AtomicLong();
    private final AtomicInteger prepareCalls = new AtomicInteger();
    private final List<Thread> persistThreads = Collections.synchronizedList(new ArrayList<>());
    /** Primary files whose preparation never returns until {@link #release}, as a read wedged on a share. */
    private final Set<String> wedgedFiles = ConcurrentHashMap.newKeySet();
    private final Set<String> unreadableFiles = ConcurrentHashMap.newKeySet();
    private final Set<String> unsavableFiles = ConcurrentHashMap.newKeySet();
    private volatile int maxPrepareMillis = 0;

    private final Logger importerLog = (Logger) LoggerFactory.getLogger(ParallelBookImporter.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    private LibraryEntity library;
    private LibraryPathEntity libraryPath;

    @BeforeEach
    void setUp() throws Exception {
        library = LibraryEntity.builder().id(1L).name("Books")
                .formatPriority(new ArrayList<>(List.of(BookFileType.EPUB, BookFileType.PDF, BookFileType.CBX, BookFileType.FB2,
                        BookFileType.MOBI, BookFileType.AZW3, BookFileType.AUDIOBOOK)))
                .build();
        libraryPath = LibraryPathEntity.builder().id(2L).library(library).path(libraryRoot.toString()).build();
        library.setLibraryPaths(List.of(libraryPath));
        when(libraryRepository.findById(1L)).thenReturn(Optional.of(library));
        when(fileService.currentCropPolicy()).thenReturn(CropPolicy.NONE);
        when(processorRegistry.getProcessorOrThrow(any())).thenReturn(processor);
        when(transactionManager.getTransaction(any())).thenAnswer(inv -> new SimpleTransactionStatus());
        when(processor.prepare(any(), any())).thenAnswer(inv -> prepare(inv.getArgument(0)));
        when(processor.persistPrepared(any(), any())).thenAnswer(inv -> persist(inv.getArgument(0), inv.getArgument(1), null));

        logs.start();
        importerLog.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        release.countDown();
        executors.forEach(ImportExecutor::shutdown);
        importerLog.detachAppender(logs);
    }

    // ========== the fake processor ==========

    private PreparedBookFile prepare(PrepareRequest request) throws Exception {
        prepareCalls.incrementAndGet();
        if (wedgedFiles.contains(request.fileName())) {
            wedge();
        }
        if (unreadableFiles.contains(request.fileName())) {
            throw new IllegalStateException("cannot read " + request.fileName());
        }
        if (maxPrepareMillis > 0) {
            Thread.sleep(ThreadLocalRandom.current().nextInt(maxPrepareMillis + 1));
        }
        return new PreparedBookFile(request, new FileFacts("hash of " + request.fileName(), 1L), null, null, null);
    }

    private PersistedBook persist(LibraryFile file, PreparedBookFile prepared, SidecarMetadataWriter.SidecarJob sidecarJob) {
        persistThreads.add(Thread.currentThread());
        // As the real persistPrepared checks
        assertThat(prepared.request().path()).isEqualTo(file.getFullPath());
        if (unsavableFiles.contains(file.getFileName())) {
            throw new IllegalStateException("cannot save " + file.getFileName());
        }
        long id = lastBookId.incrementAndGet();
        return new PersistedBook(Book.builder().id(id).build(), id, true, file.getFileName(), file.getBookFileType(), sidecarJob);
    }

    /** Blocks the way a thread stuck in a CIFS read does: interrupts do not end it, only {@link #release} does. */
    private Object wedge() {
        boolean interrupted = false;
        while (true) {
            try {
                release.await();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        return null;
    }

    // ========== helpers ==========

    private ImportExecutor executor(int io, int window, Duration timeout, int maxStuck) {
        ImportExecutor executor = new ImportExecutor(new ImportSettings(true, io, window, timeout, maxStuck, 2, 64 * MIB));
        executors.add(executor);
        return executor;
    }

    private ImportExecutor executor(int io, int window) {
        return executor(io, window, Duration.ofSeconds(30), 16);
    }

    private ParallelBookImporter importer(ImportExecutor executor) {
        return new ParallelBookImporter(executor, processorRegistry, transactionManager, libraryRepository, bookRepository,
                bookAdditionalFileRepository, bookEventBroadcaster, koboAutoShelfService, fileService, sidecarMetadataWriter,
                metadataExtractorFactory, audiobookMetadataExtractor);
    }

    private LibraryFile file(String fileName, BookFileType type) {
        return LibraryFile.builder()
                .libraryEntity(library)
                .libraryPathEntity(libraryPath)
                .fileSubPath("")
                .fileName(fileName)
                .bookFileType(type)
                .build();
    }

    private LibraryFile fileOnDisk(String fileName, BookFileType type) throws IOException {
        Files.writeString(libraryRoot.resolve(fileName), "the contents of " + fileName);
        return file(fileName, type);
    }

    private LibraryFile epub(String name) {
        return file(name + ".epub", BookFileType.EPUB);
    }

    @SafeVarargs
    private static Map<String, List<LibraryFile>> groups(List<LibraryFile>... groups) {
        Map<String, List<LibraryFile>> map = new LinkedHashMap<>();
        for (List<LibraryFile> group : groups) {
            map.put(group.getFirst().getFileName(), group);
        }
        return map;
    }

    private static BookFileEntity attachedFile(String fileName) {
        return argThat((BookFileEntity file) -> file != null && file.getFileName().equals(fileName));
    }

    private List<String> logged(Level level) {
        synchronized (logs) {
            return logs.list.stream()
                    .filter(event -> event.getLevel() == level)
                    .map(ILoggingEvent::getFormattedMessage)
                    .toList();
        }
    }

    private static void awaitUntil(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime()).as(what).isLessThan(deadline);
            Thread.sleep(5);
        }
    }

    // ========== tests ==========

    @Test
    void savesEveryGroupInTheOrderItCameOnTheCallingThread() throws Exception {
        maxPrepareMillis = 15;
        List<List<LibraryFile>> groupList = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            String name = "book-%02d".formatted(i);
            groupList.add(i % 3 == 0
                    ? List.of(epub(name), fileOnDisk(name + ".pdf", BookFileType.PDF))
                    : List.of(epub(name)));
        }
        @SuppressWarnings("unchecked")
        Map<String, List<LibraryFile>> groups = groups(groupList.toArray(List[]::new));

        ImportSummary summary = importer(executor(4, 8)).importGroups(groups, library);

        assertThat(summary.added()).isEqualTo(30);
        assertThat(summary.failed() + summary.timedOut() + summary.notStarted() + summary.attachmentsSkipped()).isZero();
        assertThat(summary.legacy()).isFalse();
        assertThat(persistThreads).hasSize(30).containsOnly(Thread.currentThread());

        InOrder inOrder = inOrder(processor, bookEventBroadcaster, koboAutoShelfService, bookAdditionalFileRepository);
        for (int i = 0; i < 30; i++) {
            long bookId = i + 1;
            List<LibraryFile> group = groupList.get(i);
            inOrder.verify(processor).persistPrepared(eq(group.getFirst()), any());
            inOrder.verify(bookEventBroadcaster).broadcastBookAddEvent(argThat(book -> book.getId() == bookId));
            inOrder.verify(koboAutoShelfService).autoAddBookToKoboShelves(bookId);
            if (group.size() > 1) {
                inOrder.verify(bookAdditionalFileRepository).save(attachedFile(group.get(1).getFileName()));
            }
        }
    }

    @Test
    void aGroupWhosePrimaryFileTimesOutIsSkippedAndTheOthersAreSaved() {
        wedgedFiles.add("two.epub");

        ImportSummary summary = importer(executor(4, 8, Duration.ofMillis(300), 16))
                .importGroups(groups(List.of(epub("one")), List.of(epub("two")), List.of(epub("three"))), library);

        assertThat(summary.added()).isEqualTo(2);
        assertThat(summary.timedOut()).isEqualTo(1);
        assertThat(summary.skippedForRetry()).isEqualTo(1);
        verify(processor).persistPrepared(eq(epub("one")), any());
        verify(processor).persistPrepared(eq(epub("three")), any());
        verify(processor, never()).persistPrepared(eq(epub("two")), any());
        assertThat(logged(Level.WARN)).singleElement().asString()
                .startsWith("Skipped " + libraryRoot.resolve("two.epub") + ": reading from storage did not finish within ")
                .endsWith("it will be retried on the next scan (1 import threads stuck)");
    }

    @Test
    void aPrimaryFileThatCannotBeReadFailsItsGroupAndSkipsItsAdditionalFormats() throws Exception {
        unreadableFiles.add("broken.epub");
        LibraryFile first = fileOnDisk("broken.m4b", BookFileType.AUDIOBOOK);
        LibraryFile second = fileOnDisk("broken-part-2.m4b", BookFileType.AUDIOBOOK);
        // An audiobook added to an ebook makes a cover, so reading one shows up as a call to the extractor. If the
        // first one starts before its book has failed, it takes long enough that the second one cannot.
        when(audiobookMetadataExtractor.extractCover(any())).thenAnswer(inv -> {
            Thread.sleep(200);
            return null;
        });

        ImportSummary summary = importer(executor(1, 4))
                .importGroups(groups(List.of(epub("broken"), first, second), List.of(epub("fine"))), library);

        assertThat(summary.failed()).isEqualTo(1);
        assertThat(summary.added()).isEqualTo(1);
        assertThat(summary.attachmentsSkipped()).isZero();
        verify(audiobookMetadataExtractor, never()).extractCover(new File(second.getFullPath().toString()));
        verify(bookAdditionalFileRepository, never()).save(any());
        assertThat(logged(Level.ERROR)).containsExactly(
                "Failed to process file group [broken.epub, broken.m4b, broken-part-2.m4b]: cannot read broken.epub");
    }

    @Test
    void anAdditionalFormatThatCannotBeReadIsLeftForTheNextRescanAndTheOthersAreAttached() throws Exception {
        LibraryFile missing = file("book.pdf", BookFileType.PDF);
        LibraryFile present = fileOnDisk("book.fb2", BookFileType.FB2);

        ImportSummary summary = importer(executor(4, 8))
                .importGroups(groups(List.of(epub("book"), missing, present), List.of(epub("next"))), library);

        assertThat(summary.added()).isEqualTo(2);
        assertThat(summary.attachmentsSkipped()).isEqualTo(1);
        verify(bookAdditionalFileRepository, never()).save(attachedFile("book.pdf"));
        verify(bookAdditionalFileRepository).save(argThat((BookFileEntity f) -> f != null && f.getFileName().equals("book.fb2")
                && f.getBookType() == BookFileType.FB2 && f.getCurrentHash() != null && f.getFileSizeKb() == 0L));
        assertThat(logged(Level.WARN)).singleElement().asString()
                .startsWith("Skipped additional format book.pdf: Failed to compute partial MD5 hash for: ")
                .endsWith("; it will be attached by the next rescan");
    }

    @Test
    void aGroupWhoseAdditionalFormatDoesNotFinishReadingInTimeIsSkippedWholeForTheNextScan() throws Exception {
        // Saved without it, dune.m4b would be new on the next scan, and at a library path's root a rescan makes it a
        // book of its own instead of attaching it
        LibraryFile audiobook = fileOnDisk("dune.m4b", BookFileType.AUDIOBOOK);
        // An audiobook added to an ebook makes a cover, read from the audio file; that read wedges
        when(audiobookMetadataExtractor.extractCover(any())).thenAnswer(inv -> wedge());

        ImportSummary summary = importer(executor(4, 8, Duration.ofMillis(300), 16))
                .importGroups(groups(List.of(epub("dune"), audiobook), List.of(epub("next"))), library);

        assertThat(summary.added()).isEqualTo(1);
        assertThat(summary.timedOut()).isEqualTo(1);
        assertThat(summary.skippedForRetry()).isEqualTo(1);
        assertThat(summary.attachmentsSkipped()).isZero();
        verify(processor, never()).persistPrepared(eq(epub("dune")), any());
        verify(processor).persistPrepared(eq(epub("next")), any());
        verify(bookAdditionalFileRepository, never()).save(any());
        assertThat(logged(Level.WARN)).singleElement().asString()
                .startsWith("Skipped [dune.epub, dune.m4b]: its additional format dune.m4b was not read (reading from storage "
                        + "did not finish within ")
                .endsWith("it will be retried on the next scan (1 import threads stuck)");
    }

    @Test
    void aFailingBroadcastOrKoboShelfLeavesTheRestOfTheGroupAlone() throws Exception {
        doThrow(new IllegalStateException("no websocket")).when(bookEventBroadcaster).broadcastBookAddEvent(argThat(book -> book.getId() == 1L));
        doThrow(new IllegalStateException("no shelf")).when(koboAutoShelfService).autoAddBookToKoboShelves(2L);

        ImportSummary summary = importer(executor(4, 8)).importGroups(groups(
                List.of(epub("one"), fileOnDisk("one.pdf", BookFileType.PDF)),
                List.of(epub("two"), fileOnDisk("two.pdf", BookFileType.PDF))), library);

        assertThat(summary.added()).isEqualTo(2);
        verify(koboAutoShelfService).autoAddBookToKoboShelves(1L);
        verify(bookAdditionalFileRepository).save(attachedFile("one.pdf"));
        verify(bookAdditionalFileRepository).save(attachedFile("two.pdf"));
        assertThat(logged(Level.ERROR)).containsExactly(
                "Failed to broadcast new book one.epub: no websocket",
                "Failed to add book two.epub to Kobo shelves: no shelf");
    }

    @Test
    void twentySavesFailingInARowStopTheScan() {
        List<List<LibraryFile>> groupList = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            String name = "book-%02d".formatted(i);
            groupList.add(List.of(epub(name)));
            // One success in between starts the count again
            if (i != 19) {
                unsavableFiles.add(name + ".epub");
            }
        }
        @SuppressWarnings("unchecked")
        Map<String, List<LibraryFile>> groups = groups(groupList.toArray(List[]::new));

        ImportSummary summary = importer(executor(2, 4)).importGroups(groups, library);

        assertThat(summary.stoppedDatabaseErrors()).isTrue();
        assertThat(summary.added()).isEqualTo(1);
        assertThat(summary.failed()).isEqualTo(39);
        assertThat(summary.notStarted()).isEqualTo(10);
        verify(processor, times(40)).persistPrepared(any(), any());
        assertThat(logged(Level.ERROR)).filteredOn(line -> line.startsWith("Stopped importing")).singleElement().asString()
                .isEqualTo("Stopped importing into library 'Books' after 20 books in a row could not be saved; 10 new books "
                        + "were not started and will be picked up by the next scan.");
    }

    @Test
    void withStorageAlreadyStuckNothingIsReadOrSaved() throws Exception {
        ImportExecutor executor = executor(2, 4, Duration.ofMillis(100), 1);
        executor.submit("wedged elsewhere", ctx -> wedge(), () -> false);
        awaitUntil("the wedged task to be given up on", executor::breakerOpen);

        ImportSummary summary = importer(executor).importGroups(groups(List.of(epub("one")), List.of(epub("two"))), library);

        assertThat(summary.stoppedStorageStuck()).isTrue();
        assertThat(summary.notStarted()).isEqualTo(2);
        assertThat(summary.added()).isZero();
        verify(processorRegistry, never()).getProcessorOrThrow(any());
        verify(processor, never()).prepare(any(), any());
        assertThat(logged(Level.ERROR)).containsExactly("Storage for library 'Books' is not responding: 1 import threads are "
                + "stuck; 2 new books were not started and will be picked up by the next scan. Check the share, or restart "
                + "Booklore if it stays stuck.");
    }

    @Test
    void aProcessorThatCannotPrepareFilesSavesItsBookThroughProcessFileOnTheCallingThread() throws Exception {
        BookFileProcessor plain = mock(BookFileProcessor.class);
        when(processorRegistry.getProcessorOrThrow(BookFileType.EPUB)).thenReturn(plain);
        LibraryFile primary = epub("plain");
        AtomicReference<Thread> processThread = new AtomicReference<>();
        when(plain.processFile(primary)).thenAnswer(inv -> {
            processThread.set(Thread.currentThread());
            return new FileProcessResult(Book.builder().id(7L).build(), FileProcessStatus.NEW);
        });

        ImportSummary summary = importer(executor(4, 8)).importGroups(groups(
                List.of(primary, fileOnDisk("plain.pdf", BookFileType.PDF)),
                List.of(epub("nothing"))), library);

        assertThat(processThread.get()).isSameAs(Thread.currentThread());
        assertThat(summary.added()).isEqualTo(1);
        assertThat(summary.failed()).isEqualTo(1);
        verify(bookEventBroadcaster).broadcastBookAddEvent(argThat(book -> book.getId() == 7L));
        verify(koboAutoShelfService).autoAddBookToKoboShelves(7L);
        verify(bookRepository).getReferenceById(7L);
        verify(bookAdditionalFileRepository).save(attachedFile("plain.pdf"));
        verify(processor, never()).prepare(any(), any());
        assertThat(logged(Level.WARN)).containsExactly("Failed to process primary file: nothing.epub");
    }

    @Test
    void theSidecarIsWrittenOnAnImportThreadOnceTheBookIsSavedAndTheImportWaitsForIt() {
        SidecarMetadataWriter.SidecarJob job = new SidecarMetadataWriter.SidecarJob(1L, libraryRoot.resolve("book.epub"),
                libraryRoot.resolve("book.metadata.json"), "{}", null, null);
        doAnswer(inv -> persist(inv.getArgument(0), inv.getArgument(1), job)).when(processor).persistPrepared(any(), any());
        AtomicReference<Thread> writer = new AtomicReference<>();
        AtomicBoolean written = new AtomicBoolean();
        doAnswer(inv -> {
            Thread.sleep(300);
            writer.set(Thread.currentThread());
            written.set(true);
            return null;
        }).when(sidecarMetadataWriter).execute(job);

        ImportSummary summary = importer(executor(4, 8)).importGroups(groups(List.of(epub("book"))), library);

        assertThat(summary.added()).isEqualTo(1);
        assertThat(written.get()).isTrue();
        assertThat(writer.get().getName()).startsWith("book-import-io-");
        InOrder inOrder = inOrder(processor, transactionManager, sidecarMetadataWriter);
        inOrder.verify(processor).persistPrepared(any(), any());
        inOrder.verify(transactionManager).commit(any());
        inOrder.verify(sidecarMetadataWriter).execute(job);
    }

    @Test
    void whileABookIsBeingSavedNoMoreThanTheWindowOfGroupsIsPlanned() throws Exception {
        CountDownLatch saving = new CountDownLatch(1);
        CountDownLatch mayFinishSaving = new CountDownLatch(1);
        doAnswer(inv -> {
            saving.countDown();
            mayFinishSaving.await();
            return persist(inv.getArgument(0), inv.getArgument(1), null);
        }).when(processor).persistPrepared(eq(epub("book-00")), any());
        List<List<LibraryFile>> groupList = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            groupList.add(List.of(epub("book-%02d".formatted(i))));
        }
        @SuppressWarnings("unchecked")
        Map<String, List<LibraryFile>> groups = groups(groupList.toArray(List[]::new));
        ParallelBookImporter importer = importer(executor(2, 4));
        AtomicReference<ImportSummary> summary = new AtomicReference<>();

        Thread scan = Thread.ofPlatform().start(() -> summary.set(importer.importGroups(groups, library)));
        try {
            assertThat(saving.await(5, TimeUnit.SECONDS)).isTrue();
            awaitUntil("the window to be read", () -> prepareCalls.get() == 4);
            Thread.sleep(200);
            assertThat(prepareCalls.get()).isEqualTo(4);
            verify(processorRegistry, times(4)).getProcessorOrThrow(any());
        } finally {
            mayFinishSaving.countDown();
            scan.join(TimeUnit.SECONDS.toMillis(10));
        }
        assertThat(summary.get().added()).isEqualTo(20);
    }

    @Test
    void anInterruptedImportStopsAtOnceAndKeepsTheInterrupt() throws Exception {
        wedgedFiles.add("two.epub");
        ParallelBookImporter importer = importer(executor(4, 8));
        AtomicReference<ImportSummary> summary = new AtomicReference<>();
        AtomicReference<Boolean> stillInterrupted = new AtomicReference<>();

        Thread scan = Thread.ofPlatform().start(() -> {
            summary.set(importer.importGroups(groups(List.of(epub("one")), List.of(epub("two")), List.of(epub("three"))), library));
            stillInterrupted.set(Thread.currentThread().isInterrupted());
        });
        awaitUntil("the first group to be saved", () -> persistThreads.size() == 1);
        scan.interrupt();
        scan.join(TimeUnit.SECONDS.toMillis(5));

        assertThat(scan.isAlive()).isFalse();
        assertThat(summary.get().interrupted()).isTrue();
        assertThat(summary.get().added()).isEqualTo(1);
        assertThat(summary.get().notStarted()).isEqualTo(2);
        assertThat(stillInterrupted.get()).isTrue();
    }

    @Test
    void aScanOnAVirtualThreadSavesOnItWhileTheFilesAreReadOnPlatformThreads() throws Exception {
        maxPrepareMillis = 5;
        List<Thread> readers = Collections.synchronizedList(new ArrayList<>());
        doAnswer(inv -> {
            readers.add(Thread.currentThread());
            return prepare(inv.getArgument(0));
        }).when(processor).prepare(any(), any());
        ParallelBookImporter importer = importer(executor(4, 8));
        AtomicReference<ImportSummary> summary = new AtomicReference<>();

        Thread scan = Thread.ofVirtual().start(() -> summary.set(importer.importGroups(
                groups(List.of(epub("one")), List.of(epub("two")), List.of(epub("three"))), library)));
        scan.join(TimeUnit.SECONDS.toMillis(10));

        assertThat(summary.get().added()).isEqualTo(3);
        assertThat(persistThreads).containsOnly(scan);
        assertThat(readers).hasSize(3).allSatisfy(reader -> assertThat(reader.isVirtual()).isFalse());
    }
}
