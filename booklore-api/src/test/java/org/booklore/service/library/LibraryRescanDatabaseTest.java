package org.booklore.service.library;

import org.booklore.BookloreApplication;
import org.booklore.model.dto.settings.LibraryFile;
import org.booklore.model.entity.AuthorEntity;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.entity.LibraryPathEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.repository.AuthorRepository;
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
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
    private FilelessShells filelessShells;
    @Autowired
    private AuthorRepository authorRepository;
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
        // The real attacher, with the cover writing it decides on recorded by the mocked processor
        ExistingBookAttacher attacher = new ExistingBookAttacher(bookRepository, bookAdditionalFileRepository,
                fileAsBookProcessor, filelessShells, transactionManager);
        libraryProcessingService = new LibraryProcessingService(libraryRepository, mock(NotificationService.class),
                bookAdditionalFileRepository, bookRepository, fileAsBookProcessor, bookRestorationService,
                bookDeletionService, libraryFileHelper, bookGroupingService, attacher);
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
    void aNewFormatNextToABookTheSameRescanRestoresGoesToThatBook() throws IOException {
        // A book soft-deleted while its share was away, whose file is back, and a pdf of it added meanwhile
        Path dir = Files.createDirectories(libraryRoot.resolve("Herbert/Dune"));
        Files.writeString(dir.resolve("Dune.epub"), "epub");
        Files.writeString(dir.resolve("Dune.pdf"), "pdf");
        BookEntity dune = book("Herbert/Dune", "Dune.epub", BookFileType.EPUB, true);
        walkFinds("Herbert/Dune/Dune.epub", "Herbert/Dune/Dune.pdf");
        List<List<String>> newBooks = newBooksOfTheRescan();

        rescan();

        assertThat(bookRepository.findById(dune.getId()).orElseThrow().getDeleted()).isFalse();
        assertThat(newBooks).isEmpty();
        assertThat(fileNamesOf(dune)).containsExactlyInAnyOrder("Herbert/Dune/Dune.epub", "Herbert/Dune/Dune.pdf");
    }

    @Test
    void anEbookAttachedToAnAudiobookMakesItsEbookCoverFromThePrimaryTheBookHadBefore() throws IOException {
        // Changed deliberately: the cover decision saw the book with the epub already added and taken as its primary,
        // so an epub never gave an audiobook its ebook cover on a rescan, while the importer gives a new one
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

        rescan();

        assertThat(fileNamesOf(audiobook)).containsExactlyInAnyOrder("Author/Title/Title.m4b", "Author/Title/Title.epub");
        verify(fileAsBookProcessor).writeCoverFromFile(argThat(book -> book.getId().equals(audiobook.getId())),
                argThat(file -> file.getFileName().equals("Title.epub")));
    }

    /** A fileless book of the library, as a physical book or one whose files were lost is. */
    private BookEntity filelessBook(String title, String coverHash, String... authors) {
        return tx.execute(status -> {
            BookEntity book = BookEntity.builder().library(library).addedOn(Instant.now()).bookCoverHash(coverHash).build();
            BookMetadataEntity metadata = BookMetadataEntity.builder().book(book).title(title).coverUpdatedOn(SHELL_COVER_TIME).build();
            metadata.setAuthors(new ArrayList<>());
            for (String name : authors) {
                metadata.getAuthors().add(authorRepository.findByName(name)
                        .orElseGet(() -> authorRepository.save(AuthorEntity.builder().name(name).build())));
            }
            book.setMetadata(metadata);
            return bookRepository.save(book);
        });
    }

    private static final Instant SHELL_COVER_TIME = Instant.parse("2025-01-02T03:04:05Z");

    private void walkFinds(String... subPathAndNames) throws IOException {
        for (String subPathAndName : subPathAndNames) {
            Path file = libraryRoot.resolve(subPathAndName);
            Files.createDirectories(file.getParent());
            Files.writeString(file, subPathAndName);
        }
        when(libraryFileHelper.scanAllLibraryFiles(any())).thenAnswer(inv -> {
            LibraryEntity walked = inv.getArgument(0);
            List<LibraryFile> files = new ArrayList<>();
            for (String subPathAndName : subPathAndNames) {
                int slash = subPathAndName.lastIndexOf('/');
                String name = subPathAndName.substring(slash + 1);
                files.add(onDisk(walked, slash < 0 ? "" : subPathAndName.substring(0, slash), name,
                        org.booklore.model.enums.BookFileExtension.fromFileName(name).orElseThrow().getType()));
            }
            return new LibraryScanResult(files, Set.of());
        });
    }

    private List<List<String>> newBooksOfTheRescan() {
        List<List<String>> newBooks = new ArrayList<>();
        doAnswer(inv -> {
            java.util.Map<String, List<LibraryFile>> groups = inv.getArgument(0);
            groups.values().forEach(group -> newBooks.add(group.stream()
                    .map(file -> file.getFileSubPath() + "/" + file.getFileName()).toList()));
            return null;
        }).when(fileAsBookProcessor).processLibraryFilesGrouped(any(), any());
        return newBooks;
    }

    private BookEntity reload(BookEntity book) {
        return tx.execute(status -> {
            BookEntity loaded = bookRepository.findById(book.getId()).orElseThrow();
            loaded.getMetadata().getCoverUpdatedOn();
            return loaded;
        });
    }

    @Test
    void aFilelessBookClaimedFromTwoFoldersKeepsItsCoverAndGetsNoFile() throws IOException {
        // Production: three "Survival.epub" of three folders went to one fileless book
        BookEntity survival = filelessBook("Survival", "h0", "Ann Smith");
        walkFinds("S/Smith, Ann/Survival.epub", "S/Smith, Ann/Series/Survival.epub");
        List<List<String>> newBooks = newBooksOfTheRescan();

        rescan();

        assertThat(fileNamesOf(survival)).isEmpty();
        BookEntity after = reload(survival);
        assertThat(after.getLibraryPath()).isNull();
        assertThat(after.getBookCoverHash()).isEqualTo("h0");
        assertThat(newBooks).containsExactlyInAnyOrder(List.of("S/Smith, Ann/Survival.epub"), List.of("S/Smith, Ann/Series/Survival.epub"));
        verify(fileAsBookProcessor, never()).writeCoverFromFile(any(), any());
    }

    @Test
    void aFilelessBookOfAnotherAuthorGetsNoFile() throws IOException {
        // Production: "Assignment - Karachi" and four more went to "Assignment" by I. Christie
        BookEntity assignment = filelessBook("Assignment", "h0", "I. Christie");
        walkFinds("A/Aarons, Edward S/Assignment - Karachi.epub", "A/Aarons, Edward S/Assignment - Stella Marni.epub");
        List<List<String>> newBooks = newBooksOfTheRescan();

        rescan();

        assertThat(fileNamesOf(assignment)).isEmpty();
        assertThat(newBooks).hasSize(2);
    }

    @Test
    void aFilelessBookWithACoverGetsTheFilesOfItsOneFolderAndKeepsItsCover() throws IOException {
        BookEntity martian = filelessBook("The Martian", "h0", "Andy Weir");
        walkFinds("W/Weir, Andy/The Martian.epub", "W/Weir, Andy/The Martian.pdf", "W/Weir, Andy/Artemis.epub");
        List<List<String>> newBooks = newBooksOfTheRescan();

        rescan();

        assertThat(fileNamesOf(martian)).containsExactlyInAnyOrder("W/Weir, Andy/The Martian.epub", "W/Weir, Andy/The Martian.pdf");
        BookEntity after = reload(martian);
        assertThat(after.getLibraryPath().getId()).isEqualTo(libraryPath.getId());
        assertThat(after.getBookCoverHash()).isEqualTo("h0");
        assertThat(after.getMetadata().getCoverUpdatedOn()).isEqualTo(SHELL_COVER_TIME);
        verify(fileAsBookProcessor, never()).writeCoverFromFile(any(), any());
        assertThat(newBooks).containsExactly(List.of("W/Weir, Andy/Artemis.epub"));
    }

    @Test
    void aFilelessBookWithoutACoverGetsOneFromItsNewFile() throws IOException {
        BookEntity martian = filelessBook("The Martian", null, "Andy Weir");
        walkFinds("W/Weir, Andy/The Martian.pdf", "W/Weir, Andy/The Martian.epub");

        rescan();

        assertThat(fileNamesOf(martian)).hasSize(2);
        // From the file that is now its primary, by the library's format priority
        verify(fileAsBookProcessor).writeCoverFromFile(argThat(book -> book.getId().equals(martian.getId())),
                argThat(file -> file.getFileName().equals("The Martian.epub")));
        verify(fileAsBookProcessor, times(1)).writeCoverFromFile(any(), any());
    }

    @Test
    void theFilesOfOneFolderGoToOneOfAlikeFilelessBooks() throws IOException {
        // Two records of "The Martian" by Andy Weir: the files go to the one with the lowest id, the other stays fileless
        BookEntity first = filelessBook("The Martian", "h0", "Andy Weir");
        BookEntity second = filelessBook("The Martian", "h1", "Andy Weir");
        walkFinds("W/Weir, Andy/The Martian.epub", "W/Weir, Andy/The Martian.pdf");
        List<List<String>> newBooks = newBooksOfTheRescan();

        rescan();

        assertThat(fileNamesOf(first)).containsExactlyInAnyOrder("W/Weir, Andy/The Martian.epub", "W/Weir, Andy/The Martian.pdf");
        assertThat(fileNamesOf(second)).isEmpty();
        assertThat(reload(second).getLibraryPath()).isNull();
        assertThat(newBooks).isEmpty();
    }

    @Test
    void alikeFilelessBooksClaimedFromTwoFoldersGetNoFile() throws IOException {
        BookEntity first = filelessBook("Survival", "h0", "Ann Smith");
        BookEntity second = filelessBook("Survival", "h1", "Ann Smith");
        walkFinds("S/Smith, Ann/Survival.epub", "S/Smith, Ann/Series/Survival.epub");
        List<List<String>> newBooks = newBooksOfTheRescan();

        rescan();

        assertThat(fileNamesOf(first)).isEmpty();
        assertThat(fileNamesOf(second)).isEmpty();
        assertThat(newBooks).containsExactlyInAnyOrder(List.of("S/Smith, Ann/Survival.epub"), List.of("S/Smith, Ann/Series/Survival.epub"));
    }

    @Test
    void aSecondDownloadAloneGoesToTheFilelessBookOfItsTitle() throws IOException {
        // The attacher checks it against the book once more, and must read " (1)" as the matcher did
        BookEntity martian = filelessBook("The Martian", "h0", "Andy Weir");
        walkFinds("W/Weir, Andy/The Martian (1).epub");
        List<List<String>> newBooks = newBooksOfTheRescan();

        rescan();

        assertThat(fileNamesOf(martian)).containsExactly("W/Weir, Andy/The Martian (1).epub");
        assertThat(newBooks).isEmpty();
    }

    @Test
    void aWeakClaimYieldsTheFilelessBookToTheStrongOne() throws IOException {
        // "MEG (1).epub" is the "MEG" only with its " (1)" read as a copy; "The MEG.epub" got the book before that
        BookEntity meg = filelessBook("MEG", "h0", "Steve Alten");
        walkFinds("A/Steve Alten/MEG/The MEG.epub", "A/Steve Alten/MEG (1).epub");
        List<List<String>> newBooks = newBooksOfTheRescan();

        rescan();

        assertThat(fileNamesOf(meg)).containsExactly("A/Steve Alten/MEG/The MEG.epub");
        assertThat(newBooks).containsExactly(List.of("A/Steve Alten/MEG (1).epub"));
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
