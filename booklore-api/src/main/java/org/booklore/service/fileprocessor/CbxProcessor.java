package org.booklore.service.fileprocessor;

import org.booklore.mapper.BookMapper;
import org.booklore.model.dto.BookMetadata;
import org.booklore.model.dto.ComicMetadata;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.model.entity.ComicMetadataEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.repository.BookAdditionalFileRepository;
import org.booklore.repository.BookRepository;
import org.booklore.service.book.BookCreatorService;
import org.booklore.service.fileprocessor.prepare.*;
import org.booklore.service.metadata.MetadataMatchService;
import org.booklore.service.metadata.extractor.CbxMetadataExtractor;
import org.booklore.service.metadata.sidecar.SidecarMetadataWriter;
import org.booklore.util.ArchiveUtils;
import org.booklore.util.CbxArchive;
import org.booklore.util.FileService;
import org.booklore.util.FileUtils;
import org.booklore.util.cover.RenderedCover;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.MemoryLimitException;
import org.apache.commons.io.FilenameUtils;
import org.springframework.stereotype.Service;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.booklore.util.FileService.truncate;


@Slf4j
@Service
public class CbxProcessor extends AbstractFileProcessor implements BookFileProcessor {

    private static final Pattern UNDERSCORE_HYPHEN_PATTERN = Pattern.compile("[_\\-]");
    private static final Pattern IMAGE_EXTENSION_CASE_INSENSITIVE_PATTERN = Pattern.compile("(?i).*\\.(jpg|jpeg|png|webp)");
    // Heap a 7z read reserves for its decoder: xz takes 64.1 MiB for a 64 MiB dictionary, the largest of 7-Zip's presets
    static final int SEVEN_ZIP_DECODER_KIB = 66 * 1024;
    // The most heap a 7z decoder is given: a quarter of the heap, what the import's heap budget is by default
    private static final long MAX_SEVEN_ZIP_DECODER_KIB = Runtime.getRuntime().maxMemory() / 4 / 1024;
    private final CbxMetadataExtractor cbxMetadataExtractor;

    public CbxProcessor(BookRepository bookRepository,
                        BookAdditionalFileRepository bookAdditionalFileRepository,
                        BookCreatorService bookCreatorService,
                        BookMapper bookMapper,
                        FileService fileService,
                        MetadataMatchService metadataMatchService,
                        SidecarMetadataWriter sidecarMetadataWriter,
                        CbxMetadataExtractor cbxMetadataExtractor) {
        super(bookRepository, bookAdditionalFileRepository, bookCreatorService, bookMapper, fileService, metadataMatchService, sidecarMetadataWriter);
        this.cbxMetadataExtractor = cbxMetadataExtractor;
    }

    @Override
    protected ExtractedContent readContent(PrepareRequest request, FileFacts facts, FolderSnapshot folder,
                                           CoverMaker covers, PrepareContext ctx) {
        File file = request.path().toFile();
        String baseName = FilenameUtils.getBaseName(file.getName());

        // Detect the type and open the archive once for both the cover and ComicInfo.xml: on a
        // network share every open costs round trips, and junrar walks every RAR header per open
        ArchiveUtils.ArchiveType archiveType = ArchiveUtils.detectArchiveType(file);
        if (archiveType == ArchiveUtils.ArchiveType.SEVEN_ZIP) {
            return readSevenZip(request, file, baseName, covers, ctx);
        }
        RenderedCover cover = null;
        BookMetadata extracted;
        try (CbxArchive archive = CbxArchive.open(file, archiveType)) {
            cover = renderCover(archive, archiveType, covers, ctx);
            ctx.checkpoint();
            extracted = cbxMetadataExtractor.extractMetadata(archive, baseName);
        } catch (PrepareAbandonedException | PrepareDeadlineException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Could not open comic archive '{}': {}", request.fileName(), e.getMessage());
            // What CbxMetadataExtractor returns for an archive it cannot open
            extracted = BookMetadata.builder().title(baseName).build();
        }

        if (cover == null) {
            log.warn("Could not find cover image in CBZ file '{}'", request.fileName());
        }
        return new ExtractedContent(extracted, cover, archiveType);
    }

    /**
     * {@link #readContent} for a 7z. Its LZMA decoder allocates the whole dictionary when it starts, up to 64 MiB
     * with 7-Zip's presets, and keeps it until the archive is closed: with many 7z files read at once, and each held
     * open while its cover waits to render, that is heap the import's budget would not know of. So the archive is
     * read under heap reserved for its decoder, and closed before the cover is rendered. One with a larger dictionary
     * than the presets' is opened again under heap for what its decoder needs, unless that is more than a quarter of
     * the heap. As before, only the first image is tried.
     */
    private ExtractedContent readSevenZip(PrepareRequest request, File file, String baseName, CoverMaker covers,
                                          PrepareContext ctx) {
        SevenZipEntries entries;
        try {
            try {
                entries = readSevenZipEntries(file, baseName, SEVEN_ZIP_DECODER_KIB, ctx);
            } catch (MemoryLimitException e) {
                if (e.getMemoryNeededInKb() > MAX_SEVEN_ZIP_DECODER_KIB) {
                    throw new IOException("its 7z decoder needs " + e.getMemoryNeededInKb() / 1024
                            + " MiB of heap, more than a quarter of the heap", e);
                }
                entries = readSevenZipEntries(file, baseName, (int) e.getMemoryNeededInKb(), ctx);
            }
        } catch (PrepareAbandonedException | PrepareDeadlineException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Could not open comic archive '{}': {}", request.fileName(), e.getMessage());
            // What CbxMetadataExtractor returns for an archive it cannot open
            entries = new SevenZipEntries(null, null, BookMetadata.builder().title(baseName).build());
        }

        RenderedCover cover = null;
        if (entries.coverImage() != null) {
            try {
                cover = covers.fromEncodedOrThrow(entries.coverImage(), true);
            } catch (PrepareAbandonedException | PrepareDeadlineException e) {
                throw e;
            } catch (Exception e) {
                log.warn("Failed to read image from archive entry {}: {}", entries.coverName(), e.getMessage());
            }
        }
        if (cover == null) {
            log.warn("Could not find cover image in CBZ file '{}'", request.fileName());
        }
        return new ExtractedContent(entries.metadata(), cover, ArchiveUtils.ArchiveType.SEVEN_ZIP);
    }

    /**
     * The first image's bytes and the ComicInfo.xml metadata of a 7z, read under {@code decoderKib} of heap for its
     * decoder.
     *
     * @throws MemoryLimitException when its decoder needs more than {@code decoderKib}
     */
    private SevenZipEntries readSevenZipEntries(File file, String baseName, int decoderKib, PrepareContext ctx)
            throws IOException {
        try (HeapReservation ignored = ctx.reserveHeap(decoderKib * 1024L);
             CbxArchive archive = CbxArchive.open(file, ArchiveUtils.ArchiveType.SEVEN_ZIP, decoderKib)) {
            String coverName = imageNames(archive).stream().findFirst().orElse(null);
            byte[] coverImage = null;
            if (coverName != null) {
                ctx.checkpoint();
                try {
                    coverImage = archive.readEntry(coverName);
                } catch (MemoryLimitException e) {
                    throw e;
                } catch (Exception e) {
                    log.warn("Failed to read image from archive entry {}: {}", coverName, e.getMessage());
                }
            }
            ctx.checkpoint();
            return new SevenZipEntries(coverName, coverImage, cbxMetadataExtractor.extractMetadata(archive, baseName));
        }
    }

    private record SevenZipEntries(String coverName, byte[] coverImage, BookMetadata metadata) {
    }

    @Override
    protected void applyMetadata(BookEntity bookEntity, PreparedBookFile prepared) {
        bookEntity.getPrimaryBookFile().setArchiveType(prepared.archiveType());
        setExtractedMetadata(bookEntity, prepared.metadata());
    }

    @Override
    public boolean generateCover(BookEntity bookEntity) {
        return generateCover(bookEntity, bookEntity.getPrimaryBookFile());
    }

    @Override
    public boolean generateCover(BookEntity bookEntity, BookFileEntity bookFile) {
        File file = new File(FileUtils.getBookFullPath(bookEntity, bookFile));
        ArchiveUtils.ArchiveType archiveType = ArchiveUtils.detectArchiveType(file);
        Optional<BufferedImage> cover;
        try (CbxArchive archive = CbxArchive.open(file, archiveType)) {
            cover = findCoverImage(archive, archiveType);
        } catch (Exception e) {
            log.error("Error generating cover for '{}': {}", bookFile.getFileName(), e.getMessage());
            return false;
        }
        return saveCover(bookEntity, bookFile, cover);
    }

    @Override
    public List<BookFileType> getSupportedTypes() {
        return List.of(BookFileType.CBX);
    }

    /**
     * The first jpg, jpeg, png or webp entry by name, in any folder. A RAR moves on to the next
     * image when one cannot be extracted or decoded; a ZIP or 7z only ever tries the first.
     */
    private Optional<BufferedImage> findCoverImage(CbxArchive archive, ArchiveUtils.ArchiveType archiveType) {
        for (String name : imageNames(archive)) {
            try {
                return Optional.ofNullable(FileService.readImage(archive.readEntry(name)));
            } catch (Exception e) {
                log.warn("Failed to read image from archive entry {}: {}", name, e.getMessage());
                if (archiveType != ArchiveUtils.ArchiveType.RAR) {
                    break;
                }
            }
        }
        return Optional.empty();
    }

    /** The cover rendered from the image {@link #findCoverImage} picks, chosen by the same rules. */
    private RenderedCover renderCover(CbxArchive archive, ArchiveUtils.ArchiveType archiveType, CoverMaker covers,
                                      PrepareContext ctx) {
        for (String name : imageNames(archive)) {
            // A RAR given up on would otherwise try every image in turn, each read failing at once: through unrar,
            // every process it starts is killed, and the thread stays interrupted
            ctx.checkpoint();
            try {
                return covers.fromEncodedOrThrow(archive.readEntry(name), true);
            } catch (PrepareAbandonedException | PrepareDeadlineException e) {
                throw e;
            } catch (Exception e) {
                log.warn("Failed to read image from archive entry {}: {}", name, e.getMessage());
                if (archiveType != ArchiveUtils.ArchiveType.RAR) {
                    break;
                }
            }
        }
        return null;
    }

    private List<String> imageNames(CbxArchive archive) {
        return archive.fileNames().stream()
                .filter(name -> IMAGE_EXTENSION_CASE_INSENSITIVE_PATTERN.matcher(name).matches())
                .sorted()
                .toList();
    }

    private boolean saveCover(BookEntity bookEntity, BookFileEntity bookFile, Optional<BufferedImage> cover) {
        if (cover.isEmpty()) {
            log.warn("Could not find cover image in CBZ file '{}'", bookFile.getFileName());
            return false;
        }
        BufferedImage image = cover.get();
        try {
            boolean saved = fileService.saveCoverImages(image, bookEntity.getId());
            if (saved) {
                return true;
            }
            log.warn("Could not save image extracted from CBZ as cover for '{}'", bookFile.getFileName());
        } catch (Exception e) {
            log.error("Error generating cover for '{}': {}", bookFile.getFileName(), e.getMessage());
        } finally {
            image.flush(); // Release resources after processing
        }
        return false;
    }

    private void setExtractedMetadata(BookEntity bookEntity, BookMetadata extracted) {
        try {
            if (extracted == null) {
                // Fallback to filename-derived title
                setMetadata(bookEntity);
                return;
            }

            BookMetadataEntity metadata = bookEntity.getMetadata();
            
            // Basic fields
            metadata.setTitle(truncate(extracted.getTitle(), 1000));
            metadata.setSubtitle(truncate(extracted.getSubtitle(), 1000));
            metadata.setDescription(truncate(extracted.getDescription(), 5000));
            metadata.setPublisher(truncate(extracted.getPublisher(), 1000));
            metadata.setPublishedDate(extracted.getPublishedDate());
            metadata.setSeriesName(truncate(extracted.getSeriesName(), 1000));
            metadata.setSeriesNumber(extracted.getSeriesNumber());
            metadata.setSeriesTotal(extracted.getSeriesTotal());
            metadata.setPageCount(extracted.getPageCount());
            metadata.setLanguage(truncate(extracted.getLanguage(), 1000));
            
            // ISBN fields
            metadata.setIsbn13(truncate(extracted.getIsbn13(), 64));
            metadata.setIsbn10(truncate(extracted.getIsbn10(), 64));
            
            // External IDs
            metadata.setAsin(truncate(extracted.getAsin(), 20));
            metadata.setGoodreadsId(truncate(extracted.getGoodreadsId(), 100));
            metadata.setHardcoverId(truncate(extracted.getHardcoverId(), 100));
            metadata.setHardcoverBookId(truncate(extracted.getHardcoverBookId(), 100));
            metadata.setGoogleId(truncate(extracted.getGoogleId(), 100));
            metadata.setComicvineId(truncate(extracted.getComicvineId(), 100));
            metadata.setLubimyczytacId(truncate(extracted.getLubimyczytacId(), 100));
            metadata.setRanobedbId(truncate(extracted.getRanobedbId(), 100));
            
            // Ratings
            metadata.setAmazonRating(extracted.getAmazonRating());
            metadata.setAmazonReviewCount(extracted.getAmazonReviewCount());
            metadata.setGoodreadsRating(extracted.getGoodreadsRating());
            metadata.setGoodreadsReviewCount(extracted.getGoodreadsReviewCount());
            metadata.setHardcoverRating(extracted.getHardcoverRating());
            metadata.setHardcoverReviewCount(extracted.getHardcoverReviewCount());
            metadata.setLubimyczytacRating(extracted.getLubimyczytacRating());
            metadata.setRanobedbRating(extracted.getRanobedbRating());

            // Authors
            if (extracted.getAuthors() != null) {
                bookCreatorService.addAuthorsToBook(extracted.getAuthors(), bookEntity);
            }
            
            // Categories
            if (extracted.getCategories() != null) {
                Set<String> validCategories = extracted.getCategories().stream()
                        .filter(s -> s != null && !s.isBlank() && s.length() <= 100 && !s.contains("\n") && !s.contains("\r") && !s.contains("  "))
                        .collect(Collectors.toSet());
                bookCreatorService.addCategoriesToBook(validCategories, bookEntity);
            }
            
            // Moods
            if (extracted.getMoods() != null && !extracted.getMoods().isEmpty()) {
                Set<String> validMoods = extracted.getMoods().stream()
                        .filter(s -> s != null && !s.isBlank() && s.length() <= 255)
                        .collect(Collectors.toSet());
                bookCreatorService.addMoodsToBook(validMoods, bookEntity);
            }
            
            // Tags
            if (extracted.getTags() != null && !extracted.getTags().isEmpty()) {
                Set<String> validTags = extracted.getTags().stream()
                        .filter(s -> s != null && !s.isBlank() && s.length() <= 255)
                        .collect(Collectors.toSet());
                bookCreatorService.addTagsToBook(validTags, bookEntity);
            }
            if (extracted.getComicMetadata() != null) {
                saveComicMetadata(bookEntity, extracted.getComicMetadata());
            }
        } catch (Exception e) {
            log.warn("Failed to extract ComicInfo metadata for '{}': {}", bookEntity.getPrimaryBookFile().getFileName(), e.getMessage());
            // Fallback to filename-derived title
            setMetadata(bookEntity);
        }
    }

    private void setMetadata(BookEntity bookEntity) {
        String baseName = new File(bookEntity.getPrimaryBookFile().getFileName()).getName();
        String extension = FileUtils.getExtension(baseName);
        if (BookFileType.CBX.supports(extension)) {
            baseName = baseName.substring(0, baseName.length() - extension.length() - 1);
        }
        String title = UNDERSCORE_HYPHEN_PATTERN.matcher(baseName).replaceAll(" ").trim();
        bookEntity.getMetadata().setTitle(truncate(title, 1000));
    }

    private void saveComicMetadata(BookEntity bookEntity, ComicMetadata comicDto) {
        Long bookId = bookEntity.getId();
        if (bookId == null) {
            log.warn("Cannot save comic metadata - book ID is null for '{}'",
                    bookEntity.getPrimaryBookFile().getFileName());
            return;
        }

        ComicMetadataEntity comic = new ComicMetadataEntity();
        comic.setBookId(bookId);
        comic.setBookMetadata(bookEntity.getMetadata());
        comic.setIssueNumber(comicDto.getIssueNumber());
        comic.setVolumeName(comicDto.getVolumeName());
        comic.setVolumeNumber(comicDto.getVolumeNumber());
        comic.setStoryArc(comicDto.getStoryArc());
        comic.setStoryArcNumber(comicDto.getStoryArcNumber());
        comic.setAlternateSeries(comicDto.getAlternateSeries());
        comic.setAlternateIssue(comicDto.getAlternateIssue());
        comic.setImprint(comicDto.getImprint());
        comic.setFormat(comicDto.getFormat());
        comic.setBlackAndWhite(comicDto.getBlackAndWhite() != null ? comicDto.getBlackAndWhite() : Boolean.FALSE);
        comic.setManga(comicDto.getManga() != null ? comicDto.getManga() : Boolean.FALSE);
        comic.setReadingDirection(comicDto.getReadingDirection() != null ? comicDto.getReadingDirection() : "ltr");
        comic.setWebLink(comicDto.getWebLink());
        comic.setNotes(comicDto.getNotes());

        // Set on parent - relationships are populated in saveConnections(), which is handed the DTO
        bookEntity.getMetadata().setComicMetadata(comic);
    }
}

