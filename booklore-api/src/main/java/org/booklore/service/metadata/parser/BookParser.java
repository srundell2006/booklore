package org.booklore.service.metadata.parser;

import org.booklore.model.dto.Book;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.dto.BookMetadata;
import org.booklore.model.dto.request.FetchMetadataRequest;

import java.util.Collection;
import java.util.List;

public interface BookParser {

    /**
     * Optional bulk pre-fetch hook.  Implementations that support batch lookups
     * (e.g. OpenLibrary) should override this to warm an internal cache before
     * per-book {@link #fetchTopMetadata} calls begin.
     * The default implementation is a no-op.
     *
     * @param isbns cleaned ISBNs (10 or 13 digit) to pre-fetch
     */
    default void preFetchByIsbn(Collection<String> isbns) {
        // no-op
    }

    /**
     * Optional bulk pre-fetch hook for title/author-based providers (e.g. Ollama).
     * Called once before the parallel per-book loop with the full book list.
     * Implementations should populate an internal cache; fetchTopMetadata() reads from it.
     * The default implementation is a no-op.
     */
    default void preFetchBookEntities(java.util.Collection<BookEntity> entities) {
        // no-op
    }

    List<BookMetadata> fetchMetadata(Book book, FetchMetadataRequest fetchMetadataRequest);

    BookMetadata fetchTopMetadata(Book book, FetchMetadataRequest fetchMetadataRequest);
}
