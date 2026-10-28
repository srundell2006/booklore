package org.booklore.service.catalog;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.*;
import lombok.RequiredArgsConstructor;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.catalog.CatalogCounts;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.entity.ShelfEntity;
import org.booklore.repository.ShelfRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * The sidebar counts, as the UI computes them from the streamed list ({@code app.menu.component.ts},
 * {@code library.service.ts}, {@code shelf.service.ts}, {@code series-data.service.ts}), counted in SQL over the
 * books {@link CatalogViewer#visible} selects. Fileless books and books without a metadata row count like any other:
 * no query joins {@code book_file}, and the series query is the only one that reads metadata.
 */
@Service
@RequiredArgsConstructor
public class CatalogCountService {

    private final EntityManager entityManager;
    private final ShelfRepository shelfRepository;
    private final CatalogViewerResolver viewerResolver;

    @Transactional(readOnly = true)
    public CatalogCounts getCounts(BookLoreUser user) {
        return getCounts(viewerResolver.resolve(user));
    }

    @Transactional(readOnly = true)
    public CatalogCounts getCounts(CatalogViewer viewer) {
        if (viewer.restricted()) {
            return CatalogCounts.computedOnClient();
        }

        List<Long> ownShelfIds = shelfRepository.findByUserId(viewer.userId()).stream()
                .map(ShelfEntity::getId)
                .sorted()
                .toList();

        if (viewer.seesNoBooks()) {
            return new CatalogCounts(false, 0L, 0L, 0L, Map.of(), zeros(ownShelfIds));
        }

        Map<Long, Long> libraries = zeros(visibleLibraryIds(viewer));
        countByLibrary(viewer).forEach(libraries::put);
        long allBooks = libraries.values().stream().mapToLong(Long::longValue).sum();

        Map<Long, Long> shelves = zeros(ownShelfIds);
        if (!ownShelfIds.isEmpty()) {
            countByOwnShelf(viewer).forEach((shelfId, count) -> {
                if (shelves.containsKey(shelfId)) shelves.put(shelfId, count);
            });
        }

        return new CatalogCounts(false, allBooks, countSeries(viewer),
                ownShelfIds.isEmpty() ? allBooks : countUnshelved(viewer), libraries, shelves);
    }

    private List<Long> visibleLibraryIds(CatalogViewer viewer) {
        if (!viewer.admin()) {
            return viewer.libraryIds().stream().sorted().toList();
        }
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Long> query = cb.createQuery(Long.class);
        Root<LibraryEntity> library = query.from(LibraryEntity.class);
        query.select(library.get("id")).orderBy(cb.asc(library.get("id")));
        return entityManager.createQuery(query).getResultList();
    }

    /** {@code filter(b => b.libraryId === id).length} for each library at once. */
    private Map<Long, Long> countByLibrary(CatalogViewer viewer) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Tuple> query = cb.createTupleQuery();
        Root<BookEntity> book = query.from(BookEntity.class);
        Path<Long> libraryId = book.get("library").get("id");
        query.multiselect(libraryId, cb.count(book))
                .where(viewer.visible(cb, book))
                .groupBy(libraryId);
        return toMap(entityManager.createQuery(query).getResultList());
    }

    /**
     * The UI groups books by {@code seriesName.trim().toLowerCase()} and skips a missing or empty name. A
     * whitespace-only name is not skipped there, so the length test reads the raw column: on MariaDB {@code <> ''}
     * would drop it (PAD SPACE). The collation also folds accents in the DISTINCT, which the UI does not; accepted.
     */
    private long countSeries(CatalogViewer viewer) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Long> query = cb.createQuery(Long.class);
        Root<BookEntity> book = query.from(BookEntity.class);
        Join<BookEntity, BookMetadataEntity> metadata = book.join("metadata");
        Expression<String> seriesName = metadata.get("seriesName");
        query.select(cb.countDistinct(cb.lower(cb.trim(seriesName))))
                .where(viewer.visible(cb, book),
                        cb.isNotNull(seriesName),
                        cb.greaterThan(cb.length(seriesName), 0));
        return entityManager.createQuery(query).getSingleResult();
    }

    /** The owner's view of a shelf: the books whose (own-shelf-only) shelves include it. */
    private Map<Long, Long> countByOwnShelf(CatalogViewer viewer) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Tuple> query = cb.createTupleQuery();
        Root<BookEntity> book = query.from(BookEntity.class);
        Join<BookEntity, ShelfEntity> shelf = book.join("shelves");
        Path<Long> shelfId = shelf.get("id");
        query.multiselect(shelfId, cb.count(book))
                .where(viewer.visible(cb, book), cb.equal(shelf.get("user").get("id"), viewer.userId()))
                .groupBy(shelfId);
        return toMap(entityManager.createQuery(query).getResultList());
    }

    /** {@code !b.shelves || b.shelves.length === 0}, where a book's shelves are only the viewer's own. */
    private long countUnshelved(CatalogViewer viewer) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Long> query = cb.createQuery(Long.class);
        Root<BookEntity> book = query.from(BookEntity.class);

        Subquery<Integer> onOwnShelf = query.subquery(Integer.class);
        Root<ShelfEntity> shelf = onOwnShelf.from(ShelfEntity.class);
        Join<ShelfEntity, BookEntity> shelved = shelf.join("bookEntities");
        onOwnShelf.select(cb.literal(1))
                .where(cb.equal(shelved.get("id"), book.get("id")),
                        cb.equal(shelf.get("user").get("id"), viewer.userId()));

        query.select(cb.count(book)).where(viewer.visible(cb, book), cb.not(cb.exists(onOwnShelf)));
        return entityManager.createQuery(query).getSingleResult();
    }

    private static Map<Long, Long> zeros(Collection<Long> ids) {
        Map<Long, Long> counts = new LinkedHashMap<>();
        ids.forEach(id -> counts.put(id, 0L));
        return counts;
    }

    private static Map<Long, Long> toMap(List<Tuple> rows) {
        Map<Long, Long> counts = new HashMap<>();
        for (Tuple row : rows) {
            counts.put(row.get(0, Long.class), row.get(1, Long.class));
        }
        return counts;
    }
}
