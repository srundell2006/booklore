package org.booklore.service.library;

import org.booklore.model.FileProcessResult;
import org.booklore.model.dto.Book;
import org.booklore.model.dto.BookFile;
import org.booklore.model.dto.settings.LibraryFile;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.entity.LibraryPathEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.model.enums.FileProcessStatus;
import org.booklore.repository.BookAdditionalFileRepository;
import org.booklore.repository.BookRepository;
import org.booklore.repository.LibraryRepository;
import org.booklore.service.event.BookEventBroadcaster;
import org.booklore.service.file.FileFingerprint;
import org.booklore.service.fileprocessor.BookFileProcessor;
import org.booklore.service.fileprocessor.BookFileProcessorRegistry;
import org.booklore.service.kobo.KoboAutoShelfService;
import org.booklore.service.library.importer.ImportExecutor;
import org.booklore.service.library.importer.ImportSettings;
import org.booklore.service.library.importer.ImportSummary;
import org.booklore.service.library.importer.ParallelBookImporter;
import org.booklore.service.metadata.extractor.AudiobookMetadataExtractor;
import org.booklore.service.metadata.extractor.MetadataExtractorFactory;
import org.booklore.util.FileService;
import org.booklore.util.FileUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.MockitoAnnotations;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class FileAsBookProcessorTest {

    @Mock
    private BookEventBroadcaster bookEventBroadcaster;

    @Mock
    private BookFileProcessorRegistry processorRegistry;

    @Mock
    private KoboAutoShelfService koboAutoShelfService;

    @Mock
    private BookRepository bookRepository;

    @Mock
    private BookAdditionalFileRepository bookAdditionalFileRepository;

    @Mock
    private LibraryRepository libraryRepository;

    @Mock
    private FileService fileService;

    @Mock
    private MetadataExtractorFactory metadataExtractorFactory;

    @Mock
    private AudiobookMetadataExtractor audiobookMetadataExtractor;

    @Mock
    private BookFileProcessor bookFileProcessor;

    @Mock
    private ParallelBookImporter parallelBookImporter;

    @Mock
    private PlatformTransactionManager transactionManager;

    @Mock
    private ImportExecutor importExecutor;

    private FileAsBookProcessor fileAsBookProcessor;

    private AutoCloseable mocks;
    private MockedStatic<FileFingerprint> fileFingerprintMock;
    private MockedStatic<FileUtils> fileUtilsMock;

    @BeforeEach
    void setUp() {
        mocks = MockitoAnnotations.openMocks(this);
        fileAsBookProcessor = new FileAsBookProcessor(
                bookEventBroadcaster,
                processorRegistry,
                koboAutoShelfService,
                bookRepository,
                bookAdditionalFileRepository,
                libraryRepository,
                fileService,
                metadataExtractorFactory,
                audiobookMetadataExtractor,
                parallelBookImporter,
                transactionManager,
                importExecutor
        );
        fileFingerprintMock = mockStatic(FileFingerprint.class);
        fileFingerprintMock.when(() -> FileFingerprint.generateHash(any(Path.class))).thenReturn("testhash");
        fileUtilsMock = mockStatic(FileUtils.class);
        fileUtilsMock.when(() -> FileUtils.getFileSizeInKb(any(Path.class))).thenReturn(100L);

    }

    @AfterEach
    void tearDown() throws Exception {
        if (fileFingerprintMock != null) fileFingerprintMock.close();
        if (fileUtilsMock != null) fileUtilsMock.close();
        if (mocks != null) mocks.close();
    }

    @Test
    void staticMocksShouldWork() {
        // Verify static mocks are working
        Path testPath = Path.of("/test/path");
        String hash = FileFingerprint.generateHash(testPath);
        Long size = FileUtils.getFileSizeInKb(testPath);
        assert hash.equals("testhash") : "Expected 'testhash' but got: " + hash;
        assert size.equals(100L) : "Expected 100L but got: " + size;
    }

    @Test
    void processLibraryFiles_shouldProcessDifferentNamedFilesSeparately() {
        LibraryEntity libraryEntity = new LibraryEntity();
        LibraryPathEntity libraryPathEntity = new LibraryPathEntity();
        libraryPathEntity.setId(1L);
        libraryPathEntity.setPath("/library/path");

        LibraryFile file1 = LibraryFile.builder()
                .libraryEntity(libraryEntity)
                .libraryPathEntity(libraryPathEntity)
                .fileName("book1.epub")
                .fileSubPath("books")
                .bookFileType(BookFileType.EPUB)
                .build();

        LibraryFile file2 = LibraryFile.builder()
                .libraryEntity(libraryEntity)
                .libraryPathEntity(libraryPathEntity)
                .fileName("book2.pdf")
                .fileSubPath("books")
                .bookFileType(BookFileType.PDF)
                .build();

        Book book1 = Book.builder().id(1L).primaryFile(BookFile.builder().fileName("book1.epub").bookType(BookFileType.EPUB).build()).build();
        Book book2 = Book.builder().id(2L).primaryFile(BookFile.builder().fileName("book2.pdf").bookType(BookFileType.PDF).build()).build();

        when(processorRegistry.getProcessorOrThrow(BookFileType.EPUB)).thenReturn(bookFileProcessor);
        when(processorRegistry.getProcessorOrThrow(BookFileType.PDF)).thenReturn(bookFileProcessor);
        when(bookFileProcessor.processFile(file1)).thenReturn(new FileProcessResult(book1, FileProcessStatus.NEW));
        when(bookFileProcessor.processFile(file2)).thenReturn(new FileProcessResult(book2, FileProcessStatus.NEW));

        fileAsBookProcessor.processLibraryFiles(List.of(file1, file2), libraryEntity);

        verify(bookEventBroadcaster, times(2)).broadcastBookAddEvent(any());
        verify(bookFileProcessor, times(2)).processFile(any());
    }

    @Test
    void processLibraryFiles_shouldHandleEmptyList() {
        LibraryEntity libraryEntity = new LibraryEntity();

        fileAsBookProcessor.processLibraryFiles(new ArrayList<>(), libraryEntity);

        verify(bookEventBroadcaster, never()).broadcastBookAddEvent(any());
        verify(processorRegistry, never()).getProcessorOrThrow(any());
    }

    @Test
    void processLibraryFiles_shouldSkipFilesWithNullBookFileType() {
        LibraryEntity libraryEntity = new LibraryEntity();
        LibraryPathEntity libraryPathEntity = new LibraryPathEntity();
        libraryPathEntity.setId(1L);
        libraryPathEntity.setPath("/library/path");

        LibraryFile invalidFile = LibraryFile.builder()
                .libraryEntity(libraryEntity)
                .libraryPathEntity(libraryPathEntity)
                .fileName("document.txt")
                .fileSubPath("docs")
                .bookFileType(null)
                .build();

        fileAsBookProcessor.processLibraryFiles(List.of(invalidFile), libraryEntity);

        verify(bookEventBroadcaster, never()).broadcastBookAddEvent(any());
        verify(processorRegistry, never()).getProcessorOrThrow(any());
    }

    @Test
    void processLibraryFiles_shouldNotBroadcastWhenProcessorReturnsNull() {
        LibraryEntity libraryEntity = new LibraryEntity();
        LibraryPathEntity libraryPathEntity = new LibraryPathEntity();
        libraryPathEntity.setId(1L);
        libraryPathEntity.setPath("/library/path");

        LibraryFile file = LibraryFile.builder()
                .libraryEntity(libraryEntity)
                .libraryPathEntity(libraryPathEntity)
                .fileName("book.epub")
                .fileSubPath("books")
                .bookFileType(BookFileType.EPUB)
                .build();

        when(processorRegistry.getProcessorOrThrow(BookFileType.EPUB)).thenReturn(bookFileProcessor);
        when(bookFileProcessor.processFile(file)).thenReturn(null);

        fileAsBookProcessor.processLibraryFiles(List.of(file), libraryEntity);

        verify(bookEventBroadcaster, never()).broadcastBookAddEvent(any());
    }

    @Test
    void processLibraryFiles_shouldNotGroupFilesInDifferentDirectories() {
        LibraryEntity libraryEntity = new LibraryEntity();
        LibraryPathEntity libraryPathEntity = new LibraryPathEntity();
        libraryPathEntity.setId(1L);
        libraryPathEntity.setPath("/library/path");

        LibraryFile file1 = LibraryFile.builder()
                .libraryEntity(libraryEntity)
                .libraryPathEntity(libraryPathEntity)
                .fileName("book.epub")
                .fileSubPath("dir1")
                .bookFileType(BookFileType.EPUB)
                .build();

        LibraryFile file2 = LibraryFile.builder()
                .libraryEntity(libraryEntity)
                .libraryPathEntity(libraryPathEntity)
                .fileName("book.pdf")
                .fileSubPath("dir2")
                .bookFileType(BookFileType.PDF)
                .build();

        Book book1 = Book.builder().id(1L).primaryFile(BookFile.builder().fileName("book.epub").bookType(BookFileType.EPUB).build()).build();
        Book book2 = Book.builder().id(2L).primaryFile(BookFile.builder().fileName("book.pdf").bookType(BookFileType.PDF).build()).build();

        when(processorRegistry.getProcessorOrThrow(BookFileType.EPUB)).thenReturn(bookFileProcessor);
        when(processorRegistry.getProcessorOrThrow(BookFileType.PDF)).thenReturn(bookFileProcessor);
        when(bookFileProcessor.processFile(file1)).thenReturn(new FileProcessResult(book1, FileProcessStatus.NEW));
        when(bookFileProcessor.processFile(file2)).thenReturn(new FileProcessResult(book2, FileProcessStatus.NEW));

        fileAsBookProcessor.processLibraryFiles(List.of(file1, file2), libraryEntity);

        // Both should be processed as separate books
        verify(bookFileProcessor, times(2)).processFile(any());
        verify(bookEventBroadcaster, times(2)).broadcastBookAddEvent(any());
    }

    // ---- which import a scan's groups go through ----

    private void givenPipeline(boolean enabled) {
        when(importExecutor.settings()).thenReturn(new ImportSettings(enabled, 4, 16, Duration.ofSeconds(180), 16, 2, 128L * 1024 * 1024));
    }

    private Map<String, List<LibraryFile>> oneEpubGroup(LibraryEntity libraryEntity) {
        LibraryPathEntity libraryPathEntity = new LibraryPathEntity();
        libraryPathEntity.setId(1L);
        libraryPathEntity.setPath("/library/path");
        LibraryFile file = LibraryFile.builder()
                .libraryEntity(libraryEntity)
                .libraryPathEntity(libraryPathEntity)
                .fileName("book.epub")
                .fileSubPath("books")
                .bookFileType(BookFileType.EPUB)
                .build();
        Book book = Book.builder().id(1L).primaryFile(BookFile.builder().fileName("book.epub").bookType(BookFileType.EPUB).build()).build();
        when(processorRegistry.getProcessorOrThrow(BookFileType.EPUB)).thenReturn(bookFileProcessor);
        when(bookFileProcessor.processFile(file)).thenReturn(new FileProcessResult(book, FileProcessStatus.NEW));
        Map<String, List<LibraryFile>> groups = new LinkedHashMap<>();
        groups.put("book", List.of(file));
        return groups;
    }

    @Test
    void processLibraryFilesGrouped_withNoTransactionOpen_goesThroughTheParallelImporter() {
        givenPipeline(true);
        LibraryEntity libraryEntity = new LibraryEntity();
        Map<String, List<LibraryFile>> groups = new LinkedHashMap<>();
        ImportSummary summary = new ImportSummary(0, 0, 0, 0, 0, 0, false, false, false, 0, Duration.ZERO, false);
        when(parallelBookImporter.importGroups(groups, libraryEntity)).thenReturn(summary);

        assertThat(fileAsBookProcessor.processLibraryFilesGrouped(groups, libraryEntity)).isSameAs(summary);

        verifyNoInteractions(transactionManager, processorRegistry);
    }

    @Test
    void processLibraryFilesGrouped_withThePipelineOff_importsOneByOneInOneTransaction() {
        givenPipeline(false);
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        LibraryEntity libraryEntity = new LibraryEntity();

        ImportSummary summary = fileAsBookProcessor.processLibraryFilesGrouped(oneEpubGroup(libraryEntity), libraryEntity);

        assertThat(summary.legacy()).isTrue();
        assertThat(summary.groups()).isEqualTo(1);
        verify(transactionManager).getTransaction(any());
        verify(bookFileProcessor).processFile(any());
        verify(transactionManager).commit(any());
        verifyNoInteractions(parallelBookImporter);
    }

    @Test
    void processLibraryFilesGrouped_insideACallersTransaction_joinsItAndImportsOneByOne() {
        givenPipeline(true);
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus(false));
        LibraryEntity libraryEntity = new LibraryEntity();
        Map<String, List<LibraryFile>> groups = oneEpubGroup(libraryEntity);

        ImportSummary summary;
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            summary = fileAsBookProcessor.processLibraryFilesGrouped(groups, libraryEntity);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }

        assertThat(summary.legacy()).isTrue();
        verify(bookFileProcessor).processFile(any());
        verifyNoInteractions(parallelBookImporter);
    }

    @Test
    void processLibraryFiles_neverGoesThroughTheParallelImporter() {
        LibraryEntity libraryEntity = new LibraryEntity();
        List<LibraryFile> files = oneEpubGroup(libraryEntity).get("book");

        fileAsBookProcessor.processLibraryFiles(files, libraryEntity);

        verify(bookFileProcessor).processFile(any());
        verifyNoInteractions(parallelBookImporter, importExecutor, transactionManager);
    }
}
