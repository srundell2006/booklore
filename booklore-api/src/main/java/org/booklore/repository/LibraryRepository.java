package org.booklore.repository;

import org.booklore.model.entity.LibraryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface LibraryRepository extends JpaRepository<LibraryEntity, Long>, JpaSpecificationExecutor<LibraryEntity> {

    List<LibraryEntity> findByIdIn(List<Long> ids);

    /** The library's watch setting as the database holds it now (a query, so never a cached entity's); empty when it is gone. */
    @Query("SELECT l.watch FROM LibraryEntity l WHERE l.id = :libraryId")
    Optional<Boolean> findWatchById(@Param("libraryId") Long libraryId);
}
