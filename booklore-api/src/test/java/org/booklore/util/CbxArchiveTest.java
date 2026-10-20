package org.booklore.util;

import org.booklore.util.CbxTestArchives.Entry;
import org.booklore.util.CbxTestArchives.Format;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.MockedStatic;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.booklore.util.CbxTestArchives.SOLID_CB7_COMIC_INFO_LAST;
import static org.booklore.util.CbxTestArchives.dir;
import static org.booklore.util.CbxTestArchives.file;
import static org.booklore.util.CbxTestArchives.imageFile;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

class CbxArchiveTest {

    @TempDir
    Path tempDir;

    private File write(Format format, Entry... entries) throws IOException {
        return format.write(tempDir, "archive", entries).toFile();
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static int imageWidth(byte[] image) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(image)).getWidth();
    }

    @ParameterizedTest
    @EnumSource(Format.class)
    void listsFileEntriesInArchiveOrderWithoutDirectories(Format format) throws IOException {
        File file = write(format,
                dir("b"),
                imageFile("b/2.jpg", 2),
                dir("a"),
                file("a/ComicInfo.xml", "<ComicInfo/>"),
                imageFile("1.png", 1),
                file("a/notes.txt", "notes"));

        try (CbxArchive archive = CbxArchive.open(file, format.archiveType())) {
            assertThat(archive.fileNames()).containsExactly("b/2.jpg", "a/ComicInfo.xml", "1.png", "a/notes.txt");
        }
    }

    @ParameterizedTest
    @EnumSource(Format.class)
    void readsEntriesInAnyOrderFromOneOpen(Format format) throws IOException {
        File file = write(format,
                file("first.txt", "one"),
                dir("deep"),
                dir("deep/er"),
                file("deep/er/second.txt", "two"),
                file("third.txt", "three"));

        try (CbxArchive archive = CbxArchive.open(file, format.archiveType())) {
            assertThat(archive.readEntry("third.txt")).isEqualTo(bytes("three"));
            assertThat(archive.readEntry("first.txt")).isEqualTo(bytes("one"));
            assertThat(archive.readEntry("deep/er/second.txt")).isEqualTo(bytes("two"));
            assertThat(archive.readEntry("third.txt")).isEqualTo(bytes("three"));
        }
    }

    @ParameterizedTest
    @EnumSource(Format.class)
    void readsEmptyEntries(Format format) throws IOException {
        File file = write(format, file("empty.txt", new byte[0]), file("after.txt", "after"));

        try (CbxArchive archive = CbxArchive.open(file, format.archiveType())) {
            assertThat(archive.readEntry("empty.txt")).isEmpty();
            assertThat(archive.readEntry("after.txt")).isEqualTo(bytes("after"));
        }
    }

    @ParameterizedTest
    @EnumSource(value = Format.class, names = {"CB7", "CBR"})
    void duplicateNameIsListedOnceAndReadsAsTheFirstEntry(Format format) throws IOException {
        File file = write(format,
                file("page.jpg", "first copy"),
                file("other.txt", "other"),
                file("page.jpg", "second copy"));

        try (CbxArchive archive = CbxArchive.open(file, format.archiveType())) {
            assertThat(archive.fileNames()).containsExactly("page.jpg", "other.txt");
            assertThat(archive.readEntry("page.jpg")).isEqualTo(bytes("first copy"));
        }
    }

    @ParameterizedTest
    @EnumSource(Format.class)
    void readingAMissingEntryOrADirectoryFails(Format format) throws IOException {
        File file = write(format, dir("folder"), file("folder/page.txt", "page"));

        try (CbxArchive archive = CbxArchive.open(file, format.archiveType())) {
            assertThatThrownBy(() -> archive.readEntry("missing.txt")).isInstanceOf(FileNotFoundException.class);
            assertThatThrownBy(() -> archive.readEntry(format == Format.CBZ ? "folder/" : "folder"))
                    .isInstanceOf(FileNotFoundException.class);
        }
    }

    @Test
    void zipOpensWhateverTheFileIsNamed() throws IOException {
        File zipNamedCbr = CbxTestArchives.zip(tempDir.resolve("mismatched.cbr"), file("a.txt", "a")).toFile();

        try (CbxArchive archive = CbxArchive.open(zipNamedCbr, ArchiveUtils.ArchiveType.ZIP)) {
            assertThat(archive.readEntry("a.txt")).isEqualTo(bytes("a"));
        }
    }

    @Test
    void unknownTypeDoesNotOpen() throws IOException {
        File text = Files.writeString(tempDir.resolve("notes.txt"), "plain text").toFile();

        assertThatThrownBy(() -> CbxArchive.open(text, ArchiveUtils.ArchiveType.UNKNOWN))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("notes.txt");
    }

    @ParameterizedTest
    @EnumSource(value = Format.class, names = {"CBZ", "CB7"})
    void corruptArchiveDoesNotOpen(Format format) throws IOException {
        File garbage = Files.writeString(tempDir.resolve("garbage"), "not an archive").toFile();

        assertThatThrownBy(() -> CbxArchive.open(garbage, format.archiveType())).isInstanceOf(IOException.class);
    }

    @Nested
    class SolidSevenZip {

        @Test
        void readsEntriesOfOneBlockInAnyOrderFromOneOpen() throws IOException {
            try (CbxArchive archive = CbxArchive.open(SOLID_CB7_COMIC_INFO_LAST.toFile(), ArchiveUtils.ArchiveType.SEVEN_ZIP)) {
                assertThat(archive.fileNames()).containsExactly("pages/002.png", "pages/001.png", "pages/003.png", "ComicInfo.xml");

                // The cover as a scan reads it, decoding past 002.png in the block
                assertThat(imageWidth(archive.readEntry("pages/001.png"))).isEqualTo(1);
                // Then ComicInfo.xml further on: the decoder first drains the cover's entry stream,
                // which the read above has already closed
                assertThat(archive.readEntry("ComicInfo.xml")).asString(StandardCharsets.UTF_8)
                        .contains("<Title>Solid Last</Title>");
                // Entries before the last one read start decoding the block again
                assertThat(imageWidth(archive.readEntry("pages/002.png"))).isEqualTo(2);
                assertThat(imageWidth(archive.readEntry("pages/003.png"))).isEqualTo(3);
                assertThat(imageWidth(archive.readEntry("pages/001.png"))).isEqualTo(1);
            }
        }
    }

    @Nested
    class RarThatJunrarCannotRead {

        private final Path rar5 = Path.of("src/test/resources/cbx/test-rar5.cbr");

        @Test
        void failsToOpenWithoutUnrarCli() {
            try (MockedStatic<UnrarHelper> unrar = mockStatic(UnrarHelper.class)) {
                unrar.when(UnrarHelper::isAvailable).thenReturn(false);

                assertThatThrownBy(() -> CbxArchive.open(rar5.toFile(), ArchiveUtils.ArchiveType.RAR))
                        .isInstanceOf(IOException.class)
                        .hasMessageContaining("test-rar5.cbr");
                unrar.verify(() -> UnrarHelper.listEntries(any()), never());
            }
        }

        @Test
        void listsOnceThroughUnrarCliAndExtractsPerRead() throws IOException {
            try (MockedStatic<UnrarHelper> unrar = mockStatic(UnrarHelper.class)) {
                unrar.when(UnrarHelper::isAvailable).thenReturn(true);
                unrar.when(() -> UnrarHelper.listEntries(rar5))
                        .thenReturn(List.of("ComicInfo.xml", "pages", "pages/001.jpg", "ComicInfo.xml"));
                unrar.when(() -> UnrarHelper.extractEntryBytes(rar5, "ComicInfo.xml")).thenReturn(bytes("<ComicInfo/>"));
                unrar.when(() -> UnrarHelper.extractEntryBytes(rar5, "pages/001.jpg")).thenReturn(bytes("jpeg"));

                try (CbxArchive archive = CbxArchive.open(rar5.toFile(), ArchiveUtils.ArchiveType.RAR)) {
                    assertThat(archive.fileNames()).containsExactly("ComicInfo.xml", "pages", "pages/001.jpg");
                    assertThat(archive.readEntry("pages/001.jpg")).isEqualTo(bytes("jpeg"));
                    assertThat(archive.readEntry("ComicInfo.xml")).isEqualTo(bytes("<ComicInfo/>"));
                }

                unrar.verify(() -> UnrarHelper.listEntries(rar5), times(1));
                unrar.verify(() -> UnrarHelper.extractEntryBytes(eq(rar5), any()), times(2));
            }
        }
    }
}
