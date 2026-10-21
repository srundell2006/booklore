package org.booklore.service.metadata.extractor;

import io.documentnode.epub4j.domain.Book;
import io.documentnode.epub4j.epub.EpubReader;
import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.model.FileHeader;
import org.apache.commons.lang3.StringUtils;
import org.booklore.util.SecureXmlUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * The EPUB cover lookup as it was before it shared one zip open with metadata extraction: epub4j reads the whole
 * book and every fallback opens the file again. Copied verbatim from EpubMetadataExtractor so tests can check the
 * new lookup returns exactly the same bytes.
 */
class LegacyEpubCoverExtractor {

    private static final Logger log = LoggerFactory.getLogger(LegacyEpubCoverExtractor.class);

    byte[] extractCover(File epubFile) {
        Book epub = null;
        io.documentnode.epub4j.domain.Resource coverImage = null;

        try (FileInputStream fis = new FileInputStream(epubFile)) {
            try {
                epub = new EpubReader().readEpub(fis);
                coverImage = epub.getCoverImage();
            } catch (NullPointerException e) {
                log.debug("epub4j threw NullPointerException (likely malformed NCX) in {}: {}", epubFile.getName(), e.getMessage());
            } catch (Exception e) {
                log.debug("epub4j failed to parse EPUB for cover extraction (will try fallbacks) in {}: {}", epubFile.getName(), e.getMessage());
            }

            // We fall back to reading the image based on the cover-image property.
            String coverHref = findCoverImageHrefInOpf(epubFile);
            if (coverHref != null) {
                byte[] image = extractFileFromZip(epubFile, coverHref);
                if (image != null) {
                    return image;
                }
            }

            // Fallback: search manifest for anything that looks like a cover
            if (coverImage == null) {
                String href = findManifestCoverByHeuristic(epubFile);
                if (href != null) {
                    byte[] data = extractFileFromZip(epubFile, href);
                    if (data != null) return data;
                }
            }

            if (coverImage == null && epub != null) {
                for (io.documentnode.epub4j.domain.Resource res : epub.getResources().getAll()) {
                    String id = res.getId();
                    String href = res.getHref();
                    if ((id != null && id.toLowerCase().contains("cover")) ||
                            (href != null && href.toLowerCase().contains("cover"))) {
                        if (res.getMediaType() != null && res.getMediaType().getName().startsWith("image")) {
                            coverImage = res;
                            break;
                        }
                    }
                }
            }

            // Absolute last resort: Scan ZIP entries directly
            if (coverImage == null) {
                byte[] data = findCoverInZipByHeuristic(epubFile);
                if (data != null) return data;
            }

            return (coverImage != null) ? coverImage.getData() : null;
        } catch (Exception e) {
            log.warn("Failed to extract cover from EPUB: {}", epubFile.getName(), e);
        }

        return null;
    }

    private String findManifestCoverByHeuristic(File epubFile) {
        try (ZipFile zip = new ZipFile(epubFile)) {
            DocumentBuilder builder = SecureXmlUtils.createSecureDocumentBuilder(true);

            FileHeader containerHdr = zip.getFileHeader("META-INF/container.xml");
            if (containerHdr == null) return null;

            try (InputStream cis = zip.getInputStream(containerHdr)) {
                Document containerDoc = builder.parse(cis);
                NodeList roots = containerDoc.getElementsByTagName("rootfile");
                if (roots.getLength() == 0) return null;

                String opfPath = ((Element) roots.item(0)).getAttribute("full-path");
                if (StringUtils.isBlank(opfPath)) return null;

                FileHeader opfHdr = zip.getFileHeader(opfPath);
                if (opfHdr == null) return null;

                try (InputStream in = zip.getInputStream(opfHdr)) {
                    Document doc = builder.parse(in);
                    NodeList manifestItems = doc.getElementsByTagName("item");

                    for (int i = 0; i < manifestItems.getLength(); i++) {
                        Element item = (Element) manifestItems.item(i);
                        String id = item.getAttribute("id");
                        String href = item.getAttribute("href");
                        String mediaType = item.getAttribute("media-type");

                        if ((id != null && id.toLowerCase().contains("cover")) || (href != null && href.toLowerCase().contains("cover"))) {
                            if (mediaType != null && mediaType.startsWith("image/")) {
                                String decodedHref = URLDecoder.decode(href, StandardCharsets.UTF_8);
                                return resolvePath(opfPath, decodedHref);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Heuristic manifest search failed: {}", e.getMessage());
        }
        return null;
    }

    private byte[] findCoverInZipByHeuristic(File epubFile) {
        try (ZipFile zip = new ZipFile(epubFile)) {
            List<FileHeader> fileHeaders = zip.getFileHeaders();
            for (FileHeader header : fileHeaders) {
                String name = header.getFileName().toLowerCase();
                if (name.contains("cover") && (name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png") || name.endsWith(".webp"))) {
                    try (InputStream is = zip.getInputStream(header)) {
                        return is.readAllBytes();
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Direct ZIP scan for cover failed: {}", e.getMessage());
        }
        return null;
    }

    private String findCoverImageHrefInOpf(File epubFile) {
        try (ZipFile zip = new ZipFile(epubFile)) {
            DocumentBuilder builder = SecureXmlUtils.createSecureDocumentBuilder(true);

            FileHeader containerHdr = zip.getFileHeader("META-INF/container.xml");
            if (containerHdr == null) return null;

            try (InputStream cis = zip.getInputStream(containerHdr)) {
                Document containerDoc = builder.parse(cis);
                NodeList roots = containerDoc.getElementsByTagName("rootfile");
                if (roots.getLength() == 0) return null;

                String opfPath = ((Element) roots.item(0)).getAttribute("full-path");
                if (StringUtils.isBlank(opfPath)) return null;

                FileHeader opfHdr = zip.getFileHeader(opfPath);
                if (opfHdr == null) return null;

                try (InputStream in = zip.getInputStream(opfHdr)) {
                    Document doc = builder.parse(in);
                    NodeList manifestItems = doc.getElementsByTagName("item");

                    for (int i = 0; i < manifestItems.getLength(); i++) {
                        Element item = (Element) manifestItems.item(i);
                        String properties = item.getAttribute("properties");
                        if (properties != null && properties.contains("cover-image")) {
                            String href = item.getAttribute("href");
                            String decodedHref = URLDecoder.decode(href, StandardCharsets.UTF_8);
                            return resolvePath(opfPath, decodedHref);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Failed to find cover image in OPF: {}", e.getMessage());
        }
        return null;
    }

    private String resolvePath(String opfPath, String href) {
        if (href == null || href.isEmpty()) return null;

        // If href is absolute within the zip (starts with /), return it without leading /
        if (href.startsWith("/")) return href.substring(1);

        int lastSlash = opfPath.lastIndexOf('/');
        String basePath = (lastSlash == -1) ? "" : opfPath.substring(0, lastSlash + 1);

        String combined = basePath + href;

        // Normalize path components to handle ".." and "."
        java.util.LinkedList<String> parts = new java.util.LinkedList<>();
        for (String part : combined.split("/")) {
            if ("..".equals(part)) {
                if (!parts.isEmpty()) parts.removeLast();
            } else if (!".".equals(part) && !part.isEmpty()) {
                parts.add(part);
            }
        }

        return String.join("/", parts);
    }

    private byte[] extractFileFromZip(File epubFile, String path) {
        try (ZipFile zip = new ZipFile(epubFile)) {
            FileHeader header = zip.getFileHeader(path);
            if (header == null) return null;
            try (InputStream is = zip.getInputStream(header)) {
                return is.readAllBytes();
            }
        } catch (Exception e) {
            log.warn("Failed to extract file {} from zip", path);
            return null;
        }
    }
}
