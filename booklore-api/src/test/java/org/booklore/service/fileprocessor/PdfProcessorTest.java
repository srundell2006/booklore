package org.booklore.service.fileprocessor;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.RandomAccessRead;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.booklore.config.AppProperties;
import org.booklore.mapper.BookMapper;
import org.booklore.model.dto.settings.AppSettings;
import org.booklore.model.dto.settings.CoverCroppingSettings;
import org.booklore.model.dto.settings.LibraryFile;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.entity.LibraryPathEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.repository.BookAdditionalFileRepository;
import org.booklore.repository.BookRepository;
import org.booklore.service.appsettings.AppSettingService;
import org.booklore.service.book.BookCreatorService;
import org.booklore.service.fileprocessor.prepare.FileFacts;
import org.booklore.service.fileprocessor.prepare.HeapReservation;
import org.booklore.service.fileprocessor.prepare.IoCallable;
import org.booklore.service.fileprocessor.prepare.PrepareContext;
import org.booklore.service.fileprocessor.prepare.PrepareRequest;
import org.booklore.service.fileprocessor.prepare.PreparedBookFile;
import org.booklore.service.metadata.MetadataMatchService;
import org.booklore.service.metadata.extractor.PdfMetadataExtractor;
import org.booklore.service.metadata.sidecar.SidecarMetadataWriter;
import org.booklore.util.FileService;
import org.booklore.util.cover.CropPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestTemplate;

import javax.imageio.ImageIO;
import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PdfProcessorTest {

    private static final long BOOK_ID = 1L;
    private static final long BASELINE_BOOK_ID = 2L;
    private static final PDRectangle LETTER_LANDSCAPE = new PDRectangle(PDRectangle.LETTER.getHeight(), PDRectangle.LETTER.getWidth());

    @Mock private BookRepository bookRepository;
    @Mock private BookAdditionalFileRepository bookAdditionalFileRepository;
    @Mock private BookCreatorService bookCreatorService;
    @Mock private BookMapper bookMapper;
    @Mock private MetadataMatchService metadataMatchService;
    @Mock private SidecarMetadataWriter sidecarMetadataWriter;
    @Mock private AppProperties appProperties;
    @Mock private AppSettingService appSettingService;

    @TempDir
    Path tempDir;

    // Booklore's defaults: no cover cropping
    private CoverCroppingSettings coverCroppingSettings = CoverCroppingSettings.builder().aspectRatioThreshold(2.5).build();
    private FileService fileService;
    private PdfMetadataExtractor pdfMetadataExtractor;
    private PdfProcessor pdfProcessor;

    // The spies record every call, page renders included, and Mockito's inline mock maker keeps a spy whose recorded
    // calls point back at it alive for good. Without this the renders pile up across the test suite's heap.
    @AfterEach
    void dropRecordedRenders() {
        Mockito.clearInvocations(fileService, pdfMetadataExtractor);
    }

    @BeforeEach
    void setUp() {
        lenient().when(appProperties.getPathConfig()).thenReturn(tempDir.resolve("config").toString());
        lenient().when(appSettingService.getAppSettings())
                .thenAnswer(_ -> AppSettings.builder().coverCroppingSettings(coverCroppingSettings).build());
        fileService = spy(new FileService(appProperties, mock(RestTemplate.class), appSettingService, mock(RestTemplate.class)));
        pdfMetadataExtractor = spy(new PdfMetadataExtractor());
        pdfProcessor = new PdfProcessor(
                bookRepository,
                bookAdditionalFileRepository,
                bookCreatorService,
                bookMapper,
                fileService,
                metadataMatchService,
                sidecarMetadataWriter,
                pdfMetadataExtractor
        );
    }

    @Nested
    class ProcessFileTests {

        private void givenShellBook(LibraryFile libraryFile, BookEntity book) {
            when(bookCreatorService.createShellBook(eq(libraryFile), eq(BookFileType.PDF), any(FileFacts.class)))
                    .thenReturn(new BookCreatorService.ShellBook(book, true));
        }

        @Test
        void loadsThePdfOnceForMetadataAndCover() throws Exception {
            Path pdf = writePdf("book.pdf", PDRectangle.LETTER, 0, doc -> {
                PDDocumentInformation info = new PDDocumentInformation();
                info.setTitle("Shared Load");
                info.setAuthor("Ann Author");
                doc.setDocumentInformation(info);
            });
            BookEntity book = bookFor(pdf, BOOK_ID);
            LibraryFile libraryFile = libraryFileFor(pdf);
            givenShellBook(libraryFile, book);

            try (MockedStatic<Loader> loader = mockStatic(Loader.class, CALLS_REAL_METHODS)) {
                pdfProcessor.processFile(libraryFile);
                loader.verify(() -> Loader.loadPDF(any(RandomAccessRead.class)), times(1));
            }

            verify(pdfMetadataExtractor, never()).extractMetadata(any(File.class));
            verify(pdfMetadataExtractor).extractMetadata(any(PDDocument.class), eq(pdf.toFile()));
            assertThat(book.getMetadata().getTitle()).isEqualTo("Shared Load");
            assertThat(book.getMetadata().getPageCount()).isEqualTo(1);
            verify(bookCreatorService).addAuthorsToBook(List.of("Ann Author"), book);
            assertThat(book.getBookCoverHash()).isNotNull();
            assertThat(book.getMetadata().getCoverUpdatedOn()).isNotNull();
            assertThat(Path.of(fileService.getCoverFile(BOOK_ID))).exists();
        }

        @Test
        void usesTheFileNameAsTitleWhenThePdfHasNone() throws Exception {
            Path pdf = writePdf("Untitled Book.pdf", PDRectangle.LETTER, 0, _ -> {});
            BookEntity book = bookFor(pdf, BOOK_ID);
            LibraryFile libraryFile = libraryFileFor(pdf);
            givenShellBook(libraryFile, book);

            pdfProcessor.processFile(libraryFile);

            assertThat(book.getMetadata().getTitle()).isEqualTo("Untitled Book");
        }

        @Test
        void keepsMetadataWhenRenderingRunsOutOfMemory() throws Exception {
            Path pdf = writePdf("book.pdf", PDRectangle.LETTER, 0, doc -> {
                PDDocumentInformation info = new PDDocumentInformation();
                info.setTitle("Survives OOM");
                doc.setDocumentInformation(info);
            });
            BookEntity book = bookFor(pdf, BOOK_ID);
            LibraryFile libraryFile = libraryFileFor(pdf);
            givenShellBook(libraryFile, book);
            doThrow(new OutOfMemoryError("test")).when(pdfMetadataExtractor).renderCover(any(PDDocument.class), anyFloat());

            pdfProcessor.processFile(libraryFile);

            assertThat(book.getMetadata().getTitle()).isEqualTo("Survives OOM");
            assertThat(book.getBookCoverHash()).isNull();
            verify(fileService, never()).writeRenderedCover(anyLong(), any());
        }

        @Test
        void unreadablePdf_leavesTheShellBook() throws Exception {
            Path pdf = tempDir.resolve("broken.pdf");
            Files.writeString(pdf, "not a pdf");
            BookEntity book = bookFor(pdf, BOOK_ID);
            book.getMetadata().setTitle("Shell Title");
            LibraryFile libraryFile = libraryFileFor(pdf);
            givenShellBook(libraryFile, book);

            pdfProcessor.processFile(libraryFile);

            verify(bookCreatorService).saveConnections(book, null);
            assertThat(book.getMetadata().getTitle()).isEqualTo("Shell Title");
            assertThat(book.getBookCoverHash()).isNull();
            verify(pdfMetadataExtractor, never()).extractMetadata(any(PDDocument.class), any(File.class));
        }
    }

    @Nested
    class PrepareTests {

        @Test
        void aScannedPage_reservesHeapForDecodingItsImageAtFullSize() throws Exception {
            // PDFBox decodes all of the scan to draw the 1000x1414 cover from it
            Path scan = writePdf("scan.pdf", PDRectangle.A4, 0, doc -> {
                PDImageXObject image = JPEGFactory.createFromImage(doc, new BufferedImage(1240, 1754, BufferedImage.TYPE_INT_RGB));
                try (PDPageContentStream cs = new PDPageContentStream(doc, doc.getPage(0))) {
                    cs.drawImage(image, 0, 0, PDRectangle.A4.getWidth(), PDRectangle.A4.getHeight());
                }
            });
            Path blank = writePdf("blank.pdf", PDRectangle.A4, 0, _ -> {});

            assertThat(heapReservedToPrepare(scan) - heapReservedToPrepare(blank)).isEqualTo(12L * 1240 * 1754);
        }

        private long heapReservedToPrepare(Path pdf) throws Exception {
            List<Long> reservations = new ArrayList<>();
            PrepareContext ctx = new PrepareContext() {
                @Override
                public void checkpoint() {
                }

                @Override
                public <T> T render(long estimatedBytes, IoCallable<T> work) throws Exception {
                    throw new AssertionError("A PDF page render takes no render permit");
                }

                @Override
                public HeapReservation reserveHeap(long bytes) {
                    reservations.add(bytes);
                    return () -> {
                    };
                }

                @Override
                public void registerProcess(Process process) {
                }
            };

            PreparedBookFile prepared = pdfProcessor.prepare(PrepareRequest.of(libraryFileFor(pdf), CropPolicy.NONE), ctx);

            assertThat(prepared.cover()).isNotNull();
            assertThat(reservations).hasSize(1);
            return reservations.getFirst();
        }
    }

    @Nested
    class GenerateCoverTests {

        @Test
        void doesNotExtractMetadata() throws Exception {
            Path pdf = writePdf("book.pdf", PDRectangle.LETTER, 0, _ -> {});
            BookEntity book = bookFor(pdf, BOOK_ID);

            assertThat(pdfProcessor.generateCover(book)).isTrue();

            verify(pdfMetadataExtractor, never()).extractMetadata(any(PDDocument.class), any(File.class));
            verify(pdfMetadataExtractor, never()).extractMetadata(any(File.class));
        }

        @Test
        void returnsFalseWhenRenderingRunsOutOfMemory() throws Exception {
            Path pdf = writePdf("book.pdf", PDRectangle.LETTER, 0, _ -> {});
            doThrow(new OutOfMemoryError("test")).when(pdfMetadataExtractor).renderCover(any(PDDocument.class), anyFloat());

            assertThat(pdfProcessor.generateCover(bookFor(pdf, BOOK_ID))).isFalse();
        }

        @Test
        void returnsFalseOnNegativeArraySize() throws Exception {
            Path pdf = writePdf("book.pdf", PDRectangle.LETTER, 0, _ -> {});
            doThrow(new NegativeArraySizeException("test")).when(pdfMetadataExtractor).renderCover(any(PDDocument.class), anyFloat());

            assertThat(pdfProcessor.generateCover(bookFor(pdf, BOOK_ID))).isFalse();
        }

        @Test
        void letterPage_rendersOnlyAsLargeAsTheStoredCover() throws Exception {
            Path pdf = writePdf("book.pdf", PDRectangle.LETTER, 0, _ -> {});
            ArgumentCaptor<BufferedImage> rendered = ArgumentCaptor.forClass(BufferedImage.class);

            pdfProcessor.generateCover(bookFor(pdf, BOOK_ID));

            verify(fileService).saveCoverImages(rendered.capture(), eq(BOOK_ID));
            // 150 DPI rendered 1275x1649, which saveCoverImages then scaled down to 1000 wide
            assertThat(rendered.getValue().getWidth()).isEqualTo(1000);
            assertThat(rendered.getValue().getHeight()).isEqualTo(1294);
        }
    }

    /**
     * The stored cover and thumbnail must come out as they did when every page rendered at 150 DPI. Both paths round
     * pixel sizes down at several steps, so each side of the cover may differ by a pixel either way. Pages whose
     * aspect ratio sits on one of saveCoverImages' thresholds are the exception, tested separately below.
     */
    @Nested
    class StoredCoverTests {

        static Stream<Arguments> pages() {
            return Stream.of(
                    Arguments.of("Letter", PDRectangle.LETTER, 0),
                    Arguments.of("A4", PDRectangle.A4, 0),
                    Arguments.of("Letter landscape", LETTER_LANDSCAPE, 0),
                    Arguments.of("Tabloid landscape", new PDRectangle(1224, 792), 0),
                    Arguments.of("Landscape box turned 90", LETTER_LANDSCAPE, 90),
                    Arguments.of("Letter turned 270", PDRectangle.LETTER, 270),
                    // Large enough to need heavy downscaling; an A0 poster at 150 DPI would not fit the test heap
                    Arguments.of("A2 poster", PDRectangle.A2, 0),
                    Arguments.of("Tiny page", new PDRectangle(144, 216), 0),
                    Arguments.of("6x9 trade", new PDRectangle(432, 648), 0),
                    Arguments.of("Tall strip", new PDRectangle(500, 1600), 0),
                    // saveCoverImages still downscales these renders and rounds their free edge a pixel short of before
                    Arguments.of("A2 landscape", new PDRectangle(PDRectangle.A2.getHeight(), PDRectangle.A2.getWidth()), 0),
                    Arguments.of("8.5x13 in", new PDRectangle(612, 936), 0)
            );
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("pages")
        void matchesThe150DpiCover(String name, PDRectangle box, int rotation) throws Exception {
            assertStoredCoverMatches150Dpi(writePdf("book.pdf", box, rotation, PdfProcessorTest::drawFrame));
        }

        static Stream<Arguments> croppedPages() {
            return Stream.of(
                    Arguments.of("Tall page, cropped to 2:3", new PDRectangle(600, 1500), 0),
                    Arguments.of("Two-page spread, cropped to 2:3", new PDRectangle(1224, 792), 0),
                    Arguments.of("Spread turned 90, cropped to 2:3", new PDRectangle(792, 1224), 90),
                    Arguments.of("Letter, not cropped", PDRectangle.LETTER, 0)
            );
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("croppedPages")
        void matchesThe150DpiCover_withCoverCropping(String name, PDRectangle box, int rotation) throws Exception {
            cropAtTheLowestThreshold();
            assertStoredCoverMatches150Dpi(writePdf("book.pdf", box, rotation, PdfProcessorTest::drawFrame));
        }

        @Test
        void pageOnTheSquareThumbnailBound_mayGetTheOtherThumbnailShape() throws Exception {
            // 8.5x10 in is exactly the 0.85 aspect ratio at which saveCoverImages starts making square thumbnails.
            // The 150 DPI cover rounded to 1000x1176, just above it; rendered at the stored size it is 1000x1177,
            // just below.
            Path pdf = writePdf("book.pdf", new PDRectangle(612, 720), 0, PdfProcessorTest::drawFrame);

            assertCoverMatches150Dpi(pdf);
            assertThat(imageSize(fileService.getThumbnailFile(BASELINE_BOOK_ID))).isEqualTo(new Dimension(250, 250));
            assertThat(imageSize(fileService.getThumbnailFile(BOOK_ID))).isEqualTo(new Dimension(250, 350));
        }

        @Test
        void pageOnTheCoverCroppingThreshold_mayNoLongerBeCropped() throws Exception {
            // A 3:2 spread is exactly on the threshold. Its 150 DPI render rounded to 2275x1516, just past it, so it
            // was cropped to 2:3; rendered 1500 tall it is 2250x1500, exactly 3:2 like the page, so it is not cropped.
            cropAtTheLowestThreshold();
            storeNewAnd150DpiCovers(writePdf("book.pdf", new PDRectangle(1092, 728), 0, PdfProcessorTest::drawFrame));

            assertThat(imageSize(fileService.getCoverFile(BASELINE_BOOK_ID))).isEqualTo(new Dimension(999, 1500));
            assertThat(imageSize(fileService.getCoverFile(BOOK_ID))).isEqualTo(new Dimension(1000, 666));
        }

        // The lowest threshold the settings page allows, so the most pages get cropped
        private void cropAtTheLowestThreshold() {
            coverCroppingSettings = CoverCroppingSettings.builder()
                    .verticalCroppingEnabled(true)
                    .horizontalCroppingEnabled(true)
                    .aspectRatioThreshold(1.5)
                    .build();
        }

        private void assertStoredCoverMatches150Dpi(Path pdf) throws Exception {
            assertCoverMatches150Dpi(pdf);
            assertThat(imageSize(fileService.getThumbnailFile(BOOK_ID))).isEqualTo(imageSize(fileService.getThumbnailFile(BASELINE_BOOK_ID)));
        }

        private void assertCoverMatches150Dpi(Path pdf) throws Exception {
            storeNewAnd150DpiCovers(pdf);
            Dimension cover = imageSize(fileService.getCoverFile(BOOK_ID));
            Dimension coverBefore = imageSize(fileService.getCoverFile(BASELINE_BOOK_ID));
            assertThat(cover.width).isCloseTo(coverBefore.width, within(1));
            assertThat(cover.height).isCloseTo(coverBefore.height, within(1));
        }

        // Stores the cover generateCover makes as BOOK_ID and the one a 150 DPI render made as BASELINE_BOOK_ID
        private void storeNewAnd150DpiCovers(Path pdf) throws Exception {
            assertThat(pdfProcessor.generateCover(bookFor(pdf, BOOK_ID))).isTrue();
            try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
                BufferedImage before = new PDFRenderer(doc).renderImageWithDPI(0, 150, ImageType.RGB);
                assertThat(fileService.saveCoverImages(before, BASELINE_BOOK_ID)).isTrue();
            }
        }
    }

    private Path writePdf(String fileName, PDRectangle box, int rotation, PdfCustomizer customizer) throws Exception {
        Path file = tempDir.resolve(fileName);
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(box);
            page.setRotation(rotation);
            doc.addPage(page);
            customizer.customize(doc);
            doc.save(file.toFile());
        }
        return file;
    }

    // Content to the page edges, so cover cropping sees a real image rather than a blank page
    private static void drawFrame(PDDocument doc) throws Exception {
        PDPage page = doc.getPage(0);
        PDRectangle box = page.getMediaBox();
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            cs.setLineWidth(8);
            cs.addRect(box.getLowerLeftX() + 4, box.getLowerLeftY() + 4, box.getWidth() - 8, box.getHeight() - 8);
            cs.moveTo(box.getLowerLeftX(), box.getLowerLeftY());
            cs.lineTo(box.getUpperRightX(), box.getUpperRightY());
            cs.stroke();
        }
    }

    private static Dimension imageSize(String path) throws Exception {
        BufferedImage image = ImageIO.read(new File(path));
        return new Dimension(image.getWidth(), image.getHeight());
    }

    private BookEntity bookFor(Path pdf, long id) {
        LibraryPathEntity libraryPath = new LibraryPathEntity();
        libraryPath.setPath(pdf.getParent().toString());

        BookEntity book = new BookEntity();
        book.setId(id);
        book.setLibraryPath(libraryPath);
        book.setMetadata(new BookMetadataEntity());

        BookFileEntity bookFile = new BookFileEntity();
        bookFile.setFileName(pdf.getFileName().toString());
        bookFile.setFileSubPath("");
        bookFile.setBook(book);
        book.getBookFiles().add(bookFile);
        return book;
    }

    private LibraryFile libraryFileFor(Path pdf) {
        LibraryPathEntity libraryPath = new LibraryPathEntity();
        libraryPath.setPath(pdf.getParent().toString());
        return LibraryFile.builder()
                .libraryEntity(LibraryEntity.builder().build())
                .libraryPathEntity(libraryPath)
                .fileSubPath("")
                .fileName(pdf.getFileName().toString())
                .bookFileType(BookFileType.PDF)
                .build();
    }

    @FunctionalInterface
    interface PdfCustomizer {
        void customize(PDDocument doc) throws Exception;
    }
}
