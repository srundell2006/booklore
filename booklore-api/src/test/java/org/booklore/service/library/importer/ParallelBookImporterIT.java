package org.booklore.service.library.importer;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.booklore.BookloreApplication;
import org.booklore.model.FileProcessResult;
import org.booklore.model.dto.settings.LibraryFile;
import org.booklore.model.entity.AuthorEntity;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.model.entity.ComicMetadataEntity;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.entity.LibraryPathEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.repository.AuthorRepository;
import org.booklore.repository.BookAdditionalFileRepository;
import org.booklore.repository.BookRepository;
import org.booklore.repository.LibraryRepository;
import org.booklore.service.event.BookEventBroadcaster;
import org.booklore.service.fileprocessor.AudiobookProcessor;
import org.booklore.service.fileprocessor.BookFileProcessor;
import org.booklore.service.fileprocessor.BookFileProcessorRegistry;
import org.booklore.service.fileprocessor.CbxProcessor;
import org.booklore.service.fileprocessor.EpubProcessor;
import org.booklore.service.fileprocessor.PdfProcessor;
import org.booklore.service.fileprocessor.PreparableBookFileProcessor;
import org.booklore.service.fileprocessor.prepare.PersistedBook;
import org.booklore.service.fileprocessor.prepare.PrepareContext;
import org.booklore.service.fileprocessor.prepare.PrepareRequest;
import org.booklore.service.fileprocessor.prepare.PreparedBookFile;
import org.booklore.service.kobo.KoboAutoShelfService;
import org.booklore.service.library.FileAsBookProcessor;
import org.booklore.service.library.LibraryHealthService;
import org.booklore.service.metadata.extractor.AudiobookMetadataExtractor;
import org.booklore.service.metadata.extractor.MetadataExtractorFactory;
import org.booklore.service.metadata.sidecar.SidecarMetadataWriter;
import org.booklore.service.reader.FfprobeService;
import org.booklore.service.task.TaskCronService;
import org.booklore.util.CbxTestArchives;
import org.booklore.util.FileService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.FileSystemUtils;

import javax.imageio.ImageIO;
import javax.sql.DataSource;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.booklore.util.CbxTestArchives.comicInfo;
import static org.mockito.Mockito.mock;

/**
 * The parallel import against H2 with the real repositories, transactions, processors, BookCreatorService and
 * FileService, on files written to a temporary library. Nothing here runs in a test transaction: the import opens its
 * own, one per book and one per additional format.
 */
@SpringBootTest(classes = {
        BookloreApplication.class
})
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.datasource.url=jdbc:h2:mem:parallelimport;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "app.bookdrop-folder=build/tmp/test-bookdrop",
        "spring.main.allow-bean-definition-overriding=true",
        "spring.task.scheduling.enabled=false",
        "app.task.scan-library-cron=*/1 * * * * *",
        "app.task.process-bookdrop-cron=*/1 * * * * *",
        "app.features.oidc-enabled=false"
})
@Import(ParallelBookImporterIT.TestConfig.class)
class ParallelBookImporterIT {

    private static final long MIB = 1024 * 1024;
    private static final BookFileType[] EBOOKS_FIRST = {BookFileType.EPUB, BookFileType.PDF, BookFileType.CBX,
            BookFileType.FB2, BookFileType.MOBI, BookFileType.AZW3, BookFileType.AUDIOBOOK};
    private static final long FIXED_ZIP_TIME = 1_600_000_000_000L;

    /** The images folders: a fresh one per run, as book ids start again with every run's database. */
    private static final Path CONFIG_DIR = createConfigDir();

    @TestConfiguration
    public static class TestConfig {
        @Bean("flyway")
        @Primary
        public org.flywaydb.core.Flyway flyway() {
            return mock(org.flywaydb.core.Flyway.class);
        }

        @Bean
        @Primary
        public TaskCronService taskCronService() {
            return mock(TaskCronService.class);
        }

        @Bean
        public static BeanPostProcessor recordingDataSource() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    return bean instanceof DataSource dataSource && !(bean instanceof RecordingDataSource)
                            ? new RecordingDataSource(dataSource)
                            : bean;
                }
            };
        }
    }

    @DynamicPropertySource
    static void pathConfig(DynamicPropertyRegistry registry) {
        registry.add("app.path-config", CONFIG_DIR::toString);
    }

    @AfterAll
    static void deleteConfigDir() throws IOException {
        FileSystemUtils.deleteRecursively(CONFIG_DIR);
    }

    // No ffprobe download: chapters come from the tags
    @MockitoBean
    private FfprobeService ffprobeService;
    // Its health check reads the database from a scheduler thread every 30 s
    @MockitoBean
    private LibraryHealthService libraryHealthService;

    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private LibraryRepository libraryRepository;
    @Autowired
    private BookRepository bookRepository;
    @Autowired
    private BookAdditionalFileRepository bookAdditionalFileRepository;
    @Autowired
    private AuthorRepository authorRepository;
    @Autowired
    private BookEventBroadcaster bookEventBroadcaster;
    @Autowired
    private KoboAutoShelfService koboAutoShelfService;
    @Autowired
    private FileService fileService;
    @Autowired
    private SidecarMetadataWriter sidecarMetadataWriter;
    @Autowired
    private MetadataExtractorFactory metadataExtractorFactory;
    @Autowired
    private AudiobookMetadataExtractor audiobookMetadataExtractor;
    @Autowired
    private BookFileProcessorRegistry processorRegistry;
    @Autowired
    private EpubProcessor epubProcessor;
    @Autowired
    private PdfProcessor pdfProcessor;
    @Autowired
    private CbxProcessor cbxProcessor;
    @Autowired
    private AudiobookProcessor audiobookProcessor;
    @Autowired
    private FileAsBookProcessor fileAsBookProcessor;
    @Autowired
    private ParallelBookImporter parallelBookImporter;

    @TempDir
    Path tempDir;

    private TransactionTemplate tx;
    private final List<ImportExecutor> executors = new ArrayList<>();

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        // As in the Flyway schema the application runs on: a new book's empty metadata is saved before its title is
        // set. The entity's nullable = false only shapes the schema H2 generates from it.
        new JdbcTemplate(dataSource).execute("ALTER TABLE book_metadata ALTER COLUMN title SET NULL");
    }

    @AfterEach
    void shutDownExecutors() {
        executors.forEach(ImportExecutor::shutdown);
    }

    // ========== tests ==========

    @Test
    void booksSharingAuthorsAreSavedInTheOrderTheyCameWithEachAuthorOnce() throws Exception {
        LibraryEntity library = library("Three Hundred", EBOOKS_FIRST);
        byte[] cover = jpeg(60, 90, 1);
        Map<String, List<LibraryFile>> groups = new LinkedHashMap<>();
        for (int i = 0; i < 300; i++) {
            String name = "Book %03d.epub".formatted(i);
            epub(root(library).resolve(name), "Book " + i, List.of("Shared Author " + (i % 20)), i % 10 == 0 ? cover : null);
            groups.put(name, List.of(file(library, "", name, BookFileType.EPUB)));
        }

        ImportSummary summary = importer(executor(8), new SlowReads(epubProcessor, 20)).importGroups(groups, library);

        assertThat(summary.added()).isEqualTo(300);
        assertThat(summary.failed() + summary.timedOut() + summary.notStarted()).isZero();
        assertThat(authorRepository.findAll()).extracting(AuthorEntity::getName)
                .filteredOn(name -> name.startsWith("Shared Author ")).hasSize(20).doesNotHaveDuplicates();
        List<Long> ids = groups.keySet().stream().map(name -> bookIdOf(library, "", name)).toList();
        assertThat(ids).doesNotContainNull().doesNotHaveDuplicates().isSorted();
        assertThat(Path.of(fileService.getCoverFile(ids.getFirst()))).exists();
    }

    @Test
    void onlyTheCallingThreadTakesDatabaseConnectionsAndNeverMoreThanTwoAtOnce() throws Exception {
        LibraryEntity library = library("Connections", EBOOKS_FIRST);
        Map<String, List<LibraryFile>> groups = new LinkedHashMap<>();
        for (int i = 0; i < 8; i++) {
            String name = "Novel " + i;
            epub(root(library).resolve(name + ".epub"), name, List.of("Connection Author " + (i % 3)), i % 2 == 0 ? jpeg(80, 120, i) : null);
            List<LibraryFile> group = new ArrayList<>(List.of(file(library, "", name + ".epub", BookFileType.EPUB)));
            if (i % 2 == 0) {
                pdf(root(library).resolve(name + ".pdf"), name, "Connection Author 0");
                group.add(file(library, "", name + ".pdf", BookFileType.PDF));
            }
            groups.put(name, group);
        }
        for (int i = 0; i < 4; i++) {
            String comic = "Comic %02d.cbz".formatted(i);
            CbxTestArchives.zip(root(library).resolve(comic),
                    CbxTestArchives.file("ComicInfo.xml", comicInfo("<Title>Comic " + i + "</Title><Writer>Connection Author 1</Writer>")),
                    CbxTestArchives.file("001.jpg", jpeg(100, 150, 10 + i)));
            groups.put(comic, List.of(file(library, "", comic, BookFileType.CBX)));
            String paper = "Paper " + i + ".pdf";
            pdf(root(library).resolve(paper), "Paper " + i, "Connection Author 2");
            groups.put(paper, List.of(file(library, "", paper, BookFileType.PDF)));
        }
        RecordingDataSource connections = (RecordingDataSource) dataSource;

        ImportSummary summary;
        connections.startRecording();
        try {
            summary = importer(executor(8)).importGroups(groups, library);
        } finally {
            connections.stopRecording();
        }

        assertThat(summary.added()).isEqualTo(16);
        assertThat(summary.attachmentsSkipped()).isZero();
        assertThat(connections.threadNames()).isNotEmpty().containsOnly(Thread.currentThread().getName());
        assertThat(connections.mostOpenAtOnce()).isBetween(1, 2);
    }

    @Test
    void anEbookAttachedToANewAudiobookGivesTheBookItsEbookCover() throws Exception {
        LibraryEntity library = library("Audio First", BookFileType.AUDIOBOOK, BookFileType.EPUB, BookFileType.PDF);
        Path dir = Files.createDirectories(root(library).resolve("Narrator/Title"));
        Files.copy(Path.of("src/test/resources/audio/fixture.m4b"), dir.resolve("Title.m4b"));
        epub(dir.resolve("Title.epub"), "Title", List.of("Audio Author"), jpeg(300, 450, 2));
        Map<String, List<LibraryFile>> groups = new LinkedHashMap<>();
        groups.put("Title", List.of(file(library, "Narrator/Title", "Title.epub", BookFileType.EPUB),
                file(library, "Narrator/Title", "Title.m4b", BookFileType.AUDIOBOOK)));

        ImportSummary summary = importer(executor(8)).importGroups(groups, library);

        assertThat(summary.added()).isEqualTo(1);
        assertThat(summary.attachmentsSkipped()).isZero();
        long bookId = tx.execute(status -> {
            BookEntity book = bookRepository.findAllWithMetadataByLibraryId(library.getId()).getFirst();
            assertThat(book.getBookFiles()).extracting(BookFileEntity::getFileName).containsExactlyInAnyOrder("Title.m4b", "Title.epub");
            assertThat(book.getPrimaryBookFile().getBookType()).isEqualTo(BookFileType.AUDIOBOOK);
            assertThat(book.getMetadata().getAudiobookCoverUpdatedOn()).as("the audiobook's own cover").isNotNull();
            assertThat(book.getMetadata().getCoverUpdatedOn()).as("the ebook cover").isNotNull();
            assertThat(book.getBookCoverHash()).isNotNull();
            return book.getId();
        });
        Path images = Path.of(fileService.getImagesFolder(bookId));
        assertThat(images.resolve("cover.jpg")).exists();
        assertThat(images.resolve("thumbnail.jpg")).exists();
        assertThat(images.resolve("audiobook-cover.jpg")).exists();
    }

    @Test
    void aBookThatFailsToSaveLeavesNoRowAndNoCoverFilesAndTheNextBookIsSaved() throws Exception {
        LibraryEntity library = library("Rollback", EBOOKS_FIRST);
        for (String name : List.of("before", "explodes", "existing", "after")) {
            epub(root(library).resolve(name + ".epub"), name, List.of("Rollback Author"), jpeg(120, 180, name.length()));
        }
        // A book already there for its file is updated instead of created
        Map<String, List<LibraryFile>> firstScan = new LinkedHashMap<>();
        firstScan.put("existing", List.of(file(library, "", "existing.epub", BookFileType.EPUB)));
        assertThat(importer(executor(4)).importGroups(firstScan, library).added()).isEqualTo(1);
        long existingId = bookIdOf(library, "", "existing.epub");

        SlowReads failing = new SlowReads(epubProcessor, 0,
                file -> file.getFileName().equals("explodes.epub") || file.getFileName().equals("existing.epub"));
        Map<String, List<LibraryFile>> groups = new LinkedHashMap<>();
        for (String name : List.of("before", "explodes", "existing", "after")) {
            groups.put(name, List.of(file(library, "", name + ".epub", BookFileType.EPUB)));
        }
        ImportSummary summary = importer(executor(4), failing).importGroups(groups, library);

        assertThat(summary.added()).isEqualTo(2);
        assertThat(summary.failed()).isEqualTo(2);
        long explodedId = failing.unsavedBookIds.get("explodes.epub");
        assertThat(bookRepository.findById(explodedId)).isEmpty();
        assertThat(bookIdOf(library, "", "explodes.epub")).isNull();
        assertThat(Path.of(fileService.getImagesFolder(explodedId))).as("the new book's covers").doesNotExist();
        assertThat(failing.unsavedBookIds.get("existing.epub")).isEqualTo(existingId);
        assertThat(Path.of(fileService.getCoverFile(existingId))).as("the existing book's cover").exists();
        assertThat(Path.of(fileService.getCoverFile(bookIdOf(library, "", "after.epub")))).exists();
    }

    @Test
    void twoLibrariesImportedAtOnceSaveOneBookAtATimeWithoutDuplicateAuthors() throws Exception {
        LibraryEntity first = library("Concurrent One", EBOOKS_FIRST);
        LibraryEntity second = library("Concurrent Two", EBOOKS_FIRST);
        Map<LibraryEntity, Map<String, List<LibraryFile>>> scans = new LinkedHashMap<>();
        for (LibraryEntity library : List.of(first, second)) {
            Map<String, List<LibraryFile>> groups = new LinkedHashMap<>();
            for (int i = 0; i < 40; i++) {
                String name = library.getName() + " " + i + ".epub";
                epub(root(library).resolve(name), name, List.of("Overlapping Author " + (i % 10)), null);
                groups.put(name, List.of(file(library, "", name, BookFileType.EPUB)));
            }
            scans.put(library, groups);
        }
        SlowReads reads = new SlowReads(epubProcessor, 10);
        ParallelBookImporter importer = importer(executor(8), reads);
        Map<String, ImportSummary> summaries = new ConcurrentHashMap<>();

        // Each library scans on a thread of its own, as LibraryService runs them
        List<Thread> threads = scans.entrySet().stream()
                .map(scan -> Thread.ofPlatform().start(() ->
                        summaries.put(scan.getKey().getName(), importer.importGroups(scan.getValue(), scan.getKey()))))
                .toList();
        for (Thread thread : threads) {
            thread.join(TimeUnit.SECONDS.toMillis(60));
        }

        assertThat(summaries).hasSize(2);
        assertThat(summaries.values()).allSatisfy(summary -> {
            assertThat(summary.added()).isEqualTo(40);
            assertThat(summary.failed()).isZero();
        });
        assertThat(authorRepository.findAll()).extracting(AuthorEntity::getName)
                .filteredOn(name -> name.startsWith("Overlapping Author ")).hasSize(10);
        List<long[]> saves = new ArrayList<>(reads.saves);
        saves.sort(Comparator.comparingLong(save -> save[0]));
        assertThat(saves).hasSize(80);
        for (int i = 1; i < saves.size(); i++) {
            assertThat(saves.get(i)[0]).as("save %d starts after save %d ended", i, i - 1).isGreaterThanOrEqualTo(saves.get(i - 1)[1]);
        }
    }

    @Test
    void theSequentialAndTheParallelImportStoreTheSameBooksAndCovers() throws Exception {
        LibraryEntity sequential = library("Sequential", EBOOKS_FIRST);
        LibraryEntity parallel = library("Parallel", EBOOKS_FIRST);
        writeMixedLibrary(root(sequential));
        // The same bytes in both: PDFBox gives every PDF it saves a new id
        copyTree(root(sequential), root(parallel));
        ImportExecutor pipelineOff = new ImportExecutor(new ImportSettings(false, 4, 16, Duration.ofSeconds(60), 16, 2, 128 * MIB));
        executors.add(pipelineOff);
        FileAsBookProcessor sequentialImport = new FileAsBookProcessor(bookEventBroadcaster, processorRegistry, koboAutoShelfService,
                bookRepository, bookAdditionalFileRepository, libraryRepository, fileService, metadataExtractorFactory,
                audiobookMetadataExtractor, parallelBookImporter, transactionManager, pipelineOff);

        ImportSummary off = sequentialImport.processLibraryFilesGrouped(mixedGroups(sequential), sequential);
        ImportSummary on = fileAsBookProcessor.processLibraryFilesGrouped(mixedGroups(parallel), parallel);

        assertThat(off.legacy()).isTrue();
        assertThat(on.legacy()).isFalse();
        assertThat(on.added()).isEqualTo(5);
        assertThat(on.attachmentsSkipped()).isZero();
        List<String> stored = snapshot(parallel);
        assertThat(stored).hasSize(5);
        assertThat(stored).isEqualTo(snapshot(sequential));
    }

    // ========== the library ==========

    private LibraryEntity library(String name, BookFileType... formatPriority) throws IOException {
        Path root = Files.createDirectories(tempDir.resolve(name));
        return tx.execute(status -> {
            LibraryEntity created = libraryRepository.save(LibraryEntity.builder()
                    .name(name).icon("book").watch(false)
                    .formatPriority(new ArrayList<>(List.of(formatPriority)))
                    .libraryPaths(new ArrayList<>())
                    .build());
            created.getLibraryPaths().add(LibraryPathEntity.builder().library(created).path(root.toString()).build());
            return libraryRepository.save(created);
        });
    }

    private static Path root(LibraryEntity library) {
        return Path.of(library.getLibraryPaths().getFirst().getPath());
    }

    private static LibraryFile file(LibraryEntity library, String subPath, String fileName, BookFileType type) {
        return LibraryFile.builder()
                .libraryEntity(library)
                .libraryPathEntity(library.getLibraryPaths().getFirst())
                .fileSubPath(subPath)
                .fileName(fileName)
                .bookFileType(type)
                .build();
    }

    private Long bookIdOf(LibraryEntity library, String subPath, String fileName) {
        return tx.execute(status -> bookAdditionalFileRepository
                .findExactlyAt(library.getLibraryPaths().getFirst().getId(), subPath, fileName)
                .map(file -> file.getBook().getId())
                .orElse(null));
    }

    private ImportExecutor executor(int io) {
        ImportExecutor executor = new ImportExecutor(new ImportSettings(true, io, 4 * io, Duration.ofSeconds(60), 16, 2, 256 * MIB));
        executors.add(executor);
        return executor;
    }

    private ParallelBookImporter importer(ImportExecutor executor, BookFileProcessor... processors) {
        List<BookFileProcessor> registered = new ArrayList<>(List.of(epubProcessor, pdfProcessor, cbxProcessor, audiobookProcessor));
        // Later ones take over the types of earlier ones
        registered.addAll(List.of(processors));
        return new ParallelBookImporter(executor, new BookFileProcessorRegistry(registered), transactionManager, libraryRepository,
                bookRepository, bookAdditionalFileRepository, bookEventBroadcaster, koboAutoShelfService, fileService,
                sidecarMetadataWriter, metadataExtractorFactory, audiobookMetadataExtractor);
    }

    /**
     * Reads through a real processor after a random pause, so files finish reading out of order, and records when
     * each save ran. A file it is told to fail is saved by the real processor, then fails inside the same transaction.
     */
    private static final class SlowReads implements PreparableBookFileProcessor {
        private final PreparableBookFileProcessor delegate;
        private final int maxPauseMillis;
        private final Predicate<LibraryFile> failsToSave;
        final Map<String, Long> unsavedBookIds = new ConcurrentHashMap<>();
        final List<long[]> saves = Collections.synchronizedList(new ArrayList<>());

        SlowReads(PreparableBookFileProcessor delegate, int maxPauseMillis, Predicate<LibraryFile> failsToSave) {
            this.delegate = delegate;
            this.maxPauseMillis = maxPauseMillis;
            this.failsToSave = failsToSave;
        }

        SlowReads(PreparableBookFileProcessor delegate, int maxPauseMillis) {
            this(delegate, maxPauseMillis, file -> false);
        }

        @Override
        public PreparedBookFile prepare(PrepareRequest request, PrepareContext ctx) throws Exception {
            if (maxPauseMillis > 0) {
                Thread.sleep(ThreadLocalRandom.current().nextInt(maxPauseMillis + 1));
            }
            return delegate.prepare(request, ctx);
        }

        @Override
        public PersistedBook persistPrepared(LibraryFile libraryFile, PreparedBookFile prepared) {
            long start = System.nanoTime();
            try {
                PersistedBook persisted = delegate.persistPrepared(libraryFile, prepared);
                if (failsToSave.test(libraryFile)) {
                    unsavedBookIds.put(libraryFile.getFileName(), persisted.bookId());
                    throw new IllegalStateException("Saving " + libraryFile.getFileName() + " failed");
                }
                return persisted;
            } finally {
                saves.add(new long[]{start, System.nanoTime()});
            }
        }

        @Override
        public List<BookFileType> getSupportedTypes() {
            return delegate.getSupportedTypes();
        }

        @Override
        public FileProcessResult processFile(LibraryFile libraryFile) {
            return delegate.processFile(libraryFile);
        }

        @Override
        public boolean generateCover(BookEntity bookEntity) {
            return delegate.generateCover(bookEntity);
        }
    }

    /** Counts the connections taken while recording, and the threads that took them. */
    static final class RecordingDataSource extends DelegatingDataSource {
        private volatile boolean recording;
        private final List<String> threadNames = new CopyOnWriteArrayList<>();
        private final AtomicInteger open = new AtomicInteger();
        private final AtomicInteger mostOpen = new AtomicInteger();

        RecordingDataSource(DataSource target) {
            super(target);
        }

        void startRecording() {
            threadNames.clear();
            mostOpen.set(0);
            recording = true;
        }

        void stopRecording() {
            recording = false;
        }

        List<String> threadNames() {
            return List.copyOf(threadNames);
        }

        int mostOpenAtOnce() {
            return mostOpen.get();
        }

        @Override
        public Connection getConnection() throws SQLException {
            return record(super.getConnection());
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return record(super.getConnection(username, password));
        }

        private Connection record(Connection connection) {
            if (!recording) {
                return connection;
            }
            threadNames.add(Thread.currentThread().getName());
            mostOpen.accumulateAndGet(open.incrementAndGet(), Math::max);
            AtomicBoolean closed = new AtomicBoolean();
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("close") && closed.compareAndSet(false, true)) {
                            open.decrementAndGet();
                        }
                        try {
                            return method.invoke(connection, args);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
        }
    }

    // ========== what was stored ==========

    /** Every stored value of each book of the library but ids and times, and its cover files, one string per book. */
    private List<String> snapshot(LibraryEntity library) {
        return tx.execute(status -> bookRepository.findAllWithMetadataByLibraryId(library.getId()).stream()
                .map(this::describe)
                .sorted()
                .toList());
    }

    private String describe(BookEntity book) {
        StringBuilder out = new StringBuilder();
        scalars(out, "book", book);
        book.getBookFiles().stream()
                .sorted(Comparator.comparing(BookFileEntity::getFileName))
                .forEach(file -> scalars(out, "file " + file.getFileName(), file));
        BookMetadataEntity metadata = book.getMetadata();
        scalars(out, "metadata", metadata);
        out.append("authors: ").append(names(metadata.getAuthors(), AuthorEntity::getName, false)).append('\n');
        out.append("categories: ").append(names(metadata.getCategories(), c -> c.getName(), true)).append('\n');
        out.append("moods: ").append(names(metadata.getMoods(), m -> m.getName(), true)).append('\n');
        out.append("tags: ").append(names(metadata.getTags(), t -> t.getName(), true)).append('\n');
        ComicMetadataEntity comic = metadata.getComicMetadata();
        if (comic != null) {
            scalars(out, "comic", comic);
            out.append("characters: ").append(names(comic.getCharacters(), c -> c.getName(), true)).append('\n');
            out.append("teams: ").append(names(comic.getTeams(), t -> t.getName(), true)).append('\n');
            out.append("locations: ").append(names(comic.getLocations(), l -> l.getName(), true)).append('\n');
            out.append("creators: ").append(names(comic.getCreatorMappings(),
                    m -> m.getRole() + " " + m.getCreator().getName(), true)).append('\n');
        }
        Path images = Path.of(fileService.getImagesFolder(book.getId()));
        if (Files.isDirectory(images)) {
            try (Stream<Path> files = Files.list(images)) {
                for (Path image : files.sorted().toList()) {
                    out.append("image ").append(image.getFileName()).append(' ').append(sha256(Files.readAllBytes(image))).append('\n');
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return out.toString();
    }

    private static <T> List<String> names(java.util.Collection<T> items, java.util.function.Function<T, String> name, boolean sort) {
        if (items == null) {
            return List.of();
        }
        Stream<String> names = items.stream().map(name);
        return sort ? names.sorted().toList() : names.toList();
    }

    /**
     * The entity's plain values, read through its getters: with bytecode enhancement a lazily loaded entity's fields
     * stay empty until a getter loads them. Ids and times only show whether they are set, as do the random cover
     * hashes.
     */
    private static void scalars(StringBuilder out, String prefix, Object entity) {
        List<Field> fields = new ArrayList<>();
        for (Class<?> type = entity.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) && !field.getName().startsWith("$$_") && isScalar(field.getType())) {
                    fields.add(field);
                }
            }
        }
        fields.sort(Comparator.comparing(Field::getName));
        for (Field field : fields) {
            Object value = read(entity, field);
            String name = field.getName();
            String shown = name.equals("id") || name.equals("bookId") || name.endsWith("CoverHash") || value instanceof Instant
                    ? (value == null ? "null" : "<set>")
                    : String.valueOf(value);
            out.append(prefix).append('.').append(name).append(": ").append(shown).append('\n');
        }
    }

    private static boolean isScalar(Class<?> type) {
        return type.isPrimitive() || type.isEnum() || type == String.class || Number.class.isAssignableFrom(type)
                || type == Boolean.class || type == Instant.class || type == LocalDate.class;
    }

    private static Object read(Object entity, Field field) {
        String name = field.getName();
        String capitalized = Character.toUpperCase(name.charAt(0)) + name.substring(1);
        for (String getter : List.of("get" + capitalized, "is" + capitalized, name)) {
            try {
                Method method = entity.getClass().getMethod(getter);
                return method.invoke(entity);
            } catch (NoSuchMethodException e) {
                // try the next name
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
        throw new IllegalStateException("No getter for " + entity.getClass().getSimpleName() + "." + name);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ========== fixtures ==========

    /** One of each kind of group: ebooks with and without covers, a comic, a paper, two formats of one book. */
    private static void writeMixedLibrary(Path root) throws IOException {
        Path novel = Files.createDirectories(root.resolve("Jane Doe/Novel"));
        epub(novel.resolve("Novel.epub"), "The Novel", List.of("Mixed Author One", "Mixed Author Two"), jpeg(300, 450, 3));
        Path papers = Files.createDirectories(root.resolve("Papers"));
        pdf(papers.resolve("Paper.pdf"), "A Paper", "Mixed Author Two");
        Path comics = Files.createDirectories(root.resolve("Comics"));
        CbxTestArchives.zip(comics.resolve("Hero 01.cbz"),
                CbxTestArchives.file("ComicInfo.xml", comicInfo("<Title>Hero</Title><Series>Heroes</Series><Number>1</Number>"
                        + "<Writer>Mixed Author One</Writer><Penciller>Pen Person</Penciller><Characters>Hero, Sidekick</Characters>")),
                CbxTestArchives.file("002.jpg", jpeg(120, 180, 4)),
                CbxTestArchives.file("001.jpg", jpeg(100, 150, 5)));
        Path story = Files.createDirectories(root.resolve("Audio/Story"));
        epub(story.resolve("Story.epub"), "Story", List.of("Mixed Author Three"), jpeg(200, 300, 6));
        Files.copy(Path.of("src/test/resources/audio/fixture.m4b"), story.resolve("Story.m4b"));
        Path twins = Files.createDirectories(root.resolve("Twins"));
        epub(twins.resolve("Twins.epub"), "Twins", List.of("Mixed Author One"), null);
        pdf(twins.resolve("Twins.pdf"), "Twins", "Mixed Author One");
    }

    private static void copyTree(Path from, Path to) throws IOException {
        try (Stream<Path> paths = Files.walk(from)) {
            for (Path path : paths.toList()) {
                Path target = to.resolve(from.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(path, target);
                }
            }
        }
    }

    private static Map<String, List<LibraryFile>> mixedGroups(LibraryEntity library) {
        Map<String, List<LibraryFile>> groups = new LinkedHashMap<>();
        groups.put("Novel", List.of(file(library, "Jane Doe/Novel", "Novel.epub", BookFileType.EPUB)));
        groups.put("Paper", List.of(file(library, "Papers", "Paper.pdf", BookFileType.PDF)));
        groups.put("Hero", List.of(file(library, "Comics", "Hero 01.cbz", BookFileType.CBX)));
        groups.put("Story", List.of(file(library, "Audio/Story", "Story.m4b", BookFileType.AUDIOBOOK),
                file(library, "Audio/Story", "Story.epub", BookFileType.EPUB)));
        groups.put("Twins", List.of(file(library, "Twins", "Twins.pdf", BookFileType.PDF),
                file(library, "Twins", "Twins.epub", BookFileType.EPUB)));
        return groups;
    }

    private static void epub(Path file, String title, List<String> authors, byte[] cover) throws IOException {
        StringBuilder metadata = new StringBuilder("<dc:title>").append(title).append("</dc:title>");
        authors.forEach(author -> metadata.append("<dc:creator opf:role=\"aut\">").append(author).append("</dc:creator>"));
        metadata.append("<dc:description>About ").append(title).append(".</dc:description><dc:subject>Fiction</dc:subject>");
        String opf = """
                <?xml version="1.0" encoding="UTF-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opf="http://www.idpf.org/2007/opf">
                    %s
                    %s
                  </metadata>
                  <manifest>
                    %s
                  </manifest>
                </package>""".formatted(metadata,
                cover == null ? "" : "<meta name=\"cover\" content=\"cover-img\"/>",
                cover == null ? "" : "<item id=\"cover-img\" href=\"images/cover.jpg\" media-type=\"image/jpeg\" properties=\"cover-image\"/>");
        String container = """
                <?xml version="1.0" encoding="UTF-8"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles>
                    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                  </rootfiles>
                </container>""";
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
            zipEntry(zip, "mimetype", "application/epub+zip".getBytes(StandardCharsets.UTF_8));
            zipEntry(zip, "META-INF/container.xml", container.getBytes(StandardCharsets.UTF_8));
            zipEntry(zip, "OEBPS/content.opf", opf.getBytes(StandardCharsets.UTF_8));
            if (cover != null) {
                zipEntry(zip, "OEBPS/images/cover.jpg", cover);
            }
        }
    }

    private static void zipEntry(ZipOutputStream zip, String name, byte[] data) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(FIXED_ZIP_TIME);
        zip.putNextEntry(entry);
        zip.write(data);
        zip.closeEntry();
    }

    private static void pdf(Path file, String title, String author) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDRectangle box = PDRectangle.A6;
            PDPage page = new PDPage(box);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setNonStrokingColor(new Color(30, 60, 150));
                cs.addRect(20, 20, box.getWidth() / 2, box.getHeight() / 3);
                cs.fill();
            }
            PDDocumentInformation info = new PDDocumentInformation();
            info.setTitle(title);
            info.setAuthor(author);
            doc.setDocumentInformation(info);
            doc.save(file.toFile());
        }
    }

    private static byte[] jpeg(int width, int height, long seed) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Random random = new Random(seed);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, (x * 255 / width) << 16 | (y * 255 / height) << 8 | random.nextInt(64));
            }
        }
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(20, 40, 160));
        g.fillOval(width / 5, height / 5, width / 2, height / 3);
        g.dispose();
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, "jpg", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path createConfigDir() {
        try {
            return Files.createTempDirectory("parallel-import-it");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
