package org.booklore.repository;

import org.booklore.model.entity.LibraryPathEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface LibraryPathRepository extends JpaRepository<LibraryPathEntity, Long> {
    Optional<LibraryPathEntity> findByLibraryIdAndPath(Long libraryId, String path);

    @Query("SELECT lp FROM LibraryPathEntity lp JOIN FETCH lp.library")
    List<LibraryPathEntity> findAllWithLibrary();

    /** The paths of a library as the database holds them now. */
    @Query("SELECT lp.path FROM LibraryPathEntity lp WHERE lp.library.id = :libraryId")
    List<String> findPathsByLibraryId(@Param("libraryId") Long libraryId);
}
