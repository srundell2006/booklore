package org.booklore.task.tasks;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.request.MetadataRefreshRequest;
import org.booklore.model.dto.request.TaskCreateRequest;
import org.booklore.model.dto.response.TaskCreateResponse;
import org.booklore.model.enums.TaskType;
import org.booklore.repository.BookRepository;
import org.booklore.service.metadata.MetadataRefreshService;
import org.booklore.task.TaskStatus;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;

@RequiredArgsConstructor
@Component
@Slf4j
public class LowScoreMetadataRefreshTask implements Task {

    private static final float DEFAULT_SCORE_THRESHOLD = 0.7f;
    private static final int BATCH_SIZE = 1000;

    private final BookRepository bookRepository;
    private final MetadataRefreshService metadataRefreshService;

    @Override
    public void validatePermissions(BookLoreUser user, TaskCreateRequest request) {
        // Cron-triggered system task — no user permission check required
    }

    @Override
    public TaskCreateResponse execute(TaskCreateRequest request) {
        String taskId = request.getTaskId();
        long startTime = System.currentTimeMillis();

        log.info("{}: Task started. TaskId: {}", getTaskType(), taskId);

        List<Long> bookIds = bookRepository.findBookIdsWithLowMetadataScore(
                DEFAULT_SCORE_THRESHOLD,
                PageRequest.of(0, BATCH_SIZE)
        );

        if (bookIds.isEmpty()) {
            log.info("{}: No books below score threshold {}, nothing to do.", getTaskType(), DEFAULT_SCORE_THRESHOLD);
            return TaskCreateResponse.builder()
                    .taskType(getTaskType())
                    .taskId(taskId)
                    .status(TaskStatus.COMPLETED)
                    .build();
        }

        log.info("{}: Refreshing metadata for {} books with score < {}", getTaskType(), bookIds.size(), DEFAULT_SCORE_THRESHOLD);

        MetadataRefreshRequest refreshRequest = MetadataRefreshRequest.builder()
                .refreshType(MetadataRefreshRequest.RefreshType.BOOKS)
                .bookIds(new LinkedHashSet<>(bookIds))
                .build();

        metadataRefreshService.refreshMetadata(refreshRequest, taskId);

        long duration = System.currentTimeMillis() - startTime;
        log.info("{}: Task completed. Processed {} books in {} ms", getTaskType(), bookIds.size(), duration);

        return TaskCreateResponse.builder()
                .taskType(getTaskType())
                .taskId(taskId)
                .status(TaskStatus.COMPLETED)
                .build();
    }

    @Override
    public TaskType getTaskType() {
        return TaskType.LOW_SCORE_METADATA_REFRESH;
    }
}
