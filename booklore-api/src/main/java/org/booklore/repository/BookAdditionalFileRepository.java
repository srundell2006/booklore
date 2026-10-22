package org.booklore.repository;

import org.booklore.model.entity.BookFileEntity;
import org.booklore.repository.projection.BookFileScanRow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BookAdditionalFileRepository extends JpaRepository<BookFileEntity, Long> {

    List<BookFileEntity> findByBookId(Long bookId);

    List<BookFileEntity> findByBookIdAndIsBookFormat(Long bookId, boolean isBookFormat);

    Optional<BookFileEntity> findByAltFormatCurrentHash(String altFormatCurrentHash);

    /**
     * The files stored at this folder and name as the database compares them, through the columns' collation: on
     * MariaDB that ignores case, and possibly accents, so this can return files at another path, and more than one.
     * Whoever asks for the file at a path uses {@link #findExactlyAt}.
     */
    @Query("SELECT bf FROM BookFileEntity bf WHERE bf.book.libraryPath.id = :libraryPathId AND bf.fileSubPath = :fileSubPath AND bf.fileName = :fileName")
    List<BookFileEntity> findAllByLibraryPathIdAndFileSubPathAndFileName(@Param("libraryPathId") Long libraryPathId,
                                                                        @Param("fileSubPath") String fileSubPath,
                                                                        @Param("fileName") String fileName);

    /**
     * The file stored at exactly this folder and name of the library path, compared as a library walk compares them
     * ({@link BookFileEntity#isAt}). A file whose path differs only in case or accents is another file: on a
     * case-sensitive share both exist, and on any other the stored one was renamed and is gone.
     */
    default Optional<BookFileEntity> findExactlyAt(Long libraryPathId, String fileSubPath, String fileName) {
        return findAllByLibraryPathIdAndFileSubPathAndFileName(libraryPathId, fileSubPath, fileName).stream()
                .filter(file -> file.isAt(fileSubPath, fileName))
                .findFirst();
    }

    /**
     * One plain row per book_file of the library, soft-deleted books included, for rescans
     * that must reason about the whole library without loading its entities. Rows come in no
     * particular order.
     */
    @Query("""
            SELECT new org.booklore.repository.projection.BookFileScanRow(
                bf.id, b.id, lp.id, lp.path, bf.fileSubPath, bf.fileName, bf.isBookFormat, bf.bookType, b.deleted)
            FROM BookFileEntity bf
            JOIN bf.book b
            LEFT JOIN b.libraryPath lp
            WHERE b.library.id = :libraryId
            """)
    List<BookFileScanRow> findScanRowsByLibraryId(@Param("libraryId") Long libraryId);

    @Modifying
    @Query("""
            UPDATE BookFileEntity bf SET
                bf.fileName = :fileName,
                bf.fileSubPath = :fileSubPath
            WHERE bf.id = :bookFileId
            """)
    void updateFileNameAndSubPath(
            @Param("bookFileId") Long bookFileId,
            @Param("fileName") String fileName,
            @Param("fileSubPath") String fileSubPath);
}
