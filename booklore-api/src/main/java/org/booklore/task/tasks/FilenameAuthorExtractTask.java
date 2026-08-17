package org.booklore.task.tasks;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.request.FilenameAuthorExtractRequest;
import org.booklore.model.dto.request.TaskCreateRequest;
import org.booklore.model.dto.response.TaskCreateResponse;
import org.booklore.model.enums.TaskType;
import org.booklore.service.metadata.FilenameAuthorExtractService;
import org.booklore.task.TaskStatus;
import org.springframework.stereotype.Component;

import static org.booklore.exception.ApiError.PERMISSION_DENIED;
import static org.booklore.model.enums.UserPermission.CAN_BULK_AUTO_FETCH_METADATA;

@AllArgsConstructor
@Component
@Slf4j
public class FilenameAuthorExtractTask implements Task {

    private final FilenameAuthorExtractService filenameAuthorExtractService;

    @Override
    public TaskType getTaskType() {
        return TaskType.FILENAME_AUTHOR_EXTRACT;
    }

    @Override
    public void validatePermissions(BookLoreUser user, TaskCreateRequest request) {
        FilenameAuthorExtractRequest extractRequest = request.getOptionsAs(FilenameAuthorExtractRequest.class);
        boolean isBulk = extractRequest == null
                || extractRequest.getRefreshType() != FilenameAuthorExtractRequest.RefreshType.BOOKS
                || (extractRequest.getBookIds() != null && extractRequest.getBookIds().size() > 1);
        if (isBulk && !CAN_BULK_AUTO_FETCH_METADATA.isGranted(user.getPermissions())) {
            throw PERMISSION_DENIED.createException(CAN_BULK_AUTO_FETCH_METADATA);
        }
    }

    @Override
    public TaskCreateResponse execute(TaskCreateRequest request) {
        FilenameAuthorExtractRequest extractRequest =
                request.getOptionsAs(FilenameAuthorExtractRequest.class);
        String taskId = request.getTaskId();

        long start = System.currentTimeMillis();
        log.info("{}: Task started. TaskId: {}, Options: {}", getTaskType(), taskId, extractRequest);

        filenameAuthorExtractService.extract(extractRequest, taskId);

        log.info("{}: Task completed in {} ms", getTaskType(), System.currentTimeMillis() - start);
        return TaskCreateResponse.builder()
                .taskType(TaskType.FILENAME_AUTHOR_EXTRACT)
                .taskId(taskId)
                .status(TaskStatus.COMPLETED)
                .build();
    }
}
