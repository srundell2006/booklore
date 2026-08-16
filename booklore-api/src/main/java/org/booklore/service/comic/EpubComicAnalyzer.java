package org.booklore.service.comic;

import lombok.extern.slf4j.Slf4j;
import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.model.FileHeader;
import org.booklore.util.SecureXmlUtils;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Component;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Inspects the internal structure of an EPUB for comic/manga characteristics.
 *
 * <p>The strongest tell is EPUB 3 fixed-layout ({@code rendition:layout =
 * pre-paginated}), which reflowable prose never uses. Beyond that, a comic is
 * overwhelmingly image bytes with almost no extractable text, and carries
 * roughly one image per spine document — one page, one scan.
 */
@Slf4j
@Component
public class EpubComicAnalyzer {

    /** Spine documents sampled when measuring text density. */
    private static final int SAMPLE_DOCS = 8;

    /** Mean characters per sampled document below which the book reads as image-only. */
    private static final int TEXT_DENSITY_FLOOR = 220;

    /** Share of uncompressed archive bytes that must be images to count as image-dominated. */
    private static final double IMAGE_BYTE_RATIO = 0.85;

    public void analyze(File epubFile, ComicScoreCard card) {
        try (ZipFile zip = new ZipFile(epubFile)) {
            List<FileHeader> headers = zip.getFileHeaders();

            if (containsComicInfo(headers)) {
                card.add("COMIC_INFO_XML", 100, "Archive contains ComicInfo.xml");
                return;
            }

            DocumentBuilder xmlBuilder = SecureXmlUtils.createSecureDocumentBuilder(true);
            String opfPath = resolveOpfPath(zip, xmlBuilder);
            if (opfPath == null) {
                log.debug("EpubComicAnalyzer: no OPF in {}", epubFile.getName());
                return;
            }

            OpfSummary opf = parseOpf(zip, xmlBuilder, opfPath);

            if (opf.prePaginated) {
                card.add("EPUB_FIXED_LAYOUT", 30, "EPUB declares fixed-layout (rendition:layout = pre-paginated)");
            }

            double imageRatio = imageByteRatio(headers);
            if (imageRatio >= IMAGE_BYTE_RATIO) {
                card.add("EPUB_IMAGE_BYTES", 25,
                        String.format("%.0f%% of archive bytes are images", imageRatio * 100));
            }

            if (opf.spineCount > 0 && opf.imageCount >= opf.spineCount) {
                card.add("EPUB_IMAGE_PER_PAGE", 15,
                        String.format("%d images across %d spine documents (~1 per page)",
                                opf.imageCount, opf.spineCount));
            }

            int meanChars = meanTextPerDocument(zip, opf.spineHrefs);
            if (meanChars >= 0 && meanChars < TEXT_DENSITY_FLOOR) {
                card.add("EPUB_LOW_TEXT", 25,
                        String.format("Only ~%d characters of text per page", meanChars));
            }

        } catch (Exception e) {
            log.debug("EpubComicAnalyzer: failed on {}: {}", epubFile.getName(), e.getMessage());
        }
    }

    // ── internals ─────────────────────────────────────────────────────────────

    private record OpfSummary(boolean prePaginated, int imageCount, int spineCount, List<String> spineHrefs) {
    }

    private boolean containsComicInfo(List<FileHeader> headers) {
        for (FileHeader h : headers) {
            String name = h.getFileName();
            if (name == null) continue;
            String base = name.substring(name.lastIndexOf('/') + 1);
            if (base.equalsIgnoreCase("ComicInfo.xml")) {
                return true;
            }
        }
        return false;
    }

    private double imageByteRatio(List<FileHeader> headers) {
        long total = 0;
        long images = 0;
        for (FileHeader h : headers) {
            if (h.isDirectory()) continue;
            long size = Math.max(0, h.getUncompressedSize());
            total += size;
            String name = h.getFileName();
            if (name != null && isImageName(name.toLowerCase(Locale.ROOT))) {
                images += size;
            }
        }
        return total == 0 ? 0d : (double) images / total;
    }

    private boolean isImageName(String lower) {
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")
                || lower.endsWith(".gif") || lower.endsWith(".webp") || lower.endsWith(".avif")
                || lower.endsWith(".bmp");
    }

    private String resolveOpfPath(ZipFile zip, DocumentBuilder xmlBuilder) {
        try {
            FileHeader containerHdr = zip.getFileHeader("META-INF/container.xml");
            if (containerHdr == null) return null;
            try (InputStream is = zip.getInputStream(containerHdr)) {
                org.w3c.dom.Document doc = xmlBuilder.parse(is);
                NodeList roots = doc.getElementsByTagName("rootfile");
                if (roots.getLength() == 0) return null;
                String path = ((Element) roots.item(0)).getAttribute("full-path");
                return path.isBlank() ? null : path;
            }
        } catch (Exception e) {
            return null;
        }
    }

    private OpfSummary parseOpf(ZipFile zip, DocumentBuilder xmlBuilder, String opfPath) {
        boolean prePaginated = false;
        int imageCount = 0;
        List<String> spineHrefs = new ArrayList<>();

        try {
            FileHeader opfHdr = zip.getFileHeader(opfPath);
            if (opfHdr == null) return new OpfSummary(false, 0, 0, spineHrefs);

            String opfDir = opfPath.contains("/")
                    ? opfPath.substring(0, opfPath.lastIndexOf('/') + 1)
                    : "";

            try (InputStream is = zip.getInputStream(opfHdr)) {
                org.w3c.dom.Document doc = xmlBuilder.parse(is);

                // rendition:layout may appear as a <meta property=...> or a legacy name/content pair
                NodeList metas = doc.getElementsByTagName("meta");
                for (int i = 0; i < metas.getLength(); i++) {
                    Element meta = (Element) metas.item(i);
                    String property = meta.getAttribute("property");
                    String name = meta.getAttribute("name");
                    String content = meta.getAttribute("content");
                    String text = meta.getTextContent() == null ? "" : meta.getTextContent().trim();
                    if ("rendition:layout".equalsIgnoreCase(property) && text.equalsIgnoreCase("pre-paginated")) {
                        prePaginated = true;
                    }
                    if ("fixed-layout".equalsIgnoreCase(name) && "true".equalsIgnoreCase(content)) {
                        prePaginated = true;
                    }
                    if ("original-resolution".equalsIgnoreCase(name) && !content.isBlank()) {
                        prePaginated = true;
                    }
                }

                Map<String, String> manifestMap = new HashMap<>();
                NodeList items = doc.getElementsByTagName("item");
                for (int i = 0; i < items.getLength(); i++) {
                    Element item = (Element) items.item(i);
                    String id = item.getAttribute("id");
                    String href = item.getAttribute("href");
                    String mimeType = item.getAttribute("media-type");
                    if (mimeType != null && mimeType.startsWith("image/")) {
                        imageCount++;
                    }
                    if (!id.isBlank() && !href.isBlank() && mimeType != null
                            && (mimeType.contains("html") || mimeType.contains("xml"))) {
                        manifestMap.put(id, normalizePath(opfDir, href));
                    }
                }

                NodeList itemRefs = doc.getElementsByTagName("itemref");
                for (int i = 0; i < itemRefs.getLength(); i++) {
                    String idref = ((Element) itemRefs.item(i)).getAttribute("idref");
                    String href = manifestMap.get(idref);
                    if (href != null) spineHrefs.add(href);
                }
            }
        } catch (Exception e) {
            log.debug("EpubComicAnalyzer: OPF parse failed for {}: {}", opfPath, e.getMessage());
        }

        return new OpfSummary(prePaginated, imageCount, spineHrefs.size(), spineHrefs);
    }

    /** Mean characters of visible text across sampled spine documents, or -1 when unmeasurable. */
    private int meanTextPerDocument(ZipFile zip, List<String> spineHrefs) {
        if (spineHrefs.isEmpty()) return -1;

        // Skip the first entry: cover pages are image-only in ordinary books too.
        int start = spineHrefs.size() > 1 ? 1 : 0;
        int limit = Math.min(start + SAMPLE_DOCS, spineHrefs.size());
        long chars = 0;
        int sampled = 0;

        for (int i = start; i < limit; i++) {
            try {
                FileHeader hdr = zip.getFileHeader(spineHrefs.get(i));
                if (hdr == null) continue;
                try (InputStream is = zip.getInputStream(hdr)) {
                    chars += Jsoup.parse(is, "UTF-8", "").text().length();
                    sampled++;
                }
            } catch (Exception e) {
                log.trace("EpubComicAnalyzer: could not read {}", spineHrefs.get(i));
            }
        }
        return sampled == 0 ? -1 : (int) (chars / sampled);
    }

    private String normalizePath(String base, String href) {
        if (href.startsWith("/")) return href.substring(1);
        String combined = base + href;
        java.util.Deque<String> parts = new java.util.ArrayDeque<>();
        for (String seg : combined.split("/")) {
            if ("..".equals(seg)) { if (!parts.isEmpty()) parts.removeLast(); }
            else if (!seg.isEmpty() && !".".equals(seg)) parts.addLast(seg);
        }
        return String.join("/", parts);
    }
}
