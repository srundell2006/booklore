package org.booklore.service.metadata.sidecar;

import org.booklore.config.AppProperties;
import org.booklore.service.appsettings.AppSettingService;
import org.booklore.util.FileService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
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
        // Both used to be cut at their first dot and write the same "01.metadata.json"
        Path series = Path.of("/audiobooks/R/Rick Riordan/The Kane Chronicles");
        Path first = series.resolve("01. The Red Pyramid - Rick Riordan (2010)");
        Path second = series.resolve("02. The Throne of Fire - Rick Riordan (2011)");
        Path third = series.resolve("01. The Serpent's Shadow - Rick Riordan (2012)");

        assertThat(writer.getSidecarPath(first)).isNotEqualTo(writer.getSidecarPath(third));
        assertThat(writer.getSidecarPath(first)).isNotEqualTo(writer.getSidecarPath(second));
        assertThat(writer.getCoverPath(first)).isNotEqualTo(writer.getCoverPath(third));
    }
}
