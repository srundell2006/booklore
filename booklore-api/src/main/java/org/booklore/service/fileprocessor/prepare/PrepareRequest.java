package org.booklore.service.fileprocessor.prepare;

import org.booklore.model.dto.settings.LibraryFile;
import org.booklore.model.enums.BookFileType;
import org.booklore.model.enums.LibraryOrganizationMode;
import org.booklore.util.cover.CropPolicy;

import java.nio.file.Path;

/**
 * What a new book's primary file is prepared from, as plain values so the preparation needs no entity or session.
 *
 * @param path                the file, or the folder of a folder audiobook
 * @param coverFallbackFolder the folder searched for a cover image when the file has none, or null for none
 * @param crop                the cover cropping settings to render the cover with
 */
public record PrepareRequest(Path path, String fileName, BookFileType type, boolean folderBased,
                             Path coverFallbackFolder, CropPolicy crop) {

    public static PrepareRequest of(LibraryFile libraryFile, CropPolicy crop) {
        return new PrepareRequest(libraryFile.getFullPath(), libraryFile.getFileName(), libraryFile.getBookFileType(),
                libraryFile.isFolderBased(), coverFallbackFolder(libraryFile), crop);
    }

    /**
     * The folder searched for a cover image when the book's own file has none: a folder audiobook's own folder, or
     * the file's folder in a library with one folder per book.
     *
     * @return null when the book has no such folder
     */
    public static Path coverFallbackFolder(LibraryFile libraryFile) {
        if (libraryFile.isFolderBased()) {
            return libraryFile.getFullPath();
        }
        if (libraryFile.getLibraryEntity() != null
                && libraryFile.getLibraryEntity().getOrganizationMode() == LibraryOrganizationMode.BOOK_PER_FOLDER) {
            return libraryFile.getFullPath().getParent();
        }
        return null;
    }
}
