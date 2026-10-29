package org.booklore.service.catalog;

import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.*;
import org.booklore.model.dto.Book;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.UserBookFileProgressEntity;
import org.booklore.model.entity.UserBookProgressEntity;
import org.booklore.model.enums.ReadStatus;
import org.booklore.service.book.BookService;
import org.booklore.service.progress.ReadingProgressService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.*;
import java.util.function.Predicate;

/**
 * The dashboard's fixed rows, chosen as {@code main-dashboard.component.ts} chooses them from the streamed list, and
 * returned as the list's own DTOs ({@link BookService#getListViewBooks}).
 *
 * <p>For a user with content restrictions, which are applied in Java when the DTOs load, more ids are chosen than
 * the row shows, and the row is cut after loading.
 */
@Service
public class DashboardRowService {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 50;

    static final int RESTRICTED_FETCH_LIMIT = 250;
    private static final int CHUNK_SIZE = 500;

    /** The statuses the reading and listening rows show. */
    private static final Set<ReadStatus> IN_PROGRESS = EnumSet.of(ReadStatus.READING, ReadStatus.RE_READING, ReadStatus.PAUSED);

    /** The statuses the random row leaves out. */
    private static final Set<ReadStatus> RANDOM_EXCLUDED = EnumSet.of(ReadStatus.READ, ReadStatus.PARTIALLY_READ,
            ReadStatus.READING, ReadStatus.PAUSED, ReadStatus.WONT_READ, ReadStatus.ABANDONED);

    private final EntityManager entityManager;
    private final CatalogViewerResolver viewerResolver;
    private final ReadingProgressService readingProgressService;
    private final BookService bookService;
    private final TransactionTemplate readOnlyTransaction;

    public DashboardRowService(EntityManager entityManager, CatalogViewerResolver viewerResolver,
                               ReadingProgressService readingProgressService, BookService bookService,
                               PlatformTransactionManager transactionManager) {
        this.entityManager = entityManager;
        this.viewerResolver = viewerResolver;
        this.readingProgressService = readingProgressService;
        this.bookService = bookService;
        this.readOnlyTransaction = new TransactionTemplate(transactionManager);
        this.readOnlyTransaction.setReadOnly(true);
    }

    public List<Book> getRow(BookLoreUser user, DashboardRowKind kind, int size) {
        CatalogViewer viewer = viewerResolver.resolve(user);
        if (viewer.seesNoBooks()) {
            return List.of();
        }
        int fetch = viewer.restricted() ? Math.min(size * 5, RESTRICTED_FETCH_LIMIT) : size;
        List<Long> ids = readOnlyTransaction.execute(status -> switch (kind) {
            case CONTINUE_READING -> continueIds(viewer, fetch, DashboardRowService::hasEbookProgress);
            case CONTINUE_LISTENING -> continueIds(viewer, fetch, book -> book.getAudiobookProgress() != null);
            case RECENTLY_ADDED -> recentlyAddedIds(viewer, fetch);
            case RANDOM -> randomIds(viewer, fetch);
        });
        List<Book> books = bookService.getListViewBooks(user, ids);
        return books.size() > size ? books.subList(0, size) : books;
    }

    /**
     * {@code getLastReadBooks} and {@code getLastListenedBooks}: a lastReadTime, an in-progress status and the row's
     * kind of progress, newest first. The status is only ever set from the user's {@code user_book_progress} row, so
     * those rows are the candidates; each is enriched with the stream's own progress code and filtered here. Equal
     * times keep the list's id order, as the stable sort does.
     */
    private List<Long> continueIds(CatalogViewer viewer, int fetch, Predicate<Book> hasRowProgress) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Long> query = cb.createQuery(Long.class);
        Root<UserBookProgressEntity> progress = query.from(UserBookProgressEntity.class);
        Join<UserBookProgressEntity, BookEntity> book = progress.join("book");
        query.select(book.get("id"))
                .where(cb.equal(progress.get("user").get("id"), viewer.userId()),
                        progress.get("readStatus").in(IN_PROGRESS),
                        viewer.visible(cb, book));
        List<Long> candidateIds = entityManager.createQuery(query).getResultList();

        List<Book> matches = new ArrayList<>();
        for (int offset = 0; offset < candidateIds.size(); offset += CHUNK_SIZE) {
            Set<Long> chunk = new HashSet<>(candidateIds.subList(offset, Math.min(offset + CHUNK_SIZE, candidateIds.size())));
            Map<Long, UserBookProgressEntity> progressMap = readingProgressService.fetchUserProgress(viewer.userId(), chunk);
            Map<Long, UserBookFileProgressEntity> fileProgressMap = readingProgressService.fetchUserFileProgress(viewer.userId(), chunk);
            for (Long id : chunk) {
                Book enriched = Book.builder().id(id).build();
                readingProgressService.enrichBookWithProgress(enriched, progressMap.get(id), fileProgressMap.get(id));
                if (enriched.getLastReadTime() != null && isInProgress(enriched.getReadStatus()) && hasRowProgress.test(enriched)) {
                    matches.add(enriched);
                }
            }
        }

        // The UI compares new Date(lastReadTime).getTime(): milliseconds
        matches.sort(Comparator.comparing((Book b) -> b.getLastReadTime().toEpochMilli()).reversed()
                .thenComparing(Book::getId));
        return matches.stream().limit(fetch).map(Book::getId).toList();
    }

    private static boolean isInProgress(String readStatus) {
        return IN_PROGRESS.stream().anyMatch(status -> status.name().equals(readStatus));
    }

    private static boolean hasEbookProgress(Book book) {
        return book.getEpubProgress() != null || book.getPdfProgress() != null || book.getCbxProgress() != null
                || book.getKoreaderProgress() != null || book.getKoboProgress() != null;
    }

    /** {@code getLatestAddedBooks}: books with an addedOn, newest first; ties keep the list's id order. */
    private List<Long> recentlyAddedIds(CatalogViewer viewer, int fetch) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Long> query = cb.createQuery(Long.class);
        Root<BookEntity> book = query.from(BookEntity.class);
        Path<Instant> addedOn = book.get("addedOn");
        query.select(book.get("id"))
                .where(viewer.visible(cb, book), cb.isNotNull(addedOn))
                .orderBy(cb.desc(addedOn), cb.asc(book.get("id")));
        return entityManager.createQuery(query).setMaxResults(fetch).getResultList();
    }

    /** {@code getRandomBooks}: books whose status is not one the row leaves out, a fresh draw each time. */
    private List<Long> randomIds(CatalogViewer viewer, int fetch) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Long> query = cb.createQuery(Long.class);
        Root<BookEntity> book = query.from(BookEntity.class);

        Subquery<Integer> excluded = query.subquery(Integer.class);
        Root<UserBookProgressEntity> progress = excluded.from(UserBookProgressEntity.class);
        excluded.select(cb.literal(1))
                .where(cb.equal(progress.get("book").get("id"), book.get("id")),
                        cb.equal(progress.get("user").get("id"), viewer.userId()),
                        progress.get("readStatus").in(RANDOM_EXCLUDED));

        // Hibernate's rand(), rand() on MariaDB and H2
        query.select(book.get("id"))
                .where(viewer.visible(cb, book), cb.not(cb.exists(excluded)))
                .orderBy(cb.asc(cb.function("rand", Double.class)));
        return entityManager.createQuery(query).setMaxResults(fetch).getResultList();
    }
}
