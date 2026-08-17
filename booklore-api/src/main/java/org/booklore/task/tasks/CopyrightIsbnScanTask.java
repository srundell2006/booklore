package org.booklore.task.tasks;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.request.CopyrightIsbnScanRequest;
import org.booklore.model.dto.request.TaskCreateRequest;
import org.booklore.model.dto.response.TaskCreateResponse;
import org.booklore.model.enums.TaskType;
import org.booklore.service.metadata.CopyrightIsbnScanService;
import org.booklore.task.TaskStatus;
import org.springframework.stereotype.Component;

import static org.booklore.exception.ApiError.PERMISSION_DENIED;
import static org.booklore.model.enums.UserPermission.CAN_BULK_AUTO_FETCH_METADATA;

@AllArgsConstructor
@Component
@Slf4j
public class CopyrightIsbnScanTask implements Task {

    private final CopyrightIsbnScanService copyrightIsbnScanService;

    @Override
    public TaskType getTaskType() {
        return TaskType.COPYRIGHT_ISBN_SCAN;
    }

    @Override
    public void validatePermissions(BookLoreUser user, TaskCreateRequest request) {
        CopyrightIsbnScanRequest scanRequest = request.getOptionsAs(CopyrightIsbnScanRequest.class);
        boolean isBulk = scanRequest == null
                || scanRequest.getRefreshType() != CopyrightIsbnScanRequest.RefreshType.BOOKS
                || (scanRequest.getBookIds() != null && scanRequest.getBookIds().size() > 1);
        if (isBulk && !CAN_BULK_AUTO_FETCH_METADATA.isGranted(user.getPermissions())) {
            throw PERMISSION_DENIED.createException(CAN_BULK_AUTO_FETCH_METADATA);
        }
    }

    @Override
    public TaskCreateResponse execute(TaskCreateRequest request) {
        CopyrightIsbnScanRequest scanRequest = request.getOptionsAs(CopyrightIsbnScanRequest.class);
        String taskId = request.getTaskId();

        long start = System.currentTimeMillis();
        log.info("{}: Task started. TaskId: {}, Options: {}", getTaskType(), taskId, scanRequest);

        copyrightIsbnScanService.scan(scanRequest, taskId);

        log.info("{}: Task completed in {} ms", getTaskType(), System.currentTimeMillis() - start);
        return TaskCreateResponse.builder()
                .taskType(TaskType.COPYRIGHT_ISBN_SCAN)
                .taskId(taskId)
                .status(TaskStatus.COMPLETED)
                .build();
    }
}
