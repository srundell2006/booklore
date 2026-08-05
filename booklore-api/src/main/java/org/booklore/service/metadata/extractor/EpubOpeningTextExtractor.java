package org.booklore.service.metadata.extractor;

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
import java.util.Map;
import java.util.Optional;

/**
 * Extracts the opening plain text from an EPUB file by reading the first N
 * spine items in reading order.  Used as input for LLM-based title/author
 * identification when OPF metadata is absent or unreliable.
 */
@Slf4j
@Component
public class EpubOpeningTextExtractor {

    /** Number of spine items to scan by default. */
    public static final int DEFAULT_MAX_SPINE_ITEMS = 5;

    /** Maximum characters returned from a single extraction. */
    public static final int MAX_CHARS = 3000;

    public Optional<String> extract(File epubFile) {
        return extract(epubFile, DEFAULT_MAX_SPINE_ITEMS);
    }

    public Optional<String> extract(File epubFile, int maxSpineItems) {
        try (ZipFile zip = new ZipFile(epubFile)) {
            DocumentBuilder xmlBuilder = SecureXmlUtils.createSecureDocumentBuilder(true);

            String opfPath = resolveOpfPath(zip, xmlBuilder);
            if (opfPath == null) {
                log.debug("EpubOpeningTextExtractor: no OPF found in {}", epubFile.getName());
                return Optional.empty();
            }

            List<String> spineHrefs = parseSpineHrefs(zip, xmlBuilder, opfPath);
            if (spineHrefs.isEmpty()) {
                log.debug("EpubOpeningTextExtractor: no spine items in {}", epubFile.getName());
                return Optional.empty();
            }

            StringBuilder sb = new StringBuilder();
            int limit = Math.min(maxSpineItems, spineHrefs.size());
            for (int i = 0; i < limit && sb.length() < MAX_CHARS; i++) {
                String text = extractText(zip, spineHrefs.get(i));
                if (text != null && !text.isBlank()) {
                    if (sb.length() > 0) sb.append(' ');
                    sb.append(text);
                }
            }

            if (sb.isEmpty()) return Optional.empty();

            String result = sb.length() > MAX_CHARS ? sb.substring(0, MAX_CHARS) : sb.toString();
            return Optional.of(result);

        } catch (Exception e) {
            log.warn("EpubOpeningTextExtractor: failed to extract from {}: {}",
                    epubFile.getName(), e.getMessage());
            return Optional.empty();
        }
    }

    // ── OPF parsing ───────────────────────────────────────────────────────────

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
            log.debug("EpubOpeningTextExtractor: could not resolve OPF path: {}", e.getMessage());
            return null;
        }
    }

    private List<String> parseSpineHrefs(ZipFile zip, DocumentBuilder xmlBuilder, String opfPath) {
        List<String> hrefs = new ArrayList<>();
        try {
            FileHeader opfHdr = zip.getFileHeader(opfPath);
            if (opfHdr == null) return hrefs;

            String opfDir = opfPath.contains("/")
                    ? opfPath.substring(0, opfPath.lastIndexOf('/') + 1)
                    : "";

            try (InputStream is = zip.getInputStream(opfHdr)) {
                org.w3c.dom.Document doc = xmlBuilder.parse(is);

                // Build id → href map from manifest (HTML/XHTML items only)
                Map<String, String> manifestMap = new HashMap<>();
                NodeList items = doc.getElementsByTagName("item");
                for (int i = 0; i < items.getLength(); i++) {
                    Element item = (Element) items.item(i);
                    String id       = item.getAttribute("id");
                    String href     = item.getAttribute("href");
                    String mimeType = item.getAttribute("media-type");
                    if (!id.isBlank() && !href.isBlank()
                            && (mimeType.contains("html") || mimeType.contains("xml"))) {
                        manifestMap.put(id, href);
                    }
                }

                // Walk spine itemrefs in order
                NodeList itemRefs = doc.getElementsByTagName("itemref");
                for (int i = 0; i < itemRefs.getLength(); i++) {
                    String idref = ((Element) itemRefs.item(i)).getAttribute("idref");
                    String href  = manifestMap.get(idref);
                    if (href != null) hrefs.add(normalizePath(opfDir, href));
                }
            }
        } catch (Exception e) {
            log.debug("EpubOpeningTextExtractor: error parsing OPF {}: {}", opfPath, e.getMessage());
        }
        return hrefs;
    }

    // ── Text extraction helpers ────────────────────────────────────────────────

    private String extractText(ZipFile zip, String entryPath) {
        try {
            FileHeader hdr = zip.getFileHeader(entryPath);
            if (hdr == null) return null;
            try (InputStream is = zip.getInputStream(hdr)) {
                return Jsoup.parse(is, "UTF-8", "").text();
            }
        } catch (Exception e) {
            log.debug("EpubOpeningTextExtractor: could not read entry {}: {}", entryPath, e.getMessage());
            return null;
        }
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
