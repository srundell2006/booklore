package org.booklore.service.watcher;

import org.booklore.exception.ApiError;
import org.booklore.model.dto.settings.LibraryFile;
import org.booklore.model.entity.AuthorEntity;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.entity.LibraryPathEntity;
import org.booklore.model.enums.BookFileExtension;
import org.booklore.model.enums.BookFileType;
import org.booklore.model.enums.LibraryOrganizationMode;
import org.booklore.model.websocket.LogNotification;
import org.booklore.model.websocket.Topic;
import org.booklore.repository.BookRepository;
import org.booklore.repository.LibraryRepository;
import org.booklore.service.NotificationService;
import org.booklore.service.file.FileFingerprint;
import org.booklore.service.library.ExistingBookAttacher;
import org.booklore.service.library.FilelessShells;
import org.booklore.service.library.LibraryProcessingService;
import org.booklore.service.library.matching.BooksInFolder;
import org.booklore.service.library.matching.BooksInFolder.FolderBook;
import org.booklore.service.library.matching.BooksInFolder.FolderFile;
import org.booklore.service.library.matching.ExistingBookMatcher;
import org.booklore.service.library.matching.ExistingBookMatcher.Ambiguous;
import org.booklore.service.library.matching.ExistingBookMatcher.Decision;
import org.booklore.service.library.matching.ExistingBookMatcher.Kind;
import org.booklore.service.library.matching.ExistingBookMatcher.Match;
import org.booklore.service.library.matching.FileEvidence;
import org.booklore.util.FileUtils;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.booklore.model.enums.PermissionType.ADMIN;
import static org.booklore.model.enums.PermissionType.MANAGE_LIBRARY;

@Slf4j
@Service
@RequiredArgsConstructor
public class BookFileTransactionalHandler {

    private final BookFilePersistenceService bookFilePersistenceService;
    private final LibraryProcessingService libraryProcessingService;
    private final NotificationService notificationService;
    private final LibraryRepository libraryRepository;
    private final BookRepository bookRepository;
    private final PendingDeletionPool pendingDeletionPool;
    private final FilelessShells filelessShells;
    private final ExistingBookAttacher existingBookAttacher;

    @Transactional()
    public void handleNewBookFile(long libraryId, Path path) {
        LibraryEntity libraryEntity = libraryRepository.findById(libraryId).orElseThrow(() -> ApiError.LIBRARY_NOT_FOUND.createException(libraryId));

        String filePath = path.toString();
        String fileName = path.getFileName().toString();
        String libraryPath = bookFilePersistenceService.findMatchingLibraryPath(libraryEntity, path);

        notificationService.sendMessageToPermissions(Topic.LOG, LogNotification.info("Started processing file: " + filePath), Set.of(ADMIN, MANAGE_LIBRARY));

        LibraryPathEntity libraryPathEntity = bookFilePersistenceService.getLibraryPathEntityForFile(libraryEntity, libraryPath);
        String fileSubPath = FileUtils.getRelativeSubPath(libraryPathEntity.getPath(), path);

        Optional<BookFileEntity> existingAtPath = bookFilePersistenceService
                .findBookFileByLibraryPathSubPathAndFileName(libraryPathEntity.getId(), fileSubPath, fileName);
        if (existingAtPath.isPresent()) {
            BookFileEntity existing = existingAtPath.get();
            BookEntity existingBook = existing.getBook();
            boolean wasDeleted = Boolean.TRUE.equals(existingBook.getDeleted());
            String existingHash = existing.getCurrentHash();
            String currentHash = FileFingerprint.generateHash(path);

            if (wasDeleted) {
                pendingDeletionPool.matchByHash(existingHash);
                existingBook.setDeleted(false);
                existingBook.setDeletedAt(null);
                existing.setCurrentHash(currentHash);
                bookFilePersistenceService.save(existingBook);
                log.info("[CREATE] File '{}' restored deleted book id={}", filePath, existingBook.getId());
                notificationService.sendMessageToPermissions(Topic.LOG, LogNotification.info("Finished processing file: " + filePath), Set.of(ADMIN, MANAGE_LIBRARY));
                return;
            }

            pendingDeletionPool.cancelByPath(path);

            if (currentHash.equals(existingHash)) {
                log.debug("[CREATE] File '{}' unchanged (same hash), skipping", filePath);
                notificationService.sendMessageToPermissions(Topic.LOG, LogNotification.info("Finished processing file: " + filePath), Set.of(ADMIN, MANAGE_LIBRARY));
                return;
            }
            existing.setCurrentHash(currentHash);
            bookFilePersistenceService.save(existingBook);
            log.info("[CREATE] File '{}' content changed, updated hash", filePath);
            notificationService.sendMessageToPermissions(Topic.LOG, LogNotification.info("Finished processing file: " + filePath), Set.of(ADMIN, MANAGE_LIBRARY));
            return;
        }

        String currentHash = FileFingerprint.generateHash(path);

        Optional<PendingDeletionPool.MatchResult> poolMatch = pendingDeletionPool.matchByHash(currentHash);
        if (poolMatch.isPresent()) {
            var match = poolMatch.get();
            pendingDeletionPool.recoverBook(match, libraryPathEntity, fileSubPath, fileName, currentHash);
            log.info("[CREATE] File '{}' matched pending deletion, recovered book id={}", filePath, match.book().bookId());
            notificationService.sendMessageToPermissions(Topic.LOG, LogNotification.info("Finished processing file: " + filePath), Set.of(ADMIN, MANAGE_LIBRARY));
            return;
        }

        Optional<BookFileEntity> movedFrom = bookFilePersistenceService.findMovedFile(currentHash, path, Instant.now().minus(60, ChronoUnit.SECONDS));
        if (movedFrom.isPresent()) {
            bookFilePersistenceService.updatePathIfChanged(movedFrom.get(), libraryEntity, path);
            log.info("[CREATE] File '{}' recognized as moved file, updated existing book's path", filePath);
            notificationService.sendMessageToPermissions(Topic.LOG, LogNotification.info("Finished processing file: " + filePath), Set.of(ADMIN, MANAGE_LIBRARY));
            return;
        }

        LibraryFile libraryFile = LibraryFile.builder()
                .libraryEntity(libraryEntity)
                .libraryPathEntity(libraryPathEntity)
                .fileSubPath(fileSubPath)
                .fileName(fileName)
                .bookFileType(BookFileExtension.fromFileName(fileName)
                        .map(BookFileExtension::getType)
                        .orElseThrow(() -> new IllegalArgumentException("Unsupported book file type: " + fileName)))
                .build();

        if (attachToExistingBook(libraryEntity, libraryFile)) {
            log.info("[CREATE] Attached file '{}' to an existing book", filePath);
        } else {
            libraryProcessingService.processLibraryFiles(List.of(libraryFile), libraryEntity);
            log.info("[CREATE] Completed processing for file '{}'", filePath);
        }

        notificationService.sendMessageToPermissions(Topic.LOG, LogNotification.info("Finished processing file: " + filePath), Set.of(ADMIN, MANAGE_LIBRARY));
    }

    @Transactional
    public void handleNewFolderAudiobook(long libraryId, Path folderPath) {
        LibraryEntity libraryEntity = libraryRepository.findById(libraryId)
                .orElseThrow(() -> ApiError.LIBRARY_NOT_FOUND.createException(libraryId));

        String folderName = folderPath.getFileName().toString();
        String libraryPath = bookFilePersistenceService.findMatchingLibraryPath(libraryEntity, folderPath);

        notificationService.sendMessageToPermissions(Topic.LOG,
                LogNotification.info("Started processing folder audiobook: " + folderPath), Set.of(ADMIN, MANAGE_LIBRARY));

        LibraryPathEntity libraryPathEntity = bookFilePersistenceService.getLibraryPathEntityForFile(libraryEntity, libraryPath);
        String fileSubPath = FileUtils.getRelativeSubPath(libraryPathEntity.getPath(), folderPath);

        String folderHash = FileFingerprint.generateFolderHash(folderPath);
        Optional<PendingDeletionPool.MatchResult> poolMatch = pendingDeletionPool.matchByHash(folderHash);
        if (poolMatch.isPresent()) {
            var match = poolMatch.get();
            pendingDeletionPool.recoverBook(match, libraryPathEntity, fileSubPath, folderName, folderHash);
            log.info("[CREATE] Folder audiobook '{}' matched pending deletion, recovered book id={}",
                    folderName, match.book().bookId());
            notificationService.sendMessageToPermissions(Topic.LOG,
                    LogNotification.info("Finished processing folder audiobook: " + folderPath), Set.of(ADMIN, MANAGE_LIBRARY));
            return;
        }

        LibraryFile libraryFile = LibraryFile.builder()
                .libraryEntity(libraryEntity)
                .libraryPathEntity(libraryPathEntity)
                .fileSubPath(fileSubPath)
                .fileName(folderName)
                .bookFileType(BookFileType.AUDIOBOOK)
                .folderBased(true)
                .build();

        if (attachToExistingBook(libraryEntity, libraryFile)) {
            log.info("[CREATE] Attached folder audiobook '{}' to an existing book", folderName);
        } else {
            libraryProcessingService.processLibraryFiles(List.of(libraryFile), libraryEntity);
            log.info("[CREATE] Completed processing folder audiobook '{}'", folderPath);
        }

        notificationService.sendMessageToPermissions(Topic.LOG,
                LogNotification.info("Finished processing folder audiobook: " + folderPath), Set.of(ADMIN, MANAGE_LIBRARY));
    }

    /**
     * Attaches a new file to a book the library already has, decided as a rescan decides
     * ({@link ExistingBookMatcher}): a book with files in the file's folder first, then a fileless book. The watcher
     * handles one file at a time, so where a rescan leaves a fileless book claimed from two folders alone, here the
     * first folder to claim it wins; the book then has a file and is no fileless book for the next one.
     *
     * @return false when the file belongs to no existing book, or its book changed since it was read
     */
    private boolean attachToExistingBook(LibraryEntity library, LibraryFile file) {
        LibraryOrganizationMode mode = library.getOrganizationMode() != null
                ? library.getOrganizationMode() : LibraryOrganizationMode.AUTO_DETECT;
        Long libraryPathId = file.getLibraryPathEntity().getId();
        String subPath = file.getFileSubPath();
        FileEvidence evidence = FileEvidence.of(libraryPathId, subPath, file.getFileName(),
                file.getBookFileType() == BookFileType.AUDIOBOOK);

        Decision inFolder = switch (mode) {
            case AUTO_DETECT -> ExistingBookMatcher.matchInFolder(evidence, booksInFolder(libraryPathId, subPath));
            case BOOK_PER_FOLDER -> bookOfFolder(libraryPathId, subPath, file.getBookFileType());
            case BOOK_PER_FILE -> null;
        };
        if (inFolder instanceof Match match) {
            return attach(match, file);
        }
        if (inFolder instanceof Ambiguous ambiguous) {
            log.info("[CREATE] '{}' is left for a new book: {} (books {})", file.getFileName(), ambiguous.reason(), ambiguous.bookIds());
            return false;
        }
        Decision fileless = ExistingBookMatcher.matchShell(evidence, filelessShells.forWatcher(library.getId()));
        if (fileless instanceof Match match) {
            return attach(match, file);
        }
        if (fileless instanceof Ambiguous ambiguous) {
            log.info("[CREATE] '{}' is left for a new book: {}", file.getFileName(), ambiguous.reason());
        } else {
            log.debug("[CREATE] '{}' matches no fileless book: {}", file.getFileName(), fileless.reason());
        }
        return false;
    }

    private boolean attach(Match match, LibraryFile file) {
        return !existingBookAttacher.attach(match.bookId(), match.kind(), List.of(file)).isEmpty();
    }

    /**
     * The books with book files in the folder, with those files and their authors. The database finds the folder through
     * its collation, which ignores case and possibly accents; a book with files only in a folder that compares equal is
     * in another folder.
     */
    private BooksInFolder booksInFolder(Long libraryPathId, String subPath) {
        if (subPath == null) {
            return BooksInFolder.EMPTY;
        }
        List<FolderBook> books = new ArrayList<>();
        for (BookEntity book : bookRepository.findAllByLibraryPathIdAndFileSubPath(libraryPathId, subPath)) {
            if (Boolean.TRUE.equals(book.getDeleted())) {
                continue;
            }
            List<FolderFile> files = book.getBookFiles().stream()
                    .filter(bf -> bf.isBookFormat() && subPath.equals(bf.getFileSubPath()))
                    .map(bf -> new FolderFile(bf.getFileName(), bf.getBookType() == BookFileType.AUDIOBOOK))
                    .toList();
            if (files.isEmpty()) {
                continue;
            }
            List<String> authors = book.getMetadata() == null || book.getMetadata().getAuthors() == null
                    ? List.of()
                    : book.getMetadata().getAuthors().stream().map(AuthorEntity::getName).toList();
            books.add(new FolderBook(book.getId(), files, authors));
        }
        return BooksInFolder.of(subPath, books);
    }

    /**
     * BOOK_PER_FOLDER, as the rescan decides it: the one book with files in the folder, none when there are several,
     * and for an audiobook in a folder without books the one book with an ebook in the nearest folder above. Files at
     * the library's root are books of their own.
     */
    private Decision bookOfFolder(Long libraryPathId, String subPath, BookFileType fileType) {
        if (subPath == null || subPath.isEmpty()) {
            return null;
        }
        List<Long> inFolder = activeBookIds(bookRepository.findAllByLibraryPathIdAndFileSubPath(libraryPathId, subPath), subPath, false);
        if (inFolder.size() == 1) {
            return new Match(inFolder.getFirst(), Kind.SAME_FOLDER, "the book of its folder");
        }
        if (inFolder.size() > 1) {
            return new Ambiguous(inFolder, inFolder.size() + " books have files in its folder");
        }
        if (fileType != BookFileType.AUDIOBOOK) {
            return null;
        }
        String current = subPath;
        while (true) {
            int lastSep = Math.max(current.lastIndexOf('/'), current.lastIndexOf('\\'));
            if (lastSep <= 0) {
                return null;
            }
            current = current.substring(0, lastSep);
            List<Long> withEbook = activeBookIds(bookRepository.findAllByLibraryPathIdAndFileSubPath(libraryPathId, current), current, true);
            if (withEbook.size() == 1) {
                log.debug("BOOK_PER_FOLDER: audiobook absorbed by book id={} at '{}'", withEbook.getFirst(), current);
                return new Match(withEbook.getFirst(), Kind.SAME_FOLDER, "the book with an ebook in '" + current + "'");
            }
            if (withEbook.size() > 1) {
                return new Ambiguous(withEbook, withEbook.size() + " books have ebooks in '" + current + "'");
            }
        }
    }

    /**
     * The books, not deleted, with a book format in exactly this folder, and with an ebook if asked. The folder lookup
     * compares through the database's collation, so it can also return books of a folder that only compares equal.
     */
    private static List<Long> activeBookIds(List<BookEntity> books, String folder, boolean withAnEbook) {
        return books.stream()
                .filter(book -> !Boolean.TRUE.equals(book.getDeleted()))
                .filter(book -> book.getBookFiles().stream()
                        .anyMatch(bf -> bf.isBookFormat() && folder.equals(bf.getFileSubPath())))
                .filter(book -> !withAnEbook || book.getBookFiles().stream()
                        .anyMatch(bf -> bf.isBookFormat() && bf.getBookType() != BookFileType.AUDIOBOOK))
                .map(BookEntity::getId)
                .toList();
    }
}
