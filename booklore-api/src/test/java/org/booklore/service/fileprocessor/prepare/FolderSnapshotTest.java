package org.booklore.service.fileprocessor.prepare;

import org.booklore.service.file.FileFingerprint;
import org.booklore.util.FileUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * One walk of a folder audiobook gives what the four separate listings gave: FileFingerprint.generateFolderHash,
 * FileUtils.getFolderSizeInKb, FileUtils.getFirstAudioFileInFolder and FileUtils.listAudioFilesInFolder.
 */
class FolderSnapshotTest {

    @TempDir
    Path tempDir;

    @Test
    void matchesTheSeparateListingsOnANestedFolder() throws IOException {
        Path folder = Files.createDirectory(tempDir.resolve("Book"));
        write(folder.resolve("02 - Middle.mp3"), 5000);
        write(folder.resolve("01 - Start.MP3"), 7000);
        write(folder.resolve("10 - End.m4b"), 3000);
        write(folder.resolve("Bonus.opus"), 1500);
        write(folder.resolve("cover.jpg"), 2500);
        write(folder.resolve("notes.txt"), 300);
        Path nested = Files.createDirectories(folder.resolve("extras/deeper"));
        write(folder.resolve("extras/chapter.mp3"), 4096);
        write(nested.resolve("data.bin"), 9000);

        FolderSnapshot snapshot = FolderSnapshot.scan(folder);

        assertSameAsSeparateListings(folder, snapshot);
        assertThat(snapshot.audioFiles()).extracting(p -> p.getFileName().toString())
                .containsExactly("01 - Start.MP3", "02 - Middle.mp3", "10 - End.m4b", "Bonus.opus");
        assertThat(snapshot.regularChildNames())
                .containsExactlyInAnyOrder("02 - Middle.mp3", "01 - Start.MP3", "10 - End.m4b", "Bonus.opus", "cover.jpg", "notes.txt");
        assertThat(snapshot.totalBytes()).isEqualTo(5000 + 7000 + 3000 + 1500 + 2500 + 300 + 4096 + 9000);
    }

    @Test
    void linksToFilesCountAndLinksToDirectoriesAndBrokenLinksDoNot() throws IOException {
        Path outside = Files.createDirectory(tempDir.resolve("outside"));
        write(outside.resolve("real.m4a"), 6000);
        Path outsideDir = Files.createDirectory(tempDir.resolve("outside-dir"));
        write(outsideDir.resolve("hidden.mp3"), 8000);

        Path folder = Files.createDirectory(tempDir.resolve("Book"));
        write(folder.resolve("b.mp3"), 2000);
        Files.createSymbolicLink(folder.resolve("a-linked.m4a"), outside.resolve("real.m4a"));
        Files.createSymbolicLink(folder.resolve("0-broken.mp3"), tempDir.resolve("nowhere.mp3"));
        Files.createSymbolicLink(folder.resolve("linked-dir"), outsideDir);
        Path sub = Files.createDirectory(folder.resolve("sub"));
        Files.createSymbolicLink(sub.resolve("deep-link.mp3"), outside.resolve("real.m4a"));

        FolderSnapshot snapshot = FolderSnapshot.scan(folder);

        assertSameAsSeparateListings(folder, snapshot);
        assertThat(snapshot.audioFiles()).extracting(p -> p.getFileName().toString()).containsExactly("a-linked.m4a", "b.mp3");
        assertThat(snapshot.regularChildNames()).containsExactlyInAnyOrder("a-linked.m4a", "b.mp3");
        assertThat(snapshot.totalBytes()).isEqualTo(2000 + 6000 + 6000);
    }

    @Test
    void folderWithoutAudioFilesFailsLikeTheFolderHash() throws IOException {
        Path folder = Files.createDirectory(tempDir.resolve("Empty"));
        write(folder.resolve("readme.txt"), 10);

        FolderSnapshot snapshot = FolderSnapshot.scan(folder);

        assertThat(snapshot.audioFiles()).isEmpty();
        assertThat(snapshot.totalBytes() / 1024).isEqualTo(FileUtils.getFolderSizeInKb(folder));
        String expected = messageOf(() -> FileFingerprint.generateFolderHash(folder));
        assertThatThrownBy(() -> FileFacts.of(snapshot)).isInstanceOf(RuntimeException.class).hasMessage(expected)
                .hasMessage("No audio files in folder: " + folder);
    }

    @Test
    void missingFolderFailsLikeTheFolderHash() {
        Path folder = tempDir.resolve("Missing");

        String expected = messageOf(() -> FileFingerprint.generateFolderHash(folder));
        assertThatThrownBy(() -> FolderSnapshot.scan(folder)).isInstanceOf(RuntimeException.class).hasMessage(expected)
                .hasMessage("Folder does not exist: " + folder);
    }

    @Test
    void fileInsteadOfFolderFailsLikeTheFolderHash() throws IOException {
        Path file = write(tempDir.resolve("book.mp3"), 100);

        String expected = messageOf(() -> FileFingerprint.generateFolderHash(file));
        assertThatThrownBy(() -> FolderSnapshot.scan(file)).isInstanceOf(RuntimeException.class).hasMessage(expected);
    }

    @Test
    void linkedFolderIsListedThroughTheLink() throws IOException {
        Path real = Files.createDirectory(tempDir.resolve("real"));
        write(real.resolve("01.mp3"), 3000);
        write(real.resolve("02.mp3"), 4000);
        Path link = Files.createSymbolicLink(tempDir.resolve("Book"), real);

        FolderSnapshot snapshot = FolderSnapshot.scan(link);

        assertThat(FileFacts.of(snapshot).hash()).isEqualTo(FileFingerprint.generateFolderHash(link));
        assertThat(snapshot.audioFiles()).containsExactlyElementsOf(FileUtils.listAudioFilesInFolder(link));
        // Deliberately not the old size: Files.walk does not follow the folder link itself and counted 0 bytes
        assertThat(FileUtils.getFolderSizeInKb(link)).isZero();
        assertThat(snapshot.totalBytes()).isEqualTo(7000);
    }

    @Test
    void fileFactsOfOneFileAreItsHashAndSize() throws IOException {
        Path file = write(tempDir.resolve("book.epub"), 70_000);

        FileFacts facts = FileFacts.read(file);

        assertThat(facts.hash()).isEqualTo(FileFingerprint.generateHash(file));
        assertThat(facts.sizeKb()).isEqualTo(FileUtils.getFileSizeInKb(file)).isEqualTo(68L);
    }

    @Test
    void fileFactsOfAMissingFileFailLikeTheHash() {
        Path file = tempDir.resolve("gone.epub");

        String expected = messageOf(() -> FileFingerprint.generateHash(file));
        assertThatThrownBy(() -> FileFacts.read(file)).isInstanceOf(RuntimeException.class).hasMessage(expected);
    }

    private static void assertSameAsSeparateListings(Path folder, FolderSnapshot snapshot) throws IOException {
        FileFacts facts = FileFacts.of(snapshot);
        assertThat(facts.hash()).isEqualTo(FileFingerprint.generateFolderHash(folder));
        assertThat(facts.sizeKb()).isEqualTo(FileUtils.getFolderSizeInKb(folder));
        assertThat(snapshot.audioFiles()).containsExactlyElementsOf(FileUtils.listAudioFilesInFolder(folder));
        assertThat(snapshot.audioFiles().getFirst()).isEqualTo(FileUtils.getFirstAudioFileInFolder(folder).orElseThrow());
        Set<String> regularChildren;
        try (Stream<Path> children = Files.list(folder)) {
            regularChildren = children.filter(Files::isRegularFile).map(p -> p.getFileName().toString()).collect(Collectors.toSet());
        }
        assertThat(snapshot.regularChildNames()).isEqualTo(regularChildren);
    }

    private static Path write(Path file, int size) throws IOException {
        byte[] bytes = new byte[size];
        new Random(file.getFileName().toString().hashCode()).nextBytes(bytes);
        return Files.write(file, bytes);
    }

    private static String messageOf(Runnable call) {
        try {
            call.run();
        } catch (RuntimeException e) {
            return e.getMessage();
        }
        throw new AssertionError("Expected the call to fail");
    }
}
