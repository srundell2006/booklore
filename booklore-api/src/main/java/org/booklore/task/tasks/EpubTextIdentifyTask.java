package org.booklore.task.tasks;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.request.EpubTextIdentifyRequest;
import org.booklore.model.dto.request.TaskCreateRequest;
import org.booklore.model.dto.response.TaskCreateResponse;
import org.booklore.model.enums.TaskType;
import org.booklore.service.metadata.EpubTextIdentifyService;
import org.booklore.task.TaskStatus;
import org.springframework.stereotype.Component;

import static org.booklore.exception.ApiError.PERMISSION_DENIED;
import static org.booklore.model.enums.UserPermission.CAN_BULK_AUTO_FETCH_METADATA;

@AllArgsConstructor
@Component
@Slf4j
public class EpubTextIdentifyTask implements Task {

    private final EpubTextIdentifyService epubTextIdentifyService;

    @Override
    public TaskType getTaskType() {
        return TaskType.EPUB_TEXT_IDENTIFY;
    }

    @Override
    public void validatePermissions(BookLoreUser user, TaskCreateRequest request) {
        EpubTextIdentifyRequest identifyRequest = request.getOptionsAs(EpubTextIdentifyRequest.class);
        // Library- or magic-shelf-scoped runs affect many books — require bulk permission.
        boolean isBulk = identifyRequest == null
                || identifyRequest.getRefreshType() != EpubTextIdentifyRequest.RefreshType.BOOKS
                || (identifyRequest.getBookIds() != null && identifyRequest.getBookIds().size() > 1);
        if (isBulk && !CAN_BULK_AUTO_FETCH_METADATA.isGranted(user.getPermissions())) {
            throw PERMISSION_DENIED.createException(CAN_BULK_AUTO_FETCH_METADATA);
        }
    }

    @Override
    public TaskCreateResponse execute(TaskCreateRequest request) {
        EpubTextIdentifyRequest identifyRequest = request.getOptionsAs(EpubTextIdentifyRequest.class);
        String taskId = request.getTaskId();

        long start = System.currentTimeMillis();
        log.info("{}: Task started. TaskId: {}, Options: {}", getTaskType(), taskId, identifyRequest);

        epubTextIdentifyService.identify(identifyRequest, taskId);

        log.info("{}: Task completed in {} ms", getTaskType(), System.currentTimeMillis() - start);
        return TaskCreateResponse.builder()
                .taskType(TaskType.EPUB_TEXT_IDENTIFY)
                .taskId(taskId)
                .status(TaskStatus.COMPLETED)
                .build();
    }
}
