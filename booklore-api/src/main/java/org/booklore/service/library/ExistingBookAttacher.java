package org.booklore.service.library;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.model.dto.settings.LibraryFile;
import org.booklore.model.entity.AuthorEntity;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.repository.BookAdditionalFileRepository;
import org.booklore.repository.BookRepository;
import org.booklore.service.file.FileFingerprint;
import org.booklore.service.library.importer.ImportGroups;
import org.booklore.service.library.matching.ExistingBookMatcher;
import org.booklore.service.library.matching.ExistingBookMatcher.Kind;
import org.booklore.service.library.matching.ExistingBookMatcher.Match;
import org.booklore.service.library.matching.FileEvidence;
import org.booklore.service.library.matching.FilelessShellIndex;
import org.booklore.service.library.matching.ShellCandidate;
import org.booklore.util.FileUtils;
import org.booklore.util.cover.CoverKind;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Attaches new files to a book the library already has, as the rescan and the watcher decided with
 * {@link org.booklore.service.library.matching.ExistingBookMatcher}. One transaction per book, of its own even when
 * the watcher has one open, that starts by locking the book's row. A watcher event and a rescan can attach files of
 * two folders to one fileless book at once; with the lock the second waits for the first to commit, then reads the
 * book's files afresh and finds it no longer fileless. In the watcher's transaction it would read them as they were
 * when that transaction began.
 * <p>
 * What the book was is read before any file is built for it. With association management on, building a file for a
 * book adds it to the book's files at once, so a fileless book would already look like it had one, and the new file
 * would already look like its primary. The cover decision is taken from what was read:
 * <ul>
 *   <li>A book that had no files keeps the cover it has, which is likely one its owner chose; only a book with no
 *       cover at all gets one, from the attached files, as a new book would.</li>
 *   <li>A book with files gets a cover of an attached file's kind only when that file is an ebook for an audiobook,
 *       or an audiobook for an ebook, and the book has no cover of that kind yet. One it has came from an earlier
 *       file, a metadata fetch or its owner, and a scan does not replace it.</li>
 *   <li>A kind of cover its owner locked is never written.</li>
 * </ul>
 * A fileless book gets the files only when one of them still matches it as it is now. The watcher matches against
 * fileless books it read up to {@link FilelessShells#WATCHER_INDEX_LIFETIME} ago, and the book's title, authors,
 * series or physical flag may have been edited since: "The Martian.epub" must not go to a book retitled "Project Hail
 * Mary", nor "Saga (1).epub" to a "Saga" number 2 whose series was renamed "Saga".
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ExistingBookAttacher {

    private final BookRepository bookRepository;
    private final BookAdditionalFileRepository bookAdditionalFileRepository;
    private final FileAsBookProcessor fileAsBookProcessor;
    private final FilelessShells filelessShells;
    private final PlatformTransactionManager transactionManager;

    /**
     * Attaches the files to the book, if it is still the book they were matched to: not deleted since, and for a
     * fileless match still without files. Files not attached are left to the caller: the rescan leaves them new to the
     * next scan, the watcher makes a new book of them. An error reading a file rolls the book's transaction back and
     * is thrown to the caller.
     *
     * @return the files attached
     */
    public List<LibraryFile> attach(long bookId, Kind kind, List<LibraryFile> files) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        List<LibraryFile> attached = transaction.execute(status -> attachToBook(bookId, kind, files));
        return attached == null ? List.of() : attached;
    }

    private List<LibraryFile> attachToBook(long bookId, Kind kind, List<LibraryFile> files) {
        BookEntity book = bookRepository.findByIdForUpdate(bookId).orElse(null);
        if (book == null || Boolean.TRUE.equals(book.getDeleted())) {
            log.warn("book#{} is gone or deleted since {} was matched to it; not attached", bookId, names(files));
            filelessShells.notFileless(bookId);
            return List.of();
        }
        String label = label(book);
        boolean wasFileless = !book.hasFiles();
        if (kind == Kind.FILELESS && !wasFileless) {
            log.info("{} got a file since {} was matched to it as a fileless book; not attached", label, names(files));
            filelessShells.notFileless(bookId);
            return List.of();
        }
        if (kind == Kind.FILELESS && !stillMatches(book, files)) {
            log.info("{} was edited since {} was matched to it as a fileless book, and no longer matches; not attached",
                    label, names(files));
            if (book.getLibrary() != null) {
                filelessShells.readAgainForWatcher(book.getLibrary().getId());
            }
            return List.of();
        }
        boolean hadCover = book.getBookCoverHash() != null || book.getAudiobookCoverHash() != null;
        BookFileEntity primaryBefore = book.getPrimaryBookFile();
        BookFileType primaryTypeBefore = primaryBefore == null ? null : primaryBefore.getBookType();

        List<LibraryFile> attached = new ArrayList<>();
        for (LibraryFile file : files) {
            if (attachFile(book, file, label)) {
                attached.add(file);
            }
        }
        if (attached.isEmpty()) {
            return attached;
        }
        if (wasFileless) {
            if (hadCover) {
                log.debug("{} keeps its cover", label);
            } else {
                coverAsForANewBook(book, attached);
            }
            filelessShells.notFileless(bookId);
        } else {
            crossTypeCovers(book, primaryTypeBefore, attached);
        }
        return attached;
    }

    private boolean attachFile(BookEntity book, LibraryFile file, String label) {
        // Another scan, or the watcher, may have stored it meanwhile
        if (bookAdditionalFileRepository.findExactlyAt(
                file.getLibraryPathEntity().getId(), file.getFileSubPath(), file.getFileName()).isPresent()) {
            log.debug("{} is already stored, not attaching it to {}", file.getFileName(), label);
            return false;
        }
        if (book.getLibraryPath() == null) {
            // A fileless book may have no library path yet (a physical book)
            book.setLibraryPath(file.getLibraryPathEntity());
        } else if (!book.getLibraryPath().getId().equals(file.getLibraryPathEntity().getId())) {
            log.warn("Cannot attach '{}' to {}: the file is in library path {} and the book in library path {}",
                    file.getFileName(), label, file.getLibraryPathEntity().getId(), book.getLibraryPath().getId());
            return false;
        }

        String hash = file.isFolderBased()
                ? FileFingerprint.generateFolderHash(file.getFullPath())
                : FileFingerprint.generateHash(file.getFullPath());
        Long fileSizeKb = file.isFolderBased()
                ? FileUtils.getFolderSizeInKb(file.getFullPath())
                : FileUtils.getFileSizeInKb(file.getFullPath());
        bookAdditionalFileRepository.save(BookFileEntity.builder()
                .book(book)
                .fileName(file.getFileName())
                .fileSubPath(file.getFileSubPath())
                .isBookFormat(true)
                .bookType(file.getBookFileType())
                .folderBased(file.isFolderBased())
                .fileSizeKb(fileSizeKb)
                .initialHash(hash)
                .currentHash(hash)
                .addedOn(Instant.now())
                .build());
        log.info("Auto-attached {} to {}", file.getFileName(), label);
        return true;
    }

    /**
     * Whether one of the files matches the fileless book as it is now, as {@link ExistingBookMatcher#matchShell} decides.
     * The other files of an attachment go with the one that matched (a copy, the parts of an audiobook) and need not
     * match on their own.
     */
    private static boolean stillMatches(BookEntity book, List<LibraryFile> files) {
        FilelessShellIndex index = FilelessShellIndex.of(List.of(shellCandidate(book)));
        for (LibraryFile file : files) {
            FileEvidence evidence = FileEvidence.of(file.getLibraryPathEntity().getId(), file.getFileSubPath(),
                    file.getFileName(), file.getBookFileType() == BookFileType.AUDIOBOOK);
            if (ExistingBookMatcher.matchShell(evidence, index) instanceof Match) {
                return true;
            }
        }
        return false;
    }

    /** The book as {@link FilelessShells} reads a fileless book, from the entity instead of a projection row. */
    static ShellCandidate shellCandidate(BookEntity book) {
        BookMetadataEntity metadata = book.getMetadata();
        List<String> authors = metadata == null || metadata.getAuthors() == null
                ? List.of()
                : metadata.getAuthors().stream().map(AuthorEntity::getName).filter(name -> name != null && !name.isBlank()).toList();
        return ShellCandidate.of(book.getId(),
                book.getLibraryPath() == null ? null : book.getLibraryPath().getId(),
                Boolean.TRUE.equals(book.getIsPhysical()),
                book.getBookCoverHash() != null || book.getAudiobookCoverHash() != null,
                metadata == null ? null : metadata.getTitle(),
                metadata == null ? null : metadata.getSubtitle(),
                metadata == null ? null : metadata.getSeriesName(),
                metadata == null ? null : metadata.getSeriesNumber(),
                authors);
    }

    /** The cover of the file that is now the book's primary, and one of the other kind from a file of that kind. */
    private void coverAsForANewBook(BookEntity book, List<LibraryFile> attached) {
        LibraryFile primary = ImportGroups.findBestPrimaryFile(attached, book.getLibrary()).orElse(attached.getFirst());
        if (!isLocked(book, coverKind(primary))) {
            fileAsBookProcessor.writeCoverFromFile(book, primary);
        }
        crossTypeCovers(book, primary.getBookFileType(), attached.stream().filter(file -> file != primary).toList());
    }

    /**
     * A cover of an attached file's kind when it is an ebook for an audiobook or the other way round, once per kind,
     * and only when the book has no cover of that kind and its owner did not lock that kind.
     */
    private void crossTypeCovers(BookEntity book, BookFileType primaryType, List<LibraryFile> files) {
        Set<CoverKind> written = EnumSet.noneOf(CoverKind.class);
        for (LibraryFile file : files) {
            CoverKind kind = ImportGroups.crossTypeCover(primaryType, file.getBookFileType());
            if (kind != null && !hasCover(book, kind) && !isLocked(book, kind) && written.add(kind)) {
                fileAsBookProcessor.writeCoverFromFile(book, file);
            }
        }
    }

    private static CoverKind coverKind(LibraryFile file) {
        return file.getBookFileType() == BookFileType.AUDIOBOOK ? CoverKind.AUDIOBOOK : CoverKind.BOOK;
    }

    private static boolean hasCover(BookEntity book, CoverKind kind) {
        return (kind == CoverKind.AUDIOBOOK ? book.getAudiobookCoverHash() : book.getBookCoverHash()) != null;
    }

    private static boolean isLocked(BookEntity book, CoverKind kind) {
        BookMetadataEntity metadata = book.getMetadata();
        return metadata != null
                && Boolean.TRUE.equals(kind == CoverKind.AUDIOBOOK ? metadata.getAudiobookCoverLocked() : metadata.getCoverLocked());
    }

    /** How log lines name the book a file went to: its id and title, not one of its files. */
    static String label(BookEntity book) {
        String title = book.getMetadata() == null ? null : book.getMetadata().getTitle();
        return title == null ? "book#" + book.getId() : "book#" + book.getId() + " \"" + title + "\"";
    }

    private static List<String> names(List<LibraryFile> files) {
        return files.stream().map(LibraryFile::getFileName).toList();
    }
}
