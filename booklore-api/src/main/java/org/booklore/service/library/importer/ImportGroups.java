package org.booklore.service.library.importer;

import org.booklore.model.dto.settings.LibraryFile;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.util.cover.CoverKind;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Rules for a group of new files that become one book, shared by the sequential import and the parallel one. */
public final class ImportGroups {

    private ImportGroups() {
    }

    /**
     * The file a new book is created from: the first by the library's format priority (formats it does not list come
     * last), or by file type order when it has none, then by file name. Files of unknown type never are.
     */
    public static Optional<LibraryFile> findBestPrimaryFile(List<LibraryFile> group, LibraryEntity libraryEntity) {
        List<BookFileType> formatPriority = libraryEntity.getFormatPriority();
        return group.stream()
                .filter(f -> f.getBookFileType() != null)
                .min(Comparator.<LibraryFile, Integer>comparing(f -> {
                    BookFileType bookFileType = f.getBookFileType();
                    if (formatPriority != null && !formatPriority.isEmpty()) {
                        int index = formatPriority.indexOf(bookFileType);
                        return index >= 0 ? index : Integer.MAX_VALUE;
                    }
                    return bookFileType.ordinal();
                }).thenComparing(LibraryFile::getFileName));
    }

    /**
     * The cover an additional format makes for its book, as FileAsBookProcessor.generateCoverFromAdditionalFile decides
     * for a book with files: only when an audiobook meets an ebook, and then of the additional format's kind.
     *
     * @return null when the additional format makes no cover
     */
    static CoverKind crossTypeCover(BookFileType primaryType, BookFileType additionalType) {
        boolean primaryIsAudiobook = primaryType == BookFileType.AUDIOBOOK;
        boolean additionalIsAudiobook = additionalType == BookFileType.AUDIOBOOK;
        if (primaryIsAudiobook == additionalIsAudiobook) {
            return null;
        }
        return additionalIsAudiobook ? CoverKind.AUDIOBOOK : CoverKind.BOOK;
    }
}
