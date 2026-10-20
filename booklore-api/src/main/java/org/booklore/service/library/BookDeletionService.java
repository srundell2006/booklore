package org.booklore.service.library;

import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.model.websocket.Topic;
import org.booklore.repository.BookAdditionalFileRepository;
import org.booklore.repository.BookRepository;
import org.booklore.repository.projection.BookFileScanRow;
import org.booklore.service.NotificationService;
import org.booklore.util.FileService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
@Slf4j
public class BookDeletionService {

    private final BookRepository bookRepository;
    private final BookAdditionalFileRepository bookAdditionalFileRepository;
    private final FileService fileService;
    private final NotificationService notificationService;

    @PersistenceContext
    private final EntityManager entityManager;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void deleteRemovedAdditionalFiles(List<Long> additionalFileIds) {
        if (additionalFileIds.isEmpty()) {
            return;
        }

        // Nothing maps a cascade from book_file, so a bulk delete removes exactly what
        // loading and removing each entity did, without the per-row selects.
        bookAdditionalFileRepository.deleteAllByIdInBatch(additionalFileIds);

        log.info("Deleted {} additional files from database", additionalFileIds.size());
    }

    /**
     * Handles books whose primary file a rescan found gone. The rescan has already deleted
     * every vanished file row it could vouch for, so this only decides from what is left: a
     * book that still has a book-format file keeps it as its new primary file, the rest are
     * deleted. It never drops files itself, because a file it does not know about (one added
     * while the rescan ran, say) is not proof of anything.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void processDeletedLibraryFiles(List<Long> deletedBookIds) {
        if (deletedBookIds.isEmpty()) {
            return;
        }

        List<Long> booksToDelete = new ArrayList<>();
        for (BookEntity book : bookRepository.findAllById(deletedBookIds)) {
            if (book.getBookFiles().stream().anyMatch(BookFileEntity::isBook)) {
                log.info("Book ID {} lost its primary file but keeps another format", book.getId());
            } else {
                booksToDelete.add(book.getId());
            }
        }

        if (!booksToDelete.isEmpty()) {
            deleteRemovedBooks(booksToDelete);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void deleteRemovedBooks(List<Long> bookIds) {
        List<BookEntity> books = bookRepository.findAllById(bookIds);
        for (BookEntity book : books) {
            try {
                deleteDirectoryRecursively(Path.of(fileService.getImagesFolder(book.getId())));
                Path backupDir = Path.of(fileService.getBookMetadataBackupPath(book.getId()));
                if (Files.exists(backupDir)) {
                    deleteDirectoryRecursively(backupDir);
                }
            } catch (Exception e) {
                log.warn("Failed to clean up files for book ID {}: {}", book.getId(), e.getMessage());
            }
        }
        bookRepository.deleteAll(books);
        entityManager.flush();
        entityManager.clear();
        notificationService.sendMessage(Topic.BOOKS_REMOVE, bookIds);
        if (bookIds.size() > 1) log.info("Books removed: {}", bookIds);
    }

    /**
     * Deletes the book files of formats the library no longer allows, then the books left
     * without any book-format file of an allowed format.
     *
     * @return whether anything was deleted
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean purgeDisallowedFormats(LibraryEntity libraryEntity, LibraryBookFiles bookFiles) {
        List<BookFileType> allowedFormats = libraryEntity.getAllowedFormats();
        if (allowedFormats == null || allowedFormats.isEmpty()) {
            return false;
        }

        Set<BookFileType> allowed = EnumSet.copyOf(allowedFormats);

        List<BookFileScanRow> disallowedFiles = bookFiles.rows().stream()
                .filter(BookFileScanRow::bookFormat)
                .filter(bf -> !allowed.contains(bf.bookType()))
                .toList();

        if (disallowedFiles.isEmpty()) {
            return false;
        }

        Set<Long> affectedBookIds = disallowedFiles.stream()
                .map(BookFileScanRow::bookId)
                .collect(Collectors.toSet());

        Map<Long, Long> remainingCounts = bookFiles.rows().stream()
                .filter(BookFileScanRow::bookFormat)
                .filter(bf -> allowed.contains(bf.bookType()))
                .filter(bf -> affectedBookIds.contains(bf.bookId()))
                .collect(Collectors.groupingBy(BookFileScanRow::bookId, Collectors.counting()));

        List<Long> booksToDelete = affectedBookIds.stream()
                .filter(bookId -> remainingCounts.getOrDefault(bookId, 0L) == 0)
                .toList();

        bookAdditionalFileRepository.deleteAllByIdInBatch(disallowedFiles.stream()
                .map(BookFileScanRow::bookFileId)
                .toList());

        if (!booksToDelete.isEmpty()) {
            log.info("Deleting {} books with no remaining files of allowed formats in library: {}",
                    booksToDelete.size(), libraryEntity.getName());
            deleteRemovedBooks(booksToDelete);
        }

        log.info("Purged {} book files of disallowed formats from library: {}",
                disallowedFiles.size(), libraryEntity.getName());
        return true;
    }


    private void deleteDirectoryRecursively(Path path) throws IOException {
        if (Files.exists(path)) {
            try (Stream<Path> walk = Files.walk(path)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException e) {
                        log.warn("Failed to delete file or directory: {}", p, e);
                    }
                });
            }
        }
    }
}
