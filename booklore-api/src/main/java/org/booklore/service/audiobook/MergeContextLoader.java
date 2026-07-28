package org.booklore.service.audiobook;

import lombok.RequiredArgsConstructor;
import org.booklore.exception.ApiError;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.repository.BookRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;

/**
 * Separate bean so @Transactional actually applies (a self-invoked method on
 * AudiobookMergeService would bypass the proxy).
 */
@Service
@RequiredArgsConstructor
public class MergeContextLoader {

    private final BookRepository bookRepository;

    @Transactional(readOnly = true)
    public MergeContext load(long bookId) {
        BookEntity book = bookRepository.findById(bookId)
                .orElseThrow(() -> ApiError.BOOK_NOT_FOUND.createException(bookId));

        // Inside the transaction, so these lazy associations resolve fine.
        BookFileEntity primaryFile = book.getPrimaryBookFile();
        if (primaryFile == null) {
            throw ApiError.GENERIC_BAD_REQUEST.createException("Book has no file to merge");
        }
        Path sourcePath = book.getFullFilePath();
        if (sourcePath == null) {
            throw ApiError.GENERIC_BAD_REQUEST.createException("Book has no resolvable file path");
        }

        BookMetadataEntity metadata = book.getMetadata();
        return MergeContext.builder()
                .bookId(bookId)
                .libraryId(book.getLibrary() != null ? book.getLibrary().getId() : null)
                .sourcePath(sourcePath.toString())
                .folderBased(primaryFile.isFolderBased())
                .title(metadata != null ? metadata.getTitle() : null)
                .seriesName(metadata != null ? metadata.getSeriesName() : null)
                .seriesNumber(metadata != null ? metadata.getSeriesNumber() : null)
                .publisher(metadata != null ? metadata.getPublisher() : null)
                .publishedYear(metadata != null && metadata.getPublishedDate() != null
                        ? metadata.getPublishedDate().getYear() : null)
                .description(metadata != null ? metadata.getDescription() : null)
                .build();
    }
}
