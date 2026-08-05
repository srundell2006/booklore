package org.booklore.model.enums;

import lombok.Getter;

public enum TaskType {
    REFRESH_LIBRARY_METADATA(
            false,
            true,
            false,
            false,
            "Refresh Metadata",
            "Re-reads book information (title, author, cover, etc.) from your files and updates the Booklore database."
    ),
    UPDATE_BOOK_RECOMMENDATIONS(
            false,
            true,
            true,
            false,
            "Update Book Recommendations",
            "Analyzes your library to generate personalized book recommendations based on the books you own."
    ),
    CLEANUP_DELETED_BOOKS(
            false,
            false,
            true,
            false,
            "Cleanup Deleted Books",
            "Permanently removes database entries for books you previously deleted from your libraries."
    ),
    SYNC_LIBRARY_FILES(
            false,
            false,
            true,
            false,
            "Sync Library Files",
            "Scans your library folders to detect new books and removes entries for files that no longer exist."
    ),
    BOOKDROP_PERIODIC_SCANNING(
            false,
            false,
            true,
            false,
            "Bookdrop Periodic Scanning",
            "Scans the bookdrop ingest folder for newly added files and queues them for bookdrop processing."
    ),
    CLEANUP_TEMP_METADATA(
            false,
            false,
            true,
            false,
            "Cleanup Temporary Metadata",
            "Removes temporary metadata files created during the bookdrop and manual metadata review processes."
    ),
    REFRESH_METADATA_MANUAL(
            false,
            true,
            false,
            true,
            "Refresh Metadata",
            "Updates metadata information for your selected books."
    ),
    LOW_SCORE_METADATA_REFRESH(
            false,
            true,
            true,
            false,
            "Low Score Metadata Refresh",
            "Fetches metadata for up to 1,000 books with the lowest metadata match scores, gradually improving coverage across your library."
    ),
    EPUB_ISBN_SCAN(
            false,
            true,
            false,
            false,
            "Scan EPUBs for ISBN",
            "Scans the content pages of EPUB files (title page, copyright page, etc.) to find and populate missing ISBN numbers."
    ),
    AUDIOBOOK_MERGE(
            false,
            true,
            false,
            false,
            "Merge Audiobook",
            "Merges an audiobook's files into a single .m4b with chapters using the m4b-merge service."
    ),
    WANTED_BOOK_SEARCH(
            false,
            true,
            true,
            false,
            "Wanted Book Search",
            "Searches your configured indexers (via Prowlarr) for books on the wanted list and automatically sends the best matching release to your download client."
    ),
    ORGANIZE_LIBRARY(
            false,
            true,
            false,
            false,
            "Organize Library",
            "Renames and moves book files into subfolders according to the naming convention defined on the library."
    ),
    AUDIOBOOK_VERIFICATION(
            false,
            true,
            false,
            false,
            "Verify Audiobook Content",
            "Transcribes a sample of audiobook audio via Whisper and uses an LLM to extract and verify the title and author against stored metadata."
    ),
    EPUB_TEXT_IDENTIFY(
            false,
            true,
            false,
            false,
            "Identify EPUBs from Text",
            "Reads the opening pages of EPUB files, uses an LLM to identify the title and author, then fetches and applies full metadata from Google Books or Open Library."
    );

    @Getter
    private final boolean parallel;

    @Getter
    private final boolean async;

    @Getter
    private final boolean cronSupported;

    @Getter
    private final boolean hiddenFromUI;

    @Getter
    private final String name;

    @Getter
    private final String description;

    TaskType(boolean parallel, boolean async, boolean cronSupported, boolean hiddenFromUI,
             String name, String description) {
        this.parallel      = parallel;
        this.async         = async;
        this.cronSupported = cronSupported;
        this.hiddenFromUI  = hiddenFromUI;
        this.name          = name;
        this.description   = description;
    }
}
