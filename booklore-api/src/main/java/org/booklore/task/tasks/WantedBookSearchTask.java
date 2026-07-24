package org.booklore.task.tasks;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.exception.ApiError;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.request.TaskCreateRequest;
import org.booklore.model.dto.response.TaskCreateResponse;
import org.booklore.model.enums.TaskType;
import org.booklore.model.enums.UserPermission;
import org.booklore.service.acquisition.BookAcquisitionService;
import org.booklore.task.TaskStatus;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class WantedBookSearchTask implements Task {

    private final BookAcquisitionService bookAcquisitionService;

    @Override
    public void validatePermissions(BookLoreUser user, TaskCreateRequest request) {
        if (!UserPermission.IS_ADMIN.isGranted(user.getPermissions())) {
            throw ApiError.PERMISSION_DENIED.createException(UserPermission.IS_ADMIN);
        }
    }

    @Override
    public TaskCreateResponse execute(TaskCreateRequest request) {
        TaskCreateResponse.TaskCreateResponseBuilder builder = TaskCreateResponse.builder()
                .taskId(UUID.randomUUID().toString())
                .taskType(getTaskType());

        long startTime = System.currentTimeMillis();
        log.info("{}: Task started", getTaskType());
        try {
            bookAcquisitionService.searchAllWanted();
            builder.status(TaskStatus.COMPLETED);
        } catch (Exception e) {
            log.error("{}: Error during wanted book search", getTaskType(), e);
            builder.status(TaskStatus.FAILED);
        }
        log.info("{}: Task completed. Duration: {} ms", getTaskType(), System.currentTimeMillis() - startTime);
        return builder.build();
    }

    @Override
    public TaskType getTaskType() {
        return TaskType.WANTED_BOOK_SEARCH;
    }
}
