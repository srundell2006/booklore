package org.booklore.service.library;

import org.booklore.exception.ApiError;
import org.booklore.model.MetadataUpdateContext;
import org.booklore.model.MetadataUpdateWrapper;
import org.booklore.model.dto.BookMetadata;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.websocket.TaskProgressPayload;
import org.booklore.model.websocket.Topic;
import org.booklore.repository.LibraryRepository;
import org.booklore.repository.BookRepository;
import org.booklore.service.NotificationService;
import org.booklore.service.fileprocessor.AudiobookProcessor;
import org.booklore.service.metadata.BookMetadataUpdater;
import org.booklore.service.metadata.extractor.MetadataExtractorFactory;
import org.booklore.task.options.RescanLibraryContext;
import org.booklore.task.TaskCancellationManager;
import org.booklore.task.TaskStatus;
import org.booklore.model.enums.BookFileType;
import org.booklore.model.enums.TaskType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

@Slf4j
@Service
public class LibraryRescanHelper {

    private final LibraryRepository libraryRepository;
    private final MetadataExtractorFactory metadataExtractorFactory;
    private final BookMetadataUpdater bookMetadataUpdater;
    private final NotificationService notificationService;
    private final TaskCancellationManager cancellationManager;
    private final BookRepository bookRepository;
    private final AudiobookProcessor audiobookProcessor;
    private final PlatformTransactionManager transactionManager;

    public LibraryRescanHelper(LibraryRepository libraryRepository, MetadataExtractorFactory metadataExtractorFactory, @Lazy BookMetadataUpdater bookMetadataUpdater, NotificationService notificationService, TaskCancellationManager cancellationManager, BookRepository bookRepository, AudiobookProcessor audiobookProcessor, PlatformTransactionManager transactionManager) {
        this.libraryRepository = libraryRepository;
        this.metadataExtractorFactory = metadataExtractorFactory;
        this.bookMetadataUpdater = bookMetadataUpdater;
        this.notificationService = notificationService;
        this.cancellationManager = cancellationManager;
        this.bookRepository = bookRepository;
        this.audiobookProcessor = audiobookProcessor;
        this.transactionManager = transactionManager;
    }

    /**
     * Reads each book's metadata from its file again and saves it. The books are listed in one
     * read-only transaction, and each book is saved in a transaction of its own. With "move files
     * to library pattern" on, saving a book's metadata can move its files, and their new paths
     * must then be committed right after the move: a refresh over a large library that stops
     * partway (a restart) must never leave files moved while their rows still name the old paths,
     * or the next rescan would delete those books and import their files as new ones.
     */
    @Transactional(readOnly = true)
    public void handleRescanOptions(RescanLibraryContext context, String taskId) {

        LibraryEntity library = libraryRepository.findById(context.getLibraryId()).orElseThrow(() -> ApiError.LIBRARY_NOT_FOUND.createException(context.getLibraryId()));

        List<BookEntity> bookEntities = bookRepository.findAllWithMetadataByLibraryId(library.getId());

        log.info("Found {} book(s) to process in library id={}", bookEntities.size(), library.getId());

        int totalBooks = bookEntities.size();
        int processedBooks = 0;

        sendTaskProgressNotification(taskId, 0, String.format("Starting rescan for library: %s", library.getName()), TaskStatus.IN_PROGRESS);

        for (BookEntity bookEntity : bookEntities) {
            if (bookEntity == null || (bookEntity.getDeleted() != null && bookEntity.getDeleted())) {
                continue;
            }

            // Skip fileless books (e.g., physical books) - they have no file to extract metadata from
            if (!bookEntity.hasFiles()) {
                processedBooks++;
                continue;
            }

            if (taskId != null && cancellationManager.isTaskCancelled(taskId)) {
                log.info("Library rescan for library {} was cancelled", library.getId());
                sendTaskProgressNotification(taskId, (processedBooks * 100) / totalBooks,
                        String.format("Rescan cancelled for library: %s (%d/%d books processed)", library.getName(), processedBooks, totalBooks),
                        TaskStatus.CANCELLED);
                break;
            }

            log.info("Processing book: library={}, bookId={}, fileName={}", library.getName(), bookEntity.getId(), bookEntity.getPrimaryBookFile().getFileName());

            int progressPercentage = totalBooks > 0 ? (processedBooks * 100) / totalBooks : 0;

            sendTaskProgressNotification(taskId, progressPercentage,
                    String.format("Processing: %s (Library: %s)", bookEntity.getPrimaryBookFile().getFileName(), library.getName()),
                    TaskStatus.IN_PROGRESS);

            try {
                refreshBookMetadata(bookEntity.getId(), context);
            } catch (Exception e) {
                log.error("Failed to update metadata for book id={} path={}: {}", bookEntity.getId(), bookEntity.getFullFilePath(), e.getMessage(), e);
            } finally {
                processedBooks++;
            }
        }

        if (taskId == null || !cancellationManager.isTaskCancelled(taskId)) {
            sendTaskProgressNotification(taskId, 100,
                    String.format("Rescan completed for library: %s (%d books processed)", library.getName(), processedBooks),
                    TaskStatus.COMPLETED);
        }
    }

    private void refreshBookMetadata(Long bookId, RescanLibraryContext context) {
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transactionTemplate.executeWithoutResult(status -> {
            BookEntity bookEntity = bookRepository.findByIdWithBookFiles(bookId).orElse(null);
            if (bookEntity == null || !bookEntity.hasFiles()) {
                log.warn("Book id={} was deleted or lost its files before its metadata was refreshed", bookId);
                return;
            }
            BookMetadata bookMetadata = metadataExtractorFactory.extractMetadata(bookEntity.getPrimaryBookFile().getBookType(), bookEntity.getFullFilePath().toFile());
            if (bookMetadata == null) {
                log.warn("No metadata extracted for book id={} path={}", bookEntity.getId(), bookEntity.getFullFilePath());
                return;
            }
            MetadataUpdateContext metadataUpdateContext = MetadataUpdateContext.builder()
                    .bookEntity(bookEntity)
                    .metadataUpdateWrapper(
                            MetadataUpdateWrapper.builder()
                                    .metadata(bookMetadata)
                                    .build()
                    )
                    .replaceMode(context.getOptions().getMetadataReplaceMode())
                    .updateThumbnail(false)
                    .mergeCategories(false)
                    .mergeMoods(true)
                    .mergeTags(true)
                    .build();
            bookMetadataUpdater.setBookMetadata(metadataUpdateContext);

            if (bookEntity.getPrimaryBookFile().getBookType() == BookFileType.AUDIOBOOK && bookMetadata.getAudiobookMetadata() != null) {
                audiobookProcessor.setAudiobookTechnicalMetadata(bookEntity, bookMetadata);
            }
        });
    }

    private void sendTaskProgressNotification(String taskId, int progress, String message, TaskStatus taskStatus) {
        try {
            TaskProgressPayload payload = TaskProgressPayload.builder()
                    .taskId(taskId)
                    .taskType(TaskType.REFRESH_LIBRARY_METADATA)
                    .message(message)
                    .progress(progress)
                    .taskStatus(taskStatus)
                    .build();

            notificationService.sendMessage(Topic.TASK_PROGRESS, payload);
        } catch (Exception e) {
            log.error("Failed to send task progress notification for taskId={}: {}", taskId, e.getMessage(), e);
        }
    }
}