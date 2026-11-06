package org.booklore.service.monitoring;

import org.booklore.repository.LibraryPathRepository;
import org.booklore.repository.LibraryRepository;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MonitoringRegistrationServiceTest {

    @Mock
    LibraryWatchService libraryWatchService;
    @Mock
    LibraryRepository libraryRepository;
    @Mock
    LibraryPathRepository libraryPathRepository;

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
        when(libraryWatchService.getPathsForLibraries(Set.of(2L))).thenReturn(Set.of(sub1));
        when(libraryWatchService.isLibraryMonitored(3L)).thenReturn(false);
        when(libraryWatchService.getPathsForLibraries(Set.of(3L))).thenReturn(Set.of());

        MonitoringPause pause = registrationService.pauseLibraries(java.util.Arrays.asList(1L, 2L, 3L, null, 1L));

        assertEquals(Set.of(1L), pause.watchedLibraries());
        assertEquals(Map.of(sub1, 2L), pause.folders());
        verify(libraryWatchService).unregisterLibrary(1L);
        verify(libraryWatchService).unregisterPath(sub1);
        verify(libraryWatchService, never()).unregisterLibrary(2L);
        verify(libraryWatchService, never()).unregisterLibrary(3L);
        verify(libraryWatchService, never()).registerLibraryPaths(anyLong(), any());
    }

    @Test
    void pauseLibraries_withNoLibraries_pausesNothing() {
        assertTrue(registrationService.pauseLibraries(null).isEmpty());
        assertTrue(registrationService.pauseLibraries(List.of()).isEmpty());
        verifyNoInteractions(libraryWatchService);
    }

    @Test
    void resume_registersAWatchedLibraryInFullAndAnUnwatchedOnesFoldersOnly() {
        Path gone = tmp.resolve("gone");
        MonitoringPause pause = new MonitoringPause(Set.of(1L), Map.of(sub2, 2L, gone, 2L));
        when(libraryRepository.findWatchById(1L)).thenReturn(Optional.of(true));
        when(libraryPathRepository.findPathsByLibraryId(1L)).thenReturn(List.of(root.toString()));
        when(libraryRepository.existsById(2L)).thenReturn(true);

        registrationService.resume(pause);

        verify(libraryWatchService).resumeLibrary(1L, List.of(root));
        verify(libraryWatchService).registerPath(sub2, 2L);
        verify(libraryWatchService, never()).registerPath(eq(gone), anyLong());
        verify(libraryWatchService, never()).registerLibraryPaths(anyLong(), any());
        verify(libraryWatchService, never()).resumeLibrary(eq(2L), any());
    }

    @Test
    void resume_leavesUnwatchedALibrarySetNotToBeWatchedOrDeletedWhileItWasPaused() {
        // Library 1 had its watching turned off meanwhile, library 4 was deleted
        MonitoringPause pause = new MonitoringPause(Set.of(1L, 4L), Map.of());
        when(libraryRepository.findWatchById(1L)).thenReturn(Optional.of(false));
        when(libraryRepository.findWatchById(4L)).thenReturn(Optional.empty());

        registrationService.resume(pause);

        verify(libraryWatchService, never()).resumeLibrary(anyLong(), any());
        verify(libraryWatchService, never()).registerPath(any(), anyLong());
        verifyNoInteractions(libraryPathRepository);
    }

    @Test
    void resume_doesNotWatchAgainTheFoldersOfALibraryDeletedWhileItWasPaused() {
        MonitoringPause pause = new MonitoringPause(Set.of(), Map.of(sub1, 3L, sub2, 3L));
        when(libraryRepository.existsById(3L)).thenReturn(false);

        registrationService.resume(pause);

        verify(libraryWatchService, never()).registerPath(any(), anyLong());
        // Looked up once for the library, not once for each of its folders
        verify(libraryRepository, times(1)).existsById(3L);
    }

    @Test
    void resume_neverThrows_andGoesOnPastWhatItCannotWatchAgain() {
        MonitoringPause pause = new MonitoringPause(Set.of(1L, 5L), Map.of(sub1, 2L, sub2, 2L));
        when(libraryRepository.findWatchById(1L)).thenThrow(new IllegalStateException("database unavailable"));
        when(libraryRepository.findWatchById(5L)).thenReturn(Optional.of(true));
        when(libraryPathRepository.findPathsByLibraryId(5L)).thenReturn(List.of(root.toString()));
        when(libraryRepository.existsById(2L)).thenReturn(true);
        // As registerPath does when the host is out of inotify watches
        when(libraryWatchService.registerPath(sub1, 2L)).thenThrow(new RuntimeException("inotify watch limit reached"));

        assertDoesNotThrow(() -> registrationService.resume(pause));

        verify(libraryWatchService).resumeLibrary(5L, List.of(root));
        verify(libraryWatchService).registerPath(sub1, 2L);
        verify(libraryWatchService).registerPath(sub2, 2L);
    }

    @Test
    void resume_ofNothing_doesNothing() {
        registrationService.resume(MonitoringPause.NONE);
        registrationService.resume(null);
        verifyNoInteractions(libraryWatchService, libraryRepository, libraryPathRepository);
    }
}
