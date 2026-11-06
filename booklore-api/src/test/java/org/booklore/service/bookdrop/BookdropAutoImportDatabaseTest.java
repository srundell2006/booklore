package org.booklore.service.bookdrop;

import org.booklore.BookloreApplication;
import org.booklore.model.dto.BookMetadata;
import org.booklore.model.dto.request.BookdropFinalizeRequest;
import org.booklore.model.dto.response.BookdropFinalizeResult;
import org.booklore.model.entity.BookdropFileEntity;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.entity.LibraryPathEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.repository.BookRepository;
import org.booklore.repository.BookdropFileRepository;
import org.booklore.repository.LibraryRepository;
import org.booklore.service.NotificationService;
import org.booklore.service.kobo.KoboAutoShelfService;
import org.booklore.service.task.TaskCronService;
import org.booklore.util.OpenEntityManagerRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

/**
 * A Bookdrop import as the auto-import runs it: on a thread of its own, outside any request, against H2 with the real
 * services, repositories and file processor. On 2026-11-06 every such import failed on the metadata update
 * ("Unable to perform requested lazy initialization [BookEntity.metadata] - no session"), and each left the book it
 * had created behind without its file, which the import's cleanup had deleted. The same import from the Bookdrop page
 * worked, as a request has a persistence context open for its whole length.
 */
@SpringBootTest(classes = {BookloreApplication.class})
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.datasource.url=jdbc:h2:mem:bookdropautoimport;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "app.path-config=build/tmp/test-config",
        "app.bookdrop-folder=build/tmp/test-bookdrop",
        "spring.main.allow-bean-definition-overriding=true",
        "spring.task.scheduling.enabled=false",
        "app.task.scan-library-cron=*/1 * * * * *",
        "app.task.process-bookdrop-cron=*/1 * * * * *",
        "app.features.oidc-enabled=false"
})
@Import(BookdropAutoImportDatabaseTest.TestConfig.class)
class BookdropAutoImportDatabaseTest {

    @org.springframework.boot.test.context.TestConfiguration
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
    }

    @MockitoBean
    private NotificationService notificationService;
    @MockitoBean
    private KoboAutoShelfService koboAutoShelfService;

    @Autowired
    private BookDropService bookDropService;
    @Autowired
    private OpenEntityManagerRunner openEntityManagerRunner;
    @Autowired
    private LibraryRepository libraryRepository;
    @Autowired
    private BookRepository bookRepository;
    @Autowired
    private BookdropFileRepository bookdropFileRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @TempDir
    Path libraryRoot;
    @TempDir
    Path dropFolder;

    private TransactionTemplate committed;

    @BeforeEach
    void setUp() {
        committed = new TransactionTemplate(transactionManager);
        committed.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        // As in the Flyway schema: a new book's metadata row is saved before the file's title is applied
        jdbcTemplate.execute("ALTER TABLE book_metadata ALTER COLUMN title SET NULL");
    }

    @Test
    void anImportOnAThreadOfItsOwnWithoutAPersistenceContextFailsAndLeavesNoBookBehind() throws Exception {
        Fixture fixture = fixture("Lost Book");

        Outcome<BookdropFinalizeResult> outcome = onAPlainThread(() -> bookDropService.finalizeImport(fixture.request()));

        assertThat(outcome.error()).isNull();
        assertThat(outcome.result().getFailed()).isEqualTo(1);
        assertThat(outcome.result().getResults().getFirst().getMessage()).contains("lazy initialization");
        // The book the import had created is gone with its file, and the source waits in the drop folder
        assertThat(booksInLibrary(fixture.library())).isZero();
        assertThat(fixture.source()).exists();
        assertThat(libraryRoot.resolve("Lost Book.epub")).doesNotExist();
        assertThat(bookdropFileRepository.findById(fixture.bookdropFileId())).isPresent();
    }

    @Test
    void anImportOnAThreadOfItsOwnWithThePersistenceContextARequestHasSucceeds() throws Exception {
        Fixture fixture = fixture("Kept Book");

        Outcome<BookdropFinalizeResult> outcome = onAPlainThread(() -> openEntityManagerRunner.call(() -> bookDropService.finalizeImport(fixture.request())));

        assertThat(outcome.error()).isNull();
        assertThat(outcome.result().getFailed()).isZero();
        assertThat(outcome.result().getSuccessfullyImported()).isEqualTo(1);
        assertThat(booksInLibrary(fixture.library())).isEqualTo(1);
        assertThat(libraryRoot.resolve("Kept Book.epub")).exists();
        assertThat(fixture.source()).doesNotExist();
        assertThat(bookdropFileRepository.findById(fixture.bookdropFileId())).isEmpty();
        assertThat(outcome.boundAfterwards()).isFalse();
    }

    @Test
    void aBookWhoseImportFailsAfterItsMetadataWasSavedIsRemovedAndTheNextFileOfTheImportStillGoesIn() throws Exception {
        Fixture failing = fixture("Failing Book");
        Fixture next = fixture("Next Book", failing.library());
        BookdropFinalizeRequest request = failing.request();
        request.setFiles(List.of(failing.request().getFiles().getFirst(), next.request().getFiles().getFirst()));
        // A step after the metadata update fails for the first file only
        doThrow(new IllegalStateException("Kobo shelf failed")).doNothing()
                .when(koboAutoShelfService).autoAddBookToKoboShelves(anyLong());

        Outcome<BookdropFinalizeResult> outcome = onAPlainThread(() -> openEntityManagerRunner.call(() -> bookDropService.finalizeImport(request)));

        assertThat(outcome.error()).isNull();
        assertThat(outcome.result().getFailed()).isEqualTo(1);
        assertThat(outcome.result().getSuccessfullyImported()).isEqualTo(1);
        // The first book went with its metadata, which had been saved; the second is in with its file
        assertThat(titlesInLibrary(failing.library())).containsExactly("Next Book");
        assertThat(failing.source()).exists();
        assertThat(libraryRoot.resolve("Failing Book.epub")).doesNotExist();
        assertThat(bookdropFileRepository.findById(failing.bookdropFileId())).isPresent();
        assertThat(libraryRoot.resolve("Next Book.epub")).exists();
        assertThat(next.source()).doesNotExist();
        assertThat(bookdropFileRepository.findById(next.bookdropFileId())).isEmpty();
        assertThat(outcome.boundAfterwards()).isFalse();
    }

    @Test
    void theRunnerReusesAPersistenceContextThatIsBoundAlready() throws Exception {
        Outcome<List<Boolean>> outcome = onAPlainThread(() -> openEntityManagerRunner.call(() -> {
            boolean outerBound = isBound();
            boolean innerBound = openEntityManagerRunner.call(this::isBound);
            // The inner call must not have unbound or closed the outer persistence context
            return List.of(outerBound, innerBound, isBound());
        }));

        assertThat(outcome.error()).isNull();
        assertThat(outcome.result()).containsExactly(true, true, true);
        assertThat(outcome.boundAfterwards()).isFalse();
    }

    private boolean isBound() {
        return TransactionSynchronizationManager.getResourceMap().keySet().stream()
                .anyMatch(key -> key instanceof jakarta.persistence.EntityManagerFactory);
    }

    private record Fixture(LibraryEntity library, Long bookdropFileId, Path source, BookdropFinalizeRequest request) {}

    private record Outcome<T>(T result, Throwable error, boolean boundAfterwards) {}

    /** Runs the work on a new platform thread, as the Bookdrop file processor does, with nothing bound to it. */
    private <T> Outcome<T> onAPlainThread(Supplier<T> work) throws InterruptedException {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        AtomicReference<Boolean> boundAfterwards = new AtomicReference<>();
        Thread thread = Thread.ofPlatform().name("BookdropFileProcessor-test").start(() -> {
            try {
                result.set(work.get());
            } catch (Throwable t) {
                error.set(t);
            } finally {
                boundAfterwards.set(isBound());
            }
        });
        thread.join(60_000);
        assertThat(thread.isAlive()).isFalse();
        return new Outcome<>(result.get(), error.get(), boundAfterwards.get());
    }

    private long booksInLibrary(LibraryEntity library) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM book WHERE library_id = ?", Long.class, library.getId());
    }

    private List<String> titlesInLibrary(LibraryEntity library) {
        return jdbcTemplate.queryForList("SELECT m.title FROM book b JOIN book_metadata m ON m.book_id = b.id WHERE b.library_id = ?",
                String.class, library.getId());
    }

    private Fixture fixture(String title) throws IOException {
        return fixture(title, null);
    }

    private Fixture fixture(String title, LibraryEntity existingLibrary) throws IOException {
        LibraryEntity library = existingLibrary != null ? existingLibrary : committed.execute(status -> {
            LibraryEntity created = libraryRepository.save(LibraryEntity.builder()
                    .name("Books " + libraryRoot.getFileName()).icon("book").watch(false)
                    .fileNamingPattern("{title}")
                    .formatPriority(new ArrayList<>(List.of(BookFileType.EPUB)))
                    .libraryPaths(new ArrayList<>())
                    .build());
            created.getLibraryPaths().add(LibraryPathEntity.builder().library(created).path(libraryRoot.toString()).build());
            return libraryRepository.save(created);
        });
        Long pathId = library.getLibraryPaths().getFirst().getId();

        Path source = dropFolder.resolve(title.toLowerCase().replace(' ', '-') + ".epub");
        writeEpub(source, title);
        Long bookdropFileId = committed.execute(status -> bookdropFileRepository.save(BookdropFileEntity.builder()
                .filePath(source.toAbsolutePath().toString())
                .fileName(source.getFileName().toString())
                .fileSize(sizeOf(source))
                .status(BookdropFileEntity.Status.PENDING_REVIEW)
                .matchScore(90)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build()).getId());

        // What the auto-import asks for. It leaves the metadata to the stored json, which H2 hands back double-encoded,
        // so the metadata that json holds is passed here; the rest of the import is the same
        BookdropFinalizeRequest.BookdropFinalizeFile file = new BookdropFinalizeRequest.BookdropFinalizeFile();
        file.setFileId(bookdropFileId);
        file.setLibraryId(library.getId());
        file.setPathId(pathId);
        file.setMetadata(BookMetadata.builder().title(title).authors(List.of("Test Author")).build());
        BookdropFinalizeRequest request = new BookdropFinalizeRequest();
        request.setSelectAll(false);
        request.setDefaultLibraryId(library.getId());
        request.setDefaultPathId(pathId);
        request.setFiles(List.of(file));
        return new Fixture(library, bookdropFileId, source, request);
    }

    private static long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void writeEpub(Path epub, String title) throws IOException {
        String opf = """
                <?xml version="1.0" encoding="UTF-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opf="http://www.idpf.org/2007/opf">
                    <dc:title>%s</dc:title>
                    <dc:creator>Test Author</dc:creator>
                    <dc:language>en</dc:language>
                    <dc:identifier>urn:uuid:%s</dc:identifier>
                  </metadata>
                  <manifest>
                    <item id="c1" href="c1.xhtml" media-type="application/xhtml+xml"/>
                  </manifest>
                </package>""".formatted(title, java.util.UUID.nameUUIDFromBytes(title.getBytes(StandardCharsets.UTF_8)));
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(epub.toFile()))) {
            zos.putNextEntry(new ZipEntry("mimetype"));
            zos.write("application/epub+zip".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("META-INF/container.xml"));
            zos.write("""
                    <?xml version="1.0" encoding="UTF-8"?>
                    <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                      <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
                    </container>""".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("OEBPS/content.opf"));
            zos.write(opf.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("OEBPS/c1.xhtml"));
            zos.write("<html xmlns=\"http://www.w3.org/1999/xhtml\"><body><p>Text</p></body></html>".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
    }
}
