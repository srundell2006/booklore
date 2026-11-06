package org.booklore.util;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.function.Supplier;

/**
 * Runs work on a background thread with one open persistence context for its whole length, as Spring's open-in-view
 * gives every HTTP request. Code written for a request can then read the lazy associations of entities it loaded
 * earlier, between the transactions of its repository and service calls; on a plain thread each of those calls
 * gets a persistence context of its own, and such a read fails with a LazyInitializationException once it has closed.
 * <p>
 * Does what OpenEntityManagerInViewInterceptor does around a request: binds a new EntityManager to the thread, and
 * unbinds and closes it afterwards. Where one is bound already (a request, or nested use) the work just runs.
 */
@Component
@RequiredArgsConstructor
public class OpenEntityManagerRunner {

    private final EntityManagerFactory entityManagerFactory;

    public <T> T call(Supplier<T> work) {
        if (TransactionSynchronizationManager.hasResource(entityManagerFactory)) {
            return work.get();
        }
        EntityManager entityManager = entityManagerFactory.createEntityManager();
        TransactionSynchronizationManager.bindResource(entityManagerFactory, new EntityManagerHolder(entityManager));
        try {
            return work.get();
        } finally {
            TransactionSynchronizationManager.unbindResource(entityManagerFactory);
            EntityManagerFactoryUtils.closeEntityManager(entityManager);
        }
    }

    public void run(Runnable work) {
        call(() -> {
            work.run();
            return null;
        });
    }
}
