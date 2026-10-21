package org.booklore.service.book;

import org.booklore.model.dto.ComicMetadata;
import org.booklore.model.dto.settings.LibraryFile;
import org.booklore.model.entity.*;
import org.booklore.model.enums.BookFileType;
import org.booklore.model.enums.ComicCreatorRole;
import org.booklore.repository.*;
import org.booklore.service.file.FileFingerprint;
import org.booklore.service.fileprocessor.prepare.FileFacts;
import org.booklore.util.FileUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BookCreatorServiceTest {

    @Mock private AuthorRepository authorRepository;
    @Mock private CategoryRepository categoryRepository;
    @Mock private MoodRepository moodRepository;
    @Mock private TagRepository tagRepository;
    @Mock private BookRepository bookRepository;
    @Mock private BookMetadataRepository bookMetadataRepository;
    @Mock private ComicMetadataRepository comicMetadataRepository;
    @Mock private ComicCharacterRepository comicCharacterRepository;
    @Mock private ComicTeamRepository comicTeamRepository;
    @Mock private ComicLocationRepository comicLocationRepository;
    @Mock private ComicCreatorRepository comicCreatorRepository;

    @InjectMocks
    private BookCreatorService bookCreatorService;

    private BookEntity bookEntity;

    @BeforeEach
    void setUp() {
        BookMetadataEntity metadata = new BookMetadataEntity();
        bookEntity = BookEntity.builder().metadata(metadata).build();
    }

    @Test
    void addAuthorsToBook_nullAuthors_doesNothing() {
        bookCreatorService.addAuthorsToBook(null, bookEntity);

        assertNull(bookEntity.getMetadata().getAuthors());
        verifyNoInteractions(authorRepository);
    }

    @Test
    void addAuthorsToBook_emptyAuthors_doesNothing() {
        bookCreatorService.addAuthorsToBook(Set.of(), bookEntity);

        assertNull(bookEntity.getMetadata().getAuthors());
        verifyNoInteractions(authorRepository);
    }

    @Test
    void addAuthorsToBook_validAuthors_addsToBook() {
        AuthorEntity author = AuthorEntity.builder().name("Test Author").build();
        when(authorRepository.findByName("Test Author")).thenReturn(Optional.of(author));

        bookCreatorService.addAuthorsToBook(Set.of("Test Author"), bookEntity);

        assertNotNull(bookEntity.getMetadata().getAuthors());
        assertEquals(1, bookEntity.getMetadata().getAuthors().size());
    }

    @Test
    void addAuthorsToBook_nullExistingAuthorsOnEntity_initializesSet() {
        assertNull(bookEntity.getMetadata().getAuthors());

        AuthorEntity author = AuthorEntity.builder().name("Author").build();
        when(authorRepository.findByName("Author")).thenReturn(Optional.of(author));

        bookCreatorService.addAuthorsToBook(Set.of("Author"), bookEntity);

        assertNotNull(bookEntity.getMetadata().getAuthors());
        assertTrue(bookEntity.getMetadata().getAuthors().contains(author));
    }

    @Test
    void addCategoriesToBook_nullCategories_doesNothing() {
        bookCreatorService.addCategoriesToBook(null, bookEntity);

        assertNull(bookEntity.getMetadata().getCategories());
        verifyNoInteractions(categoryRepository);
    }

    @Test
    void addCategoriesToBook_emptyCategories_doesNothing() {
        bookCreatorService.addCategoriesToBook(Set.of(), bookEntity);

        assertNull(bookEntity.getMetadata().getCategories());
        verifyNoInteractions(categoryRepository);
    }

    @Test
    void addCategoriesToBook_validCategories_addsToBook() {
        CategoryEntity category = CategoryEntity.builder().name("Fiction").build();
        when(categoryRepository.findByName("Fiction")).thenReturn(Optional.of(category));

        bookCreatorService.addCategoriesToBook(Set.of("Fiction"), bookEntity);

        assertNotNull(bookEntity.getMetadata().getCategories());
        assertEquals(1, bookEntity.getMetadata().getCategories().size());
    }

    @Test
    void addMoodsToBook_nullMoods_doesNothing() {
        bookCreatorService.addMoodsToBook(null, bookEntity);

        assertNull(bookEntity.getMetadata().getMoods());
        verifyNoInteractions(moodRepository);
    }

    @Test
    void addMoodsToBook_emptyMoods_doesNothing() {
        bookCreatorService.addMoodsToBook(Set.of(), bookEntity);

        assertNull(bookEntity.getMetadata().getMoods());
        verifyNoInteractions(moodRepository);
    }

    @Test
    void addMoodsToBook_validMoods_addsToBook() {
        MoodEntity mood = MoodEntity.builder().name("Dark").build();
        when(moodRepository.findByName("Dark")).thenReturn(Optional.of(mood));

        bookCreatorService.addMoodsToBook(Set.of("Dark"), bookEntity);

        assertNotNull(bookEntity.getMetadata().getMoods());
        assertEquals(1, bookEntity.getMetadata().getMoods().size());
    }

    @Test
    void addTagsToBook_nullTags_doesNothing() {
        bookCreatorService.addTagsToBook(null, bookEntity);

        assertNull(bookEntity.getMetadata().getTags());
        verifyNoInteractions(tagRepository);
    }

    @Test
    void addTagsToBook_emptyTags_doesNothing() {
        bookCreatorService.addTagsToBook(Set.of(), bookEntity);

        assertNull(bookEntity.getMetadata().getTags());
        verifyNoInteractions(tagRepository);
    }

    @Test
    void addTagsToBook_validTags_addsToBook() {
        TagEntity tag = TagEntity.builder().name("favorite").build();
        when(tagRepository.findByName("favorite")).thenReturn(Optional.of(tag));

        bookCreatorService.addTagsToBook(Set.of("favorite"), bookEntity);

        assertNotNull(bookEntity.getMetadata().getTags());
        assertEquals(1, bookEntity.getMetadata().getTags().size());
    }

    @Test
    void addAuthorsToBook_existingAuthorsOnEntity_appendsWithoutOverwriting() {
        AuthorEntity existingAuthor = AuthorEntity.builder().name("Existing").build();
        bookEntity.getMetadata().setAuthors(new ArrayList<>(List.of(existingAuthor)));

        AuthorEntity newAuthor = AuthorEntity.builder().name("New Author").build();
        when(authorRepository.findByName("New Author")).thenReturn(Optional.of(newAuthor));

        bookCreatorService.addAuthorsToBook(Set.of("New Author"), bookEntity);

        assertEquals(2, bookEntity.getMetadata().getAuthors().size());
    }

    @Test
    void addAuthorsToBook_newAuthorNotInRepo_savesAndAdds() {
        AuthorEntity saved = AuthorEntity.builder().name("Brand New").build();
        when(authorRepository.findByName("Brand New")).thenReturn(Optional.empty());
        when(authorRepository.save(any())).thenReturn(saved);

        bookCreatorService.addAuthorsToBook(Set.of("Brand New"), bookEntity);

        verify(authorRepository).save(any());
        assertEquals(1, bookEntity.getMetadata().getAuthors().size());
    }

    // ========== createShellBook ==========

    @Test
    void createShellBook_newBook_isSavedWithTheFactsAndReadsNoFile() {
        LibraryFile libraryFile = libraryFile(true);
        when(bookRepository.saveAndFlush(any(BookEntity.class))).thenAnswer(inv -> {
            BookEntity saved = inv.getArgument(0);
            saved.setId(9L);
            return saved;
        });

        BookCreatorService.ShellBook shell;
        try (MockedStatic<FileFingerprint> fingerprint = mockStatic(FileFingerprint.class);
             MockedStatic<FileUtils> fileUtils = mockStatic(FileUtils.class)) {
            shell = bookCreatorService.createShellBook(libraryFile, BookFileType.AUDIOBOOK, new FileFacts("abc", 42L));
            fingerprint.verifyNoInteractions();
            fileUtils.verifyNoInteractions();
        }

        assertTrue(shell.created());
        BookEntity book = shell.entity();
        assertEquals(9L, book.getId());
        assertSame(libraryFile.getLibraryEntity(), book.getLibrary());
        assertSame(libraryFile.getLibraryPathEntity(), book.getLibraryPath());
        assertNotNull(book.getAddedOn());
        assertNotNull(book.getMetadata());
        BookFileEntity file = book.getPrimaryBookFile();
        assertEquals("Book", file.getFileName());
        assertEquals("Author", file.getFileSubPath());
        assertEquals(BookFileType.AUDIOBOOK, file.getBookType());
        assertTrue(file.isBookFormat());
        assertTrue(file.isFolderBased());
        assertEquals(42L, file.getFileSizeKb());
        assertEquals("abc", file.getInitialHash());
        assertEquals("abc", file.getCurrentHash());
    }

    @Test
    void createShellBook_existingBook_isUpdatedWithTheFactsAndNotSaved() {
        LibraryFile libraryFile = libraryFile(false);
        BookEntity existing = BookEntity.builder().id(9L).deleted(true).bookFiles(new ArrayList<>()).build();
        BookFileEntity file = BookFileEntity.builder().fileName("Book").fileSubPath("Author").isBookFormat(true)
                .folderBased(true).initialHash("old").currentHash("old").fileSizeKb(1L).build();
        existing.getBookFiles().add(file);
        when(bookRepository.findByLibraryIdAndLibraryPathIdAndFileSubPathAndFileName(1L, 2L, "Author", "Book"))
                .thenReturn(Optional.of(existing));

        BookCreatorService.ShellBook shell;
        try (MockedStatic<FileFingerprint> fingerprint = mockStatic(FileFingerprint.class);
             MockedStatic<FileUtils> fileUtils = mockStatic(FileUtils.class)) {
            shell = bookCreatorService.createShellBook(libraryFile, BookFileType.EPUB, new FileFacts("abc", 42L));
            fingerprint.verifyNoInteractions();
            fileUtils.verifyNoInteractions();
        }

        assertFalse(shell.created());
        assertSame(existing, shell.entity());
        assertFalse(existing.getDeleted());
        assertEquals("abc", file.getInitialHash());
        assertEquals("abc", file.getCurrentHash());
        assertEquals(42L, file.getFileSizeKb());
        assertFalse(file.isFolderBased());
        verify(bookRepository, never()).saveAndFlush(any());
    }

    @Test
    void createShellBook_unknownSize_isStoredAsZero() {
        when(bookRepository.saveAndFlush(any(BookEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        BookCreatorService.ShellBook shell = bookCreatorService.createShellBook(libraryFile(false), BookFileType.EPUB, new FileFacts("abc", null));

        assertEquals(0L, shell.entity().getPrimaryBookFile().getFileSizeKb());
    }

    @Test
    void createShellBook_existingBookWithUnknownSize_isGivenZero() {
        BookEntity existing = BookEntity.builder().id(9L).bookFiles(new ArrayList<>()).build();
        BookFileEntity file = BookFileEntity.builder().fileName("Book").fileSizeKb(5L).build();
        existing.getBookFiles().add(file);
        when(bookRepository.findByLibraryIdAndLibraryPathIdAndFileSubPathAndFileName(1L, 2L, "Author", "Book"))
                .thenReturn(Optional.of(existing));

        bookCreatorService.createShellBook(libraryFile(false), BookFileType.EPUB, new FileFacts("abc", null));

        assertEquals(0L, file.getFileSizeKb());
    }

    // ========== saveConnections ==========

    @Test
    void saveConnections_comicBook_fillsTheComicMetadataFromTheDto() {
        ComicMetadataEntity comic = new ComicMetadataEntity();
        comic.setBookId(9L);
        bookEntity.getMetadata().setComicMetadata(comic);
        ComicMetadata dto = ComicMetadata.builder()
                .characters(Set.of("Hero"))
                .teams(Set.of("Team"))
                .locations(Set.of("City"))
                .pencillers(Set.of("Pen"))
                .build();
        when(comicCharacterRepository.findByName("Hero")).thenReturn(Optional.of(ComicCharacterEntity.builder().name("Hero").build()));
        when(comicTeamRepository.findByName("Team")).thenReturn(Optional.empty());
        when(comicTeamRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(comicLocationRepository.findByName("City")).thenReturn(Optional.of(ComicLocationEntity.builder().name("City").build()));
        when(comicCreatorRepository.findByName("Pen")).thenReturn(Optional.of(ComicCreatorEntity.builder().name("Pen").build()));

        bookCreatorService.saveConnections(bookEntity, dto);

        verify(bookRepository).save(bookEntity);
        verify(bookMetadataRepository).save(bookEntity.getMetadata());
        verify(comicMetadataRepository, times(2)).save(comic);
        assertThat(comic.getCharacters()).extracting(ComicCharacterEntity::getName).containsExactly("Hero");
        assertThat(comic.getTeams()).extracting(ComicTeamEntity::getName).containsExactly("Team");
        assertThat(comic.getLocations()).extracting(ComicLocationEntity::getName).containsExactly("City");
        assertThat(comic.getCreatorMappings()).extracting(m -> m.getCreator().getName(), ComicCreatorMappingEntity::getRole)
                .containsExactly(tuple("Pen", ComicCreatorRole.PENCILLER));
    }

    @Test
    void saveConnections_bookWithoutComicMetadata_ignoresTheDto() {
        bookCreatorService.saveConnections(bookEntity, ComicMetadata.builder().characters(Set.of("Hero")).build());

        verify(bookRepository).save(bookEntity);
        verifyNoInteractions(comicMetadataRepository, comicCharacterRepository);
    }

    @Test
    void saveConnections_comicBookWithoutDto_savesTheComicMetadataAlone() {
        ComicMetadataEntity comic = new ComicMetadataEntity();
        comic.setBookId(9L);
        bookEntity.getMetadata().setComicMetadata(comic);

        bookCreatorService.saveConnections(bookEntity, null);

        verify(comicMetadataRepository).save(comic);
        verifyNoInteractions(comicCharacterRepository, comicTeamRepository, comicLocationRepository, comicCreatorRepository);
    }

    @Test
    void saveConnections_savesTheAuthors() {
        List<AuthorEntity> authors = new ArrayList<>(List.of(AuthorEntity.builder().name("A").build()));
        bookEntity.getMetadata().setAuthors(authors);

        bookCreatorService.saveConnections(bookEntity, null);

        verify(authorRepository).saveAll(authors);
    }

    private LibraryFile libraryFile(boolean folderBased) {
        LibraryEntity library = LibraryEntity.builder().id(1L).build();
        LibraryPathEntity libraryPath = LibraryPathEntity.builder().id(2L).path("/books").build();
        return LibraryFile.builder()
                .libraryEntity(library)
                .libraryPathEntity(libraryPath)
                .fileSubPath("Author")
                .fileName("Book")
                .folderBased(folderBased)
                .build();
    }
}
