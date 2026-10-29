package org.booklore.service.catalog;

import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.*;
import org.booklore.exception.ApiError;
import org.booklore.model.dto.Book;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.catalog.SeriesBooks;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.service.book.BookService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.booklore.service.catalog.CatalogSql.*;

/**
 * The books of one series, as {@code series-page.component.ts} picks them from the streamed list, returned as the
 * list's own DTOs ({@link BookService#getListViewBooks}).
 *
 * <p>Content restrictions are applied in Java when the DTOs load, after the ids are chosen. A restricted user's page
 * can then hold fewer books than the limit, and when more books than the limit match before the restrictions, the
 * total is an upper bound: of the hidden books it leaves out only those among the loaded ids.
 */
@Service
public class SeriesBooksService {

    public static final int DEFAULT_LIMIT = 1000;
    public static final int MAX_LIMIT = 5000;

    private final EntityManager entityManager;
    private final CatalogViewerResolver viewerResolver;
    private final BookService bookService;
    private final TransactionTemplate readOnlyTransaction;

    public SeriesBooksService(EntityManager entityManager, CatalogViewerResolver viewerResolver, BookService bookService,
                              PlatformTransactionManager transactionManager) {
        this.entityManager = entityManager;
        this.viewerResolver = viewerResolver;
        this.bookService = bookService;
        this.readOnlyTransaction = new TransactionTemplate(transactionManager);
        this.readOnlyTransaction.setReadOnly(true);
    }

    private record Matches(List<Long> ids, long total) {
    }

    /** The first {@code limit} books of the series, and how many there are. A blank name is a bad request. */
    public SeriesBooks getSeriesBooks(BookLoreUser user, String name, int limit) {
        String key = name == null ? "" : JsSemantics.lower(JsSemantics.trim(name));
        if (key.isEmpty()) {
            throw ApiError.GENERIC_BAD_REQUEST.createException("A series name is required");
        }
        CatalogViewer viewer = viewerResolver.resolve(user);
        if (viewer.seesNoBooks()) {
            return new SeriesBooks(name, 0, List.of());
        }

        Matches matches = readOnlyTransaction.execute(status -> {
            List<Long> ids = entityManager.createQuery(idsQuery(viewer, key)).setMaxResults(limit).getResultList();
            return new Matches(ids, ids.size() < limit ? ids.size() : countMatching(viewer, key));
        });
        List<Book> books = bookService.getListViewBooks(user, matches.ids());
        long hidden = matches.ids().size() - books.size();
        String seriesName = books.isEmpty() ? name : books.getFirst().getMetadata().getSeriesName();
        return new SeriesBooks(seriesName, matches.total() - hidden, books);
    }

    /**
     * The matching ids in the page's order. It sorts by {@code seriesNumber ?? Number.MAX_SAFE_INTEGER}, stable over
     * the list's id order: numbered books first, then the unnumbered, ties by id.
     */
    CriteriaQuery<Long> idsQuery(CatalogViewer viewer, String key) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Long> query = cb.createQuery(Long.class);
        Root<BookEntity> book = query.from(BookEntity.class);
        Join<BookEntity, BookMetadataEntity> metadata = book.join("metadata");
        Path<Float> seriesNumber = metadata.get("seriesNumber");
        query.select(book.get("id"))
                .where(inSeries(cb, viewer, book, metadata, key))
                .orderBy(cb.asc(cb.<Integer>selectCase().when(cb.isNull(seriesNumber), 1).otherwise(0)),
                        cb.asc(seriesNumber),
                        cb.asc(book.get("id")));
        return query;
    }

    private long countMatching(CatalogViewer viewer, String key) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Long> query = cb.createQuery(Long.class);
        Root<BookEntity> book = query.from(BookEntity.class);
        query.select(cb.count(book)).where(inSeries(cb, viewer, book, book.join("metadata"), key));
        return entityManager.createQuery(query).getSingleResult();
    }

    /**
     * {@code b.metadata?.seriesName?.trim().toLowerCase() === key}, where the key is the requested name trimmed and
     * lower-cased the same way, in Java with {@link java.util.Locale#ROOT}. Books without a metadata row never match.
     * The trim is {@link CatalogSql#jsTrim} and the comparison a LIKE without wildcards, so that MariaDB's PAD SPACE
     * does not take {@code "x "} for {@code "x"}.
     *
     * <p>The trim reads every row, so a cheaper test goes first: the key must appear in the lower-cased name as it is
     * stored. A matching name is the key give or take whitespace at either end, and lower-casing goes character by
     * character, so every match passes it. A prefix test would not be safe: a stored name can begin with whitespace
     * that Java's trim keeps (a no-break space, a byte order mark) or that was written past the entity.
     *
     * <p>On MariaDB the comparison goes through the column collation, which also folds accents, as in the series
     * count; accepted.
     */
    private static Predicate[] inSeries(CriteriaBuilder cb, CatalogViewer viewer, Path<BookEntity> book,
                                        Join<BookEntity, BookMetadataEntity> metadata, String key) {
        Expression<String> seriesName = metadata.get("seriesName");
        return new Predicate[]{
                viewer.visible(cb, book),
                cb.like(cb.lower(seriesName), "%" + escapeLike(key) + "%", LIKE_ESCAPE),
                matchesExactly(cb, cb.lower(jsTrim(cb, seriesName)), key)
        };
    }
}
