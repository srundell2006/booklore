package org.booklore.service.fileprocessor;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.RandomAccessReadBufferedFile;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.booklore.mapper.BookMapper;
import org.booklore.model.dto.BookMetadata;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.repository.BookAdditionalFileRepository;
import org.booklore.repository.BookRepository;
import org.booklore.service.book.BookCreatorService;
import org.booklore.service.fileprocessor.prepare.*;
import org.booklore.service.metadata.MetadataMatchService;
import org.booklore.service.metadata.extractor.PdfMetadataExtractor;
import org.booklore.service.metadata.sidecar.SidecarMetadataWriter;
import org.booklore.util.FileService;
import org.booklore.util.FileUtils;
import org.booklore.util.cover.RenderedCover;
import org.springframework.stereotype.Service;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.List;

import static org.booklore.util.FileService.truncate;

@Slf4j
@Service
public class PdfProcessor extends AbstractFileProcessor implements BookFileProcessor {

    // Only pages too small to fill the stored cover render this sharp; larger pages render just big enough
    private static final float MAX_COVER_DPI = 150f;
    // Heap PDFBox needs to render a page beyond the page's own raster
    private static final long RENDER_HEAP_BYTES = 64L * 1024 * 1024;

    private final PdfMetadataExtractor pdfMetadataExtractor;

    public PdfProcessor(BookRepository bookRepository,
                        BookAdditionalFileRepository bookAdditionalFileRepository,
                        BookCreatorService bookCreatorService,
                        BookMapper bookMapper,
                        FileService fileService,
                        MetadataMatchService metadataMatchService,
                        SidecarMetadataWriter sidecarMetadataWriter,
                        PdfMetadataExtractor pdfMetadataExtractor) {
        super(bookRepository, bookAdditionalFileRepository, bookCreatorService, bookMapper, fileService, metadataMatchService, sidecarMetadataWriter);
        this.pdfMetadataExtractor = pdfMetadataExtractor;
    }

    @Override
    protected ExtractedContent readContent(PrepareRequest request, FileFacts facts, FolderSnapshot folder,
                                           CoverMaker covers, PrepareContext ctx) {
        File pdfFile = request.path().toFile();
        BookMetadata metadata = null;
        RenderedCover cover = null;
        try (RandomAccessReadBufferedFile randomAccessRead = new RandomAccessReadBufferedFile(pdfFile);
             PDDocument pdf = Loader.loadPDF(randomAccessRead)) {
            // Metadata is read from the document loaded for the cover, so the file is only loaded once
            try {
                metadata = pdfMetadataExtractor.extractMetadata(pdf, pdfFile);
            } catch (Exception e) {
                log.warn("Failed to extract PDF metadata for '{}': {}", request.fileName(), e.getMessage());
            }
            ctx.checkpoint();
            long coverPixels = PdfMetadataExtractor.coverPixels(pdf.getPage(0), MAX_COVER_DPI);
            cover = covers.fromPixels(coverPixels, RENDER_HEAP_BYTES, () -> pdfMetadataExtractor.renderCover(pdf, MAX_COVER_DPI));
        } catch (PrepareAbandonedException | PrepareDeadlineException e) {
            throw e;
        } catch (OutOfMemoryError e) {
            // Note: Catching OOM is generally discouraged, but for batch processing
            // of potentially large/corrupted PDFs, we prefer graceful degradation
            // over crashing the entire service.
            log.error("Out of memory (heap space exhausted) while generating cover for '{}'. Skipping cover generation.", request.fileName());
            System.gc(); // Hint to JVM to reclaim memory
        } catch (NegativeArraySizeException e) {
            // This can appear on corrupted PDF, or PDF with such large images that the
            // initial memory buffer is already bigger than the entire JVM heap, therefore
            // it leads to NegativeArrayException (basically run out of memory, and overflows)
            log.warn("Corrupted PDF structure for '{}'. Skipping cover generation.", request.fileName());
        } catch (Exception e) {
            log.warn("Failed to generate cover for '{}': {}", request.fileName(), e.getMessage());
        }
        return new ExtractedContent(metadata, cover, null);
    }

    @Override
    public boolean generateCover(BookEntity bookEntity) {
        return generateCover(bookEntity, bookEntity.getPrimaryBookFile());
    }

    @Override
    public boolean generateCover(BookEntity bookEntity, BookFileEntity bookFile) {
        File pdfFile = new File(FileUtils.getBookFullPath(bookEntity, bookFile));
        try (RandomAccessReadBufferedFile randomAccessRead = new RandomAccessReadBufferedFile(pdfFile);
             PDDocument pdf = Loader.loadPDF(randomAccessRead)) {
            return generateCoverImageAndSave(bookEntity.getId(), pdf);
        } catch (OutOfMemoryError e) {
            // Note: Catching OOM is generally discouraged, but for batch processing
            // of potentially large/corrupted PDFs, we prefer graceful degradation
            // over crashing the entire service.
            log.error("Out of memory (heap space exhausted) while generating cover for '{}'. Skipping cover generation.", bookFile.getFileName());
            System.gc(); // Hint to JVM to reclaim memory
            return false;
        } catch (NegativeArraySizeException e) {
            // This can appear on corrupted PDF, or PDF with such large images that the
            // initial memory buffer is already bigger than the entire JVM heap, therefore
            // it leads to NegativeArrayException (basically run out of memory, and overflows)
            log.warn("Corrupted PDF structure for '{}'. Skipping cover generation.", bookFile.getFileName());
            return false;
        } catch (Exception e) {
            log.warn("Failed to generate cover for '{}': {}", bookFile.getFileName(), e.getMessage());
            return false;
        }
    }

    @Override
    public List<BookFileType> getSupportedTypes() {
        return List.of(BookFileType.PDF);
    }

    @Override
    protected void applyMetadata(BookEntity bookEntity, PreparedBookFile prepared) {
        BookMetadata extracted = prepared.metadata();
        if (extracted == null) return;
        try {
            if (StringUtils.isNotBlank(extracted.getTitle())) {
                bookEntity.getMetadata().setTitle(truncate(extracted.getTitle(), 1000));
            }
            if (StringUtils.isNotBlank(extracted.getSubtitle())) {
                bookEntity.getMetadata().setSubtitle(truncate(extracted.getSubtitle(), 1000));
            }
            if (StringUtils.isNotBlank(extracted.getSeriesName())) {
                bookEntity.getMetadata().setSeriesName(truncate(extracted.getSeriesName(), 1000));
            }
            if (extracted.getSeriesNumber() != null) {
                bookEntity.getMetadata().setSeriesNumber(extracted.getSeriesNumber());
            }
            if (extracted.getSeriesTotal() != null) {
                bookEntity.getMetadata().setSeriesTotal(extracted.getSeriesTotal());
            }
            if (extracted.getAuthors() != null) {
                bookCreatorService.addAuthorsToBook(extracted.getAuthors(), bookEntity);
            }
            if (StringUtils.isNotBlank(extracted.getPublisher())) {
                bookEntity.getMetadata().setPublisher(extracted.getPublisher());
            }
            if (StringUtils.isNotBlank(extracted.getDescription())) {
                bookEntity.getMetadata().setDescription(truncate(extracted.getDescription(), 5000));
            }
            if (extracted.getPublishedDate() != null) {
                bookEntity.getMetadata().setPublishedDate(extracted.getPublishedDate());
            }
            if (StringUtils.isNotBlank(extracted.getLanguage())) {
                bookEntity.getMetadata().setLanguage(extracted.getLanguage());
            }
            if (extracted.getPageCount() != null) {
                bookEntity.getMetadata().setPageCount(extracted.getPageCount());
            }
            
            // External IDs
            if (StringUtils.isNotBlank(extracted.getAsin())) {
                bookEntity.getMetadata().setAsin(extracted.getAsin());
            }
            if (StringUtils.isNotBlank(extracted.getGoogleId())) {
                bookEntity.getMetadata().setGoogleId(extracted.getGoogleId());
            }
            if (StringUtils.isNotBlank(extracted.getHardcoverId())) {
                bookEntity.getMetadata().setHardcoverId(extracted.getHardcoverId());
            }
            if (StringUtils.isNotBlank(extracted.getHardcoverBookId())) {
                bookEntity.getMetadata().setHardcoverBookId(extracted.getHardcoverBookId());
            }
            if (StringUtils.isNotBlank(extracted.getGoodreadsId())) {
                bookEntity.getMetadata().setGoodreadsId(extracted.getGoodreadsId());
            }
            if (StringUtils.isNotBlank(extracted.getComicvineId())) {
                bookEntity.getMetadata().setComicvineId(extracted.getComicvineId());
            }
            if (StringUtils.isNotBlank(extracted.getRanobedbId())) {
                bookEntity.getMetadata().setRanobedbId(extracted.getRanobedbId());
            }
            if (StringUtils.isNotBlank(extracted.getLubimyczytacId())) {
                bookEntity.getMetadata().setLubimyczytacId(extracted.getLubimyczytacId());
            }
            if (StringUtils.isNotBlank(extracted.getIsbn10())) {
                bookEntity.getMetadata().setIsbn10(extracted.getIsbn10());
            }
            if (StringUtils.isNotBlank(extracted.getIsbn13())) {
                bookEntity.getMetadata().setIsbn13(extracted.getIsbn13());
            }
            
            // Categories, moods, and tags
            if (extracted.getCategories() != null && !extracted.getCategories().isEmpty()) {
                bookCreatorService.addCategoriesToBook(extracted.getCategories(), bookEntity);
            }
            if (extracted.getMoods() != null && !extracted.getMoods().isEmpty()) {
                bookCreatorService.addMoodsToBook(extracted.getMoods(), bookEntity);
            }
            if (extracted.getTags() != null && !extracted.getTags().isEmpty()) {
                bookCreatorService.addTagsToBook(extracted.getTags(), bookEntity);
            }
            
            // Ratings
            if (extracted.getAmazonRating() != null) {
                bookEntity.getMetadata().setAmazonRating(extracted.getAmazonRating());
            }
            if (extracted.getGoodreadsRating() != null) {
                bookEntity.getMetadata().setGoodreadsRating(extracted.getGoodreadsRating());
            }
            if (extracted.getHardcoverRating() != null) {
                bookEntity.getMetadata().setHardcoverRating(extracted.getHardcoverRating());
            }
            if (extracted.getLubimyczytacRating() != null) {
                bookEntity.getMetadata().setLubimyczytacRating(extracted.getLubimyczytacRating());
            }
            if (extracted.getRanobedbRating() != null) {
                bookEntity.getMetadata().setRanobedbRating(extracted.getRanobedbRating());
            }
            if (extracted.getRating() != null) {
                bookEntity.getMetadata().setRating(extracted.getRating());
            }

        } catch (Exception e) {
            log.warn("Failed to extract PDF metadata for '{}': {}", bookEntity.getPrimaryBookFile().getFileName(), e.getMessage());
        }
    }

    private boolean generateCoverImageAndSave(Long bookId, PDDocument document) throws IOException {
        BufferedImage coverImage = null;
        try {
            coverImage = pdfMetadataExtractor.renderCover(document, MAX_COVER_DPI);
            return fileService.saveCoverImages(coverImage, bookId);
        } catch (OutOfMemoryError e) {
            log.error("Out of memory (heap space exhausted) while generating cover for bookId {}. Skipping cover generation.", bookId);
            System.gc(); // Hint to JVM to reclaim memory
            return false;
        } finally {
            if (coverImage != null) {
                coverImage.flush(); // Release native resources
            }
        }
    }
}
