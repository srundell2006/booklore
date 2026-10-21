package org.booklore.service.metadata.extractor;

import org.booklore.model.dto.BookMetadata;

import java.io.File;

public interface FileMetadataExtractor {

    /**
     * What {@link #extractMetadata(File)} and {@link #extractCover(File)} return for one file; either may be null.
     */
    record MetadataAndCover(BookMetadata metadata, byte[] cover) {
    }

    BookMetadata extractMetadata(File file);

    byte[] extractCover(File file);

    /**
     * Metadata and cover, each what {@link #extractMetadata(File)} and {@link #extractCover(File)} return. Extractors
     * that can read both from one open of the file override this.
     */
    default MetadataAndCover extractMetadataAndCover(File file) {
        return new MetadataAndCover(extractMetadata(file), extractCover(file));
    }
}
