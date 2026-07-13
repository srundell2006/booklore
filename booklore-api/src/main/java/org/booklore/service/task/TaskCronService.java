package org.booklore.service.task;

import org.booklore.config.security.service.AuthenticationService;
import org.booklore.exception.APIException;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.request.TaskCronConfigRequest;
import org.booklore.model.dto.response.CronConfig;
import org.booklore.model.entity.TaskCronConfigurationEntity;
import org.booklore.model.enums.TaskType;
import org.booklore.repository.TaskCronConfigurationRepository;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.core.type.TypeReference;

@Service
@Slf4j
@AllArgsConstructor
public class TaskCronService {

    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");
    private final TaskCronConfigurationRepository repository;
    private final AuthenticationService authService;

    @Transactional(readOnly = true)
    public List<TaskCronConfigurationEntity> getAllEnabledCronConfigs() {
        return repository.findByEnabledTrue();
    }

    @Transactional(readOnly = true)
    public CronConfig getCronConfigOrDefault(TaskType taskType) {
        validateTaskTypeForCron(taskType);
        return repository.findByTaskType(taskType)
                .map(this::mapToResponse)
                .orElse(CronConfig.builder()
                        .taskType(taskType)
                        .enabled(false)
                        .build());
    }

    @Transactional
    public CronConfig patchCronConfig(TaskType taskType, TaskCronConfigRequest request) {
        validateTaskTypeForCron(taskType);
        BookLoreUser user = authService.getAuthenticatedUser();
        TaskCronConfigurationEntity config = repository.findByTaskType(taskType)
                .orElse(TaskCronConfigurationEntity.builder()
                        .taskType(taskType)
                        .createdBy(user.getId())
                        .enabled(false)
                        .build());
        if (request.getCronExpression() != null) {
            validateCronExpression(request.getCronExpression());
            config.setCronExpression(request.getCronExpression());
        }
        if (request.getEnabled() != null) {
            config.setEnabled(request.getEnabled());
        }
        if (request.getTaskOptions() != null) {
            config.setTaskOptions(request.getTaskOptions());
        }
        config = repository.save(config);
        log.info("Updated cron configuration for task type: {}", taskType);
        return mapToResponse(config);
    }

    private void validateTaskTypeForCron(TaskType taskType) {
        if (taskType == null) {
            throw new APIException("Task type is required", HttpStatus.BAD_REQUEST);
        }
        if (!taskType.isCronSupported()) {
            throw new APIException("Task type " + taskType + " does not support cron scheduling", HttpStatus.BAD_REQUEST);
        }
    }

    private void validateCronExpression(String cronExpression) {
        if (cronExpression == null || cronExpression.trim().isEmpty()) {
            throw new APIException("Cron expression is required", HttpStatus.BAD_REQUEST);
        }
        String[] fields = WHITESPACE_PATTERN.split(cronExpression.trim());
        if (fields.length != 6) {
            throw new APIException("Invalid cron expression format. Expected 6 fields (second minute hour day month day-of-week)", HttpStatus.BAD_REQUEST);
        }
        try {
            new CronTrigger(cronExpression);
        } catch (Exception e) {
            throw new APIException("Invalid cron expression: " + cronExpression + ". Error: " + e.getMessage(), HttpStatus.BAD_REQUEST);
        }
    }

    private CronConfig mapToResponse(TaskCronConfigurationEntity config) {
        Map<String, Object> parsedOptions = null;
        if (config.getTaskOptions() != null && !config.getTaskOptions().isBlank()) {
            try {
                parsedOptions = new ObjectMapper().readValue(
                        config.getTaskOptions(),
                        new TypeReference<Map<String, Object>>() {});
            } catch (Exception e) {
                log.warn("Failed to parse taskOptions JSON for {}: {}", config.getTaskType(), e.getMessage());
            }
        }
        return CronConfig.builder()
                .id(config.getId())
                .taskType(config.getTaskType())
                .cronExpression(config.getCronExpression())
                .enabled(config.getEnabled())
                .createdAt(config.getCreatedAt())
                .updatedAt(config.getUpdatedAt())
                .options(parsedOptions)
                .build();
    }
}
