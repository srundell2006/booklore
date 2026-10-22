package org.booklore.repository;

import org.booklore.model.entity.BookFileEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BookFileRepository extends JpaRepository<BookFileEntity, Long> {

    /**
     * The files stored at this folder and name as the database compares them, through the columns' collation, which on
     * MariaDB ignores case and possibly accents: they may be at another path, and there may be more than one. Callers
     * pick the one at exactly the path with {@link BookFileEntity#isAt}.
     */
    @Query("""
            SELECT bf FROM BookFileEntity bf
            WHERE bf.book.libraryPath.id = :libraryPathId
            AND bf.fileSubPath = :fileSubPath
            AND bf.fileName = :fileName
            """)
    List<BookFileEntity> findAllByLibraryPathIdAndFileSubPathAndFileName(
            @Param("libraryPathId") Long libraryPathId,
            @Param("fileSubPath") String fileSubPath,
            @Param("fileName") String fileName);

    @Query("SELECT COUNT(bf) FROM BookFileEntity bf WHERE bf.book.id = :bookId")
    long countByBookId(@Param("bookId") Long bookId);
}
