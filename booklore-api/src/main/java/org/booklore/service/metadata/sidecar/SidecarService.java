package org.booklore.service.metadata.sidecar;

import jakarta.persistence.EntityManager;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.exception.ApiError;
import org.booklore.model.MetadataUpdateContext;
import org.booklore.model.MetadataUpdateWrapper;
import org.booklore.model.dto.BookMetadata;
import org.booklore.model.dto.sidecar.SidecarMetadata;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.enums.MetadataReplaceMode;
import org.booklore.model.enums.SidecarSyncStatus;
import org.booklore.repository.BookRepository;
import org.booklore.repository.LibraryRepository;
import org.booklore.service.metadata.BookMetadataUpdater;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Slf4j
@Service
@AllArgsConstructor
public class SidecarService {

    private final BookRepository bookRepository;
    private final LibraryRepository libraryRepository;
    private final SidecarMetadataReader sidecarReader;
    private final SidecarMetadataWriter sidecarWriter;
    private final SidecarMetadataMapper sidecarMapper;
    private final BookMetadataUpdater bookMetadataUpdater;
    private final PlatformTransactionManager transactionManager;
    private final EntityManager entityManager;

    public Optional<SidecarMetadata> getSidecarContent(Long bookId) {
        BookEntity book = bookRepository.findByIdWithBookFiles(bookId)
                .orElseThrow(() -> ApiError.BOOK_NOT_FOUND.createException(bookId));

        Path bookPath = book.getFullFilePath();
        if (bookPath == null) {
            return Optional.empty();
        }

        return sidecarReader.readSidecarMetadata(bookPath);
    }

    public SidecarSyncStatus getSyncStatus(Long bookId) {
        BookEntity book = bookRepository.findByIdWithBookFiles(bookId)
                .orElseThrow(() -> ApiError.BOOK_NOT_FOUND.createException(bookId));

        return sidecarReader.getSyncStatus(book);
    }

    @Transactional
    public void exportToSidecar(Long bookId) {
        BookEntity book = bookRepository.findByIdWithBookFiles(bookId)
                .orElseThrow(() -> ApiError.BOOK_NOT_FOUND.createException(bookId));

        sidecarWriter.writeSidecarMetadata(book);
    }

    @Transactional
    public void importFromSidecar(Long bookId) {
        BookEntity book = bookRepository.findByIdWithBookFiles(bookId)
                .orElseThrow(() -> ApiError.BOOK_NOT_FOUND.createException(bookId));

        Path bookPath = book.getFullFilePath();
        if (bookPath == null) {
            throw ApiError.FILE_NOT_FOUND.createException("Book has no file path");
        }

        Optional<SidecarMetadata> sidecarOpt = sidecarReader.readSidecarMetadata(bookPath);
        if (sidecarOpt.isEmpty()) {
            throw ApiError.FILE_NOT_FOUND.createException("No sidecar file found for book");
        }

        SidecarMetadata sidecar = sidecarOpt.get();
        BookMetadata bookMetadata = sidecarMapper.toBookMetadata(sidecar);

        if (bookMetadata != null) {
            MetadataUpdateWrapper wrapper = MetadataUpdateWrapper.builder()
                    .metadata(bookMetadata)
                    .build();

            MetadataUpdateContext context = MetadataUpdateContext.builder()
                    .bookEntity(book)
                    .metadataUpdateWrapper(wrapper)
                    .updateThumbnail(false)
                    .replaceMode(MetadataReplaceMode.REPLACE_WHEN_PROVIDED)
                    .build();

            bookMetadataUpdater.setBookMetadata(context);
        }

        byte[] coverBytes = sidecarReader.readSidecarCover(bookPath);
        if (coverBytes != null) {
            log.info("Sidecar cover found for book ID {} - cover import is a separate operation", bookId);
        }
    }

    @Transactional
    public int bulkExport(Long libraryId) {
        LibraryEntity library = libraryRepository.findById(libraryId)
                .orElseThrow(() -> ApiError.LIBRARY_NOT_FOUND.createException(libraryId));

        List<BookEntity> books = bookRepository.findAllByLibraryIdWithFiles(libraryId);
        int exported = 0;

        for (BookEntity book : books) {
            try {
                sidecarWriter.writeSidecarMetadata(book);
                exported++;
            } catch (Exception e) {
                log.warn("Failed to export sidecar for book ID {}: {}", book.getId(), e.getMessage());
            }
        }

        log.info("Bulk exported {} sidecar files for library {}", exported, library.getName());
        return exported;
    }

    /**
     * Imports the sidecar of each book of the library, each book in a transaction of its own. With
     * "move files to library pattern" on, saving a book's metadata can move its files, and their
     * new paths must then be committed right after the move: an import over a large library that
     * stops partway (a restart) must never leave files moved while their rows still name the old
     * paths, or the next rescan would delete those books and import their files as new ones.
     */
    public int bulkImport(Long libraryId) {
        LibraryEntity library = libraryRepository.findById(libraryId)
                .orElseThrow(() -> ApiError.LIBRARY_NOT_FOUND.createException(libraryId));

        Set<Long> bookIds = bookRepository.findBookIdsByLibraryId(libraryId);
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        int imported = 0;

        for (Long bookId : bookIds) {
            try {
                if (Boolean.TRUE.equals(transactionTemplate.execute(status -> importSidecar(bookId)))) {
                    imported++;
                }
            } catch (Exception e) {
                log.warn("Failed to import sidecar for book ID {}: {}", bookId, e.getMessage());
            } finally {
                forgetBooksRead();
            }
        }

        log.info("Bulk imported {} sidecar files for library {}", imported, library.getName());
        return imported;
    }

    /**
     * Run from a request, each book's transaction uses the request's EntityManager (open in view),
     * which keeps every book read so far until the request ends: each later book's flush would
     * dirty-check all of them again, and an import over a large library would take days of CPU
     * even with no sidecar to import. So it is cleared once each book's transaction is over.
     * Within a transaction of the caller's, each book's transaction has an EntityManager of its
     * own, and the caller's is left alone.
     */
    private void forgetBooksRead() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            entityManager.clear();
        }
    }

    private boolean importSidecar(Long bookId) {
        BookEntity book = bookRepository.findByIdWithBookFiles(bookId).orElse(null);
        if (book == null) {
            return false;
        }
        Path bookPath = book.getFullFilePath();
        if (bookPath == null) {
            return false;
        }

        Optional<SidecarMetadata> sidecarOpt = sidecarReader.readSidecarMetadata(bookPath);
        if (sidecarOpt.isEmpty()) {
            return false;
        }

        SidecarMetadata sidecar = sidecarOpt.get();
        BookMetadata bookMetadata = sidecarMapper.toBookMetadata(sidecar);
        if (bookMetadata == null) {
            return false;
        }

        MetadataUpdateWrapper wrapper = MetadataUpdateWrapper.builder()
                .metadata(bookMetadata)
                .build();

        MetadataUpdateContext context = MetadataUpdateContext.builder()
                .bookEntity(book)
                .metadataUpdateWrapper(wrapper)
                .updateThumbnail(false)
                .replaceMode(MetadataReplaceMode.REPLACE_WHEN_PROVIDED)
                .build();

        bookMetadataUpdater.setBookMetadata(context);
        return true;
    }
}
