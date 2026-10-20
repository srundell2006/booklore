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
import org.booklore.service.metadata.MetadataMatchService;
import org.booklore.service.metadata.extractor.EpubMetadataExtractor;
import org.booklore.service.metadata.sidecar.SidecarMetadataWriter;
import org.booklore.util.FileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
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

    @BeforeEach
    void setUp() {
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
    void processNewFile_ReadsMetadataAndCoverWithOneExtraction() throws IOException {
        LibraryFile libraryFile = libraryFile();
        BookEntity bookEntity = shellBook();
        File epubFile = tempDir.resolve("book.epub").toFile();
        BookMetadata metadata = BookMetadata.builder().title("The Title").authors(List.of("An Author")).build();
        when(bookCreatorService.createShellBook(libraryFile, BookFileType.EPUB)).thenReturn(bookEntity);
        when(epubMetadataExtractor.extractMetadataAndCover(epubFile)).thenReturn(new EpubMetadataExtractor.MetadataAndCover(metadata, jpeg()));
        when(fileService.saveCoverImages(any(BufferedImage.class), eq(1L))).thenReturn(true);

        BookEntity result = epubProcessor.processNewFile(libraryFile);

        assertThat(result.getMetadata().getTitle()).isEqualTo("The Title");
        assertThat(result.getMetadata().getCoverUpdatedOn()).isNotNull();
        assertThat(result.getBookCoverHash()).isNotNull();
        verify(bookCreatorService).addAuthorsToBook(List.of("An Author"), bookEntity);
        verify(epubMetadataExtractor, never()).extractMetadata(any());
        verify(epubMetadataExtractor, never()).extractCover(any());
    }

    @Test
    void processNewFile_WithoutCoverSavesNone() throws IOException {
        LibraryFile libraryFile = libraryFile();
        BookEntity bookEntity = shellBook();
        when(bookCreatorService.createShellBook(libraryFile, BookFileType.EPUB)).thenReturn(bookEntity);
        when(epubMetadataExtractor.extractMetadataAndCover(any(File.class))).thenReturn(new EpubMetadataExtractor.MetadataAndCover(null, null));

        BookEntity result = epubProcessor.processNewFile(libraryFile);

        assertThat(result.getBookCoverHash()).isNull();
        assertThat(result.getMetadata().getCoverUpdatedOn()).isNull();
        verify(fileService, never()).saveCoverImages(any(), anyLong());
    }

    @Test
    void processNewFile_UndecodableCoverSavesNone() throws IOException {
        LibraryFile libraryFile = libraryFile();
        BookEntity bookEntity = shellBook();
        when(bookCreatorService.createShellBook(libraryFile, BookFileType.EPUB)).thenReturn(bookEntity);
        when(epubMetadataExtractor.extractMetadataAndCover(any(File.class)))
                .thenReturn(new EpubMetadataExtractor.MetadataAndCover(null, "<svg/>".getBytes()));

        BookEntity result = epubProcessor.processNewFile(libraryFile);

        assertThat(result.getBookCoverHash()).isNull();
        verify(fileService, never()).saveCoverImages(any(), anyLong());
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
