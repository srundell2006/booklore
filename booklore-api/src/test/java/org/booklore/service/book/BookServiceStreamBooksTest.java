package org.booklore.service.book;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.apache.catalina.connector.ClientAbortException;
import org.booklore.model.dto.Book;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.service.progress.ReadingProgressService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.Set;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * The one INFO line a finished catalogue stream logs, and the WARN line when it ends early.
 */
@ExtendWith(MockitoExtension.class)
class BookServiceStreamBooksTest {

    @Mock
    private BookQueryService bookQueryService;
    @Mock
    private ReadingProgressService readingProgressService;
    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private BookService bookService;

    private final Logger serviceLog = (Logger) LoggerFactory.getLogger(BookService.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private BookLoreUser admin;
    private Level previousLevel;

    @BeforeEach
    void setUp() {
        previousLevel = serviceLog.getLevel();
        serviceLog.setLevel(Level.INFO);
        logs.start();
        serviceLog.addAppender(logs);

        BookLoreUser.UserPermissions permissions = new BookLoreUser.UserPermissions();
        permissions.setAdmin(true);
        admin = BookLoreUser.builder().id(1L).permissions(permissions).assignedLibraries(List.of()).build();
    }

    @AfterEach
    void tearDown() {
        serviceLog.detachAppender(logs);
        serviceLog.setLevel(previousLevel);
    }

    @Test
    void finishedStream_logsBooksChunksAndElapsedAtInfo() throws IOException {
        when(bookQueryService.getAllBookIdsForStreaming()).thenReturn(ids(501));
        when(bookQueryService.getListViewChunk(anySet(), anyBoolean(), anyLong(), anyBoolean()))
                .thenAnswer(inv -> books(inv.getArgument(0)));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bookService.streamBooks(admin, false, out);

        assertThat(out.toString()).startsWith("[{").endsWith("}]");
        assertThat(logged(Level.INFO)).singleElement().asString()
                .matches("Streamed 501 books in 2 chunks for user 1 in \\d+ ms");
        assertThat(logged(Level.WARN)).isEmpty();
    }

    @Test
    void failedChunk_logsProgressSoFarAtWarnAndRethrows() {
        when(bookQueryService.getAllBookIdsForStreaming()).thenReturn(ids(1200));
        when(bookQueryService.getListViewChunk(anySet(), anyBoolean(), anyLong(), anyBoolean()))
                .thenAnswer(inv -> books(inv.getArgument(0)))
                .thenAnswer(inv -> books(inv.getArgument(0)))
                .thenThrow(new IllegalStateException("database went away"));

        assertThatThrownBy(() -> bookService.streamBooks(admin, false, new ByteArrayOutputStream()))
                .isInstanceOf(IllegalStateException.class);

        assertThat(logged(Level.WARN)).singleElement().asString()
                .matches("Book stream for user 1 ended early after 1000 of 1200 books in 2 chunks, \\d+ ms: "
                        + "java.lang.IllegalStateException: database went away");
        assertThat(logged(Level.INFO)).isEmpty();
    }

    @Test
    void clientAbort_logsProgressSoFarAtWarnAndRethrows() {
        when(bookQueryService.getAllBookIdsForStreaming()).thenReturn(ids(3));
        when(bookQueryService.getListViewChunk(anySet(), anyBoolean(), anyLong(), anyBoolean()))
                .thenAnswer(inv -> books(inv.getArgument(0)));
        OutputStream goneClient = new OutputStream() {
            @Override
            public void write(int b) {
            }

            @Override
            public void flush() throws IOException {
                throw new ClientAbortException("java.io.IOException: Broken pipe");
            }
        };

        // Jackson flushes after each value and wraps the abort in its own unchecked exception.
        assertThatThrownBy(() -> bookService.streamBooks(admin, false, goneClient))
                .satisfiesAnyOf(
                        e -> assertThat(e).isInstanceOf(ClientAbortException.class),
                        e -> assertThat(e).hasCauseInstanceOf(ClientAbortException.class));

        assertThat(logged(Level.WARN)).singleElement().asString()
                .matches("Book stream for user 1 ended early after [0-3] of 3 books in 0 chunks, \\d+ ms: "
                        + "org.apache.catalina.connector.ClientAbortException: java.io.IOException: Broken pipe");
    }

    private List<String> logged(Level level) {
        return logs.list.stream()
                .filter(event -> event.getLevel() == level)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    private static List<Long> ids(int count) {
        return LongStream.rangeClosed(1, count).boxed().toList();
    }

    private static List<Book> books(Set<Long> ids) {
        return ids.stream().map(id -> Book.builder().id(id).build()).toList();
    }
}
