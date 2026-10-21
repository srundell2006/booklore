package org.booklore.service.fileprocessor.prepare;

import org.booklore.service.file.FileFingerprint;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * The hash and size a new book file is stored with.
 *
 * @param sizeKb null when the size could not be read
 */
public record FileFacts(String hash, Long sizeKb) {

    /**
     * The facts of one file: its size as FileUtils.getFileSizeInKb reads it and its hash as FileFingerprint.generateHash
     * computes it, which throws when the file cannot be read.
     */
    public static FileFacts read(Path file) {
        Long sizeKb;
        try {
            sizeKb = Files.readAttributes(file, BasicFileAttributes.class).size() / 1024;
        } catch (IOException e) {
            sizeKb = null;
        }
        return new FileFacts(FileFingerprint.generateHash(file), sizeKb);
    }

    /**
     * The facts of a folder audiobook, equal to FileFingerprint.generateFolderHash and FileUtils.getFolderSizeInKb.
     *
     * @throws RuntimeException "No audio files in folder: ..." as generateFolderHash throws
     */
    public static FileFacts of(FolderSnapshot snapshot) {
        if (snapshot.audioFiles().isEmpty()) {
            throw new RuntimeException("No audio files in folder: " + snapshot.folder());
        }
        String firstFileHash = FileFingerprint.generateHash(snapshot.audioFiles().getFirst());
        return new FileFacts(FileFingerprint.folderHash(firstFileHash, snapshot.audioFiles().size()), snapshot.totalBytes() / 1024);
    }
}
