package org.booklore.service.monitoring;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MonitoringRegistrationServiceTest {

    @Mock
    LibraryWatchService libraryWatchService;

    @InjectMocks
    MonitoringRegistrationService registrationService;

    @TempDir
    Path tmp;

    Path root;
    Path sub1;
    Path sub2;

    @BeforeEach
    void setupFs() throws IOException {
        root = tmp.resolve("libroot");
        sub1 = root.resolve("a");
        sub2 = root.resolve("a").resolve("b");
        Files.createDirectories(sub2);
    }

    @Test
    void isPathMonitored_delegatesToLibraryWatchService() {
        when(libraryWatchService.isPathMonitored(root)).thenReturn(true);
        assertTrue(registrationService.isPathMonitored(root));
        verify(libraryWatchService).isPathMonitored(root);
    }

    @Test
    void unregisterSpecificPath_delegates() {
        registrationService.unregisterSpecificPath(root);
        verify(libraryWatchService).unregisterPath(root);
    }

    @Test
    void registerSpecificPath_delegates() {
        registrationService.registerSpecificPath(root, 123L);
        verify(libraryWatchService).registerPath(root, 123L);
    }

    @Test
    void unregisterLibrary_delegates() {
        registrationService.unregisterLibrary(99L);
        verify(libraryWatchService).unregisterLibrary(99L);
    }

    @Test
    void registerLibraryPaths_noopWhenMissingOrNotDirectory() throws IOException {
        Path missing = tmp.resolve("does-not-exist");
        registrationService.registerLibraryPaths(7L, missing);
        verify(libraryWatchService).registerLibraryPaths(7L, missing);

        Path file = tmp.resolve("afile.txt");
        Files.writeString(file, "x");
        registrationService.registerLibraryPaths(7L, file);
        verify(libraryWatchService).registerLibraryPaths(7L, file);
    }

    @Test
    void registerLibraryPaths_delegates() {
        registrationService.registerLibraryPaths(42L, root);
        verify(libraryWatchService).registerLibraryPaths(42L, root);
    }

    @Test
    void pauseLibraries_unregistersAWatchedLibraryInFullAndJustTheRegisteredFoldersOfAnUnwatchedOne() {
        when(libraryWatchService.isLibraryMonitored(1L)).thenReturn(true);
        when(libraryWatchService.isLibraryMonitored(2L)).thenReturn(false);
        when(libraryWatchService.getPathsForLibraries(java.util.Set.of(2L))).thenReturn(java.util.Set.of(sub1));
        when(libraryWatchService.isLibraryMonitored(3L)).thenReturn(false);
        when(libraryWatchService.getPathsForLibraries(java.util.Set.of(3L))).thenReturn(java.util.Set.of());

        MonitoringPause pause = registrationService.pauseLibraries(java.util.Arrays.asList(1L, 2L, 3L, null, 1L));

        assertEquals(java.util.Set.of(1L), pause.watchedLibraries());
        assertEquals(java.util.Map.of(sub1, 2L), pause.folders());
        verify(libraryWatchService).unregisterLibrary(1L);
        verify(libraryWatchService).unregisterPath(sub1);
        verify(libraryWatchService, never()).unregisterLibrary(2L);
        verify(libraryWatchService, never()).unregisterLibrary(3L);
        verify(libraryWatchService, never()).registerLibraryPaths(anyLong(), any());
    }

    @Test
    void pauseLibraries_withNoLibraries_pausesNothing() {
        assertTrue(registrationService.pauseLibraries(null).isEmpty());
        assertTrue(registrationService.pauseLibraries(java.util.List.of()).isEmpty());
        verifyNoInteractions(libraryWatchService);
    }

    @Test
    void resume_registersAWatchedLibraryInFullAndAnUnwatchedOnesFoldersOnly() throws IOException {
        Path gone = tmp.resolve("gone");
        MonitoringPause pause = new MonitoringPause(java.util.Set.of(1L, 4L), java.util.Map.of(sub2, 2L, gone, 2L));

        registrationService.resume(pause, id -> id == 1L ? java.util.List.of(root) : null);

        verify(libraryWatchService).resumeLibrary(1L, java.util.List.of(root));
        verify(libraryWatchService, never()).resumeLibrary(eq(4L), any());
        verify(libraryWatchService).registerPath(sub2, 2L);
        verify(libraryWatchService, never()).registerPath(eq(gone), anyLong());
        verify(libraryWatchService, never()).registerLibraryPaths(anyLong(), any());
        verify(libraryWatchService, never()).resumeLibrary(eq(2L), any());
    }

    @Test
    void resume_ofNothing_doesNothing() {
        registrationService.resume(MonitoringPause.NONE, id -> java.util.List.of(root));
        registrationService.resume(null, id -> java.util.List.of(root));
        verifyNoInteractions(libraryWatchService);
    }
}
