package org.booklore.service.library;

import org.booklore.model.dto.settings.LibraryFile;
import org.booklore.model.entity.AuthorEntity;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.entity.LibraryPathEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.repository.BookAdditionalFileRepository;
import org.booklore.repository.BookRepository;
import org.booklore.service.file.FileFingerprint;
import org.booklore.service.library.matching.ExistingBookMatcher.Kind;
import org.booklore.service.library.matching.ShellCandidate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The shared attach step of the rescan and the watcher: the book it reloads, what it skips, and the cover it decides
 * from what the book was before any file was built for it.
 */
@ExtendWith(MockitoExtension.class)
class ExistingBookAttacherTest {

    @Mock
    private BookRepository bookRepository;
    @Mock
    private BookAdditionalFileRepository bookAdditionalFileRepository;
    @Mock
    private FileAsBookProcessor fileAsBookProcessor;
    @Mock
    private FilelessShells filelessShells;
    @Mock
    private PlatformTransactionManager transactionManager;

    private static final String WEIR = "W/Weir, Andy";

    @TempDir
    Path root;

    private ExistingBookAttacher attacher;
    private LibraryEntity library;
    private LibraryPathEntity path;

    @BeforeEach
    void setUp() {
        attacher = new ExistingBookAttacher(bookRepository, bookAdditionalFileRepository, fileAsBookProcessor,
                filelessShells, transactionManager);
        path = LibraryPathEntity.builder().id(10L).path(root.toString()).build();
        library = LibraryEntity.builder().id(1L).name("Books")
                .formatPriority(new ArrayList<>(List.of(BookFileType.EPUB, BookFileType.PDF, BookFileType.AUDIOBOOK)))
                .libraryPaths(List.of(path)).build();
    }

    private LibraryFile onDisk(String subPath, String name, BookFileType type) throws IOException {
        Path dir = Files.createDirectories(root.resolve(subPath));
        Files.writeString(dir.resolve(name), name);
        return LibraryFile.builder().libraryEntity(library).libraryPathEntity(path)
                .fileSubPath(subPath).fileName(name).bookFileType(type).build();
    }

    private BookEntity book(long id, LibraryPathEntity bookPath, String coverHash, BookFileType... types) {
        BookEntity book = BookEntity.builder().id(id).library(library).libraryPath(bookPath).bookCoverHash(coverHash)
                .bookFiles(new ArrayList<>()).build();
        book.setMetadata(BookMetadataEntity.builder().bookId(id).title("Title " + id).build());
        long fileId = id * 10;
        for (BookFileType type : types) {
            BookFileEntity file = BookFileEntity.builder().id(fileId++).fileSubPath("A").fileName("Title." + type)
                    .isBookFormat(true).bookType(type).build();
            file.setBook(book);
            if (!book.getBookFiles().contains(file)) {
                book.getBookFiles().add(file);
            }
        }
        when(bookRepository.findByIdForUpdate(id)).thenReturn(Optional.of(book));
        return book;
    }

    /** A fileless book "The Martian" by Andy Weir, which files in "W/Weir, Andy" match. */
    private BookEntity shell(long id, String coverHash) {
        BookEntity book = book(id, null, coverHash, new BookFileType[0]);
        book.getMetadata().setTitle("The Martian");
        book.getMetadata().setAuthors(new ArrayList<>(List.of(AuthorEntity.builder().name("Andy Weir").build())));
        return book;
    }

    private List<BookFileEntity> saved(int times) {
        ArgumentCaptor<BookFileEntity> saved = ArgumentCaptor.forClass(BookFileEntity.class);
        verify(bookAdditionalFileRepository, org.mockito.Mockito.times(times)).save(saved.capture());
        return saved.getAllValues();
    }

    @Test
    void reloadsTheBookAndSavesTheFileInATransactionOfItsOwn() throws IOException {
        BookEntity book = book(11, path, "h", BookFileType.EPUB);
        LibraryFile pdf = onDisk("A", "Title.pdf", BookFileType.PDF);

        List<LibraryFile> attached = attacher.attach(11, Kind.SAME_FOLDER, List.of(pdf));

        assertThat(attached).containsExactly(pdf);
        InOrder inOrder = inOrder(transactionManager, bookRepository, bookAdditionalFileRepository);
        ArgumentCaptor<TransactionDefinition> transaction = ArgumentCaptor.forClass(TransactionDefinition.class);
        inOrder.verify(transactionManager).getTransaction(transaction.capture());
        // Its own even inside the watcher's, so that it reads the book's files as they are once it holds the book
        assertThat(transaction.getValue().getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        inOrder.verify(bookRepository).findByIdForUpdate(11L);
        ArgumentCaptor<BookFileEntity> saved = ArgumentCaptor.forClass(BookFileEntity.class);
        inOrder.verify(bookAdditionalFileRepository).save(saved.capture());
        inOrder.verify(transactionManager).commit(any());
        assertThat(saved.getValue().getBook()).isSameAs(book);
        assertThat(saved.getValue().getFileName()).isEqualTo("Title.pdf");
        assertThat(saved.getValue().getCurrentHash()).isNotNull();
        // An ebook for an ebook makes no cover
        verifyNoInteractions(fileAsBookProcessor);
        verifyNoInteractions(filelessShells);
    }

    @Test
    void aBookDeletedOrGoneSinceGroupingGetsNothing() throws IOException {
        LibraryFile pdf = onDisk("A", "Title.pdf", BookFileType.PDF);
        BookEntity deleted = book(12, path, null, BookFileType.EPUB);
        deleted.setDeleted(true);
        when(bookRepository.findByIdForUpdate(13L)).thenReturn(Optional.empty());

        assertThat(attacher.attach(12, Kind.SAME_FOLDER, List.of(pdf))).isEmpty();
        assertThat(attacher.attach(13, Kind.SAME_FOLDER, List.of(pdf))).isEmpty();

        verify(bookAdditionalFileRepository, never()).save(any());
    }

    @Test
    void aFilelessBookThatGotAFileSinceGroupingGetsNoneOfTheBatch() throws IOException {
        BookEntity book = book(14, path, null, BookFileType.EPUB);
        LibraryFile epub = onDisk("W", "The Martian.epub", BookFileType.EPUB);
        LibraryFile pdf = onDisk("W", "The Martian.pdf", BookFileType.PDF);

        assertThat(attacher.attach(14, Kind.FILELESS, List.of(epub, pdf))).isEmpty();

        verify(bookAdditionalFileRepository, never()).save(any());
        verifyNoInteractions(fileAsBookProcessor);
        assertThat(book.getBookFiles()).hasSize(1);
        // The watcher's index must not offer it again
        verify(filelessShells).notFileless(14L);
    }

    @Test
    void aFilelessBookWithACoverKeepsIt() throws IOException {
        BookEntity shell = shell(15, "curated");
        LibraryFile epub = onDisk(WEIR, "The Martian.epub", BookFileType.EPUB);
        LibraryFile audio = onDisk(WEIR, "The Martian.m4b", BookFileType.AUDIOBOOK);

        assertThat(attacher.attach(15, Kind.FILELESS, List.of(epub, audio))).containsExactly(epub, audio);

        assertThat(saved(2)).extracting(BookFileEntity::getFileName).containsExactly("The Martian.epub", "The Martian.m4b");
        assertThat(shell.getLibraryPath()).isSameAs(path);
        assertThat(shell.getBookCoverHash()).isEqualTo("curated");
        verifyNoInteractions(fileAsBookProcessor);
        verify(filelessShells).notFileless(15L);
    }

    @Test
    void aFilelessBookWithoutACoverGetsOneAsANewBookWould() throws IOException {
        shell(16, null);
        LibraryFile audio = onDisk(WEIR, "The Martian.m4b", BookFileType.AUDIOBOOK);
        LibraryFile pdf = onDisk(WEIR, "The Martian.pdf", BookFileType.PDF);
        LibraryFile epub = onDisk(WEIR, "The Martian.epub", BookFileType.EPUB);

        attacher.attach(16, Kind.FILELESS, List.of(audio, pdf, epub));

        // The epub is the primary by format priority; the audiobook brings an audiobook cover; the pdf none
        InOrder inOrder = inOrder(fileAsBookProcessor);
        inOrder.verify(fileAsBookProcessor).writeCoverFromFile(any(), eq(epub));
        inOrder.verify(fileAsBookProcessor).writeCoverFromFile(any(), eq(audio));
        verify(fileAsBookProcessor, never()).writeCoverFromFile(any(), eq(pdf));
    }

    @Test
    void anEbookForAnAudiobookMakesAnEbookCoverFromThePrimaryItHadBefore() throws IOException {
        // Before, the decision saw the epub already added and taken as primary, and made no cover
        BookEntity book = book(17, path, null, BookFileType.AUDIOBOOK);
        LibraryFile epub = onDisk("A", "Title.epub", BookFileType.EPUB);
        LibraryFile pdf = onDisk("A", "Title.pdf", BookFileType.PDF);

        attacher.attach(17, Kind.SAME_FOLDER, List.of(epub, pdf));

        verify(fileAsBookProcessor).writeCoverFromFile(book, epub);
        verify(fileAsBookProcessor, never()).writeCoverFromFile(any(), eq(pdf));
        verifyNoInteractions(filelessShells);
    }

    @Test
    void aLockedEbookCoverOfAnAudiobookIsNotWrittenByAnAttachedEpub() throws IOException {
        // The owner locked the ebook cover of an audiobook that has none: a rescan or the watcher attaches an epub
        BookEntity book = book(21, path, null, BookFileType.AUDIOBOOK);
        book.getMetadata().setCoverLocked(true);
        LibraryFile epub = onDisk("A", "Title.epub", BookFileType.EPUB);

        assertThat(attacher.attach(21, Kind.SAME_FOLDER, List.of(epub))).containsExactly(epub);

        verify(fileAsBookProcessor, never()).writeCoverFromFile(any(), any());
    }

    @Test
    void aLockedAudiobookCoverOfAnEbookIsNotWrittenByAnAttachedAudiobook() throws IOException {
        BookEntity book = book(22, path, "h", BookFileType.EPUB);
        book.getMetadata().setAudiobookCoverLocked(true);
        LibraryFile audio = onDisk("A", "Title.m4b", BookFileType.AUDIOBOOK);

        assertThat(attacher.attach(22, Kind.SAME_FOLDER, List.of(audio))).containsExactly(audio);

        verify(fileAsBookProcessor, never()).writeCoverFromFile(any(), any());
    }

    @Test
    void aCoverOfTheAttachedFilesKindTheBookHasIsKept() throws IOException {
        // An audiobook with an ebook cover (from its owner or a metadata fetch), and an ebook with an audiobook cover
        BookEntity audiobook = book(23, path, "chosen", BookFileType.AUDIOBOOK);
        BookEntity ebook = book(24, path, "h", BookFileType.EPUB);
        ebook.setAudiobookCoverHash("chosen");
        LibraryFile epub = onDisk("A", "Title.epub", BookFileType.EPUB);
        LibraryFile audio = onDisk("B", "Title.m4b", BookFileType.AUDIOBOOK);

        assertThat(attacher.attach(23, Kind.SAME_FOLDER, List.of(epub))).containsExactly(epub);
        assertThat(attacher.attach(24, Kind.SAME_FOLDER, List.of(audio))).containsExactly(audio);

        verify(fileAsBookProcessor, never()).writeCoverFromFile(any(), any());
        assertThat(audiobook.getBookCoverHash()).isEqualTo("chosen");
    }

    @Test
    void aFilelessBookWithoutACoverGetsNoneOfAKindItsOwnerLocked() throws IOException {
        BookEntity shell = shell(25, null);
        shell.getMetadata().setCoverLocked(true);
        LibraryFile epub = onDisk(WEIR, "The Martian.epub", BookFileType.EPUB);
        LibraryFile audio = onDisk(WEIR, "The Martian.m4b", BookFileType.AUDIOBOOK);

        assertThat(attacher.attach(25, Kind.FILELESS, List.of(epub, audio))).containsExactly(epub, audio);

        verify(fileAsBookProcessor, never()).writeCoverFromFile(any(), eq(epub));
        verify(fileAsBookProcessor).writeCoverFromFile(shell, audio);
    }

    @Test
    void aFilelessBookRetitledSinceTheMatchGetsNoFile() throws IOException {
        // The watcher matched against the book as it read it up to ten minutes ago: "The Martian"
        BookEntity retitled = shell(26, null);
        retitled.getMetadata().setTitle("Project Hail Mary");
        retitled.setLibrary(library);
        LibraryFile epub = onDisk(WEIR, "The Martian.epub", BookFileType.EPUB);

        assertThat(attacher.attach(26, Kind.FILELESS, List.of(epub))).isEmpty();

        verify(bookAdditionalFileRepository, never()).save(any());
        verifyNoInteractions(fileAsBookProcessor);
        verify(filelessShells).readAgainForWatcher(1L);
        assertThat(retitled.getLibraryPath()).isNull();
    }

    @Test
    void aFilelessBookByAnotherAuthorSinceTheMatchGetsNoFile() throws IOException {
        BookEntity reattributed = shell(27, null);
        reattributed.getMetadata().getAuthors().set(0, AuthorEntity.builder().name("Zadie Smith").build());
        LibraryFile epub = onDisk(WEIR, "The Martian.epub", BookFileType.EPUB);

        assertThat(attacher.attach(27, Kind.FILELESS, List.of(epub))).isEmpty();

        verify(bookAdditionalFileRepository, never()).save(any());
    }

    @Test
    void anAuthorlessFilelessBookNoLongerPhysicalGetsNoFile() throws IOException {
        // D1: a fileless book without authors takes a file only while it is a physical book
        BookEntity guide = book(28, null, null, new BookFileType[0]);
        guide.getMetadata().setTitle("A Field Guide to Birds");
        guide.setIsPhysical(true);
        LibraryFile epub = onDisk("Nature", "A Field Guide to Birds.epub", BookFileType.EPUB);
        LibraryFile pdf = onDisk("Nature", "A Field Guide to Birds.pdf", BookFileType.PDF);

        assertThat(attacher.attach(28, Kind.FILELESS, List.of(epub))).containsExactly(epub);
        guide.getBookFiles().clear();
        guide.setIsPhysical(false);
        assertThat(attacher.attach(28, Kind.FILELESS, List.of(pdf))).isEmpty();

        assertThat(saved(1)).extracting(BookFileEntity::getFileName).containsExactly("A Field Guide to Birds.epub");
    }

    @Test
    void theFilesThatGoWithTheOneThatStillMatchesAreAttachedWithIt() throws IOException {
        // "Survival.pdf" alone does not say which Smith; it goes with the file that does, as the rescan decided
        BookEntity survival = book(29, null, null, new BookFileType[0]);
        survival.getMetadata().setTitle("Survival");
        survival.getMetadata().setAuthors(new ArrayList<>(List.of(AuthorEntity.builder().name("Jane Smith").build())));
        LibraryFile named = onDisk("S/Smith, J", "Survival - Jane Smith.epub", BookFileType.EPUB);
        LibraryFile bare = onDisk("S/Smith, J", "Survival.pdf", BookFileType.PDF);

        assertThat(attacher.attach(29, Kind.FILELESS, List.of(bare))).isEmpty();
        assertThat(attacher.attach(29, Kind.FILELESS, List.of(named, bare))).containsExactly(named, bare);
    }

    @Test
    void aSecondDownloadStillMatchesTheFilelessBookOfItsTitle() throws IOException {
        // "The Martian (1).epub" reads as "The Martian" as well as its first volume; before, it no longer matched here
        shell(31, "curated");
        LibraryFile copy = onDisk(WEIR, "The Martian (1).epub", BookFileType.EPUB);

        assertThat(attacher.attach(31, Kind.FILELESS, List.of(copy))).containsExactly(copy);

        assertThat(saved(1)).extracting(BookFileEntity::getFileName).containsExactly("The Martian (1).epub");
        verify(filelessShells).notFileless(31L);
    }

    @Test
    void theAlikeBookChosenStillMatches() throws IOException {
        // The matcher chose book#32 among alike fileless books (another "The Martian" by Andy Weir); the attacher
        // checks the file against book#32 alone, so the books it was chosen from do not make it ambiguous here
        BookEntity chosen = shell(32, "curated");
        LibraryFile epub = onDisk(WEIR, "The Martian.epub", BookFileType.EPUB);

        assertThat(attacher.attach(32, Kind.FILELESS, List.of(epub))).containsExactly(epub);

        assertThat(saved(1).getFirst().getBook()).isSameAs(chosen);
        verify(filelessShells).notFileless(32L);
        verify(filelessShells, never()).readAgainForWatcher(anyLong());
    }

    @Test
    void aFilelessBookIsMatchedWithItsSeriesAsTheIndexReadsIt() throws IOException {
        // Number 2 of a series of another name: "The Testaments (1).epub" is the copy of "The Testaments"
        BookEntity testaments = book(33, null, "curated", new BookFileType[0]);
        testaments.getMetadata().setTitle("The Testaments");
        testaments.getMetadata().setSeriesName("The Handmaid's Tale");
        testaments.getMetadata().setSeriesNumber(2f);
        testaments.getMetadata().setAuthors(new ArrayList<>(List.of(AuthorEntity.builder().name("Margaret Atwood").build())));
        LibraryFile copy = onDisk("A/Atwood, Margaret", "The Testaments (1).epub", BookFileType.EPUB);

        ShellCandidate candidate = ExistingBookAttacher.shellCandidate(testaments);
        assertThat(candidate.seriesName()).isEqualTo("The Handmaid's Tale");
        assertThat(candidate.seriesPlace()).isEqualTo(2f);
        assertThat(attacher.attach(33, Kind.FILELESS, List.of(copy))).containsExactly(copy);

        assertThat(saved(1)).extracting(BookFileEntity::getFileName).containsExactly("The Testaments (1).epub");
    }

    @Test
    void aFilelessBookWhoseSeriesWasRenamedAfterItsTitleSinceTheMatchGetsNoFile() throws IOException {
        // Matched while its series had another name; in a series "Saga", "Saga (1).epub" may be volume 1
        BookEntity saga = book(34, null, "curated", new BookFileType[0]);
        saga.getMetadata().setTitle("Saga");
        saga.getMetadata().setSeriesName("Saga");
        saga.getMetadata().setSeriesNumber(2f);
        saga.getMetadata().setAuthors(new ArrayList<>(List.of(AuthorEntity.builder().name("Brian K. Vaughan").build())));
        LibraryFile copy = onDisk("V/Vaughan, Brian K.", "Saga (1).epub", BookFileType.EPUB);

        assertThat(attacher.attach(34, Kind.FILELESS, List.of(copy))).isEmpty();

        verify(bookAdditionalFileRepository, never()).save(any());
        verify(filelessShells).readAgainForWatcher(1L);
    }

    @Test
    void aFolderAudiobookIsStoredWithItsFolderHashAndSize() throws IOException {
        shell(30, "curated");
        Path dir = Files.createDirectories(root.resolve(WEIR).resolve("The Martian"));
        Files.write(dir.resolve("01.mp3"), new byte[4096]);
        Files.write(dir.resolve("02.mp3"), new byte[4096]);
        LibraryFile folder = LibraryFile.builder().libraryEntity(library).libraryPathEntity(path)
                .fileSubPath(WEIR).fileName("The Martian").bookFileType(BookFileType.AUDIOBOOK).folderBased(true).build();

        assertThat(attacher.attach(30, Kind.FILELESS, List.of(folder))).containsExactly(folder);

        BookFileEntity stored = saved(1).getFirst();
        assertThat(stored.isFolderBased()).isTrue();
        assertThat(stored.getCurrentHash()).isEqualTo(FileFingerprint.generateFolderHash(dir));
        assertThat(stored.getInitialHash()).isEqualTo(stored.getCurrentHash());
        assertThat(stored.getFileSizeKb()).isEqualTo(8L);
    }

    @Test
    void aFileAlreadyStoredOrInAnotherLibraryPathIsSkipped() throws IOException {
        BookEntity book = book(18, path, null, BookFileType.EPUB);
        LibraryFile stored = onDisk("A", "Stored.pdf", BookFileType.PDF);
        LibraryFile elsewhere = onDisk("A", "Title.mobi", BookFileType.MOBI);
        elsewhere.setLibraryPathEntity(LibraryPathEntity.builder().id(99L).path(root.toString()).build());
        when(bookAdditionalFileRepository.findExactlyAt(10L, "A", "Stored.pdf"))
                .thenReturn(Optional.of(BookFileEntity.builder().id(5L).build()));
        when(bookAdditionalFileRepository.findExactlyAt(eq(99L), anyString(), anyString()))
                .thenReturn(Optional.empty());

        assertThat(attacher.attach(18, Kind.SAME_FOLDER, List.of(stored, elsewhere))).isEmpty();

        verify(bookAdditionalFileRepository, never()).save(any());
        assertThat(book.getLibraryPath()).isSameAs(path);
    }

    @Test
    void aFileThatCannotBeReadRollsTheBooksTransactionBack() {
        book(19, path, null, BookFileType.EPUB);
        LibraryFile missing = LibraryFile.builder().libraryEntity(library).libraryPathEntity(path)
                .fileSubPath("A").fileName("missing.pdf").bookFileType(BookFileType.PDF).build();
        when(bookAdditionalFileRepository.findExactlyAt(anyLong(), anyString(), anyString()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> attacher.attach(19, Kind.SAME_FOLDER, List.of(missing))).isInstanceOf(RuntimeException.class);

        verify(transactionManager).rollback(any());
        verify(transactionManager, never()).commit(any());
        verify(bookAdditionalFileRepository, never()).save(any());
    }

    @Test
    void logLinesNameTheBookByIdAndTitle() {
        BookEntity book = BookEntity.builder().id(20L).build();
        assertThat(ExistingBookAttacher.label(book)).isEqualTo("book#20");
        book.setMetadata(BookMetadataEntity.builder().bookId(20L).title("Assignment").build());
        assertThat(ExistingBookAttacher.label(book)).isEqualTo("book#20 \"Assignment\"");
    }
}
