package org.booklore.service.metadata.sidecar;

import org.booklore.model.enums.BookFileExtension;

import java.nio.file.Path;

final class SidecarFileNames {

    private SidecarFileNames() {
    }

    /**
     * The name a book's sidecar files are built from: a book file's name without its
     * extension, or a folder audiobook's whole folder name. Only a real book extension is
     * cut off, so a dot inside a folder name ("01. The Farthest Shore - Ursula K. Le Guin")
     * is never mistaken for one.
     */
    static String baseName(Path bookPath) {
        String fileName = bookPath.getFileName().toString();
        return BookFileExtension.fromFileName(fileName)
                .map(extension -> fileName.substring(0, fileName.length() - extension.getExtension().length() - 1))
                .filter(baseName -> !baseName.isEmpty())
                .orElse(fileName);
    }
}
