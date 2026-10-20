package org.booklore.service.library;

import org.booklore.model.entity.AuthorEntity;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.entity.LibraryPathEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.repository.BookFileRepository;
import org.booklore.repository.BookRepository;
import org.booklore.repository.LibraryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Organize on real files: conflicting names are numbered after the whole name of a folder audiobook and before a
 * file's extension, and a book already in place, under whatever spelling the share stores it, is left alone.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LibraryOrganizeServiceTest {

    @Mock private LibraryRepository libraryRepository;
    @Mock private BookRepository bookRepository;
    @Mock private BookFileRepository bookFileRepository;

    @TempDir
    Path libraryRoot;

    private LibraryOrganizeService service;
    private LibraryEntity library;
    private LibraryPathEntity libraryPath;
    private final List<BookEntity> books = new ArrayList<>();
    private long nextId = 1;

    @BeforeEach
    void setUp() {
        library = new LibraryEntity();
        library.setId(1L);
        library.setName("Books");
        library.setFileNamingPattern("{title}");
        libraryPath = new LibraryPathEntity();
        libraryPath.setId(10L);
        libraryPath.setPath(libraryRoot.toString());
        libraryPath.setLibrary(library);
        library.setLibraryPaths(List.of(libraryPath));

        when(libraryRepository.findById(1L)).thenReturn(Optional.of(library));
        when(bookRepository.findAllWithMetadataByLibraryId(1L)).thenReturn(books);
        service = new LibraryOrganizeService(libraryRepository, bookRepository, bookFileRepository);
    }

    private BookFileEntity book(String title, String subPath, String fileName, boolean folderBased) {
        BookEntity book = new BookEntity();
        book.setId(nextId++);
        book.setLibrary(library);
        book.setLibraryPath(libraryPath);
        BookMetadataEntity metadata = new BookMetadataEntity();
        metadata.setTitle(title);
        book.setMetadata(metadata);
        book.setBookFiles(new ArrayList<>());

        BookFileEntity file = new BookFileEntity();
        file.setId(nextId++);
        file.setBook(book);
        file.setFileName(fileName);
        file.setFileSubPath(subPath);
        file.setBookFormat(true);
        file.setFolderBased(folderBased);
        file.setBookType(folderBased ? BookFileType.AUDIOBOOK : BookFileType.EPUB);
        // Hibernate's association management may already have added it in setBook
        if (!book.getBookFiles().contains(file)) {
            book.getBookFiles().add(file);
        }
        books.add(book);
        return file;
    }

    private BookFileEntity addFile(BookEntity book, String subPath, String fileName) {
        BookFileEntity file = new BookFileEntity();
        file.setId(nextId++);
        file.setBook(book);
        file.setFileName(fileName);
        file.setFileSubPath(subPath);
        file.setBookFormat(true);
        file.setBookType(BookFileType.PDF);
        if (!book.getBookFiles().contains(file)) {
            book.getBookFiles().add(file);
        }
        return file;
    }

    private static void assumeUnicodeFileNames() {
        assumeTrue(Charset.forName(System.getProperty("sun.jnu.encoding", "UTF-8")).newEncoder().canEncode("한강ガ"),
                "File names are not encoded as UTF-8 here: run under a UTF-8 locale");
    }

    private Path write(String relativePath, String content) throws IOException {
        Path file = libraryRoot.resolve(relativePath);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, content);
    }

    private List<String> namesIn(Path dir) throws IOException {
        try (Stream<Path> paths = Files.list(dir)) {
            return paths.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    void conflictingFolderAudiobook_isNumberedAfterTheWholeFolderName() throws IOException {
        String name = "01. The Assassin's Blade - Sarah J. Maas (2015)";
        write(name + "/part1.m4b", "first");
        write("incoming/other folder/part1.m4b", "second");
        book(name, "", name, true);
        BookFileEntity second = book(name, "incoming", "other folder", true);

        service.organizeLibrary(1L);

        assertThat(namesIn(libraryRoot)).containsExactly(name, name + " (1)");
        assertThat(Files.readString(libraryRoot.resolve(name + "/part1.m4b"))).isEqualTo("first");
        assertThat(Files.readString(libraryRoot.resolve(name + " (1)/part1.m4b"))).isEqualTo("second");
        assertThat(second.getFileName()).isEqualTo(name + " (1)");
        assertThat(second.getFileSubPath()).isEmpty();
    }

    @Test
    void conflictingFile_isNumberedBeforeItsExtension() throws IOException {
        write("Mr. Mercedes - Stephen King.m4b", "first");
        write("incoming/other.m4b", "second");
        book("Mr. Mercedes - Stephen King", "", "Mr. Mercedes - Stephen King.m4b", false);
        BookFileEntity second = book("Mr. Mercedes - Stephen King", "incoming", "other.m4b", false);

        service.organizeLibrary(1L);

        assertThat(namesIn(libraryRoot)).containsExactly("Mr. Mercedes - Stephen King (1).m4b", "Mr. Mercedes - Stephen King.m4b");
        assertThat(Files.readString(libraryRoot.resolve("Mr. Mercedes - Stephen King (1).m4b"))).isEqualTo("second");
        assertThat(second.getFileName()).isEqualTo("Mr. Mercedes - Stephen King (1).m4b");
    }

    @Test
    void numberedBookFromAnEarlierRun_keepsItsNumber() throws IOException {
        write("Title.epub", "first");
        write("Title (1).epub", "second");
        book("Title", "", "Title.epub", false);
        book("Title", "", "Title (1).epub", false);

        service.organizeLibrary(1L);

        assertThat(namesIn(libraryRoot)).containsExactly("Title (1).epub", "Title.epub");
        assertThat(Files.readString(libraryRoot.resolve("Title (1).epub"))).isEqualTo("second");
        verify(bookFileRepository, never()).save(any());
    }

    @Test
    void caseOnlyRename_movesTheBookWithoutANumber() throws IOException {
        write("the hobbit.epub", "hobbit");
        BookFileEntity file = book("The Hobbit", "", "the hobbit.epub", false);

        service.organizeLibrary(1L);

        assertThat(namesIn(libraryRoot)).containsExactly("The Hobbit.epub");
        assertThat(Files.readString(libraryRoot.resolve("The Hobbit.epub"))).isEqualTo("hobbit");
        assertThat(file.getFileName()).isEqualTo("The Hobbit.epub");
        verify(bookFileRepository).save(file);
    }

    @Test
    void targetThatIsTheSourceUnderAnotherName_isNoConflict() throws IOException {
        // A second link to the same file stands in for a share that finds the source under the target's spelling
        Path source = write("incoming/book.epub", "the book");
        Files.createLink(libraryRoot.resolve("Title.epub"), source);
        book("Title", "incoming", "book.epub", false);

        service.organizeLibrary(1L);

        assertThat(namesIn(libraryRoot)).containsExactly("Title.epub", "incoming");
        assertThat(namesIn(libraryRoot.resolve("incoming"))).containsExactly("book.epub");
        verify(bookFileRepository, never()).save(any());
    }

    @Test
    void bookStoredDecomposed_isAlreadyInPlace() throws IOException {
        assumeUnicodeFileNames();
        String title = "ガイドがある";
        String decomposed = Normalizer.normalize(title, Normalizer.Form.NFD) + ".epub";
        write(decomposed, "the book");
        book(title, "", decomposed, false);

        service.organizeLibrary(1L);

        assertThat(namesIn(libraryRoot)).containsExactly(decomposed);
        verify(bookFileRepository, never()).save(any());
    }

    /** A book by 한강 in a folder whose name is stored decomposed: one file to rename, one already named right. */
    private BookFileEntity[] bookInADecomposedFolder() throws IOException {
        library.setFileNamingPattern("{authors}/{title}");
        String decomposedAuthor = Normalizer.normalize("한강", Normalizer.Form.NFD);
        write(decomposedAuthor + "/old name.epub", "the epub");
        write(decomposedAuthor + "/소년이 온다.pdf", "the pdf");
        BookFileEntity epub = book("소년이 온다", decomposedAuthor, "old name.epub", false);
        BookEntity book = epub.getBook();
        book.getMetadata().setAuthors(List.of(AuthorEntity.builder().name("한강").build()));
        return new BookFileEntity[]{epub, addFile(book, decomposedAuthor, "소년이 온다.pdf")};
    }

    @Test
    void bookInADecomposedFolderOnASensitiveFileSystem_movesTogether() throws IOException {
        assumeUnicodeFileNames();
        BookFileEntity[] files = bookInADecomposedFolder();

        service.organizeLibrary(1L);

        // The composed folder is another folder here: the PDF goes into it with the EPUB, not stay behind
        assertThat(namesIn(libraryRoot)).containsExactly("한강");
        assertThat(namesIn(libraryRoot.resolve("한강"))).containsExactly("소년이 온다.epub", "소년이 온다.pdf");
        assertThat(files[1].getFileSubPath()).isEqualTo("한강");
        verify(bookFileRepository).save(files[0]);
        verify(bookFileRepository).save(files[1]);
    }

    @Test
    void fileInADecomposedFolderTheShareFindsUnderTheComposedName_isAlreadyInPlace() throws IOException {
        assumeUnicodeFileNames();
        BookFileEntity[] files = bookInADecomposedFolder();
        String decomposedAuthor = files[1].getFileSubPath();
        // A share that finds the decomposed folder under the composed name, as a link does here
        Files.createSymbolicLink(libraryRoot.resolve("한강"), libraryRoot.resolve(decomposedAuthor));

        service.organizeLibrary(1L);

        assertThat(namesIn(libraryRoot.resolve(decomposedAuthor))).containsExactly("소년이 온다.epub", "소년이 온다.pdf");
        assertThat(files[1].getFileSubPath()).isEqualTo(decomposedAuthor);
        verify(bookFileRepository).save(files[0]);
        verify(bookFileRepository, never()).save(files[1]);
    }
}
