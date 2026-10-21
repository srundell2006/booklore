package org.booklore.service.metadata.extractor;

import io.documentnode.epub4j.domain.Book;
import io.documentnode.epub4j.domain.LazyResource;
import io.documentnode.epub4j.domain.Resource;
import io.documentnode.epub4j.domain.Resources;
import io.documentnode.epub4j.epub.EpubReader;
import io.documentnode.epub4j.epub.PackageDocumentReader;
import io.documentnode.epub4j.util.ResourceUtil;
import lombok.extern.slf4j.Slf4j;
import net.lingala.zip4j.exception.ZipException;
import net.lingala.zip4j.headers.HeaderReader;
import net.lingala.zip4j.headers.HeaderUtil;
import net.lingala.zip4j.io.inputstream.ZipInputStream;
import net.lingala.zip4j.model.FileHeader;
import net.lingala.zip4j.model.Zip4jConfig;
import net.lingala.zip4j.model.ZipModel;
import net.lingala.zip4j.util.InternalZipConstants;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import org.booklore.model.dto.BookMetadata;
import org.booklore.service.metadata.BookLoreMetadata;
import org.booklore.util.SecureXmlUtils;
import org.springframework.boot.configurationprocessor.json.JSONException;
import org.springframework.boot.configurationprocessor.json.JSONObject;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.regex.Pattern;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

@Slf4j
@Component
public class EpubMetadataExtractor implements FileMetadataExtractor {

    private static final Pattern YEAR_ONLY_PATTERN = Pattern.compile("^\\d{4}$");
    private static final String OPF_NS = "http://www.idpf.org/2007/opf";
    private static final String CONTAINER_PATH = "META-INF/container.xml";
    // Where epub4j looks for the OPF when container.xml does not name one
    private static final String EPUB4J_DEFAULT_OPF_PATH = "OEBPS/content.opf";

    private static final Pattern ISBN_SEPARATOR_PATTERN = Pattern.compile("[- ]");

    private static final Set<Integer> VALID_AGE_RATINGS = Set.of(0, 6, 10, 13, 16, 18, 21);

    private static final Map<String, BiConsumer<BookMetadata.BookMetadataBuilder, String>> CALIBRE_IDENTIFIER_PREFIXES = Map.of(
            "amazon", BookMetadata.BookMetadataBuilder::asin,
            "asin", BookMetadata.BookMetadataBuilder::asin,
            "mobi-asin", BookMetadata.BookMetadataBuilder::asin,
            "goodreads", BookMetadata.BookMetadataBuilder::goodreadsId,
            "google", BookMetadata.BookMetadataBuilder::googleId,
            "hardcover", BookMetadata.BookMetadataBuilder::hardcoverId,
            "hardcover_book", BookMetadata.BookMetadataBuilder::hardcoverBookId,
            "comicvine", BookMetadata.BookMetadataBuilder::comicvineId,
            "lubimyczytac", BookMetadata.BookMetadataBuilder::lubimyczytacId,
            "ranobedb", BookMetadata.BookMetadataBuilder::ranobedbId);

    private static final Map<String, BiConsumer<BookMetadata.BookMetadataBuilder, String>> CALIBRE_FIELD_MAPPINGS = Map.ofEntries(
            Map.entry("#subtitle", BookMetadata.BookMetadataBuilder::subtitle),
            Map.entry("#pagecount", (builder, value) -> safeParseInt(value, builder::pageCount)),
            Map.entry("#series_total", (builder, value) -> safeParseInt(value, builder::seriesTotal)),
            Map.entry("#amazon_rating", (builder, value) -> safeParseDouble(value, builder::amazonRating)),
            Map.entry("#amazon_review_count", (builder, value) -> safeParseInt(value, builder::amazonReviewCount)),
            Map.entry("#goodreads_rating", (builder, value) -> safeParseDouble(value, builder::goodreadsRating)),
            Map.entry("#goodreads_review_count", (builder, value) -> safeParseInt(value, builder::goodreadsReviewCount)),
            Map.entry("#hardcover_rating", (builder, value) -> safeParseDouble(value, builder::hardcoverRating)),
            Map.entry("#hardcover_review_count", (builder, value) -> safeParseInt(value, builder::hardcoverReviewCount)),
            Map.entry("#lubimyczytac_rating", (builder, value) -> safeParseDouble(value, builder::lubimyczytacRating)),
            Map.entry("#ranobedb_rating", (builder, value) -> safeParseDouble(value, builder::ranobedbRating)),
            Map.entry("#age_rating", (builder, value) -> safeParseInt(value, v -> {
                if (VALID_AGE_RATINGS.contains(v)) builder.ageRating(v);
            })),
            Map.entry("#content_rating", (builder, value) -> {
                String normalized = value.trim().toUpperCase();
                if (Set.of("EVERYONE", "TEEN", "MATURE", "ADULT", "EXPLICIT").contains(normalized)) {
                    builder.contentRating(normalized);
                }
            }));

    /**
     * The OPF named by container.xml: its path in the zip and its parsed document.
     */
    private record OpfPackage(String path, Document document) {
    }

    @Override
    public BookMetadata extractMetadata(File epubFile) {
        return extract(epubFile, true, false).metadata();
    }

    @Override
    public byte[] extractCover(File epubFile) {
        return extract(epubFile, false, true).cover();
    }

    /**
     * Metadata and cover from a single open of the file and a single parse of container.xml and the OPF,
     * each identical to what {@link #extractMetadata(File)} and {@link #extractCover(File)} return.
     */
    @Override
    public MetadataAndCover extractMetadataAndCover(File epubFile) {
        return extract(epubFile, true, true);
    }

    // Seam for tests that watch how the EPUB file is opened and read
    RandomAccessFile openFile(File epubFile) throws FileNotFoundException {
        return new RandomAccessFile(epubFile, "r");
    }

    private MetadataAndCover extract(File epubFile, boolean withMetadata, boolean withCover) {
        RandomAccessFile file;
        try {
            file = openFile(epubFile);
        } catch (FileNotFoundException e) {
            // zip4j reads a missing file as an empty zip, which has neither metadata nor cover and logs nothing
            if (!epubFile.exists()) return new MetadataAndCover(null, null);
            return unreadableZip(epubFile, e, withMetadata, withCover);
        }

        try (file) {
            EpubZip zip;
            try {
                zip = new EpubZip(file);
            } catch (IOException e) {
                return unreadableZip(epubFile, e, withMetadata, withCover);
            }

            // container.xml and OPF bytes as read, so epub4j's cover lookup does not read them a second time
            Map<FileHeader, byte[]> entriesRead = new IdentityHashMap<>();
            OpfPackage opf = null;
            BookMetadata metadata = null;
            try {
                opf = readOpfPackage(zip, entriesRead);
                if (withMetadata && opf != null) {
                    metadata = parseMetadata(opf.document(), epubFile);
                }
            } catch (Exception e) {
                if (withMetadata) {
                    log.error("Failed to read metadata from EPUB file {}: {}", epubFile.getName(), e.getMessage(), e);
                } else {
                    log.debug("Failed to read OPF from EPUB file {}: {}", epubFile.getName(), e.getMessage());
                }
            }

            byte[] cover = withCover ? findCover(zip, opf, entriesRead) : null;
            return new MetadataAndCover(metadata, cover);
        } catch (Exception e) {
            log.error("Failed to read EPUB file {}: {}", epubFile.getName(), e.getMessage(), e);
            return new MetadataAndCover(null, null);
        }
    }

    /**
     * A file that cannot be opened or whose central directory zip4j cannot read: no metadata, but epub4j reads entries
     * in sequence and may still get through to a cover.
     */
    private MetadataAndCover unreadableZip(File epubFile, IOException e, boolean withMetadata, boolean withCover) {
        if (withMetadata) {
            log.error("Failed to read metadata from EPUB file {}: {}", epubFile.getName(), e.getMessage(), e);
        }
        return new MetadataAndCover(null, withCover ? findCoverWithFullEpub4jRead(epubFile) : null);
    }

    /**
     * Finds the OPF through container.xml and parses it. Null when container.xml or the OPF is missing.
     */
    private OpfPackage readOpfPackage(EpubZip zip, Map<FileHeader, byte[]> entriesRead) throws Exception {
        DocumentBuilder builder = SecureXmlUtils.createSecureDocumentBuilder(true);
        FileHeader containerHdr = zip.header(CONTAINER_PATH);
        if (containerHdr == null) return null;

        Document containerDoc = builder.parse(new ByteArrayInputStream(readEntry(zip, containerHdr, entriesRead)));
        NodeList roots = containerDoc.getElementsByTagName("rootfile");
        if (roots.getLength() == 0) return null;

        String opfPath = ((Element) roots.item(0)).getAttribute("full-path");
        if (StringUtils.isBlank(opfPath)) return null;

        FileHeader opfHdr = zip.header(opfPath);
        if (opfHdr == null) return null;

        return new OpfPackage(opfPath, builder.parse(new ByteArrayInputStream(readEntry(zip, opfHdr, entriesRead))));
    }

    private static byte[] readEntry(EpubZip zip, FileHeader header, Map<FileHeader, byte[]> entriesRead) throws IOException {
        byte[] data = zip.read(header);
        entriesRead.put(header, data);
        return data;
    }

    private BookMetadata parseMetadata(Document doc, File epubFile) {
        Element metadata = (Element) doc.getElementsByTagNameNS("*", "metadata").item(0);
        if (metadata == null) return null;

        BookMetadata.BookMetadataBuilder builderMeta = BookMetadata.builder();
        Set<String> categories = new HashSet<>();
        Set<String> moods = new HashSet<>();
        Set<String> tags = new HashSet<>();

        boolean seriesFound = false;
        boolean seriesIndexFound = false;

        NodeList children = metadata.getChildNodes();

        Map<String, String> creatorsById = new HashMap<>();
        Map<String, String> creatorRoleById = new HashMap<>();
        Map<String, List<String>> creatorsByRole = new HashMap<>();
        creatorsByRole.put("aut", new ArrayList<>());

        Map<String, String> titlesById = new HashMap<>();
        Map<String, String> titleTypeById = new HashMap<>();

        for (int i = 0; i < children.getLength(); i++) {
            if (!(children.item(i) instanceof Element el)) continue;

            String tag = el.getLocalName();
            String text = el.getTextContent().trim();

            switch (tag) {
                case "title" -> {
                    String id = el.getAttribute("id");
                    if (StringUtils.isNotBlank(id)) {
                        titlesById.put(id, text);
                    } else {
                        builderMeta.title(text);
                    }
                }
                case "meta" -> {
                    String prop = el.getAttribute("property").trim();
                    String name = el.getAttribute("name").trim();
                    String refines = el.getAttribute("refines").trim();
                    String content = el.hasAttribute("content") ? el.getAttribute("content").trim() : text;

                    if ("title-type".equals(prop) && StringUtils.isNotBlank(refines)) {
                        titleTypeById.put(refines.substring(1), content.toLowerCase());
                    }

                    if ("role".equals(prop) && StringUtils.isNotBlank(refines)) {
                       creatorRoleById.put(refines.substring(1), content.toLowerCase());
                    }

                    if (!seriesFound && ((BookLoreMetadata.NS_PREFIX + ":series").equals(prop) || "calibre:series".equals(name) || "belongs-to-collection".equals(prop))) {
                        builderMeta.seriesName(content);
                        seriesFound = true;
                    }
                    if (!seriesIndexFound && ((BookLoreMetadata.NS_PREFIX + ":series_index").equals(prop) || "calibre:series_index".equals(name) || "group-position".equals(prop))) {
                        try {
                            builderMeta.seriesNumber(Float.parseFloat(content));
                            seriesIndexFound = true;
                        } catch (NumberFormatException ignored) {
                        }
                    }

                    if ("calibre:pages".equals(name) || "pagecount".equals(name) || "schema:pagecount".equals(prop) || "media:pagecount".equals(prop) || (BookLoreMetadata.NS_PREFIX + ":page_count").equals(prop)) {
                        safeParseInt(content, builderMeta::pageCount);
                    } else if ("calibre:user_metadata:#pagecount".equals(name)) {
                        try {
                            JSONObject jsonroot = new JSONObject(content);
                            Object value = jsonroot.opt("#value#");
                            safeParseInt(String.valueOf(value), builderMeta::pageCount);
                        } catch (JSONException ignored) {
                        }
                    } else if ("calibre:user_metadata".equals(prop)) {
                        try {
                            extractCalibreUserMetadata(new JSONObject(content), builderMeta, moods, tags);
                        } catch (JSONException e) {
                            log.warn("Failed to parse Calibre user_metadata JSON: {}", e.getMessage());
                        }
                    }



                    String key = StringUtils.isNotBlank(prop) ? prop : name;

                    if (key.equals(BookLoreMetadata.NS_PREFIX + ":asin")) builderMeta.asin(content);
                    else if (key.equals(BookLoreMetadata.NS_PREFIX + ":goodreads_id")) builderMeta.goodreadsId(content);
                    else if (key.equals(BookLoreMetadata.NS_PREFIX + ":comicvine_id")) builderMeta.comicvineId(content);
                    else if (key.equals(BookLoreMetadata.NS_PREFIX + ":ranobedb_id")) builderMeta.ranobedbId(content);
                    else if (key.equals(BookLoreMetadata.NS_PREFIX + ":hardcover_id")) builderMeta.hardcoverId(content);
                    else if (key.equals(BookLoreMetadata.NS_PREFIX + ":google_books_id")) builderMeta.googleId(content);
                    else if (key.equals(BookLoreMetadata.NS_PREFIX + ":lubimyczytac_id")) builderMeta.lubimyczytacId(content);
                    else if (key.equals(BookLoreMetadata.NS_PREFIX + ":page_count")) safeParseInt(content, builderMeta::pageCount);
                    else if (key.equals(BookLoreMetadata.NS_PREFIX + ":subtitle")) builderMeta.subtitle(content);
                    else if (key.equals(BookLoreMetadata.NS_PREFIX + ":series_total")) safeParseInt(content, builderMeta::seriesTotal);
                    else if (key.equals(BookLoreMetadata.NS_PREFIX + ":rating")) { /* Generic rating not supported */ }
                    else if (key.equals(BookLoreMetadata.NS_PREFIX + ":amazon_rating")) safeParseDouble(content, builderMeta::amazonRating);
                    else if (key.equals(BookLoreMetadata.NS_PREFIX + ":amazon_review_count")) safeParseInt(content, builderMeta::amazonReviewCount);
                    else if (key.equals(BookLoreMetadata.NS_PREFIX + ":goodreads_rating")) safeParseDouble(content, builderMeta::goodreadsRating);
                    else if (key.equals(BookLoreMetadata.NS_PREFIX + ":goodreads_review_count")) safeParseInt(content, builderMeta::goodreadsReviewCount);
                    else if (key.equals(BookLoreMetadata.NS_PREFIX + ":hardcover_rating")) safeParseDouble(content, builderMeta::hardcoverRating);
                    else if (key.equals(BookLoreMetadata.NS_PREFIX + ":hardcover_review_count")) safeParseInt(content, builderMeta::hardcoverReviewCount);
                    else if (key.equals(BookLoreMetadata.NS_PREFIX + ":lubimyczytac_rating")) safeParseDouble(content, builderMeta::lubimyczytacRating);
                    else if (key.equals(BookLoreMetadata.NS_PREFIX + ":ranobedb_rating")) safeParseDouble(content, builderMeta::ranobedbRating);
                    else if (key.equals(BookLoreMetadata.NS_PREFIX + ":age_rating")) safeParseInt(content, v -> { if (VALID_AGE_RATINGS.contains(v)) builderMeta.ageRating(v); });
                    else if (key.equals(BookLoreMetadata.NS_PREFIX + ":content_rating")) builderMeta.contentRating(content);
                    else if (key.equals(BookLoreMetadata.NS_PREFIX + ":moods")) {
                        if (StringUtils.isNotBlank(content)) {
                            extractSetField(content, moods);
                        }
                    }
                    else if (key.equals(BookLoreMetadata.NS_PREFIX + ":tags")) {
                        if (StringUtils.isNotBlank(content)) {
                            extractSetField(content, tags);
                        }
                    }
                }
                case "creator" -> {
                    String role = el.getAttributeNS(OPF_NS, "role");
                    if (StringUtils.isNotBlank(role)) {
                        creatorsByRole.computeIfAbsent(role, k -> new ArrayList<>()).add(text);
                    } else {
                        String id = el.getAttribute("id");
                        if (StringUtils.isNotBlank(id)) {
                            creatorsById.put(id, text);
                        } else {
                            creatorsByRole.get("aut").add(text);
                        }
                    }
                }
                case "subject" -> categories.add(text);
                case "description" -> builderMeta.description(text);
                case "publisher" -> builderMeta.publisher(text);
                case "language" -> builderMeta.language(text);
                case "identifier" -> {
                    String scheme = el.getAttributeNS(OPF_NS, "scheme").toUpperCase();
                    String textLower = text.toLowerCase();

                    // Parse URN format: urn:scheme:value
                    String value = text;
                    String urnScheme = null;
                    if (textLower.startsWith("urn:")) {
                        String[] parts = text.split(":", 3);
                        if (parts.length >= 3) {
                            urnScheme = parts[1].toUpperCase();
                            value = parts[2];
                        }
                    } else if (textLower.startsWith("isbn:")) {
                        value = text.substring(5);
                        urnScheme = "ISBN";
                    }

                    // Use URN scheme if opf:scheme is not present
                    if (scheme.isEmpty() && urnScheme != null) {
                        scheme = urnScheme;
                    }

                    if (!scheme.isEmpty()) {
                        switch (scheme) {
                            case "ISBN", "ISBN10", "ISBN13" -> {
                                String cleanValue = ISBN_SEPARATOR_PATTERN.matcher(value).replaceAll("");
                                if (cleanValue.length() == 13) builderMeta.isbn13(value);
                                else if (cleanValue.length() == 10) builderMeta.isbn10(value);
                            }
                            case "GOODREADS" -> builderMeta.goodreadsId(value);
                            case "COMICVINE" -> builderMeta.comicvineId(value);
                            case "RANOBEDB" -> builderMeta.ranobedbId(value);
                            case "GOOGLE" -> builderMeta.googleId(value);
                            case "AMAZON" -> builderMeta.asin(value);
                            case "HARDCOVER" -> builderMeta.hardcoverId(value);
                            case "HARDCOVERBOOK", "HARDCOVER_BOOK_ID" -> builderMeta.hardcoverBookId(value);
                            case "LUBIMYCZYTAC" -> builderMeta.lubimyczytacId(value);
                        }
                    } else {
                        // Handle Calibre's prefix:value format (e.g., amazon:B09XXX, goodreads:123)
                        int colonIdx = text.indexOf(':');
                        if (colonIdx > 0) {
                            String prefix = text.substring(0, colonIdx).toLowerCase();
                            String val = text.substring(colonIdx + 1).trim();
                            if (!"calibre".equals(prefix) && !"uuid".equals(prefix)) {
                                BiConsumer<BookMetadata.BookMetadataBuilder, String> setter = CALIBRE_IDENTIFIER_PREFIXES.get(prefix);
                                if (setter != null) {
                                    setter.accept(builderMeta, val);
                                }
                            }
                        }
                    }
                }
                case "date" -> {
                    LocalDate parsed = parseDate(text);
                    if (parsed != null) builderMeta.publishedDate(parsed);
                }
            }
        }

        for (Map.Entry<String, String> entry : titlesById.entrySet()) {
            String id = entry.getKey();
            String value = entry.getValue();
            String type = titleTypeById.getOrDefault(id, "main");
            if ("main".equals(type)) builderMeta.title(value);
            else if ("subtitle".equals(type)) builderMeta.subtitle(value);
        }

        if (builderMeta.build().getPublishedDate() == null) {
            for (int i = 0; i < children.getLength(); i++) {
                if (!(children.item(i) instanceof Element el)) continue;
                if (!"meta".equals(el.getLocalName())) continue;
                String prop = el.getAttribute("property").trim().toLowerCase();
                String content = el.hasAttribute("content") ? el.getAttribute("content").trim() : el.getTextContent().trim();
                if ("dcterms:modified".equals(prop)) {
                    LocalDate parsed = parseDate(content);
                    if (parsed != null) {
                        builderMeta.publishedDate(parsed);
                        break;
                    }
                }
            }
        }

        for (Map.Entry<String, String> entry : creatorsById.entrySet()) {
            String id = entry.getKey();
            String value = entry.getValue();
            String role = creatorRoleById.getOrDefault(id, "aut");
            creatorsByRole.computeIfAbsent(role, k -> new ArrayList<>()).add(value);
        }

        builderMeta.authors(creatorsByRole.get("aut"));

        if (!moods.isEmpty()) builderMeta.moods(moods);
        if (!tags.isEmpty()) builderMeta.tags(tags);

        // Remove moods and tags from categories to ensure strict separation
        categories.removeAll(moods);
        categories.removeAll(tags);

        builderMeta.categories(categories);

        BookMetadata extractedMetadata = builderMeta.build();

        if (StringUtils.isBlank(extractedMetadata.getTitle())) {
            builderMeta.title(FilenameUtils.getBaseName(epubFile.getName()));
            extractedMetadata = builderMeta.build();
        }

        return extractedMetadata;
    }

    private static void safeParseInt(String value, java.util.function.IntConsumer setter) {
        try {
            setter.accept(Integer.parseInt(value));
        } catch (NumberFormatException ignored) {
        }
    }

    private static void safeParseDouble(String value, java.util.function.DoubleConsumer setter) {
        try {
            setter.accept(Double.parseDouble(value));
        } catch (NumberFormatException ignored) {
        }
    }
    
    private void extractSetField(String value, Set<String> targetSet) {
        if (StringUtils.isNotBlank(value)) {
            targetSet.addAll(parseJsonArrayOrCsv(value));
        }
    }

    private void extractCalibreUserMetadata(JSONObject userMetadata, BookMetadata.BookMetadataBuilder builder,
                                             Set<String> moodsSet, Set<String> tagsSet) {
        Iterator<String> keys = userMetadata.keys();
        while (keys.hasNext()) {
            String fieldName = keys.next();
            try {
                JSONObject fieldObj = userMetadata.optJSONObject(fieldName);
                if (fieldObj == null) continue;

                Object rawValue = fieldObj.opt("#value#");
                if (rawValue == null) continue;

                String value = String.valueOf(rawValue).trim();
                if (value.isEmpty() || "null".equals(value)) continue;

                if ("#moods".equals(fieldName)) {
                    extractSetField(value, moodsSet);
                } else if ("#extra_tags".equals(fieldName)) {
                    extractSetField(value, tagsSet);
                } else {
                    BiConsumer<BookMetadata.BookMetadataBuilder, String> mapper = CALIBRE_FIELD_MAPPINGS.get(fieldName);
                    if (mapper != null) {
                        mapper.accept(builder, value);
                    }
                }
            } catch (Exception e) {
                log.debug("Failed to extract Calibre field '{}': {}", fieldName, e.getMessage());
            }
        }
    }

    /**
     * Parses a string that may be either a JSON array (e.g., ["item1", "item2"]) or a CSV (item1, item2).
     * Returns a Set of parsed values.
     */
    private Set<String> parseJsonArrayOrCsv(String content) {
        if (StringUtils.isBlank(content)) {
            return new HashSet<>();
        }
        
        content = content.trim();
        
        // Check if it looks like a JSON array
        if (content.startsWith("[") && content.endsWith("]")) {
            // Remove brackets
            String inner = content.substring(1, content.length() - 1).trim();
            if (inner.isEmpty()) {
                return new HashSet<>();
            }

            // Split by comma and parse each quoted item
            return Arrays.stream(inner.split(","))
                    .map(String::trim)
                    .map(s -> {
                        // Remove surrounding quotes if present
                        if (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2) {
                            return s.substring(1, s.length() - 1);
                        }
                        return s;
                    })
                    .filter(StringUtils::isNotBlank)
                    .collect(Collectors.toSet());
        }

        // Fallback to CSV parsing
        return Arrays.stream(content.split(","))
                .map(String::trim)
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.toSet());
    }

    private LocalDate parseDate(String value) {
        if (StringUtils.isBlank(value)) return null;

        value = value.trim();

        // Check for year-only format first (e.g., "2024") - common in EPUB metadata
        if (YEAR_ONLY_PATTERN.matcher(value).matches()) {
            int year = Integer.parseInt(value);
            if (year >= 1 && year <= 9999) {
                return LocalDate.of(year, 1, 1);
            }
        }

        try {
            return LocalDate.parse(value);
        } catch (Exception ignored) {
        }

        try {
            return OffsetDateTime.parse(value).toLocalDate();
        } catch (Exception ignored) {
        }

        // Try parsing first 10 characters for ISO date format with extra content
        if (value.length() >= 10) {
            try {
                return LocalDate.parse(value.substring(0, 10));
            } catch (Exception ignored) {
            }
        }

        log.warn("Failed to parse date from string: {}", value);
        return null;
    }

    /**
     * Cover precedence, unchanged from when every lookup opened the file itself and epub4j read the whole book:
     * <ol>
     *   <li>the manifest item whose properties contain "cover-image" (EPUB3);</li>
     *   <li>epub4j's cover image (EPUB2 meta name="cover" or guide reference type="cover", bitmap images only);</li>
     *   <li>a manifest item whose id or href contains "cover" and whose media type is an image;</li>
     *   <li>an epub4j resource whose id or href contains "cover" and whose media type is an image;</li>
     *   <li>a zip entry whose name contains "cover" and ends in an image extension.</li>
     * </ol>
     * Steps 3 to 5 only run when epub4j found no cover. Everything works off the zip already open: steps 1 and 3 use
     * the OPF already parsed, and epub4j's decision comes from its own OPF reader run over lazy resources, so beyond
     * container.xml and the OPF only cover candidates are read.
     */
    private byte[] findCover(EpubZip zip, OpfPackage opf, Map<FileHeader, byte[]> entriesRead) {
        byte[] image = extractFileFromZip(zip, findCoverImageHrefInOpf(opf));
        if (image != null) return image;

        Book epub = readPackageWithEpub4j(zip, entriesRead);
        if (epub != null && epub.getCoverImage() != null) {
            image = readResource(epub.getCoverImage());
            if (image != null) return image;
            // A full epub4j read fails outright on an unreadable entry, which leaves epub4j out of the remaining steps
            epub = null;
        }

        image = extractFileFromZip(zip, findManifestCoverByHeuristic(opf));
        if (image != null) return image;

        if (epub != null) {
            Resource resource = findCoverResourceByHeuristic(epub);
            if (resource != null) {
                image = readResource(resource);
                if (image != null) return image;
            }
        }

        return findCoverInZipByHeuristic(zip);
    }

    /**
     * Last resort for a file that cannot be opened or whose central directory zip4j cannot read: epub4j's full read
     * of every entry, then the steps of {@link #findCover} that do not need zip4j.
     */
    private byte[] findCoverWithFullEpub4jRead(File epubFile) {
        Book epub;
        try (FileInputStream fis = new FileInputStream(epubFile)) {
            epub = new EpubReader().readEpub(fis);
        } catch (Exception e) {
            log.debug("epub4j failed to parse EPUB for cover extraction in {}: {}", epubFile.getName(), e.getMessage());
            return null;
        }
        Resource resource = epub.getCoverImage() != null ? epub.getCoverImage() : findCoverResourceByHeuristic(epub);
        return resource != null ? readResource(resource) : null;
    }

    /**
     * What epub4j's EpubReader.readEpub(Resources, Book) builds, minus the NCX table of contents (which cannot change
     * the cover image or the resources), over resources that read from the open zip only when asked for their data.
     * Null where epub4j would have thrown.
     */
    private Book readPackageWithEpub4j(EpubZip zip, Map<FileHeader, byte[]> entriesRead) {
        try {
            Resources resources = new Resources();
            for (FileHeader header : zip.headers()) {
                String name = header.getFileName();
                // epub4j's streaming read skips directories by name, as zip local headers carry no attributes
                if (name.endsWith("/") || name.endsWith("\\")) continue;
                byte[] data = entriesRead.get(header);
                resources.add(data != null
                        ? new Resource(data, name)
                        : new LazyResource(href -> new ByteArrayInputStream(zip.read(header)), name));
            }
            resources.remove("mimetype");
            Resource opfResource = resources.remove(findOpfPathAsEpub4j(resources));

            Book book = new Book();
            try {
                PackageDocumentReader.read(opfResource, new EpubReader(), book, resources);
            } catch (Exception e) {
                // epub4j logs this and keeps whatever it had read
                log.debug("epub4j could not read the OPF: {}", e.getMessage());
            }
            return book;
        } catch (Exception e) {
            log.debug("epub4j failed to read EPUB resources for cover extraction: {}", e.getMessage());
            return null;
        }
    }

    /**
     * epub4j's OPF lookup (private in EpubReader): the first rootfile under rootfiles, else OEBPS/content.opf.
     * It can differ from {@link #readOpfPackage} on a missing or malformed container.xml.
     */
    private static String findOpfPathAsEpub4j(Resources resources) {
        String opfPath = null;
        Resource container = resources.remove(CONTAINER_PATH);
        if (container != null) {
            try {
                Element rootfiles = (Element) ResourceUtil.getAsDocument(container).getDocumentElement().getElementsByTagName("rootfiles").item(0);
                opfPath = ((Element) rootfiles.getElementsByTagName("rootfile").item(0)).getAttribute("full-path");
            } catch (Exception e) {
                log.debug("epub4j could not read container.xml: {}", e.getMessage());
            }
        }
        return StringUtils.isBlank(opfPath) ? EPUB4J_DEFAULT_OPF_PATH : opfPath;
    }

    private Resource findCoverResourceByHeuristic(Book epub) {
        for (Resource res : epub.getResources().getAll()) {
            String id = res.getId();
            String href = res.getHref();
            if ((id != null && id.toLowerCase().contains("cover")) ||
                    (href != null && href.toLowerCase().contains("cover"))) {
                if (res.getMediaType() != null && res.getMediaType().getName().startsWith("image")) {
                    return res;
                }
            }
        }
        return null;
    }

    private byte[] readResource(Resource resource) {
        try {
            return resource.getData();
        } catch (Exception e) {
            log.debug("Failed to read {} from EPUB: {}", resource.getHref(), e.getMessage());
            return null;
        }
    }

    private String findCoverImageHrefInOpf(OpfPackage opf) {
        if (opf == null) return null;
        try {
            NodeList manifestItems = opf.document().getElementsByTagName("item");
            for (int i = 0; i < manifestItems.getLength(); i++) {
                Element item = (Element) manifestItems.item(i);
                String properties = item.getAttribute("properties");
                if (properties != null && properties.contains("cover-image")) {
                    String href = item.getAttribute("href");
                    String decodedHref = URLDecoder.decode(href, StandardCharsets.UTF_8);
                    return resolvePath(opf.path(), decodedHref);
                }
            }
        } catch (Exception e) {
            log.debug("Failed to find cover image in OPF: {}", e.getMessage());
        }
        return null;
    }

    private String findManifestCoverByHeuristic(OpfPackage opf) {
        if (opf == null) return null;
        try {
            NodeList manifestItems = opf.document().getElementsByTagName("item");
            for (int i = 0; i < manifestItems.getLength(); i++) {
                Element item = (Element) manifestItems.item(i);
                String id = item.getAttribute("id");
                String href = item.getAttribute("href");
                String mediaType = item.getAttribute("media-type");

                if ((id != null && id.toLowerCase().contains("cover")) || (href != null && href.toLowerCase().contains("cover"))) {
                    if (mediaType != null && mediaType.startsWith("image/")) {
                        String decodedHref = URLDecoder.decode(href, StandardCharsets.UTF_8);
                        return resolvePath(opf.path(), decodedHref);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Heuristic manifest search failed: {}", e.getMessage());
        }
        return null;
    }

    private byte[] findCoverInZipByHeuristic(EpubZip zip) {
        try {
            for (FileHeader header : zip.headers()) {
                String name = header.getFileName().toLowerCase();
                if (name.contains("cover") && (name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png") || name.endsWith(".webp"))) {
                    return zip.read(header);
                }
            }
        } catch (Exception e) {
            log.debug("Direct ZIP scan for cover failed: {}", e.getMessage());
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

    private byte[] extractFileFromZip(EpubZip zip, String path) {
        if (path == null) return null;
        try {
            FileHeader header = zip.header(path);
            if (header == null) return null;
            return zip.read(header);
        } catch (Exception e) {
            log.warn("Failed to extract file {} from zip", path);
            return null;
        }
    }

    /**
     * The EPUB's zip as zip4j's ZipFile reads it, but through the one file handle held for the whole extraction:
     * ZipFile opens the file again to read the central directory and again for every entry, each a round trip on a
     * network share. Headers, entry lookup and entry streams are zip4j's own, so names, inflating, CRC checks and
     * failures are as before. Entries are read one at a time, each to the end before the next is opened.
     * <p>
     * One difference: every entry is read from this file. ZipFile looks for the other parts of a split archive beside
     * it, so it could read nothing from an EPUB whose end record claims to be a later part, where epub4j's full read,
     * reading the file front to back, still found the cover.
     */
    private static final class EpubZip {

        private final RandomAccessFile file;
        private final ZipModel model;

        EpubZip(RandomAccessFile file) throws IOException {
            this.file = file;
            // ZipFile's defaults: charset left to zip4j, its standard buffer, UTF-8 passwords
            this.model = new HeaderReader().readAllHeaders(file, new Zip4jConfig(null, InternalZipConstants.BUFF_SIZE, true));
        }

        // An empty file has no central directory, which ZipFile reports as no entries
        List<FileHeader> headers() {
            return model.getCentralDirectory() == null ? List.of() : model.getCentralDirectory().getFileHeaders();
        }

        FileHeader header(String name) throws ZipException {
            return model.getCentralDirectory() == null ? null : HeaderUtil.getFileHeader(model, name);
        }

        byte[] read(FileHeader header) throws IOException {
            try (InputStream in = open(header)) {
                return in.readAllBytes();
            }
        }

        // What UnzipUtil.createZipInputStream does, reading from the open file instead of opening it again
        private InputStream open(FileHeader header) throws IOException {
            file.seek(header.getOffsetLocalHeader());
            ZipInputStream in = new ZipInputStream(fromCurrentPosition(), (char[]) null);
            if (in.getNextEntry(header, false) == null) {
                throw new ZipException("Could not locate local file header for corresponding file header");
            }
            return in;
        }

        // The file from wherever it was last positioned; closing the stream leaves the file open for the next entry
        private InputStream fromCurrentPosition() {
            return new InputStream() {
                @Override
                public int read() throws IOException {
                    return file.read();
                }

                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    return file.read(b, off, len);
                }
            };
        }
    }
}
