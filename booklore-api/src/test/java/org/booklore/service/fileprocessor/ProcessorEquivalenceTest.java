package org.booklore.service.fileprocessor;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.booklore.config.AppProperties;
import org.booklore.mapper.BookMapper;
import org.booklore.model.dto.settings.AppSettings;
import org.booklore.model.dto.settings.CoverCroppingSettings;
import org.booklore.model.dto.settings.LibraryFile;
import org.booklore.model.dto.settings.MetadataMatchWeights;
import org.booklore.model.dto.settings.MetadataPersistenceSettings;
import org.booklore.model.dto.settings.SidecarSettings;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.entity.LibraryPathEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.model.enums.LibraryOrganizationMode;
import org.booklore.repository.*;
import org.booklore.service.appsettings.AppSettingService;
import org.booklore.service.book.BookCreatorService;
import org.booklore.service.file.FileFingerprint;
import org.booklore.service.fileprocessor.prepare.PersistedBook;
import org.booklore.service.fileprocessor.prepare.PrepareContext;
import org.booklore.service.fileprocessor.prepare.PrepareRequest;
import org.booklore.service.fileprocessor.prepare.PreparedBookFile;
import org.booklore.service.metadata.MetadataMatchService;
import org.booklore.service.metadata.extractor.*;
import org.booklore.service.metadata.sidecar.SidecarMetadataMapper;
import org.booklore.service.metadata.sidecar.SidecarMetadataWriter;
import org.booklore.service.reader.FfprobeService;
import org.booklore.util.CbxTestArchives;
import org.booklore.util.FileService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.web.client.RestTemplate;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.booklore.util.CbxTestArchives.comicInfo;
import static org.booklore.util.CbxTestArchives.file;
import static org.booklore.util.CbxTestArchives.imageFile;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * What processFile stores for a new book of every format, recorded before it was split into a preparation step and a
 * persisting step: every field of the book, its files and its metadata, the cover files written for it and its
 * sidecar. Both processFile and prepare followed by persistPrepared must still store exactly that. The processors run
 * with their real extractors, BookCreatorService, MetadataMatchService, FileService and SidecarMetadataWriter on files
 * written into a temporary library; only the repositories and the mapper are mocked.
 * <p>
 * The expected snapshots are in {@value #EXPECTED_FILE}. Every run writes what it saw to {@value #ACTUAL_FILE}; after
 * an intended change, check the difference and copy that file over the expected one.
 */
class ProcessorEquivalenceTest {

    private static final String EXPECTED_FILE = "src/test/resources/fileprocessor/processor-equivalence.txt";
    private static final String ACTUAL_FILE = "build/processor-equivalence.actual.txt";
    private static final String SECTION = "### ";
    private static final long BOOK_ID = 7L;
    private static final long FIXED_ZIP_TIME = 1_600_000_000_000L;

    private static final Map<String, String> ACTUAL = Collections.synchronizedMap(new LinkedHashMap<>());

    @TempDir
    Path tempDir;

    static Stream<Case> cases() {
        return Stream.of(
                Case.of("epub with metadata and cover", BookFileType.EPUB, "Author/Book", "book.epub",
                        file -> epub(file, EPUB_METADATA, jpeg(300, 450, 1))),
                Case.of("epub in a folder per book with only a folder cover", BookFileType.EPUB, "Author/Book", "book.epub",
                                file -> {
                                    epub(file, "<dc:title>Folder Cover</dc:title>", null);
                                    Files.write(file.resolveSibling("Folder.PNG"), png(400, 600, 2));
                                    Files.writeString(file.resolveSibling("cover.txt"), "not an image");
                                })
                        .inMode(LibraryOrganizationMode.BOOK_PER_FOLDER),
                Case.of("epub with an extremely tall cover, smart cropping on", BookFileType.EPUB, "", "tall.epub",
                                file -> epub(file, "<dc:title>Tall</dc:title>", tallJpeg(300, 1200, 200)))
                        .cropping(CoverCroppingSettings.builder().verticalCroppingEnabled(true).horizontalCroppingEnabled(true)
                                .aspectRatioThreshold(2.5).smartCroppingEnabled(true).build()),
                Case.of("epub already in the library", BookFileType.EPUB, "Author/Book", "book.epub",
                                file -> epub(file, EPUB_METADATA, jpeg(300, 450, 3)))
                        .alreadyInLibrary(),
                Case.of("epub with the sidecar written on scan", BookFileType.EPUB, "Author/Book", "book.epub",
                                file -> epub(file, EPUB_METADATA, jpeg(300, 450, 4)))
                        .withSidecar(),
                Case.of("pdf with document information", BookFileType.PDF, "Papers", "paper.pdf",
                        file -> pdf(file, PDRectangle.LETTER, true)),
                Case.of("unreadable pdf in a folder per book with a folder cover", BookFileType.PDF, "Papers/One", "broken.pdf",
                                file -> {
                                    Files.writeString(file, "not a pdf");
                                    Files.write(file.resolveSibling("cover.jpg"), jpeg(200, 300, 5));
                                })
                        .inMode(LibraryOrganizationMode.BOOK_PER_FOLDER),
                Case.of("cbz with comic info", BookFileType.CBX, "Comics", "My_Comic-01.cbz",
                        file -> CbxTestArchives.zip(file,
                                file("ComicInfo.xml", comicInfo(COMIC_INFO)),
                                file("pages/002.jpg", jpeg(120, 180, 6)),
                                file("pages/001.jpg", jpeg(100, 150, 7)),
                                file("notes.txt", "not an image"))),
                Case.of("cbr whose first image is broken", BookFileType.CBX, "Comics", "broken-first.cbr",
                        file -> CbxTestArchives.rar(file,
                                file("001.jpg", "this is not image data"),
                                file("002.png", png(90, 140, 8)),
                                file("ComicInfo.xml", comicInfo("<Title>Second Page</Title><Writer>Jane Doe</Writer>")))),
                Case.of("cb7 without images", BookFileType.CBX, "Comics", "text-only.cb7",
                        file -> CbxTestArchives.sevenZip(file,
                                file("ComicInfo.xml", comicInfo("<Title>Text Only</Title><Series>Words</Series><Number>2</Number>")),
                                imageFile("cover.gif", 4))),
                Case.of("unreadable cbz", BookFileType.CBX, "Comics", "Broken_Book-02.cbz",
                        file -> Files.writeString(file, "not a zip at all")),
                Case.of("fb2 with metadata and cover", BookFileType.FB2, "Classics", "war.fb2",
                        file -> Files.writeString(file, fb2(jpeg(160, 240, 9)), StandardCharsets.UTF_8)),
                Case.of("mobi with metadata and cover", BookFileType.MOBI, "Kindle", "book.mobi",
                        file -> mobi(1).writeTo(file)),
                Case.of("azw3 with metadata and cover", BookFileType.AZW3, "Kindle", "book.azw3",
                        file -> mobi(2).writeTo(file)),
                Case.of("mp3 audiobook", BookFileType.AUDIOBOOK, "Audio", "book.mp3",
                        file -> Files.copy(Path.of("src/test/resources/audio/fixture.mp3"), file)),
                Case.of("m4b audiobook with chapters", BookFileType.AUDIOBOOK, "Audio", "book.m4b",
                        file -> Files.copy(Path.of("src/test/resources/audio/fixture.m4b"), file)),
                Case.of("folder audiobook", BookFileType.AUDIOBOOK, "Audio", "Folder Book",
                                file -> {
                                    Files.createDirectories(file.resolve("extras"));
                                    Files.copy(Path.of("src/test/resources/audio/fixture.mp3"), file.resolve("02 - Two.mp3"));
                                    Files.copy(Path.of("src/test/resources/audio/fixture.mp3"), file.resolve("01 - One.mp3"));
                                    Files.writeString(file.resolve("extras/notes.txt"), "x".repeat(3000));
                                    Files.write(file.resolve("cover.jpg"), jpeg(200, 200, 10));
                                })
                        .asFolder(),
                Case.of("folder audiobook with an unreadable audio file and a folder cover", BookFileType.AUDIOBOOK, "Audio", "Unreadable",
                                file -> {
                                    Files.createDirectories(file);
                                    Files.writeString(file.resolve("01.m4a"), "not audio");
                                    Files.write(file.resolve("cover.jpg"), jpeg(500, 400, 11));
                                })
                        .asFolder()
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void processFileStoresWhatItAlwaysHas(Case c) throws Exception {
        Harness harness = new Harness(tempDir.resolve("inline"), c);
        LibraryFile libraryFile = harness.writeBook();

        harness.processor().processFile(libraryFile);

        String stored = harness.snapshot();
        ACTUAL.put(c.name(), stored);
        assertThat(stored).isEqualTo(expected(c.name()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void prepareThenPersistStoresWhatProcessFileAlwaysHas(Case c) throws Exception {
        Harness harness = new Harness(tempDir.resolve("split"), c);
        LibraryFile libraryFile = harness.writeBook();
        AbstractFileProcessor processor = harness.processor();

        PrepareRequest request = PrepareRequest.of(libraryFile, harness.fileService.currentCropPolicy());
        clearInvocations(harness.appSettingService);

        PreparedBookFile prepared = processor.prepare(request, PrepareContext.unbounded());
        // Reading the file needs no database and no settings, so it can run on any thread
        verifyNoInteractions(harness.repositories.toArray());
        verifyNoInteractions(harness.appSettingService, harness.bookMapper);

        PersistedBook persisted = processor.persistPrepared(libraryFile, prepared);
        if (persisted.sidecarJob() != null) {
            harness.sidecarMetadataWriter.execute(persisted.sidecarJob());
        }

        assertThat(persisted.bookId()).isEqualTo(BOOK_ID);
        assertThat(persisted.created()).isEqualTo(!c.existing());
        assertThat(harness.snapshot()).isEqualTo(expected(c.name()));
    }

    @AfterAll
    static void writeActual() throws IOException {
        StringBuilder out = new StringBuilder();
        cases().map(Case::name).filter(ACTUAL::containsKey)
                .forEach(name -> out.append(SECTION).append(name).append('\n').append(ACTUAL.get(name)));
        Path actual = Path.of(ACTUAL_FILE);
        Files.createDirectories(actual.getParent());
        Files.writeString(actual, out);
    }

    private static String expected(String caseName) throws IOException {
        Path file = Path.of(EXPECTED_FILE);
        if (!Files.exists(file)) {
            return "(no snapshot recorded: " + EXPECTED_FILE + " is missing)";
        }
        String text = Files.readString(file);
        int start = text.indexOf(SECTION + caseName + "\n");
        if (start < 0) {
            return "(no snapshot recorded for this case)";
        }
        start += SECTION.length() + caseName.length() + 1;
        int end = text.indexOf("\n" + SECTION, start);
        return end < 0 ? text.substring(start) : text.substring(start, end + 1);
    }

    // ========== the processors and their collaborators ==========

    /** Everything a processor needs, on a library and a config folder of its own. */
    private static final class Harness {

        private final Case c;
        private final Path library;
        private final Path config;
        private final AppSettingService appSettingService = mock(AppSettingService.class);
        private final List<Object> repositories = new ArrayList<>();
        private final BookRepository bookRepository = repository(BookRepository.class);
        private final BookAdditionalFileRepository bookAdditionalFileRepository = repository(BookAdditionalFileRepository.class);
        private final BookMapper bookMapper = mock(BookMapper.class);
        private final FileService fileService;
        private final BookCreatorService bookCreatorService;
        private final MetadataMatchService metadataMatchService;
        private final SidecarMetadataWriter sidecarMetadataWriter;
        private final LibraryEntity libraryEntity;
        private final LibraryPathEntity libraryPathEntity;
        private BookEntity saved;

        Harness(Path root, Case c) {
            this.c = c;
            this.library = root.resolve("library");
            this.config = root.resolve("config");

            AppProperties appProperties = new AppProperties();
            appProperties.setPathConfig(config.toString());
            AppSettings settings = AppSettings.builder()
                    .coverCroppingSettings(c.crop())
                    .metadataMatchWeights(MetadataMatchWeights.builder().build())
                    .metadataPersistenceSettings(MetadataPersistenceSettings.builder()
                            .sidecarSettings(SidecarSettings.builder()
                                    .enabled(c.sidecar()).writeOnScan(c.sidecar()).includeCoverFile(c.sidecar()).build())
                            .build())
                    .build();
            when(appSettingService.getAppSettings()).thenReturn(settings);

            fileService = new FileService(appProperties, mock(RestTemplate.class), appSettingService, mock(RestTemplate.class));
            bookCreatorService = new BookCreatorService(savingMock(AuthorRepository.class), savingMock(CategoryRepository.class),
                    savingMock(MoodRepository.class), savingMock(TagRepository.class), bookRepository,
                    savingMock(BookMetadataRepository.class), savingMock(ComicMetadataRepository.class),
                    savingMock(ComicCharacterRepository.class), savingMock(ComicTeamRepository.class),
                    savingMock(ComicLocationRepository.class), savingMock(ComicCreatorRepository.class));
            metadataMatchService = new MetadataMatchService(appSettingService, null);
            sidecarMetadataWriter = new SidecarMetadataWriter(appProperties, new SidecarMetadataMapper(), fileService, appSettingService);

            libraryEntity = LibraryEntity.builder().id(3L).name("Library").organizationMode(c.mode()).build();
            libraryPathEntity = LibraryPathEntity.builder().id(4L).library(libraryEntity).path(library.toString()).build();

            when(bookRepository.saveAndFlush(any(BookEntity.class))).thenAnswer(inv -> {
                BookEntity book = inv.getArgument(0);
                book.setId(BOOK_ID);
                return book;
            });
            when(bookRepository.save(any(BookEntity.class))).thenAnswer(inv -> saved = inv.getArgument(0));
            if (c.existing()) {
                BookEntity existing = existingBook();
                when(bookRepository.findBookFormatsByLibraryIdAndLibraryPathIdAndFileSubPathAndFileName(3L, 4L, c.subPath(), c.fileName()))
                        .thenReturn(List.of(existing.getPrimaryBookFile()));
            }
        }

        private <T> T repository(Class<T> type) {
            T repository = mock(type);
            repositories.add(repository);
            return repository;
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private <T extends org.springframework.data.jpa.repository.JpaRepository> T savingMock(Class<T> type) {
            T repository = repository(type);
            when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(repository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
            return repository;
        }

        private BookEntity existingBook() {
            BookEntity book = BookEntity.builder()
                    .id(BOOK_ID)
                    .library(libraryEntity)
                    .libraryPath(libraryPathEntity)
                    .addedOn(Instant.EPOCH)
                    .deleted(true)
                    .bookFiles(new ArrayList<>())
                    .build();
            book.getBookFiles().add(BookFileEntity.builder()
                    .book(book)
                    .fileName(c.fileName())
                    .fileSubPath(c.subPath())
                    .isBookFormat(true)
                    .bookType(c.type())
                    .fileSizeKb(999L)
                    .initialHash("old hash")
                    .currentHash("old hash")
                    .addedOn(Instant.EPOCH)
                    .build());
            book.setMetadata(BookMetadataEntity.builder().book(book).bookId(BOOK_ID).title("Old Title").build());
            return book;
        }

        LibraryFile writeBook() throws Exception {
            Path bookPath = c.subPath().isEmpty() ? library.resolve(c.fileName()) : library.resolve(c.subPath()).resolve(c.fileName());
            Files.createDirectories(bookPath.getParent());
            c.fixture().write(bookPath);
            return LibraryFile.builder()
                    .libraryEntity(libraryEntity)
                    .libraryPathEntity(libraryPathEntity)
                    .fileSubPath(c.subPath())
                    .fileName(c.fileName())
                    .bookFileType(c.type())
                    .folderBased(c.folderBased())
                    .build();
        }

        AbstractFileProcessor processor() {
            return switch (c.type()) {
                case EPUB -> new EpubProcessor(bookRepository, bookAdditionalFileRepository, bookCreatorService, bookMapper,
                        fileService, metadataMatchService, sidecarMetadataWriter, new EpubMetadataExtractor());
                case PDF -> new PdfProcessor(bookRepository, bookAdditionalFileRepository, bookCreatorService, bookMapper,
                        fileService, metadataMatchService, sidecarMetadataWriter, new PdfMetadataExtractor());
                case CBX -> new CbxProcessor(bookRepository, bookAdditionalFileRepository, bookCreatorService, bookMapper,
                        fileService, metadataMatchService, sidecarMetadataWriter, new CbxMetadataExtractor());
                case FB2 -> new Fb2Processor(bookRepository, bookAdditionalFileRepository, bookCreatorService, bookMapper,
                        fileService, metadataMatchService, sidecarMetadataWriter, new Fb2MetadataExtractor());
                case MOBI -> new MobiProcessor(bookRepository, bookAdditionalFileRepository, bookCreatorService, bookMapper,
                        fileService, metadataMatchService, sidecarMetadataWriter, new MobiMetadataExtractor());
                case AZW3 -> new Azw3Processor(bookRepository, bookAdditionalFileRepository, bookCreatorService, bookMapper,
                        fileService, metadataMatchService, sidecarMetadataWriter, new Azw3MetadataExtractor());
                // No ffprobe: chapters come from the tags or default to one
                case AUDIOBOOK -> new AudiobookProcessor(bookRepository, bookAdditionalFileRepository, bookCreatorService, bookMapper,
                        fileService, metadataMatchService, sidecarMetadataWriter, new AudiobookMetadataExtractor(mock(FfprobeService.class)));
                default -> throw new IllegalArgumentException("No processor for " + c.type());
            };
        }

        /** The saved book, the cover files in its images folder and the sidecar next to its file, as text. */
        String snapshot() throws Exception {
            assertThat(saved).as("the book handed to BookRepository.save").isNotNull();
            Path bookPath = library.resolve(c.subPath()).resolve(c.fileName());
            String bookHash = c.folderBased() ? FileFingerprint.generateFolderHash(bookPath) : FileFingerprint.generateHash(bookPath);

            StringBuilder out = new StringBuilder();
            dump(out, "", "book", saved, new IdentityHashMap<>());

            out.append("images:\n");
            Path images = Path.of(fileService.getImagesFolder(BOOK_ID));
            if (Files.isDirectory(images)) {
                try (Stream<Path> files = Files.list(images)) {
                    for (Path image : files.sorted().toList()) {
                        out.append("  ").append(image.getFileName()).append(' ').append(sha256(Files.readAllBytes(image))).append('\n');
                    }
                }
            }

            Path sidecar = sidecarMetadataWriter.getSidecarPath(bookPath);
            if (Files.exists(sidecar)) {
                out.append("sidecar:\n");
                for (String line : Files.readString(sidecar).replaceAll("\"generatedAt\": \"[^\"]*\"", "\"generatedAt\": <set>").split("\n")) {
                    out.append("  ").append(line).append('\n');
                }
                Path sidecarCover = sidecarMetadataWriter.getCoverPath(bookPath);
                if (Files.exists(sidecarCover)) {
                    out.append("sidecar cover: ").append(sha256(Files.readAllBytes(sidecarCover))).append('\n');
                }
            }
            return out.toString().replace(bookHash, "<hash of the book file>");
        }
    }

    record Case(String name, BookFileType type, String subPath, String fileName, Fixture fixture, LibraryOrganizationMode mode,
                boolean folderBased, CoverCroppingSettings crop, boolean sidecar, boolean existing) {

        static Case of(String name, BookFileType type, String subPath, String fileName, Fixture fixture) {
            return new Case(name, type, subPath, fileName, fixture, LibraryOrganizationMode.BOOK_PER_FILE, false, null, false, false);
        }

        Case inMode(LibraryOrganizationMode mode) {
            return new Case(name, type, subPath, fileName, fixture, mode, folderBased, crop, sidecar, existing);
        }

        Case asFolder() {
            return new Case(name, type, subPath, fileName, fixture, mode, true, crop, sidecar, existing);
        }

        Case cropping(CoverCroppingSettings crop) {
            return new Case(name, type, subPath, fileName, fixture, mode, folderBased, crop, sidecar, existing);
        }

        Case withSidecar() {
            return new Case(name, type, subPath, fileName, fixture, mode, folderBased, crop, true, existing);
        }

        Case alreadyInLibrary() {
            return new Case(name, type, subPath, fileName, fixture, mode, folderBased, crop, sidecar, true);
        }

        @Override
        public String toString() {
            return name;
        }
    }

    @FunctionalInterface
    interface Fixture {
        void write(Path bookPath) throws Exception;
    }

    // ========== snapshots ==========

    /**
     * One line per field that is not null, nested objects indented, sorted by field name. Times and the random cover
     * hashes only show that they are set. Back references to an object already being written are left out, and
     * entities known by name (authors, categories, comic characters and the like) are written as their name.
     */
    private static void dump(StringBuilder out, String indent, String name, Object value, IdentityHashMap<Object, Boolean> path) throws IllegalAccessException {
        if (value == null || path.containsKey(value)) {
            return;
        }
        if (value instanceof Instant || value instanceof LocalDateTime
                || name.equals("bookCoverHash") || name.equals("audiobookCoverHash")) {
            out.append(indent).append(name).append(": <set>\n");
        } else if (value instanceof LibraryEntity || value instanceof LibraryPathEntity) {
            out.append(indent).append(name).append(": <the library's>\n");
        } else if (isScalar(value)) {
            out.append(indent).append(name).append(": ").append(scalar(value)).append('\n');
        } else if (nameOf(value) != null) {
            out.append(indent).append(name).append(": ").append(scalar(nameOf(value))).append('\n');
        } else if (value instanceof Collection<?> collection) {
            List<String> items = new ArrayList<>();
            path.put(value, true);
            for (Object item : collection) {
                StringBuilder itemOut = new StringBuilder();
                dump(itemOut, indent + "    ", "-", item, path);
                items.add(itemOut.toString());
            }
            path.remove(value);
            if (value instanceof Set<?>) {
                Collections.sort(items);
            }
            out.append(indent).append(name).append(items.isEmpty() ? ": []\n" : ":\n");
            items.forEach(out::append);
        } else {
            out.append(indent).append(name).append(":\n");
            path.put(value, true);
            for (Field field : fields(value.getClass())) {
                dump(out, indent + "  ", field.getName(), field.get(value), path);
            }
            path.remove(value);
        }
    }

    private static boolean isScalar(Object value) {
        return value instanceof CharSequence || value instanceof Number || value instanceof Boolean
                || value instanceof Enum<?> || value instanceof java.time.temporal.Temporal;
    }

    private static String scalar(Object value) {
        return value.toString().replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r");
    }

    /** The name of an entity that is known by its name, such as an author, or null. */
    private static Object nameOf(Object value) throws IllegalAccessException {
        if (!value.getClass().isAnnotationPresent(jakarta.persistence.Entity.class)) {
            return null;
        }
        for (Field field : fields(value.getClass())) {
            if (field.getName().equals("name") && field.getType() == String.class) {
                return field.get(value);
            }
        }
        return null;
    }

    private static List<Field> fields(Class<?> type) {
        List<Field> fields = new ArrayList<>();
        for (Class<?> t = type; t != null && t != Object.class; t = t.getSuperclass()) {
            for (Field field : t.getDeclaredFields()) {
                // Hibernate's bytecode enhancement adds fields of its own
                if (!Modifier.isStatic(field.getModifiers()) && !field.getName().startsWith("$$_")) {
                    field.setAccessible(true);
                    fields.add(field);
                }
            }
        }
        fields.sort(Comparator.comparing(Field::getName));
        return fields;
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    // ========== fixtures ==========

    private static final String EPUB_METADATA = """
            <dc:title>The Fixture Book</dc:title>
            <dc:creator opf:role="aut">Jane Doe</dc:creator>
            <dc:creator opf:role="aut">John Roe</dc:creator>
            <dc:description>A book made for tests.</dc:description>
            <dc:publisher>Fixture House</dc:publisher>
            <dc:date>2020-05-17</dc:date>
            <dc:language>UND</dc:language>
            <dc:identifier opf:scheme="ISBN">9780306406157</dc:identifier>
            <dc:subject>Fiction</dc:subject>
            <dc:subject>Adventure</dc:subject>
            <dc:subject>Two  spaces</dc:subject>
            <meta name="calibre:series" content="Fixture Series"/>
            <meta name="calibre:series_index" content="3"/>
            <meta property="booklore:subtitle">A Subtitle</meta>
            <meta property="booklore:moods">Dark, Tense</meta>
            <meta property="booklore:tags">Favourite</meta>
            <meta property="booklore:page_count">321</meta>
            <meta property="booklore:goodreads_id">12345</meta>
            <meta property="booklore:age_rating">16</meta>
            """;

    private static final String COMIC_INFO = """
            <Title>The Title</Title>
            <Series>The Series</Series>
            <Number>7</Number>
            <Count>12</Count>
            <Volume>2</Volume>
            <AlternateSeries>Other Series</AlternateSeries>
            <AlternateNumber>3</AlternateNumber>
            <StoryArc>The Arc</StoryArc>
            <StoryArcNumber>1</StoryArcNumber>
            <Summary>A comic made for tests.</Summary>
            <Notes>Some notes</Notes>
            <Year>2021</Year>
            <Month>4</Month>
            <Day>9</Day>
            <Writer>Jane Doe, John Roe</Writer>
            <Penciller>Pen One</Penciller>
            <Inker>Ink One</Inker>
            <Colorist>Color One</Colorist>
            <Letterer>Letter One</Letterer>
            <CoverArtist>Cover One</CoverArtist>
            <Editor>Edit One</Editor>
            <Publisher>Fixture Comics</Publisher>
            <Imprint>Fixture Imprint</Imprint>
            <Genre>Action, Drama</Genre>
            <Tags>Collected</Tags>
            <Web>https://example.org/comic</Web>
            <PageCount>24</PageCount>
            <LanguageISO>fr</LanguageISO>
            <Format>Trade</Format>
            <BlackAndWhite>Yes</BlackAndWhite>
            <Manga>YesAndRightToLeft</Manga>
            <Characters>Hero, Sidekick</Characters>
            <Teams>The Team</Teams>
            <Locations>The City</Locations>
            <GTIN>9780306406157</GTIN>
            """;

    private static void epub(Path file, String metadata, byte[] cover) throws IOException {
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

    private static void pdf(Path file, PDRectangle box, boolean withInfo) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(box);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setNonStrokingColor(new Color(30, 60, 150));
                cs.addRect(40, 40, box.getWidth() / 2, box.getHeight() / 3);
                cs.fill();
                cs.setLineWidth(8);
                cs.addRect(4, 4, box.getWidth() - 8, box.getHeight() - 8);
                cs.moveTo(0, 0);
                cs.lineTo(box.getWidth(), box.getHeight());
                cs.stroke();
            }
            if (withInfo) {
                PDDocumentInformation info = new PDDocumentInformation();
                info.setTitle("A Paper");
                info.setAuthor("Ann Author, Bob Writer");
                info.setSubject("What the paper is about.");
                info.setKeywords("science; tests");
                doc.setDocumentInformation(info);
            }
            doc.save(file.toFile());
        }
    }

    private static String fb2(byte[] cover) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0" xmlns:l="http://www.w3.org/1999/xlink">
                  <description>
                    <title-info>
                      <genre>sf_fantasy</genre>
                      <genre>prose_classic</genre>
                      <author><first-name>Leo</first-name><last-name>Tolstoy</last-name></author>
                      <book-title>War and Peace</book-title>
                      <annotation><p>A long book.</p></annotation>
                      <keywords>war, peace</keywords>
                      <date value="1869-01-01">1869</date>
                      <lang>ru</lang>
                      <sequence name="Classics" number="2"/>
                      <coverpage><image l:href="#cover.jpg"/></coverpage>
                    </title-info>
                    <publish-info>
                      <publisher>Fixture House</publisher>
                      <isbn>978-0-306-40615-7</isbn>
                    </publish-info>
                  </description>
                  <body><section><p>Text</p></section></body>
                  <binary id="cover.jpg" content-type="image/jpeg">%s</binary>
                </FictionBook>
                """.formatted(Base64.getEncoder().encodeToString(cover));
    }

    private static MobiFixture mobi(int mobiType) {
        return MobiFixture.book()
                .mobiType(mobiType)
                .exth(100, "Jane Doe")
                .exth(101, "Fixture House")
                .exth(103, "A book made for tests.")
                .exth(104, "978-0-306-40615-7")
                .exth(105, "Fiction; Adventure")
                .exth(106, "2020-05-17")
                .exth(524, "en")
                .exth(113, "B00FIXTURE")
                .exthInt(201, 1)
                .image(jpeg(40, 60, 12))
                .image(jpeg(160, 240, 13));
    }

    /** A noisy gradient with shapes, so scaling and JPEG encoding have detail to work on. */
    private static BufferedImage picture(int width, int height, long seed) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Random random = new Random(seed);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int r = (x * 255 / Math.max(1, width - 1) + random.nextInt(24)) & 0xFF;
                int g = (y * 255 / Math.max(1, height - 1) + random.nextInt(24)) & 0xFF;
                int b = ((x + y) * 7 + random.nextInt(24)) & 0xFF;
                image.setRGB(x, y, (r << 16) | (g << 8) | b);
            }
        }
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(20, 40, 160));
        g.fillOval(width / 5, height / 5, width / 2, height / 3);
        g.dispose();
        return image;
    }

    private static byte[] jpeg(int width, int height, long seed) {
        return encode(picture(width, height, seed), "jpg");
    }

    private static byte[] png(int width, int height, long seed) {
        return encode(picture(width, height, seed), "png");
    }

    /** An extremely tall picture whose top rows are plain white, as smart cropping looks for. */
    private static byte[] tallJpeg(int width, int height, int whiteRows) {
        BufferedImage image = picture(width, height, 14);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width, whiteRows);
        g.dispose();
        return encode(image, "jpg");
    }

    private static byte[] encode(BufferedImage image, String format) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, format, out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
