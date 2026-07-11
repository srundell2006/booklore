package org.booklore.service.metadata.extractor;

import lombok.extern.slf4j.Slf4j;
import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.model.FileHeader;
import org.booklore.util.SecureXmlUtils;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scans the first few HTML content documents inside an EPUB for ISBN-13 and ISBN-10
 * numbers that appear in the book text (copyright page, title page, etc.) but may
 * not be present in the OPF metadata.
 *
 * <p>Algorithm:
 * <ol>
 *   <li>Open the EPUB as a ZIP and resolve the OPF path via META-INF/container.xml.</li>
 *   <li>Parse the OPF spine to obtain the ordered list of content document hrefs.</li>
 *   <li>For each of the first {@code maxSpineItems} documents, extract plain text via
 *       Jsoup and search for ISBN candidates using a broad pattern.</li>
 *   <li>Validate each candidate with the appropriate checksum algorithm.</li>
 *   <li>Return the first valid ISBN-13; fall back to the first valid ISBN-10.</li>
 * </ol>
 */
@Slf4j
@Component
public class EpubIsbnScanner {

    /** Default number of spine items to scan (covers title page + copyright page). */
    public static final int DEFAULT_MAX_SPINE_ITEMS = 5;

    // Broad pattern: optional "ISBN" label, optional 10/13 qualifier, then a digit
    // sequence that may contain hyphens or spaces.  Capture group 1 is the raw candidate.
    private static final Pattern ISBN_CANDIDATE_PATTERN = Pattern.compile(
            "(?i)(?:isbn[- ]?(?:1[03][: ]?)?)?\\b((?:97[89][\\d -]{10}\\d|\\d[\\d -]{8}[\\dXx]))\\b"
    );

    // Strip everything except digits and the trailing X for ISBN-10
    private static final Pattern NON_DIGIT_PATTERN = Pattern.compile("[^\\dXx]");

    public record IsbnResult(String isbn13, String isbn10) {}

    /**
     * Scans the EPUB at {@code epubFile} using at most {@link #DEFAULT_MAX_SPINE_ITEMS}
     * spine items.
     */
    public Optional<IsbnResult> scan(File epubFile) {
        return scan(epubFile, DEFAULT_MAX_SPINE_ITEMS);
    }

    /**
     * Scans the EPUB at {@code epubFile} using at most {@code maxSpineItems} spine items.
     */
    public Optional<IsbnResult> scan(File epubFile, int maxSpineItems) {
        try (ZipFile zip = new ZipFile(epubFile)) {
            DocumentBuilder xmlBuilder = SecureXmlUtils.createSecureDocumentBuilder(true);

            String opfPath = resolveOpfPath(zip, xmlBuilder);
            if (opfPath == null) {
                log.debug("EpubIsbnScanner: no OPF path found in {}", epubFile.getName());
                return Optional.empty();
            }

            List<String> spineHrefs = resolveSpineHrefs(zip, xmlBuilder, opfPath);
            if (spineHrefs.isEmpty()) {
                log.debug("EpubIsbnScanner: empty spine in {}", epubFile.getName());
                return Optional.empty();
            }

            String foundIsbn13 = null;
            String foundIsbn10 = null;

            int limit = Math.min(maxSpineItems, spineHrefs.size());
            for (int i = 0; i < limit; i++) {
                String href = spineHrefs.get(i);
                String text = extractText(zip, href);
                if (text == null || text.isBlank()) continue;

                Matcher m = ISBN_CANDIDATE_PATTERN.matcher(text);
                while (m.find()) {
                    String raw = m.group(1);
                    String digits = NON_DIGIT_PATTERN.matcher(raw).replaceAll("").toUpperCase();

                    if (digits.length() == 13 && foundIsbn13 == null && isValidIsbn13(digits)) {
                        foundIsbn13 = digits;
                    } else if (digits.length() == 10 && foundIsbn10 == null && isValidIsbn10(digits)) {
                        foundIsbn10 = digits;
                    }
                    if (foundIsbn13 != null && foundIsbn10 != null) break;
                }
                if (foundIsbn13 != null && foundIsbn10 != null) break;
            }

            if (foundIsbn13 == null && foundIsbn10 == null) {
                return Optional.empty();
            }
            log.debug("EpubIsbnScanner: found isbn13={} isbn10={} in {}", foundIsbn13, foundIsbn10, epubFile.getName());
            return Optional.of(new IsbnResult(foundIsbn13, foundIsbn10));

        } catch (Exception e) {
            log.warn("EpubIsbnScanner: failed to scan {}: {}", epubFile.getName(), e.getMessage());
            return Optional.empty();
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private String resolveOpfPath(ZipFile zip, DocumentBuilder xmlBuilder) {
        try {
            FileHeader containerHdr = zip.getFileHeader("META-INF/container.xml");
            if (containerHdr == null) return null;
            try (InputStream is = zip.getInputStream(containerHdr)) {
                Document doc = xmlBuilder.parse(is);
                NodeList roots = doc.getElementsByTagName("rootfile");
                if (roots.getLength() == 0) return null;
                String path = ((Element) roots.item(0)).getAttribute("full-path");
                return path.isBlank() ? null : path;
            }
        } catch (Exception e) {
            log.debug("EpubIsbnScanner: could not resolve OPF path: {}", e.getMessage());
            return null;
        }
    }

    private List<String> resolveSpineHrefs(ZipFile zip, DocumentBuilder xmlBuilder, String opfPath) {
        List<String> hrefs = new ArrayList<>();
        try {
            FileHeader opfHdr = zip.getFileHeader(opfPath);
            if (opfHdr == null) return hrefs;

            try (InputStream is = zip.getInputStream(opfHdr)) {
                Document doc = xmlBuilder.parse(is);

                // Build idref → href map from manifest
                java.util.Map<String, String> manifestMap = new java.util.HashMap<>();
                NodeList items = doc.getElementsByTagName("item");
                for (int i = 0; i < items.getLength(); i++) {
                    Element item = (Element) items.item(i);
                    String id = item.getAttribute("id");
                    String href = item.getAttribute("href");
                    String mediaType = item.getAttribute("media-type");
                    if (!id.isBlank() && !href.isBlank()
                            && (mediaType.contains("html") || mediaType.contains("xml"))) {
                        manifestMap.put(id, href);
                    }
                }

                // Walk spine in order
                NodeList itemRefs = doc.getElementsByTagName("itemref");
                String opfDir = opfPath.contains("/") ? opfPath.substring(0, opfPath.lastIndexOf('/') + 1) : "";
                for (int i = 0; i < itemRefs.getLength(); i++) {
                    String idref = ((Element) itemRefs.item(i)).getAttribute("idref");
                    String href = manifestMap.get(idref);
                    if (href != null) {
                        hrefs.add(normalizePath(opfDir, href));
                    }
                }
            }
        } catch (Exception e) {
            log.debug("EpubIsbnScanner: could not resolve spine: {}", e.getMessage());
        }
        return hrefs;
    }

    private String extractText(ZipFile zip, String entryPath) {
        try {
            FileHeader hdr = zip.getFileHeader(entryPath);
            if (hdr == null) return null;
            try (InputStream is = zip.getInputStream(hdr)) {
                return Jsoup.parse(is, "UTF-8", "").text();
            }
        } catch (Exception e) {
            log.debug("EpubIsbnScanner: could not read entry {}: {}", entryPath, e.getMessage());
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

    // ── Checksum validation ───────────────────────────────────────────────────

    /**
     * ISBN-13: alternating weights 1 and 3; total mod 10 == 0.
     */
    static boolean isValidIsbn13(String digits) {
        if (digits == null || digits.length() != 13) return false;
        if (!digits.startsWith("978") && !digits.startsWith("979")) return false;
        int sum = 0;
        for (int i = 0; i < 13; i++) {
            char c = digits.charAt(i);
            if (!Character.isDigit(c)) return false;
            sum += (c - '0') * (i % 2 == 0 ? 1 : 3);
        }
        return sum % 10 == 0;
    }

    /**
     * ISBN-10: weights 10 down to 1; total mod 11 == 0.  Last char may be 'X' (=10).
     */
    static boolean isValidIsbn10(String digits) {
        if (digits == null || digits.length() != 10) return false;
        int sum = 0;
        for (int i = 0; i < 10; i++) {
            char c = digits.charAt(i);
            int val;
            if (i == 9 && c == 'X') {
                val = 10;
            } else if (Character.isDigit(c)) {
                val = c - '0';
            } else {
                return false;
            }
            sum += val * (10 - i);
        }
        return sum % 11 == 0;
    }
}
