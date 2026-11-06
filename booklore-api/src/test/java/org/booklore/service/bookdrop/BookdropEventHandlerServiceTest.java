package org.booklore.service.bookdrop;

import org.booklore.model.BookDropFileEvent;
import org.booklore.model.dto.request.BookdropFinalizeRequest;
import org.booklore.model.dto.response.BookdropFileResult;
import org.booklore.model.dto.response.BookdropFinalizeResult;
import org.booklore.model.dto.settings.AppSettings;
import org.booklore.model.dto.settings.AudiobookVerificationSettings;
import org.booklore.model.entity.BookdropFileEntity;
import org.booklore.repository.BookdropFileRepository;
import org.booklore.service.NotificationService;
import org.booklore.service.appsettings.AppSettingService;
import org.booklore.service.audiobook.AudiobookVerificationService;
import org.booklore.util.OpenEntityManagerRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The auto-import as the Bookdrop file processor thread runs it, when a file whose metadata matches well enough comes
 * into the Bookdrop folder. On that thread there is no request, so the import must run inside an
 * {@link OpenEntityManagerRunner}; without one, every auto-import failed in production on 2026-11-06.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BookdropEventHandlerServiceTest {

    private static final long FILE_ID = 5L;

    @Mock
    private BookdropFileRepository bookdropFileRepository;
    @Mock
    private NotificationService notificationService;
    @Mock
    private BookdropNotificationService bookdropNotificationService;
    @Mock
    private AppSettingService appSettingService;
    @Mock
    private BookdropMetadataService bookdropMetadataService;
    @Mock
    private OpenEntityManagerRunner openEntityManagerRunner;
    @Mock
    private BookDropService bookDropService;
    @Mock
    private AudiobookVerificationService audiobookVerificationService;

    @InjectMocks
    private BookdropEventHandlerService handler;

    @TempDir
    Path dropFolder;

    private final AtomicBoolean insideRunner = new AtomicBoolean();
    private final List<Boolean> importRanInsideRunner = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        // Field-injected and @Lazy in the service, so not set by the constructor
        ReflectionTestUtils.setField(handler, "bookDropService", bookDropService);
        ReflectionTestUtils.setField(handler, "audiobookVerificationService", audiobookVerificationService);

        when(appSettingService.getAppSettings()).thenReturn(AppSettings.builder()
                .metadataDownloadOnBookdrop(true)
                .bookdropAutoImportEnabled(true)
                .bookdropAutoImportLibraryId(1L)
                .bookdropAutoImportPathId(2L)
                .bookdropAutoImportMinScore(50)
                .audiobookVerificationSettings(AudiobookVerificationSettings.builder().enabled(true).build())
                .build());
        when(bookdropFileRepository.findByFilePath(any())).thenReturn(Optional.empty());
        when(bookdropFileRepository.save(any(BookdropFileEntity.class))).thenAnswer(invocation -> {
            BookdropFileEntity entity = invocation.getArgument(0);
            entity.setId(FILE_ID);
            return entity;
        });
        when(bookdropFileRepository.findById(FILE_ID))
                .thenAnswer(invocation -> Optional.of(BookdropFileEntity.builder().id(FILE_ID).matchScore(90).build()));
        when(openEntityManagerRunner.call(any())).thenAnswer(invocation -> {
            insideRunner.set(true);
            try {
                return ((Supplier<?>) invocation.getArgument(0)).get();
            } finally {
                insideRunner.set(false);
            }
        });
    }

    @Test
    void theAutoImportRunsInsideAnOpenPersistenceContext() throws IOException {
        importReturns(imported("book.epub"));

        handler.processFile(created("book.epub"));

        assertThat(importRanInsideRunner).containsExactly(true);
        ArgumentCaptor<BookdropFinalizeRequest> request = ArgumentCaptor.forClass(BookdropFinalizeRequest.class);
        verify(bookDropService).finalizeImport(request.capture());
        assertThat(request.getValue().getFiles()).singleElement().satisfies(file -> {
            assertThat(file.getFileId()).isEqualTo(FILE_ID);
            assertThat(file.getLibraryId()).isEqualTo(1L);
            assertThat(file.getPathId()).isEqualTo(2L);
        });
    }

    @Test
    void anAudiobookThatGoesInIsVerified() throws IOException {
        importReturns(imported("book.m4b"));

        handler.processFile(created("book.m4b"));

        verify(audiobookVerificationService, timeout(5_000)).runVerification(any(), anyString());
    }

    @Test
    void anAudiobookWhoseImportFailsIsNotVerified() throws IOException {
        // finalizeImport reports a failed file in its result; it does not throw
        importReturns(BookdropFinalizeResult.builder()
                .totalFiles(1)
                .failed(1)
                .results(List.of(BookdropFileResult.builder().fileName("book.m4b").success(false)
                        .message("Processing failed: Kobo shelf failed").build()))
                .build());

        handler.processFile(created("book.m4b"));

        assertThat(importRanInsideRunner).containsExactly(true);
        verify(audiobookVerificationService, after(1_000).never()).runVerification(any(), anyString());
    }

    private void importReturns(BookdropFinalizeResult result) {
        when(bookDropService.finalizeImport(any())).thenAnswer(invocation -> {
            importRanInsideRunner.add(insideRunner.get());
            return result;
        });
    }

    private static BookdropFinalizeResult imported(String fileName) {
        return BookdropFinalizeResult.builder()
                .totalFiles(1)
                .successfullyImported(1)
                .results(List.of(BookdropFileResult.builder().fileName(fileName).success(true).message("imported").build()))
                .build();
    }

    private BookDropFileEvent created(String fileName) throws IOException {
        Path file = dropFolder.resolve(fileName);
        Files.write(file, new byte[]{1, 2, 3});
        return new BookDropFileEvent(file, StandardWatchEventKinds.ENTRY_CREATE);
    }
}
