package org.booklore.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.booklore.BookloreApplication;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.entity.BookFileEntity.AudioFileChapter;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.entity.LibraryPathEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.repository.projection.BookFileScanRow;
import org.booklore.service.task.TaskCronService;
import org.hibernate.Session;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.mock;

/**
 * Persistence behaviour a library rescan depends on, checked against H2.
 */
@SpringBootTest(classes = {
        BookloreApplication.class
})
@Transactional
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "spring.datasource.url=jdbc:h2:mem:libraryscan;DB_CLOSE_DELAY=-1",
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
@Import(LibraryScanRepositoryTest.TestConfig.class)
class LibraryScanRepositoryTest {

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

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private BookAdditionalFileRepository bookAdditionalFileRepository;

    @Autowired
    private BookRepository bookRepository;

    private LibraryEntity library;
    private LibraryPathEntity libraryPath;

    @BeforeEach
    void setUp() {
        library = persist(LibraryEntity.builder().name("Books").icon("book").watch(false).build());
        libraryPath = persist(LibraryPathEntity.builder().library(library).path("/books").build());
    }

    private Statistics statistics() {
        return entityManager.unwrap(Session.class).getSessionFactory().getStatistics();
    }

    private <T> T persist(T entity) {
        entityManager.persist(entity);
        entityManager.flush();
        return entity;
    }

    private BookEntity book(LibraryEntity owner, LibraryPathEntity path, boolean deleted) {
        return persist(BookEntity.builder()
                .library(owner)
                .libraryPath(path)
                .addedOn(Instant.now())
                .deleted(deleted)
                .build());
    }

    private BookFileEntity file(BookEntity book, String subPath, String name, BookFileType type, boolean bookFormat) {
        return persist(BookFileEntity.builder()
                .book(book)
                .fileSubPath(subPath)
                .fileName(name)
                .bookType(type)
                .isBookFormat(bookFormat)
                .build());
    }

    private void metadata(BookEntity book, String title) {
        persist(BookMetadataEntity.builder().book(book).bookId(book.getId()).title(title).build());
    }

    private void clear() {
        entityManager.flush();
        entityManager.clear();
        statistics().clear();
    }

    @Nested
    class AudioChapterDirtyChecking {

        private Long audiobookFileId;
        private Long audiobookBookId;

        @BeforeEach
        void persistAudiobookWithChapters() {
            BookEntity book = book(library, libraryPath, false);
            audiobookBookId = book.getId();
            BookFileEntity audiobook = BookFileEntity.builder()
                    .book(book)
                    .fileSubPath("Author/Title")
                    .fileName("Title")
                    .bookType(BookFileType.AUDIOBOOK)
                    .isBookFormat(true)
                    .folderBased(true)
                    .chapterCount(2)
                    .chapters(new ArrayList<>(List.of(
                            new AudioFileChapter(0, "Opening", 0L, 60_000L, 60_000L),
                            new AudioFileChapter(1, "Ending", 60_000L, 90_000L, 30_000L))))
                    .build();
            audiobookFileId = persist(audiobook).getId();
            clear();
        }

        @Test
        void chaptersCompareByValue() {
            AudioFileChapter chapter = new AudioFileChapter(0, "Opening", 0L, 60_000L, 60_000L);
            AudioFileChapter same = new AudioFileChapter(0, "Opening", 0L, 60_000L, 60_000L);

            assertThat(same).isEqualTo(chapter).hasSameHashCodeAs(chapter);
            assertThat(List.of(same)).isEqualTo(List.of(chapter));
            assertThat(new AudioFileChapter(0, "Opening", 0L, 60_000L, 59_999L)).isNotEqualTo(chapter);
            assertThat(new AudioFileChapter(1, "Opening", 0L, 60_000L, 60_000L)).isNotEqualTo(chapter);
            assertThat(new AudioFileChapter(0, null, 0L, 60_000L, 60_000L)).isNotEqualTo(chapter);
        }

        @Test
        void loadingAnAudiobookFileWithChaptersAndFlushingIssuesNoUpdate() {
            BookFileEntity loaded = entityManager.find(BookFileEntity.class, audiobookFileId);
            assertThat(loaded.getChapters()).hasSize(2);

            entityManager.flush();

            assertThat(statistics().getEntityUpdateCount()).isZero();
        }

        @Test
        void queryAutoFlushDoesNotUpdateLoadedAudiobookFiles() {
            entityManager.find(BookFileEntity.class, audiobookFileId);

            // A query on book_file auto-flushes, dirty-checking everything managed first; in the
            // old one-transaction rescan every query did this against the whole library.
            bookAdditionalFileRepository.findByBookId(audiobookBookId);
            bookRepository.countByLibraryId(library.getId());

            assertThat(statistics().getEntityUpdateCount()).isZero();
        }

        @Test
        void changingAChapterIsStillWritten() {
            BookFileEntity loaded = entityManager.find(BookFileEntity.class, audiobookFileId);
            loaded.getChapters().getFirst().setTitle("Prologue");

            entityManager.flush();

            assertThat(statistics().getEntityUpdateCount()).isEqualTo(1);
            entityManager.clear();
            assertThat(entityManager.find(BookFileEntity.class, audiobookFileId).getChapters().getFirst().getTitle())
                    .isEqualTo("Prologue");
        }
    }

    @Nested
    class ScanRowProjection {

        @Test
        void returnsOneRowPerBookFileOfTheLibraryWithTheBookColumns() {
            BookEntity active = book(library, libraryPath, false);
            BookFileEntity epub = file(active, "Author/Title", "Title.epub", BookFileType.EPUB, true);
            BookFileEntity notes = file(active, "Author/Title", "notes.txt", BookFileType.PDF, false);

            BookEntity softDeleted = book(library, libraryPath, true);
            BookFileEntity deletedPdf = file(softDeleted, "", "Gone.pdf", BookFileType.PDF, true);

            BookEntity withoutPath = book(library, null, false);
            BookFileEntity orphan = file(withoutPath, "x", "Orphan.cbz", BookFileType.CBX, true);

            book(library, libraryPath, false); // fileless: no rows

            LibraryEntity otherLibrary = persist(LibraryEntity.builder().name("Other").icon("book").watch(false).build());
            LibraryPathEntity otherPath = persist(LibraryPathEntity.builder().library(otherLibrary).path("/other").build());
            file(book(otherLibrary, otherPath, false), "", "Elsewhere.epub", BookFileType.EPUB, true);
            clear();

            List<BookFileScanRow> rows = bookAdditionalFileRepository.findScanRowsByLibraryId(library.getId());

            assertThat(rows)
                    .extracting(BookFileScanRow::bookFileId, BookFileScanRow::bookId, BookFileScanRow::libraryPathId,
                            BookFileScanRow::libraryPath, BookFileScanRow::fileSubPath, BookFileScanRow::fileName,
                            BookFileScanRow::bookFormat, BookFileScanRow::bookType, BookFileScanRow::bookDeleted)
                    .containsExactlyInAnyOrder(
                            tuple(epub.getId(), active.getId(), libraryPath.getId(), "/books", "Author/Title", "Title.epub",
                                    true, BookFileType.EPUB, false),
                            tuple(notes.getId(), active.getId(), libraryPath.getId(), "/books", "Author/Title", "notes.txt",
                                    false, BookFileType.PDF, false),
                            tuple(deletedPdf.getId(), softDeleted.getId(), libraryPath.getId(), "/books", "", "Gone.pdf",
                                    true, BookFileType.PDF, true),
                            tuple(orphan.getId(), withoutPath.getId(), null, null, "x", "Orphan.cbz",
                                    true, BookFileType.CBX, false));
            assertThat(statistics().getPrepareStatementCount()).isEqualTo(1);
            assertThat(statistics().getEntityLoadCount()).isZero();
        }

        @Test
        void countIncludingDeletedCountsSoftDeletedAndFilelessBooks() {
            file(book(library, libraryPath, false), "", "A.epub", BookFileType.EPUB, true);
            book(library, libraryPath, true);
            book(library, null, false);

            assertThat(bookRepository.countIncludingDeletedByLibraryId(library.getId())).isEqualTo(3);
            assertThat(bookRepository.countByLibraryId(library.getId())).isEqualTo(2);
        }
    }

    @Nested
    class GroupingLookups {

        @Test
        void filelessBooksWithMetadataMatchTheGroupByQueryAndComeWithMetadataLoaded() {
            BookEntity physical = book(library, null, false);
            metadata(physical, "Dune");
            BookEntity physicalWithPath = book(library, libraryPath, false);
            metadata(physicalWithPath, "Emma");
            BookEntity deletedPhysical = book(library, null, true);
            metadata(deletedPhysical, "Gone");
            BookEntity withFile = book(library, libraryPath, false);
            metadata(withFile, "Has File");
            file(withFile, "", "Has File.epub", BookFileType.EPUB, true);
            clear();

            List<Long> expected = bookRepository.findFilelessBooksByLibraryId(library.getId()).stream()
                    .map(BookEntity::getId).sorted().toList();
            clear();

            List<BookEntity> fileless = bookRepository.findFilelessBooksWithMetadataByLibraryId(library.getId());
            long statements = statistics().getPrepareStatementCount();

            assertThat(fileless).extracting(BookEntity::getId)
                    .containsExactlyElementsOf(expected)
                    .containsExactly(physical.getId(), physicalWithPath.getId());
            assertThat(fileless).extracting(b -> b.getMetadata().getTitle()).containsExactly("Dune", "Emma");
            assertThat(fileless.get(1).getLibraryPath().getId()).isEqualTo(libraryPath.getId());
            assertThat(statistics().getPrepareStatementCount()).isEqualTo(statements);
        }

        @Test
        void booksInDirectoryMatchThePlainQueryAndComeWithAllTheirFiles() {
            BookEntity first = book(library, libraryPath, false);
            BookFileEntity firstEpub = file(first, "Author/Title", "Title.epub", BookFileType.EPUB, true);
            BookFileEntity firstAudio = file(first, "Author/Title/audio", "audio", BookFileType.AUDIOBOOK, true);
            BookFileEntity firstNotes = file(first, "Author/Title", "notes.txt", BookFileType.PDF, false);
            BookEntity second = book(library, libraryPath, false);
            BookFileEntity secondPdf = file(second, "Author/Title", "Other.pdf", BookFileType.PDF, true);
            BookEntity deleted = book(library, libraryPath, true);
            file(deleted, "Author/Title", "Deleted.epub", BookFileType.EPUB, true);
            BookEntity onlyNonBook = book(library, libraryPath, false);
            file(onlyNonBook, "Author/Title", "cover.jpg", BookFileType.PDF, false);
            BookEntity elsewhere = book(library, libraryPath, false);
            file(elsewhere, "Author/Other", "Other.epub", BookFileType.EPUB, true);
            clear();

            List<Long> expected = bookRepository.findAllByLibraryPathIdAndFileSubPath(libraryPath.getId(), "Author/Title")
                    .stream().map(BookEntity::getId).sorted().toList();
            clear();

            List<BookEntity> books = bookRepository.findAllWithFilesByLibraryPathIdAndFileSubPath(libraryPath.getId(), "Author/Title");
            long statements = statistics().getPrepareStatementCount();

            assertThat(books).extracting(BookEntity::getId)
                    .containsExactlyElementsOf(expected)
                    .containsExactly(first.getId(), second.getId());
            assertThat(books.getFirst().getBookFiles()).extracting(BookFileEntity::getId)
                    .containsExactly(firstEpub.getId(), firstAudio.getId(), firstNotes.getId());
            assertThat(books.get(1).getBookFiles()).extracting(BookFileEntity::getId).containsExactly(secondPdf.getId());
            // What grouping reads: primary file (needs the library's format priority) and file types.
            assertThat(books).extracting(b -> b.getPrimaryBookFile().getId())
                    .containsExactly(firstEpub.getId(), secondPdf.getId());
            assertThat(books.getFirst().getLibraryPath().getId()).isEqualTo(libraryPath.getId());
            assertThat(statistics().getPrepareStatementCount()).isEqualTo(statements);
        }
    }
}
