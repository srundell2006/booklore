package org.booklore.service.library;

import org.booklore.config.AppProperties;
import org.booklore.model.dto.settings.LibraryFile;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.entity.LibraryPathEntity;
import org.booklore.service.library.LibraryDirectoryWalker.Directory;
import org.booklore.service.library.LibraryDirectoryWalker.WalkedPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class LibraryDirectoryWalkerTest {

    @TempDir
    Path tempDir;

    private final ScriptedFileAccess fileAccess = new ScriptedFileAccess();

    @AfterEach
    void releaseStuckListings() {
        fileAccess.release();
    }

    private LibraryDirectoryWalker walker(int parallelism, Duration timeout) {
        return new LibraryDirectoryWalker(parallelism, timeout, fileAccess);
    }

    private Path book(String relativePath) throws IOException {
        Path file = tempDir.resolve(relativePath);
        Files.createDirectories(file.getParent());
        return Files.write(file, new byte[]{1});
    }

    private static LibraryEntity library(Path... paths) {
        List<LibraryPathEntity> pathEntities = new ArrayList<>();
        for (int i = 0; i < paths.length; i++) {
            LibraryPathEntity pathEntity = new LibraryPathEntity();
            pathEntity.setId((long) i + 1);
            pathEntity.setPath(paths[i].toString());
            pathEntities.add(pathEntity);
        }
        return LibraryEntity.builder().name("Test Library").libraryPaths(pathEntities).build();
    }

    private static List<String> fileNames(List<LibraryFile> files) {
        return files.stream().map(LibraryFile::getFileName).sorted().toList();
    }

    private static Directory child(Directory directory, String name) {
        return directory.getSubdirectories().stream()
                .filter(sub -> sub.getPath().getFileName().toString().equals(name))
                .findFirst().orElseThrow();
    }

    @Test
    void reportsFailedListingsAndBookFilesItCouldNotRead() throws IOException {
        book("ok/a.epub");
        book("denied/b.epub");
        book("Author/c.epub");
        Path locked = book("Author/locked.epub");
        Path statFails = book("Author/stat-fails.epub");
        Path statFailsDir = Files.createDirectories(tempDir.resolve("Author/maybe-a-dir"));
        book("Author/cover.jpg");
        fileAccess.listingFailures.put(tempDir.resolve("denied"), new AccessDeniedException(tempDir.resolve("denied").toString()));
        fileAccess.unreadable.add(locked);
        fileAccess.unreadable.add(tempDir.resolve("Author/cover.jpg"));
        fileAccess.attributeFailures.put(statFails, new IOException("Input/output error"));
        fileAccess.attributeFailures.put(statFailsDir, new IOException("Stale file handle"));

        LibraryScanResult result = new LibraryFileHelper(walker(4, Duration.ofSeconds(30))).scanAllLibraryFiles(library(tempDir));

        assertThat(fileNames(result.files())).containsExactly("a.epub", "c.epub");
        assertThat(result.incompletePaths()).containsExactlyInAnyOrder(
                tempDir.resolve("denied"), locked, statFails, statFailsDir);
        assertThat(result.isComplete()).isFalse();
        assertThat(result.isUnderIncompletePath(tempDir.resolve("denied/b.epub"))).isTrue();
    }

    @Test
    void listingThatFailsPartWayIsIncomplete() throws IOException {
        book("flaky/a.epub");
        book("flaky/b.epub");
        book("fine/c.epub");
        fileAccess.listingsFailingAfterFirstEntry.add(tempDir.resolve("flaky"));

        LibraryScanResult result = new LibraryFileHelper(walker(4, Duration.ofSeconds(30))).scanAllLibraryFiles(library(tempDir));

        // Nothing of a half-read directory is reported, so none of it can pass for a complete listing.
        assertThat(fileNames(result.files())).containsExactly("c.epub");
        assertThat(result.incompletePaths()).containsExactly(tempDir.resolve("flaky"));
    }

    @Test
    void entriesSkippedOnPurposeAreNotIncomplete() throws IOException {
        book("real.epub");
        book("Ignored/.ignore");
        book("Ignored/hidden.epub");
        book(".hidden/x.epub");
        book("#recycle/x.epub");
        book("@eaDir/x.epub");
        book(".caltrash/x.epub");
        book("download.epub.part");
        Files.createFile(tempDir.resolve("empty.epub"));
        Files.createSymbolicLink(tempDir.resolve("dangling.epub"), tempDir.resolve("nowhere.epub"));
        Files.createDirectories(tempDir.resolve("Loop"));
        Files.createSymbolicLink(tempDir.resolve("Loop/back"), tempDir);
        Path vanished = book("vanished.epub");
        fileAccess.attributeFailures.put(vanished, new NoSuchFileException(vanished.toString()));

        LibraryScanResult result = new LibraryFileHelper(walker(4, Duration.ofSeconds(30))).scanAllLibraryFiles(library(tempDir));

        assertThat(fileNames(result.files())).containsExactly("real.epub");
        assertThat(result.isComplete()).isTrue();
    }

    @Test
    void missingLibraryPathIsIncomplete() throws IOException {
        Path missing = tempDir.resolve("unmounted");

        LibraryScanResult result = new LibraryFileHelper(walker(4, Duration.ofSeconds(30))).scanAllLibraryFiles(library(missing));

        assertThat(result.files()).isEmpty();
        assertThat(result.incompletePaths()).containsExactly(missing);
    }

    @Test
    void libraryPathThatIsNotADirectoryIsIncomplete() throws IOException {
        Path file = book("not-a-dir.epub");

        List<WalkedPath> walked = walker(4, Duration.ofSeconds(30)).walk(List.of(file));

        assertThat(walked.getFirst().root()).isNull();
        assertThat(walked.getFirst().incompletePaths()).containsExactly(file);
    }

    @Test
    void incompletePathsOfAllLibraryPathsAreReported() throws IOException {
        book("one/Shelf/a.epub");
        book("two/b.epub");
        Path missing = tempDir.resolve("three");
        fileAccess.listingFailures.put(tempDir.resolve("one/Shelf"), new IOException("Host is down"));

        LibraryFileHelper helper = new LibraryFileHelper(walker(4, Duration.ofSeconds(30)));
        LibraryScanResult result = helper.scanAllLibraryFiles(library(tempDir.resolve("one"), tempDir.resolve("two"), missing));

        assertThat(fileNames(result.files())).containsExactly("b.epub");
        assertThat(result.incompletePaths()).containsExactlyInAnyOrder(tempDir.resolve("one/Shelf"), missing);
        // The other entry points keep returning just the files.
        assertThat(fileNames(helper.getAllLibraryFiles(library(tempDir.resolve("one"), tempDir.resolve("two"), missing))))
                .containsExactly("b.epub");
    }

    @Test
    void walkFinishesPromptlyWhenAListingNeverReturns() throws Exception {
        book("stuck/x.epub");
        book("a/a.epub");
        book("b/b.epub");
        Path stuck = tempDir.resolve("stuck");
        fileAccess.stuckListings.add(stuck);

        List<WalkedPath> walked = assertTimeoutPreemptively(Duration.ofSeconds(10),
                () -> walker(4, Duration.ofMillis(500)).walk(List.of(tempDir)));

        WalkedPath result = walked.getFirst();
        assertThat(result.incompletePaths()).containsExactly(stuck);
        assertThat(child(result.root(), "a").getBookFiles()).containsExactly("a.epub");
        assertThat(child(result.root(), "b").getBookFiles()).containsExactly("b.epub");

        // The abandoned listing finally returns; what the walk already handed back must not change.
        fileAccess.release();
        Path lateEntry = stuck.resolve("x.epub");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!fileAccess.readabilityChecked.contains(lateEntry) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(fileAccess.readabilityChecked).contains(lateEntry);
        Thread.sleep(100);
        assertThat(child(result.root(), "stuck").getBookFiles()).isEmpty();
        assertThat(child(result.root(), "stuck").getSubdirectories()).isEmpty();
    }

    @Test
    void abandonedListingFreesItsSlotExactlyOnce() throws Exception {
        // Sorts first, so it holds one of the two slots while the other directories queue behind it.
        Path stuck = tempDir.resolve("a-stuck");
        Files.createDirectories(stuck);
        fileAccess.stuckListings.add(stuck);
        for (int i = 0; i < 24; i++) {
            book("dir" + i + "/book" + i + ".epub");
            fileAccess.listingDelays.put(tempDir.resolve("dir" + i), Duration.ofMillis(50));
        }

        List<WalkedPath> walked = assertTimeoutPreemptively(Duration.ofSeconds(10),
                () -> walker(2, Duration.ofMillis(300)).walk(List.of(tempDir)));

        assertThat(walked.getFirst().incompletePaths()).containsExactly(stuck);
        assertThat(walked.getFirst().root().getSubdirectories()).hasSize(25)
                .filteredOn(dir -> !dir.getPath().equals(stuck))
                .allSatisfy(dir -> assertThat(dir.getBookFiles()).hasSize(1));
        // Two slots: the stuck listing's slot is reused once it is abandoned, never twice over.
        assertThat(fileAccess.maxActiveListings.get()).isEqualTo(2);
    }

    @Test
    void slowListingThatKeepsMakingProgressIsNotAbandoned() throws IOException {
        for (int i = 0; i < 10; i++) {
            book("slow/book" + i + ".epub");
        }
        fileAccess.entryAttributeDelays.put(tempDir.resolve("slow"), Duration.ofMillis(50));

        // Ten entries at 50 ms each take well over the 300 ms limit in total, but no single call stalls.
        List<WalkedPath> walked = walker(4, Duration.ofMillis(300)).walk(List.of(tempDir));

        assertThat(walked.getFirst().incompletePaths()).isEmpty();
        assertThat(child(walked.getFirst().root(), "slow").getBookFiles()).hasSize(10);
    }

    @Test
    void stopsWalkingALibraryPathAfterTooManyTimeouts() throws Exception {
        Path dead = tempDir.resolve("dead");
        for (int i = 0; i < 40; i++) {
            Path dir = Files.createDirectories(dead.resolve("dir" + i));
            fileAccess.stuckListings.add(dir);
        }
        Path healthy = tempDir.resolve("healthy");
        for (int i = 0; i < 5; i++) {
            book("healthy/dir" + i + "/book" + i + ".epub");
        }
        int parallelism = 3;

        List<WalkedPath> walked = assertTimeoutPreemptively(Duration.ofSeconds(10),
                () -> walker(parallelism, Duration.ofMillis(250)).walk(List.of(dead, healthy)));

        assertThat(walked.get(0).incompletePaths()).contains(dead);
        assertThat(fileAccess.stuckCount.get())
                .isBetween(LibraryDirectoryWalker.MAX_TIMEOUTS_PER_PATH, LibraryDirectoryWalker.MAX_TIMEOUTS_PER_PATH + parallelism - 1);
        assertThat(walked.get(1).incompletePaths()).isEmpty();
        assertThat(walked.get(1).root().getSubdirectories())
                .allSatisfy(dir -> assertThat(dir.getBookFiles()).hasSize(1))
                .hasSize(5);
    }

    @Test
    void listsDirectoriesInParallelWithinTheBound() throws IOException {
        for (int i = 0; i < 12; i++) {
            book("dir" + i + "/book.epub");
            fileAccess.listingDelays.put(tempDir.resolve("dir" + i), Duration.ofMillis(50));
        }

        walker(3, Duration.ofSeconds(30)).walk(List.of(tempDir));

        assertThat(fileAccess.maxActiveListings.get()).isEqualTo(3);
    }

    @Test
    void parallelismIsClampedToASaneRange() throws IOException {
        for (int i = 0; i < 4; i++) {
            book("dir" + i + "/book.epub");
            fileAccess.listingDelays.put(tempDir.resolve("dir" + i), Duration.ofMillis(30));
        }

        List<WalkedPath> walked = walker(0, Duration.ofSeconds(30)).walk(List.of(tempDir));

        assertThat(walked.getFirst().root().getSubdirectories()).hasSize(4);
        assertThat(fileAccess.maxActiveListings.get()).isEqualTo(1);
    }

    @Test
    void walksWithConfiguredSettingsEvenWhenOutOfRange() throws IOException {
        book("Author/a.epub");
        AppProperties appProperties = new AppProperties();
        appProperties.getScan().setWalkParallelism(10_000);
        appProperties.getScan().setDirectoryTimeoutSeconds(0);

        List<WalkedPath> walked = new LibraryDirectoryWalker(appProperties).walk(List.of(tempDir));

        assertThat(walked.getFirst().incompletePaths()).isEmpty();
        assertThat(child(walked.getFirst().root(), "Author").getBookFiles()).containsExactly("a.epub");
    }

    @Test
    void walksAllLibraryPathsAtOnce() throws IOException {
        Path first = book("first/a.epub").getParent();
        Path second = book("second/b.epub").getParent();
        // Each library path's listing waits for the other's to start: a one-path-at-a-time walk never gets past it.
        CyclicBarrier bothStarted = new CyclicBarrier(2);
        fileAccess.listingBarriers.put(first, bothStarted);
        fileAccess.listingBarriers.put(second, bothStarted);

        List<WalkedPath> walked = walker(4, Duration.ofSeconds(30)).walk(List.of(first, second));

        assertThat(walked).extracting(WalkedPath::path).containsExactly(first, second);
        assertThat(walked.get(0).root().getBookFiles()).containsExactly("a.epub");
        assertThat(walked.get(1).root().getBookFiles()).containsExactly("b.epub");
    }

    @Test
    void touchesOnlyWhatTheWalkNeeds() throws IOException {
        Path epub = book("book.epub");
        Path cover = book("cover.jpg");
        Path opf = book("metadata.opf");
        book(".DS_Store");
        book("._book.epub");
        book("book.epub.part");
        Path empty = Files.createFile(tempDir.resolve("empty.epub"));
        book("#recycle/x.epub");
        book(".git/x.epub");
        book("Ignored/.ignore");
        book("Ignored/a.epub");
        book("Ignored/Sub/b.epub");
        Path nested = book("Author/c.epub");

        walker(4, Duration.ofSeconds(30)).walk(List.of(tempDir));

        assertThat(fileAccess.listed).containsExactlyInAnyOrder(tempDir, tempDir.resolve("Ignored"), tempDir.resolve("Author"));
        assertThat(fileAccess.attributesRead).containsExactlyInAnyOrder(
                tempDir, epub, cover, opf, empty, tempDir.resolve("Ignored"), tempDir.resolve("Author"), nested);
        assertThat(fileAccess.readabilityChecked).containsExactlyInAnyOrder(epub, nested);
    }

    @Test
    void outputIsInNameOrder() throws IOException {
        book("c.epub");
        book("a.epub");
        book("b.epub");
        book("Zeta/z.epub");
        book("Mu/m.epub");
        book("Mu/Inner/i.epub");

        List<LibraryFile> files = new LibraryFileHelper(walker(4, Duration.ofSeconds(30))).getAllLibraryFiles(library(tempDir));

        assertThat(files).extracting(LibraryFile::getFileName)
                .containsExactly("a.epub", "b.epub", "c.epub", "m.epub", "i.epub", "z.epub");
    }

    @Test
    void interruptingTheCallerEndsTheWalk() throws Exception {
        Path stuck = Files.createDirectories(tempDir.resolve("stuck"));
        fileAccess.stuckListings.add(stuck);
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread caller = Thread.ofPlatform().start(() -> {
            try {
                walker(4, Duration.ofSeconds(60)).walk(List.of(tempDir));
            } catch (Throwable t) {
                thrown.set(t);
            }
        });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (fileAccess.stuckCount.get() == 0 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }

        caller.interrupt();
        caller.join(5000);

        assertThat(caller.isAlive()).isFalse();
        assertThat(thrown.get()).isInstanceOf(InterruptedIOException.class);
    }
}
