package org.booklore.service.metadata.sidecar;

import org.booklore.model.enums.BookFileExtension;
import org.booklore.util.PathPatternResolver;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

final class SidecarFileNames {

    // A file name may be 255 bytes, and ".metadata.json" is the longest suffix put after the base name
    private static final int MAX_BASE_NAME_BYTES = 255 - ".metadata.json".getBytes(StandardCharsets.UTF_8).length;

    private SidecarFileNames() {
    }

    /**
     * The name a book's sidecar files are built from: a book file's name without its
     * extension, or a folder audiobook's whole folder name. Only a real book extension is
     * cut off, so a dot inside a folder name ("01. The Farthest Shore - Ursula K. Le Guin")
     * is never mistaken for one. A name too long to take ".metadata.json" within 255 bytes
     * (folder names may be 245) is cut short at a character boundary.
     */
    static String baseName(Path bookPath) {
        String fileName = bookPath.getFileName().toString();
        String baseName = BookFileExtension.fromFileName(fileName)
                .map(extension -> fileName.substring(0, fileName.length() - extension.getExtension().length() - 1))
                .filter(name -> !name.isEmpty())
                .orElse(fileName);
        return PathPatternResolver.truncatePathComponent(baseName, MAX_BASE_NAME_BYTES);
    }
}
