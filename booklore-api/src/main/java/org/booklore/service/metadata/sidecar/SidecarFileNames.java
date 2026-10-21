package org.booklore.service.metadata.sidecar;

import org.booklore.model.enums.BookFileExtension;
import org.booklore.util.PathPatternResolver;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.zip.CRC32;

final class SidecarFileNames {

    // A file name may be 255 bytes, and ".metadata.json" is the longest suffix put after the base name
    private static final int MAX_BASE_NAME_BYTES = 255 - ".metadata.json".getBytes(StandardCharsets.UTF_8).length;
    // "~" and 8 hex digits
    private static final int HASH_SUFFIX_BYTES = 9;

    private SidecarFileNames() {
    }

    /**
     * The name a book's sidecar files are built from: a book file's name without its
     * extension, or a folder audiobook's whole folder name. Only a real book extension is
     * cut off, so a dot inside a folder name ("01. The Farthest Shore - Ursula K. Le Guin")
     * is never mistaken for one. A name too long to take ".metadata.json" within 255 bytes
     * (folder names may be 245) is cut short at a character boundary and ends in "~" and a
     * hash of the whole name instead: long names often differ only at the end ("... (1)"),
     * and two books must never share sidecar files.
     */
    static String baseName(Path bookPath) {
        String fileName = bookPath.getFileName().toString();
        String baseName = BookFileExtension.fromFileName(fileName)
                .map(extension -> fileName.substring(0, fileName.length() - extension.getExtension().length() - 1))
                .filter(name -> !name.isEmpty())
                .orElse(fileName);
        byte[] bytes = baseName.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= MAX_BASE_NAME_BYTES) {
            return baseName;
        }
        CRC32 hash = new CRC32();
        hash.update(bytes);
        return PathPatternResolver.truncatePathComponent(baseName, MAX_BASE_NAME_BYTES - HASH_SUFFIX_BYTES)
                + "~" + HexFormat.of().toHexDigits((int) hash.getValue());
    }
}
