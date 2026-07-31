package org.booklore.service.audiobook;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.exception.ApiError;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.repository.BookRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.util.stream.Collectors;

/**
 * Separate bean so @Transactional actually applies when called from
 * AudiobookVerificationService (a self-invoked method would bypass the proxy).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VerificationContextLoader {

    private final BookRepository bookRepository;

    @Transactional(readOnly = true)
    public VerificationContext load(long bookId) {
        BookEntity book = bookRepository.findById(bookId)
                .orElseThrow(() -> ApiError.BOOK_NOT_FOUND.createException(bookId));

        BookFileEntity primaryFile = book.getPrimaryBookFile();
        if (primaryFile == null || primaryFile.getBookType() != BookFileType.AUDIOBOOK) {
            log.debug("Book {} is not an audiobook or has no primary file", bookId);
            return null;
        }

        Path audioPath;
        try {
            audioPath = primaryFile.getFirstAudioFile();
        } catch (Exception e) {
            log.debug("Book {}: could not resolve audio file path — {}", bookId, e.getMessage());
            return null;
        }

        if (audioPath == null) {
            log.debug("Book {}: getFirstAudioFile() returned null", bookId);
            return null;
        }

        BookMetadataEntity metadata = book.getMetadata();
        if (metadata == null) {
            log.debug("Book {} has no metadata", bookId);
            return null;
        }

        String title = metadata.getTitle();
        String authors = (metadata.getAuthors() == null) ? null :
                metadata.getAuthors().stream()
                        .map(a -> a.getName())
                        .filter(n -> n != null && !n.isBlank())
                        .collect(Collectors.joining(", "));

        return new VerificationContext(bookId, audioPath.toString(), title, authors);
    }

    public record VerificationContext(long bookId, String filePath, String title, String authors) {
    }
}
