package org.booklore.service.library;

import org.booklore.BookloreApplication;
import org.booklore.model.dto.settings.LibraryFile;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.entity.LibraryPathEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.repository.BookAdditionalFileRepository;
import org.booklore.repository.BookRepository;
import org.booklore.repository.LibraryRepository;
import org.booklore.service.NotificationService;
import org.booklore.service.task.TaskCronService;
import org.booklore.task.options.RescanLibraryContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Rescans against H2 with the real repositories, transactions and phase services; only the
 * walk and book creation are stubbed. Nothing here runs in a test transaction, as a rescan
 * opens its own.
 */
@SpringBootTest(classes = {
        BookloreApplication.class
})
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.datasource.url=jdbc:h2:mem:libraryrescan;DB_CLOSE_DELAY=-1",
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
@Import(LibraryRescanDatabaseTest.TestConfig.class)
class LibraryRescanDatabaseTest {

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

    @Autowired
    private LibraryRepository libraryRepository;
    @Autowired
    private BookRepository bookRepository;
    @Autowired
    private BookAdditionalFileRepository bookAdditionalFileRepository;
    @Autowired
    private BookRestorationService bookRestorationService;
    @Autowired
    private BookDeletionService bookDeletionService;
    @Autowired
    private BookGroupingService bookGroupingService;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @TempDir
    Path libraryRoot;

    private final LibraryFileHelper libraryFileHelper = mock(LibraryFileHelper.class);
    private final FileAsBookProcessor fileAsBookProcessor = mock(FileAsBookProcessor.class);

    private TransactionTemplate tx;
    private LibraryProcessingService libraryProcessingService;
    private LibraryEntity library;
    private LibraryPathEntity libraryPath;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        libraryProcessingService = new LibraryProcessingService(libraryRepository, mock(NotificationService.class),
                bookAdditionalFileRepository, bookRepository, fileAsBookProcessor, bookRestorationService,
                bookDeletionService, libraryFileHelper, bookGroupingService, transactionManager);
        when(libraryFileHelper.filterByAllowedFormats(anyList(), any())).thenAnswer(inv -> inv.getArgument(0));

        library = tx.execute(status -> {
            LibraryEntity created = libraryRepository.save(LibraryEntity.builder()
                    .name("Books").icon("book").watch(false)
                    .formatPriority(new ArrayList<>(List.of(BookFileType.EPUB, BookFileType.PDF, BookFileType.CBX,
                            BookFileType.MOBI, BookFileType.AZW3, BookFileType.FB2, BookFileType.AUDIOBOOK)))
                    .libraryPaths(new ArrayList<>())
                    .build());
            created.getLibraryPaths().add(LibraryPathEntity.builder().library(created).path(libraryRoot.toString()).build());
            return libraryRepository.save(created);
        });
        libraryPath = library.getLibraryPaths().getFirst();
    }

    private BookEntity book(String subPath, String fileName, BookFileType type, boolean deleted) {
        return tx.execute(status -> {
            BookEntity book = bookRepository.save(BookEntity.builder()
                    .library(library).libraryPath(libraryPath).addedOn(Instant.now()).deleted(deleted).build());
            bookAdditionalFileRepository.save(BookFileEntity.builder()
                    .book(book).fileSubPath(subPath).fileName(fileName).bookType(type).isBookFormat(true).build());
            return book;
        });
    }

    private BookEntity book(String subPath, String fileName) {
        return book(subPath, fileName, BookFileType.EPUB, false);
    }

    /** What the walk reports, built from the library it is handed as the real walk does. */
    private LibraryFile onDisk(LibraryEntity walked, String subPath, String fileName, BookFileType type) {
        return LibraryFile.builder()
                .libraryEntity(walked)
                .libraryPathEntity(walked.getLibraryPaths().getFirst())
                .fileSubPath(subPath)
                .fileName(fileName)
                .bookFileType(type)
                .build();
    }

    private void rescan() throws IOException {
        libraryProcessingService.rescanLibrary(RescanLibraryContext.builder().libraryId(library.getId()).build());
    }

    private List<String> fileNamesOf(BookEntity book) {
        return bookAdditionalFileRepository.findByBookId(book.getId()).stream()
                .map(file -> file.getFileSubPath() + "/" + file.getFileName())
                .toList();
    }

    @Test
    void booksThatChangeWhileTheWalkRunsAreLeftForTheNextRescan() throws IOException {
        BookEntity present = book("A", "A.epub");
        BookEntity vanished = book("G", "G.epub");
        BookEntity moved = book("old", "M.epub");
        BookEntity softDeletedDuringWalk = book("S", "S.epub");
        BookEntity[] importedDuringWalk = new BookEntity[1];

        when(libraryFileHelper.scanAllLibraryFiles(any())).thenAnswer(inv -> {
            LibraryEntity walked = inv.getArgument(0);
            // Meanwhile, in directories the walk has already listed: a book is imported, a file
            // is moved, and a book whose file the walk saw is soft-deleted as its file goes.
            importedDuringWalk[0] = book("X", "X.epub");
            tx.executeWithoutResult(status -> {
                bookAdditionalFileRepository.findByBookId(moved.getId()).getFirst().setFileSubPath("new");
                bookRepository.findById(softDeletedDuringWalk.getId()).orElseThrow().setDeleted(true);
            });
            return new LibraryScanResult(List.of(
                    onDisk(walked, "A", "A.epub", BookFileType.EPUB),
                    onDisk(walked, "S", "S.epub", BookFileType.EPUB)), Set.of());
        });

        rescan();

        assertThat(bookRepository.findById(vanished.getId())).isEmpty();
        assertThat(fileNamesOf(present)).containsExactly("A/A.epub");
        assertThat(fileNamesOf(importedDuringWalk[0])).containsExactly("X/X.epub");
        assertThat(fileNamesOf(moved)).containsExactly("new/M.epub");
        assertThat(bookRepository.findById(softDeletedDuringWalk.getId()).orElseThrow().getDeleted()).isTrue();
    }

    @Test
    void autoAttachHandsTheCoverDecisionTheBookWithTheAttachedFileAsBefore() throws IOException {
        Path titleDir = Files.createDirectories(libraryRoot.resolve("Author/Title"));
        Files.writeString(titleDir.resolve("Title.m4b"), "audio");
        Files.writeString(titleDir.resolve("Title.epub"), "epub");
        BookEntity audiobook = book("Author/Title", "Title.m4b", BookFileType.AUDIOBOOK, false);
        when(libraryFileHelper.scanAllLibraryFiles(any())).thenAnswer(inv -> {
            LibraryEntity walked = inv.getArgument(0);
            return new LibraryScanResult(List.of(
                    onDisk(walked, "Author/Title", "Title.m4b", BookFileType.AUDIOBOOK),
                    onDisk(walked, "Author/Title", "Title.epub", BookFileType.EPUB)), Set.of());
        });
        List<String> filesSeenByCoverDecision = new ArrayList<>();
        BookFileType[] primarySeenByCoverDecision = new BookFileType[1];
        doAnswer(inv -> {
            BookEntity book = inv.getArgument(0);
            book.getBookFiles().forEach(file -> filesSeenByCoverDecision.add(file.getFileName()));
            primarySeenByCoverDecision[0] = book.getPrimaryBookFile().getBookType();
            return null;
        }).when(fileAsBookProcessor).generateCoverFromAdditionalFile(any(), any());

        rescan();

        assertThat(fileNamesOf(audiobook)).containsExactlyInAnyOrder("Author/Title/Title.m4b", "Author/Title/Title.epub");
        // As in the old one-transaction rescan, where the book's files were already loaded and
        // association management added the new file to them as soon as it was built.
        assertThat(filesSeenByCoverDecision).containsExactly("Title.m4b", "Title.epub");
        assertThat(primarySeenByCoverDecision[0]).isEqualTo(BookFileType.EPUB);
    }

    @Test
    void newFilesByTheFoldersAuthorBecomeOneBookEach() throws IOException {
        // Production: in "B/Dale Brown", "Shadow Command" and "Warrior Class" were imported as more formats of
        // "Battle Born", because the folder's name was part of every file's name
        BookEntity other = book("Other", "Other.epub");
        when(libraryFileHelper.scanAllLibraryFiles(any())).thenAnswer(inv -> {
            LibraryEntity walked = inv.getArgument(0);
            return new LibraryScanResult(List.of(
                    onDisk(walked, "Other", "Other.epub", BookFileType.EPUB),
                    onDisk(walked, "B/Dale Brown", "Battle Born by Dale Brown.epub", BookFileType.EPUB),
                    onDisk(walked, "B/Dale Brown", "Shadow Command by Dale Brown.epub", BookFileType.EPUB),
                    onDisk(walked, "B/Dale Brown", "Warrior Class by Dale Brown.epub", BookFileType.EPUB)), Set.of());
        });
        List<List<String>> newBooks = new ArrayList<>();
        doAnswer(inv -> {
            java.util.Map<String, List<LibraryFile>> groups = inv.getArgument(0);
            groups.values().forEach(group -> newBooks.add(group.stream().map(LibraryFile::getFileName).toList()));
            return null;
        }).when(fileAsBookProcessor).processLibraryFilesGrouped(any(), any());

        rescan();

        assertThat(newBooks).containsExactlyInAnyOrder(
                List.of("Battle Born by Dale Brown.epub"),
                List.of("Shadow Command by Dale Brown.epub"),
                List.of("Warrior Class by Dale Brown.epub"));
        assertThat(fileNamesOf(other)).containsExactly("Other/Other.epub");
    }
}
