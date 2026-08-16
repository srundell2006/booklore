package org.booklore.repository;

import org.booklore.model.entity.ComicDetectionCandidateEntity;
import org.booklore.model.enums.ComicCandidateStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ComicDetectionCandidateRepository extends JpaRepository<ComicDetectionCandidateEntity, Long> {

    Optional<ComicDetectionCandidateEntity> findByBookId(Long bookId);

    List<ComicDetectionCandidateEntity> findByStatusOrderByScoreDesc(ComicCandidateStatus status);

    long countByStatus(ComicCandidateStatus status);

    void deleteByBookId(Long bookId);
}
