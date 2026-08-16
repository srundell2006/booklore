package org.booklore.service.comic;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.File;

/**
 * Inspects a PDF for comic characteristics.
 *
 * <p>A scanned or exported comic page is a single full-bleed image with no text
 * layer. Ordinary PDF books — even image-heavy ones — carry an extractable text
 * layer and rarely place exactly one page-filling image on every page.
 */
@Slf4j
@Component
public class PdfComicAnalyzer {

    /** Pages sampled from the interior of the document. */
    private static final int SAMPLE_PAGES = 6;

    /** Mean characters per sampled page below which the PDF has effectively no text layer. */
    private static final int TEXT_FLOOR = 120;

    /** Share of sampled pages that must hold exactly one image to look page-per-scan. */
    private static final double SINGLE_IMAGE_PAGE_RATIO = 0.8;

    public void analyze(File pdfFile, ComicScoreCard card) {
        try (PDDocument doc = Loader.loadPDF(pdfFile)) {
            int pageCount = doc.getNumberOfPages();
            if (pageCount == 0) return;

            // Sample from the middle: front matter and covers are unrepresentative.
            int start = pageCount > SAMPLE_PAGES * 2 ? pageCount / 4 : 0;
            int end = Math.min(start + SAMPLE_PAGES, pageCount);

            long chars = 0;
            int singleImagePages = 0;
            int sampled = 0;

            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(false);

            for (int i = start; i < end; i++) {
                try {
                    stripper.setStartPage(i + 1);
                    stripper.setEndPage(i + 1);
                    String text = stripper.getText(doc);
                    chars += text == null ? 0 : text.trim().length();

                    if (countImages(doc.getPage(i)) == 1) {
                        singleImagePages++;
                    }
                    sampled++;
                } catch (Exception e) {
                    log.trace("PdfComicAnalyzer: page {} unreadable in {}", i, pdfFile.getName());
                }
            }

            if (sampled == 0) return;

            int meanChars = (int) (chars / sampled);
            boolean noTextLayer = meanChars < TEXT_FLOOR;
            if (noTextLayer) {
                card.add("PDF_NO_TEXT_LAYER", 30,
                        String.format("Only ~%d characters of text per page", meanChars));
            }

            double singleRatio = (double) singleImagePages / sampled;
            boolean pagePerImage = singleRatio >= SINGLE_IMAGE_PAGE_RATIO;
            if (pagePerImage) {
                card.add("PDF_PAGE_PER_IMAGE", 30,
                        String.format("%d of %d sampled pages are a single full-page image",
                                singleImagePages, sampled));
            }

            // Same reasoning as the EPUB consensus bonus: a PDF with no text layer
            // whose every page is one full-bleed image tops out at 60 otherwise,
            // which would send every scanned comic to the review queue.
            if (noTextLayer && pagePerImage) {
                card.add("PDF_STRUCTURAL_CONSENSUS", 25,
                        "No text layer and one full-page image per page — both agree");
            }

        } catch (Exception e) {
            log.debug("PdfComicAnalyzer: failed on {}: {}", pdfFile.getName(), e.getMessage());
        }
    }

    private int countImages(PDPage page) {
        PDResources resources = page.getResources();
        if (resources == null) return 0;
        int count = 0;
        for (COSName name : resources.getXObjectNames()) {
            try {
                if (resources.isImageXObject(name)) {
                    count++;
                    if (count > 1) return count;
                }
            } catch (Exception e) {
                // Malformed resource entry — ignore and keep counting.
            }
        }
        return count;
    }
}
