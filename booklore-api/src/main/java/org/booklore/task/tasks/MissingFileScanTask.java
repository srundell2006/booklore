package org.booklore.task.tasks;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.request.MissingFileScanRequest;
import org.booklore.model.dto.request.TaskCreateRequest;
import org.booklore.model.dto.response.TaskCreateResponse;
import org.booklore.model.enums.TaskType;
import org.booklore.service.book.MissingFileScanService;
import org.booklore.task.TaskStatus;
import org.springframework.stereotype.Component;

import static org.booklore.exception.ApiError.PERMISSION_DENIED;
import static org.booklore.model.enums.UserPermission.CAN_BULK_AUTO_FETCH_METADATA;

@AllArgsConstructor
@Component
@Slf4j
public class MissingFileScanTask implements Task {

    private final MissingFileScanService missingFileScanService;

    @Override
    public TaskType getTaskType() {
        return TaskType.MISSING_FILE_SCAN;
    }

    @Override
    public void validatePermissions(BookLoreUser user, TaskCreateRequest request) {
        MissingFileScanRequest scanRequest = request.getOptionsAs(MissingFileScanRequest.class);
        boolean isBulk = scanRequest == null
                || scanRequest.getRefreshType() != MissingFileScanRequest.RefreshType.BOOKS
                || (scanRequest.getBookIds() != null && scanRequest.getBookIds().size() > 1);
        if (isBulk && !CAN_BULK_AUTO_FETCH_METADATA.isGranted(user.getPermissions())) {
            throw PERMISSION_DENIED.createException(CAN_BULK_AUTO_FETCH_METADATA);
        }
    }

    @Override
    public TaskCreateResponse execute(TaskCreateRequest request) {
        MissingFileScanRequest scanRequest = request.getOptionsAs(MissingFileScanRequest.class);
        String taskId = request.getTaskId();

        long start = System.currentTimeMillis();
        log.info("{}: Task started. TaskId: {}, Options: {}", getTaskType(), taskId, scanRequest);

        missingFileScanService.scan(scanRequest, taskId);

        log.info("{}: Task completed in {} ms", getTaskType(), System.currentTimeMillis() - start);
        return TaskCreateResponse.builder()
                .taskType(TaskType.MISSING_FILE_SCAN)
                .taskId(taskId)
                .status(TaskStatus.COMPLETED)
                .build();
    }
}
