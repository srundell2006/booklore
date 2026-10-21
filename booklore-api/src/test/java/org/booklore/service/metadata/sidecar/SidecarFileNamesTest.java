package org.booklore.service.metadata.sidecar;

import org.booklore.config.AppProperties;
import org.booklore.service.appsettings.AppSettingService;
import org.booklore.util.FileService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.CRC32;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.Mockito.mock;

class SidecarFileNamesTest {

    private final SidecarMetadataMapper mapper = new SidecarMetadataMapper();
    private final SidecarMetadataWriter writer = new SidecarMetadataWriter(
            mock(AppProperties.class), mapper, mock(FileService.class), mock(AppSettingService.class));
    private final SidecarMetadataReader reader = new SidecarMetadataReader(mapper);

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
            "Title.epub                                                 | Title",
            "Title.EPUB                                                 | Title",
            "Mr. Mercedes - Stephen King.azw3                           | Mr. Mercedes - Stephen King",
            "Old Kindle Book.azw                                        | Old Kindle Book",
            "01. The Assassin's Blade - Sarah J. Maas (2015).m4b        | 01. The Assassin's Blade - Sarah J. Maas (2015)",
            "Comic.cbz                                                  | Comic",
            // Folder audiobooks have no extension: a dot in the name is part of the name
            "01. The Farthest Shore - Ursula K. Le Guin (2012)          | 01. The Farthest Shore - Ursula K. Le Guin (2012)",
            "'01.  - Naguib Mahfouz (2000)'                             | '01.  - Naguib Mahfouz (2000)'",
            "Vol. 1                                                     | Vol. 1",
            // Unchanged from before: a name that is only an extension keeps it
            ".epub                                                      | .epub",
    })
    void baseName_cutsOnlyARealBookExtension(String fileName, String expected) {
        assertThat(SidecarFileNames.baseName(Path.of("/books/A/Author", fileName))).isEqualTo(expected);
    }

    @Test
    void folderAudiobook_sidecarKeepsTheWholeFolderName() {
        Path folder = Path.of("/audiobooks/G/Ursula K. Le Guin/The Earthsea Cycle/01. The Farthest Shore - Ursula K. Le Guin (2012)");
        Path parent = folder.getParent();

        assertThat(writer.getSidecarPath(folder)).isEqualTo(parent.resolve("01. The Farthest Shore - Ursula K. Le Guin (2012).metadata.json"));
        assertThat(writer.getCoverPath(folder)).isEqualTo(parent.resolve("01. The Farthest Shore - Ursula K. Le Guin (2012).cover.jpg"));
        assertThat(reader.getSidecarPath(folder)).isEqualTo(writer.getSidecarPath(folder));
        assertThat(reader.getCoverPath(folder)).isEqualTo(writer.getCoverPath(folder));
        assertThat(mapper.getCoverFileName(folder)).isEqualTo("01. The Farthest Shore - Ursula K. Le Guin (2012).cover.jpg");
    }

    @Test
    void bookFile_sidecarNamesAreUnchanged() {
        Path book = Path.of("/books/S/Stephen King/Mr. Mercedes - Stephen King.epub");

        assertThat(writer.getSidecarPath(book)).isEqualTo(Path.of("/books/S/Stephen King/Mr. Mercedes - Stephen King.metadata.json"));
        assertThat(writer.getCoverPath(book)).isEqualTo(Path.of("/books/S/Stephen King/Mr. Mercedes - Stephen King.cover.jpg"));
        assertThat(reader.getSidecarPath(book)).isEqualTo(writer.getSidecarPath(book));
        assertThat(mapper.getCoverFileName(book)).isEqualTo("Mr. Mercedes - Stephen King.cover.jpg");
    }

    @Test
    void folderAudiobooksInOneDirectory_noLongerShareASidecar() {
        // All three used to be cut at their last dot (their only one) and write the same "01.metadata.json"
        Path series = Path.of("/audiobooks/R/Rick Riordan/The Kane Chronicles");
        Path first = series.resolve("01. The Red Pyramid - Rick Riordan (2010)");
        Path second = series.resolve("02. The Throne of Fire - Rick Riordan (2011)");
        Path third = series.resolve("01. The Serpent's Shadow - Rick Riordan (2012)");

        assertThat(writer.getSidecarPath(first)).isNotEqualTo(writer.getSidecarPath(third));
        assertThat(writer.getSidecarPath(first)).isNotEqualTo(writer.getSidecarPath(second));
        assertThat(writer.getCoverPath(first)).isNotEqualTo(writer.getCoverPath(third));
    }

    private static int utf8Bytes(String name) {
        return name.getBytes(StandardCharsets.UTF_8).length;
    }

    static Stream<Arguments> longNames() {
        return Stream.of(
                arguments("245-byte Arabic folder", "01. " + "\u0628".repeat(120) + "1"),
                arguments("245-byte CJK folder", "01. " + "\u4E2D".repeat(80) + "1"),
                arguments("245-byte folder of 4-byte characters", "0" + "\uD840\uDC00".repeat(61)),
                arguments("251-byte CJK book file", "\u4E2D".repeat(82) + ".epub")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("longNames")
    void longName_sidecarNamesFitIn255Bytes(String description, String fileName, @TempDir Path tempDir) throws IOException {
        // A zip file system holds any name, where the default one cannot under a non-UTF-8 locale
        try (FileSystem names = FileSystems.newFileSystem(tempDir.resolve("names.zip"), Map.of("create", "true"))) {
            Path book = names.getPath("/audiobooks/Author", fileName);

            String baseName = SidecarFileNames.baseName(book);
            String sidecarName = writer.getSidecarPath(book).getFileName().toString();
            String coverName = writer.getCoverPath(book).getFileName().toString();

            assertThat(utf8Bytes(fileName)).isBetween(245, 255);
            assertThat(utf8Bytes(sidecarName)).isBetween(252, 255);
            assertThat(utf8Bytes(coverName)).isLessThanOrEqualTo(255);
            assertThat(baseName).matches("(?s).+~[0-9a-f]{8}");
            assertThat(fileName).startsWith(baseName.substring(0, baseName.length() - 9));
            assertThat(baseName.codePoints()).noneMatch(codePoint -> Character.getType(codePoint) == Character.SURROGATE);
            assertThat(sidecarName).isEqualTo(baseName + ".metadata.json");
            assertThat(coverName).isEqualTo(baseName + ".cover.jpg");
            assertThat(reader.getSidecarPath(book)).isEqualTo(writer.getSidecarPath(book));
            assertThat(reader.getCoverPath(book)).isEqualTo(writer.getCoverPath(book));
            assertThat(mapper.getCoverFileName(book)).isEqualTo(coverName);
        }
    }

    @Test
    void longNamesThatDifferOnlyAtTheEnd_neverShareSidecars(@TempDir Path tempDir) throws IOException {
        try (FileSystem names = FileSystems.newFileSystem(tempDir.resolve("names.zip"), Map.of("create", "true"))) {
            // Organize numbers the second of two folders whose names were both cut to the same 245 bytes
            String folder = "01. " + "\u4E2D".repeat(80) + "1";
            Path dir = names.getPath("/audiobooks/Author");
            List<Path> books = List.of(dir.resolve(folder), dir.resolve(folder + " (1)"), dir.resolve(folder + " (2)"),
                    dir.resolve("\u4E2D".repeat(82) + "\u4E00.epub"), dir.resolve("\u4E2D".repeat(82) + "\u4E01.epub"));

            assertThat(books.stream().map(writer::getSidecarPath).distinct()).hasSize(books.size());
            assertThat(books.stream().map(writer::getCoverPath).distinct()).hasSize(books.size());
            assertThat(books).allSatisfy(book -> assertThat(utf8Bytes(writer.getSidecarPath(book).getFileName().toString())).isLessThanOrEqualTo(255));
        }
    }

    @Test
    void cutName_endsInAHashOfTheWholeName(@TempDir Path tempDir) throws IOException {
        try (FileSystem names = FileSystems.newFileSystem(tempDir.resolve("names.zip"), Map.of("create", "true"))) {
            String folder = "01. " + "\u0628".repeat(120) + "1";
            CRC32 hash = new CRC32();
            hash.update(folder.getBytes(StandardCharsets.UTF_8));

            String baseName = SidecarFileNames.baseName(names.getPath("/audiobooks/Author", folder));

            assertThat(baseName).isEqualTo(SidecarFileNames.baseName(names.getPath("/elsewhere", folder)));
            assertThat(baseName).endsWith("~" + HexFormat.of().toHexDigits((int) hash.getValue()));
        }
    }

    @Test
    void nameThatFits_isNotCut(@TempDir Path tempDir) throws IOException {
        try (FileSystem names = FileSystems.newFileSystem(tempDir.resolve("names.zip"), Map.of("create", "true"))) {
            // 241 bytes: ".metadata.json" makes 255
            String folder = "01. " + "\u0628".repeat(118) + "1";

            assertThat(utf8Bytes(folder)).isEqualTo(241);
            assertThat(SidecarFileNames.baseName(names.getPath("/audiobooks/Author", folder))).isEqualTo(folder);
        }
    }
}
