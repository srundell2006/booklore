package org.booklore.model;

import lombok.*;
import org.booklore.model.dto.Book;
import org.booklore.model.enums.FileProcessStatus;

@Builder
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class FileProcessResult {
    private Book book;
    private FileProcessStatus status;
    /** Whether processing the file created the book; false when the file went to a book that was there already or was restored. */
    private boolean bookCreated;

    public FileProcessResult(Book book, FileProcessStatus status) {
        this(book, status, false);
    }
}
