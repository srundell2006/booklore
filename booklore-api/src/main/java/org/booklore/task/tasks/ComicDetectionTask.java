package org.booklore.task.tasks;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.request.ComicDetectionRequest;
import org.booklore.model.dto.request.TaskCreateRequest;
import org.booklore.model.dto.response.TaskCreateResponse;
import org.booklore.model.enums.TaskType;
import org.booklore.service.comic.ComicDetectionService;
import org.booklore.task.TaskStatus;
import org.springframework.stereotype.Component;

import static org.booklore.exception.ApiError.PERMISSION_DENIED;
import static org.booklore.model.enums.UserPermission.CAN_BULK_AUTO_FETCH_METADATA;

@AllArgsConstructor
@Component
@Slf4j
public class ComicDetectionTask implements Task {

    private final ComicDetectionService comicDetectionService;

    @Override
    public TaskType getTaskType() {
        return TaskType.COMIC_DETECTION;
    }

    @Override
    public void validatePermissions(BookLoreUser user, TaskCreateRequest request) {
        ComicDetectionRequest detectionRequest = request.getOptionsAs(ComicDetectionRequest.class);
        boolean isBulk = detectionRequest == null
                || detectionRequest.getRefreshType() != ComicDetectionRequest.RefreshType.BOOKS
                || (detectionRequest.getBookIds() != null && detectionRequest.getBookIds().size() > 1);
        if (isBulk && !CAN_BULK_AUTO_FETCH_METADATA.isGranted(user.getPermissions())) {
            throw PERMISSION_DENIED.createException(CAN_BULK_AUTO_FETCH_METADATA);
        }
    }

    @Override
    public TaskCreateResponse execute(TaskCreateRequest request) {
        ComicDetectionRequest detectionRequest = request.getOptionsAs(ComicDetectionRequest.class);
        String taskId = request.getTaskId();

        long start = System.currentTimeMillis();
        log.info("{}: Task started. TaskId: {}, Options: {}", getTaskType(), taskId, detectionRequest);

        comicDetectionService.detect(detectionRequest, taskId);

        log.info("{}: Task completed in {} ms", getTaskType(), System.currentTimeMillis() - start);
        return TaskCreateResponse.builder()
                .taskType(TaskType.COMIC_DETECTION)
                .taskId(taskId)
                .status(TaskStatus.COMPLETED)
                .build();
    }
}
