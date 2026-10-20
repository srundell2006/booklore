package org.booklore.service.file;

import jakarta.persistence.EntityManager;
import org.booklore.config.AppProperties;
import org.booklore.mapper.BookMapper;
import org.booklore.mapper.LibraryMapper;
import org.booklore.model.dto.FileMoveResult;
import org.booklore.model.dto.request.FileMoveRequest;
import org.booklore.model.entity.AuthorEntity;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.entity.LibraryPathEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.repository.BookAdditionalFileRepository;
import org.booklore.repository.BookRepository;
import org.booklore.repository.LibraryRepository;
import org.booklore.service.NotificationService;
import org.booklore.service.appsettings.AppSettingService;
import org.booklore.service.metadata.sidecar.SidecarMetadataWriter;
import org.booklore.service.monitoring.MonitoringRegistrationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Moves on real files: a move never replaces another book's file or folder, and a book already in place, under
 * whatever spelling the share stores it, stays where it is.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FileMoveServiceConflictTest {

    @Mock private AppProperties appProperties;
    @Mock private BookRepository bookRepository;
    @Mock private BookAdditionalFileRepository bookFileRepository;
    @Mock private LibraryRepository libraryRepository;
    @Mock private MonitoringRegistrationService monitoringRegistrationService;
    @Mock private AppSettingService appSettingService;
    @Mock private LibraryMapper libraryMapper;
    @Mock private BookMapper bookMapper;
    @Mock private NotificationService notificationService;
    @Mock private EntityManager entityManager;
    @Mock private TransactionTemplate transactionTemplate;
    @Mock private SidecarMetadataWriter sidecarMetadataWriter;

    @TempDir
    Path tempDir;

    private FileMoveService service;
    private Path libraryRoot;
    private LibraryEntity library;
    private LibraryPathEntity libraryPath;
    private long nextId = 1;

    @BeforeEach
    void setUp() throws IOException {
        when(appProperties.isLocalStorage()).thenReturn(true);
        when(monitoringRegistrationService.getPathsForLibraries(anySet())).thenReturn(Collections.emptySet());
        doAnswer(invocation -> {
            Consumer<TransactionStatus> action = invocation.getArgument(0);
            action.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        service = service(new FileMoveHelper(monitoringRegistrationService, appSettingService));
        libraryRoot = Files.createDirectories(tempDir.resolve("library"));
        library = library(1L, libraryRoot);
        libraryPath = library.getLibraryPaths().getFirst();
    }

    private FileMoveService service(FileMoveHelper fileMoveHelper) {
        return new FileMoveService(appProperties, bookRepository, bookFileRepository, libraryRepository, fileMoveHelper,
                monitoringRegistrationService, libraryMapper, bookMapper, notificationService, entityManager,
                transactionTemplate, sidecarMetadataWriter) {
            @Override
            protected void sleep(long millis) {
                // No-op
            }
        };
    }

    private LibraryEntity library(Long id, Path root) {
        LibraryEntity entity = new LibraryEntity();
        entity.setId(id);
        entity.setName("Library " + id);
        entity.setFileNamingPattern("{title}");
        LibraryPathEntity path = new LibraryPathEntity();
        path.setId(id * 10);
        path.setPath(root.toString());
        path.setLibrary(entity);
        entity.setLibraryPaths(List.of(path));
        return entity;
    }

    private BookEntity book(String title, String subPath, String fileName, boolean folderBased) {
        BookEntity book = new BookEntity();
        book.setId(nextId++);
        book.setLibrary(library);
        book.setLibraryPath(libraryPath);
        BookMetadataEntity metadata = new BookMetadataEntity();
        metadata.setTitle(title);
        book.setMetadata(metadata);
        book.setBookFiles(new ArrayList<>());
        addFile(book, subPath, fileName, true, folderBased);
        when(bookRepository.findById(book.getId())).thenReturn(Optional.of(book));
        when(bookRepository.findByIdWithBookFiles(book.getId())).thenReturn(Optional.of(book));
        return book;
    }

    private BookFileEntity addFile(BookEntity book, String subPath, String fileName, boolean isBook, boolean folderBased) {
        BookFileEntity file = new BookFileEntity();
        file.setId(nextId++);
        file.setBook(book);
        file.setFileName(fileName);
        file.setFileSubPath(subPath);
        file.setBookFormat(isBook);
        file.setFolderBased(folderBased);
        if (isBook) {
            file.setBookType(folderBased ? BookFileType.AUDIOBOOK : BookFileType.EPUB);
        }
        // Hibernate's association management may already have added it in setBook
        if (!book.getBookFiles().contains(file)) {
            book.getBookFiles().add(file);
        }
        return file;
    }

    private static Path write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        return Files.writeString(file, content);
    }

    /** Non-ASCII file names need the JVM to encode them as UTF-8, as it does under a UTF-8 locale (LANG=C.UTF-8). */
    private static void assumeUnicodeFileNames() {
        assumeTrue(Charset.forName(System.getProperty("sun.jnu.encoding", "UTF-8")).newEncoder().canEncode("한강ガ"),
                "File names are not encoded as UTF-8 here: run under a UTF-8 locale");
    }

    private List<Path> leftoverTempFiles() throws IOException {
        try (Stream<Path> paths = Files.walk(tempDir)) {
            return paths.filter(path -> path.getFileName().toString().endsWith(".tmp_move")).toList();
        }
    }

    @Test
    void twoBooksResolvingToTheSameName_secondStaysWhereItIs() throws IOException {
        Path first = write(libraryRoot.resolve("incoming/first.epub"), "first book");
        Path second = write(libraryRoot.resolve("incoming/second.epub"), "second book");
        BookEntity firstBook = book("Same Title", "incoming", "first.epub", false);
        BookEntity secondBook = book("Same Title", "incoming", "second.epub", false);
        Path target = libraryRoot.resolve("Same Title.epub");

        assertThat(service.moveSingleFile(firstBook).isMoved()).isTrue();
        FileMoveResult result = service.moveSingleFile(secondBook);

        assertThat(result.isMoved()).isFalse();
        assertThat(Files.readString(target)).isEqualTo("first book");
        assertThat(first).doesNotExist();
        assertThat(Files.readString(second)).isEqualTo("second book");
        assertThat(leftoverTempFiles()).isEmpty();
        verify(bookFileRepository, never()).updateFileNameAndSubPath(eq(secondBook.getBookFiles().getFirst().getId()), any(), any());
    }

    @Test
    void twoBooksMovedToTheSameNameInABulkMove_secondStaysWhereItIs() throws IOException {
        Path targetRoot = Files.createDirectories(tempDir.resolve("target"));
        LibraryEntity targetLibrary = library(2L, targetRoot);
        when(libraryRepository.findById(2L)).thenReturn(Optional.of(targetLibrary));
        Path first = write(libraryRoot.resolve("first.epub"), "first book");
        Path second = write(libraryRoot.resolve("second.epub"), "second book");
        BookEntity firstBook = book("Same Title", "", "first.epub", false);
        BookEntity secondBook = book("Same Title", "", "second.epub", false);

        FileMoveRequest request = new FileMoveRequest();
        request.setMoves(Stream.of(firstBook, secondBook).map(book -> {
            FileMoveRequest.Move move = new FileMoveRequest.Move();
            move.setBookId(book.getId());
            move.setTargetLibraryId(2L);
            move.setTargetLibraryPathId(20L);
            return move;
        }).toList());
        service.bulkMoveFiles(request);

        assertThat(Files.readString(targetRoot.resolve("Same Title.epub"))).isEqualTo("first book");
        assertThat(first).doesNotExist();
        assertThat(Files.readString(second)).isEqualTo("second book");
        assertThat(leftoverTempFiles()).isEmpty();
        verify(bookRepository).updateLibrary(eq(firstBook.getId()), eq(2L), any());
        verify(bookRepository, never()).updateLibrary(eq(secondBook.getId()), anyLong(), any());
    }

    @Test
    void twoFolderAudiobooksResolvingToTheSameFolder_secondStaysWhereItIs() throws IOException {
        write(libraryRoot.resolve("incoming/01. First - Author (2001)/part1.mp3"), "first audiobook");
        write(libraryRoot.resolve("incoming/01. Second - Author (2002)/part1.mp3"), "second audiobook");
        BookEntity firstBook = book("Same Title", "incoming", "01. First - Author (2001)", true);
        BookEntity secondBook = book("Same Title", "incoming", "01. Second - Author (2002)", true);
        Path target = libraryRoot.resolve("Same Title");

        assertThat(service.moveSingleFile(firstBook).isMoved()).isTrue();
        assertThat(service.moveSingleFile(secondBook).isMoved()).isFalse();

        assertThat(Files.readString(target.resolve("part1.mp3"))).isEqualTo("first audiobook");
        try (Stream<Path> files = Files.list(target)) {
            assertThat(files).hasSize(1);
        }
        assertThat(Files.readString(libraryRoot.resolve("incoming/01. Second - Author (2002)/part1.mp3"))).isEqualTo("second audiobook");
        assertThat(leftoverTempFiles()).isEmpty();
    }

    @Test
    void caseOnlyRename_stillMovesTheBook() throws IOException {
        write(libraryRoot.resolve("the hobbit.epub"), "hobbit");
        BookEntity book = book("The Hobbit", "", "the hobbit.epub", false);

        FileMoveResult result = service.moveSingleFile(book);

        assertThat(result.isMoved()).isTrue();
        try (Stream<Path> files = Files.list(libraryRoot)) {
            assertThat(files.map(path -> path.getFileName().toString())).containsExactly("The Hobbit.epub");
        }
        assertThat(Files.readString(libraryRoot.resolve("The Hobbit.epub"))).isEqualTo("hobbit");
        verify(bookFileRepository).updateFileNameAndSubPath(book.getBookFiles().getFirst().getId(), "The Hobbit.epub", "");
    }

    @Test
    void targetAppearingBetweenPlanAndCommit_isNotReplacedAndTheSourceIsRestored() throws IOException {
        Path source = write(libraryRoot.resolve("incoming/book.epub"), "the book");
        BookEntity book = book("Title", "incoming", "book.epub", false);
        Path target = libraryRoot.resolve("Title.epub");
        FileMoveService racingService = service(new FileMoveHelper(monitoringRegistrationService, appSettingService) {
            @Override
            public Path moveFileWithBackup(Path source) throws IOException {
                Path tempPath = super.moveFileWithBackup(source);
                write(target, "another book");
                return tempPath;
            }
        });

        FileMoveResult result = racingService.moveSingleFile(book);

        assertThat(result.isMoved()).isFalse();
        assertThat(Files.readString(target)).isEqualTo("another book");
        assertThat(Files.readString(source)).isEqualTo("the book");
        assertThat(leftoverTempFiles()).isEmpty();
        verify(bookFileRepository, never()).updateFileNameAndSubPath(anyLong(), any(), any());
    }

    @Test
    void bookStoredDecomposed_isAlreadyInPlace() throws IOException {
        assumeUnicodeFileNames();
        String title = "ガイドがある";
        String decomposed = Normalizer.normalize(title, Normalizer.Form.NFD) + ".epub";
        Path source = write(libraryRoot.resolve(decomposed), "the book");
        BookEntity book = book(title, "", decomposed, false);

        FileMoveResult result = service.moveSingleFile(book);

        assertThat(result.isMoved()).isFalse();
        assertThat(Files.readString(source)).isEqualTo("the book");
        assertThat(leftoverTempFiles()).isEmpty();
        verify(bookFileRepository, never()).updateFileNameAndSubPath(anyLong(), any(), any());
    }

    @Test
    void fileLeftInPlaceUnderADecomposedName_keepsThatNameInTheDatabase() throws IOException {
        assumeUnicodeFileNames();
        library.setFileNamingPattern("{authors}/{title}");
        String decomposedAuthor = Normalizer.normalize("한강", Normalizer.Form.NFD);
        write(libraryRoot.resolve(decomposedAuthor).resolve("old name.epub"), "the book");
        Path notes = write(libraryRoot.resolve(decomposedAuthor).resolve("notes.txt"), "notes");
        BookEntity book = book("소년이 온다", decomposedAuthor, "old name.epub", false);
        book.getMetadata().setAuthors(List.of(AuthorEntity.builder().name("한강").build()));
        BookFileEntity notesFile = addFile(book, decomposedAuthor, "notes.txt", false, false);

        assertThat(service.moveSingleFile(book).isMoved()).isTrue();

        assertThat(Files.readString(notes)).isEqualTo("notes");
        verify(bookFileRepository).updateFileNameAndSubPath(book.getBookFiles().getFirst().getId(), "소년이 온다.epub", "한강");
        verify(bookFileRepository).updateFileNameAndSubPath(notesFile.getId(), "notes.txt", decomposedAuthor);
    }
}
