package org.booklore.service.library;

import org.booklore.mapper.BookMapper;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.websocket.Topic;
import org.booklore.repository.BookRepository;
import org.booklore.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class BookRestorationService {

    private final BookRepository bookRepository;
    private final BookMapper bookMapper;
    private final NotificationService notificationService;

    /**
     * Un-deletes the soft-deleted books of the library whose primary file is back on disk.
     * Only books that were already soft-deleted, with that primary file, when the walk started
     * count: the walk may have listed a file just before it was removed and its book
     * soft-deleted.
     *
     * @param currentPaths full paths of every file the scan found
     * @return whether it restored any book, so that the caller's rows, which still have it deleted, are stale
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean restoreDeletedBooks(LibraryEntity libraryEntity, LibraryBookFiles bookFiles, Set<Path> currentPaths,
                                       WalkCoverage coverage) {
        if (currentPaths.isEmpty()) return false;

        List<Long> restoreIds = bookFiles.primaryFiles(libraryEntity.getFormatPriority()).entrySet().stream()
                .filter(entry -> entry.getValue().isBookDeleted())
                .filter(entry -> isOnDisk(entry.getValue().fullPath(), currentPaths))
                .filter(entry -> !coverage.changedDuringWalk(entry.getValue()))
                .map(Map.Entry::getKey)
                .toList();

        if (restoreIds.isEmpty()) return false;

        List<BookEntity> toRestore = bookRepository.findAllById(restoreIds);
        toRestore.forEach(book -> {
            book.setDeleted(false);
            book.setDeletedAt(null);
            book.setAddedOn(Instant.now());
            notificationService.sendMessage(Topic.BOOK_ADD, bookMapper.toBookWithDescription(book, false));
        });
        bookRepository.saveAll(toRestore);

        log.info("Restored {} books in library: {}", toRestore.size(), libraryEntity.getName());
        return !toRestore.isEmpty();
    }

    private static boolean isOnDisk(Path fullPath, Set<Path> currentPaths) {
        return fullPath != null && currentPaths.contains(fullPath);
    }
}
