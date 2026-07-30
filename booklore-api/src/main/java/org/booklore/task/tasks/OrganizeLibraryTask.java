package org.booklore.task.tasks;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.request.OrganizeLibraryRequest;
import org.booklore.model.dto.request.TaskCreateRequest;
import org.booklore.model.dto.response.TaskCreateResponse;
import org.booklore.model.enums.TaskType;
import org.booklore.service.library.LibraryOrganizeService;
import org.booklore.task.TaskStatus;
import org.springframework.stereotype.Component;

@AllArgsConstructor
@Component
@Slf4j
public class OrganizeLibraryTask implements Task {

    private final LibraryOrganizeService libraryOrganizeService;

    @Override
    public TaskType getTaskType() {
        return TaskType.ORGANIZE_LIBRARY;
    }

    @Override
    public void validatePermissions(BookLoreUser user, TaskCreateRequest request) {
        // Admin-level operation; permission enforced at the task-service layer
    }

    @Override
    public TaskCreateResponse execute(TaskCreateRequest request) {
        OrganizeLibraryRequest options = request.getOptionsAs(OrganizeLibraryRequest.class);
        String taskId = request.getTaskId();

        log.info("{}: Task started. TaskId: {}, libraryId: {}", getTaskType(), taskId,
                options != null ? options.getLibraryId() : "null");

        long start = System.currentTimeMillis();
        libraryOrganizeService.organizeLibrary(options.getLibraryId());
        log.info("{}: Task completed in {} ms", getTaskType(), System.currentTimeMillis() - start);

        return TaskCreateResponse.builder()
                .taskType(TaskType.ORGANIZE_LIBRARY)
                .taskId(taskId)
                .status(TaskStatus.COMPLETED)
                .build();
    }
}
