package org.booklore.repository;

import org.booklore.model.entity.TaskHistoryEntity;
import org.booklore.task.TaskStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

import java.util.List;

@Repository
public interface TaskHistoryRepository extends JpaRepository<TaskHistoryEntity, String> {

    @Query("SELECT t FROM TaskHistoryEntity t WHERE t.createdAt = " +
           "(SELECT MAX(t2.createdAt) FROM TaskHistoryEntity t2 WHERE t2.type = t.type) " +
           "ORDER BY t.createdAt DESC")
    List<TaskHistoryEntity> findLatestTaskForEachType();

    @Modifying
    @Query("UPDATE TaskHistoryEntity t SET t.status = :newStatus, t.message = :message, t.updatedAt = :now, t.completedAt = :now WHERE t.status IN :statuses")
    int abortStuckTasks(TaskStatus newStatus, String message, LocalDateTime now, java.util.List<TaskStatus> statuses);
}
