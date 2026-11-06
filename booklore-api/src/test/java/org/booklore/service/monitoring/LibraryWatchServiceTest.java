package org.booklore.service.monitoring;

import org.booklore.model.dto.Library;
import org.booklore.model.dto.LibraryPath;
import org.booklore.model.enums.BookFileExtension;
import org.booklore.repository.LibraryPathRepository;
import org.booklore.repository.LibraryRepository;
import org.booklore.service.watcher.LibraryFileEventProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardWatchEventKinds;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class LibraryWatchServiceTest {

    @TempDir
    Path tmp;

    LibraryWatchService service;
    LibraryFileEventProcessor processor;

    @BeforeEach
    void setup() throws Exception {
        processor = mock(LibraryFileEventProcessor.class);
        service = new LibraryWatchService(processor);
    }

    @AfterEach
    void teardown() {
        try {
            service.shutdown();
        } catch (Exception ignored) {}
    }

    @Test
    void start_pollsForEventsOnAPlatformDaemonThread() throws Exception {
        Path root = tmp.resolve("polled");
        Files.createDirectories(root);
        service.registerPath(root, 5L);
        CompletableFuture<Thread> polledOn = new CompletableFuture<>();
        doAnswer(inv -> polledOn.complete(Thread.currentThread()))
                .when(processor).processEvent(any(), anyLong(), any(), anyBoolean());

        service.start();
        Files.createDirectories(root.resolve("sub"));

        Thread poll = polledOn.get(10, TimeUnit.SECONDS);
        assertThat(poll.isVirtual()).isFalse();
        assertThat(poll.isDaemon()).isTrue();
        assertThat(poll.getName()).isEqualTo("watch-poll");
    }

    @Test
    void aFileThatIsNoBookFileArrivingIsNotedAsAWriteAndNotHandled() throws Exception {
        // A mover writing cover art or extras is still at work in the library: the copies waiting there wait for it
        Path root = Files.createDirectories(tmp.resolve("noted"));
        service.registerPath(root, 5L);
        service.start();

        Path cover = Files.writeString(root.resolve("cover.jpg"), "image");
        verify(processor, timeout(10000)).noteFileArrived(5L, cover);
        Files.delete(cover);
        Path book = Files.writeString(root.resolve("book.epub"), "book");
        verify(processor, timeout(10000)).processEvent(StandardWatchEventKinds.ENTRY_CREATE, 5L, book, false);

        // Neither handled as a file of the library nor noted again for its deletion
        verify(processor, never()).processEvent(any(), anyLong(), eq(cover), anyBoolean());
        verify(processor, times(1)).noteFileArrived(anyLong(), any());
    }

    @Test
    void announceFilesWithoutBooks_hasTheWatchedLibrariesAnnouncedOnAThreadOfItsOwn() throws Exception {
        Library watched = library(5L, true);
        Library unwatched = library(6L, false);
        service.registerLibraries(List.of(watched, unwatched));
        CompletableFuture<Thread> announcedOn = new CompletableFuture<>();
        doAnswer(inv -> announcedOn.complete(Thread.currentThread())).when(processor).announceFilesWithoutBooks(5L);

        service.announceFilesWithoutBooks(List.of(watched, unwatched));

        // The walk can take long, and new folders must go on being watched meanwhile
        Thread announcer = announcedOn.get(10, TimeUnit.SECONDS);
        assertThat(announcer).isNotSameAs(Thread.currentThread());
        assertThat(announcer.isDaemon()).isTrue();
        assertThat(announcer.isVirtual()).isFalse();
        Thread.sleep(500);
        verify(processor, never()).announceFilesWithoutBooks(6L);
    }

    private static Library library(long id, boolean watch) {
        Library library = mock(Library.class);
        when(library.getId()).thenReturn(id);
        when(library.isWatch()).thenReturn(watch);
        when(library.getPaths()).thenReturn(List.of());
        when(library.getName()).thenReturn("library " + id);
        return library;
    }

    @Test
    void registerLibrary_registersAllDirectoriesUnderLibraryPath() throws Exception {
        Path root = tmp.resolve("libroot");
        Path a = root.resolve("a");
        Path b = a.resolve("b");
        Files.createDirectories(b);

        Library lib = mock(Library.class);
        LibraryPath lp = mock(LibraryPath.class);
        when(lp.getPath()).thenReturn(root.toString());
        when(lib.getPaths()).thenReturn(List.of(lp));
        when(lib.getId()).thenReturn(7L);
        when(lib.getName()).thenReturn("my-lib");
        when(lib.isWatch()).thenReturn(true);

        service.registerLibrary(lib);

        assertThat(service.isPathMonitored(root)).isTrue();
        assertThat(service.isPathMonitored(a)).isTrue();
        assertThat(service.isPathMonitored(b)).isTrue();
    }

    @Test
    void unregisterLibrary_removesRegisteredPaths() throws Exception {
        Path root = tmp.resolve("libroot2");
        Files.createDirectories(root);

        service.registerPath(root, 99L);
        assertThat(service.isPathMonitored(root)).isTrue();

        service.unregisterLibrary(99L);
        assertThat(service.isPathMonitored(root)).isFalse();
    }

    @Test
    void registerPath_succeedsForDirectories() throws Exception {
        Path dir = tmp.resolve("regdir");
        Files.createDirectories(dir);

        boolean registered = service.registerPath(dir, 55L);
        assertThat(registered).isTrue();
        assertThat(service.isPathMonitored(dir)).isTrue();
    }

    @Test
    void registerPath_failsForFilesAndMissingPaths() throws Exception {
        Path file = tmp.resolve("file.txt");
        Files.writeString(file, "content");

        assertThat(service.registerPath(file, 1L)).isFalse();

        Path missing = tmp.resolve("missing");
        assertThat(service.registerPath(missing, 1L)).isFalse();
    }

    @Test
    void registerPath_duplicateRegistrationReturnsFalse() throws Exception {
        Path dir = tmp.resolve("dupdir");
        Files.createDirectories(dir);

        assertThat(service.registerPath(dir, 10L)).isTrue();
        assertThat(service.registerPath(dir, 10L)).isFalse();
    }

    @Test
    void isPathMonitored_handlesNonNormalizedPaths() throws Exception {
        Path root = tmp.resolve("libroot-norm");
        Path sub = root.resolve("subdir");
        Files.createDirectories(sub);

        service.registerPath(sub, 1L);

        Path nonNormalized = root.resolve("subdir/../subdir/.");
        assertThat(service.isPathMonitored(nonNormalized)).isTrue();
    }

    @Test
    void getPathsForLibraries_returnsCorrectPaths() throws Exception {
        Path dir1 = tmp.resolve("lib1");
        Path dir2 = tmp.resolve("lib2");
        Files.createDirectories(dir1);
        Files.createDirectories(dir2);

        service.registerPath(dir1, 1L);
        service.registerPath(dir2, 2L);

        Set<Path> paths = service.getPathsForLibraries(Set.of(1L));
        assertThat(paths).containsExactly(dir1);
    }

    @Test
    void isRelevantBookFile_detectsBookExtensions() {
        assertThat(service.isRelevantBookFile(Paths.get("book.pdf"))).isTrue();
        assertThat(service.isRelevantBookFile(Paths.get("book.epub"))).isTrue();
        assertThat(service.isRelevantBookFile(Paths.get("notes.txt"))).isFalse();
    }

    @Test
    void isLibraryMonitored_reflectsWatchStatus() {
        Library lib = mock(Library.class);
        when(lib.getId()).thenReturn(5L);
        when(lib.isWatch()).thenReturn(true);
        when(lib.getPaths()).thenReturn(List.of());
        when(lib.getName()).thenReturn("test");

        service.registerLibrary(lib);
        assertThat(service.isLibraryMonitored(5L)).isTrue();

        service.unregisterLibrary(5L);
        assertThat(service.isLibraryMonitored(5L)).isFalse();
    }

    @Test
    void resumeLibrary_countsTheLibraryAsWatchedAgainAndRegistersItsFolders() throws Exception {
        Path root = tmp.resolve("resumed");
        Path a = root.resolve("a");
        Files.createDirectories(a);
        Library lib = mock(Library.class);
        LibraryPath lp = mock(LibraryPath.class);
        when(lp.getPath()).thenReturn(root.toString());
        when(lib.getPaths()).thenReturn(List.of(lp));
        when(lib.getId()).thenReturn(8L);
        when(lib.getName()).thenReturn("resumed");
        when(lib.isWatch()).thenReturn(true);
        service.registerLibrary(lib);
        service.unregisterLibrary(8L);
        assertThat(service.isLibraryMonitored(8L)).isFalse();
        assertThat(service.isPathMonitored(a)).isFalse();

        service.resumeLibrary(8L, List.of(root));

        assertThat(service.isLibraryMonitored(8L)).isTrue();
        assertThat(service.isPathMonitored(root)).isTrue();
        assertThat(service.isPathMonitored(a)).isTrue();
    }

    @Test
    void aPauseAndResume_givesAWatchedLibraryBackInFullAndAnUnwatchedOneOnlyTheFolderItHad() throws Exception {
        LibraryRepository libraryRepository = mock(LibraryRepository.class);
        LibraryPathRepository libraryPathRepository = mock(LibraryPathRepository.class);
        MonitoringRegistrationService registration = new MonitoringRegistrationService(service, libraryRepository, libraryPathRepository);
        Path watchedRoot = tmp.resolve("watched");
        Path watchedSub = watchedRoot.resolve("sub");
        Path unwatchedRoot = tmp.resolve("unwatched");
        Path strayFolder = unwatchedRoot.resolve("B").resolve("Author");
        Path otherFolder = unwatchedRoot.resolve("C");
        Files.createDirectories(watchedSub);
        Files.createDirectories(strayFolder);
        Files.createDirectories(otherFolder);
        Library watched = mock(Library.class);
        LibraryPath wp = mock(LibraryPath.class);
        when(wp.getPath()).thenReturn(watchedRoot.toString());
        when(watched.getPaths()).thenReturn(List.of(wp));
        when(watched.getId()).thenReturn(1L);
        when(watched.getName()).thenReturn("watched");
        when(watched.isWatch()).thenReturn(true);
        Library unwatched = mock(Library.class);
        when(unwatched.getId()).thenReturn(2L);
        when(unwatched.isWatch()).thenReturn(false);
        service.registerLibraries(List.of(watched, unwatched));
        // What a book deletion used to leave: one folder of the unwatched library registered
        service.registerPath(strayFolder, 2L);

        MonitoringPause pause = registration.pauseLibraries(List.of(1L, 2L));

        assertThat(pause.watchedLibraries()).containsExactly(1L);
        assertThat(pause.folders()).containsOnlyKeys(strayFolder);
        assertThat(service.isPathMonitored(watchedSub)).isFalse();
        assertThat(service.isPathMonitored(strayFolder)).isFalse();
        // Folders the writer makes while the libraries are paused
        Path newInWatched = watchedRoot.resolve("New Author").resolve("New Title");
        Path newInUnwatched = unwatchedRoot.resolve("New Author");
        Files.createDirectories(newInWatched);
        Files.createDirectories(newInUnwatched);
        when(libraryRepository.findWatchById(1L)).thenReturn(Optional.of(true));
        when(libraryPathRepository.findPathsByLibraryId(1L)).thenReturn(List.of(watchedRoot.toString()));
        when(libraryRepository.existsById(2L)).thenReturn(true);

        registration.resume(pause);

        assertThat(service.isLibraryMonitored(1L)).isTrue();
        assertThat(service.isPathMonitored(watchedRoot)).isTrue();
        assertThat(service.isPathMonitored(watchedSub)).isTrue();
        assertThat(service.isPathMonitored(newInWatched)).isTrue();
        assertThat(service.isPathMonitored(newInUnwatched)).isFalse();
        // The unwatched library gets back the one folder it had: it is not walked and does not become watched
        assertThat(service.isPathMonitored(strayFolder)).isTrue();
        assertThat(service.isPathMonitored(unwatchedRoot)).isFalse();
        assertThat(service.isPathMonitored(otherFolder)).isFalse();
        assertThat(service.isLibraryMonitored(2L)).isFalse();
        assertThat(service.getPathsForLibraries(Set.of(2L))).containsExactly(strayFolder);
    }

    @Test
    void aWatchedLibraryCreatedWhileRunning_isPausedAndResumedAsAWatchedOne() throws Exception {
        LibraryRepository libraryRepository = mock(LibraryRepository.class);
        LibraryPathRepository libraryPathRepository = mock(LibraryPathRepository.class);
        MonitoringRegistrationService registration = new MonitoringRegistrationService(service, libraryRepository, libraryPathRepository);
        Path root = tmp.resolve("created");
        Path existing = root.resolve("Existing Author");
        Files.createDirectories(existing);

        // What LibraryService.createLibrary does for a library with watching on
        service.watchNewLibrary(9L, List.of(root));

        assertThat(service.isLibraryMonitored(9L)).isTrue();
        assertThat(service.isPathMonitored(root)).isTrue();

        MonitoringPause pause = registration.pauseLibraries(List.of(9L));
        assertThat(pause.watchedLibraries()).containsExactly(9L);
        assertThat(pause.folders()).isEmpty();
        Path imported = root.resolve("Imported Author").resolve("Imported Title");
        Files.createDirectories(imported);
        when(libraryRepository.findWatchById(9L)).thenReturn(Optional.of(true));
        when(libraryPathRepository.findPathsByLibraryId(9L)).thenReturn(List.of(root.toString()));

        registration.resume(pause);

        assertThat(service.isLibraryMonitored(9L)).isTrue();
        assertThat(service.isPathMonitored(imported)).isTrue();
        assertThat(service.isPathMonitored(existing)).isTrue();
    }
}
