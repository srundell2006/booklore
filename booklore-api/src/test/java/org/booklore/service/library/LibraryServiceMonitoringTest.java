package org.booklore.service.library;

import org.booklore.mapper.BookMapper;
import org.booklore.mapper.LibraryMapper;
import org.booklore.model.dto.Library;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.repository.BookRepository;
import org.booklore.repository.LibraryPathRepository;
import org.booklore.repository.LibraryRepository;
import org.booklore.repository.UserRepository;
import org.booklore.service.NotificationService;
import org.booklore.service.audit.AuditService;
import org.booklore.util.FileService;
import org.booklore.service.monitoring.LibraryWatchService;
import org.booklore.config.security.service.AuthenticationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.Mockito.*;

/** What the application watches once it is up. */
@ExtendWith(MockitoExtension.class)
class LibraryServiceMonitoringTest {

    @Mock
    private LibraryRepository libraryRepository;
    @Mock
    private LibraryPathRepository libraryPathRepository;
    @Mock
    private BookRepository bookRepository;
    @Mock
    private LibraryProcessingService libraryProcessingService;
    @Mock
    private BookMapper bookMapper;
    @Mock
    private LibraryMapper libraryMapper;
    @Mock
    private NotificationService notificationService;
    @Mock
    private FileService fileService;
    @Mock
    private LibraryWatchService libraryWatchService;
    @Mock
    private AuthenticationService authenticationService;
    @Mock
    private UserRepository userRepository;
    @Mock
    private AuditService auditService;

    @InjectMocks
    private LibraryService libraryService;

    @Test
    void initializeMonitoring_watchesTheLibrariesThenHasTheirFilesWithoutBooksAnnounced() {
        // What arrived while the application was down, or waited to be handled when it stopped, gives no event
        LibraryEntity entity = LibraryEntity.builder().id(3L).name("Books").build();
        Library library = Library.builder().id(3L).name("Books").watch(true).build();
        when(libraryRepository.findAll()).thenReturn(List.of(entity));
        when(libraryMapper.toLibrary(entity)).thenReturn(library);

        libraryService.initializeMonitoring();

        InOrder order = inOrder(libraryWatchService);
        order.verify(libraryWatchService).registerLibraries(List.of(library));
        order.verify(libraryWatchService).announceFilesWithoutBooks(List.of(library));
    }
}
