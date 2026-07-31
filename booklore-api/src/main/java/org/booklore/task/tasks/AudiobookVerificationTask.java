package org.booklore.task.tasks;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.request.AudiobookVerificationRequest;
import org.booklore.model.dto.request.TaskCreateRequest;
import org.booklore.model.dto.response.TaskCreateResponse;
import org.booklore.model.enums.TaskType;
import org.booklore.service.audiobook.AudiobookVerificationService;
import org.booklore.task.TaskStatus;
import org.springframework.stereotype.Component;

@AllArgsConstructor
@Component
@Slf4j
public class AudiobookVerificationTask implements Task {

    private final AudiobookVerificationService audiobookVerificationService;

    @Override
    public TaskType getTaskType() {
        return TaskType.AUDIOBOOK_VERIFICATION;
    }

    @Override
    public void validatePermissions(BookLoreUser user, TaskCreateRequest request) {
        // Admin permission is enforced at the task-dispatch layer.
    }

    @Override
    public TaskCreateResponse execute(TaskCreateRequest request) {
        AudiobookVerificationRequest verifyRequest = request.getOptionsAs(AudiobookVerificationRequest.class);
        String taskId = request.getTaskId();

        long start = System.currentTimeMillis();
        log.info("{}: Task started. TaskId: {}, Options: {}", getTaskType(), taskId, verifyRequest);

        audiobookVerificationService.runVerification(verifyRequest, taskId);

        log.info("{}: Task completed in {} ms", getTaskType(), System.currentTimeMillis() - start);
        return TaskCreateResponse.builder()
                .taskType(TaskType.AUDIOBOOK_VERIFICATION)
                .taskId(taskId)
                .status(TaskStatus.COMPLETED)
                .build();
    }
}
