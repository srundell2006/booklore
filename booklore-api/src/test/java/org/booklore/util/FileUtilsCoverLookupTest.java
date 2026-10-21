package org.booklore.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * FileUtils.findCoverImageInFolder lists the folder once instead of probing up to 18 names. Wherever names match in
 * case it must pick what the old probe picked; {@link #legacyFindCoverImageInFolder} is the old code, kept as the oracle.
 */
class FileUtilsCoverLookupTest {

    private static final List<String> CANDIDATES = List.of(
            "cover.jpg", "cover.jpeg", "cover.png", "cover.webp", "cover.gif", "cover.bmp",
            "folder.jpg", "folder.jpeg", "folder.png", "folder.webp", "folder.gif", "folder.bmp",
            "image.jpg", "image.jpeg", "image.png", "image.webp", "image.gif", "image.bmp");
    private static final List<String> DECOYS = List.of(
            "cover.tiff", "covers.jpg", "my-cover.jpg", "back.jpg", "cover", "book.epub", "image.svg", "folder.jpg.bak");

    @TempDir
    Path tempDir;

    private int folders;

    @BeforeEach
    void setUp() {
        folders = 0;
    }

    @Test
    void everyPairOfCandidatesPicksWhatTheProbePicked() throws IOException {
        for (int i = 0; i < CANDIDATES.size(); i++) {
            for (int j = i + 1; j < CANDIDATES.size(); j++) {
                Path folder = folderWith(List.of(CANDIDATES.get(j), CANDIDATES.get(i), "book.epub"));

                assertMatchesLegacy(folder);
                assertThat(FileUtils.findCoverImageInFolder(folder)).contains(folder.resolve(CANDIDATES.get(i)));
            }
        }
    }

    @Test
    void randomMixesPickWhatTheProbePicked() throws IOException {
        Random random = new Random(42);
        for (int round = 0; round < 150; round++) {
            List<String> names = new ArrayList<>();
            for (String candidate : CANDIDATES) {
                if (random.nextInt(6) == 0) names.add(candidate);
            }
            for (String decoy : DECOYS) {
                if (random.nextBoolean()) names.add(decoy);
            }
            Path folder = folderWith(names);
            if (random.nextInt(4) == 0 && !names.contains("cover.jpg")) {
                Files.createDirectory(folder.resolve("cover.jpg"));
            }

            assertMatchesLegacy(folder);
        }
    }

    @Test
    void namesAloneGiveTheSamePick() throws IOException {
        Path folder = folderWith(List.of("image.png", "folder.gif", "notes.txt"));

        assertThat(FileUtils.findCoverImageInNames(folder, Set.of("image.png", "folder.gif", "notes.txt")))
                .contains(folder.resolve("folder.gif"))
                .isEqualTo(legacyFindCoverImageInFolder(folder));
        assertThat(FileUtils.findCoverImageInNames(folder, Set.of("notes.txt"))).isEmpty();
    }

    @Test
    void directoryNamedLikeACoverIsSkipped() throws IOException {
        Path folder = folderWith(List.of("folder.png"));
        Files.createDirectory(folder.resolve("cover.jpg"));
        Files.createDirectory(folder.resolve("Cover.jpeg"));

        assertMatchesLegacy(folder);
        assertThat(FileUtils.findCoverImageInFolder(folder)).contains(folder.resolve("folder.png"));
    }

    @Test
    void onlyDirectoriesNamedLikeCoversGiveNothing() throws IOException {
        Path folder = folderWith(List.of("book.epub"));
        Files.createDirectory(folder.resolve("cover.jpg"));

        assertThat(FileUtils.findCoverImageInFolder(folder)).isEmpty();
    }

    @Test
    void linkedCoverCountsAndBrokenLinkDoesNot() throws IOException {
        Path folder = folderWith(List.of("image.jpg"));
        Path target = Files.writeString(tempDir.resolve("elsewhere.jpg"), "x");
        Files.createSymbolicLink(folder.resolve("folder.png"), target);
        Files.createSymbolicLink(folder.resolve("cover.jpg"), tempDir.resolve("nowhere.jpg"));

        assertMatchesLegacy(folder);
        assertThat(FileUtils.findCoverImageInFolder(folder)).contains(folder.resolve("folder.png"));
    }

    @Test
    void namesDifferingInCaseMatchWithExactCasePreferred() throws IOException {
        assumeCaseSensitiveFilesystem();

        Path onlyUpperCase = folderWith(List.of("Cover.JPG", "image.jpg"));
        // The old probe asked for cover.jpg, which only a case-insensitive filesystem (such as SMB) resolves
        assertThat(legacyFindCoverImageInFolder(onlyUpperCase)).contains(onlyUpperCase.resolve("image.jpg"));
        assertThat(FileUtils.findCoverImageInFolder(onlyUpperCase)).contains(onlyUpperCase.resolve("Cover.JPG"));

        Path bothCases = folderWith(List.of("Cover.jpg", "cover.jpg", "COVER.JPG"));
        assertThat(FileUtils.findCoverImageInFolder(bothCases)).contains(bothCases.resolve("cover.jpg"));

        Path noExactCase = folderWith(List.of("Cover.jpg", "COVER.JPG"));
        assertThat(FileUtils.findCoverImageInFolder(noExactCase)).contains(noExactCase.resolve("COVER.JPG"));

        // Priority still runs over the well-known names first, as the probe on SMB did
        Path priority = folderWith(List.of("FOLDER.PNG", "image.jpg"));
        assertThat(FileUtils.findCoverImageInFolder(priority)).contains(priority.resolve("FOLDER.PNG"));
    }

    @Test
    void largeFolderIsProbedNameByName() throws IOException {
        assumeCaseSensitiveFilesystem();

        // 1024 entries are listed: the case-insensitive match is found
        Path listed = folderWith(junk(1023));
        Files.writeString(listed.resolve("Cover.JPG"), "x");
        assertThat(FileUtils.findCoverImageInFolder(listed)).contains(listed.resolve("Cover.JPG"));

        // 1025 entries fall back to the old probe, which only finds names in exactly its case
        Path probed = folderWith(junk(1024));
        Files.writeString(probed.resolve("Cover.JPG"), "x");
        assertThat(FileUtils.findCoverImageInFolder(probed)).isEmpty();
        Files.writeString(probed.resolve("image.webp"), "x");
        assertThat(FileUtils.findCoverImageInFolder(probed)).contains(probed.resolve("image.webp"));
    }

    @Test
    void missingNullAndNonFolderPathsGiveNothing() throws IOException {
        Path file = Files.writeString(tempDir.resolve("cover.jpg"), "x");

        assertThat(FileUtils.findCoverImageInFolder(null)).isEmpty();
        assertThat(FileUtils.findCoverImageInFolder(tempDir.resolve("missing"))).isEmpty();
        assertThat(FileUtils.findCoverImageInFolder(file)).isEmpty();
    }

    private void assertMatchesLegacy(Path folder) {
        assertThat(FileUtils.findCoverImageInFolder(folder)).as("cover in %s", folder).isEqualTo(legacyFindCoverImageInFolder(folder));
    }

    private Path folderWith(List<String> names) throws IOException {
        Path folder = Files.createDirectory(tempDir.resolve("folder-" + folders++));
        for (String name : names) {
            Files.writeString(folder.resolve(name), name);
        }
        return folder;
    }

    private static List<String> junk(int count) {
        List<String> names = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            names.add("page-" + i + ".txt");
        }
        return names;
    }

    private void assumeCaseSensitiveFilesystem() throws IOException {
        Path probe = Files.writeString(tempDir.resolve("case-probe.txt"), "x");
        boolean caseSensitive = !Files.exists(tempDir.resolve("CASE-PROBE.TXT"));
        Files.delete(probe);
        assumeThat(caseSensitive).as("needs a case-sensitive filesystem").isTrue();
    }

    /** FileUtils.findCoverImageInFolder before it listed the folder. */
    private static Optional<Path> legacyFindCoverImageInFolder(Path folderPath) {
        try {
            if (folderPath == null || !Files.exists(folderPath) || !Files.isDirectory(folderPath)) {
                return Optional.empty();
            }
            for (String baseName : List.of("cover", "folder", "image")) {
                for (String ext : List.of("jpg", "jpeg", "png", "webp", "gif", "bmp")) {
                    Path candidate = folderPath.resolve(baseName + "." + ext);
                    if (Files.isRegularFile(candidate)) {
                        return Optional.of(candidate);
                    }
                }
            }
            return Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
