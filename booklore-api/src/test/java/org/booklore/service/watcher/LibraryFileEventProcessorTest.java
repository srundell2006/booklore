package org.booklore.service.watcher;

import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.entity.LibraryPathEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.model.enums.LibraryOrganizationMode;
import org.booklore.repository.BookRepository;
import org.booklore.repository.LibraryRepository;
import org.booklore.service.file.FileFingerprint;
import org.booklore.service.library.LibraryProcessingService;
import org.booklore.util.FileUtils;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.MockitoAnnotations;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LibraryFileEventProcessorTest {

    @Mock private LibraryRepository libraryRepository;
    @Mock private BookRepository bookRepository;
    @Mock private BookFileTransactionalHandler bookFileTransactionalHandler;
    @Mock private BookFilePersistenceService bookFilePersistenceService;
    @Mock private LibraryProcessingService libraryProcessingService;
    @Mock private PendingDeletionPool pendingDeletionPool;

    private LibraryFileEventProcessor processor;

    private AutoCloseable mocks;

    private LibraryEntity library;
    private LibraryPathEntity libraryPath;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        mocks = MockitoAnnotations.openMocks(this);
        processor = new LibraryFileEventProcessor(
                libraryRepository, bookRepository, bookFileTransactionalHandler,
                bookFilePersistenceService, libraryProcessingService, pendingDeletionPool);

        libraryPath = new LibraryPathEntity();
        libraryPath.setId(1L);
        libraryPath.setPath(tempDir.toString());

        library = LibraryEntity.builder()
                .id(1L)
                .name("Test Library")
                .libraryPaths(List.of(libraryPath))
                .organizationMode(LibraryOrganizationMode.AUTO_DETECT)
                .build();

        when(libraryRepository.findById(1L)).thenReturn(Optional.of(library));
        when(bookFilePersistenceService.findMatchingLibraryPath(eq(library), any(Path.class)))
                .thenReturn(tempDir.toString());
        when(bookFilePersistenceService.getLibraryPathEntityForFile(eq(library), eq(tempDir.toString())))
                .thenReturn(libraryPath);

        // Start the event processing thread (normally done by @PostConstruct)
        processor.init();
    }

    @AfterEach
    void tearDown() throws Exception {
        processor.shutdown();
        mocks.close();
    }

    /** Well past the folder's debounce, which a test that sees a folder handled waits out first. */
    private static void waitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }

    @Test
    void handlesEventsOnAPlatformDaemonThread() throws Exception {
        CompletableFuture<Thread> handledOn = new CompletableFuture<>();
        when(libraryRepository.findById(1L)).thenAnswer(inv -> {
            handledOn.complete(Thread.currentThread());
            return Optional.of(library);
        });

        processor.processEvent(StandardWatchEventKinds.ENTRY_DELETE, 1L, tempDir.resolve("notes.txt"), false);

        Thread handler = handledOn.get(10, TimeUnit.SECONDS);
        assertThat(handler.isVirtual()).isFalse();
        assertThat(handler.isDaemon()).isTrue();
        assertThat(handler.getName()).isEqualTo("library-file-events");
    }

    @Nested
    class HasPendingEventsForPaths {

        @Test
        void returnsFalseWhenNothingPending() {
            assertThat(processor.hasPendingEventsForPaths(Set.of(tempDir))).isFalse();
        }

        @Test
        void delegatesToPendingDeletionPool() {
            when(pendingDeletionPool.hasPendingForPaths(any())).thenReturn(true);

            assertThat(processor.hasPendingEventsForPaths(Set.of(tempDir))).isTrue();
        }

        @Test
        void countsAFileWaitingAsACopyOfAStoredFile() throws Exception {
            processor.setCopySourceWaitMs(60000);
            Path copy = Files.writeString(tempDir.resolve("copy.epub"), "content");
            Files.setLastModifiedTime(copy, FileTime.from(Instant.now().minusSeconds(60)));
            when(bookFileTransactionalHandler.handleNewBookFileUnlessCopy(1L, copy))
                    .thenReturn(Optional.of(new BookFileTransactionalHandler.CopyOfAStoredFile(7L, "hash")));

            processor.processEvent(StandardWatchEventKinds.ENTRY_CREATE, 1L, copy, false);
            waitUntil(() -> processor.waitsAsCopy(copy));

            // An in-app move waits for it: handed back while the move has moved a book's file but not yet stored its
            // new path, it would find that file gone and take the book
            assertThat(processor.hasPendingEventsForPaths(Set.of(tempDir))).isTrue();
            assertThat(processor.hasPendingEventsForPaths(Set.of(tempDir.resolve("elsewhere")))).isFalse();
        }

        @Test
        void countsANewFileStillBeingWritten() throws Exception {
            Path file = Files.writeString(tempDir.resolve("arriving.epub"), "content");

            processor.processEvent(StandardWatchEventKinds.ENTRY_CREATE, 1L, file, false);
            Thread.sleep(500);

            verify(bookFileTransactionalHandler, never()).handleNewBookFileUnlessCopy(anyLong(), any());
            assertThat(processor.hasPendingEventsForPaths(Set.of(tempDir))).isTrue();
            verify(bookFileTransactionalHandler, timeout(8000)).handleNewBookFileUnlessCopy(1L, file);
            waitUntil(() -> !processor.hasPendingEventsForPaths(Set.of(tempDir)));
        }
    }

    @Nested
    class ProcessEventDebouncing {

        @Test
        void createCancelsExistingDelete() throws Exception {
            Path file = tempDir.resolve("test.epub");
            Files.writeString(file, "content");

            // Schedule a DELETE
            processor.processEvent(StandardWatchEventKinds.ENTRY_DELETE, 1L, file, false);

            // Immediately follow with CREATE (simulates quick rename)
            processor.processEvent(StandardWatchEventKinds.ENTRY_CREATE, 1L, file, false);

            // The DELETE should have been cancelled. Wait a bit for debounce to settle.
            Thread.sleep(1000);

            // The DELETE event should not have been processed
            verify(bookFilePersistenceService, never()).findBookFileByLibraryPathSubPathAndFileName(anyLong(), anyString(), anyString());
        }

        @Test
        void directoryCreateScheduledWithDelay() throws IOException {
            Path folder = tempDir.resolve("newFolder");
            Files.createDirectory(folder);
            Files.writeString(folder.resolve("test.epub"), "content");

            processor.processEvent(StandardWatchEventKinds.ENTRY_CREATE, 1L, folder, true);

            // Should not process immediately due to folder debounce
            verify(bookFileTransactionalHandler, never()).handleNewBookFile(anyLong(), any());
            verify(bookFileTransactionalHandler, never()).handleNewBookFileUnlessCopy(anyLong(), any());
        }

        @Test
        void fileInsidePendingFolderResetsFolderTimer() throws IOException {
            Path folder = tempDir.resolve("audioFolder");
            Files.createDirectory(folder);

            // Trigger folder create first
            processor.processEvent(StandardWatchEventKinds.ENTRY_CREATE, 1L, folder, true);

            // Then file inside it
            Path file = folder.resolve("track01.m4b");
            Files.writeString(file, "audio content");
            processor.processEvent(StandardWatchEventKinds.ENTRY_CREATE, 1L, file, false);

            // The folder debounce should have been reset, not processed yet
            verify(bookFileTransactionalHandler, never()).handleNewFolderAudiobook(anyLong(), any());
        }

        @Test
        void nonBookFileEventsIgnored() throws Exception {
            Path textFile = tempDir.resolve("readme.txt");
            Files.writeString(textFile, "hello");

            // Queue a modify event for a non-book file
            processor.processEvent(StandardWatchEventKinds.ENTRY_MODIFY, 1L, textFile, false);

            // Wait for processing
            Thread.sleep(200);

            verify(bookFileTransactionalHandler, never()).handleNewBookFile(anyLong(), any());
            verify(bookFileTransactionalHandler, never()).handleNewBookFileUnlessCopy(anyLong(), any());
        }
    }

    @Nested
    class FolderCreateHandling {

        @Test
        void bookPerFileMode_processesFilesIndividually() throws Exception {
            library.setOrganizationMode(LibraryOrganizationMode.BOOK_PER_FILE);

            Path folder = tempDir.resolve("books");
            Files.createDirectory(folder);
            Files.writeString(folder.resolve("book1.epub"), "content1");
            Files.writeString(folder.resolve("book2.pdf"), "content2");

            processor.processEvent(StandardWatchEventKinds.ENTRY_CREATE, 1L, folder, true);

            // Folder debounce is 5s, then queue processing
            Thread.sleep(8000);

            verify(bookFileTransactionalHandler, times(2)).handleNewBookFileUnlessCopy(eq(1L), any());
            verify(bookFileTransactionalHandler, never()).handleNewBookFile(anyLong(), any());
        }

        @Test
        void bookPerFileMode_ignoresFolderWithIgnoreFile() throws Exception {
            library.setOrganizationMode(LibraryOrganizationMode.BOOK_PER_FILE);

            Path folder = tempDir.resolve("ignored");
            Files.createDirectory(folder);
            Files.writeString(folder.resolve(".ignore"), "");
            Files.writeString(folder.resolve("book.epub"), "content");

            processor.processEvent(StandardWatchEventKinds.ENTRY_CREATE, 1L, folder, true);

            Thread.sleep(8000);

            verify(bookFileTransactionalHandler, never()).handleNewBookFile(anyLong(), any());
            verify(bookFileTransactionalHandler, never()).handleNewBookFileUnlessCopy(anyLong(), any());
        }

        @Test
        void bookPerFileMode_skipsFilesUnderIgnoredSubdirectory() throws Exception {
            library.setOrganizationMode(LibraryOrganizationMode.BOOK_PER_FILE);

            Path folder = tempDir.resolve("parent");
            Files.createDirectory(folder);
            Files.writeString(folder.resolve("good.epub"), "content");

            Path ignoredSub = folder.resolve("skipped");
            Files.createDirectory(ignoredSub);
            Files.writeString(ignoredSub.resolve(".ignore"), "");
            Files.writeString(ignoredSub.resolve("hidden.epub"), "content");

            processor.processEvent(StandardWatchEventKinds.ENTRY_CREATE, 1L, folder, true);

            Thread.sleep(8000);

            // Only good.epub should be processed, not hidden.epub
            verify(bookFileTransactionalHandler, times(1)).handleNewBookFileUnlessCopy(eq(1L), any());
            verify(bookFileTransactionalHandler).handleNewBookFileUnlessCopy(1L, folder.resolve("good.epub"));
        }

        @Test
        void bookPerFolderMode_emptyFolder_doesNothing() throws Exception {
            library.setOrganizationMode(LibraryOrganizationMode.BOOK_PER_FOLDER);

            Path folder = tempDir.resolve("empty");
            Files.createDirectory(folder);

            processor.processEvent(StandardWatchEventKinds.ENTRY_CREATE, 1L, folder, true);

            Thread.sleep(8000);

            verify(libraryProcessingService, never()).processLibraryFiles(any(), any());
            verify(bookFileTransactionalHandler, never()).handleNewBookFile(anyLong(), any());
            verify(bookFileTransactionalHandler, never()).handleNewBookFileUnlessCopy(anyLong(), any());
        }

        @Test
        void bookPerFolderMode_movesOnlyAFileThatWasMovedAndImportsACopy() throws Exception {
            library.setOrganizationMode(LibraryOrganizationMode.BOOK_PER_FOLDER);
            Path folder = Files.createDirectory(tempDir.resolve("arrived"));
            Path moved = Files.writeString(folder.resolve("moved.epub"), "moved content");
            Files.writeString(folder.resolve("copy.epub"), "copied content");
            when(pendingDeletionPool.matchFolderByHashes(any())).thenReturn(Optional.empty());
            when(pendingDeletionPool.matchByHash(anyString())).thenReturn(Optional.empty());
            BookFileEntity movedFrom = BookFileEntity.builder().id(100L).fileName("moved.epub").fileSubPath("before").build();
            when(bookFilePersistenceService.findMovedFile(anyString(), any(), any())).thenAnswer(inv ->
                    inv.getArgument(1, Path.class).equals(moved) ? Optional.of(movedFrom) : Optional.empty());

            processor.processEvent(StandardWatchEventKinds.ENTRY_CREATE, 1L, folder, true);

            Thread.sleep(8000);

            verify(bookFilePersistenceService).updatePathIfChanged(movedFrom, library, moved);
            verify(libraryProcessingService).processLibraryFiles(argThat(files -> files.size() == 1
                    && files.getFirst().getFileName().equals("copy.epub")), eq(library));
        }

        @Test
        void pathOutsideLibrary_skipped() throws Exception {
            Path outsideFolder = Files.createTempDirectory("outside");
            try {
                Files.writeString(outsideFolder.resolve("book.epub"), "content");

                processor.processEvent(StandardWatchEventKinds.ENTRY_CREATE, 1L, outsideFolder, true);

                Thread.sleep(8000);

                // handleEvent should skip because path is outside library
                verify(bookFileTransactionalHandler, never()).handleNewBookFile(anyLong(), any());
                verify(bookFileTransactionalHandler, never()).handleNewBookFileUnlessCopy(anyLong(), any());
            } finally {
                try (var stream = Files.walk(outsideFolder)) {
                    stream.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                    });
                }
            }
        }
    }

    @Nested
    class CopiesOfStoredFiles {

        private Path settledFile(String name) throws IOException {
            Path file = Files.writeString(tempDir.resolve(name), "content");
            // Written a while ago, so the processor does not wait for it to settle
            Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.from(java.time.Instant.now().minusSeconds(60)));
            return file;
        }

        @Test
        void aNewFileThatIsNoCopyIsHandledOnce() throws Exception {
            Path file = settledFile("new.epub");
            when(bookFileTransactionalHandler.handleNewBookFileUnlessCopy(1L, file)).thenReturn(Optional.empty());

            processor.processEvent(StandardWatchEventKinds.ENTRY_CREATE, 1L, file, false);

            verify(bookFileTransactionalHandler, timeout(3000)).handleNewBookFileUnlessCopy(1L, file);
            Thread.sleep(500);
            verify(bookFileTransactionalHandler, never()).handleNewBookFile(anyLong(), any());
        }

        @Test
        void aCopyWhoseSourceStaysIsHandedBackOnceTheWaitIsOver() throws Exception {
            processor.setCopySourceWaitMs(1500);
            Path copy = settledFile("copy.epub");
            when(bookFileTransactionalHandler.handleNewBookFileUnlessCopy(1L, copy))
                    .thenReturn(Optional.of(new BookFileTransactionalHandler.CopyOfAStoredFile(7L, "hash")));

            processor.processEvent(StandardWatchEventKinds.ENTRY_CREATE, 1L, copy, false);

            verify(bookFileTransactionalHandler, timeout(3000)).handleNewBookFileUnlessCopy(1L, copy);
            verify(bookFileTransactionalHandler, never()).handleNewBookFile(anyLong(), any());
            verify(bookFileTransactionalHandler, timeout(5000)).handleNewBookFile(1L, copy);
            Thread.sleep(2000);
            verify(bookFileTransactionalHandler, times(1)).handleNewBookFile(1L, copy);
            verify(bookFileTransactionalHandler, times(1)).handleNewBookFileUnlessCopy(1L, copy);
        }

        @Test
        void aCopyIsHandedBackAtOnceWhenItsSourcesDeletionComesIn() throws Exception {
            processor.setCopySourceWaitMs(60000);
            Path copy = settledFile("moved.epub");
            Path source = tempDir.resolve("source").resolve("moved.epub");
            // The second time, the pool holds the source's book and gives it the file
            when(bookFileTransactionalHandler.handleNewBookFileUnlessCopy(1L, copy))
                    .thenReturn(Optional.of(new BookFileTransactionalHandler.CopyOfAStoredFile(7L, "hash")))
                    .thenReturn(Optional.empty());
            BookEntity book = BookEntity.builder().id(7L).library(library).libraryPath(libraryPath).build();
            BookFileEntity stored = BookFileEntity.builder().id(70L).book(book).fileSubPath("source").fileName("moved.epub")
                    .currentHash("hash").isBookFormat(true).bookType(BookFileType.EPUB).build();
            when(bookFilePersistenceService.findBookFileByLibraryPathSubPathAndFileName(1L, "source", "moved.epub"))
                    .thenReturn(Optional.of(stored));

            processor.processEvent(StandardWatchEventKinds.ENTRY_CREATE, 1L, copy, false);
            verify(bookFileTransactionalHandler, timeout(3000)).handleNewBookFileUnlessCopy(1L, copy);
            processor.processEvent(StandardWatchEventKinds.ENTRY_DELETE, 1L, source, false);

            verify(pendingDeletionPool, timeout(3000)).addFileDeletion(eq(source), eq(1L), eq(stored), eq(book), any());
            // Then the copy is checked again, with the pool holding the source's book
            InOrder order = inOrder(pendingDeletionPool, bookFileTransactionalHandler);
            order.verify(pendingDeletionPool, timeout(3000)).addFileDeletion(eq(source), eq(1L), eq(stored), eq(book), any());
            order.verify(bookFileTransactionalHandler, timeout(3000)).handleNewBookFileUnlessCopy(1L, copy);
            verify(bookFileTransactionalHandler, times(2)).handleNewBookFileUnlessCopy(1L, copy);
            assertThat(processor.waitsAsCopy(copy)).isFalse();
            verify(bookFileTransactionalHandler, never()).handleNewBookFile(anyLong(), any());
        }

        @Test
        void aWaitingCopyIsNotHandedBackForTheDeletionOfAnotherFile() throws Exception {
            processor.setCopySourceWaitMs(60000);
            Path copy = settledFile("copy.epub");
            Path other = tempDir.resolve("other.epub");
            when(bookFileTransactionalHandler.handleNewBookFileUnlessCopy(1L, copy))
                    .thenReturn(Optional.of(new BookFileTransactionalHandler.CopyOfAStoredFile(7L, "hash")));
            BookEntity book = BookEntity.builder().id(8L).library(library).libraryPath(libraryPath).build();
            BookFileEntity stored = BookFileEntity.builder().id(80L).book(book).fileSubPath("").fileName("other.epub")
                    .currentHash("another hash").isBookFormat(true).bookType(BookFileType.EPUB).build();
            when(bookFilePersistenceService.findBookFileByLibraryPathSubPathAndFileName(1L, "", "other.epub"))
                    .thenReturn(Optional.of(stored));

            processor.processEvent(StandardWatchEventKinds.ENTRY_CREATE, 1L, copy, false);
            verify(bookFileTransactionalHandler, timeout(3000)).handleNewBookFileUnlessCopy(1L, copy);
            processor.processEvent(StandardWatchEventKinds.ENTRY_DELETE, 1L, other, false);

            verify(pendingDeletionPool, timeout(3000)).addFileDeletion(eq(other), eq(1L), eq(stored), eq(book), any());
            Thread.sleep(500);
            verify(bookFileTransactionalHandler, never()).handleNewBookFile(anyLong(), any());
            verify(bookFileTransactionalHandler, times(1)).handleNewBookFileUnlessCopy(1L, copy);
            assertThat(processor.waitsAsCopy(copy)).isTrue();
        }

        @Test
        void aCopyWaitsOnWhileFilesKeepArrivingInItsLibrary() throws Exception {
            processor.setCopySourceWaitMs(1500);
            Path copy = settledFile("copy.epub");
            when(bookFileTransactionalHandler.handleNewBookFileUnlessCopy(1L, copy))
                    .thenReturn(Optional.of(new BookFileTransactionalHandler.CopyOfAStoredFile(7L, "hash")));

            processor.processEvent(StandardWatchEventKinds.ENTRY_CREATE, 1L, copy, false);
            verify(bookFileTransactionalHandler, timeout(3000)).handleNewBookFileUnlessCopy(1L, copy);
            // Files keep arriving for twice the wait, as a large copy into the library makes them
            arriveFor(3000);

            verify(bookFileTransactionalHandler, never()).handleNewBookFile(1L, copy);
            assertThat(processor.waitsAsCopy(copy)).isTrue();
            // Once no file arrived for the wait, it is taken for a copy
            verify(bookFileTransactionalHandler, timeout(4000)).handleNewBookFile(1L, copy);
        }

        @Test
        void aCopyWaitsNoLongerThanTheLongestWaitWhileFilesKeepArriving() throws Exception {
            processor.setCopySourceWaitMs(1000);
            processor.setCopySourceMaxWaitMs(2500);
            Path copy = settledFile("copy.epub");
            when(bookFileTransactionalHandler.handleNewBookFileUnlessCopy(1L, copy))
                    .thenReturn(Optional.of(new BookFileTransactionalHandler.CopyOfAStoredFile(7L, "hash")));

            processor.processEvent(StandardWatchEventKinds.ENTRY_CREATE, 1L, copy, false);
            verify(bookFileTransactionalHandler, timeout(3000)).handleNewBookFileUnlessCopy(1L, copy);
            arriveFor(5000);

            // Taken for a copy while files were still arriving
            verify(bookFileTransactionalHandler).handleNewBookFile(1L, copy);
        }

        @Test
        void aCopyWaitsOnWhileAFileHandedOnAtTheSettlingLimitStillGrows() throws Exception {
            // A mover still writing a file whose copy outlasts the settling limit removes no source until it is done
            processor.setCopySourceWaitMs(4000);
            processor.setStabilityMaxWaitMs(2000);
            Path copy = settledFile("copy.epub");
            when(bookFileTransactionalHandler.handleNewBookFileUnlessCopy(1L, copy))
                    .thenReturn(Optional.of(new BookFileTransactionalHandler.CopyOfAStoredFile(7L, "hash")));
            processor.processEvent(StandardWatchEventKinds.ENTRY_CREATE, 1L, copy, false);
            waitUntil(() -> processor.waitsAsCopy(copy));

            Path big = Files.writeString(tempDir.resolve("Big.m4b"), "the start");
            processor.processEvent(StandardWatchEventKinds.ENTRY_CREATE, 1L, big, false);
            long until = System.currentTimeMillis() + 12000;
            while (System.currentTimeMillis() < until) {
                Files.writeString(big, " and more", StandardOpenOption.APPEND);
                Thread.sleep(500);
            }

            // Handed on half written at the limit, as a new file still is, while the copy waits for the mover
            verify(bookFileTransactionalHandler).handleNewBookFileUnlessCopy(1L, big);
            verify(bookFileTransactionalHandler, never()).handleNewBookFile(1L, copy);
            // Taken for a copy once the library is quiet
            verify(bookFileTransactionalHandler, timeout(12000)).handleNewBookFile(1L, copy);
        }

        @Test
        void aCopyWaitsOnWhileAFileThatIsNoBookFileIsStillWritten() throws Exception {
            // A mover that copies a tree writes its cover art and extras too, and removes no source until it is done
            processor.setCopySourceWaitMs(4000);
            Path copy = settledFile("copy.epub");
            when(bookFileTransactionalHandler.handleNewBookFileUnlessCopy(1L, copy))
                    .thenReturn(Optional.of(new BookFileTransactionalHandler.CopyOfAStoredFile(7L, "hash")));
            processor.processEvent(StandardWatchEventKinds.ENTRY_CREATE, 1L, copy, false);
            waitUntil(() -> processor.waitsAsCopy(copy));

            Path extra = Files.writeString(tempDir.resolve("extras.mkv"), "the start");
            processor.noteFileArrived(1L, extra);
            writeFor(extra, 12000);

            verify(bookFileTransactionalHandler, never()).handleNewBookFile(1L, copy);
            assertThat(processor.waitsAsCopy(copy)).isTrue();
            // Taken for a copy once the library is quiet
            verify(bookFileTransactionalHandler, timeout(12000)).handleNewBookFile(1L, copy);
            // The file is no book: nothing is imported from it
            verify(bookFileTransactionalHandler, never()).handleNewBookFileUnlessCopy(1L, extra);
        }

        @Test
        void aCopyWaitsOnWhileAFileThatIsNoBookFileIsStillWrittenInANewFolderThatGaveNoEventForIt() throws Exception {
            // The first file of a new folder is written before the folder is watched, so no event comes for it; the
            // folder's handling finds it still being written. The wait is longer than the folder's debounce, as the
            // production wait is.
            processor.setCopySourceWaitMs(7000);
            Path copy = settledFile("copy.epub");
            when(bookFileTransactionalHandler.handleNewBookFileUnlessCopy(1L, copy))
                    .thenReturn(Optional.of(new BookFileTransactionalHandler.CopyOfAStoredFile(7L, "hash")));
            processor.processEvent(StandardWatchEventKinds.ENTRY_CREATE, 1L, copy, false);
            waitUntil(() -> processor.waitsAsCopy(copy));

            Path folder = Files.createDirectory(tempDir.resolve("Extras"));
            Path extra = Files.writeString(folder.resolve("making of.mkv"), "the start");
            processor.processEvent(StandardWatchEventKinds.ENTRY_CREATE, 1L, folder, true);
            writeFor(extra, 15000);

            verify(bookFileTransactionalHandler, never()).handleNewBookFile(1L, copy);
            assertThat(processor.waitsAsCopy(copy)).isTrue();
            verify(bookFileTransactionalHandler, timeout(15000)).handleNewBookFile(1L, copy);
        }

        /** Appends to the file every half second for this long, as a mover writing it does. */
        private void writeFor(Path file, long millis) throws Exception {
            long until = System.currentTimeMillis() + millis;
            while (System.currentTimeMillis() < until) {
                Files.writeString(file, " and more", StandardOpenOption.APPEND);
                Thread.sleep(500);
            }
        }

        @Test
        void aFileWhoseModificationTimeIsAheadOfTheClockHoldsTheCopiesNoLongerThanAFileStillBeingWritten() throws Exception {
            // A share whose clock runs ahead of this host stamps a file it has finished with a time still to come: it
            // looks written to until then, but it no longer changes
            processor.setCopySourceWaitMs(4000);
            processor.setStabilityMaxWaitMs(2000);
            Path copy = settledFile("copy.epub");
            when(bookFileTransactionalHandler.handleNewBookFileUnlessCopy(1L, copy))
                    .thenReturn(Optional.of(new BookFileTransactionalHandler.CopyOfAStoredFile(7L, "hash")));
            processor.processEvent(StandardWatchEventKinds.ENTRY_CREATE, 1L, copy, false);
            waitUntil(() -> processor.waitsAsCopy(copy));

            Path skewed = Files.writeString(tempDir.resolve("skewed.epub"), "written");
            Files.setLastModifiedTime(skewed, FileTime.from(Instant.now().plusSeconds(120)));
            processor.processEvent(StandardWatchEventKinds.ENTRY_CREATE, 1L, skewed, false);

            // Handed on at the settling limit, as a new file still is, and the copy is taken for a copy once the
            // library is quiet, not once this host's clock reaches the file's time
            verify(bookFileTransactionalHandler, timeout(8000)).handleNewBookFileUnlessCopy(1L, skewed);
            verify(bookFileTransactionalHandler, timeout(12000)).handleNewBookFile(1L, copy);
        }

        @Test
        void aCopyWaitsWhileFilesKeepArrivingForAsLongAsALargeMoveAcrossDevicesCopies() {
            // A mover copies the whole tree before it removes any of it: hours, for a large library on a slow disk or
            // share. A copy whose wait ended before then would be a second book, and the source's removal would delete
            // the first.
            assertThat(LibraryFileEventProcessor.COPY_SOURCE_MAX_WAIT_MS).isGreaterThanOrEqualTo(TimeUnit.HOURS.toMillis(12));
        }

        @Test
        void aCopyHandedBackAndStillACopyWaitsNoLongerThanTheLongestWaitFromWhenItFirstBeganToWait() throws Exception {
            processor.setCopySourceWaitMs(1000);
            processor.setCopySourceMaxWaitMs(3000);
            Path copy = settledFile("copy.epub");
            when(bookFileTransactionalHandler.handleNewBookFileUnlessCopy(1L, copy))
                    .thenReturn(Optional.of(new BookFileTransactionalHandler.CopyOfAStoredFile(7L, "hash")));
            BookEntity book = BookEntity.builder().id(8L).library(library).libraryPath(libraryPath).build();
            BookFileEntity stored = BookFileEntity.builder().id(80L).book(book).fileSubPath("gone").fileName("other.epub")
                    .currentHash("hash").isBookFormat(true).bookType(BookFileType.EPUB).build();
            when(bookFilePersistenceService.findBookFileByLibraryPathSubPathAndFileName(eq(1L), eq("gone"), anyString()))
                    .thenReturn(Optional.of(stored));

            processor.processEvent(StandardWatchEventKinds.ENTRY_CREATE, 1L, copy, false);
            waitUntil(() -> processor.waitsAsCopy(copy));
            // Deletions of its content keep handing it back, and it is still a copy each time
            long until = System.currentTimeMillis() + 6000;
            for (int i = 0; System.currentTimeMillis() < until; i++) {
                processor.processEvent(StandardWatchEventKinds.ENTRY_DELETE, 1L, tempDir.resolve("gone").resolve("copy " + i + ".epub"), false);
                Thread.sleep(500);
            }

            // Taken for a copy at the longest wait from when it first began to wait, while they still came in
            verify(bookFileTransactionalHandler).handleNewBookFile(1L, copy);
        }

        @Test
        void aCopyIsHandedBackOnceItsWaitIsOverWhenTheClockStepsBackWhileItWaits() throws Exception {
            processor.setCopySourceWaitMs(1500);
            AtomicLong steppedBack = new AtomicLong();
            processor.setClock(() -> TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) - steppedBack.get());
            Path copy = settledFile("copy.epub");
            when(bookFileTransactionalHandler.handleNewBookFileUnlessCopy(1L, copy))
                    .thenReturn(Optional.of(new BookFileTransactionalHandler.CopyOfAStoredFile(7L, "hash")));

            processor.processEvent(StandardWatchEventKinds.ENTRY_CREATE, 1L, copy, false);
            waitUntil(() -> processor.waitsAsCopy(copy));
            // The clock the wait is timed on steps back, as the wall clock can: its timer fires before the wait is
            // over by that clock, and must wait again for the rest rather than leave the copy waiting for good
            steppedBack.set(2000);

            verify(bookFileTransactionalHandler, timeout(8000)).handleNewBookFile(1L, copy);
            waitUntil(() -> !processor.hasPendingEventsForPaths(Set.of(tempDir)));
            verify(bookFileTransactionalHandler, times(1)).handleNewBookFile(1L, copy);
        }

        /** New files arrive in the library, one every few hundred milliseconds, for this long. */
        private void arriveFor(long millis) throws Exception {
            long until = System.currentTimeMillis() + millis;
            for (int i = 0; System.currentTimeMillis() < until; i++) {
                processor.processEvent(StandardWatchEventKinds.ENTRY_CREATE, 1L, settledFile("arrived " + i + ".epub"), false);
                Thread.sleep(300);
            }
        }
    }

    /**
     * A new folder's files, or the folder audiobook it is, that copy stored files still there wait for those files to
     * go, as a single new file does: a mover that copies a folder into a new one and removes the source after moves
     * its books, and a copy is still a copy once the wait is over.
     */
    @Nested
    class CopiesInNewFolders {

        private Path settled(Path file, String content) throws IOException {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content);
            Files.setLastModifiedTime(file, FileTime.from(Instant.now().minusSeconds(60)));
            return file;
        }

        /** What the watcher sees when a stored file of book 7, with this content, is removed. */
        private void storedFileRemoved(String subPath, String fileName, String hash) {
            BookEntity book = BookEntity.builder().id(7L).library(library).libraryPath(libraryPath).build();
            BookFileEntity stored = BookFileEntity.builder().id(70L).book(book).fileSubPath(subPath).fileName(fileName)
                    .currentHash(hash).isBookFormat(true).bookType(BookFileType.EPUB).build();
            when(bookFilePersistenceService.findBookFileByLibraryPathSubPathAndFileName(1L, subPath, fileName))
                    .thenReturn(Optional.of(stored));
            processor.processEvent(StandardWatchEventKinds.ENTRY_DELETE, 1L, tempDir.resolve(subPath).resolve(fileName), false);
        }

        @ParameterizedTest
        @EnumSource(value = LibraryOrganizationMode.class, names = {"AUTO_DETECT", "BOOK_PER_FILE"})
        void aFileOfANewFolderThatCopiesAStoredFileWaitsUntilItsSourceGoes(LibraryOrganizationMode mode) throws Exception {
            library.setOrganizationMode(mode);
            processor.setCopySourceWaitMs(60000);
            Path folder = tempDir.resolve("Moved");
            Path copy = settled(folder.resolve("Foundation.epub"), "content");
            // The second time, the pool holds the source's book and gives it the file
            when(bookFileTransactionalHandler.handleNewBookFileUnlessCopy(1L, copy))
                    .thenReturn(Optional.of(new BookFileTransactionalHandler.CopyOfAStoredFile(7L, "hash")))
                    .thenReturn(Optional.empty());

            processor.processEvent(StandardWatchEventKinds.ENTRY_CREATE, 1L, folder, true);

            verify(bookFileTransactionalHandler, timeout(8000)).handleNewBookFileUnlessCopy(1L, copy);
            waitUntil(() -> processor.waitsAsCopy(copy));
            storedFileRemoved("Asimov", "Foundation.epub", "hash");

            verify(bookFileTransactionalHandler, timeout(3000).times(2)).handleNewBookFileUnlessCopy(1L, copy);
            waitUntil(() -> !processor.waitsAsCopy(copy));
            verify(bookFileTransactionalHandler, never()).handleNewBookFile(anyLong(), any());
        }

        @ParameterizedTest
        @EnumSource(value = LibraryOrganizationMode.class, names = {"AUTO_DETECT", "BOOK_PER_FILE"})
        void aFileOfANewFolderThatCopiesAStoredFileThatStaysIsANewFileOnceTheWaitIsOver(LibraryOrganizationMode mode) throws Exception {
            library.setOrganizationMode(mode);
            processor.setCopySourceWaitMs(1500);
            Path folder = tempDir.resolve("Copies");
            Path copy = settled(folder.resolve("Foundation.epub"), "content");
            when(bookFileTransactionalHandler.handleNewBookFileUnlessCopy(1L, copy))
                    .thenReturn(Optional.of(new BookFileTransactionalHandler.CopyOfAStoredFile(7L, "hash")));

            processor.processEvent(StandardWatchEventKinds.ENTRY_CREATE, 1L, folder, true);

            verify(bookFileTransactionalHandler, timeout(8000)).handleNewBookFileUnlessCopy(1L, copy);
            verify(bookFileTransactionalHandler, never()).handleNewBookFile(anyLong(), any());
            verify(bookFileTransactionalHandler, timeout(5000)).handleNewBookFile(1L, copy);
            Thread.sleep(500);
            verify(bookFileTransactionalHandler, times(1)).handleNewBookFile(1L, copy);
            verify(bookFileTransactionalHandler, times(1)).handleNewBookFileUnlessCopy(1L, copy);
        }

        @Test
        void bookPerFolder_aNewFolderWithACopyOfAStoredFileWaitsWithAllItsNewFilesAndIsOneBookOnceTheWaitIsOver() throws Exception {
            library.setOrganizationMode(LibraryOrganizationMode.BOOK_PER_FOLDER);
            processor.setCopySourceWaitMs(3000);
            Path folder = tempDir.resolve("Copies");
            Path copy = settled(folder.resolve("Foundation.epub"), "copied content");
            settled(folder.resolve("Foundation.pdf"), "new content");
            when(pendingDeletionPool.matchFolderByHashes(any())).thenReturn(Optional.empty());
            when(pendingDeletionPool.matchByHash(anyString())).thenReturn(Optional.empty());
            when(bookFilePersistenceService.findBookCopied(eq(FileFingerprint.generateHash(copy)), any())).thenReturn(Optional.of(7L));

            processor.processEvent(StandardWatchEventKinds.ENTRY_CREATE, 1L, folder, true);

            // Handled once the folder's debounce is over, then waiting for the length of the wait
            verify(bookFilePersistenceService, timeout(10000).atLeastOnce()).findBookCopied(anyString(), any());
            waitUntil(() -> processor.waitsAsCopy(folder));
            verify(libraryProcessingService, never()).processLibraryFiles(any(), any());
            // The copied file stays where it was: the folder's new files are one new book, as before
            verify(libraryProcessingService, timeout(8000)).processLibraryFiles(argThat(files -> files.size() == 2), eq(library));
            assertThat(processor.waitsAsCopy(folder)).isFalse();
        }

        @Test
        void bookPerFolder_aFolderOfCopiesMovesEachFileAsItsSourceGoesAndTheRestWaitsOn() throws Exception {
            library.setOrganizationMode(LibraryOrganizationMode.BOOK_PER_FOLDER);
            processor.setCopySourceWaitMs(60000);
            Path folder = tempDir.resolve("Moved");
            Path epub = settled(folder.resolve("Foundation.epub"), "epub content");
            Path pdf = settled(folder.resolve("Foundation.pdf"), "pdf content");
            String epubHash = FileFingerprint.generateHash(epub);
            String pdfHash = FileFingerprint.generateHash(pdf);
            // The sources' deletions come in one at a time: the pool then holds each file's book, and the file it gave
            // its book is stored at its path
            Set<String> removed = ConcurrentHashMap.newKeySet();
            Set<String> recovered = ConcurrentHashMap.newKeySet();
            when(pendingDeletionPool.matchFolderByHashes(any())).thenReturn(Optional.empty());
            when(pendingDeletionPool.matchByHash(anyString())).thenAnswer(inv -> {
                String hash = inv.getArgument(0);
                return removed.contains(hash) && recovered.add(hash) ? Optional.of(poolMatch(hash)) : Optional.empty();
            });
            when(bookFilePersistenceService.isStoredByALiveBook(eq(library), any())).thenAnswer(inv ->
                    recovered.contains(FileFingerprint.generateHash(inv.getArgument(1, Path.class))));
            when(bookFilePersistenceService.findBookCopied(anyString(), any())).thenAnswer(inv ->
                    removed.contains(inv.getArgument(0, String.class)) ? Optional.empty() : Optional.of(7L));

            processor.processEvent(StandardWatchEventKinds.ENTRY_CREATE, 1L, folder, true);
            verify(bookFilePersistenceService, timeout(10000).atLeastOnce()).findBookCopied(anyString(), any());
            waitUntil(() -> processor.waitsAsCopy(folder));

            removed.add(epubHash);
            storedFileRemoved("Asimov", "Foundation.epub", epubHash);
            verify(pendingDeletionPool, timeout(3000)).recoverBook(argThat(m -> m.file().currentHash().equals(epubHash)), eq(libraryPath),
                    eq("Moved"), eq("Foundation.epub"), eq(epubHash));
            // The PDF's source is still there: it waits on, from when the folder began to wait
            waitUntil(() -> processor.waitsAsCopy(folder));

            removed.add(pdfHash);
            storedFileRemoved("Asimov", "Foundation.pdf", pdfHash);
            verify(pendingDeletionPool, timeout(3000)).recoverBook(argThat(m -> m.file().currentHash().equals(pdfHash)), eq(libraryPath),
                    eq("Moved"), eq("Foundation.pdf"), eq(pdfHash));
            waitUntil(() -> !processor.waitsAsCopy(folder));
            verify(libraryProcessingService, never()).processLibraryFiles(any(), any());
        }

        @Test
        void bookPerFolder_aFolderOfCopiesWaitsOnAfterOneSourceWentForTheOthersStillOnTheirWay() throws Exception {
            // A mover that removes a tree it copied removes it file by file: the PDF's source goes after the wait has
            // passed since the folder began to wait, but within it of the EPUB's source going
            library.setOrganizationMode(LibraryOrganizationMode.BOOK_PER_FOLDER);
            processor.setCopySourceWaitMs(3000);
            Path folder = tempDir.resolve("Moved");
            Path epub = settled(folder.resolve("Foundation.epub"), "epub content");
            Path pdf = settled(folder.resolve("Foundation.pdf"), "pdf content");
            String epubHash = FileFingerprint.generateHash(epub);
            String pdfHash = FileFingerprint.generateHash(pdf);
            Set<String> removed = ConcurrentHashMap.newKeySet();
            Set<String> recovered = ConcurrentHashMap.newKeySet();
            when(pendingDeletionPool.matchFolderByHashes(any())).thenReturn(Optional.empty());
            when(pendingDeletionPool.matchByHash(anyString())).thenAnswer(inv -> {
                String hash = inv.getArgument(0);
                return removed.contains(hash) && recovered.add(hash) ? Optional.of(poolMatch(hash)) : Optional.empty();
            });
            when(bookFilePersistenceService.isStoredByALiveBook(eq(library), any())).thenAnswer(inv ->
                    recovered.contains(FileFingerprint.generateHash(inv.getArgument(1, Path.class))));
            when(bookFilePersistenceService.findBookCopied(anyString(), any())).thenAnswer(inv ->
                    removed.contains(inv.getArgument(0, String.class)) ? Optional.empty() : Optional.of(7L));

            processor.processEvent(StandardWatchEventKinds.ENTRY_CREATE, 1L, folder, true);
            verify(bookFilePersistenceService, timeout(10000).atLeastOnce()).findBookCopied(anyString(), any());
            waitUntil(() -> processor.waitsAsCopy(folder));
            long began = System.currentTimeMillis();

            Thread.sleep(2000);
            removed.add(epubHash);
            storedFileRemoved("Asimov", "Foundation.epub", epubHash);
            verify(pendingDeletionPool, timeout(3000)).recoverBook(argThat(m -> m.file().currentHash().equals(epubHash)), eq(libraryPath),
                    eq("Moved"), eq("Foundation.epub"), eq(epubHash));
            Thread.sleep(Math.max(0L, began + 4000 - System.currentTimeMillis()));
            removed.add(pdfHash);
            storedFileRemoved("Asimov", "Foundation.pdf", pdfHash);

            verify(pendingDeletionPool, timeout(3000)).recoverBook(argThat(m -> m.file().currentHash().equals(pdfHash)), eq(libraryPath),
                    eq("Moved"), eq("Foundation.pdf"), eq(pdfHash));
            verify(libraryProcessingService, never()).processLibraryFiles(any(), any());
        }

        private PendingDeletionPool.MatchResult poolMatch(String hash) {
            var file = new PendingDeletionPool.FileSnapshot(70L, "Foundation", "Asimov", hash, false, BookFileType.EPUB);
            var book = new PendingDeletionPool.BookSnapshot(7L, 1L, "Asimov", List.of(file));
            return new PendingDeletionPool.MatchResult(null, book, file);
        }

        @Test
        void aNewFolderAudiobookThatCopiesAStoredOneWaitsAndIsHandedBackWhenTheFolderHoldingItGoes() throws Exception {
            processor.setCopySourceWaitMs(60000);
            Path folder = tempDir.resolve("Moved").resolve("Book");
            settled(folder.resolve("Track 01.mp3"), "first");
            settled(folder.resolve("Track 02.mp3"), "second");
            when(bookFileTransactionalHandler.handleNewFolderAudiobookUnlessCopy(1L, folder))
                    .thenReturn(Optional.of(new BookFileTransactionalHandler.CopyOfAStoredFile(7L, "folder hash")))
                    .thenReturn(Optional.empty());
            BookEntity book = BookEntity.builder().id(7L).library(library).libraryPath(libraryPath).deleted(false)
                    .bookFiles(new ArrayList<>(List.of(BookFileEntity.builder().id(70L).fileSubPath("Audio").fileName("Book")
                            .folderBased(true).currentHash("folder hash").isBookFormat(true).bookType(BookFileType.AUDIOBOOK).build())))
                    .build();
            when(bookRepository.findBooksWithFilesUnderPath(eq(1L), eq("Audio/Book"))).thenReturn(List.of(book));

            processor.processEvent(StandardWatchEventKinds.ENTRY_CREATE, 1L, folder, true);

            verify(bookFileTransactionalHandler, timeout(8000)).handleNewFolderAudiobookUnlessCopy(1L, folder);
            waitUntil(() -> processor.waitsAsCopy(folder));
            // A mover that removes the tree it copied: the folder's deletion, whose files the database does not hold
            processor.processEvent(StandardWatchEventKinds.ENTRY_DELETE, 1L, tempDir.resolve("Audio").resolve("Book"), true);

            verify(pendingDeletionPool, timeout(3000)).addFolderDeletion(any(), eq(1L), eq(List.of(book)), any());
            verify(bookFileTransactionalHandler, timeout(3000).times(2)).handleNewFolderAudiobookUnlessCopy(1L, folder);
            waitUntil(() -> !processor.waitsAsCopy(folder));
            verify(bookFileTransactionalHandler, never()).handleNewFolderAudiobook(anyLong(), any());
        }

        @Test
        void aNewFolderAudiobookThatCopiesAStoredOneThatStaysIsAFolderAudiobookOnceTheWaitIsOver() throws Exception {
            processor.setCopySourceWaitMs(1500);
            Path folder = tempDir.resolve("Book");
            settled(folder.resolve("Track 01.mp3"), "first");
            settled(folder.resolve("Track 02.mp3"), "second");
            when(bookFileTransactionalHandler.handleNewFolderAudiobookUnlessCopy(1L, folder))
                    .thenReturn(Optional.of(new BookFileTransactionalHandler.CopyOfAStoredFile(7L, "folder hash")));

            processor.processEvent(StandardWatchEventKinds.ENTRY_CREATE, 1L, folder, true);

            verify(bookFileTransactionalHandler, timeout(8000)).handleNewFolderAudiobookUnlessCopy(1L, folder);
            verify(bookFileTransactionalHandler, never()).handleNewFolderAudiobook(anyLong(), any());
            verify(bookFileTransactionalHandler, timeout(5000)).handleNewFolderAudiobook(1L, folder);
        }
    }

    /**
     * A new folder's files that are written are handled at once, and each one still being written once it is: a mover
     * that removes each source as soon as it copied it leaves the book in the pool only for its grace period, which a
     * large file written into the same folder outlasts. What groups files waits for the files it groups.
     */
    @Nested
    class FilesStillBeingWrittenInNewFolders {

        private Path settled(Path file, String content) throws IOException {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content);
            Files.setLastModifiedTime(file, FileTime.from(Instant.now().minusSeconds(60)));
            return file;
        }

        /** Appends to the file for this long, as a mover copying a large file into the library writes it. */
        private void writeFor(Path file, long millis) throws Exception {
            long until = System.currentTimeMillis() + millis;
            while (System.currentTimeMillis() < until) {
                Files.writeString(file, " and more", StandardOpenOption.APPEND);
                Thread.sleep(500);
            }
        }

        @ParameterizedTest
        @EnumSource(value = LibraryOrganizationMode.class, names = {"AUTO_DETECT", "BOOK_PER_FILE"})
        void aNewFolderHandlesItsWrittenFilesAtOnceAndAFileStillBeingWrittenOnceItIsWritten(LibraryOrganizationMode mode) throws Exception {
            library.setOrganizationMode(mode);
            Path folder = Files.createDirectory(tempDir.resolve("Arriving"));
            Path written = settled(folder.resolve("Foundation.epub"), "written");
            Path big = Files.writeString(folder.resolve("Big.m4b"), "the start");

            processor.processEvent(StandardWatchEventKinds.ENTRY_CREATE, 1L, folder, true);
            // Still being written well past the folder's debounce, as a large file copied into a new folder is
            writeFor(big, 9000);

            verify(bookFileTransactionalHandler).handleNewBookFileUnlessCopy(1L, written);
            verify(bookFileTransactionalHandler, never()).handleNewBookFileUnlessCopy(1L, big);
            // Handled once it is written in full, not half written
            verify(bookFileTransactionalHandler, timeout(8000)).handleNewBookFileUnlessCopy(1L, big);
        }

        @Test
        void bookPerFolder_aFolderWaitingForAFileStillBeingWrittenGivesABookInThePoolItsWrittenFileAtOnce() throws Exception {
            library.setOrganizationMode(LibraryOrganizationMode.BOOK_PER_FOLDER);
            Path folder = Files.createDirectory(tempDir.resolve("Arriving"));
            Path moved = settled(folder.resolve("Foundation.epub"), "moved content");
            String movedHash = FileFingerprint.generateHash(moved);
            Path big = Files.writeString(folder.resolve("Big.pdf"), "the start");
            // The mover removed the file's source as soon as it copied it: the pool holds its book
            Set<String> recovered = ConcurrentHashMap.newKeySet();
            when(pendingDeletionPool.matchFolderByHashes(any())).thenReturn(Optional.empty());
            when(pendingDeletionPool.matchByHash(anyString())).thenAnswer(inv ->
                    movedHash.equals(inv.getArgument(0)) && recovered.add(movedHash) ? Optional.of(poolMatch(movedHash)) : Optional.empty());
            when(bookFilePersistenceService.isStoredByALiveBook(eq(library), any())).thenAnswer(inv ->
                    inv.getArgument(1, Path.class).equals(moved) && !recovered.isEmpty());

            processor.processEvent(StandardWatchEventKinds.ENTRY_CREATE, 1L, folder, true);
            writeFor(big, 9000);

            verify(pendingDeletionPool).recoverBook(argThat(m -> m.file().currentHash().equals(movedHash)), eq(libraryPath),
                    eq("Arriving"), eq("Foundation.epub"), eq(movedHash));
            verify(libraryProcessingService, never()).processLibraryFiles(any(), any());
            // Once it is written, the folder's new file is a book, without the file the pool gave its book
            verify(libraryProcessingService, timeout(8000)).processLibraryFiles(argThat(files -> files.size() == 1
                    && files.getFirst().getFileName().equals("Big.pdf")), eq(library));
        }

        @Test
        void bookPerFolder_aFolderWaitsForAFileStillBeingWrittenNoLongerThanANewFileWaitsToSettle() throws Exception {
            library.setOrganizationMode(LibraryOrganizationMode.BOOK_PER_FOLDER);
            processor.setStabilityMaxWaitMs(3000);
            processor.setCopySourceWaitMs(6000);
            // A copy elsewhere in the library waits for the mover meanwhile
            Path copy = settled(tempDir.resolve("copy.epub"), "copied");
            when(bookFileTransactionalHandler.handleNewBookFileUnlessCopy(1L, copy))
                    .thenReturn(Optional.of(new BookFileTransactionalHandler.CopyOfAStoredFile(7L, "hash")));
            processor.processEvent(StandardWatchEventKinds.ENTRY_CREATE, 1L, copy, false);
            waitUntil(() -> processor.waitsAsCopy(copy));
            Path folder = Files.createDirectory(tempDir.resolve("Arriving"));
            Path big = Files.writeString(folder.resolve("Big.pdf"), "the start");

            processor.processEvent(StandardWatchEventKinds.ENTRY_CREATE, 1L, folder, true);
            writeFor(big, 14000);

            // Handled at the limit while still being written, as a single new file is
            verify(libraryProcessingService).processLibraryFiles(argThat(files -> files.size() == 1
                    && files.getFirst().getFileName().equals("Big.pdf")), eq(library));
            // The file's growth past the limit still counts as the mover's writes: the copy waits until it is written
            verify(bookFileTransactionalHandler, never()).handleNewBookFile(1L, copy);
            verify(bookFileTransactionalHandler, timeout(15000)).handleNewBookFile(1L, copy);
        }

        @Test
        void aFolderThatCanBeAFolderAudiobookWaitsForItsAudioFilesButHandsOnAtOnceOneMovedFromABookThePoolHolds() throws Exception {
            Path folder = Files.createDirectory(tempDir.resolve("Herbert"));
            Path moved = settled(folder.resolve("Dune.m4b"), "dune");
            Path big = Files.writeString(folder.resolve("Children of Dune.m4b"), "the start");
            // A single-file audiobook whose source the mover removed as soon as it copied it
            when(pendingDeletionPool.holds(FileFingerprint.generateHash(moved))).thenReturn(true, false);

            processor.processEvent(StandardWatchEventKinds.ENTRY_CREATE, 1L, folder, true);
            writeFor(big, 9000);

            verify(bookFileTransactionalHandler).handleNewBookFileUnlessCopy(1L, moved);
            verify(bookFileTransactionalHandler, never()).handleNewBookFileUnlessCopy(1L, big);
            verify(bookFileTransactionalHandler, never()).handleNewFolderAudiobookUnlessCopy(anyLong(), any());
            // Once written, two single-file audiobooks of different titles are a series: each a book of its own
            verify(bookFileTransactionalHandler, timeout(8000)).handleNewBookFileUnlessCopy(1L, big);
            verify(bookFileTransactionalHandler, never()).handleNewFolderAudiobookUnlessCopy(anyLong(), any());
        }

        @Test
        void aNewFolderHoldingFolderAudiobooksInFoldersOfTheirOwnHandsEachToTheFolderAudiobookFlow() throws Exception {
            // Author/Title/tracks: the author folder is no folder audiobook, each title folder is
            Path author = tempDir.resolve("Listening").resolve("Herbert");
            Path dune = author.resolve("Dune");
            Path messiah = author.resolve("Dune Messiah");
            settled(dune.resolve("Dune - 01.mp3"), "dune 1");
            settled(dune.resolve("Dune - 02.mp3"), "dune 2");
            settled(messiah.resolve("Dune Messiah - 01.mp3"), "messiah 1");
            settled(messiah.resolve("Dune Messiah - 02.mp3"), "messiah 2");

            processor.processEvent(StandardWatchEventKinds.ENTRY_CREATE, 1L, author, true);

            verify(bookFileTransactionalHandler, timeout(8000)).handleNewFolderAudiobookUnlessCopy(1L, dune);
            verify(bookFileTransactionalHandler, timeout(3000)).handleNewFolderAudiobookUnlessCopy(1L, messiah);
            verify(bookFileTransactionalHandler, never()).handleNewFolderAudiobookUnlessCopy(1L, author);
            verify(bookFileTransactionalHandler, never()).handleNewBookFileUnlessCopy(anyLong(), any());
        }

        @Test
        void aFolderAudiobookNextToAnEbookKeepsItsTracksTogether() throws Exception {
            Path author = tempDir.resolve("Herbert");
            Path dune = author.resolve("Dune");
            settled(dune.resolve("Dune - 01.mp3"), "dune 1");
            settled(dune.resolve("Dune - 02.mp3"), "dune 2");
            Path ebook = settled(author.resolve("Dune.epub"), "ebook");

            processor.processEvent(StandardWatchEventKinds.ENTRY_CREATE, 1L, author, true);

            verify(bookFileTransactionalHandler, timeout(8000)).handleNewFolderAudiobookUnlessCopy(1L, dune);
            verify(bookFileTransactionalHandler, timeout(3000)).handleNewBookFileUnlessCopy(1L, ebook);
            verify(bookFileTransactionalHandler, never()).handleNewBookFileUnlessCopy(eq(1L),
                    argThat(p -> p.getFileName().toString().endsWith(".mp3")));
        }

        private PendingDeletionPool.MatchResult poolMatch(String hash) {
            var file = new PendingDeletionPool.FileSnapshot(70L, "Foundation.epub", "Asimov", hash, false, BookFileType.EPUB);
            var book = new PendingDeletionPool.BookSnapshot(7L, 1L, "Asimov", List.of(file));
            return new PendingDeletionPool.MatchResult(null, book, file);
        }
    }

    @Nested
    class FolderDeleteHandling {

        @Test
        void folderWithBooks_addsToPool() throws Exception {
            BookEntity book = BookEntity.builder()
                    .id(10L)
                    .library(library)
                    .libraryPath(libraryPath)
                    .deleted(false)
                    .bookFiles(new ArrayList<>(List.of(BookFileEntity.builder()
                            .id(100L)
                            .fileName("test.epub")
                            .fileSubPath("books")
                            .currentHash("hash")
                            .isBookFormat(true)
                            .bookType(BookFileType.EPUB)
                            .build())))
                    .build();

            when(bookRepository.findBooksWithFilesUnderPath(eq(1L), anyString()))
                    .thenReturn(List.of(book));

            Path folder = tempDir.resolve("books");

            processor.processEvent(StandardWatchEventKinds.ENTRY_DELETE, 1L, folder, true);

            Thread.sleep(2000);

            verify(pendingDeletionPool).addFolderDeletion(any(), eq(1L), eq(List.of(book)), any());
        }

        @Test
        void booksOfAFolderThatOnlyComparesEqualAreNotAddedToThePool() throws Exception {
            // The lookup compares through the database's collation, which ignores case: 'Books' is another folder
            BookEntity inBooks = BookEntity.builder().id(10L).library(library).libraryPath(libraryPath).deleted(false)
                    .bookFiles(new ArrayList<>(List.of(BookFileEntity.builder().id(100L).fileName("a.epub")
                            .fileSubPath("books/a").currentHash("h1").isBookFormat(true).bookType(BookFileType.EPUB).build())))
                    .build();
            BookEntity inOtherFolder = BookEntity.builder().id(11L).library(library).libraryPath(libraryPath).deleted(false)
                    .bookFiles(new ArrayList<>(List.of(BookFileEntity.builder().id(101L).fileName("b.epub")
                            .fileSubPath("Books").currentHash("h2").isBookFormat(true).bookType(BookFileType.EPUB).build())))
                    .build();
            when(bookRepository.findBooksWithFilesUnderPath(eq(1L), anyString())).thenReturn(List.of(inBooks, inOtherFolder));

            processor.processEvent(StandardWatchEventKinds.ENTRY_DELETE, 1L, tempDir.resolve("books"), true);

            Thread.sleep(2000);

            verify(pendingDeletionPool).addFolderDeletion(any(), eq(1L), eq(List.of(inBooks)), any());
        }

        @Test
        void folderWithNoBooks_doesNotAddToPool() throws Exception {
            when(bookRepository.findBooksWithFilesUnderPath(eq(1L), anyString()))
                    .thenReturn(List.of());

            Path folder = tempDir.resolve("emptybooks");

            processor.processEvent(StandardWatchEventKinds.ENTRY_DELETE, 1L, folder, true);

            Thread.sleep(2000);

            verify(pendingDeletionPool, never()).addFolderDeletion(any(), anyLong(), any(), any());
        }
    }

    @Nested
    class FileDeleteHandling {

        @Test
        void bookFileFound_addsToPool() throws Exception {
            BookEntity book = BookEntity.builder()
                    .id(10L).library(library).libraryPath(libraryPath).deleted(false)
                    .bookFiles(new ArrayList<>()).build();
            BookFileEntity bookFile = BookFileEntity.builder()
                    .id(100L).book(book).fileName("test.epub").fileSubPath("sub")
                    .currentHash("hash").isBookFormat(true).bookType(BookFileType.EPUB).build();

            when(bookFilePersistenceService.findBookFileByLibraryPathSubPathAndFileName(eq(1L), anyString(), eq("test.epub")))
                    .thenReturn(Optional.of(bookFile));

            Path file = tempDir.resolve("sub").resolve("test.epub");

            processor.processEvent(StandardWatchEventKinds.ENTRY_DELETE, 1L, file, false);

            Thread.sleep(2000);

            verify(pendingDeletionPool).addFileDeletion(any(), eq(1L), eq(bookFile), eq(book), any());
        }

        @Test
        void bookFileNotFound_logsAndContinues() throws Exception {
            when(bookFilePersistenceService.findBookFileByLibraryPathSubPathAndFileName(anyLong(), anyString(), anyString()))
                    .thenReturn(Optional.empty());

            Path file = tempDir.resolve("sub").resolve("missing.epub");

            processor.processEvent(StandardWatchEventKinds.ENTRY_DELETE, 1L, file, false);

            Thread.sleep(2000);

            verify(pendingDeletionPool, never()).addFileDeletion(any(), anyLong(), any(), any(), any());
        }
    }

    @Nested
    class FileCreateHandling {

        @Test
        void zeroByteFile_skipped() throws Exception {
            Path file = tempDir.resolve("empty.epub");
            Files.createFile(file);

            processor.processEvent(StandardWatchEventKinds.ENTRY_CREATE, 1L, file, false);

            // Wait for stability check + processing
            Thread.sleep(4000);

            verify(bookFileTransactionalHandler, never()).handleNewBookFile(anyLong(), any());
            verify(bookFileTransactionalHandler, never()).handleNewBookFileUnlessCopy(anyLong(), any());
        }

        @Test
        void nonBookFile_skipped() throws Exception {
            Path file = tempDir.resolve("readme.txt");
            Files.writeString(file, "hello");

            processor.processEvent(StandardWatchEventKinds.ENTRY_CREATE, 1L, file, false);

            Thread.sleep(4000);

            verify(bookFileTransactionalHandler, never()).handleNewBookFile(anyLong(), any());
            verify(bookFileTransactionalHandler, never()).handleNewBookFileUnlessCopy(anyLong(), any());
        }

        @Test
        void validBookFile_processedAfterStabilityCheck() throws Exception {
            Path file = tempDir.resolve("book.epub");
            Files.writeString(file, "book content");

            processor.processEvent(StandardWatchEventKinds.ENTRY_CREATE, 1L, file, false);

            // Stability check needs file to be stable for STABILITY_CHECK_INTERVAL_MS (3s)
            // then event goes to queue and is processed by virtual thread
            Thread.sleep(7000);

            verify(bookFileTransactionalHandler).handleNewBookFileUnlessCopy(eq(1L), any());
        }
    }

    @Nested
    class Shutdown {

        @Test
        void shutdownCompletesCleanly() {
            // Just verify shutdown doesn't throw
            processor.shutdown();

            // Reinitialize so tearDown's shutdown doesn't fail
            processor = new LibraryFileEventProcessor(
                    libraryRepository, bookRepository, bookFileTransactionalHandler,
                    bookFilePersistenceService, libraryProcessingService, pendingDeletionPool);
        }
    }
}
