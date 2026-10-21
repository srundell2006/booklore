package org.booklore.service.library.importer;

import org.booklore.model.FileProcessResult;
import org.booklore.model.dto.Book;
import org.booklore.model.dto.settings.LibraryFile;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.entity.LibraryPathEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.repository.BookAdditionalFileRepository;
import org.booklore.repository.BookRepository;
import org.booklore.repository.LibraryRepository;
import org.booklore.service.event.BookEventBroadcaster;
import org.booklore.service.fileprocessor.BookFileProcessorRegistry;
import org.booklore.service.fileprocessor.EpubProcessor;
import org.booklore.service.fileprocessor.PreparableBookFileProcessor;
import org.booklore.service.fileprocessor.prepare.PersistedBook;
import org.booklore.service.fileprocessor.prepare.PrepareContext;
import org.booklore.service.fileprocessor.prepare.PrepareRequest;
import org.booklore.service.fileprocessor.prepare.PreparedBookFile;
import org.booklore.service.kobo.KoboAutoShelfService;
import org.booklore.service.metadata.extractor.AudiobookMetadataExtractor;
import org.booklore.service.metadata.extractor.EpubMetadataExtractor;
import org.booklore.service.metadata.extractor.MetadataExtractorFactory;
import org.booklore.service.metadata.sidecar.SidecarMetadataWriter;
import org.booklore.util.FileService;
import org.booklore.util.cover.CropPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.PlatformTransactionManager;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A scan with one book file wedged in its open, as a file on a hung share is: a FIFO with no writer blocks the open
 * that hashes it, and no interrupt ends that. The real EPUB preparation runs on the import threads; only saving is
 * faked.
 */
@EnabledOnOs(OS.LINUX)
class WedgedFileIT {

    @TempDir
    Path libraryRoot;

    @Test
    void aFileWedgedInItsOpenIsSkippedAndTheRestOfTheScanIsSaved() throws Exception {
        Path fifo = libraryRoot.resolve("wedged.epub");
        assertThat(new ProcessBuilder("mkfifo", fifo.toString()).start().waitFor()).isZero();
        Files.writeString(libraryRoot.resolve("first.epub"), "not much of an epub");
        Files.writeString(libraryRoot.resolve("last.epub"), "not much of an epub either");

        LibraryEntity library = LibraryEntity.builder().name("Share").build();
        LibraryPathEntity libraryPath = LibraryPathEntity.builder().id(1L).library(library).path(libraryRoot.toString()).build();
        library.setLibraryPaths(List.of(libraryPath));
        Map<String, List<LibraryFile>> groups = new LinkedHashMap<>();
        for (String name : List.of("first.epub", "wedged.epub", "last.epub")) {
            groups.put(name, List.of(LibraryFile.builder().libraryEntity(library).libraryPathEntity(libraryPath)
                    .fileSubPath("").fileName(name).bookFileType(BookFileType.EPUB).build()));
        }

        SavingFake processor = new SavingFake();
        BookFileProcessorRegistry registry = new BookFileProcessorRegistry(List.of(processor));
        FileService fileService = mock(FileService.class);
        when(fileService.currentCropPolicy()).thenReturn(CropPolicy.NONE);
        ImportExecutor executor = new ImportExecutor(new ImportSettings(true, 4, 16, Duration.ofSeconds(2), 16, 2, 64L * 1024 * 1024));
        ParallelBookImporter importer = new ParallelBookImporter(executor, registry, mock(PlatformTransactionManager.class),
                mock(LibraryRepository.class), mock(BookRepository.class), mock(BookAdditionalFileRepository.class),
                mock(BookEventBroadcaster.class), mock(KoboAutoShelfService.class), fileService, mock(SidecarMetadataWriter.class),
                mock(MetadataExtractorFactory.class), mock(AudiobookMetadataExtractor.class));
        try {
            long start = System.nanoTime();
            ImportSummary summary = importer.importGroups(groups, library);

            assertThat(System.nanoTime() - start).as("the scan's duration").isLessThan(TimeUnit.SECONDS.toNanos(5));
            assertThat(summary.added()).isEqualTo(2);
            assertThat(summary.timedOut()).isEqualTo(1);
            assertThat(processor.saved).containsExactly("first.epub", "last.epub");
            assertThat(executor.stuckThreads()).isEqualTo(1);
            assertThat(executor.breakerOpen()).isFalse();

            openForWriting(fifo);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (executor.stuckThreads() != 0) {
                assertThat(System.nanoTime()).as("the wedged thread to return").isLessThan(deadline);
                Thread.sleep(5);
            }
        } finally {
            if (executor.stuckThreads() != 0) {
                openForWriting(fifo);
            }
            executor.shutdown();
        }
    }

    /** Completes the open of the FIFO's reader, from a thread the test does not wait on for good. */
    private static void openForWriting(Path fifo) throws InterruptedException {
        Thread writer = Thread.ofPlatform().daemon(true).start(() -> {
            try (FileOutputStream ignored = new FileOutputStream(fifo.toFile())) {
                // closing it ends the reader's stream
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
        writer.join(TimeUnit.SECONDS.toMillis(10));
    }

    /** Reads with the real EPUB processor, which needs nothing from the database to do so, and fakes the save. */
    private static final class SavingFake implements PreparableBookFileProcessor {
        private final EpubProcessor reader = new EpubProcessor(null, null, null, null, null, null, null, new EpubMetadataExtractor());
        private final AtomicLong lastId = new AtomicLong();
        final List<String> saved = Collections.synchronizedList(new ArrayList<>());

        @Override
        public PreparedBookFile prepare(PrepareRequest request, PrepareContext ctx) throws Exception {
            return reader.prepare(request, ctx);
        }

        @Override
        public PersistedBook persistPrepared(LibraryFile libraryFile, PreparedBookFile prepared) {
            saved.add(libraryFile.getFileName());
            long id = lastId.incrementAndGet();
            return new PersistedBook(Book.builder().id(id).build(), id, true, libraryFile.getFileName(), BookFileType.EPUB, null);
        }

        @Override
        public List<BookFileType> getSupportedTypes() {
            return List.of(BookFileType.EPUB);
        }

        @Override
        public FileProcessResult processFile(LibraryFile libraryFile) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean generateCover(BookEntity bookEntity) {
            throw new UnsupportedOperationException();
        }
    }
}
