package org.booklore.service.watcher;

import org.booklore.exception.ApiError;
import org.booklore.mapper.BookMapper;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.entity.LibraryPathEntity;
import org.booklore.model.enums.PermissionType;
import org.booklore.model.websocket.Topic;
import org.booklore.repository.BookFileRepository;
import org.booklore.repository.BookRepository;
import org.booklore.service.NotificationService;
import org.booklore.util.FileUtils;
import jakarta.persistence.EntityManager;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;

import static org.booklore.model.enums.PermissionType.ADMIN;
import static org.booklore.model.enums.PermissionType.MANAGE_LIBRARY;

@Slf4j
@Service
@AllArgsConstructor
public class BookFilePersistenceService {

    private final EntityManager entityManager;
    private final BookRepository bookRepository;
    private final BookFileRepository bookFileRepository;
    private final NotificationService notificationService;
    private final BookMapper bookMapper;

    /**
     * The stored file that a new file at {@code path} with this hash was moved from, if it was moved and not copied.
     * That is the one book format of this hash, of the one book with a book format of it that is not deleted or was
     * deleted after the cutoff, which is no longer at its old path, or whose book was deleted after the cutoff (its
     * file went first, then came back). A copy leaves its original where it was, and files of one content in two
     * books do not say which of them moved: the new file is then a file of its own, matched as any other new file.
     * Rebinding the original to a copy instead moved the book off a file that is still there, and as the copy and the
     * original both had the hash, the lookup threw for two books.
     */
    @Transactional(readOnly = true)
    public Optional<BookFileEntity> findMovedFile(String hash, Path path, Instant cutoff) {
        List<BookFileEntity> withHash = bookRepository.findBookFormatsByCurrentHashIncludingRecentlyDeleted(hash, cutoff);
        if (withHash.isEmpty()) {
            return Optional.empty();
        }
        Set<Long> books = new TreeSet<>();
        withHash.forEach(file -> books.add(file.getBook().getId()));
        if (books.size() > 1) {
            log.info("[FILE_CREATE] '{}' has the content of files of books {}; taken for a new file, not a move", path, books);
            return Optional.empty();
        }
        List<BookFileEntity> moved = withHash.stream()
                .filter(file -> Boolean.TRUE.equals(file.getBook().getDeleted()) || isGone(file))
                .toList();
        if (moved.isEmpty()) {
            log.info("[FILE_CREATE] '{}' has the content of a file of book id={} that is still where it was; taken for a copy",
                    path, books.iterator().next());
            return Optional.empty();
        }
        if (moved.size() > 1) {
            log.info("[FILE_CREATE] '{}' has the content of {} files of book id={} that are gone; taken for a new file",
                    path, moved.size(), books.iterator().next());
            return Optional.empty();
        }
        return Optional.of(moved.getFirst());
    }

    /** Whether a stored file is no longer at its path. One whose path cannot be told is taken as still there. */
    private static boolean isGone(BookFileEntity file) {
        try {
            return !Files.exists(file.getFullFilePath());
        } catch (IllegalStateException e) {
            return false;
        }
    }

    /**
     * Moves a stored file, found by {@link #findMovedFile}, to the path a file with its content appeared at, and
     * undeletes its book.
     */
    @Transactional
    public void updatePathIfChanged(BookFileEntity movedFile, LibraryEntity libraryEntity, Path path) {
        BookFileEntity matchedFile = bookFileRepository.findById(movedFile.getId())
                .orElseThrow(() -> new IllegalStateException("BookFile not found: " + movedFile.getId()));
        BookEntity book = matchedFile.getBook();
        LibraryPathEntity newLibraryPath = getLibraryPathEntityForFile(libraryEntity, path.toString());
        newLibraryPath = entityManager.merge(newLibraryPath);

        String newSubPath = FileUtils.getRelativeSubPath(newLibraryPath.getPath(), path);
        String newFileName = path.getFileName().toString();

        boolean changed = !Objects.equals(newSubPath, matchedFile.getFileSubPath())
                || !Objects.equals(newFileName, matchedFile.getFileName())
                || !Objects.equals(newLibraryPath.getId(), book.getLibraryPath().getId())
                || Boolean.TRUE.equals(book.getDeleted());

        if (changed) {
            book.setLibraryPath(newLibraryPath);
            matchedFile.setFileSubPath(newSubPath);
            matchedFile.setFileName(newFileName);
            book.setDeleted(Boolean.FALSE);
            book.setDeletedAt(null);
            bookRepository.save(book);
            log.info("[FILE_CREATE] Updated path / undeleted existing book with hash '{}': '{}'", matchedFile.getCurrentHash(), path);
        } else {
            log.info("[FILE_CREATE] Book with hash '{}' already exists at same path. Skipping update.", matchedFile.getCurrentHash());
        }
        notificationService.sendMessageToPermissions(Topic.BOOK_ADD, bookMapper.toBookWithDescription(book, false), Set.of(ADMIN, MANAGE_LIBRARY));
    }

    String findMatchingLibraryPath(LibraryEntity libraryEntity, Path filePath) {
        return libraryEntity.getLibraryPaths().stream()
                .map(lp -> Paths.get(lp.getPath()).toAbsolutePath().normalize())
                .filter(base -> filePath.toAbsolutePath().normalize().startsWith(base))
                .map(Path::toString)
                .findFirst()
                .orElseThrow(() -> ApiError.LIBRARY_NOT_FOUND.createException("No matching libraryPath for: " + filePath));
    }

    LibraryPathEntity getLibraryPathEntityForFile(LibraryEntity libraryEntity, String inputPath) {
        Path fullPath = Paths.get(inputPath).toAbsolutePath().normalize();
        return libraryEntity.getLibraryPaths().stream()
                .map(lp -> Map.entry(lp, Paths.get(lp.getPath()).toAbsolutePath().normalize()))
                .filter(entry -> fullPath.startsWith(entry.getValue()))
                .max(Comparator.comparingInt(entry -> entry.getValue().getNameCount()))
                .map(Map.Entry::getKey)
                .orElseThrow(() -> ApiError.BOOK_NOT_FOUND.createException(inputPath));
    }

    @Transactional
    public int markAllBooksUnderPathAsDeleted(long libraryPathId, String relativeFolderPath) {
        if (relativeFolderPath == null) {
            throw new IllegalArgumentException("relativeFolderPath cannot be null");
        }
        List<BookEntity> books = bookRepository.findAllByLibraryPathIdAndFileSubPathStartingWith(libraryPathId, relativeFolderPath);
        books.forEach(book -> {
            book.setDeleted(true);
            book.setDeletedAt(Instant.now());
        });

        bookRepository.saveAll(books);
        return books.size();
    }

    /**
     * The file stored at exactly this path. The database also returns files whose path only compares equal to it in its
     * collation, ignoring case and possibly accents; those are other files, and an event for this path is not about them.
     */
    @Transactional(readOnly = true)
    public Optional<BookFileEntity> findBookFileByLibraryPathSubPathAndFileName(long libraryPathId, String fileSubPath, String fileName) {
        return bookFileRepository.findAllByLibraryPathIdAndFileSubPathAndFileName(libraryPathId, fileSubPath, fileName).stream()
                .filter(file -> file.isAt(fileSubPath, fileName))
                .findFirst();
    }

    @Transactional
    public void deleteBookFile(BookFileEntity bookFile) {
        bookFileRepository.delete(bookFile);
    }

    @Transactional
    public void markBookAsDeleted(BookEntity book) {
        book.setDeleted(true);
        book.setDeletedAt(Instant.now());
        bookRepository.save(book);
    }

    @Transactional
    public void save(BookEntity book) {
        bookRepository.save(book);
    }

    @Transactional(readOnly = true)
    public long countBookFilesByBookId(Long bookId) {
        return bookFileRepository.countByBookId(bookId);
    }

    @Transactional
    public void recoverFolderBook(PendingDeletionPool.BookSnapshot bookSnap, LibraryPathEntity newLibraryPath,
                                   Path folderPath, Map<Path, String> fileHashes) {
        BookEntity book = bookRepository.findById(bookSnap.bookId()).orElse(null);
        if (book == null) return;

        book.setLibraryPath(entityManager.merge(newLibraryPath));
        book.setDeleted(false);
        book.setDeletedAt(null);

        for (PendingDeletionPool.FileSnapshot fs : bookSnap.files()) {
            bookFileRepository.findById(fs.bookFileId()).ifPresent(bf -> {
                for (var entry : fileHashes.entrySet()) {
                    if (entry.getValue().equals(fs.currentHash())) {
                        Path filePath = entry.getKey();
                        String newSubPath = FileUtils.getRelativeSubPath(newLibraryPath.getPath(), filePath);
                        bf.setFileSubPath(newSubPath);
                        bf.setFileName(filePath.getFileName().toString());
                        bf.setCurrentHash(entry.getValue());
                        break;
                    }
                }
            });
        }

        bookRepository.save(book);
        notificationService.sendMessageToPermissions(Topic.BOOK_ADD,
                bookMapper.toBookWithDescription(book, false), Set.of(PermissionType.ADMIN, PermissionType.MANAGE_LIBRARY));
        log.info("[RECOVERED] Book id={} recovered from folder move", book.getId());
    }
}