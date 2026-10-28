package org.booklore.exception;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.catalina.connector.ClientAbortException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.SocketException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GlobalExceptionHandlerClientGoneTest {

    @RestController
    static class FailingController {

        @GetMapping("/font")
        void font(HttpServletResponse response) throws IOException {
            response.setContentType("font/woff2");
            throw new ClientAbortException(new SocketException("Connection reset by peer"));
        }

        @GetMapping("/async-not-usable")
        void asyncNotUsable() throws IOException {
            throw new AsyncRequestNotUsableException("ServletOutputStream failed to flush: Broken pipe");
        }

        @GetMapping("/broken-pipe")
        void brokenPipe(HttpServletResponse response) throws IOException {
            response.setContentType("font/woff2");
            throw new IOException("Broken pipe");
        }

        @GetMapping("/io-wrapping-abort")
        void ioWrappingAbort() throws IOException {
            throw new IOException("write failed", new ClientAbortException("java.io.IOException: Broken pipe"));
        }

        @GetMapping("/unchecked-abort")
        void uncheckedAbort() {
            throw new UncheckedIOException(new ClientAbortException());
        }

        @GetMapping("/disk-full")
        void diskFull() throws IOException {
            throw new IOException("No space left on device");
        }

        @GetMapping("/outbound-reset")
        void outboundReset() {
            throw new RuntimeException("Provider lookup failed", new SocketException("Connection reset"));
        }
    }

    private final Logger handlerLog = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private Level previousLevel;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        previousLevel = handlerLog.getLevel();
        handlerLog.setLevel(Level.DEBUG);
        logs.start();
        handlerLog.addAppender(logs);
        mockMvc = MockMvcBuilders.standaloneSetup(new FailingController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void tearDown() {
        handlerLog.detachAppender(logs);
        handlerLog.setLevel(previousLevel);
    }

    @Test
    void clientAbort_writesNothingAndLogsOneDebugLine() throws Exception {
        mockMvc.perform(get("/font"))
                .andExpect(content().string(""));

        assertOneDebugLine("ClientAbortException");
    }

    @Test
    void asyncRequestNotUsable_writesNothing() throws Exception {
        mockMvc.perform(get("/async-not-usable"))
                .andExpect(content().string(""));

        assertOneDebugLine("AsyncRequestNotUsableException");
    }

    @Test
    void brokenPipeIOException_writesNothing() throws Exception {
        mockMvc.perform(get("/broken-pipe"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().string(""));

        assertOneDebugLine("Broken pipe");
    }

    @Test
    void ioExceptionCausedByClientAbort_writesNothing() throws Exception {
        mockMvc.perform(get("/io-wrapping-abort"))
                .andExpect(content().string(""));

        assertOneDebugLine("write failed <- ClientAbortException");
    }

    @Test
    void uncheckedWrapperOfClientAbort_writesNothing() throws Exception {
        mockMvc.perform(get("/unchecked-abort"))
                .andExpect(content().string(""));

        assertOneDebugLine("UncheckedIOException");
    }

    @Test
    void otherIOException_isStillAServerError() throws Exception {
        mockMvc.perform(get("/disk-full"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("An unexpected error occurred."));

        assertThat(logs.list).extracting(ILoggingEvent::getLevel).containsExactly(Level.ERROR);
    }

    @Test
    void connectionResetFromAnOutboundCall_isStillAServerError() throws Exception {
        mockMvc.perform(get("/outbound-reset"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("An unexpected error occurred."));

        assertThat(logs.list).extracting(ILoggingEvent::getLevel).containsExactly(Level.ERROR);
    }

    @Test
    void isClientGone_matchesMessagesOnlyWhenAsked() {
        IOException reset = new IOException("Connection reset by peer");

        assertThat(GlobalExceptionHandler.isClientGone(reset, true)).isTrue();
        assertThat(GlobalExceptionHandler.isClientGone(reset, false)).isFalse();
        assertThat(GlobalExceptionHandler.isClientGone(new RuntimeException(new ClientAbortException()), false)).isTrue();
        assertThat(GlobalExceptionHandler.isClientGone(new IOException("Permission denied"), true)).isFalse();
        assertThat(GlobalExceptionHandler.isClientGone(new IllegalArgumentException("Broken pipe"), true)).isFalse();
    }

    private void assertOneDebugLine(String expectedText) {
        assertThat(logs.list).hasSize(1);
        ILoggingEvent event = logs.list.getFirst();
        assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
        assertThat(event.getFormattedMessage()).contains(expectedText).doesNotContain("\n");
        assertThat(event.getThrowableProxy()).isNull();
    }
}
