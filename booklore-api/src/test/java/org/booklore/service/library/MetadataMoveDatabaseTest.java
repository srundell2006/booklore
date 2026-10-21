package org.booklore.service.library;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.booklore.BookloreApplication;
import org.booklore.model.dto.BookMetadata;
import org.booklore.model.dto.settings.MetadataPersistenceSettings;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.entity.LibraryPathEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.model.enums.MergeMetadataType;
import org.booklore.model.enums.MetadataReplaceMode;
import org.booklore.repository.BookAdditionalFileRepository;
import org.booklore.repository.BookMetadataRepository;
import org.booklore.repository.BookRepository;
import org.booklore.repository.LibraryRepository;
import org.booklore.service.NotificationService;
import org.booklore.service.appsettings.AppSettingService;
import org.booklore.service.file.FileMoveService;
import org.booklore.service.metadata.MetadataManagementService;
import org.booklore.service.metadata.extractor.MetadataExtractorFactory;
import org.booklore.service.metadata.sidecar.SidecarService;
import org.booklore.service.task.TaskCronService;
import org.booklore.task.options.LibraryRescanOptions;
import org.booklore.task.options.RescanLibraryContext;
import org.apache.commons.io.FilenameUtils;
import org.hibernate.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.orm.jpa.support.OpenEntityManagerInViewInterceptor;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.ServletWebRequest;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Metadata changes that move files to the library's naming pattern, against H2 with the real
 * services, repositories and transactions; only reading metadata from the files is stubbed. The
 * server may stop at any point, so a moved file's new path must be committed as soon as it has
 * moved: the next rescan would otherwise find the book's file gone, delete the book, and import
 * the moved file as a new one.
 */
@SpringBootTest(classes = {
        BookloreApplication.class
})
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.datasource.url=jdbc:h2:mem:metadatamove;DB_CLOSE_DELAY=-1",
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
@Import(MetadataMoveDatabaseTest.TestConfig.class)
class MetadataMoveDatabaseTest {

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
    private MetadataExtractorFactory metadataExtractorFactory;
    @MockitoBean
    private NotificationService notificationService;
    @MockitoSpyBean
    private FileMoveService fileMoveService;

    @Autowired
    private LibraryRescanHelper libraryRescanHelper;
    @Autowired
    private MetadataManagementService metadataManagementService;
    @Autowired
    private SidecarService sidecarService;
    @Autowired
    private OpenEntityManagerInViewInterceptor openEntityManagerInViewInterceptor;
    @Autowired
    private EntityManagerFactory entityManagerFactory;
    @Autowired
    private AppSettingService appSettingService;
    @Autowired
    private LibraryRepository libraryRepository;
    @Autowired
    private BookRepository bookRepository;
    @Autowired
    private BookMetadataRepository bookMetadataRepository;
    @Autowired
    private BookAdditionalFileRepository bookFileRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @TempDir
    Path libraryRoot;

    /** A transaction of its own, which sees only what is committed. */
    private TransactionTemplate committed;
    private boolean moveFilesBefore;
    private MetadataPersistenceSettings.SaveToOriginalFile saveToOriginalFileBefore;

    @BeforeEach
    void setUp() {
        committed = new TransactionTemplate(transactionManager);
        committed.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        MetadataPersistenceSettings settings = appSettingService.getAppSettings().getMetadataPersistenceSettings();
        moveFilesBefore = settings.isMoveFilesToLibraryPattern();
        saveToOriginalFileBefore = settings.getSaveToOriginalFile();
        settings.setMoveFilesToLibraryPattern(true);
        settings.setSaveToOriginalFile(MetadataPersistenceSettings.SaveToOriginalFile.builder().build());
    }

    @AfterEach
    void restoreSettings() {
        MetadataPersistenceSettings settings = appSettingService.getAppSettings().getMetadataPersistenceSettings();
        settings.setMoveFilesToLibraryPattern(moveFilesBefore);
        settings.setSaveToOriginalFile(saveToOriginalFileBefore);
    }

    @Test
    void refreshingALibrarysMetadataCommitsEachMovedBookBeforeReadingTheNext() throws IOException {
        LibraryEntity library = library("{title}");
        Map<String, Long> bookIdsByFileName = new HashMap<>();
        for (String title : List.of("One", "Two", "Three")) {
            bookIdsByFileName.put(title + ".epub", book(library, "old", title, null).getId());
        }
        List<String> titlesRead = new ArrayList<>();
        List<String> movedButNotCommitted = new ArrayList<>();
        when(metadataExtractorFactory.extractMetadata(any(BookFileType.class), any(File.class))).thenAnswer(inv -> {
            File file = inv.getArgument(1);
            for (String title : titlesRead) {
                String expected = "/New " + title + ".epub";
                String committedPath = committedPath(bookIdsByFileName.get(title + ".epub"));
                if (!expected.equals(committedPath) || !Files.exists(libraryRoot.resolve("New " + title + ".epub"))) {
                    movedButNotCommitted.add("reading " + file.getName() + ": " + title + " is committed at " + committedPath);
                }
            }
            String title = FilenameUtils.getBaseName(file.getName());
            titlesRead.add(title);
            return BookMetadata.builder().title("New " + title).build();
        });

        libraryRescanHelper.handleRescanOptions(RescanLibraryContext.builder()
                .libraryId(library.getId())
                .options(LibraryRescanOptions.builder()
                        .metadataReplaceMode(MetadataReplaceMode.REPLACE_ALL)
                        .updateMetadataFromFiles(true)
                        .build())
                .build(), null);

        assertThat(titlesRead).containsExactlyInAnyOrder("One", "Two", "Three");
        assertThat(movedButNotCommitted).isEmpty();
        for (String title : titlesRead) {
            assertThat(committedPath(bookIdsByFileName.get(title + ".epub"))).isEqualTo("/New " + title + ".epub");
            assertThat(libraryRoot.resolve("New " + title + ".epub")).exists();
        }
    }

    @Test
    void mergingSeriesMovesTheFilesOnlyOnceTheMergeIsCommitted() throws IOException {
        LibraryEntity library = library("{series}/{title}");
        List<Long> bookIds = List.of(
                book(library, "Old Saga", "A", "Old Saga").getId(),
                book(library, "Old Saga", "B", "Old Saga").getId());
        List<String> seriesCommittedAtMove = new ArrayList<>();
        doAnswer(inv -> {
            for (Long bookId : bookIds) {
                seriesCommittedAtMove.add(committed.execute(status -> bookMetadataRepository.findById(bookId).orElseThrow().getSeriesName()));
            }
            return inv.callRealMethod();
        }).when(fileMoveService).moveSingleFile(any());

        metadataManagementService.consolidateMetadata(MergeMetadataType.series, List.of("New Saga"), List.of("Old Saga"));

        assertThat(seriesCommittedAtMove).hasSize(4).containsOnly("New Saga");
        assertThat(committedPath(bookIds.get(0))).isEqualTo("New Saga/A.epub");
        assertThat(committedPath(bookIds.get(1))).isEqualTo("New Saga/B.epub");
        assertThat(libraryRoot.resolve("New Saga/A.epub")).exists();
        assertThat(libraryRoot.resolve("New Saga/B.epub")).exists();
        assertThat(libraryRoot.resolve("Old Saga")).doesNotExist();
    }

    @Test
    void importingAllSidecarsFromARequestKeepsNoBookInTheRequestsEntityManager() throws IOException {
        LibraryEntity library = library("{title}");
        Long withSidecar = book(library, "old", "One", null).getId();
        Long withoutSidecar = book(library, "old", "Two", null).getId();
        book(library, "old", "Three", null);
        Files.writeString(libraryRoot.resolve("old/One.metadata.json"), "{\"metadata\":{\"title\":\"New One\"}}");

        // The import is a request of its own, and a request's EntityManager is open for its whole duration
        ServletWebRequest request = new ServletWebRequest(new MockHttpServletRequest());
        openEntityManagerInViewInterceptor.preHandle(request);
        int imported;
        int entitiesKept;
        try {
            EntityManager requestEntityManager = ((EntityManagerHolder) TransactionSynchronizationManager.getResource(entityManagerFactory)).getEntityManager();
            imported = sidecarService.bulkImport(library.getId());
            // Every book kept there would be dirty-checked again by the flush of each book after it
            entitiesKept = requestEntityManager.unwrap(Session.class).getStatistics().getEntityCount();
        } finally {
            openEntityManagerInViewInterceptor.afterCompletion(request, null);
        }

        assertThat(imported).isEqualTo(1);
        assertThat(entitiesKept).isZero();
        assertThat(committedPath(withSidecar)).isEqualTo("/New One.epub");
        assertThat(committedPath(withoutSidecar)).isEqualTo("old/Two.epub");
        assertThat(libraryRoot.resolve("New One.epub")).exists();
    }

    private LibraryEntity library(String fileNamingPattern) {
        return committed.execute(status -> {
            LibraryEntity created = libraryRepository.save(LibraryEntity.builder()
                    .name("Books " + libraryRoot.getFileName()).icon("book").watch(false)
                    .fileNamingPattern(fileNamingPattern)
                    .formatPriority(new ArrayList<>(List.of(BookFileType.EPUB)))
                    .libraryPaths(new ArrayList<>())
                    .build());
            created.getLibraryPaths().add(LibraryPathEntity.builder().library(created).path(libraryRoot.toString()).build());
            return libraryRepository.save(created);
        });
    }

    private BookEntity book(LibraryEntity library, String subPath, String title, String seriesName) throws IOException {
        Path folder = Files.createDirectories(libraryRoot.resolve(subPath));
        Files.writeString(folder.resolve(title + ".epub"), title);
        return committed.execute(status -> {
            BookEntity book = bookRepository.save(BookEntity.builder()
                    .library(library).libraryPath(library.getLibraryPaths().getFirst()).addedOn(Instant.now()).deleted(false).build());
            bookMetadataRepository.save(BookMetadataEntity.builder().book(book).title(title).seriesName(seriesName).build());
            bookFileRepository.save(BookFileEntity.builder()
                    .book(book).fileSubPath(subPath).fileName(title + ".epub").bookType(BookFileType.EPUB).isBookFormat(true).build());
            return book;
        });
    }

    private String committedPath(Long bookId) {
        return committed.execute(status -> {
            BookFileEntity file = bookFileRepository.findByBookId(bookId).getFirst();
            return file.getFileSubPath() + "/" + file.getFileName();
        });
    }
}
