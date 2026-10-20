package org.booklore.service.library;

import org.booklore.model.dto.settings.LibraryFile;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.entity.LibraryPathEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.model.enums.LibraryOrganizationMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the new walk to the old one: for the same tree on disk, {@link LibraryFileHelper}
 * must report exactly the library files {@link LegacyLibraryFileWalker} (the previous
 * single-threaded {@code Files.walkFileTree} implementation) does, in every mode.
 */
class LibraryFileHelperEquivalenceTest {

    @TempDir
    Path tempDir;

    private final LegacyLibraryFileWalker legacy = new LegacyLibraryFileWalker();
    private final LibraryFileHelper helper = new LibraryFileHelper(
            new LibraryDirectoryWalker(8, Duration.ofSeconds(30), new LibraryDirectoryWalker.FileAccess()));

    /** What identifies a library file; the entities are the same objects on both sides. */
    private record Key(Long pathId, String fileSubPath, String fileName, BookFileType type, boolean folderBased) {
        static List<Key> of(List<LibraryFile> files) {
            return files.stream()
                    .map(f -> new Key(f.getLibraryPathEntity().getId(), f.getFileSubPath(), f.getFileName(),
                            f.getBookFileType(), f.isFolderBased()))
                    .toList();
        }
    }

    private List<Key> assertSameAsLegacy(LibraryEntity library) throws IOException {
        List<Key> autoDetect = Key.of(legacy.getAllLibraryFiles(library));
        assertThat(Key.of(helper.getAllLibraryFiles(library))).containsExactlyInAnyOrderElementsOf(autoDetect);
        assertThat(Key.of(helper.scanAllLibraryFiles(library).files())).containsExactlyInAnyOrderElementsOf(autoDetect);
        assertThat(Key.of(helper.getAllLibraryFilesFlat(library)))
                .containsExactlyInAnyOrderElementsOf(Key.of(legacy.getAllLibraryFilesFlat(library)));
        for (LibraryOrganizationMode mode : LibraryOrganizationMode.values()) {
            for (List<BookFileType> formats : List.of(List.<BookFileType>of(), List.of(BookFileType.EPUB, BookFileType.AUDIOBOOK))) {
                library.setOrganizationMode(mode);
                library.setAllowedFormats(formats);
                assertThat(Key.of(helper.getLibraryFiles(library)))
                        .as("mode %s, formats %s", mode, formats)
                        .containsExactlyInAnyOrderElementsOf(Key.of(legacy.getLibraryFiles(library)));
            }
        }
        library.setOrganizationMode(null);
        library.setAllowedFormats(null);
        return autoDetect;
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

    private static void file(Path root, String relativePath, int size) throws IOException {
        Path file = root.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[size]);
    }

    private static void file(Path root, String relativePath) throws IOException {
        file(root, relativePath, 1);
    }

    @Test
    void handBuiltLibraryMatchesTheOldWalk() throws IOException {
        Path root = Files.createDirectories(tempDir.resolve("Books"));
        Path outside = Files.createDirectories(tempDir.resolve("Elsewhere"));

        // Root: ebooks and audio files that never collapse, plus everything the walk ignores
        file(root, "Salem's Lot.epub");
        file(root, "BOOK.EPUB");
        file(root, "track1.mp3");
        file(root, "track2.mp3");
        file(root, "cover.jpg");
        file(root, "metadata.opf");
        file(root, ".ignore");
        file(root, "empty.epub", 0);
        file(root, ".hidden.epub");
        file(root, "._Salem's Lot.epub");
        file(root, ".DS_Store");
        for (String temp : List.of("a.epub.part", "b.epub.tmp", "c.epub.crdownload", "d.epub.download",
                "e.epub.bak", "f.epub.old", "g.epub.temp", "h.epub.tempfile")) {
            file(root, temp);
        }
        // Folder audiobook, with nested audio below it and a lone file in a subfolder
        for (int i = 1; i <= 5; i++) {
            file(root, "Author A/Book 1/ch0" + i + ".mp3");
        }
        file(root, "Author A/Book 1/cover.jpg");
        file(root, "Author A/Book 1/CD2/t1.mp3");
        file(root, "Author A/Book 1/CD2/t2.mp3");
        file(root, "Author A/Book 1/Extras/bonus.mp3");
        file(root, "Author A/@eaDir/thumb.epub");
        // Series folder of m4b files, and m4b parts with generic titles (one audiobook)
        file(root, "Series/01 - The Fellowship.m4b");
        file(root, "Series/02 - The Two Towers.m4b");
        file(root, "Series/03 - Return of the King.m4b");
        file(root, "Parts/Part 1.m4b");
        file(root, "Parts/Part 2.m4b");
        // Mixed ebook and audio: audio stays individual
        file(root, "Mixed/book.epub");
        file(root, "Mixed/track1.mp3");
        file(root, "Mixed/track2.opus");
        file(root, "Single/standalone.m4b");
        file(root, "Zero/a.mp3", 0);
        file(root, "Zero/b.m4a");
        // Every format
        for (String ext : List.of("pdf", "epub", "cbz", "cbr", "cb7", "mobi", "azw3", "azw", "fb2")) {
            file(root, "Formats/book." + ext);
        }
        // Skipped on purpose
        file(root, "Ignored/.ignore");
        file(root, "Ignored/hidden.epub");
        file(root, "Ignored/Sub/deeper.epub");
        file(root, ".hiddenDir/x.epub");
        file(root, "#recycle/x.epub");
        file(root, "@eaDir/x.epub");
        file(root, ".caltrash/x.epub");
        // A directory named like a book, and a deep tree
        file(root, "Apple.epub/inner.epub");
        file(root, "Deep/A/B/C/D/deep.epub");
        // Links: a directory elsewhere, a file, a dangling link, and two loops
        file(outside, "linked.epub");
        file(outside, "Linked Audio/c1.mp3");
        file(outside, "Linked Audio/c2.mp3");
        Files.createSymbolicLink(root.resolve("Linked"), outside);
        Files.createSymbolicLink(root.resolve("alias.epub"), root.resolve("Salem's Lot.epub"));
        Files.createSymbolicLink(root.resolve("dangling.epub"), root.resolve("gone.epub"));
        Files.createDirectories(root.resolve("Loop"));
        Files.createSymbolicLink(root.resolve("Loop/back-to-root"), root);
        Files.createSymbolicLink(root.resolve("Deep/A/B/up"), root.resolve("Deep/A"));

        List<Key> files = assertSameAsLegacy(library(root));

        // The oracle is only worth something if the tree exercised the interesting rules.
        assertThat(files).hasSize(30);
        assertThat(files).anyMatch(k -> k.folderBased() && k.fileName().equals("Book 1"));
        assertThat(files).anyMatch(k -> k.folderBased() && k.fileName().equals("CD2"));
        assertThat(files).anyMatch(k -> k.fileName().equals("02 - The Two Towers.m4b"));
        assertThat(files).anyMatch(k -> k.folderBased() && k.fileName().equals("Linked Audio"));
        assertThat(files).anyMatch(k -> k.fileName().equals("inner.epub"));
    }

    @Test
    void libraryPathReachedThroughASymlinkMatchesTheOldWalk() throws IOException {
        Path real = Files.createDirectories(tempDir.resolve("real"));
        file(real, "Author/Book/book.epub");
        file(real, "Author/Audio/a.mp3");
        file(real, "Author/Audio/b.mp3");
        Path link = Files.createSymbolicLink(tempDir.resolve("link"), real);

        assertThat(assertSameAsLegacy(library(link))).hasSize(2);
    }

    @Test
    void missingAndIgnoredLibraryPathsMatchTheOldWalk() throws IOException {
        Path present = Files.createDirectories(tempDir.resolve("present"));
        file(present, "book.epub");
        file(tempDir, ".hidden-library/book.epub");

        assertThat(assertSameAsLegacy(library(present, tempDir.resolve("missing"), tempDir.resolve(".hidden-library"))))
                .hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20})
    void randomLibraryMatchesTheOldWalk(long seed) throws IOException {
        Random random = new Random(seed);
        Path root = Files.createDirectories(tempDir.resolve("lib"));
        List<Path> directories = new ArrayList<>();
        populate(root, 0, random, directories);

        // A few links between directories: duplicates of other subtrees, and loops back up.
        for (int i = 0; i < 3 && !directories.isEmpty(); i++) {
            Path from = directories.get(random.nextInt(directories.size()));
            Path to = random.nextBoolean() ? root : directories.get(random.nextInt(directories.size()));
            Path link = from.resolve("link" + i);
            if (!Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                Files.createSymbolicLink(link, to);
            }
        }
        Path nested = directories.isEmpty() ? root : directories.get(random.nextInt(directories.size()));

        assertSameAsLegacy(library(root, nested));
    }

    private static final List<String> DIRECTORY_NAMES = List.of(
            "Author", "Book", "CD1", "Disc 2", "Series", "Extras", "a", "B", "z", "Book.epub",
            ".hidden", "#recycle", "@eaDir", ".caltrash");
    private static final List<String> FILE_NAMES = List.of(
            "ch01.mp3", "ch02.mp3", "ch03.mp3", "Track.MP3", "01 - Alpha.m4b", "02 - Beta.m4b",
            "Part 1.m4b", "Part 2.m4b", "a.m4a", "talk.opus", "book.epub", "Book.EPUB", "comic.cbz",
            "doc.pdf", "story.fb2", "novel.azw3", "cover.jpg", "metadata.opf", ".DS_Store",
            "._book.epub", "book.epub.part", "x.tmp", ".ignore");

    private static void populate(Path dir, int depth, Random random, List<Path> directories) throws IOException {
        int fileCount = random.nextInt(7);
        for (int i = 0; i < fileCount; i++) {
            String name = FILE_NAMES.get(random.nextInt(FILE_NAMES.size()));
            if (name.equals(".ignore") && random.nextInt(3) != 0) {
                continue;
            }
            Files.write(dir.resolve(name), new byte[random.nextInt(7) == 0 ? 0 : 1]);
        }
        if (random.nextInt(10) == 0) {
            Files.createSymbolicLink(dir.resolve("dangling.epub"), dir.resolve("gone.epub"));
        }
        if (depth == 4) {
            return;
        }
        int directoryCount = random.nextInt(depth == 0 ? 6 : 4);
        for (int i = 0; i < directoryCount; i++) {
            Path sub = dir.resolve(DIRECTORY_NAMES.get(random.nextInt(DIRECTORY_NAMES.size())));
            if (Files.exists(sub, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            Files.createDirectory(sub);
            directories.add(sub);
            populate(sub, depth + 1, random, directories);
        }
    }
}
