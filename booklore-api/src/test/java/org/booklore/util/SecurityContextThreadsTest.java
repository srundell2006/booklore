package org.booklore.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class SecurityContextThreadsTest {

    private Authentication originalAuth;
    private SecurityContext originalContext;

    @BeforeEach
    void setUp() {
        originalAuth = new UsernamePasswordAuthenticationToken("testUser", "password", null);
        originalContext = new SecurityContextImpl(originalAuth);
        SecurityContextHolder.setContext(originalContext);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void start_runsTheTaskOnANamedPlatformDaemonThread() throws Exception {
        CompletableFuture<Thread> ranOn = new CompletableFuture<>();

        Thread thread = SecurityContextThreads.start("library-scan-7", () -> ranOn.complete(Thread.currentThread()));

        assertSame(thread, ranOn.get(5, TimeUnit.SECONDS));
        assertFalse(thread.isVirtual());
        assertTrue(thread.isDaemon());
        assertEquals("library-scan-7", thread.getName());
        thread.join(5000);
        assertFalse(thread.isAlive());
    }

    @Test
    void start_withProvidedContext_runsTheTaskOnANamedPlatformDaemonThread() throws Exception {
        CompletableFuture<Thread> ranOn = new CompletableFuture<>();

        Thread thread = SecurityContextThreads.start("task-MISSING_FILE_SCAN", originalContext,
                () -> ranOn.complete(Thread.currentThread()));

        assertSame(thread, ranOn.get(5, TimeUnit.SECONDS));
        assertFalse(thread.isVirtual());
        assertTrue(thread.isDaemon());
        assertEquals("task-MISSING_FILE_SCAN", thread.getName());
    }

    @Test
    void runAs_setsTheSecurityContextForTheTaskAndClearsItAfterwards() {
        Authentication otherAuth = new UsernamePasswordAuthenticationToken("otherUser", "password", null);
        AtomicReference<Authentication> capturedAuth = new AtomicReference<>();

        SecurityContextThreads.runAs(otherAuth, () -> capturedAuth.set(SecurityContextHolder.getContext().getAuthentication()));

        assertSame(otherAuth, capturedAuth.get());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void runAs_clearsTheSecurityContextWhenTheTaskThrows() {
        Authentication otherAuth = new UsernamePasswordAuthenticationToken("otherUser", "password", null);

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> SecurityContextThreads.runAs(otherAuth, () -> {
                    throw new RuntimeException("Test exception");
                }));

        assertEquals("Test exception", thrown.getMessage());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void testRunWithSecurityContext_executesRunnable() throws ExecutionException, InterruptedException {
        AtomicReference<String> result = new AtomicReference<>();

        CompletableFuture<Void> future = new CompletableFuture<>();
        SecurityContextThreads.start("test", () -> {
            try {
                result.set("executed");
                future.complete(null);
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });

        // Wait for the thread to complete
        future.get();
        assertEquals("executed", result.get());
    }

    @Test
    void testRunWithSecurityContext_preservesSecurityContext() throws ExecutionException, InterruptedException {
        AtomicReference<Authentication> capturedAuth = new AtomicReference<>();

        CompletableFuture<Void> future = new CompletableFuture<>();
        SecurityContextThreads.start("test", () -> {
            try {
                capturedAuth.set(SecurityContextHolder.getContext().getAuthentication());
                future.complete(null);
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });

        future.get();
        assertNotNull(capturedAuth.get());
        assertEquals(originalAuth.getName(), capturedAuth.get().getName());
    }

    @Test
    void testRunWithSecurityContext_maintainsMainThreadContext() throws ExecutionException, InterruptedException {
        CompletableFuture<Void> future = new CompletableFuture<>();
        SecurityContextThreads.start("test", () -> {
            try {
                // Context should be set here
                assertNotNull(SecurityContextHolder.getContext().getAuthentication());
                future.complete(null);
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });

        future.get();

        // After the thread completes, the main thread's context should still be intact
        Authentication mainThreadAuth = SecurityContextHolder.getContext().getAuthentication();
        assertNotNull(mainThreadAuth);
        assertEquals(originalAuth.getName(), mainThreadAuth.getName());
    }

    @Test
    void testRunWithSecurityContext_withProvidedContext() throws ExecutionException, InterruptedException {
        Authentication differentAuth = new UsernamePasswordAuthenticationToken("differentUser", "password", null);
        SecurityContext differentContext = new SecurityContextImpl(differentAuth);

        AtomicReference<Authentication> capturedAuth = new AtomicReference<>();

        CompletableFuture<Void> future = new CompletableFuture<>();
        SecurityContextThreads.start("test", differentContext, () -> {
            try {
                capturedAuth.set(SecurityContextHolder.getContext().getAuthentication());
                future.complete(null);
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });

        future.get();
        assertNotNull(capturedAuth.get());
        assertEquals("differentUser", capturedAuth.get().getName());
    }

    @Test
    void testRunWithSecurityContext_withProvidedContext_clearsAfterExecution() throws ExecutionException, InterruptedException {
        Authentication differentAuth = new UsernamePasswordAuthenticationToken("differentUser", "password", null);
        SecurityContext differentContext = new SecurityContextImpl(differentAuth);

        CompletableFuture<Void> future = new CompletableFuture<>();
        SecurityContextThreads.start("test", differentContext, () -> {
            try {
                // Different context should be set here
                assertEquals("differentUser", SecurityContextHolder.getContext().getAuthentication().getName());
                future.complete(null);
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });

        future.get();

        // Main thread should still have original context
        Authentication mainThreadAuth = SecurityContextHolder.getContext().getAuthentication();
        assertEquals("testUser", mainThreadAuth.getName());
    }

    @Test
    void testRunWithSecurityContext_handlesException() throws ExecutionException, InterruptedException {
        CompletableFuture<Exception> future = new CompletableFuture<>();

        SecurityContextThreads.start("test", () -> {
            try {
                throw new RuntimeException("Test exception");
            } catch (Exception e) {
                future.complete(e);
                throw e;
            }
        });

        Exception capturedException = future.get();
        assertNotNull(capturedException);
        assertEquals("Test exception", capturedException.getMessage());
    }
}
