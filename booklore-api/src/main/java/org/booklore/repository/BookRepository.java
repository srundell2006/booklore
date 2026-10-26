package org.booklore.repository;

import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.entity.LibraryPathEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.repository.projection.BookAuthorRow;
import org.booklore.repository.projection.BookFileScanRow;
import org.booklore.repository.projection.BookCoverUpdateProjection;
import org.booklore.repository.projection.FilelessBookRow;
import jakarta.transaction.Transactional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Repository
public interface BookRepository extends JpaRepository<BookEntity, Long>, JpaSpecificationExecutor<BookEntity> {
    Optional<BookEntity> findBookByIdAndLibraryId(long id, long libraryId);

    @EntityGraph(attributePaths = { "metadata", "metadata.comicMetadata", "shelves", "libraryPath", "library", "bookFiles" })
    @Query("SELECT b FROM BookEntity b LEFT JOIN FETCH b.bookFiles bf WHERE b.id = :id AND (b.deleted IS NULL OR b.deleted = false)")
    Optional<BookEntity> findByIdWithBookFiles(@Param("id") Long id);

    @Query("SELECT b FROM BookEntity b JOIN b.bookFiles bf WHERE bf.currentHash = :currentHash AND bf.isBookFormat = true AND (b.deleted IS NULL OR b.deleted = false)")
    Optional<BookEntity> findByCurrentHash(@Param("currentHash") String currentHash);

    /**
     * The book formats of this hash, with their books, of books not deleted or deleted after the cutoff. Looks the hash
     * up in alt_format_current_hash, which holds the current hash of exactly the book formats and is indexed, where
     * current_hash is not: the watcher runs this for every new file. Files of several books can have one content, as
     * a copy of a file does, so this is a list.
     */
    @Query("SELECT bf FROM BookFileEntity bf JOIN FETCH bf.book b WHERE bf.altFormatCurrentHash = :currentHash AND bf.isBookFormat = true AND (b.deleted IS NULL OR b.deleted = false OR b.deletedAt > :cutoff)")
    List<BookFileEntity> findBookFormatsByCurrentHashIncludingRecentlyDeleted(@Param("currentHash") String currentHash, @Param("cutoff") Instant cutoff);

    Optional<BookEntity> findByBookCoverHash(String bookCoverHash);

    /**
     * The book, its row locked until the transaction ends: two attaches to one book, from the watcher and a rescan,
     * run one after the other, and the second sees the files the first gave it.
     */
    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM BookEntity b WHERE b.id = :id")
    Optional<BookEntity> findByIdForUpdate(@Param("id") long id);

    @Query("SELECT b.id FROM BookEntity b WHERE b.library.id = :libraryId AND (b.deleted IS NULL OR b.deleted = false)")
    Set<Long> findBookIdsByLibraryId(@Param("libraryId") long libraryId);

    /** Every book of the library, soft-deleted ones too: those keep their cover folders until they are purged. */
    @Query("SELECT b.id FROM BookEntity b WHERE b.library.id = :libraryId")
    Set<Long> findAllBookIdsIncludingDeletedByLibraryId(@Param("libraryId") long libraryId);

    @Query("SELECT DISTINCT b FROM BookEntity b JOIN b.bookFiles bf WHERE b.libraryPath.id = :libraryPathId AND (bf.fileSubPath = :fileSubPathPrefix OR bf.fileSubPath LIKE CONCAT(:fileSubPathPrefix, '/%')) AND bf.isBookFormat = true AND (b.deleted IS NULL OR b.deleted = false)")
    List<BookEntity> findAllByLibraryPathIdAndFileSubPathStartingWith(@Param("libraryPathId") Long libraryPathId, @Param("fileSubPathPrefix") String fileSubPathPrefix);

    /**
     * The book formats stored in one folder of a library path, soft-deleted books' included, as plain rows: what the
     * watcher matches a new file against, in one query whatever the number of books in the folder. The folder is
     * compared through the column's collation, which on MariaDB ignores case and possibly accents; callers compare
     * it exactly.
     */
    @Query("""
            SELECT new org.booklore.repository.projection.BookFileScanRow(
                bf.id, b.id, lp.id, lp.path, bf.fileSubPath, bf.fileName, bf.isBookFormat, bf.bookType, b.deleted,
                bf.folderBased)
            FROM BookFileEntity bf
            JOIN bf.book b
            LEFT JOIN b.libraryPath lp
            WHERE b.libraryPath.id = :libraryPathId AND bf.fileSubPath = :fileSubPath AND bf.isBookFormat = true
            """)
    List<BookFileScanRow> findBookFormatRowsInFolder(@Param("libraryPathId") Long libraryPathId,
                                                     @Param("fileSubPath") String fileSubPath);

    /**
     * The book formats whose own folder is {@code parent/leaf}: the files stored in it, and a folder audiobook that is
     * that folder. As {@link #findBookFormatRowsInFolder}, compared through the columns' collation.
     */
    @Query("""
            SELECT new org.booklore.repository.projection.BookFileScanRow(
                bf.id, b.id, lp.id, lp.path, bf.fileSubPath, bf.fileName, bf.isBookFormat, bf.bookType, b.deleted,
                bf.folderBased)
            FROM BookFileEntity bf
            JOIN bf.book b
            LEFT JOIN b.libraryPath lp
            WHERE b.libraryPath.id = :libraryPathId AND bf.isBookFormat = true
            AND ((bf.fileSubPath = :folder AND bf.folderBased = false)
                 OR (bf.folderBased = true AND bf.fileSubPath = :parent AND bf.fileName = :leaf))
            """)
    List<BookFileScanRow> findBookFormatRowsOwnedByFolder(@Param("libraryPathId") Long libraryPathId,
                                                          @Param("folder") String folder,
                                                          @Param("parent") String parent,
                                                          @Param("leaf") String leaf);

    @Query("SELECT b.id FROM BookEntity b WHERE b.libraryPath.id IN :libraryPathIds AND (b.deleted IS NULL OR b.deleted = false)")
    List<Long> findAllBookIdsByLibraryPathIdIn(@Param("libraryPathIds") Collection<Long> libraryPathIds);

    @EntityGraph(attributePaths = {"metadata", "metadata.comicMetadata", "shelves", "libraryPath", "bookFiles"})
    @Query("SELECT b FROM BookEntity b WHERE (b.deleted IS NULL OR b.deleted = false)")
    List<BookEntity> findAllWithMetadata();

    /**
     * Ids only, for streaming the catalogue in chunks instead of materialising
     * every entity and DTO at once.
     */
    @Query("SELECT b.id FROM BookEntity b WHERE (b.deleted IS NULL OR b.deleted = false) ORDER BY b.id")
    List<Long> findAllBookIdsForStreaming();

    @Query("SELECT b.id FROM BookEntity b WHERE b.library.id IN :libraryIds "
            + "AND (b.deleted IS NULL OR b.deleted = false) ORDER BY b.id")
    List<Long> findBookIdsByLibraryIdsForStreaming(@Param("libraryIds") Set<Long> libraryIds);

    @EntityGraph(attributePaths = {"metadata", "metadata.authors", "metadata.comicMetadata", "shelves", "libraryPath", "bookFiles", "library"})
    @Query("SELECT b FROM BookEntity b WHERE b.id IN :bookIds AND (b.deleted IS NULL OR b.deleted = false)")
    List<BookEntity> findAllWithMetadataByIds(@Param("bookIds") Set<Long> bookIds);

    @EntityGraph(attributePaths = {"metadata", "metadata.comicMetadata", "shelves", "libraryPath", "bookFiles"})
    @Query("SELECT b FROM BookEntity b WHERE b.id IN :bookIds AND (b.deleted IS NULL OR b.deleted = false)")
    List<BookEntity> findWithMetadataByIdsWithPagination(@Param("bookIds") Set<Long> bookIds, Pageable pageable);

    @EntityGraph(attributePaths = {"metadata", "metadata.comicMetadata", "shelves", "libraryPath", "bookFiles"})
    @Query("SELECT b FROM BookEntity b WHERE b.library.id = :libraryId AND (b.deleted IS NULL OR b.deleted = false)")
    List<BookEntity> findAllWithMetadataByLibraryId(@Param("libraryId") Long libraryId);

    @EntityGraph(attributePaths = {"metadata", "bookFiles"})
    @Query("SELECT b FROM BookEntity b WHERE b.library.id = :libraryId AND (b.deleted IS NULL OR b.deleted = false)")
    List<BookEntity> findAllByLibraryIdWithFiles(@Param("libraryId") Long libraryId);

    @Query("""
            SELECT DISTINCT b FROM BookEntity b
            LEFT JOIN FETCH b.metadata m
            LEFT JOIN FETCH m.authors
            LEFT JOIN FETCH b.bookFiles
            LEFT JOIN FETCH b.libraryPath
            WHERE b.library.id = :libraryId
            AND (b.deleted IS NULL OR b.deleted = false)
            """)
    List<BookEntity> findAllForDuplicateDetection(@Param("libraryId") Long libraryId);

    @EntityGraph(attributePaths = {"metadata", "metadata.comicMetadata", "shelves", "libraryPath", "bookFiles"})
    @Query("SELECT b FROM BookEntity b WHERE b.library.id IN :libraryIds AND (b.deleted IS NULL OR b.deleted = false)")
    List<BookEntity> findAllWithMetadataByLibraryIds(@Param("libraryIds") Collection<Long> libraryIds);

    @EntityGraph(attributePaths = {"metadata", "metadata.comicMetadata", "shelves", "libraryPath", "bookFiles"})
    @Query("SELECT DISTINCT b FROM BookEntity b JOIN b.shelves s WHERE s.id = :shelfId AND (b.deleted IS NULL OR b.deleted = false)")
    List<BookEntity> findAllWithMetadataByShelfId(@Param("shelfId") Long shelfId);

    @EntityGraph(attributePaths = { "metadata", "metadata.comicMetadata", "shelves", "libraryPath", "bookFiles" })
    @Query("SELECT DISTINCT b FROM BookEntity b JOIN b.bookFiles bf WHERE bf.isBookFormat = true AND bf.fileSizeKb IS NULL AND (b.deleted IS NULL OR b.deleted = false)")
    List<BookEntity> findAllWithMetadataByFileSizeKbIsNull();

    @Query("""
                SELECT DISTINCT b FROM BookEntity b
                LEFT JOIN FETCH b.metadata m
                LEFT JOIN FETCH m.authors
                LEFT JOIN FETCH m.categories
                LEFT JOIN FETCH b.shelves
                WHERE (b.deleted IS NULL OR b.deleted = false)
            """)
    List<BookEntity> findAllFullBooks();

    @Query(value = """
                SELECT DISTINCT b.* FROM book b
                LEFT JOIN book_metadata m ON b.id = m.book_id
                WHERE (b.deleted IS NULL OR b.deleted = false)
                ORDER BY b.id
                LIMIT :limit OFFSET :offset
            """, nativeQuery = true)
    List<BookEntity> findBooksForMigrationBatch(@Param("offset") int offset, @Param("limit") int limit);

    @Query("""
                SELECT DISTINCT b FROM BookEntity b
                LEFT JOIN FETCH b.metadata m
                LEFT JOIN FETCH m.authors
                WHERE b.id IN :bookIds
            """)
    List<BookEntity> findBooksWithMetadataAndAuthors(@Param("bookIds") List<Long> bookIds);

    @Modifying
    @Transactional
    @Query("DELETE FROM BookEntity b WHERE b.deleted IS TRUE")
    int deleteAllSoftDeleted();

    @Modifying
    @Transactional
    @Query("DELETE FROM BookEntity b WHERE b.deleted IS TRUE AND b.deletedAt < :cutoffDate")
    int deleteSoftDeletedBefore(@Param("cutoffDate") Instant cutoffDate);

    @Query("SELECT COUNT(b) FROM BookEntity b WHERE b.deleted = TRUE")
    long countAllSoftDeleted();

    @Query("""
        SELECT DISTINCT b FROM BookEntity b
        JOIN FETCH b.bookFiles bf
        WHERE b.libraryPath.id = :libraryPathId
        AND (bf.fileSubPath = :folderPath
             OR bf.fileSubPath LIKE CONCAT(:folderPath, '/%')
             OR (bf.folderBased = true AND CONCAT(bf.fileSubPath, '/', bf.fileName) = :folderPath))
        AND bf.isBookFormat = true
        AND (b.deleted IS NULL OR b.deleted = false)
        """)
    List<BookEntity> findBooksWithFilesUnderPath(@Param("libraryPathId") Long libraryPathId,
                                                  @Param("folderPath") String folderPath);

    /**
     * The book formats stored at this folder and name of the library path, with their books, deleted ones included, as
     * the database compares paths: through the columns' collation, which on MariaDB ignores case and possibly accents.
     * So several can come back, and some at another path; the caller picks the one at exactly the path.
     */
    @Query("""
            SELECT bf FROM BookFileEntity bf
            JOIN FETCH bf.book b
            WHERE b.library.id = :libraryId
              AND b.libraryPath.id = :libraryPathId
              AND bf.fileSubPath = :fileSubPath
              AND bf.fileName = :fileName
              AND bf.isBookFormat = true
            """)
    List<BookFileEntity> findBookFormatsByLibraryIdAndLibraryPathIdAndFileSubPathAndFileName(
            @Param("libraryId") Long libraryId,
            @Param("libraryPathId") Long libraryPathId,
            @Param("fileSubPath") String fileSubPath,
            @Param("fileName") String fileName);

    @Query("SELECT COUNT(b.id) FROM BookEntity b WHERE b.id IN :bookIds AND (b.deleted IS NULL OR b.deleted = false)")
    long countByIdIn(@Param("bookIds") List<Long> bookIds);

    @Query("""
            SELECT COUNT(DISTINCT b) FROM BookEntity b
            JOIN b.bookFiles bf
            WHERE bf.isBookFormat = true
              AND bf.bookType = :type
              AND (b.deleted IS NULL OR b.deleted = false)
            """)
    long countByBookType(@Param("type") BookFileType type);

    @Query("""
            SELECT COUNT(DISTINCT b) FROM BookEntity b
            JOIN b.bookFiles bf
            WHERE b.library.id = :libraryId
              AND bf.isBookFormat = true
              AND bf.bookType = :type
              AND (b.deleted IS NULL OR b.deleted = false)
            """)
    long countByLibraryIdAndBookType(@Param("libraryId") Long libraryId, @Param("type") BookFileType type);

    @Query("SELECT COUNT(b) FROM BookEntity b WHERE b.library.id = :libraryId AND (b.deleted IS NULL OR b.deleted = false)")
    long countByLibraryId(@Param("libraryId") Long libraryId);

    /** Every book of the library, soft-deleted and fileless ones included. */
    @Query("SELECT COUNT(b) FROM BookEntity b WHERE b.library.id = :libraryId")
    long countIncludingDeletedByLibraryId(@Param("libraryId") Long libraryId);

    /**
     * The fileless books of the library that are not deleted, one row per author, in book id order: what matching a
     * new file against them needs, for the whole library in one query and without entities. Books without metadata
     * have no title to match and are left out.
     */
    @Query("""
            SELECT new org.booklore.repository.projection.FilelessBookRow(
                b.id, lp.id, b.isPhysical, b.bookCoverHash, b.audiobookCoverHash,
                m.title, m.subtitle, m.seriesName, m.seriesNumber, a.name)
            FROM BookEntity b
            JOIN b.metadata m
            LEFT JOIN b.libraryPath lp
            LEFT JOIN m.authors a
            WHERE b.library.id = :libraryId
            AND (b.deleted IS NULL OR b.deleted = false)
            AND NOT EXISTS (SELECT bf.id FROM BookFileEntity bf WHERE bf.book = b)
            ORDER BY b.id
            """)
    List<FilelessBookRow> findFilelessBookRowsByLibraryId(@Param("libraryId") Long libraryId);

    /** The author names of these books, one row per book and author. */
    @Query("""
            SELECT new org.booklore.repository.projection.BookAuthorRow(m.bookId, a.name)
            FROM BookMetadataEntity m
            JOIN m.authors a
            WHERE m.bookId IN :bookIds
            """)
    List<BookAuthorRow> findAuthorNamesByBookIds(@Param("bookIds") Collection<Long> bookIds);

    @Query("SELECT b.id as id, m.coverUpdatedOn as coverUpdatedOn FROM BookEntity b LEFT JOIN b.metadata m WHERE b.id IN :bookIds")
    List<BookCoverUpdateProjection> findCoverUpdateInfoByIds(@Param("bookIds") Collection<Long> bookIds);

    @Modifying
    @Query("""
            UPDATE BookEntity b SET
                b.library.id = :libraryId,
                b.libraryPath = :libraryPath
            WHERE b.id = :bookId
            """)
    void updateLibrary(
            @Param("bookId") Long bookId,
            @Param("libraryId") Long libraryId,
            @Param("libraryPath") LibraryPathEntity libraryPath);

    /**
     * Get distinct series names for a library when groupUnknown=true.
     * Books without series name are grouped as "Unknown Series".
     */
    @Query("""
            SELECT DISTINCT 
                CASE 
                    WHEN m.seriesName IS NOT NULL THEN m.seriesName
                    ELSE :unknownSeriesName
                END as seriesName
            FROM BookEntity b
            LEFT JOIN b.metadata m
            WHERE b.library.id = :libraryId 
            AND (b.deleted IS NULL OR b.deleted = false)
            ORDER BY seriesName
            """)
    List<String> findDistinctSeriesNamesGroupedByLibraryId(
            @Param("libraryId") Long libraryId,
            @Param("unknownSeriesName") String unknownSeriesName);

    /**
     * Get distinct series names across all libraries when groupUnknown=true.
     * Books without series name are grouped as "Unknown Series".
     */
    @Query("""
            SELECT DISTINCT 
                CASE 
                    WHEN m.seriesName IS NOT NULL THEN m.seriesName
                    ELSE :unknownSeriesName
                END as seriesName
            FROM BookEntity b
            LEFT JOIN b.metadata m
            WHERE (b.deleted IS NULL OR b.deleted = false)
            ORDER BY seriesName
            """)
    List<String> findDistinctSeriesNamesGrouped(@Param("unknownSeriesName") String unknownSeriesName);

    /**
     * Get distinct series names for a library when groupUnknown=false.
     * Each book without series gets its own entry (title or filename).
     * Uses a derived-table JOIN instead of a correlated subquery to avoid
     * N-per-book execution cost on large libraries.
     */
    @Query(value = """
            SELECT DISTINCT 
                CASE 
                    WHEN m.series_name IS NOT NULL THEN m.series_name
                    WHEN m.title IS NOT NULL THEN m.title
                    ELSE bf_min.file_name
                END as seriesName
            FROM book b
            LEFT JOIN book_metadata m ON m.book_id = b.id
            LEFT JOIN (
                SELECT bf.book_id, bf.file_name
                FROM book_file bf
                INNER JOIN (
                    SELECT book_id, MIN(id) AS min_id
                    FROM book_file
                    WHERE is_book = true
                    GROUP BY book_id
                ) agg ON bf.id = agg.min_id
            ) bf_min ON bf_min.book_id = b.id
            WHERE b.library_id = :libraryId
              AND (b.deleted IS NULL OR b.deleted = false)
            ORDER BY seriesName
            """, nativeQuery = true)
    List<String> findDistinctSeriesNamesUngroupedByLibraryId(@Param("libraryId") Long libraryId);

    /**
     * Get distinct series names across all libraries when groupUnknown=false.
     * Each book without series gets its own entry (title or filename).
     * Uses a derived-table JOIN instead of a correlated subquery to avoid
     * N-per-book execution cost on large libraries.
     */
    @Query(value = """
            SELECT DISTINCT 
                CASE 
                    WHEN m.series_name IS NOT NULL THEN m.series_name
                    WHEN m.title IS NOT NULL THEN m.title
                    ELSE bf_min.file_name
                END as seriesName
            FROM book b
            LEFT JOIN book_metadata m ON m.book_id = b.id
            LEFT JOIN (
                SELECT bf.book_id, bf.file_name
                FROM book_file bf
                INNER JOIN (
                    SELECT book_id, MIN(id) AS min_id
                    FROM book_file
                    WHERE is_book = true
                    GROUP BY book_id
                ) agg ON bf.id = agg.min_id
            ) bf_min ON bf_min.book_id = b.id
            WHERE (b.deleted IS NULL OR b.deleted = false)
            ORDER BY seriesName
            """, nativeQuery = true)
    List<String> findDistinctSeriesNamesUngrouped();

    /**
     * Find books by series name for a library when groupUnknown=true.
     * Uses the first bookFile.fileName as fallback when metadata.seriesName is null.
     */
    @EntityGraph(attributePaths = {"metadata", "metadata.comicMetadata", "shelves", "libraryPath", "bookFiles"})
    @Query("""
            SELECT DISTINCT b FROM BookEntity b
            LEFT JOIN b.metadata m
            LEFT JOIN b.bookFiles bf
            WHERE b.library.id = :libraryId
            AND (
                (m.seriesName = :seriesName)
                OR (
                    m.seriesName IS NULL
                    AND bf.isBookFormat = true
                    AND bf.id = (
                        SELECT MIN(bf2.id) FROM BookFileEntity bf2
                        WHERE bf2.book = b AND bf2.isBookFormat = true
                    )
                    AND bf.fileName = :seriesName
                )
            )
            AND (b.deleted IS NULL OR b.deleted = false)
            ORDER BY COALESCE(m.seriesNumber, 0)
            """)
    List<BookEntity> findBooksBySeriesNameGroupedByLibraryId(
            @Param("seriesName") String seriesName,
            @Param("libraryId") Long libraryId,
            @Param("unknownSeriesName") String unknownSeriesName);

    /**
     * Find books by series name for a library when groupUnknown=false.
     * Matches by series name, or by title/filename for books without series.
     */
    @EntityGraph(attributePaths = {"metadata", "metadata.comicMetadata", "shelves", "libraryPath", "bookFiles"})
    @Query("""
            SELECT b FROM BookEntity b
            LEFT JOIN b.metadata m
            LEFT JOIN b.bookFiles bf
            WHERE b.library.id = :libraryId
            AND (
                (m.seriesName = :seriesName)
                OR (m.seriesName IS NULL AND m.title = :seriesName)
                OR (
                    m.seriesName IS NULL AND m.title IS NULL
                    AND bf.isBookFormat = true
                    AND bf.id = (
                        SELECT MIN(bf2.id) FROM BookFileEntity bf2
                        WHERE bf2.book = b AND bf2.isBookFormat = true
                    )
                    AND bf.fileName = :seriesName
                )
            )
            AND (b.deleted IS NULL OR b.deleted = false)
            ORDER BY COALESCE(m.seriesNumber, 0)
            """)
    List<BookEntity> findBooksBySeriesNameUngroupedByLibraryId(
            @Param("seriesName") String seriesName,
            @Param("libraryId") Long libraryId);
    @Query("""
        SELECT b.id FROM BookEntity b
        WHERE (b.metadataMatchScore IS NULL OR b.metadataMatchScore < :threshold)
          AND (b.deleted IS NULL OR b.deleted = false)
        ORDER BY
            CASE WHEN b.lastMetadataRefreshAt IS NULL THEN 0 ELSE 1 END ASC,
            b.lastMetadataRefreshAt ASC,
            COALESCE(b.metadataMatchScore, -1) ASC
        """)
    List<Long> findBookIdsWithLowMetadataScore(@Param("threshold") float threshold, Pageable pageable);

    @Modifying
    @Transactional
    @Query("UPDATE BookEntity b SET b.lastMetadataRefreshAt = :refreshedAt WHERE b.id IN :bookIds")
    void updateLastMetadataRefreshAt(@Param("bookIds") List<Long> bookIds,
                                     @Param("refreshedAt") java.time.LocalDateTime refreshedAt);

}
