package org.booklore.service.audiobook;

import org.booklore.model.dto.settings.AppSettings;
import org.booklore.model.dto.settings.AudiobookMergeSettings;
import org.booklore.service.appsettings.AppSettingService;
import org.booklore.service.monitoring.MonitoringPause;
import org.booklore.service.monitoring.MonitoringRegistrationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A merge stages an audiobook's files out of its library and back, so the library's watching is paused around it.
 * The merge service is made to fail here, which is the quickest way through a whole merge: staged out, submitted,
 * restored, and watching resumed.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AudiobookMergeMonitoringTest {

    private static final long LIBRARY_ID = 7L;

    @Mock
    private MergeContextLoader contextLoader;
    @Mock
    private MonitoringRegistrationService monitoringRegistrationService;
    @Mock
    private AppSettingService appSettingService;
    @Mock
    private M4bMergeClient mergeClient;
    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private AudiobookMergeService mergeService;

    @TempDir
    Path tmp;

    private Path book;

    @BeforeEach
    void setUp() throws IOException {
        Path staging = Files.createDirectories(tmp.resolve("staging"));
        when(appSettingService.getAppSettings()).thenReturn(AppSettings.builder()
                .audiobookMergeSettings(AudiobookMergeSettings.builder()
                        .enabled(true)
                        .stagingPath(staging.toString())
                        .serviceStagingPath("/merge")
                        .build())
                .build());
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        when(mergeClient.submit(any(), anyString(), anyString(), anyMap()))
                .thenThrow(new IllegalStateException("merge service unavailable"));

        book = Files.createDirectories(tmp.resolve("library").resolve("Author").resolve("Title"));
        Files.write(book.resolve("01.mp3"), new byte[]{1});
        Files.write(book.resolve("02.mp3"), new byte[]{2});
    }

    @Test
    void aMergePausesTheLibraryAndResumesWhatItPaused() {
        MonitoringPause pause = new MonitoringPause(Set.of(LIBRARY_ID), Map.of());
        when(monitoringRegistrationService.pauseLibraries(Set.of(LIBRARY_ID))).thenReturn(pause);

        assertThatThrownBy(() -> mergeService.mergeBook(context(), (percent, message) -> {}))
                .hasMessageContaining("merge service unavailable");

        var inOrder = inOrder(monitoringRegistrationService, mergeClient);
        inOrder.verify(monitoringRegistrationService).pauseLibraries(Set.of(LIBRARY_ID));
        inOrder.verify(mergeClient).submit(any(), anyString(), anyString(), anyMap());
        inOrder.verify(monitoringRegistrationService).resume(pause);
        verify(monitoringRegistrationService, never()).registerLibraryPaths(any(), any());
        verify(monitoringRegistrationService, never()).unregisterLibrary(any());
        // The files went back where they were
        assertThat(book.resolve("01.mp3")).exists();
        assertThat(book.resolve("02.mp3")).exists();
    }

    @Test
    void aMergeInALibraryWithNothingWatchedResumesNothing() {
        when(monitoringRegistrationService.pauseLibraries(Set.of(LIBRARY_ID))).thenReturn(MonitoringPause.NONE);

        assertThatThrownBy(() -> mergeService.mergeBook(context(), (percent, message) -> {}));

        verify(monitoringRegistrationService).pauseLibraries(Set.of(LIBRARY_ID));
        verify(monitoringRegistrationService, never()).resume(any());
        verify(monitoringRegistrationService, never()).registerLibraryPaths(any(), any());
    }

    private MergeContext context() {
        return new MergeContext(1L, LIBRARY_ID, book.toString(), true, "Title", null, null, null, null, null);
    }
}
