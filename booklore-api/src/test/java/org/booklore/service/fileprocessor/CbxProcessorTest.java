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
import org.booklore.model.enums.LibraryOrganizationMode;
import org.booklore.repository.BookAdditionalFileRepository;
import org.booklore.repository.BookRepository;
import org.booklore.service.book.BookCreatorService;
import org.booklore.service.fileprocessor.prepare.FileFacts;
import org.booklore.service.fileprocessor.prepare.HeapReservation;
import org.booklore.service.fileprocessor.prepare.IoCallable;
import org.booklore.service.fileprocessor.prepare.PrepareAbandonedException;
import org.booklore.service.fileprocessor.prepare.PrepareContext;
import org.booklore.service.fileprocessor.prepare.PrepareRequest;
import org.booklore.service.metadata.MetadataMatchService;
import org.booklore.service.metadata.extractor.CbxMetadataExtractor;
import org.booklore.service.metadata.sidecar.SidecarMetadataWriter;
import org.booklore.util.ArchiveUtils;
import org.booklore.util.CbxArchive;
import org.booklore.util.CbxTestArchives.Entry;
import org.booklore.util.CbxTestArchives.Format;
import org.booklore.util.FileService;
import org.booklore.util.UnrarHelper;
import org.booklore.util.cover.CoverKind;
import org.booklore.util.cover.CropPolicy;
import org.booklore.util.cover.RenderedCover;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.booklore.util.CbxTestArchives.SOLID_CB7_COMIC_INFO_FIRST;
import static org.booklore.util.CbxTestArchives.SOLID_CB7_COMIC_INFO_LAST;
import static org.booklore.util.CbxTestArchives.comicInfo;
import static org.booklore.util.CbxTestArchives.dir;
import static org.booklore.util.CbxTestArchives.file;
import static org.booklore.util.CbxTestArchives.image;
import static org.booklore.util.CbxTestArchives.imageFile;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CbxProcessorTest {

    @Mock private BookRepository bookRepository;
    @Mock private BookAdditionalFileRepository bookAdditionalFileRepository;
    @Mock private BookCreatorService bookCreatorService;
    @Mock private BookMapper bookMapper;
    @Mock private FileService fileService;
    @Mock private MetadataMatchService metadataMatchService;
    @Mock private SidecarMetadataWriter sidecarMetadataWriter;
    @Mock private CbxMetadataExtractor cbxMetadataExtractor;

    private CbxProcessor cbxProcessor;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        cbxProcessor = processorWith(cbxMetadataExtractor);
    }

    @AfterEach
    void dropRecordedCovers() {
        Mockito.clearInvocations(fileService);
    }

    private CbxProcessor processorWith(CbxMetadataExtractor extractor) {
        return new CbxProcessor(
                bookRepository,
                bookAdditionalFileRepository,
                bookCreatorService,
                bookMapper,
                fileService,
                metadataMatchService,
                sidecarMetadataWriter,
                extractor
        );
    }

    private Path archive(Format format, String baseName, Entry... entries) throws IOException {
        return format.write(tempDir, baseName, entries);
    }

    private BookEntity bookFor(Path file) {
        BookEntity bookEntity = new BookEntity();
        bookEntity.setId(1L);
        BookFileEntity bookFile = new BookFileEntity();
        bookFile.setFileName(file.getFileName().toString());
        bookFile.setFileSubPath("");
        bookFile.setBook(bookEntity);
        bookEntity.getBookFiles().add(bookFile);
        LibraryPathEntity libPath = new LibraryPathEntity();
        libPath.setPath(file.getParent().toString());
        bookEntity.setLibraryPath(libPath);
        bookEntity.setMetadata(new BookMetadataEntity());
        return bookEntity;
    }

    private LibraryFile libraryFileFor(Path file) {
        LibraryEntity library = new LibraryEntity();
        library.setOrganizationMode(LibraryOrganizationMode.BOOK_PER_FILE);
        LibraryPathEntity libPath = new LibraryPathEntity();
        libPath.setPath(file.getParent().toString());
        return LibraryFile.builder()
                .libraryEntity(library)
                .libraryPathEntity(libPath)
                .fileSubPath("")
                .fileName(file.getFileName().toString())
                .bookFileType(BookFileType.CBX)
                .build();
    }

    /** Width of the image handed to saveCoverImages; the test images are told apart by width. */
    private int savedCoverWidth() throws IOException {
        ArgumentCaptor<BufferedImage> captor = ArgumentCaptor.forClass(BufferedImage.class);
        verify(fileService).saveCoverImages(captor.capture(), eq(1L));
        return captor.getValue().getWidth();
    }

    private static byte[] notAnImage() {
        return "this is not image data".getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void generateCover_ZipNamedAsCbr_ShouldExtractImage() throws IOException {
        // Create a ZIP file named .cbr
        File zipAsCbr = tempDir.resolve("mismatched.cbr").toFile();
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipAsCbr))) {
            ZipEntry entry = new ZipEntry("cover.jpg");
            zos.putNextEntry(entry);
            BufferedImage image = new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB);
            ImageIO.write(image, "jpg", zos);
            zos.closeEntry();
        }

        BookEntity bookEntity = new BookEntity();
        bookEntity.setId(1L);

        // Create and configure the primary book file
        BookFileEntity bookFile = new BookFileEntity();
        bookFile.setFileName(zipAsCbr.getName());
        bookFile.setFileSubPath("");
        bookFile.setBook(bookEntity);
        bookEntity.getBookFiles().add(bookFile);
        LibraryPathEntity libPath = new LibraryPathEntity();
        libPath.setPath(tempDir.toString());
        bookEntity.setLibraryPath(libPath);
        bookEntity.setMetadata(new BookMetadataEntity());

        when(fileService.saveCoverImages(any(BufferedImage.class), eq(1L))).thenReturn(true);

        cbxProcessor.generateCover(bookEntity);

        verify(fileService).saveCoverImages(any(BufferedImage.class), eq(1L));
    }

    @Nested
    class CoverSelection {

        @ParameterizedTest
        @EnumSource(Format.class)
        void picksFirstImageByNameAcrossFoldersNotByArchiveOrder(Format format) throws IOException {
            Path file = archive(format, "nested",
                    dir("b"),
                    imageFile("b/page2.jpg", 2),
                    dir("a"),
                    imageFile("a/page10.jpg", 10),
                    file("ComicInfo.xml", comicInfo("<Title>Nested</Title>")),
                    file("notes.txt", "not an image"),
                    imageFile("a/page1.png", 1),
                    imageFile("Zebra.JPEG", 26));
            when(fileService.saveCoverImages(any(BufferedImage.class), eq(1L))).thenReturn(true);

            boolean generated = cbxProcessor.generateCover(bookFor(file));

            // Plain String order puts upper case first, so "Zebra.JPEG" sorts before "a/page1.png"
            assertThat(generated).isTrue();
            assertThat(savedCoverWidth()).isEqualTo(26);
        }

        @ParameterizedTest
        @EnumSource(Format.class)
        void usesLexicographicNotNaturalOrder(Format format) throws IOException {
            Path file = archive(format, "pages",
                    imageFile("Page9.jpg", 9),
                    imageFile("Page10.jpg", 10),
                    imageFile("Page11.jpg", 11));
            when(fileService.saveCoverImages(any(BufferedImage.class), eq(1L))).thenReturn(true);

            cbxProcessor.generateCover(bookFor(file));

            assertThat(savedCoverWidth()).isEqualTo(10);
        }

        @ParameterizedTest
        @EnumSource(Format.class)
        void considersOnlyJpgJpegPngAndWebpInAnyCase(Format format) throws IOException {
            Path file = archive(format, "extensions",
                    imageFile("!first.gif", 99),
                    imageFile("#second.bmp", 98),
                    file("$third.txt", "text"),
                    imageFile("page.WebP", 7),
                    imageFile("page.Png", 8));
            when(fileService.saveCoverImages(any(BufferedImage.class), eq(1L))).thenReturn(true);

            cbxProcessor.generateCover(bookFor(file));

            assertThat(savedCoverWidth()).isEqualTo(8);
        }

        @ParameterizedTest
        @EnumSource(Format.class)
        void ignoresComicInfoFrontCoverAndCoverLikeNames(Format format) throws IOException {
            Path file = archive(format, "frontcover",
                    file("ComicInfo.xml", comicInfo("""
                            <Pages>
                              <Page Image="2" Type="FrontCover" ImageFile="003.jpg"/>
                            </Pages>
                            """)),
                    imageFile("cover.jpg", 50),
                    imageFile("003.jpg", 3),
                    imageFile("001.jpg", 1),
                    imageFile("002.jpg", 2));
            when(fileService.saveCoverImages(any(BufferedImage.class), eq(1L))).thenReturn(true);

            cbxProcessor.generateCover(bookFor(file));

            assertThat(savedCoverWidth()).isEqualTo(1);
        }

        @ParameterizedTest
        @EnumSource(value = Format.class, names = {"CB7", "CBR"})
        void skipsDirectoryEntriesNamedLikeImages(Format format) throws IOException {
            Path file = archive(format, "dirs",
                    dir("chapter.jpg"),
                    imageFile("chapter.jpg/inner.png", 5),
                    imageFile("x.png", 7));
            when(fileService.saveCoverImages(any(BufferedImage.class), eq(1L))).thenReturn(true);

            cbxProcessor.generateCover(bookFor(file));

            assertThat(savedCoverWidth()).isEqualTo(5);
        }

        @ParameterizedTest
        @EnumSource(value = Format.class, names = {"CBZ", "CB7"})
        void zipAndSevenZipOnlyTryTheFirstImage(Format format) throws IOException {
            Path file = archive(format, "broken",
                    file("001.jpg", notAnImage()),
                    imageFile("002.jpg", 2));

            boolean generated = cbxProcessor.generateCover(bookFor(file));

            assertThat(generated).isFalse();
            verify(fileService, never()).saveCoverImages(any(), anyLong());
        }

        @Test
        void rarMovesOnToTheNextImageWhenOneCannotBeDecoded() throws IOException {
            Path file = archive(Format.CBR, "broken",
                    file("001.jpg", notAnImage()),
                    imageFile("002.jpg", 2));
            when(fileService.saveCoverImages(any(BufferedImage.class), eq(1L))).thenReturn(true);

            boolean generated = cbxProcessor.generateCover(bookFor(file));

            assertThat(generated).isTrue();
            assertThat(savedCoverWidth()).isEqualTo(2);
        }

        @ParameterizedTest
        @EnumSource(Format.class)
        void noCoverWhenArchiveHasNoUsableImage(Format format) throws IOException {
            Path file = archive(format, "noimages",
                    file("ComicInfo.xml", comicInfo("<Title>No Images</Title>")),
                    imageFile("cover.gif", 4),
                    file("readme.txt", "text"));

            boolean generated = cbxProcessor.generateCover(bookFor(file));

            assertThat(generated).isFalse();
            verify(fileService, never()).saveCoverImages(any(), anyLong());
        }

        @Test
        void noCoverForUnreadableArchive() throws IOException {
            Path file = Files.writeString(tempDir.resolve("garbage.cbz"), "not a zip at all");

            boolean generated = cbxProcessor.generateCover(bookFor(file));

            assertThat(generated).isFalse();
            verify(fileService, never()).saveCoverImages(any(), anyLong());
        }

        @Test
        void generatesCoverFromTheGivenBookFile() throws IOException {
            Path primary = archive(Format.CBZ, "primary", imageFile("p.jpg", 11));
            Path additional = archive(Format.CB7, "additional", imageFile("a.jpg", 22));
            BookEntity bookEntity = bookFor(primary);
            BookFileEntity additionalFile = new BookFileEntity();
            additionalFile.setFileName(additional.getFileName().toString());
            additionalFile.setFileSubPath("");
            additionalFile.setBook(bookEntity);
            when(fileService.saveCoverImages(any(BufferedImage.class), eq(1L))).thenReturn(true);

            boolean generated = cbxProcessor.generateCover(bookEntity, additionalFile);

            assertThat(generated).isTrue();
            assertThat(savedCoverWidth()).isEqualTo(22);
        }

        @Test
        void reportsFailureWhenCoverCannotBeSaved() throws IOException {
            Path file = archive(Format.CBZ, "unsaved", imageFile("p.jpg", 11));
            when(fileService.saveCoverImages(any(BufferedImage.class), eq(1L))).thenReturn(false);

            assertThat(cbxProcessor.generateCover(bookFor(file))).isFalse();
        }
    }

    @Nested
    class ProcessFile {

        private CbxProcessor processor;

        @BeforeEach
        void useRealExtractor() {
            processor = processorWith(new CbxMetadataExtractor());
            lenient().when(fileService.currentCropPolicy()).thenReturn(CropPolicy.NONE);
        }

        private BookEntity process(Path file) {
            BookEntity shell = bookFor(file);
            LibraryFile libraryFile = libraryFileFor(file);
            when(bookCreatorService.createShellBook(eq(libraryFile), eq(BookFileType.CBX), any(FileFacts.class)))
                    .thenReturn(new BookCreatorService.ShellBook(shell, true));
            processor.processFile(libraryFile);
            return shell;
        }

        /** Width of the cover written for the book; the test images are told apart by width. */
        private int writtenCoverWidth() throws IOException {
            ArgumentCaptor<RenderedCover> captor = ArgumentCaptor.forClass(RenderedCover.class);
            verify(fileService).writeRenderedCover(eq(1L), captor.capture());
            assertThat(captor.getValue().kind()).isEqualTo(CoverKind.BOOK);
            return ImageIO.read(new ByteArrayInputStream(captor.getValue().cover())).getWidth();
        }

        @SuppressWarnings("unchecked")
        private Collection<String> addedAuthors(BookEntity book) {
            ArgumentCaptor<Collection<String>> captor = ArgumentCaptor.forClass(Collection.class);
            verify(bookCreatorService).addAuthorsToBook(captor.capture(), eq(book));
            return captor.getValue();
        }

        @ParameterizedTest
        @EnumSource(Format.class)
        void takesCoverAndComicInfoFromTheArchive(Format format) throws IOException {
            Path file = archive(format, "My_Comic-01",
                    imageFile("pages/002.jpg", 2),
                    file("ComicInfo.xml", comicInfo("""
                            <Title>The Title</Title>
                            <Series>The Series</Series>
                            <Number>7</Number>
                            <Writer>Jane Doe, John Roe</Writer>
                            <Pages><Page Image="1" Type="FrontCover"/></Pages>
                            """)),
                    imageFile("pages/001.jpg", 1));
            when(fileService.writeRenderedCover(eq(1L), any(RenderedCover.class))).thenReturn(true);

            BookEntity book = process(file);

            assertThat(writtenCoverWidth()).isEqualTo(1);
            assertThat(book.getBookCoverHash()).isNotNull();
            assertThat(book.getMetadata().getCoverUpdatedOn()).isNotNull();
            assertThat(book.getPrimaryBookFile().getArchiveType()).isEqualTo(format.archiveType());
            assertThat(book.getMetadata().getTitle()).isEqualTo("The Title");
            assertThat(book.getMetadata().getSeriesName()).isEqualTo("The Series");
            assertThat(book.getMetadata().getSeriesNumber()).isEqualTo(7f);
            assertThat(addedAuthors(book)).containsExactlyInAnyOrder("Jane Doe", "John Roe");
        }

        @ParameterizedTest
        @EnumSource(Format.class)
        void usesFirstComicInfoInArchiveOrderEvenInASubfolder(Format format) throws IOException {
            Path file = archive(format, "two-infos",
                    imageFile("001.jpg", 1),
                    file("extras/comicinfo.XML", comicInfo("<Title>From Extras</Title>")),
                    file("ComicInfo.xml", comicInfo("<Title>From Root</Title>")));
            when(fileService.writeRenderedCover(eq(1L), any(RenderedCover.class))).thenReturn(true);

            BookEntity book = process(file);

            assertThat(book.getMetadata().getTitle()).isEqualTo("From Extras");
        }

        @ParameterizedTest
        @EnumSource(Format.class)
        void titleIsTheRawFileBaseNameWithoutComicInfo(Format format) throws IOException {
            Path file = archive(format, "My_Comic-01", imageFile("001.jpg", 1));
            when(fileService.writeRenderedCover(eq(1L), any(RenderedCover.class))).thenReturn(true);

            BookEntity book = process(file);

            assertThat(writtenCoverWidth()).isEqualTo(1);
            assertThat(book.getMetadata().getTitle()).isEqualTo("My_Comic-01");
        }

        @ParameterizedTest
        @EnumSource(Format.class)
        void titleIsTheRawFileBaseNameWhenComicInfoIsMalformed(Format format) throws IOException {
            Path file = archive(format, "My_Comic-01",
                    file("ComicInfo.xml", "<ComicInfo><Title>unclosed"),
                    imageFile("001.jpg", 1));
            when(fileService.writeRenderedCover(eq(1L), any(RenderedCover.class))).thenReturn(true);

            BookEntity book = process(file);

            assertThat(writtenCoverWidth()).isEqualTo(1);
            assertThat(book.getMetadata().getTitle()).isEqualTo("My_Comic-01");
        }

        @ParameterizedTest
        @EnumSource(Format.class)
        void readsComicInfoWhenThereIsNoCover(Format format) throws IOException {
            Path file = archive(format, "no-cover",
                    file("ComicInfo.xml", comicInfo("<Title>Text Only</Title>")),
                    file("story.txt", "words"));

            BookEntity book = process(file);

            verify(fileService, never()).writeRenderedCover(anyLong(), any());
            assertThat(book.getBookCoverHash()).isNull();
            assertThat(book.getMetadata().getTitle()).isEqualTo("Text Only");
        }

        private void assertCoverAndComicInfoFromSolidBlock(Path fixture, String title) throws IOException {
            Path file = Files.copy(fixture, tempDir.resolve(fixture.getFileName()));
            when(fileService.writeRenderedCover(eq(1L), any(RenderedCover.class))).thenReturn(true);

            BookEntity book = process(file);

            assertThat(writtenCoverWidth()).isEqualTo(1);
            assertThat(book.getPrimaryBookFile().getArchiveType()).isEqualTo(ArchiveUtils.ArchiveType.SEVEN_ZIP);
            assertThat(book.getMetadata().getTitle()).isEqualTo(title);
            assertThat(book.getMetadata().getSeriesName()).isEqualTo("Solid Series");
            assertThat(book.getMetadata().getSeriesNumber()).isEqualTo(3f);
        }

        @Test
        void takesCoverAndComicInfoAfterItFromOneSolidBlock() throws IOException {
            assertCoverAndComicInfoFromSolidBlock(SOLID_CB7_COMIC_INFO_LAST, "Solid Last");
        }

        @Test
        void takesCoverAndComicInfoBeforeItFromOneSolidBlock() throws IOException {
            assertCoverAndComicInfoFromSolidBlock(SOLID_CB7_COMIC_INFO_FIRST, "Solid First");
        }

        @Test
        void unreadableArchiveGetsNoCoverAndTheRawBaseNameAsTitle() throws IOException {
            Path file = Files.writeString(tempDir.resolve("Broken_Book-02.cbz"), "not a zip at all");

            BookEntity book = process(file);

            verify(fileService, never()).writeRenderedCover(anyLong(), any());
            assertThat(book.getPrimaryBookFile().getArchiveType()).isEqualTo(ArchiveUtils.ArchiveType.ZIP);
            assertThat(book.getMetadata().getTitle()).isEqualTo("Broken_Book-02");
        }

        @Test
        void fallsBackToCleanedFileNameWhenExtractorReturnsNothing() throws IOException {
            Path file = archive(Format.CBZ, "My_Comic-01", imageFile("001.jpg", 1));
            when(fileService.writeRenderedCover(eq(1L), any(RenderedCover.class))).thenReturn(true);
            processor = cbxProcessor;

            BookEntity book = process(file);

            assertThat(book.getMetadata().getTitle()).isEqualTo("My Comic 01");
        }

        @Test
        void rarThatJunrarCannotReadGoesThroughUnrarCli() throws IOException {
            Path file = tempDir.resolve("rar5.cbr");
            Files.copy(Path.of("src/test/resources/cbx/test-rar5.cbr"), file);
            byte[] xml = comicInfo("<Title>Via CLI</Title>").getBytes(StandardCharsets.UTF_8);
            when(fileService.writeRenderedCover(eq(1L), any(RenderedCover.class))).thenReturn(true);

            try (MockedStatic<UnrarHelper> unrar = mockStatic(UnrarHelper.class)) {
                unrar.when(UnrarHelper::isAvailable).thenReturn(true);
                unrar.when(() -> UnrarHelper.listEntries(any(Path.class)))
                        .thenReturn(List.of("pages", "pages/001.jpg", "pages/002.jpg", "ComicInfo.xml"));
                unrar.when(() -> UnrarHelper.extractEntryBytes(any(Path.class), eq("pages/001.jpg"))).thenReturn(notAnImage());
                unrar.when(() -> UnrarHelper.extractEntryBytes(any(Path.class), eq("pages/002.jpg"))).thenReturn(image("002.jpg", 2));
                unrar.when(() -> UnrarHelper.extractEntryBytes(any(Path.class), eq("ComicInfo.xml"))).thenReturn(xml);

                BookEntity book = process(file);

                assertThat(writtenCoverWidth()).isEqualTo(2);
                assertThat(book.getPrimaryBookFile().getArchiveType()).isEqualTo(ArchiveUtils.ArchiveType.RAR);
                assertThat(book.getMetadata().getTitle()).isEqualTo("Via CLI");
                // One listing serves both the cover and ComicInfo.xml
                unrar.verify(() -> UnrarHelper.listEntries(any(Path.class)), times(1));
            }
        }

        @Test
        void rarGivenUpOnWhileExtractingAnImageTriesNoFurtherImage() throws IOException {
            Path file = tempDir.resolve("rar5.cbr");
            Files.copy(Path.of("src/test/resources/cbx/test-rar5.cbr"), file);
            AtomicBoolean givenUp = new AtomicBoolean();
            PrepareContext ctx = new PrepareContext() {
                @Override
                public void checkpoint() {
                    if (givenUp.get()) {
                        throw new PrepareAbandonedException("Gave up on rar5.cbr");
                    }
                }

                @Override
                public <T> T render(long estimatedBytes, IoCallable<T> work) throws Exception {
                    return work.call();
                }

                @Override
                public HeapReservation reserveHeap(long bytes) {
                    return () -> {
                    };
                }

                @Override
                public void registerProcess(Process process) {
                }
            };

            try (MockedStatic<UnrarHelper> unrar = mockStatic(UnrarHelper.class)) {
                unrar.when(UnrarHelper::isAvailable).thenReturn(true);
                unrar.when(() -> UnrarHelper.listEntries(any(Path.class)))
                        .thenReturn(List.of("pages/001.jpg", "pages/002.jpg", "pages/003.jpg"));
                // The file's deadline passes while unrar extracts the first image: the import kills unrar, and every
                // unrar started for the file after that, and interrupts the thread
                unrar.when(() -> UnrarHelper.extractEntryBytes(any(Path.class), anyString())).thenAnswer(invocation -> {
                    givenUp.set(true);
                    throw new IOException("unrar extract interrupted");
                });

                assertThatThrownBy(() -> processor.prepare(PrepareRequest.of(libraryFileFor(file), CropPolicy.NONE), ctx))
                        .isInstanceOf(PrepareAbandonedException.class);

                unrar.verify(() -> UnrarHelper.extractEntryBytes(any(Path.class), anyString()), times(1));
            }
        }

        @ParameterizedTest
        @EnumSource(Format.class)
        void detectsTheTypeAndOpensTheArchiveOnce(Format format) throws IOException {
            Path file = archive(format, "once",
                    imageFile("001.jpg", 1),
                    file("ComicInfo.xml", comicInfo("<Title>Once</Title>")));
            when(fileService.writeRenderedCover(eq(1L), any(RenderedCover.class))).thenReturn(true);

            try (MockedStatic<ArchiveUtils> archiveUtils = mockStatic(ArchiveUtils.class, CALLS_REAL_METHODS);
                 MockedStatic<CbxArchive> cbxArchive = mockStatic(CbxArchive.class, CALLS_REAL_METHODS)) {
                BookEntity book = process(file);

                assertThat(writtenCoverWidth()).isEqualTo(1);
                assertThat(book.getMetadata().getTitle()).isEqualTo("Once");
                archiveUtils.verify(() -> ArchiveUtils.detectArchiveType(any(File.class)), times(1));
                cbxArchive.verify(() -> CbxArchive.open(any(File.class), eq(format.archiveType())), times(1));
            }
        }

        @Test
        void handsTheOpenArchiveToTheExtractor() throws IOException {
            Path file = archive(Format.CBR, "handoff", imageFile("001.jpg", 1));
            when(fileService.writeRenderedCover(eq(1L), any(RenderedCover.class))).thenReturn(true);
            when(cbxMetadataExtractor.extractMetadata(any(CbxArchive.class), eq("handoff")))
                    .thenReturn(BookMetadata.builder().title("From The Open Archive").build());
            processor = cbxProcessor;

            BookEntity book = process(file);

            assertThat(book.getMetadata().getTitle()).isEqualTo("From The Open Archive");
            verify(cbxMetadataExtractor, never()).extractMetadata(any(File.class));
        }
    }
}
