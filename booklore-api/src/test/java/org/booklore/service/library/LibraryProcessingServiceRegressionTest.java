package org.booklore.service.library;

import org.booklore.model.dto.settings.LibraryFile;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.entity.LibraryPathEntity;
import org.booklore.repository.BookAdditionalFileRepository;
import org.booklore.repository.BookRepository;
import org.booklore.repository.LibraryRepository;
import org.booklore.service.NotificationService;
import org.booklore.task.options.RescanLibraryContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LibraryProcessingServiceRegressionTest {

    @Mock
    private LibraryRepository libraryRepository;
    @Mock
    private NotificationService notificationService;
    @Mock
    private BookAdditionalFileRepository bookAdditionalFileRepository;
    @Mock
    private BookRepository bookRepository;
    @Mock
    private FileAsBookProcessor fileAsBookProcessor;
    @Mock
    private BookRestorationService bookRestorationService;
    @Mock
    private BookDeletionService bookDeletionService;
    @Mock
    private LibraryFileHelper libraryFileHelper;
    @Mock
    private BookGroupingService bookGroupingService;
    @Mock
    private PlatformTransactionManager transactionManager;

    private LibraryProcessingService libraryProcessingService;

    @BeforeEach
    void setUp() {
        libraryProcessingService = new LibraryProcessingService(
                libraryRepository,
                notificationService,
                bookAdditionalFileRepository,
                bookRepository,
                fileAsBookProcessor,
                bookRestorationService,
                bookDeletionService,
                libraryFileHelper,
                bookGroupingService,
                transactionManager
        );
    }

    @Test
    void rescanLibrary_shouldNotDeleteFilelessBooks(@TempDir Path tempDir) throws IOException {
        long libraryId = 1L;
        Path accessiblePath = tempDir.resolve("accessible");
        Files.createDirectory(accessiblePath);

        LibraryEntity libraryEntity = new LibraryEntity();
        libraryEntity.setId(libraryId);
        libraryEntity.setName("Test Library");

        LibraryPathEntity pathEntity = new LibraryPathEntity();
        pathEntity.setId(10L);
        pathEntity.setPath(accessiblePath.toString());
        libraryEntity.setLibraryPaths(List.of(pathEntity));

        when(libraryRepository.findById(libraryId)).thenReturn(Optional.of(libraryEntity));
        when(libraryFileHelper.scanAllLibraryFiles(libraryEntity)).thenReturn(new LibraryScanResult(List.of(
            LibraryFile.builder()
                .libraryPathEntity(pathEntity)
                .fileName("other.epub")
                .fileSubPath("")
                .build()
        ), Set.of()));
        when(libraryFileHelper.filterByAllowedFormats(anyList(), any())).thenAnswer(inv -> inv.getArgument(0));
        // A fileless book (e.g., physical book) has no book_file rows at all
        when(bookAdditionalFileRepository.findScanRowsByLibraryId(libraryId)).thenReturn(Collections.emptyList());
        when(bookGroupingService.groupForRescan(anyList(), any(LibraryEntity.class)))
                .thenReturn(new BookGroupingService.GroupingResult(Collections.emptyMap(), Collections.emptyMap()));

        RescanLibraryContext context = RescanLibraryContext.builder().libraryId(libraryId).build();

        libraryProcessingService.rescanLibrary(context);

        // Fileless books should NOT be marked as deleted - they are intentionally without files
        verify(bookDeletionService, never()).processDeletedLibraryFiles(any());
    }
}
