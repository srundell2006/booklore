package org.booklore.mapper.v2;

import org.booklore.model.enums.BookFileType;

import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Picks the file the list view shows as a book's primary file. {@link BookMapperV2} maps it that way, and the
 * magic-shelf counts check fileType rules against the same choice, from file rows they load without the entities.
 */
public final class PrimaryBookFile {

    private PrimaryBookFile() {
    }

    /**
     * The first book format, in the given order, whose type comes first in the library's format priority; without a
     * match, the first book format. Supplementary files never count, so a book with none but those has no primary file.
     *
     * @param files the book's files in id order, as {@code BookEntity.bookFiles} holds them
     */
    public static <F> F select(List<F> files, List<BookFileType> formatPriority,
                               Predicate<F> isBookFormat, Function<F, BookFileType> bookType) {
        if (files == null || files.isEmpty()) return null;

        List<F> bookFormats = files.stream().filter(isBookFormat).toList();
        if (bookFormats.isEmpty()) return null;

        if (formatPriority != null) {
            for (BookFileType format : formatPriority) {
                for (F file : bookFormats) {
                    if (bookType.apply(file) == format) {
                        return file;
                    }
                }
            }
        }
        return bookFormats.getFirst();
    }
}
