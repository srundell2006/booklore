package org.booklore.service.fileprocessor.prepare;

import org.booklore.util.FileUtils;

import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * What a folder audiobook's folder holds, from one pass over it: what FileFingerprint.generateFolderHash,
 * FileUtils.getFolderSizeInKb, FileUtils.getFirstAudioFileInFolder and FileUtils.findCoverImageInFolder each listed
 * the folder for.
 *
 * @param totalBytes        the size of every regular file in the folder and below; links to files count with their
 *                          target's size, links to directories are not followed
 * @param audioFiles        the direct children that are audio files (regular files, links followed), sorted by name
 * @param regularChildNames the names of the direct children that are regular files, links followed
 */
public record FolderSnapshot(Path folder, long totalBytes, List<Path> audioFiles, Set<String> regularChildNames) {

    public FolderSnapshot {
        audioFiles = List.copyOf(audioFiles);
        regularChildNames = Set.copyOf(regularChildNames);
    }

    /**
     * Walks the folder. Its own entries are read through the folder even when the folder is a link, as
     * generateFolderHash lists it, and the walk below does not follow links, as getFolderSizeInKb walks it.
     *
     * @throws RuntimeException "Folder does not exist: ..." when the folder is missing or not a directory, as
     *                          generateFolderHash throws
     * @throws IOException      when an entry or directory in the folder cannot be read
     */
    public static FolderSnapshot scan(Path folder) throws IOException {
        boolean directory;
        try {
            directory = Files.readAttributes(folder, BasicFileAttributes.class).isDirectory();
        } catch (IOException e) {
            directory = false;
        }
        if (!directory) {
            throw new RuntimeException("Folder does not exist: " + folder);
        }

        SizeCounter sizes = new SizeCounter();
        List<Path> audioFiles = new ArrayList<>();
        Set<String> regularChildNames = new HashSet<>();
        try (DirectoryStream<Path> children = Files.newDirectoryStream(folder)) {
            for (Path child : children) {
                BasicFileAttributes attrs = Files.readAttributes(child, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attrs.isDirectory()) {
                    Files.walkFileTree(child, sizes);
                    continue;
                }
                if (attrs.isSymbolicLink()) {
                    attrs = targetAttributes(child);
                }
                if (attrs != null && attrs.isRegularFile()) {
                    sizes.totalBytes += attrs.size();
                    String name = child.getFileName().toString();
                    regularChildNames.add(name);
                    if (FileUtils.isAudioFile(name)) {
                        audioFiles.add(child);
                    }
                }
            }
        } catch (DirectoryIteratorException e) {
            throw e.getCause();
        }
        audioFiles.sort(Comparator.comparing(p -> p.getFileName().toString()));
        return new FolderSnapshot(folder, sizes.totalBytes, audioFiles, regularChildNames);
    }

    /** A link's target's attributes, or null when the target cannot be read, which then counts as no file. */
    private static BasicFileAttributes targetAttributes(Path link) {
        try {
            return Files.readAttributes(link, BasicFileAttributes.class);
        } catch (IOException e) {
            return null;
        }
    }

    private static final class SizeCounter extends SimpleFileVisitor<Path> {
        private long totalBytes;

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            if (attrs.isRegularFile()) {
                totalBytes += attrs.size();
            } else if (attrs.isSymbolicLink()) {
                BasicFileAttributes target = targetAttributes(file);
                if (target != null && target.isRegularFile()) {
                    totalBytes += target.size();
                }
            }
            return FileVisitResult.CONTINUE;
        }
    }
}
