package org.booklore.service.metadata.extractor;

import lombok.extern.slf4j.Slf4j;
import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.model.FileHeader;
import org.booklore.util.SecureXmlUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.select.Elements;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scans an EPUB for ISBN-13 and ISBN-10 numbers using three strategies, in order:
 *
 * <ol>
 *   <li><b>OPF dc:identifier</b> — checks every {@code <dc:identifier>} element in the
 *       package metadata for a valid ISBN (via {@code opf:scheme="ISBN"} attribute or
 *       a {@code urn:isbn:} prefix in the content).</li>
 *   <li><b>Copyright page via guide/landmarks</b> — uses the EPUB 2 {@code <guide>}
 *       ({@code type="copyright-page"}) or the EPUB 3 nav-document landmarks
 *       ({@code epub:type="copyright-page"}) to jump directly to the right page.</li>
 *   <li><b>First N spine items</b> — falls back to scanning the first
 *       {@link #DEFAULT_MAX_SPINE_ITEMS} HTML documents in reading order.</li>
 * </ol>
 */
@Slf4j
@Component
public class EpubIsbnScanner {

    /** Default number of spine items to scan during the fallback pass. */
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
     * Holds everything extracted from a single OPF parse pass so we only open
     * and parse the OPF file once.
     */
    private record OpfData(
            List<String> dcIdentifiers,      // raw content of every <dc:identifier>
            List<String> spineHrefs,         // resolved absolute ZIP paths, in spine order
            String copyrightPageHref,        // resolved ZIP path from guide (may be null)
            String navDocPath,               // resolved ZIP path of the EPUB 3 nav doc (may be null)
            List<String> extraManifestHrefs  // HTML files in manifest but NOT in spine, priority-sorted
    ) {}

    // Keywords that suggest a file is the copyright/title page — checked against the ZIP path
    private static final List<String> COPYRIGHT_PRIORITY_KEYWORDS = List.of(
            "copyright", "colophon", "imprint", "legal", "rights", "titlepage", "title-page",
            "frontmatter", "front-matter", "front_matter"
    );

    // ── Public API ────────────────────────────────────────────────────────────

    public Optional<IsbnResult> scan(File epubFile) {
        return scan(epubFile, DEFAULT_MAX_SPINE_ITEMS);
    }

    public Optional<IsbnResult> scan(File epubFile, int maxSpineItems) {
        try (ZipFile zip = new ZipFile(epubFile)) {
            DocumentBuilder xmlBuilder = SecureXmlUtils.createSecureDocumentBuilder(true);

            String opfPath = resolveOpfPath(zip, xmlBuilder);
            if (opfPath == null) {
                log.debug("EpubIsbnScanner: no OPF path in {}", epubFile.getName());
                return Optional.empty();
            }

            OpfData opf = parseOpf(zip, xmlBuilder, opfPath);

            // ── Strategy 1: OPF dc:identifier ────────────────────────────────
            IsbnResult fromIdentifiers = extractFromIdentifiers(opf.dcIdentifiers());
            if (fromIdentifiers != null) {
                log.debug("EpubIsbnScanner: found via dc:identifier in {}: isbn13={} isbn10={}",
                        epubFile.getName(), fromIdentifiers.isbn13(), fromIdentifiers.isbn10());
                return Optional.of(fromIdentifiers);
            }

            // ── Strategy 2: copyright page (guide / EPUB 3 landmarks) ────────
            // EPUB 2 guide is already in opf.copyrightPageHref(); for EPUB 3 we
            // need to parse the nav document.
            String copyrightHref = opf.copyrightPageHref();
            if (copyrightHref == null && opf.navDocPath() != null) {
                copyrightHref = findCopyrightHrefInNavDoc(zip, opf.navDocPath(), opfPath);
            }
            if (copyrightHref != null) {
                IsbnResult fromCopyright = scanSingleDoc(zip, copyrightHref);
                if (fromCopyright != null) {
                    log.debug("EpubIsbnScanner: found via copyright page in {}: isbn13={} isbn10={}",
                            epubFile.getName(), fromCopyright.isbn13(), fromCopyright.isbn10());
                    return Optional.of(fromCopyright);
                }
                log.debug("EpubIsbnScanner: copyright page {} had no ISBN in {}",
                        copyrightHref, epubFile.getName());
            }

            // ── Strategy 3: first N spine items ──────────────────────────────
            if (opf.spineHrefs().isEmpty()) {
                return Optional.empty();
            }
            String foundIsbn13 = null;
            String foundIsbn10 = null;
            int limit = Math.min(maxSpineItems, opf.spineHrefs().size());
            for (int i = 0; i < limit; i++) {
                String href = opf.spineHrefs().get(i);
                // Skip if already scanned as the copyright page
                if (href.equals(copyrightHref)) continue;

                String text = extractText(zip, href);
                if (text == null || text.isBlank()) continue;

                Matcher m = ISBN_CANDIDATE_PATTERN.matcher(text);
                while (m.find()) {
                    String digits = normalizeDigits(m.group(1));
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
                // ── Strategy 4: remaining manifest HTML (copyright-named first) ──
                for (String href : opf.extraManifestHrefs()) {
                    IsbnResult fromExtra = scanSingleDoc(zip, href);
                    if (fromExtra != null) {
                        if (fromExtra.isbn13() != null && foundIsbn13 == null) foundIsbn13 = fromExtra.isbn13();
                        if (fromExtra.isbn10() != null && foundIsbn10 == null) foundIsbn10 = fromExtra.isbn10();
                        if (foundIsbn13 != null && foundIsbn10 != null) break;
                    }
                }
            }

            if (foundIsbn13 == null && foundIsbn10 == null) {
                return Optional.empty();
            }
            log.debug("EpubIsbnScanner: found via spine scan in {}: isbn13={} isbn10={}",
                    epubFile.getName(), foundIsbn13, foundIsbn10);
            return Optional.of(new IsbnResult(foundIsbn13, foundIsbn10));

        } catch (Exception e) {
            log.warn("EpubIsbnScanner: failed to scan {}: {}", epubFile.getName(), e.getMessage());
            return Optional.empty();
        }
    }

    // ── Strategy helpers ──────────────────────────────────────────────────────

    /**
     * Tries to extract an ISBN from a list of raw {@code <dc:identifier>} values.
     * Handles {@code urn:isbn:9781234567890} prefixes and plain digit strings.
     */
    private IsbnResult extractFromIdentifiers(List<String> identifiers) {
        String isbn13 = null;
        String isbn10 = null;
        for (String raw : identifiers) {
            if (raw == null || raw.isBlank()) continue;

            // Strip common prefixes
            String cleaned = raw.trim();
            if (cleaned.toLowerCase().startsWith("urn:isbn:")) {
                cleaned = cleaned.substring("urn:isbn:".length());
            } else if (cleaned.toLowerCase().startsWith("isbn:")) {
                cleaned = cleaned.substring("isbn:".length()).trim();
            }

            // Run the broad pattern over the cleaned value so hyphenated ISBNs work
            Matcher m = ISBN_CANDIDATE_PATTERN.matcher(cleaned);
            while (m.find()) {
                String digits = normalizeDigits(m.group(1));
                if (digits.length() == 13 && isbn13 == null && isValidIsbn13(digits)) {
                    isbn13 = digits;
                } else if (digits.length() == 10 && isbn10 == null && isValidIsbn10(digits)) {
                    isbn10 = digits;
                }
            }
            if (isbn13 != null && isbn10 != null) break;
        }
        return (isbn13 != null || isbn10 != null) ? new IsbnResult(isbn13, isbn10) : null;
    }

    /**
     * Parses an EPUB 3 nav document (HTML) for a landmarks entry whose
     * {@code epub:type} is {@code copyright-page} and returns the resolved
     * ZIP path of the linked document.
     */
    private String findCopyrightHrefInNavDoc(ZipFile zip, String navDocPath, String opfPath) {
        try {
            FileHeader hdr = zip.getFileHeader(navDocPath);
            if (hdr == null) return null;
            try (InputStream is = zip.getInputStream(hdr)) {
                Document html = Jsoup.parse(is, "UTF-8", "");
                // Find <a epub:type="copyright-page"> or <a epub:type="...copyright-page...">
                Elements links = html.select("nav[epub\\:type~=landmarks] a[epub\\:type~=copyright-page]");
                if (links.isEmpty()) {
                    // Some publishers omit the namespace prefix
                    links = html.select("nav a[epub\\:type~=copyright-page]");
                }
                if (links.isEmpty()) return null;

                String href = links.first().attr("href");
                if (href.isBlank()) return null;

                // href is relative to the nav doc; resolve against nav doc's directory
                String navDir = navDocPath.contains("/")
                        ? navDocPath.substring(0, navDocPath.lastIndexOf('/') + 1)
                        : "";
                // Strip any fragment
                int hash = href.indexOf('#');
                if (hash >= 0) href = href.substring(0, hash);
                return href.isBlank() ? null : normalizePath(navDir, href);
            }
        } catch (Exception e) {
            log.debug("EpubIsbnScanner: error reading nav doc {}: {}", navDocPath, e.getMessage());
            return null;
        }
    }

    /** Scans a single ZIP entry and returns the first valid ISBN pair found, or null. */
    private IsbnResult scanSingleDoc(ZipFile zip, String entryPath) {
        String text = extractText(zip, entryPath);
        if (text == null || text.isBlank()) return null;

        String isbn13 = null;
        String isbn10 = null;
        Matcher m = ISBN_CANDIDATE_PATTERN.matcher(text);
        while (m.find()) {
            String digits = normalizeDigits(m.group(1));
            if (digits.length() == 13 && isbn13 == null && isValidIsbn13(digits)) {
                isbn13 = digits;
            } else if (digits.length() == 10 && isbn10 == null && isValidIsbn10(digits)) {
                isbn10 = digits;
            }
            if (isbn13 != null && isbn10 != null) break;
        }
        return (isbn13 != null || isbn10 != null) ? new IsbnResult(isbn13, isbn10) : null;
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
            log.debug("EpubIsbnScanner: could not resolve OPF path: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Parses the OPF file in one pass, extracting:
     * <ul>
     *   <li>All {@code <dc:identifier>} values</li>
     *   <li>Spine href list (resolved to absolute ZIP paths)</li>
     *   <li>Copyright page href from EPUB 2 {@code <guide>} (resolved, or null)</li>
     *   <li>Nav document ZIP path for EPUB 3 landmarks lookup (or null)</li>
     * </ul>
     */
    private OpfData parseOpf(ZipFile zip, DocumentBuilder xmlBuilder, String opfPath) {
        List<String> identifiers = new ArrayList<>();
        List<String> spineHrefs = new ArrayList<>();
        List<String> allManifestHrefs = new ArrayList<>();
        String copyrightPageHref = null;
        String navDocPath = null;

        try {
            FileHeader opfHdr = zip.getFileHeader(opfPath);
            if (opfHdr == null) return new OpfData(identifiers, spineHrefs, null, null, Collections.emptyList());

            try (InputStream is = zip.getInputStream(opfHdr)) {
                org.w3c.dom.Document doc = xmlBuilder.parse(is);
                String opfDir = opfPath.contains("/")
                        ? opfPath.substring(0, opfPath.lastIndexOf('/') + 1)
                        : "";

                // ── dc:identifier ─────────────────────────────────────────────
                // Try both namespaced and non-namespaced variants
                NodeList dcIds = doc.getElementsByTagNameNS("http://purl.org/dc/elements/1.1/", "identifier");
                if (dcIds.getLength() == 0) {
                    dcIds = doc.getElementsByTagName("dc:identifier");
                }
                for (int i = 0; i < dcIds.getLength(); i++) {
                    Element el = (Element) dcIds.item(i);
                    String scheme = el.getAttribute("opf:scheme");
                    String content = el.getTextContent().trim();
                    if (content.isBlank()) continue;

                    // Always include; extractFromIdentifiers will validate
                    // Prioritise explicit ISBN scheme entries
                    if ("ISBN".equalsIgnoreCase(scheme)) {
                        identifiers.add(0, content); // push to front
                    } else {
                        identifiers.add(content);
                    }
                }

                // ── manifest: build id→href map, find nav doc ─────────────────
                Map<String, String> manifestMap = new HashMap<>();
                NodeList items = doc.getElementsByTagName("item");
                for (int i = 0; i < items.getLength(); i++) {
                    Element item = (Element) items.item(i);
                    String id = item.getAttribute("id");
                    String href = item.getAttribute("href");
                    String mediaType = item.getAttribute("media-type");
                    String properties = item.getAttribute("properties");

                    if (!id.isBlank() && !href.isBlank()) {
                        if (mediaType.contains("html") || mediaType.contains("xml")) {
                            manifestMap.put(id, href);
                            // Track every HTML/XHTML item for the extra-manifest scan
                            allManifestHrefs.add(normalizePath(opfDir, href));
                        }
                        // EPUB 3 nav document
                        if (properties.contains("nav")) {
                            navDocPath = normalizePath(opfDir, href);
                        }
                    }
                }

                // ── spine ─────────────────────────────────────────────────────
                NodeList itemRefs = doc.getElementsByTagName("itemref");
                for (int i = 0; i < itemRefs.getLength(); i++) {
                    String idref = ((Element) itemRefs.item(i)).getAttribute("idref");
                    String href = manifestMap.get(idref);
                    if (href != null) {
                        spineHrefs.add(normalizePath(opfDir, href));
                    }
                }

                // ── EPUB 2 guide ──────────────────────────────────────────────
                NodeList refs = doc.getElementsByTagName("reference");
                for (int i = 0; i < refs.getLength(); i++) {
                    Element ref = (Element) refs.item(i);
                    String type = ref.getAttribute("type");
                    if ("copyright-page".equalsIgnoreCase(type)) {
                        String href = ref.getAttribute("href");
                        if (!href.isBlank()) {
                            // Strip fragment
                            int hash = href.indexOf('#');
                            if (hash >= 0) href = href.substring(0, hash);
                            copyrightPageHref = normalizePath(opfDir, href);
                        }
                        break;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("EpubIsbnScanner: error parsing OPF {}: {}", opfPath, e.getMessage());
        }

        // Build extra manifest list: all HTML not in spine, copyright-named items first
        Set<String> spineSet = new java.util.HashSet<>(spineHrefs);
        List<String> extraManifestHrefs = allManifestHrefs.stream()
                .filter(p -> !spineSet.contains(p) && !p.equals(navDocPath))
                .sorted(java.util.Comparator.comparingInt(this::manifestPriority))
                .collect(java.util.stream.Collectors.toList());

        return new OpfData(identifiers, spineHrefs, copyrightPageHref, navDocPath, extraManifestHrefs);
    }

    // ── ZIP/text helpers ──────────────────────────────────────────────────────

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

    private String normalizeDigits(String raw) {
        return NON_DIGIT_PATTERN.matcher(raw).replaceAll("").toUpperCase();
    }

    // ── Manifest priority helper ─────────────────────────────────────────────

    /**
     * Returns a sort key for an EPUB manifest entry path: lower = higher priority.
     * Files whose name contains a copyright/title-page keyword sort first.
     */
    private int manifestPriority(String path) {
        String lower = path.toLowerCase();
        for (int i = 0; i < COPYRIGHT_PRIORITY_KEYWORDS.size(); i++) {
            if (lower.contains(COPYRIGHT_PRIORITY_KEYWORDS.get(i))) return i;
        }
        return COPYRIGHT_PRIORITY_KEYWORDS.size();
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
