package org.booklore.service.fileprocessor;

import org.booklore.mapper.BookMapper;
import org.booklore.model.dto.Book;
import org.booklore.model.dto.BookMetadata;
import org.booklore.model.dto.ComicMetadata;
import org.booklore.model.dto.settings.LibraryFile;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.entity.LibraryPathEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.model.enums.LibraryOrganizationMode;
import org.booklore.repository.BookAdditionalFileRepository;
import org.booklore.repository.BookRepository;
import org.booklore.service.book.BookCreatorService;
import org.booklore.service.file.FileFingerprint;
import org.booklore.service.fileprocessor.prepare.*;
import org.booklore.service.metadata.MetadataMatchService;
import org.booklore.service.metadata.sidecar.SidecarMetadataWriter;
import org.booklore.util.FileService;
import org.booklore.util.cover.CoverKind;
import org.booklore.util.cover.CropPolicy;
import org.booklore.util.cover.RenderedCover;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AbstractFileProcessorTest {

    private static final long BOOK_ID = 1L;

    @Mock private BookRepository bookRepository;
    @Mock private BookAdditionalFileRepository bookAdditionalFileRepository;
    @Mock private BookCreatorService bookCreatorService;
    @Mock private BookMapper bookMapper;
    @Mock private FileService fileService;
    @Mock private MetadataMatchService metadataMatchService;
    @Mock private SidecarMetadataWriter sidecarMetadataWriter;

    @TempDir
    Path tempDir;

    private TestableFileProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new TestableFileProcessor(
                bookRepository, bookAdditionalFileRepository, bookCreatorService,
                bookMapper, fileService, metadataMatchService, sidecarMetadataWriter
        );
    }

    // The rendered covers go through the FileService mock, which would otherwise keep them for the rest of the suite
    @AfterEach
    void dropRecordedCovers() {
        Mockito.clearInvocations(fileService);
    }

    // ========== getBookFolderForCoverFallback ==========

    @Test
    void getBookFolderForCoverFallback_folderBased_returnsFullPath() {
        var libraryFile = buildLibraryFile(tempDir, "audiobooks", "MyAudiobook", true, LibraryOrganizationMode.AUTO_DETECT);
        var result = processor.exposedGetBookFolderForCoverFallback(libraryFile);
        assertThat(result).isEqualTo(libraryFile.getFullPath());
    }

    @Test
    void getBookFolderForCoverFallback_bookPerFolder_returnsParent() {
        var libraryFile = buildLibraryFile(tempDir, "books/author", "book.epub", false, LibraryOrganizationMode.BOOK_PER_FOLDER);
        var result = processor.exposedGetBookFolderForCoverFallback(libraryFile);
        assertThat(result).isEqualTo(libraryFile.getFullPath().getParent());
    }

    @Test
    void getBookFolderForCoverFallback_autoDetectNotFolderBased_returnsNull() {
        var libraryFile = buildLibraryFile(tempDir, "books", "book.epub", false, LibraryOrganizationMode.AUTO_DETECT);
        var result = processor.exposedGetBookFolderForCoverFallback(libraryFile);
        assertThat(result).isNull();
    }

    @Test
    void getBookFolderForCoverFallback_folderBasedInBookPerFolder_returnsFullPath() {
        var libraryFile = buildLibraryFile(tempDir, "audiobooks", "MyAudiobook", true, LibraryOrganizationMode.BOOK_PER_FOLDER);
        var result = processor.exposedGetBookFolderForCoverFallback(libraryFile);
        assertThat(result).isEqualTo(libraryFile.getFullPath());
    }

    // ========== prepare ==========

    @Nested
    class Prepare {

        @Test
        void readsTheFileWithoutTouchingTheDatabase() throws Exception {
            var libraryFile = bookFile("books/author", "book.epub", LibraryOrganizationMode.BOOK_PER_FOLDER);
            createTestImage(libraryFile.getFullPath().resolveSibling("cover.jpg"));
            processor.content = new ExtractedContent(BookMetadata.builder().title("Read").build(), null, null);

            PreparedBookFile prepared = processor.prepare(PrepareRequest.of(libraryFile, CropPolicy.NONE), PrepareContext.unbounded());

            assertThat(prepared.metadata().getTitle()).isEqualTo("Read");
            assertThat(prepared.facts().hash()).isEqualTo(FileFingerprint.generateHash(libraryFile.getFullPath()));
            verifyNoInteractions(bookRepository, bookAdditionalFileRepository, bookCreatorService, bookMapper,
                    fileService, metadataMatchService, sidecarMetadataWriter);
        }

        @Test
        void bookWithoutCover_usesTheFolderImage() throws Exception {
            var libraryFile = bookFile("books/author", "book.epub", LibraryOrganizationMode.BOOK_PER_FOLDER);
            createTestImage(libraryFile.getFullPath().resolveSibling("cover.jpg"));

            PreparedBookFile prepared = processor.prepare(PrepareRequest.of(libraryFile, CropPolicy.NONE), PrepareContext.unbounded());

            assertThat(prepared.cover()).isNotNull();
            assertThat(prepared.cover().kind()).isEqualTo(CoverKind.BOOK);
        }

        @Test
        void audiobookWithoutCover_usesTheFolderImageAsAnAudiobookCover() throws Exception {
            processor.kind = CoverKind.AUDIOBOOK;
            var libraryFile = bookFile("audiobooks/author", "book.m4b", LibraryOrganizationMode.BOOK_PER_FOLDER);
            createTestImage(libraryFile.getFullPath().resolveSibling("folder.jpg"));

            PreparedBookFile prepared = processor.prepare(PrepareRequest.of(libraryFile, CropPolicy.NONE), PrepareContext.unbounded());

            assertThat(prepared.cover().kind()).isEqualTo(CoverKind.AUDIOBOOK);
        }

        @Test
        void folderAudiobook_findsTheCoverInItsOwnWalkAndHandsTheWalkToTheFormat() throws Exception {
            processor.kind = CoverKind.AUDIOBOOK;
            var libraryFile = buildLibraryFile(tempDir, "audiobooks", "MyAudiobook", true, LibraryOrganizationMode.AUTO_DETECT);
            Path folder = Files.createDirectories(libraryFile.getFullPath());
            Files.writeString(folder.resolve("01.mp3"), "audio");
            createTestImage(folder.resolve("Cover.JPG"));

            PreparedBookFile prepared = processor.prepare(PrepareRequest.of(libraryFile, CropPolicy.NONE), PrepareContext.unbounded());

            assertThat(prepared.cover().kind()).isEqualTo(CoverKind.AUDIOBOOK);
            assertThat(prepared.facts().hash()).isEqualTo(FileFingerprint.generateFolderHash(folder));
            assertThat(processor.folderSeen.audioFiles()).containsExactly(folder.resolve("01.mp3"));
        }

        @Test
        void noFolderImage_noCover() throws Exception {
            var libraryFile = bookFile("books/author", "book.epub", LibraryOrganizationMode.BOOK_PER_FOLDER);
            Files.writeString(libraryFile.getFullPath().resolveSibling("notes.txt"), "text");

            PreparedBookFile prepared = processor.prepare(PrepareRequest.of(libraryFile, CropPolicy.NONE), PrepareContext.unbounded());

            assertThat(prepared.cover()).isNull();
        }

        @Test
        void undecodableFolderImage_noCover() throws Exception {
            var libraryFile = bookFile("books/author", "book.epub", LibraryOrganizationMode.BOOK_PER_FOLDER);
            Files.writeString(libraryFile.getFullPath().resolveSibling("cover.jpg"), "not an image");

            PreparedBookFile prepared = processor.prepare(PrepareRequest.of(libraryFile, CropPolicy.NONE), PrepareContext.unbounded());

            assertThat(prepared.cover()).isNull();
        }

        @Test
        void theFilesOwnCoverWinsOverTheFolderImage() throws Exception {
            var libraryFile = bookFile("books/author", "book.epub", LibraryOrganizationMode.BOOK_PER_FOLDER);
            createTestImage(libraryFile.getFullPath().resolveSibling("cover.jpg"));
            RenderedCover own = new RenderedCover(CoverKind.BOOK, new byte[]{1}, new byte[]{2});
            processor.content = new ExtractedContent(null, own, null);

            PreparedBookFile prepared = processor.prepare(PrepareRequest.of(libraryFile, CropPolicy.NONE), PrepareContext.unbounded());

            assertThat(prepared.cover()).isSameAs(own);
        }

        @Test
        void bookPerFileLibrary_doesNotLookInTheFolder() throws Exception {
            var libraryFile = bookFile("books/author", "book.epub", LibraryOrganizationMode.BOOK_PER_FILE);
            createTestImage(libraryFile.getFullPath().resolveSibling("cover.jpg"));

            PreparedBookFile prepared = processor.prepare(PrepareRequest.of(libraryFile, CropPolicy.NONE), PrepareContext.unbounded());

            assertThat(prepared.cover()).isNull();
        }

        @Test
        void missingFile_failsBeforeReadingIt() {
            var libraryFile = buildLibraryFile(tempDir, "books", "missing.epub", false, LibraryOrganizationMode.BOOK_PER_FILE);

            assertThatThrownBy(() -> processor.prepare(PrepareRequest.of(libraryFile, CropPolicy.NONE), PrepareContext.unbounded()))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Failed to compute partial MD5 hash");
            assertThat(processor.reads).isZero();
        }
    }

    // ========== persistPrepared ==========

    @Nested
    class PersistPrepared {

        @Test
        void savesTheBookFromWhatWasPrepared() throws Exception {
            var libraryFile = bookFile("books", "book.epub", LibraryOrganizationMode.BOOK_PER_FILE);
            ComicMetadata comic = ComicMetadata.builder().issueNumber("7").build();
            BookMetadata metadata = BookMetadata.builder().title("Saved").comicMetadata(comic).build();
            RenderedCover cover = new RenderedCover(CoverKind.BOOK, new byte[]{1}, new byte[]{2});
            PreparedBookFile prepared = prepared(libraryFile, new FileFacts("abc", 12L), metadata, cover);
            BookEntity book = shellBook();
            Book dto = Book.builder().id(BOOK_ID).build();
            when(bookCreatorService.createShellBook(libraryFile, BookFileType.EPUB, prepared.facts())).thenReturn(new BookCreatorService.ShellBook(book, true));
            when(fileService.writeRenderedCover(BOOK_ID, cover)).thenReturn(true);
            when(metadataMatchService.calculateMatchScore(book)).thenReturn(42f);
            when(bookMapper.toBook(book)).thenReturn(dto);

            PersistedBook persisted = processor.persistPrepared(libraryFile, prepared);

            assertThat(processor.applied).isSameAs(prepared);
            assertThat(book.getPrimaryBookFile().getCurrentHash()).isEqualTo("abc");
            assertThat(book.getMetadataMatchScore()).isEqualTo(42f);
            assertThat(book.getMetadata().getCoverUpdatedOn()).isNotNull();
            assertThat(book.getBookCoverHash()).isNotNull();
            assertThat(book.getAudiobookCoverHash()).isNull();
            assertThat(persisted).isEqualTo(new PersistedBook(dto, BOOK_ID, true, "book.epub", BookFileType.EPUB, null));

            InOrder order = inOrder(bookCreatorService, fileService, metadataMatchService, bookMapper);
            order.verify(bookCreatorService).createShellBook(libraryFile, BookFileType.EPUB, prepared.facts());
            order.verify(fileService).writeRenderedCover(BOOK_ID, cover);
            order.verify(metadataMatchService).calculateMatchScore(book);
            order.verify(bookCreatorService).saveConnections(book, comic);
            order.verify(bookMapper).toBook(book);
        }

        @Test
        void audiobookCover_setsTheAudiobookCoverFields() throws Exception {
            var libraryFile = bookFile("books", "book.m4b", LibraryOrganizationMode.BOOK_PER_FILE);
            RenderedCover cover = new RenderedCover(CoverKind.AUDIOBOOK, new byte[]{1}, new byte[]{2});
            PreparedBookFile prepared = prepared(libraryFile, new FileFacts("abc", 12L), null, cover);
            BookEntity book = shellBook();
            when(bookCreatorService.createShellBook(any(), any(), any())).thenReturn(new BookCreatorService.ShellBook(book, true));
            when(fileService.writeRenderedCover(BOOK_ID, cover)).thenReturn(true);

            processor.persistPrepared(libraryFile, prepared);

            assertThat(book.getMetadata().getAudiobookCoverUpdatedOn()).isNotNull();
            assertThat(book.getAudiobookCoverHash()).isNotNull();
            assertThat(book.getMetadata().getCoverUpdatedOn()).isNull();
            assertThat(book.getBookCoverHash()).isNull();
            verify(bookCreatorService).saveConnections(book, null);
        }

        @Test
        void coverThatCannotBeWritten_leavesTheBookWithoutCover() throws Exception {
            var libraryFile = bookFile("books", "book.epub", LibraryOrganizationMode.BOOK_PER_FILE);
            RenderedCover cover = new RenderedCover(CoverKind.BOOK, new byte[]{1}, new byte[]{2});
            BookEntity book = shellBook();
            when(bookCreatorService.createShellBook(any(), any(), any())).thenReturn(new BookCreatorService.ShellBook(book, true));
            when(fileService.writeRenderedCover(BOOK_ID, cover)).thenThrow(new IOException("disk full"));

            processor.persistPrepared(libraryFile, prepared(libraryFile, new FileFacts("abc", 12L), null, cover));

            assertThat(book.getBookCoverHash()).isNull();
            assertThat(book.getMetadata().getCoverUpdatedOn()).isNull();
            verify(bookCreatorService).saveConnections(book, null);
        }

        @Test
        void withoutCover_writesNoCoverFiles() throws Exception {
            var libraryFile = bookFile("books", "book.epub", LibraryOrganizationMode.BOOK_PER_FILE);
            BookEntity book = shellBook();
            when(bookCreatorService.createShellBook(any(), any(), any())).thenReturn(new BookCreatorService.ShellBook(book, true));

            processor.persistPrepared(libraryFile, prepared(libraryFile, new FileFacts("abc", 12L), null, null));

            verify(fileService, never()).writeRenderedCover(anyLong(), any());
            assertThat(book.getBookCoverHash()).isNull();
        }

        @Test
        void refusesWhatWasPreparedFromAnotherFile() throws Exception {
            var libraryFile = bookFile("books", "book.epub", LibraryOrganizationMode.BOOK_PER_FILE);
            var other = bookFile("books", "other.epub", LibraryOrganizationMode.BOOK_PER_FILE);

            assertThatThrownBy(() -> processor.persistPrepared(libraryFile, prepared(other, new FileFacts("abc", 12L), null, null)))
                    .isInstanceOf(IllegalStateException.class);
            verifyNoInteractions(bookCreatorService);
        }

        @Test
        void sidecarOnScan_isPreparedForLater() throws Exception {
            var libraryFile = bookFile("books", "book.epub", LibraryOrganizationMode.BOOK_PER_FILE);
            BookEntity book = shellBook();
            var job = new SidecarMetadataWriter.SidecarJob(BOOK_ID, tempDir, tempDir, "{}", null, null);
            when(bookCreatorService.createShellBook(any(), any(), any())).thenReturn(new BookCreatorService.ShellBook(book, true));
            when(sidecarMetadataWriter.isWriteOnScanEnabled()).thenReturn(true);
            when(sidecarMetadataWriter.prepareForScan(book)).thenReturn(Optional.of(job));

            PersistedBook persisted = processor.persistPrepared(libraryFile, prepared(libraryFile, new FileFacts("abc", 12L), null, null));

            assertThat(persisted.sidecarJob()).isSameAs(job);
            verify(sidecarMetadataWriter, never()).execute(any());
        }

        @Test
        void sidecarDeferred_isNotPrepared() throws Exception {
            var libraryFile = bookFile("books", "book.epub", LibraryOrganizationMode.BOOK_PER_FILE);
            libraryFile.setDeferSidecarWrite(true);
            BookEntity book = shellBook();
            when(bookCreatorService.createShellBook(any(), any(), any())).thenReturn(new BookCreatorService.ShellBook(book, true));

            PersistedBook persisted = processor.persistPrepared(libraryFile, prepared(libraryFile, new FileFacts("abc", 12L), null, null));

            assertThat(persisted.sidecarJob()).isNull();
            verify(sidecarMetadataWriter, never()).prepareForScan(any());
        }

        @Test
        void sidecarThatFailsToPrepare_doesNotFailTheBook() throws Exception {
            var libraryFile = bookFile("books", "book.epub", LibraryOrganizationMode.BOOK_PER_FILE);
            BookEntity book = shellBook();
            when(bookCreatorService.createShellBook(any(), any(), any())).thenReturn(new BookCreatorService.ShellBook(book, true));
            when(sidecarMetadataWriter.isWriteOnScanEnabled()).thenReturn(true);
            when(sidecarMetadataWriter.prepareForScan(book)).thenThrow(new IllegalStateException("broken"));

            PersistedBook persisted = processor.persistPrepared(libraryFile, prepared(libraryFile, new FileFacts("abc", 12L), null, null));

            assertThat(persisted.sidecarJob()).isNull();
        }

        @Test
        void newBookRolledBack_deletesItsCoverFiles() throws Exception {
            List<TransactionSynchronization> synchronizations = persistWithSynchronization(true);

            assertThat(synchronizations).hasSize(1);
            synchronizations.getFirst().afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
            verify(fileService, never()).deleteBookCovers(any());
            synchronizations.getFirst().afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
            verify(fileService).deleteBookCovers(Set.of(BOOK_ID));
        }

        @Test
        void restoredBookRolledBack_keepsItsCoverFiles() throws Exception {
            assertThat(persistWithSynchronization(false)).isEmpty();
            verify(fileService).writeRenderedCover(eq(BOOK_ID), any());
        }

        @Test
        void bookAlreadyStoredForThePath_keepsItsMetadataCoverAndPrimaryHash() throws Exception {
            var libraryFile = bookFile("books", "book.epub", LibraryOrganizationMode.BOOK_PER_FILE);
            RenderedCover cover = new RenderedCover(CoverKind.BOOK, new byte[]{1}, new byte[]{2});
            BookEntity book = shellBook();
            book.getPrimaryBookFile().setCurrentHash("primary");
            book.setBookCoverHash("curated");
            when(bookCreatorService.createShellBook(any(), any(), any())).thenReturn(new BookCreatorService.ShellBook(book, false, false));

            PersistedBook persisted = processor.persistPrepared(libraryFile,
                    prepared(libraryFile, new FileFacts("abc", 12L), BookMetadata.builder().title("From The File").build(), cover));

            assertThat(processor.applied).isNull();
            verify(fileService, never()).writeRenderedCover(anyLong(), any());
            assertThat(book.getBookCoverHash()).isEqualTo("curated");
            assertThat(book.getPrimaryBookFile().getCurrentHash()).isEqualTo("primary");
            assertThat(persisted.created()).isFalse();
            verify(bookCreatorService).saveConnections(book, null);
        }

        @Test
        void restoredBook_takesTheFilesMetadataAndCover() throws Exception {
            var libraryFile = bookFile("books", "book.epub", LibraryOrganizationMode.BOOK_PER_FILE);
            RenderedCover cover = new RenderedCover(CoverKind.BOOK, new byte[]{1}, new byte[]{2});
            PreparedBookFile prepared = prepared(libraryFile, new FileFacts("abc", 12L), BookMetadata.builder().title("From The File").build(), cover);
            BookEntity book = shellBook();
            when(bookCreatorService.createShellBook(any(), any(), any())).thenReturn(new BookCreatorService.ShellBook(book, false, true));
            when(fileService.writeRenderedCover(BOOK_ID, cover)).thenReturn(true);

            processor.persistPrepared(libraryFile, prepared);

            assertThat(processor.applied).isSameAs(prepared);
            assertThat(book.getBookCoverHash()).isNotNull();
        }

        @Test
        void restoredBookWithALockedField_keepsAllOfItsMetadataButTakesTheCover() throws Exception {
            var libraryFile = bookFile("books", "book.epub", LibraryOrganizationMode.BOOK_PER_FILE);
            RenderedCover cover = new RenderedCover(CoverKind.BOOK, new byte[]{1}, new byte[]{2});
            BookEntity book = shellBook();
            book.getMetadata().setSeriesNumberLocked(true);
            when(bookCreatorService.createShellBook(any(), any(), any())).thenReturn(new BookCreatorService.ShellBook(book, false, true));
            when(fileService.writeRenderedCover(BOOK_ID, cover)).thenReturn(true);

            processor.persistPrepared(libraryFile, prepared(libraryFile, new FileFacts("abc", 12L), BookMetadata.builder().title("From The File").build(), cover));

            assertThat(processor.applied).isNull();
            assertThat(book.getBookCoverHash()).isNotNull();
        }

        @Test
        void restoredBookWithALockedCover_keepsItsCoverButTakesTheMetadata() throws Exception {
            var libraryFile = bookFile("books", "book.epub", LibraryOrganizationMode.BOOK_PER_FILE);
            RenderedCover cover = new RenderedCover(CoverKind.BOOK, new byte[]{1}, new byte[]{2});
            PreparedBookFile prepared = prepared(libraryFile, new FileFacts("abc", 12L), BookMetadata.builder().title("From The File").build(), cover);
            BookEntity book = shellBook();
            book.setBookCoverHash("curated");
            book.getMetadata().setCoverLocked(true);
            when(bookCreatorService.createShellBook(any(), any(), any())).thenReturn(new BookCreatorService.ShellBook(book, false, true));

            processor.persistPrepared(libraryFile, prepared);

            assertThat(processor.applied).isSameAs(prepared);
            verify(fileService, never()).writeRenderedCover(anyLong(), any());
            assertThat(book.getBookCoverHash()).isEqualTo("curated");
        }

        @Test
        void restoredAudiobookWithOnlyItsEbookCoverLocked_takesTheAudiobookCover() throws Exception {
            var libraryFile = bookFile("books", "book.m4b", LibraryOrganizationMode.BOOK_PER_FILE);
            RenderedCover cover = new RenderedCover(CoverKind.AUDIOBOOK, new byte[]{1}, new byte[]{2});
            BookEntity book = shellBook();
            book.getMetadata().setCoverLocked(true);
            when(bookCreatorService.createShellBook(any(), any(), any())).thenReturn(new BookCreatorService.ShellBook(book, false, true));
            when(fileService.writeRenderedCover(BOOK_ID, cover)).thenReturn(true);

            processor.persistPrepared(libraryFile, prepared(libraryFile, new FileFacts("abc", 12L), null, cover));

            assertThat(book.getAudiobookCoverHash()).isNotNull();
        }

        private List<TransactionSynchronization> persistWithSynchronization(boolean created) throws Exception {
            var libraryFile = bookFile("books", "book.epub", LibraryOrganizationMode.BOOK_PER_FILE);
            RenderedCover cover = new RenderedCover(CoverKind.BOOK, new byte[]{1}, new byte[]{2});
            BookEntity book = shellBook();
            // A book not created is one restored here: only a new or a restored book takes the file's cover
            when(bookCreatorService.createShellBook(any(), any(), any())).thenReturn(new BookCreatorService.ShellBook(book, created, !created));
            when(fileService.writeRenderedCover(BOOK_ID, cover)).thenReturn(true);
            TransactionSynchronizationManager.initSynchronization();
            try {
                processor.persistPrepared(libraryFile, prepared(libraryFile, new FileFacts("abc", 12L), null, cover));
                return TransactionSynchronizationManager.getSynchronizations();
            } finally {
                TransactionSynchronizationManager.clearSynchronization();
            }
        }
    }

    // ========== processFile ==========

    @Nested
    class ProcessFile {

        @BeforeEach
        void cropPolicy() {
            lenient().when(fileService.currentCropPolicy()).thenReturn(CropPolicy.NONE);
        }

        @Test
        void preparesThenPersistsThenWritesTheSidecar() throws Exception {
            var libraryFile = bookFile("books", "book.epub", LibraryOrganizationMode.BOOK_PER_FILE);
            BookEntity book = shellBook();
            Book dto = Book.builder().id(BOOK_ID).build();
            var job = new SidecarMetadataWriter.SidecarJob(BOOK_ID, tempDir, tempDir, "{}", null, null);
            when(bookCreatorService.createShellBook(eq(libraryFile), eq(BookFileType.EPUB), any(FileFacts.class))).thenReturn(new BookCreatorService.ShellBook(book, true));
            when(bookMapper.toBook(book)).thenReturn(dto);
            when(sidecarMetadataWriter.isWriteOnScanEnabled()).thenReturn(true);
            when(sidecarMetadataWriter.prepareForScan(book)).thenReturn(Optional.of(job));

            var result = processor.processFile(libraryFile);

            assertThat(result.getBook()).isSameAs(dto);
            assertThat(book.getPrimaryBookFile().getCurrentHash()).isEqualTo(FileFingerprint.generateHash(libraryFile.getFullPath()));
            verify(sidecarMetadataWriter).execute(job);
        }

        @Test
        void sidecarThatFailsToWrite_doesNotFailTheBook() throws Exception {
            var libraryFile = bookFile("books", "book.epub", LibraryOrganizationMode.BOOK_PER_FILE);
            BookEntity book = shellBook();
            var job = new SidecarMetadataWriter.SidecarJob(BOOK_ID, tempDir, tempDir, "{}", null, null);
            when(bookCreatorService.createShellBook(any(), any(), any())).thenReturn(new BookCreatorService.ShellBook(book, true));
            when(sidecarMetadataWriter.isWriteOnScanEnabled()).thenReturn(true);
            when(sidecarMetadataWriter.prepareForScan(book)).thenReturn(Optional.of(job));
            doThrow(new IllegalStateException("broken")).when(sidecarMetadataWriter).execute(job);

            processor.processFile(libraryFile);

            verify(bookCreatorService).saveConnections(book, null);
        }

        @Test
        void checkedExceptionFromTheFormat_comesOutUnchecked() throws Exception {
            var libraryFile = bookFile("books", "book.epub", LibraryOrganizationMode.BOOK_PER_FILE);
            processor.failure = new IOException("cannot read");

            assertThatThrownBy(() -> processor.processFile(libraryFile))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Failed to read book.epub: cannot read")
                    .hasCauseInstanceOf(IOException.class);
            verifyNoInteractions(bookCreatorService);
        }
    }

    // ========== helpers ==========

    private PreparedBookFile prepared(LibraryFile libraryFile, FileFacts facts, BookMetadata metadata, RenderedCover cover) {
        return new PreparedBookFile(PrepareRequest.of(libraryFile, CropPolicy.NONE), facts, metadata, cover, null);
    }

    private BookEntity shellBook() {
        BookEntity book = new BookEntity();
        book.setId(BOOK_ID);
        BookFileEntity file = new BookFileEntity();
        file.setFileName("book.epub");
        file.setBookType(BookFileType.EPUB);
        file.setBookFormat(true);
        book.getBookFiles().add(file);
        book.setMetadata(new BookMetadataEntity());
        return book;
    }

    /** A library file for a book file that exists, with some content to hash. */
    private LibraryFile bookFile(String subPath, String fileName, LibraryOrganizationMode mode) throws IOException {
        var libraryFile = buildLibraryFile(tempDir, subPath, fileName, false, mode);
        Files.createDirectories(libraryFile.getFullPath().getParent());
        Files.writeString(libraryFile.getFullPath(), "content of " + fileName);
        return libraryFile;
    }

    private void createTestImage(Path path) throws IOException {
        var image = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
        ImageIO.write(image, "jpg", path.toFile());
    }

    private LibraryFile buildLibraryFile(Path libraryRoot, String subPath, String fileName,
                                         boolean folderBased, LibraryOrganizationMode mode) {
        var libraryPath = new LibraryPathEntity();
        libraryPath.setPath(libraryRoot.toString());

        var library = new LibraryEntity();
        library.setOrganizationMode(mode);

        return LibraryFile.builder()
                .libraryEntity(library)
                .libraryPathEntity(libraryPath)
                .fileSubPath(subPath)
                .fileName(fileName)
                .bookFileType(BookFileType.EPUB)
                .folderBased(folderBased)
                .build();
    }

    /**
     * Minimal concrete subclass whose format part returns what the test sets and records what it was given.
     */
    static class TestableFileProcessor extends AbstractFileProcessor {

        ExtractedContent content = new ExtractedContent(null, null, null);
        Exception failure;
        CoverKind kind = CoverKind.BOOK;
        int reads;
        FolderSnapshot folderSeen;
        PreparedBookFile applied;

        TestableFileProcessor(BookRepository bookRepository,
                              BookAdditionalFileRepository bookAdditionalFileRepository,
                              BookCreatorService bookCreatorService,
                              BookMapper bookMapper,
                              FileService fileService,
                              MetadataMatchService metadataMatchService,
                              SidecarMetadataWriter sidecarMetadataWriter) {
            super(bookRepository, bookAdditionalFileRepository, bookCreatorService,
                    bookMapper, fileService, metadataMatchService, sidecarMetadataWriter);
        }

        @Override
        protected ExtractedContent readContent(PrepareRequest request, FileFacts facts, FolderSnapshot folder,
                                               CoverMaker covers, PrepareContext ctx) throws Exception {
            reads++;
            folderSeen = folder;
            if (failure != null) {
                throw failure;
            }
            return content;
        }

        @Override
        protected void applyMetadata(BookEntity bookEntity, PreparedBookFile prepared) {
            applied = prepared;
        }

        @Override
        protected CoverKind coverKind() {
            return kind;
        }

        @Override
        public boolean generateCover(BookEntity bookEntity) {
            return false;
        }

        @Override
        public List<BookFileType> getSupportedTypes() {
            return List.of(BookFileType.EPUB);
        }

        Path exposedGetBookFolderForCoverFallback(LibraryFile libraryFile) {
            return getBookFolderForCoverFallback(libraryFile);
        }
    }
}
