package org.booklore.service.fileprocessor.prepare;

import org.booklore.model.dto.settings.LibraryFile;
import org.booklore.model.enums.BookFileType;
import org.booklore.util.cover.CoverKind;
import org.booklore.util.cover.CropPolicy;

import java.nio.file.Path;

/**
 * What an additional format of a new book is prepared from, as plain values.
 *
 * @param path           the file, or the folder of a folder audiobook
 * @param crossTypeCover the kind of cover to make from this file for a book of the other kind (an audiobook cover
 *                       from an audiobook added to an ebook, or the reverse), or null for none
 */
public record AttachmentRequest(Path path, String fileName, BookFileType type, boolean folderBased,
                                CoverKind crossTypeCover, CropPolicy crop) {

    public static AttachmentRequest of(LibraryFile libraryFile, CoverKind crossTypeCover, CropPolicy crop) {
        return new AttachmentRequest(libraryFile.getFullPath(), libraryFile.getFileName(), libraryFile.getBookFileType(),
                libraryFile.isFolderBased(), crossTypeCover, crop);
    }
}
