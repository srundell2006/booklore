package org.booklore.service.acquisition;

import lombok.Builder;
import lombok.Data;
import org.booklore.model.enums.MetadataProvider;

import java.util.List;

/**
 * A candidate book returned by an external metadata provider search,
 * annotated with whether it already exists in the local library.
 */
@Data
@Builder
public class BookLookupResult {
    private String title;
    private List<String> authors;
    private String description;
    private String publisher;
    private Integer publishedYear;
    private String isbn13;
    private String isbn10;
    private String asin;
    private String thumbnailUrl;
    private MetadataProvider provider;

    /** True when a book with this ISBN (or title+author) already exists in the library. */
    private boolean inLibrary;
    /** Populated when inLibrary is true. */
    private Long existingBookId;
    /** True when this book is already on the wanted list. */
    private boolean alreadyWanted;
}
