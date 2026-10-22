package org.booklore.service.library;

import org.booklore.model.dto.settings.LibraryFile;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.entity.LibraryPathEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.repository.BookAdditionalFileRepository;
import org.booklore.repository.BookRepository;
import org.booklore.service.library.matching.ExistingBookMatcher.Kind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

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
        when(bookRepository.findById(id)).thenReturn(Optional.of(book));
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
        inOrder.verify(transactionManager).getTransaction(any());
        inOrder.verify(bookRepository).findById(11L);
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
        when(bookRepository.findById(13L)).thenReturn(Optional.empty());

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
        BookEntity shell = book(15, null, "curated", new BookFileType[0]);
        LibraryFile epub = onDisk("W", "The Martian.epub", BookFileType.EPUB);
        LibraryFile audio = onDisk("W", "The Martian.m4b", BookFileType.AUDIOBOOK);

        assertThat(attacher.attach(15, Kind.FILELESS, List.of(epub, audio))).containsExactly(epub, audio);

        assertThat(saved(2)).extracting(BookFileEntity::getFileName).containsExactly("The Martian.epub", "The Martian.m4b");
        assertThat(shell.getLibraryPath()).isSameAs(path);
        assertThat(shell.getBookCoverHash()).isEqualTo("curated");
        verifyNoInteractions(fileAsBookProcessor);
        verify(filelessShells).notFileless(15L);
    }

    @Test
    void aFilelessBookWithoutACoverGetsOneAsANewBookWould() throws IOException {
        book(16, null, null, new BookFileType[0]);
        LibraryFile audio = onDisk("W", "The Martian.m4b", BookFileType.AUDIOBOOK);
        LibraryFile pdf = onDisk("W", "The Martian.pdf", BookFileType.PDF);
        LibraryFile epub = onDisk("W", "The Martian.epub", BookFileType.EPUB);

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
    void aFileAlreadyStoredOrInAnotherLibraryPathIsSkipped() throws IOException {
        BookEntity book = book(18, path, null, BookFileType.EPUB);
        LibraryFile stored = onDisk("A", "Stored.pdf", BookFileType.PDF);
        LibraryFile elsewhere = onDisk("A", "Title.mobi", BookFileType.MOBI);
        elsewhere.setLibraryPathEntity(LibraryPathEntity.builder().id(99L).path(root.toString()).build());
        when(bookAdditionalFileRepository.findByLibraryPath_IdAndFileSubPathAndFileName(10L, "A", "Stored.pdf"))
                .thenReturn(Optional.of(BookFileEntity.builder().id(5L).build()));
        when(bookAdditionalFileRepository.findByLibraryPath_IdAndFileSubPathAndFileName(eq(99L), anyString(), anyString()))
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
        when(bookAdditionalFileRepository.findByLibraryPath_IdAndFileSubPathAndFileName(anyLong(), anyString(), anyString()))
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
