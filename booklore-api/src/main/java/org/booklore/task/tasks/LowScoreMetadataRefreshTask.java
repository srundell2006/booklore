package org.booklore.task.tasks;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.request.LowScoreMetadataRefreshOptions;
import org.booklore.model.dto.request.MetadataRefreshOptions;
import org.booklore.model.dto.request.MetadataRefreshOptions.EnabledFields;
import org.booklore.model.dto.request.MetadataRefreshOptions.FieldOptions;
import org.booklore.model.dto.request.MetadataRefreshOptions.FieldProvider;
import org.booklore.model.dto.request.MetadataRefreshRequest;
import org.booklore.model.dto.request.TaskCreateRequest;
import org.booklore.model.dto.response.TaskCreateResponse;
import org.booklore.model.enums.MetadataProvider;
import org.booklore.model.enums.MetadataReplaceMode;
import org.booklore.model.enums.TaskType;
import org.booklore.repository.BookRepository;
import org.booklore.service.metadata.MetadataRefreshService;
import org.booklore.task.TaskStatus;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;

@RequiredArgsConstructor
@Component
@Slf4j
public class LowScoreMetadataRefreshTask implements Task {

    private static final float DEFAULT_SCORE_THRESHOLD = 70f;
    private static final int DEFAULT_BATCH_SIZE = 1000;

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

        LowScoreMetadataRefreshOptions opts = request.getOptionsAs(LowScoreMetadataRefreshOptions.class);
        float threshold = (opts != null) ? opts.getScoreThreshold() : DEFAULT_SCORE_THRESHOLD;
        int batchSize  = (opts != null && opts.getBatchSize() > 0) ? opts.getBatchSize() : DEFAULT_BATCH_SIZE;

        log.info("{}: Task started. TaskId: {}, threshold: {}, batchSize: {}", getTaskType(), taskId, threshold, batchSize);

        // Resolve which providers are actually enabled in settings
        List<MetadataProvider> enabledProviders = metadataRefreshService.getEnabledProviders();
        log.info("{}: Enabled metadata providers: {}", getTaskType(), enabledProviders);

        if (enabledProviders.isEmpty()) {
            log.warn("{}: No metadata providers are enabled. Enable at least one provider in Settings > Metadata Providers.", getTaskType());
            return TaskCreateResponse.builder()
                    .taskType(getTaskType())
                    .taskId(taskId)
                    .status(TaskStatus.COMPLETED)
                    .build();
        }

        List<Long> bookIds = bookRepository.findBookIdsWithLowMetadataScore(
                threshold,
                PageRequest.of(0, batchSize)
        );

        if (bookIds.isEmpty()) {
            log.info("{}: No books below score threshold {}, nothing to do.", getTaskType(), threshold);
            return TaskCreateResponse.builder()
                    .taskType(getTaskType())
                    .taskId(taskId)
                    .status(TaskStatus.COMPLETED)
                    .build();
        }

        log.info("{}: Refreshing metadata for {} books with score < {} using providers: {}",
                getTaskType(), bookIds.size(), threshold, enabledProviders);

        // Build a FieldProvider that tries enabled providers in order (P1, P2, P3, P4)
        FieldProvider fp = FieldProvider.builder()
                .p1(enabledProviders.size() > 0 ? enabledProviders.get(0) : null)
                .p2(enabledProviders.size() > 1 ? enabledProviders.get(1) : null)
                .p3(enabledProviders.size() > 2 ? enabledProviders.get(2) : null)
                .p4(enabledProviders.size() > 3 ? enabledProviders.get(3) : null)
                .p5(enabledProviders.size() > 4 ? enabledProviders.get(4) : null)
                .build();

        FieldOptions fieldOptions = FieldOptions.builder()
                .title(fp).subtitle(fp).description(fp).authors(fp)
                .publisher(fp).publishedDate(fp).seriesName(fp).seriesNumber(fp)
                .seriesTotal(fp).isbn13(fp).isbn10(fp).language(fp)
                .categories(fp).cover(fp).pageCount(fp)
                .asin(fp).goodreadsId(fp).googleId(fp).hardcoverId(fp)
                .goodreadsRating(fp).goodreadsReviewCount(fp)
                .amazonRating(fp).amazonReviewCount(fp)
                .hardcoverRating(fp).hardcoverReviewCount(fp)
                .build();

        MetadataRefreshOptions refreshOptions = MetadataRefreshOptions.builder()
                .refreshCovers(false)
                .mergeCategories(true)
                .reviewBeforeApply(false)
                .replaceMode(MetadataReplaceMode.REPLACE_MISSING)
                .skipComplete(false)
                .fieldOptions(fieldOptions)
                .enabledFields(EnabledFields.builder().build()) // all default to true
                .build();

        MetadataRefreshRequest refreshRequest = MetadataRefreshRequest.builder()
                .refreshType(MetadataRefreshRequest.RefreshType.BOOKS)
                .bookIds(new LinkedHashSet<>(bookIds))
                .refreshOptions(refreshOptions)
                .build();

        int updatedCount = metadataRefreshService.refreshMetadata(refreshRequest, taskId, TaskType.LOW_SCORE_METADATA_REFRESH);

        // Stamp all processed books so they go to the back of the queue next run
        // Batch in chunks of 100 to avoid long IN-clause locks on the book table
        LocalDateTime now = LocalDateTime.now();
        for (int i = 0; i < bookIds.size(); i += 100) {
            bookRepository.updateLastMetadataRefreshAt(
                    bookIds.subList(i, Math.min(i + 100, bookIds.size())), now);
        }

        long duration = System.currentTimeMillis() - startTime;
        log.info("{}: Task completed in {} ms — {} books processed, {} actually updated",
                getTaskType(), duration, bookIds.size(), updatedCount);

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
