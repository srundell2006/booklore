package org.booklore.repository;

import org.booklore.model.entity.WantedBookEntity;
import org.booklore.model.enums.WantedBookStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface WantedBookRepository extends JpaRepository<WantedBookEntity, Long> {

    List<WantedBookEntity> findAllByStatus(WantedBookStatus status);

    List<WantedBookEntity> findAllByStatusIn(List<WantedBookStatus> statuses);

    List<WantedBookEntity> findAllByOrderByAddedAtDesc();
}
