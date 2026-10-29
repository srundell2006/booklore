package org.booklore.service.catalog;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;
import lombok.extern.slf4j.Slf4j;
import org.booklore.mapper.v2.PrimaryBookFile;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.MagicShelf;
import org.booklore.model.dto.catalog.MagicShelfCount;
import org.booklore.model.dto.catalog.MagicShelfCounts;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.service.MagicShelfService;
import org.booklore.service.catalog.MagicShelfCountCompiler.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * The count of every magic shelf the user lists, as the UI computes it over the streamed list, for the shelves
 * {@link MagicShelfCountCompiler} supports. Shelves are counted one after another, each in its own read-only
 * transaction, so one failing query leaves the others to complete.
 */
@Slf4j
@Service
public class MagicShelfCountService {

    /** The most SQL matches a fileType post-filter loads files for; above it the shelf is left to the client. */
    static final int FILE_TYPE_CANDIDATE_LIMIT = 5000;

    private static final String QUERY_TIMEOUT_HINT = "jakarta.persistence.query.timeout";
    private static final int QUERY_TIMEOUT_MS = 3000;
    private static final long SLOW_SHELF_MS = 500;
    private static final int IN_CHUNK_SIZE = 1000;

    private final EntityManager entityManager;
    private final MagicShelfCountCompiler compiler;
    private final MagicShelfService magicShelfService;
    private final CatalogViewerResolver viewerResolver;
    private final TransactionTemplate readOnlyTransaction;

    public MagicShelfCountService(EntityManager entityManager, MagicShelfCountCompiler compiler,
                                  MagicShelfService magicShelfService, CatalogViewerResolver viewerResolver,
                                  PlatformTransactionManager transactionManager) {
        this.entityManager = entityManager;
        this.compiler = compiler;
        this.magicShelfService = magicShelfService;
        this.viewerResolver = viewerResolver;
        this.readOnlyTransaction = new TransactionTemplate(transactionManager);
        this.readOnlyTransaction.setReadOnly(true);
    }

    public MagicShelfCounts getCounts(BookLoreUser user) {
        CatalogViewer viewer = viewerResolver.resolve(user);
        Instant now = Instant.now();
        Map<Long, MagicShelfCount> counts = new LinkedHashMap<>();
        for (MagicShelf shelf : magicShelfService.getShelvesForUser(user.getId())) {
            counts.put(shelf.getId(), countShelf(viewer, shelf.getId(), shelf.getFilterJson(), now));
        }
        return new MagicShelfCounts(counts);
    }

    MagicShelfCount countShelf(CatalogViewer viewer, Long shelfId, String filterJson, Instant now) {
        if (viewer.restricted()) {
            return MagicShelfCount.unsupported(List.of("restrictions"));
        }
        Compiled compiled = compiler.compile(filterJson);
        if (compiled instanceof Invalid) {
            return MagicShelfCount.invalid();
        }
        if (viewer.seesNoBooks()) {
            // The UI filters an empty list, so it evaluates no rule and shows 0 whatever the shelf holds
            return MagicShelfCount.ok(0);
        }
        if (compiled instanceof Unsupported unsupported) {
            return MagicShelfCount.unsupported(unsupported.reasons());
        }
        Supported supported = (Supported) compiled;

        long started = System.nanoTime();
        try {
            return readOnlyTransaction.execute(status -> count(viewer, supported, now));
        } catch (RuntimeException e) {
            log.warn("Counting magic shelf {} failed: {}", shelfId, e.toString());
            return MagicShelfCount.error();
        } finally {
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            if (elapsedMs > SLOW_SHELF_MS) {
                log.info("Counting magic shelf {} took {} ms", shelfId, elapsedMs);
            }
        }
    }

    MagicShelfCount count(CatalogViewer viewer, Supported compiled, Instant now) {
        if (!compiled.needsFileTypeFilter()) {
            CriteriaBuilder cb = entityManager.getCriteriaBuilder();
            CriteriaQuery<Long> query = cb.createQuery(Long.class);
            Root<BookEntity> book = query.from(BookEntity.class);
            RuleContext ctx = new RuleContext(cb, query, book, viewer.userId(), now);
            query.select(cb.count(book))
                    .where(viewer.visible(cb, book), compiler.toPredicate(compiled, ctx));
            return MagicShelfCount.ok(withTimeout(entityManager.createQuery(query)).getSingleResult());
        }
        List<Long> matches = matchingIds(viewer, compiled, now);
        return matches == null
                ? MagicShelfCount.unsupported(compiled.fileTypeReasons())
                : MagicShelfCount.ok(matches.size());
    }

    /**
     * The ids of the books on the shelf, ascending, or null when a fileType post-filter would have to load more
     * than {@link #FILE_TYPE_CANDIDATE_LIMIT} candidates.
     */
    List<Long> matchingIds(CatalogViewer viewer, Supported compiled, Instant now) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Tuple> query = cb.createTupleQuery();
        Root<BookEntity> book = query.from(BookEntity.class);
        RuleContext ctx = new RuleContext(cb, query, book, viewer.userId(), now);
        query.multiselect(book.get("id"), book.get("library").get("id"))
                .where(viewer.visible(cb, book), compiler.toPredicate(compiled, ctx))
                .orderBy(cb.asc(book.get("id")));
        TypedQuery<Tuple> typed = withTimeout(entityManager.createQuery(query));

        if (!compiled.needsFileTypeFilter()) {
            return typed.getResultList().stream().map(row -> row.get(0, Long.class)).toList();
        }

        List<Tuple> candidates = typed.setMaxResults(FILE_TYPE_CANDIDATE_LIMIT + 1).getResultList();
        if (candidates.size() > FILE_TYPE_CANDIDATE_LIMIT) {
            return null;
        }
        Map<Long, String> primaryTypes = primaryBookTypes(candidates);
        List<Long> matches = new ArrayList<>();
        for (Tuple candidate : candidates) {
            Long bookId = candidate.get(0, Long.class);
            String primaryType = primaryTypes.get(bookId);
            if (compiled.fileTypeTests().stream().allMatch(test -> test.test(primaryType))) {
                matches.add(bookId);
            }
        }
        return matches;
    }

    private record FileRow(boolean isBookFormat, BookFileType bookType) {
    }

    /**
     * The lower-cased {@code bookType} of each candidate's primary file, picked as the list's mapper picks it, from
     * the file rows alone. Books without a book-format file are absent (no primary file).
     */
    private Map<Long, String> primaryBookTypes(List<Tuple> candidates) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();

        CriteriaQuery<Tuple> libraryQuery = cb.createTupleQuery();
        Root<LibraryEntity> library = libraryQuery.from(LibraryEntity.class);
        libraryQuery.multiselect(library.get("id"), library.get("formatPriority"));
        Map<Long, List<BookFileType>> formatPriorities = new HashMap<>();
        for (Tuple row : withTimeout(entityManager.createQuery(libraryQuery)).getResultList()) {
            @SuppressWarnings("unchecked")
            List<BookFileType> priority = (List<BookFileType>) row.get(1);
            formatPriorities.put(row.get(0, Long.class), priority);
        }

        List<Long> bookIds = candidates.stream().map(row -> row.get(0, Long.class)).toList();
        Map<Long, List<FileRow>> filesByBook = new HashMap<>();
        for (int offset = 0; offset < bookIds.size(); offset += IN_CHUNK_SIZE) {
            List<Long> chunk = bookIds.subList(offset, Math.min(offset + IN_CHUNK_SIZE, bookIds.size()));
            CriteriaQuery<Tuple> fileQuery = cb.createTupleQuery();
            Root<BookFileEntity> file = fileQuery.from(BookFileEntity.class);
            fileQuery.multiselect(file.get("book").get("id"), file.get("isBookFormat"), file.get("bookType"))
                    .where(file.get("book").get("id").in(chunk))
                    .orderBy(cb.asc(file.get("id")));
            for (Tuple row : withTimeout(entityManager.createQuery(fileQuery)).getResultList()) {
                filesByBook.computeIfAbsent(row.get(0, Long.class), id -> new ArrayList<>())
                        .add(new FileRow(row.get(1, Boolean.class), row.get(2, BookFileType.class)));
            }
        }

        Map<Long, String> primaryTypes = new HashMap<>();
        for (Tuple candidate : candidates) {
            Long bookId = candidate.get(0, Long.class);
            FileRow primary = PrimaryBookFile.select(filesByBook.get(bookId),
                    formatPriorities.get(candidate.get(1, Long.class)), FileRow::isBookFormat, FileRow::bookType);
            if (primary != null) {
                primaryTypes.put(bookId, primary.bookType().name().toLowerCase(Locale.ROOT));
            }
        }
        return primaryTypes;
    }

    private static <T> TypedQuery<T> withTimeout(TypedQuery<T> query) {
        return query.setHint(QUERY_TIMEOUT_HINT, QUERY_TIMEOUT_MS);
    }
}
