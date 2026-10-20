package org.booklore.util;

import lombok.experimental.UtilityClass;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Starts a background job on a thread of its own, running as the user who asked for it.
 *
 * <p>The threads are named platform daemon threads, not virtual threads: the jobs started
 * here read and write library storage, and on JDK 25 a virtual thread blocked in a file call
 * keeps its carrier. A few jobs wedged on a network share would then stall every virtual
 * thread in the application, request handling included.
 */
@UtilityClass
public class SecurityContextThreads {

    /** Starts {@code task} on a new thread called {@code name}, as the current thread's user. */
    public Thread start(String name, Runnable task) {
        Authentication currentAuth = SecurityContextHolder.getContext().getAuthentication();
        return startThread(name, () -> runAs(currentAuth, task));
    }

    /** Starts {@code task} on a new thread called {@code name}, as the user of {@code parentContext}. */
    public Thread start(String name, SecurityContext parentContext, Runnable task) {
        return startThread(name, () -> runAs(parentContext.getAuthentication(), task));
    }

    /** Runs {@code task} on the current thread as {@code authentication}, clearing the security context afterwards. */
    void runAs(Authentication authentication, Runnable task) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        try {
            task.run();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private Thread startThread(String name, Runnable task) {
        return Thread.ofPlatform().daemon(true).name(name).start(task);
    }
}
