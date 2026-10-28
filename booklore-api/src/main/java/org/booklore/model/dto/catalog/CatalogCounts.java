package org.booklore.model.dto.catalog;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * The sidebar counts, computed over the books the user's list holds. {@code clientSide} means the server leaves the
 * counts to the client, which computes them from the list as before; the other fields are then absent.
 *
 * @param libraries every library the user sees, by id, with 0 for an empty one
 * @param shelves   every shelf the user owns, by id, with the count of the user's visible books on it
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CatalogCounts(
        boolean clientSide,
        Long allBooks,
        Long series,
        Long unshelved,
        Map<Long, Long> libraries,
        Map<Long, Long> shelves
) {
    public static CatalogCounts computedOnClient() {
        return new CatalogCounts(true, null, null, null, null, null);
    }
}
