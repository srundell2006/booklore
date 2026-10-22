package org.booklore.service.fileprocessor;

import org.booklore.mapper.BookMapper;
import org.booklore.model.FileProcessResult;
import org.booklore.model.dto.settings.LibraryFile;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.model.enums.FileProcessStatus;
import org.booklore.repository.BookAdditionalFileRepository;
import org.booklore.repository.BookRepository;
import org.booklore.service.book.BookCreatorService;
import org.booklore.service.fileprocessor.prepare.*;
import org.booklore.service.metadata.MetadataMatchService;
import org.booklore.service.metadata.sidecar.SidecarMetadataWriter;
import org.booklore.util.BookCoverUtils;
import org.booklore.util.FileService;
import org.booklore.util.FileUtils;
import org.booklore.util.cover.CoverKind;
import org.booklore.util.cover.RenderedCover;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

/**
 * Turns a new book file into a book in two steps. {@link #prepare} reads the file: its hash and size, its metadata
 * and its cover, rendered to the stored JPEGs, falling back to an image in the book's folder. {@link #persistPrepared}
 * saves what was read. Subclasses supply the format's part of each step: {@link #readContent} and
 * {@link #applyMetadata}.
 * <p>
 * No method here may be final: the processors are CGLIB proxies (their processFile is transactional), and a final
 * method would run on the proxy, whose fields are null.
 */
@Slf4j
public abstract class AbstractFileProcessor implements PreparableBookFileProcessor {

    protected final BookRepository bookRepository;
    protected final BookAdditionalFileRepository bookAdditionalFileRepository;
    protected final BookCreatorService bookCreatorService;
    protected final BookMapper bookMapper;
    protected final MetadataMatchService metadataMatchService;
    protected final FileService fileService;
    protected final SidecarMetadataWriter sidecarMetadataWriter;


    protected AbstractFileProcessor(BookRepository bookRepository,
                                    BookAdditionalFileRepository bookAdditionalFileRepository,
                                    BookCreatorService bookCreatorService,
                                    BookMapper bookMapper,
                                    FileService fileService,
                                    MetadataMatchService metadataMatchService,
                                    SidecarMetadataWriter sidecarMetadataWriter) {
        this.bookRepository = bookRepository;
        this.bookAdditionalFileRepository = bookAdditionalFileRepository;
        this.bookCreatorService = bookCreatorService;
        this.bookMapper = bookMapper;
        this.metadataMatchService = metadataMatchService;
        this.fileService = fileService;
        this.sidecarMetadataWriter = sidecarMetadataWriter;
    }

    /**
     * One file, read on the calling thread with no limits and saved in a transaction of its own, with its sidecar
     * written before the transaction commits.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Override
    public FileProcessResult processFile(LibraryFile libraryFile) {
        PreparedBookFile prepared;
        try {
            prepared = prepare(PrepareRequest.of(libraryFile, fileService.currentCropPolicy()), PrepareContext.unbounded());
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            // Unchecked, so the transaction rolls back
            throw new IllegalStateException("Failed to read " + libraryFile.getFileName() + ": " + e.getMessage(), e);
        }

        PersistedBook persisted = persistPrepared(libraryFile, prepared);

        if (persisted.sidecarJob() != null) {
            try {
                sidecarMetadataWriter.execute(persisted.sidecarJob());
            } catch (Exception e) {
                log.warn("Failed to write sidecar metadata for book ID {}: {}", persisted.bookId(), e.getMessage());
            }
        }
        return new FileProcessResult(persisted.book(), FileProcessStatus.NEW);
    }

    @Override
    public PreparedBookFile prepare(PrepareRequest request, PrepareContext ctx) throws Exception {
        // A folder audiobook's folder is walked once for its hash, its size, its audio files and its cover image
        FolderSnapshot folder = request.folderBased() ? FolderSnapshot.scan(request.path()) : null;
        FileFacts facts = folder != null ? FileFacts.of(folder) : FileFacts.read(request.path());
        ctx.checkpoint();

        CoverMaker covers = new CoverMaker(ctx, coverKind(), request.crop());
        ExtractedContent content = readContent(request, facts, folder, covers, ctx);
        ctx.checkpoint();

        RenderedCover cover = content.cover();
        if (cover == null && request.coverFallbackFolder() != null) {
            cover = folderCover(request.coverFallbackFolder(), folder, covers);
        }
        return new PreparedBookFile(request, facts, content.metadata(), cover, content.archiveType());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public PersistedBook persistPrepared(LibraryFile libraryFile, PreparedBookFile prepared) {
        if (!libraryFile.getFullPath().equals(prepared.request().path())) {
            throw new IllegalStateException("Prepared file " + prepared.request().path() + " does not belong to " + libraryFile.getFullPath());
        }

        BookCreatorService.ShellBook shell = bookCreatorService.createShellBook(libraryFile, shellBookType(libraryFile), prepared.facts());
        BookEntity entity = shell.entity();
        boolean metadataApplied = false;
        if (shell.created()) {
            applyMetadata(entity, prepared);
            metadataApplied = true;
            applyCover(entity, prepared.cover(), true);
            // The new book's one file
            entity.getPrimaryBookFile().setCurrentHash(prepared.facts().hash());
        } else if (shell.restored()) {
            metadataApplied = restore(entity, prepared);
        } else {
            // The book already had this file, which createShellBook gave its new hash. What the book says is what its
            // owner or a metadata refresh made it say; a scan that took the file for a new one does not overwrite it.
            log.info("Kept the metadata and cover of book#{}, which already has {}", entity.getId(), libraryFile.getFileName());
        }
        entity.setMetadataMatchScore(metadataMatchService.calculateMatchScore(entity));
        // Only comics have comic metadata, and only CbxProcessor's applyMetadata gives the book the entity it fills. A
        // book that keeps its metadata keeps its characters, teams, locations and creators too.
        bookCreatorService.saveConnections(entity,
                !metadataApplied || prepared.metadata() == null ? null : prepared.metadata().getComicMetadata());

        SidecarMetadataWriter.SidecarJob sidecarJob = null;
        if (!libraryFile.isDeferSidecarWrite() && sidecarMetadataWriter.isWriteOnScanEnabled()) {
            try {
                sidecarJob = sidecarMetadataWriter.prepareForScan(entity).orElse(null);
            } catch (Exception e) {
                log.warn("Failed to write sidecar metadata for book ID {}: {}", entity.getId(), e.getMessage());
            }
        }

        return new PersistedBook(bookMapper.toBook(entity), entity.getId(), shell.created(), libraryFile.getFileName(),
                entity.getPrimaryBookFile().getBookType(), sidecarJob);
    }

    /**
     * A deleted book that its file brings back takes the file's metadata and cover as a new book would, but not where its
     * owner locked them: with any metadata field locked it keeps all of its metadata, as the format's
     * {@link #applyMetadata} writes every field it reads, and a locked cover stays.
     *
     * @return whether the book took the file's metadata
     */
    private boolean restore(BookEntity entity, PreparedBookFile prepared) {
        BookMetadataEntity metadata = entity.getMetadata();
        boolean applied = !metadata.isAnyMetadataFieldLocked();
        if (applied) {
            applyMetadata(entity, prepared);
        } else {
            log.info("Restored book#{} keeps its metadata, some of which is locked", entity.getId());
        }
        RenderedCover cover = prepared.cover();
        boolean coverLocked = cover != null && Boolean.TRUE.equals(cover.kind() == CoverKind.AUDIOBOOK
                ? metadata.getAudiobookCoverLocked() : metadata.getCoverLocked());
        if (coverLocked) {
            log.info("Restored book#{} keeps its locked cover", entity.getId());
        } else {
            applyCover(entity, cover, false);
        }
        return applied;
    }

    /**
     * Reads the format's metadata and cover from the file. Runs wherever {@link #prepare} runs, so it must not touch
     * entities, repositories or the session. Exceptions it does not handle fail the whole file.
     *
     * @param folder the walked folder of a folder audiobook, null for a single file
     * @param covers renders the cover found in the file
     */
    protected abstract ExtractedContent readContent(PrepareRequest request, FileFacts facts, FolderSnapshot folder,
                                                    CoverMaker covers, PrepareContext ctx) throws Exception;

    /** Copies the prepared metadata onto the book, inside the transaction that saves it. */
    protected abstract void applyMetadata(BookEntity bookEntity, PreparedBookFile prepared);

    /** The type the new book's primary file is saved with. */
    protected BookFileType shellBookType(LibraryFile libraryFile) {
        return getSupportedTypes().getFirst();
    }

    /** Which cover files this format's covers are stored as. */
    protected CoverKind coverKind() {
        return CoverKind.BOOK;
    }

    protected Path getBookFolderForCoverFallback(LibraryFile libraryFile) {
        return PrepareRequest.coverFallbackFolder(libraryFile);
    }

    /**
     * The cover rendered from a cover image in the book's folder, or null. A book's image is decoded as ImageIO.read
     * decodes a file, an audiobook's as FileService.readImage decodes it, as each always was.
     */
    private RenderedCover folderCover(Path bookFolder, FolderSnapshot folder, CoverMaker covers) throws Exception {
        // A folder audiobook's walk already listed the folder
        Optional<Path> coverImage = folder != null && folder.folder().equals(bookFolder)
                ? FileUtils.findCoverImageInNames(bookFolder, folder.regularChildNames())
                : FileUtils.findCoverImageInFolder(bookFolder);
        if (coverImage.isEmpty()) return null;
        try {
            RenderedCover cover = covers.fromEncoded(Files.readAllBytes(coverImage.get()), coverKind() == CoverKind.AUDIOBOOK);
            if (cover == null) {
                log.debug("Failed to use folder cover image {}: it could not be decoded", coverImage.get());
            }
            return cover;
        } catch (PrepareAbandonedException | PrepareDeadlineException e) {
            throw e;
        } catch (Exception e) {
            log.debug("Failed to use folder cover image {}: {}", coverImage.get(), e.getMessage());
            return null;
        }
    }

    private void applyCover(BookEntity bookEntity, RenderedCover cover, boolean created) {
        if (cover == null) return;
        long bookId = bookEntity.getId();
        if (created && TransactionSynchronizationManager.isSynchronizationActive()) {
            // A new book's images folder must not outlive a rollback of the book
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status == STATUS_ROLLED_BACK) {
                        fileService.deleteBookCovers(Set.of(bookId));
                    }
                }
            });
        }

        boolean written;
        try {
            written = fileService.writeRenderedCover(bookId, cover);
        } catch (IOException e) {
            log.warn("Failed to write cover for book ID {}: {}", bookId, e.getMessage());
            written = false;
        }
        if (!written) return;

        if (cover.kind() == CoverKind.AUDIOBOOK) {
            bookEntity.getMetadata().setAudiobookCoverUpdatedOn(Instant.now());
            bookEntity.setAudiobookCoverHash(BookCoverUtils.generateCoverHash());
        } else {
            FileService.setBookCoverPath(bookEntity.getMetadata());
            bookEntity.setBookCoverHash(BookCoverUtils.generateCoverHash());
        }
    }
}
