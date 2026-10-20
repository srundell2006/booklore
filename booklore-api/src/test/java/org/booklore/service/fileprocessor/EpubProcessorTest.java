package org.booklore.service.fileprocessor;

import org.booklore.mapper.BookMapper;
import org.booklore.model.dto.BookMetadata;
import org.booklore.model.dto.settings.LibraryFile;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.entity.LibraryPathEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.repository.BookAdditionalFileRepository;
import org.booklore.repository.BookRepository;
import org.booklore.service.book.BookCreatorService;
import org.booklore.service.fileprocessor.prepare.FileFacts;
import org.booklore.service.metadata.MetadataMatchService;
import org.booklore.service.metadata.extractor.EpubMetadataExtractor;
import org.booklore.service.metadata.sidecar.SidecarMetadataWriter;
import org.booklore.util.FileService;
import org.booklore.util.cover.CoverKind;
import org.booklore.util.cover.CropPolicy;
import org.booklore.util.cover.RenderedCover;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EpubProcessorTest {

    @Mock private BookRepository bookRepository;
    @Mock private BookAdditionalFileRepository bookAdditionalFileRepository;
    @Mock private BookCreatorService bookCreatorService;
    @Mock private BookMapper bookMapper;
    @Mock private FileService fileService;
    @Mock private MetadataMatchService metadataMatchService;
    @Mock private SidecarMetadataWriter sidecarMetadataWriter;
    @Mock private EpubMetadataExtractor epubMetadataExtractor;

    private EpubProcessor epubProcessor;

    @TempDir
    Path tempDir;

    @AfterEach
    void dropRecordedCovers() {
        Mockito.clearInvocations(fileService);
    }

    @BeforeEach
    void setUp() throws IOException {
        // processFile hashes the book before reading it
        Files.writeString(tempDir.resolve("book.epub"), "epub");
        lenient().when(fileService.currentCropPolicy()).thenReturn(CropPolicy.NONE);
        epubProcessor = new EpubProcessor(
                bookRepository,
                bookAdditionalFileRepository,
                bookCreatorService,
                bookMapper,
                fileService,
                metadataMatchService,
                sidecarMetadataWriter,
                epubMetadataExtractor
        );
    }

    @Test
    void processFile_ReadsMetadataAndCoverWithOneExtraction() throws IOException {
        LibraryFile libraryFile = libraryFile();
        BookEntity bookEntity = shellBook();
        File epubFile = tempDir.resolve("book.epub").toFile();
        BookMetadata metadata = BookMetadata.builder().title("The Title").authors(List.of("An Author")).build();
        when(bookCreatorService.createShellBook(eq(libraryFile), eq(BookFileType.EPUB), any(FileFacts.class))).thenReturn(new BookCreatorService.ShellBook(bookEntity, true));
        when(epubMetadataExtractor.extractMetadataAndCover(epubFile)).thenReturn(new EpubMetadataExtractor.MetadataAndCover(metadata, jpeg()));
        when(fileService.writeRenderedCover(eq(1L), any(RenderedCover.class))).thenReturn(true);

        epubProcessor.processFile(libraryFile);

        assertThat(bookEntity.getMetadata().getTitle()).isEqualTo("The Title");
        assertThat(bookEntity.getMetadata().getCoverUpdatedOn()).isNotNull();
        assertThat(bookEntity.getBookCoverHash()).isNotNull();
        ArgumentCaptor<RenderedCover> cover = ArgumentCaptor.forClass(RenderedCover.class);
        verify(fileService).writeRenderedCover(eq(1L), cover.capture());
        assertThat(cover.getValue().kind()).isEqualTo(CoverKind.BOOK);
        verify(bookCreatorService).addAuthorsToBook(List.of("An Author"), bookEntity);
        verify(epubMetadataExtractor, never()).extractMetadata(any());
        verify(epubMetadataExtractor, never()).extractCover(any());
    }

    @Test
    void processFile_WithoutCoverSavesNone() throws IOException {
        LibraryFile libraryFile = libraryFile();
        BookEntity bookEntity = shellBook();
        when(bookCreatorService.createShellBook(eq(libraryFile), eq(BookFileType.EPUB), any(FileFacts.class))).thenReturn(new BookCreatorService.ShellBook(bookEntity, true));
        when(epubMetadataExtractor.extractMetadataAndCover(any(File.class))).thenReturn(new EpubMetadataExtractor.MetadataAndCover(null, null));

        epubProcessor.processFile(libraryFile);

        assertThat(bookEntity.getBookCoverHash()).isNull();
        assertThat(bookEntity.getMetadata().getCoverUpdatedOn()).isNull();
        verify(fileService, never()).writeRenderedCover(anyLong(), any());
    }

    @Test
    void processFile_UndecodableCoverSavesNone() throws IOException {
        LibraryFile libraryFile = libraryFile();
        BookEntity bookEntity = shellBook();
        when(bookCreatorService.createShellBook(eq(libraryFile), eq(BookFileType.EPUB), any(FileFacts.class))).thenReturn(new BookCreatorService.ShellBook(bookEntity, true));
        when(epubMetadataExtractor.extractMetadataAndCover(any(File.class)))
                .thenReturn(new EpubMetadataExtractor.MetadataAndCover(null, "<svg/>".getBytes()));

        epubProcessor.processFile(libraryFile);

        assertThat(bookEntity.getBookCoverHash()).isNull();
        verify(fileService, never()).writeRenderedCover(anyLong(), any());
    }

    @Test
    void generateCover_ExtractsCoverFromTheBookFile() throws IOException {
        BookEntity bookEntity = shellBook();
        when(epubMetadataExtractor.extractCover(tempDir.resolve("book.epub").toFile())).thenReturn(jpeg());
        when(fileService.saveCoverImages(any(BufferedImage.class), eq(1L))).thenReturn(true);

        assertThat(epubProcessor.generateCover(bookEntity, bookEntity.getPrimaryBookFile())).isTrue();

        verify(epubMetadataExtractor, never()).extractMetadataAndCover(any());
    }

    @Test
    void generateCover_NoCoverInEpubReturnsFalse() throws IOException {
        BookEntity bookEntity = shellBook();
        when(epubMetadataExtractor.extractCover(any(File.class))).thenReturn(null);

        assertThat(epubProcessor.generateCover(bookEntity)).isFalse();

        verify(fileService, never()).saveCoverImages(any(), anyLong());
    }

    private LibraryFile libraryFile() {
        LibraryPathEntity libraryPath = new LibraryPathEntity();
        libraryPath.setPath(tempDir.toString());
        return LibraryFile.builder()
                .libraryEntity(new LibraryEntity())
                .libraryPathEntity(libraryPath)
                .fileSubPath("")
                .fileName("book.epub")
                .bookFileType(BookFileType.EPUB)
                .build();
    }

    private BookEntity shellBook() {
        BookEntity bookEntity = new BookEntity();
        bookEntity.setId(1L);
        BookFileEntity bookFile = new BookFileEntity();
        bookFile.setFileName("book.epub");
        bookFile.setFileSubPath("");
        bookFile.setBook(bookEntity);
        bookEntity.getBookFiles().add(bookFile);
        LibraryPathEntity libraryPath = new LibraryPathEntity();
        libraryPath.setPath(tempDir.toString());
        bookEntity.setLibraryPath(libraryPath);
        bookEntity.setMetadata(new BookMetadataEntity());
        return bookEntity;
    }

    private static byte[] jpeg() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB), "jpg", out);
        return out.toByteArray();
    }
}
