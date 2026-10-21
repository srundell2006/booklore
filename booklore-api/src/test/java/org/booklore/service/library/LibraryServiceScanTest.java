package org.booklore.service.library;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.booklore.config.security.service.AuthenticationService;
import org.booklore.exception.APIException;
import org.booklore.mapper.BookMapper;
import org.booklore.mapper.LibraryMapper;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.Library;
import org.booklore.model.dto.request.CreateLibraryRequest;
import org.booklore.model.entity.BookLoreUserEntity;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.websocket.LogNotification;
import org.booklore.model.websocket.Topic;
import org.booklore.repository.BookRepository;
import org.booklore.repository.LibraryPathRepository;
import org.booklore.repository.LibraryRepository;
import org.booklore.repository.UserRepository;
import org.booklore.service.NotificationService;
import org.booklore.service.audit.AuditService;
import org.booklore.service.monitoring.LibraryWatchService;
import org.booklore.util.FileService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * How the threads that scan and rescan a library end. The ids are this class's own: which
 * libraries are being scanned is kept for the whole application.
 */
@ExtendWith(MockitoExtension.class)
class LibraryServiceScanTest {

    private static final String COMPLETED = "Parsing task completed!";

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

    private final Logger serviceLog = (Logger) LoggerFactory.getLogger(LibraryService.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    @BeforeEach
    void captureLogs() {
        logs.start();
        serviceLog.addAppender(logs);
    }

    @AfterEach
    void stopCapturingLogs() {
        serviceLog.detachAppender(logs);
    }

    private void givenLibrary(long libraryId) {
        when(libraryRepository.findById(libraryId)).thenReturn(Optional.of(LibraryEntity.builder().id(libraryId).name("Books").build()));
    }

    /** What the threads of the scan or rescan of this library logged. */
    private List<ILoggingEvent> logged(long libraryId, Predicate<ILoggingEvent> filter) {
        List<ILoggingEvent> events;
        // The appender adds under its own lock.
        synchronized (logs) {
            events = List.copyOf(logs.list);
        }
        return events.stream()
                .filter(event -> event.getThreadName().endsWith("-" + libraryId))
                .filter(filter)
                .toList();
    }

    private List<ILoggingEvent> logged(long libraryId, String message) {
        return logged(libraryId, event -> event.getFormattedMessage().equals(message));
    }

    private List<ILoggingEvent> errors(long libraryId) {
        return logged(libraryId, event -> event.getLevel() == Level.ERROR);
    }

    private void await(long libraryId, String message) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (logged(libraryId, message).isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(logged(libraryId, message)).isNotEmpty();
    }

    /** The scan thread writes this last however the scan ended, so it also shows nothing escaped it. */
    private void awaitScanEnd(long libraryId) throws InterruptedException {
        await(libraryId, COMPLETED);
        assertThat(logged(libraryId, COMPLETED)).hasSize(1);
    }

    private List<String> notified() {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(notificationService, atLeastOnce()).sendMessage(eq(Topic.LOG), captor.capture());
        return captor.getAllValues().stream()
                .map(LogNotification.class::cast)
                .map(n -> n.getSeverity() + " " + n.getMessage())
                .toList();
    }

    @Test
    void rescanStoppedBeforeChangingAnythingIsLoggedInOneLineAndToldToTheUser() throws Exception {
        long libraryId = 9101L;
        givenLibrary(libraryId);
        String reason = "Library 'Books' was not rescanned: /books/books could not be read in time; no books were removed.";
        doThrow(new APIException(reason, HttpStatus.SERVICE_UNAVAILABLE)).when(libraryProcessingService).rescanLibrary(any());

        libraryService.rescanLibrary(libraryId);
        awaitScanEnd(libraryId);

        assertThat(errors(libraryId)).singleElement().satisfies(event -> {
            assertThat(event.getFormattedMessage()).isEqualTo(reason);
            assertThat(event.getThrowableProxy()).isNull();
        });
        assertThat(notified()).containsExactly("ERROR " + reason);
        assertThat(LibraryService.isLibraryScanning(libraryId)).isFalse();
    }

    @Test
    void rescanThatFailsIsLoggedWithItsCauseAndToldToTheUser() throws Exception {
        long libraryId = 9102L;
        givenLibrary(libraryId);
        doThrow(new IOException("Host is down")).when(libraryProcessingService).rescanLibrary(any());

        libraryService.rescanLibrary(libraryId);
        awaitScanEnd(libraryId);

        assertThat(errors(libraryId)).singleElement().satisfies(event -> {
            assertThat(event.getFormattedMessage()).isEqualTo("Failed to refresh library: Books - Host is down");
            assertThat(event.getThrowableProxy()).isNotNull();
        });
        assertThat(notified()).containsExactly("ERROR Failed to refresh library: Books - Host is down");
        assertThat(LibraryService.isLibraryScanning(libraryId)).isFalse();
    }

    @Test
    void rescanThatBreaksUnexpectedlyEndsItsThreadNormally() throws Exception {
        long libraryId = 9103L;
        givenLibrary(libraryId);
        doThrow(new IllegalStateException("Connection pool exhausted")).when(libraryProcessingService).rescanLibrary(any());

        libraryService.rescanLibrary(libraryId);
        awaitScanEnd(libraryId);

        assertThat(errors(libraryId)).singleElement().satisfies(event -> {
            assertThat(event.getFormattedMessage()).isEqualTo("Failed to refresh library: Books - Connection pool exhausted");
            assertThat(event.getThrowableProxy().getClassName()).isEqualTo(IllegalStateException.class.getName());
        });
        assertThat(notified()).containsExactly("ERROR Failed to refresh library: Books - Connection pool exhausted");
        assertThat(LibraryService.isLibraryScanning(libraryId)).isFalse();
    }

    @Test
    void rescanRequestedWhileOneRunsIsSkippedAndTheLibraryIsFreedWhenTheFirstStops() throws Exception {
        long libraryId = 9104L;
        givenLibrary(libraryId);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch stop = new CountDownLatch(1);
        doAnswer(invocation -> {
            started.countDown();
            stop.await();
            throw new APIException("Library 'Books' was not rescanned: /books could not be read; no books were removed.",
                    HttpStatus.SERVICE_UNAVAILABLE);
        }).when(libraryProcessingService).rescanLibrary(any());

        libraryService.rescanLibrary(libraryId);
        assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(LibraryService.isLibraryScanning(libraryId)).isTrue();
        libraryService.rescanLibrary(libraryId);
        await(libraryId, "Library " + libraryId + " is already being scanned, skipping duplicate rescan request");
        assertThat(LibraryService.isLibraryScanning(libraryId)).isTrue();

        stop.countDown();
        awaitScanEnd(libraryId);

        verify(libraryProcessingService, times(1)).rescanLibrary(any());
        assertThat(LibraryService.isLibraryScanning(libraryId)).isFalse();
    }

    @Test
    void initialScanThatFailsIsLoggedWithItsCauseAndToldToTheUser() throws Exception {
        long libraryId = 9105L;
        when(authenticationService.getAuthenticatedUser()).thenReturn(BookLoreUser.builder().id(1L).build());
        when(userRepository.findById(1L)).thenReturn(Optional.of(BookLoreUserEntity.builder().id(1L).build()));
        when(libraryRepository.save(any(LibraryEntity.class))).thenAnswer(invocation -> {
            LibraryEntity entity = invocation.getArgument(0);
            entity.setId(libraryId);
            return entity;
        });
        when(libraryMapper.toLibrary(any(LibraryEntity.class))).thenReturn(Library.builder().name("Books").build());
        doThrow(new IOException("Stale file handle")).when(libraryProcessingService).processLibrary(anyLong());

        libraryService.createLibrary(CreateLibraryRequest.builder().name("Books").paths(Collections.emptyList()).build());
        awaitScanEnd(libraryId);

        assertThat(errors(libraryId)).singleElement().satisfies(event -> {
            assertThat(event.getFormattedMessage()).isEqualTo("Failed to process library: Books - Stale file handle");
            assertThat(event.getThrowableProxy()).isNotNull();
        });
        assertThat(notified()).containsExactly("ERROR Failed to process library: Books - Stale file handle");
        assertThat(LibraryService.isLibraryScanning(libraryId)).isFalse();
    }
}
