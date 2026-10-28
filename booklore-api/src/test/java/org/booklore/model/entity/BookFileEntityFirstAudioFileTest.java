package org.booklore.model.entity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class BookFileEntityFirstAudioFileTest {

    @TempDir
    Path libraryRoot;

    @Test
    void folderBased_returnsFirstAudioFileByName() throws IOException {
        Path folder = Files.createDirectories(libraryRoot.resolve("King/The Stand"));
        Files.writeString(folder.resolve("cover.jpg"), "");
        Files.writeString(folder.resolve("02.mp3"), "");
        Files.writeString(folder.resolve("01.m4b"), "");
        Files.createDirectories(folder.resolve("00.mp3"));

        assertThat(folderBasedFile().getFirstAudioFile()).isEqualTo(folder.resolve("01.m4b"));
    }

    @Test
    void folderBased_withoutAudio_returnsTheFolder() throws IOException {
        Path folder = Files.createDirectories(libraryRoot.resolve("King/The Stand"));
        Files.writeString(folder.resolve("notes.txt"), "");

        assertThat(folderBasedFile().getFirstAudioFile()).isEqualTo(folder);
    }

    @Test
    void folderBased_missingFolder_returnsTheFolder() {
        assertThat(folderBasedFile().getFirstAudioFile()).isEqualTo(libraryRoot.resolve("King/The Stand"));
    }

    private BookFileEntity folderBasedFile() {
        LibraryPathEntity libraryPath = LibraryPathEntity.builder().path(libraryRoot.toString()).build();
        BookEntity book = BookEntity.builder().libraryPath(libraryPath).build();
        BookFileEntity file = new BookFileEntity();
        file.setBook(book);
        file.setFileSubPath("King");
        file.setFileName("The Stand");
        file.setFolderBased(true);
        return file;
    }
}
