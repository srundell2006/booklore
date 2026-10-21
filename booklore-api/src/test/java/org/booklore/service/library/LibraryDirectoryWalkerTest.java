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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.lang.ref.WeakReference;
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
import java.util.stream.Stream;

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

    private static List<String> subdirectoryNames(Directory directory) {
        return directory.getSubdirectories().stream().map(sub -> sub.getPath().getFileName().toString()).toList();
    }

    /** Where a share that stops answering makes a walk stall. */
    enum Stall {
        /** Listing the directory. */
        LISTING,
        /** Reading the attributes of a book file in it. */
        ENTRY;

        /** Makes the walk stall in {@code dir}, and returns the path it then reports incomplete. */
        Path stallIn(ScriptedFileAccess fileAccess, Path dir) throws IOException {
            if (this == LISTING) {
                fileAccess.stuckListings.add(dir);
                return dir;
            }
            Path file = Files.write(dir.resolve("stuck.epub"), new byte[]{1});
            fileAccess.stuckAttributeReads.add(file);
            return file;
        }
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
    void bookFileItCouldNotSeePutsItsDirectoryInDoubtWhereItDecidesHowAudioGroups() throws IOException {
        // Two tracks of a folder audiobook, one unreadable: the other alone would come out as a single-file book.
        Path folderBook = tempDir.resolve("Author/Book");
        book("Author/Book/ch01.mp3");
        book("Author/Book/ch02.mp3");
        fileAccess.unreadable.add(folderBook.resolve("ch01.mp3"));
        // An unreadable ebook that keeps two tracks from collapsing into one folder audiobook.
        Path mixed = tempDir.resolve("Mixed");
        book("Mixed/book.epub");
        book("Mixed/a.mp3");
        book("Mixed/b.mp3");
        fileAccess.unreadable.add(mixed.resolve("book.epub"));
        // A track whose attributes cannot be read.
        Path stale = tempDir.resolve("Stale");
        book("Stale/t1.mp3");
        book("Stale/t2.mp3");
        fileAccess.attributeFailures.put(stale.resolve("t1.mp3"), new IOException("Stale file handle"));
        // Where every book file is a book of its own whatever else is there, only the unseen file is in doubt:
        // at the library path, beside a single track, and among ebooks.
        book("r1.mp3");
        Path rootTrack = book("r2.mp3");
        fileAccess.unreadable.add(rootTrack);
        book("Lone/talk.mp3");
        Path loneEpub = book("Lone/book.epub");
        fileAccess.unreadable.add(loneEpub);
        book("Ebooks/a.epub");
        Path lockedEpub = book("Ebooks/b.epub");
        fileAccess.unreadable.add(lockedEpub);

        LibraryScanResult result = new LibraryFileHelper(walker(4, Duration.ofSeconds(30))).scanAllLibraryFiles(library(tempDir));

        assertThat(result.incompletePaths()).containsExactlyInAnyOrder(folderBook, mixed, stale, rootTrack, loneEpub, lockedEpub);
        // The folder audiobook a rescan would otherwise drop is covered, as are the books of the mixed directory.
        assertThat(result.isUnderIncompletePath(folderBook)).isTrue();
        assertThat(result.isUnderIncompletePath(mixed.resolve("a.mp3"))).isTrue();
        assertThat(result.isUnderIncompletePath(stale)).isTrue();
        assertThat(result.isUnderIncompletePath(tempDir.resolve("r1.mp3"))).isFalse();
        assertThat(result.isUnderIncompletePath(tempDir.resolve("Ebooks/a.epub"))).isFalse();
    }

    @Test
    void linkOntoAShareThatIsDownIsIncomplete() throws IOException {
        Path share = Files.createDirectories(tempDir.resolve("nas2"));
        Files.write(share.resolve("comic.cbz"), new byte[]{1});
        Files.write(share.resolve("novel.epub"), new byte[]{1});
        Path root = book("books/a.epub").getParent();
        Path comics = Files.createSymbolicLink(root.resolve("Comics"), share);
        Path novel = Files.createSymbolicLink(root.resolve("novel.epub"), share.resolve("novel.epub"));
        Files.createSymbolicLink(root.resolve("dangling.epub"), share.resolve("gone.epub"));
        fileAccess.targetFailures.put(comics, new IOException("Host is down"));
        fileAccess.targetFailures.put(novel, new IOException("Host is down"));

        LibraryScanResult result = new LibraryFileHelper(walker(4, Duration.ofSeconds(30))).scanAllLibraryFiles(library(root));

        // Unlike a link whose target is gone, these may lead to books that still exist.
        assertThat(fileNames(result.files())).containsExactly("a.epub");
        assertThat(result.incompletePaths()).containsExactlyInAnyOrder(comics, novel);
        assertThat(result.isUnderIncompletePath(comics.resolve("comic.cbz"))).isTrue();
    }

    @Test
    void emptyLibraryPathIsIncomplete() throws IOException {
        // What a share that failed to mount looks like: its mount point, empty.
        Path unmounted = Files.createDirectories(tempDir.resolve("unmounted"));
        Path quiet = Files.createDirectories(tempDir.resolve("quiet"));
        book("quiet/.DS_Store");
        Path present = book("present/a.epub").getParent();

        List<WalkedPath> walked = walker(4, Duration.ofSeconds(30)).walk(List.of(unmounted, quiet, present));

        assertThat(walked.get(0).incompletePaths()).containsExactly(unmounted);
        assertThat(walked.get(1).incompletePaths()).isEmpty();
        assertThat(walked.get(2).incompletePaths()).isEmpty();
        // Said apart from a path that could not be read, so the user can be told which it was.
        assertThat(walked).extracting(WalkedPath::empty).containsExactly(true, false, false);
        LibraryScanResult result = new LibraryFileHelper(walker(4, Duration.ofSeconds(30))).scanAllLibraryFiles(library(unmounted, present));
        assertThat(result.emptyLibraryPaths()).containsExactly(unmounted);
        assertThat(result.unreadPaths()).isEmpty();
    }

    @Test
    void missingLibraryPathIsIncomplete() throws IOException {
        Path missing = tempDir.resolve("unmounted");

        LibraryScanResult result = new LibraryFileHelper(walker(4, Duration.ofSeconds(30))).scanAllLibraryFiles(library(missing));

        assertThat(result.files()).isEmpty();
        assertThat(result.incompletePaths()).containsExactly(missing);
        assertThat(result.unreadPaths()).containsExactly(missing);
        assertThat(result.emptyLibraryPaths()).isEmpty();
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

        LibraryDirectoryWalker walker = walker(4, Duration.ofMillis(500));

        List<WalkedPath> walked = assertTimeoutPreemptively(Duration.ofSeconds(10), () -> walker.walk(List.of(tempDir)));

        WalkedPath result = walked.getFirst();
        assertThat(result.incompletePaths()).containsExactly(stuck);
        assertThat(child(result.root(), "a").getBookFiles()).containsExactly("a.epub");
        assertThat(child(result.root(), "b").getBookFiles()).containsExactly("b.epub");
        assertThat(walker.strandedListings()).isEqualTo(1);

        // The stuck call finally returns: the abandoned listing stops there instead of reading the rest of the
        // directory, and what the walk already handed back does not change.
        fileAccess.release();
        awaitNoStrandedListings(walker);
        assertThat(fileAccess.attributesRead).doesNotContain(stuck.resolve("x.epub"));
        assertThat(fileAccess.readabilityChecked).doesNotContain(stuck.resolve("x.epub"));
        assertThat(child(result.root(), "stuck").getBookFiles()).isEmpty();
        assertThat(child(result.root(), "stuck").getSubdirectories()).isEmpty();
    }

    @Test
    void entryStuckUnderTheLibraryPathIsLeftOutAloneAndTheRestOfThePathIsScanned() throws Exception {
        // What a wedged author folder directly under the library path looks like.
        book("Author 1/a.epub");
        Path stuck = book("Author 2/b.epub").getParent();
        book("Author 3/c.epub");
        book("loose.epub");
        fileAccess.listInNameOrder = true;
        fileAccess.stuckAttributeReads.add(stuck);
        LibraryDirectoryWalker walker = walker(4, Duration.ofMillis(300));

        WalkedPath walked = assertTimeoutPreemptively(Duration.ofSeconds(10), () -> walker.walk(List.of(tempDir))).getFirst();

        // What was inspected before the stall is kept, and the entries after it are still inspected.
        assertThat(walked.incompletePaths()).containsExactly(stuck);
        assertThat(walked.timedOut()).isTrue();
        assertThat(subdirectoryNames(walked.root())).containsExactly("Author 1", "Author 3");
        assertThat(child(walked.root(), "Author 1").getBookFiles()).containsExactly("a.epub");
        assertThat(child(walked.root(), "Author 3").getBookFiles()).containsExactly("c.epub");
        assertThat(walked.root().getBookFiles()).containsExactly("loose.epub");
        // Carrying on lists the library path again, but looks at nothing a second time.
        assertThat(fileAccess.listed).filteredOn(tempDir::equals).hasSize(2);
        assertThat(fileAccess.attributesRead).doesNotHaveDuplicates();
        assertThat(walker.strandedListings()).isEqualTo(1);

        // The stuck call finally returns: the listing given up on stops there, and nothing it does is taken.
        List<Path> attributesRead = List.copyOf(fileAccess.attributesRead);
        fileAccess.release();
        awaitNoStrandedListings(walker);
        assertThat(fileAccess.attributesRead).containsExactlyElementsOf(attributesRead);
        assertThat(subdirectoryNames(walked.root())).containsExactly("Author 1", "Author 3");
        assertThat(walked.incompletePaths()).containsExactly(stuck);
    }

    @Test
    void bookFilesStuckInAnAuthorFolderAreLeftOutAloneAndTheRestOfItIsScanned() throws Exception {
        book("Author/a.epub");
        Path stuckStat = book("Author/b.epub");
        book("Author/c.epub");
        Path stuckCheck = book("Author/d.epub");
        book("Author/e.epub");
        book("Other/f.epub");
        fileAccess.listInNameOrder = true;
        fileAccess.stuckAttributeReads.add(stuckStat);
        fileAccess.stuckReadabilityChecks.add(stuckCheck);
        LibraryDirectoryWalker walker = walker(4, Duration.ofMillis(300));

        LibraryScanResult result = assertTimeoutPreemptively(Duration.ofSeconds(10),
                () -> new LibraryFileHelper(walker).scanAllLibraryFiles(library(tempDir)));

        assertThat(fileNames(result.files())).containsExactly("a.epub", "c.epub", "e.epub", "f.epub");
        assertThat(result.incompletePaths()).containsExactlyInAnyOrder(stuckStat, stuckCheck);
        assertThat(result.timedOut()).isTrue();
        assertThat(result.isUnderIncompletePath(tempDir.resolve("Author/a.epub"))).isFalse();
        // One listing given up on per stall, each carried on by a fresh one.
        assertThat(fileAccess.listed).filteredOn(tempDir.resolve("Author")::equals).hasSize(3);
        assertThat(fileAccess.attributesRead).doesNotHaveDuplicates();
        assertThat(walker.strandedListings()).isEqualTo(2);

        fileAccess.release();
        awaitNoStrandedListings(walker);
    }

    @Test
    void trackStuckInAnAudiobookFolderPutsTheFolderInDoubt() throws Exception {
        Path folderBook = tempDir.resolve("Author/Book");
        book("Author/Book/ch01.mp3");
        fileAccess.stuckAttributeReads.add(book("Author/Book/ch02.mp3"));
        book("Author/Book/ch03.mp3");
        book("Author/novel.epub");
        // At the library path every audio file is a book of its own, so only the stuck one is in doubt.
        book("r1.mp3");
        Path rootTrack = book("r2.mp3");
        fileAccess.stuckAttributeReads.add(rootTrack);
        LibraryDirectoryWalker walker = walker(4, Duration.ofMillis(300));

        LibraryScanResult result = assertTimeoutPreemptively(Duration.ofSeconds(10),
                () -> new LibraryFileHelper(walker).scanAllLibraryFiles(library(tempDir)));

        // How the other tracks group depends on the one given up on, as for a track that could not be read, so
        // none of them is taken until a scan sees them all.
        assertThat(result.incompletePaths()).containsExactlyInAnyOrder(folderBook, rootTrack);
        assertThat(fileNames(result.files())).containsExactly("novel.epub", "r1.mp3");

        fileAccess.release();
        awaitNoStrandedListings(walker);
    }

    @Test
    void listingStuckReadingTheDirectoryAfterInspectingSomeOfItGivesUpTheWholeDirectory() throws Exception {
        Path shelf = book("Shelf/a.epub").getParent();
        book("Shelf/b.epub");
        book("Other/c.epub");
        fileAccess.listInNameOrder = true;
        fileAccess.listingsStuckAfterFirstEntry.add(shelf);
        LibraryDirectoryWalker walker = walker(4, Duration.ofMillis(300));

        LibraryScanResult result = assertTimeoutPreemptively(Duration.ofSeconds(10),
                () -> new LibraryFileHelper(walker).scanAllLibraryFiles(library(tempDir)));

        // a.epub was inspected, but nothing of a half-read directory is reported.
        assertThat(fileAccess.attributesRead).contains(shelf.resolve("a.epub"));
        assertThat(fileNames(result.files())).containsExactly("c.epub");
        assertThat(result.incompletePaths()).containsExactly(shelf);
        assertThat(fileAccess.listed).filteredOn(shelf::equals).hasSize(1);

        fileAccess.release();
        awaitNoStrandedListings(walker);
    }

    @Test
    void directoryWhoseEntriesAreAllStuckCostsTwoTimeoutsAndTheRestOfTheLibraryPathIsScanned() throws Exception {
        // A client gone to sleep holds every track of an audiobook folder.
        Path folderBook = Files.createDirectories(tempDir.resolve("Author/Book"));
        for (int i = 0; i < 2 * LibraryDirectoryWalker.MAX_TIMEOUTS_PER_PATH; i++) {
            fileAccess.stuckAttributeReads.add(Files.write(folderBook.resolve(String.format("ch%02d.mp3", i)), new byte[]{1}));
        }
        book("Author/novel.epub");
        for (int i = 0; i < 5; i++) {
            book("Other" + i + "/book" + i + ".epub");
        }
        LibraryDirectoryWalker walker = walker(4, Duration.ofMillis(200));

        LibraryScanResult result = assertTimeoutPreemptively(Duration.ofSeconds(10),
                () -> new LibraryFileHelper(walker).scanAllLibraryFiles(library(tempDir)));

        // The listing carrying on after the first track given up on is stuck again at once, so the folder is given
        // up: it costs two timeouts and two stranded threads, not one per track, and the library path goes on.
        assertThat(result.incompletePaths()).containsExactly(folderBook);
        assertThat(fileNames(result.files()))
                .containsExactly("book0.epub", "book1.epub", "book2.epub", "book3.epub", "book4.epub", "novel.epub");
        assertThat(fileAccess.stuckCount.get()).isEqualTo(2);
        assertThat(walker.strandedListings()).isEqualTo(2);
        assertThat(fileAccess.listed).filteredOn(folderBook::equals).hasSize(2);

        // A rescan while the folder stays wedged costs the same again, not more.
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> walker.walk(List.of(tempDir)));
        assertThat(walker.strandedListings()).isEqualTo(4);

        fileAccess.release();
        awaitNoStrandedListings(walker);
    }

    @Test
    void entryStuckRightAfterOneGivenUpGivesUpTheRestOfTheDirectoryButKeepsWhatWasFound() throws Exception {
        book("Author 1/a.epub");
        Path stuck2 = book("Author 2/b.epub").getParent();
        Path stuck3 = book("Author 3/c.epub").getParent();
        book("Author 4/d.epub");
        fileAccess.listInNameOrder = true;
        fileAccess.stuckAttributeReads.add(stuck2);
        fileAccess.stuckAttributeReads.add(stuck3);
        LibraryDirectoryWalker walker = walker(4, Duration.ofMillis(200));

        WalkedPath walked = assertTimeoutPreemptively(Duration.ofSeconds(10), () -> walker.walk(List.of(tempDir))).getFirst();

        // The library path is in doubt as a whole, but what was found in it is still scanned, so a rescan has books
        // to go on instead of stopping as if the share were down.
        assertThat(walked.incompletePaths()).containsExactly(tempDir);
        assertThat(walked.timedOut()).isTrue();
        assertThat(subdirectoryNames(walked.root())).containsExactly("Author 1");
        assertThat(child(walked.root(), "Author 1").getBookFiles()).containsExactly("a.epub");
        assertThat(fileAccess.attributesRead).doesNotContain(tempDir.resolve("Author 4"));
        assertThat(walker.strandedListings()).isEqualTo(2);

        fileAccess.release();
        awaitNoStrandedListings(walker);
    }

    @Test
    void directoryGivesUpOnlyAFewOfItsEntriesOneByOne() throws Exception {
        // Stuck entries with others between them, more than the library path has timeouts for.
        Path shelf = Files.createDirectories(tempDir.resolve("Shelf"));
        for (int i = 0; i < 4 * LibraryDirectoryWalker.MAX_TIMEOUTS_PER_PATH; i++) {
            Path file = Files.write(shelf.resolve(String.format("b%02d.epub", i)), new byte[]{1});
            if (i % 2 == 1) {
                fileAccess.stuckAttributeReads.add(file);
            }
        }
        book("Other/a.epub");
        fileAccess.listInNameOrder = true;
        LibraryDirectoryWalker walker = walker(4, Duration.ofMillis(200));

        WalkedPath walked = assertTimeoutPreemptively(Duration.ofSeconds(10), () -> walker.walk(List.of(tempDir))).getFirst();

        // One directory never takes enough of its library path's timeouts to stop it.
        int stuck = LibraryDirectoryWalker.MAX_STUCK_ENTRIES_PER_DIRECTORY;
        assertThat(stuck).isLessThan(LibraryDirectoryWalker.MAX_TIMEOUTS_PER_PATH);
        assertThat(fileAccess.stuckCount.get()).isEqualTo(stuck);
        assertThat(walker.strandedListings()).isEqualTo(stuck);
        assertThat(walked.incompletePaths()).containsExactly(shelf);
        // Given up as a whole, so nothing found in it is kept.
        assertThat(fileAccess.attributesRead).contains(shelf.resolve("b00.epub"));
        assertThat(child(walked.root(), "Shelf").getBookFiles()).isEmpty();
        assertThat(child(walked.root(), "Other").getBookFiles()).containsExactly("a.epub");

        fileAccess.release();
        awaitNoStrandedListings(walker);
    }

    @Test
    void libraryPathFoundEmptyWhenReadAgainAfterAStuckEntryIsReportedEmpty() throws Exception {
        // The share is unmounted while an entry of it is stuck, so the listing carrying on reads the bare mount point.
        book("A loose.epub");
        book("Author A/a.epub");
        Path stuck = book("Author B/b.epub").getParent();
        Path notReached = book("Author C/c.epub");
        fileAccess.listInNameOrder = true;
        fileAccess.stuckAttributeReads.add(stuck);
        try (Stream<Path> entries = Files.list(tempDir)) {
            entries.forEach(fileAccess.unlistedOnRelisting::add);
        }
        LibraryDirectoryWalker walker = walker(4, Duration.ofMillis(300));

        LibraryScanResult result = assertTimeoutPreemptively(Duration.ofSeconds(10),
                () -> new LibraryFileHelper(walker).scanAllLibraryFiles(library(tempDir)));

        // As when its first listing finds it empty: nothing in it is kept, and all of it is in doubt, so a rescan
        // stops instead of taking the books it did not reach for deleted.
        assertThat(fileAccess.listed).filteredOn(tempDir::equals).hasSize(2);
        assertThat(result.files()).isEmpty();
        assertThat(result.incompletePaths()).containsExactly(tempDir);
        assertThat(result.emptyLibraryPaths()).containsExactly(tempDir);
        assertThat(result.isUnderIncompletePath(notReached)).isTrue();

        fileAccess.release();
        awaitNoStrandedListings(walker);
    }

    @Test
    void libraryPathMissingEntriesWhenReadAgainAfterAStuckEntryIsInDoubtAsAWhole() throws Exception {
        book("Author A/a.epub");
        Path stuck = book("Author B/b.epub").getParent();
        book("Author C/c.epub");
        fileAccess.listInNameOrder = true;
        fileAccess.stuckAttributeReads.add(stuck);
        fileAccess.unlistedOnRelisting.add(tempDir.resolve("Author A"));
        LibraryDirectoryWalker walker = walker(4, Duration.ofMillis(300));

        WalkedPath walked = assertTimeoutPreemptively(Duration.ofSeconds(10), () -> walker.walk(List.of(tempDir))).getFirst();

        // A listing that no longer returns an entry already looked at is not of the directory first read, or not of
        // all of it, so it cannot vouch for the entries it returns first. What was found is still scanned.
        assertThat(walked.incompletePaths()).containsExactly(tempDir);
        assertThat(walked.empty()).isFalse();
        assertThat(subdirectoryNames(walked.root())).containsExactly("Author A", "Author C");

        fileAccess.release();
        awaitNoStrandedListings(walker);
    }

    @Test
    void directoryMissingEntriesWhenReadAgainAfterAStuckEntryIsLeftOut() throws Exception {
        Path author = book("Author/a.epub").getParent();
        fileAccess.stuckAttributeReads.add(book("Author/b.epub"));
        book("Author/c.epub");
        book("Other/d.epub");
        fileAccess.listInNameOrder = true;
        fileAccess.unlistedOnRelisting.add(author.resolve("a.epub"));
        LibraryDirectoryWalker walker = walker(4, Duration.ofMillis(300));

        LibraryScanResult result = assertTimeoutPreemptively(Duration.ofSeconds(10),
                () -> new LibraryFileHelper(walker).scanAllLibraryFiles(library(tempDir)));

        assertThat(fileAccess.listed).filteredOn(author::equals).hasSize(2);
        assertThat(fileNames(result.files())).containsExactly("d.epub");
        assertThat(result.incompletePaths()).containsExactly(author);

        fileAccess.release();
        awaitNoStrandedListings(walker);
    }

    @Test
    void directoryGivenUpAsAWholeAddsNothingFoundInIt() throws Exception {
        // Listed in name order, the marker comes after the entries, so it is never reached.
        Path incoming = book("_incoming/(0) Sub/a.epub").getParent().getParent();
        book("_incoming/(1) b.epub");
        fileAccess.stuckAttributeReads.add(book("_incoming/(2) c.epub"));
        fileAccess.stuckAttributeReads.add(book("_incoming/(3) d.epub"));
        book("_incoming/.ignore");
        // Each track found would make a book of its own, which the next scan would group and so delete.
        Path folderBook = book("Author/Book/ch01.mp3").getParent();
        fileAccess.stuckAttributeReads.add(book("Author/Book/ch02.mp3"));
        fileAccess.stuckAttributeReads.add(book("Author/Book/ch03.mp3"));
        book("Author/Book/ch04.mp3");
        book("Other/e.epub");
        fileAccess.listInNameOrder = true;
        LibraryDirectoryWalker walker = walker(4, Duration.ofMillis(300));

        LibraryScanResult result = assertTimeoutPreemptively(Duration.ofSeconds(10),
                () -> new LibraryFileHelper(walker).scanAllLibraryFiles(library(tempDir)));

        // As for a directory stuck being read: whatever is in it may be ignored, or grouped otherwise.
        assertThat(fileNames(result.files())).containsExactly("e.epub");
        assertThat(result.incompletePaths()).containsExactlyInAnyOrder(incoming, folderBook);
        assertThat(fileAccess.listed).doesNotContain(incoming.resolve("(0) Sub"));

        fileAccess.release();
        awaitNoStrandedListings(walker);
    }

    @Test
    void audioFilesOfADirectoryWhoseGroupingDependsOnAnEntryGivenUpOnAreLeftOut() throws Exception {
        Path folderBook = book("Author/Book/ch01.mp3").getParent();
        fileAccess.stuckAttributeReads.add(book("Author/Book/ch02.mp3"));
        Path mixed = book("Mixed/a.mp3").getParent();
        book("Mixed/b.mp3");
        fileAccess.stuckAttributeReads.add(book("Mixed/book.epub"));
        book("Other/c.epub");
        fileAccess.listInNameOrder = true;
        LibraryDirectoryWalker walker = walker(4, Duration.ofMillis(300));

        LibraryScanResult result = assertTimeoutPreemptively(Duration.ofSeconds(10),
                () -> new LibraryFileHelper(walker).scanAllLibraryFiles(library(tempDir)));

        // Grouped as found, ch01.mp3 would make a book of its own and a.mp3 and b.mp3 a folder audiobook, each
        // deleted again by the next scan that sees the whole directory. Left out, they are kept by the incomplete
        // directory until then.
        assertThat(fileNames(result.files())).containsExactly("c.epub");
        assertThat(result.incompletePaths()).containsExactlyInAnyOrder(folderBook, mixed);

        fileAccess.release();
        awaitNoStrandedListings(walker);
    }

    private static void awaitNoStrandedListings(LibraryDirectoryWalker walker) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (walker.strandedListings() > 0 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(walker.strandedListings()).isZero();
    }

    @ParameterizedTest
    @EnumSource(Stall.class)
    void stopsListingWhileTooManyAbandonedListingsAreStillStuck(Stall stall) throws Exception {
        Path dead = tempDir.resolve("dead");
        for (int i = 0; i < 40; i++) {
            stall.stallIn(fileAccess, Files.createDirectories(dead.resolve("dir" + i)));
        }
        Path healthy = book("healthy/a.epub").getParent();
        int parallelism = 8;
        LibraryDirectoryWalker walker = walker(parallelism, Duration.ofMillis(200));

        // Rescan after rescan of a share that stays dead, each leaving the threads it gave up on stuck.
        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
            while (fileAccess.stuckCount.get() < LibraryDirectoryWalker.MAX_STRANDED_LISTINGS) {
                walker.walk(List.of(dead));
            }
        });
        assertThat(walker.strandedListings()).isGreaterThanOrEqualTo(fileAccess.stuckCount.get());
        assertThat(fileAccess.stuckCount.get()).isLessThan(LibraryDirectoryWalker.MAX_STRANDED_LISTINGS + parallelism);

        // No more threads are put at risk, not even for a healthy path: it is reported as not scanned.
        int stuckBefore = fileAccess.stuckCount.get();
        WalkedPath refused = walker.walk(List.of(healthy)).getFirst();
        assertThat(refused.root()).isNull();
        assertThat(refused.incompletePaths()).containsExactly(healthy);
        assertThat(fileAccess.listed).doesNotContain(healthy);
        assertThat(walker.walk(List.of(dead)).getFirst().incompletePaths()).containsExactly(dead);
        assertThat(fileAccess.stuckCount.get()).isEqualTo(stuckBefore);

        // Once the share answers again the stuck threads return, and walks list again.
        fileAccess.release();
        awaitNoStrandedListings(walker);
        WalkedPath walked = walker.walk(List.of(healthy)).getFirst();
        assertThat(walked.incompletePaths()).isEmpty();
        assertThat(walked.root().getBookFiles()).containsExactly("a.epub");
    }

    @ParameterizedTest
    @EnumSource(Stall.class)
    void stuckListingDoesNotKeepTheWalkedTreeAlive(Stall stall) throws Exception {
        for (int i = 0; i < 10; i++) {
            book("Author" + i + "/Book/book.epub");
        }
        stall.stallIn(fileAccess, Files.createDirectories(tempDir.resolve("Author3/Book/Stuck")));
        LibraryDirectoryWalker walker = walker(4, Duration.ofMillis(200));

        WeakReference<Directory> root = new WeakReference<>(assertTimeoutPreemptively(Duration.ofSeconds(10),
                () -> walker.walk(List.of(tempDir))).getFirst().root());

        // The thread wedged in the listing may stay stuck for good; it must not pin the scan's whole tree.
        assertThat(walker.strandedListings()).isEqualTo(1);
        for (int i = 0; i < 50 && root.get() != null; i++) {
            System.gc();
            Thread.sleep(20);
        }
        assertThat(root.get()).isNull();
        assertThat(fileAccess.stuckCount.get()).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(Stall.class)
    void abandonedListingFreesItsSlotExactlyOnce(Stall stall) throws Exception {
        // Sorts first, so it holds one of the two slots while the other directories queue behind it.
        Path stuckDir = Files.createDirectories(tempDir.resolve("a-stuck"));
        Path stuck = stall.stallIn(fileAccess, stuckDir);
        for (int i = 0; i < 24; i++) {
            book("dir" + i + "/book" + i + ".epub");
            fileAccess.listingDelays.put(tempDir.resolve("dir" + i), Duration.ofMillis(50));
        }

        List<WalkedPath> walked = assertTimeoutPreemptively(Duration.ofSeconds(10),
                () -> walker(2, Duration.ofMillis(300)).walk(List.of(tempDir)));

        assertThat(walked.getFirst().incompletePaths()).containsExactly(stuck);
        assertThat(walked.getFirst().root().getSubdirectories()).hasSize(25)
                .filteredOn(dir -> !dir.getPath().equals(stuckDir))
                .allSatisfy(dir -> assertThat(dir.getBookFiles()).hasSize(1));
        assertThat(child(walked.getFirst().root(), "a-stuck").getBookFiles()).isEmpty();
        // Two slots: the stuck listing's slot is reused once it is abandoned (by the listing that carries on
        // after an entry given up on, or another directory), never twice over.
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

    @ParameterizedTest
    @EnumSource(Stall.class)
    void stopsWalkingALibraryPathAfterTooManyTimeouts(Stall stall) throws Exception {
        Path dead = tempDir.resolve("dead");
        for (int i = 0; i < 40; i++) {
            stall.stallIn(fileAccess, Files.createDirectories(dead.resolve("dir" + i)));
        }
        Path healthy = tempDir.resolve("healthy");
        for (int i = 0; i < 5; i++) {
            book("healthy/dir" + i + "/book" + i + ".epub");
        }
        int parallelism = 3;

        List<WalkedPath> walked = assertTimeoutPreemptively(Duration.ofSeconds(10),
                () -> walker(parallelism, Duration.ofMillis(250)).walk(List.of(dead, healthy)));

        assertThat(walked.get(0).incompletePaths()).contains(dead);
        assertThat(walked.get(0).timedOut()).isTrue();
        assertThat(fileAccess.stuckCount.get())
                .isBetween(LibraryDirectoryWalker.MAX_TIMEOUTS_PER_PATH, LibraryDirectoryWalker.MAX_TIMEOUTS_PER_PATH + parallelism - 1);
        assertThat(walked.get(1).incompletePaths()).isEmpty();
        assertThat(walked.get(1).timedOut()).isFalse();
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
        // Listed in name order: one entry before the marker, the rest after it.
        Path beforeMarker = book("Ignored/-first.epub");
        book("Ignored/.ignore");
        book("Ignored/after.epub");
        book("Ignored/Sub/b.epub");
        Path nested = book("Author/c.epub");
        fileAccess.listInNameOrder = true;

        walker(4, Duration.ofSeconds(30)).walk(List.of(tempDir));

        assertThat(fileAccess.listed).containsExactlyInAnyOrder(tempDir, tempDir.resolve("Ignored"), tempDir.resolve("Author"));
        // An entry listed before the marker has been looked at already; nothing after it is.
        assertThat(fileAccess.attributesRead).containsExactlyInAnyOrder(
                tempDir, epub, cover, opf, empty, tempDir.resolve("Ignored"), tempDir.resolve("Author"), nested, beforeMarker);
        assertThat(fileAccess.readabilityChecked).containsExactlyInAnyOrder(epub, nested, beforeMarker);
        // The marker is recognised by its name alone.
        assertThat(fileAccess.existenceChecked).isEmpty();
    }

    @Test
    void readsEachEntrysAttributesAsTheListingReturnsIt() throws IOException {
        for (int i = 0; i < 20; i++) {
            book("Shelf/book" + i + ".epub");
        }
        book("Shelf/cover.jpg");
        Files.createDirectories(tempDir.resolve("Shelf/Sub"));
        Path shelf = tempDir.resolve("Shelf");

        walker(4, Duration.ofSeconds(30)).walk(List.of(tempDir));

        // A network client caches the attributes a listing brings along only briefly, so each entry is
        // looked at before the listing moves on, never after the whole directory has been read.
        List<ScriptedFileAccess.Call> calls = fileAccess.calls.stream()
                .filter(call -> shelf.equals(call.path().getParent()))
                .toList();
        assertThat(calls).hasSize(2 * 22);
        for (int i = 0; i < calls.size(); i += 2) {
            assertThat(calls.get(i).kind()).isEqualTo("entry");
            assertThat(calls.get(i + 1)).isEqualTo(new ScriptedFileAccess.Call("stat", calls.get(i).path()));
        }
    }

    @Test
    void markerInAnotherCaseCountsOnlyWhereTheFilesystemIgnoresCase() throws IOException {
        book("Shouting/.IGNORE");
        book("Shouting/loud.epub");
        Path lookup = tempDir.resolve("Shouting/.ignore");
        LibraryFileHelper helper = new LibraryFileHelper(walker(4, Duration.ofSeconds(30)));

        // Like the old Files.exists(dir/.ignore) lookup: a case-sensitive filesystem has no .ignore here...
        assertThat(fileNames(helper.getAllLibraryFiles(library(tempDir)))).containsExactly("loud.epub");
        assertThat(fileAccess.existenceChecked).containsExactly(lookup);

        // ...while one that ignores case, as SMB shares usually do, finds it.
        fileAccess.caseInsensitive = true;
        assertThat(helper.getAllLibraryFiles(library(tempDir))).isEmpty();
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
