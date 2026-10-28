package org.booklore.service.catalog;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import org.booklore.model.entity.BookEntity;

import java.util.Set;

/**
 * Who asks, reduced to what decides which books their list holds.
 *
 * @param libraryIds the assigned libraries of a non-admin; empty for an admin, who sees every library
 * @param restricted a non-admin with at least one content restriction. Restrictions are applied in Java when the
 *                   list is loaded, so the catalog leaves such a user's counts to the client
 */
public record CatalogViewer(Long userId, boolean admin, Set<Long> libraryIds, boolean restricted) {

    public CatalogViewer {
        libraryIds = Set.copyOf(libraryIds);
    }

    /** A non-admin without libraries: the list is empty, so every count is 0 and no query needs to run. */
    public boolean seesNoBooks() {
        return !admin && libraryIds.isEmpty();
    }

    /**
     * The books {@code BookService.streamBooks} lists for this user, before content restrictions: not deleted, and for
     * a non-admin in one of their libraries. Reads the library through its foreign key, so it adds no join.
     */
    public Predicate visible(CriteriaBuilder cb, Path<BookEntity> book) {
        Path<Boolean> deleted = book.get("deleted");
        Predicate notDeleted = cb.or(cb.isNull(deleted), cb.isFalse(deleted));
        if (admin) {
            return notDeleted;
        }
        return cb.and(notDeleted, book.get("library").get("id").in(libraryIds));
    }
}
