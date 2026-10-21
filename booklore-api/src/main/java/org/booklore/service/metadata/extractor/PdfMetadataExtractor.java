package org.booklore.service.metadata.extractor;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.PDContentStream;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.contentstream.operator.OperatorName;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.io.RandomAccessReadBufferedFile;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDMetadata;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.booklore.model.dto.BookMetadata;
import org.booklore.util.SecureXmlUtils;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import javax.imageio.ImageIO;
import javax.xml.namespace.NamespaceContext;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
@Slf4j
public class PdfMetadataExtractor implements FileMetadataExtractor {


    private static final Pattern COMMA_AMPERSAND_PATTERN = Pattern.compile("[,&]");
    private static final Pattern ISBN_CLEANUP_PATTERN = Pattern.compile("[^0-9Xx]");

    // FileService.saveCoverImages scales stored covers down to fit within this box
    private static final int STORED_COVER_MAX_WIDTH = 1000;
    private static final int STORED_COVER_MAX_HEIGHT = 1500;
    private static final float EXTRACTED_COVER_MAX_DPI = 300f;
    // Heap PDFBox 3 takes to decode an image, per pixel, measured under G1: 10-12 bytes for an 8-bit RGB or CMYK
    // JPEG or Flate image, 6 for 8-bit gray, 1-4 for a 1-bit image or stencil mask and 22 for JPEG 2000
    private static final long IMAGE_DECODE_BYTES_PER_PIXEL = 12;
    private static final long BILEVEL_DECODE_BYTES_PER_PIXEL = 4;
    private static final long JPX_DECODE_BYTES_PER_PIXEL = 24;
    // Forms drawn inside forms deeper than this are not looked into
    private static final int MAX_FORM_DEPTH = 8;

    @Override
    public byte[] extractCover(File file) {
        try (RandomAccessReadBufferedFile randomAccessRead = new RandomAccessReadBufferedFile(file);
             PDDocument pdf = Loader.loadPDF(randomAccessRead)) {
            return extractCover(pdf);
        } catch (Exception e) {
            log.warn("Failed to extract cover from PDF: {}", file.getAbsolutePath(), e);
            return null;
        }
    }

    /**
     * The cover {@link #extractCover(File)} makes, from a document the caller has loaded, so a caller can reserve heap
     * for the render once it knows the page: see {@link #imageDecodeBytes}.
     */
    public byte[] extractCover(PDDocument pdf) throws IOException {
        BufferedImage coverImage = null;
        try {
            coverImage = renderCover(pdf, EXTRACTED_COVER_MAX_DPI);
            try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                ImageIO.write(coverImage, "jpg", baos);
                return baos.toByteArray();
            }
        } finally {
            if (coverImage != null) {
                coverImage.flush(); // Release native resources
            }
        }
    }

    /**
     * Renders the first page only as large as the cover FileService.saveCoverImages stores from it, and no sharper
     * than {@code maxDpi}, since saveCoverImages discards extra pixels. That saves render time on text, vector and
     * large pages. A page that is one scanned image at more than twice the render DPI gains nothing and can take a few
     * percent longer: PDFBox then area-averages the whole scan, which costs about what rendering at {@code maxDpi}
     * and downscaling did. Its faster options, subsampling or a lower imageDownscalingOptimizationThreshold, leave
     * the cover visibly aliased.
     */
    public BufferedImage renderCover(PDDocument pdf, float maxDpi) throws IOException {
        return new PDFRenderer(pdf).renderImageWithDPI(0, coverDpi(pdf.getPage(0), maxDpi), ImageType.RGB);
    }

    /**
     * The DPI at which the page just fills the edge of the stored cover that saveCoverImages keeps whole, capped at
     * {@code maxDpi}. saveCoverImages scales covers down to fit 1000x1500, and its optional cover cropping trims tall
     * images to 2:3 keeping their width and wide images keeping their height, so portrait pages render 1000 wide and
     * landscape pages 1500 tall. Pages too small for that render at {@code maxDpi}, exactly as before. Other pages
     * round differently from a downscaled {@code maxDpi} render: each side of the stored cover can differ by a pixel,
     * and a page whose aspect ratio sits on one of saveCoverImages' thresholds (square thumbnail, cover cropping) can
     * land on the other side of it.
     */
    static float coverDpi(PDPage page, float maxDpi) {
        PDRectangle cropBox = page.getCropBox();
        // PDFRenderer sizes the image from the crop box, swapping its sides for a quarter-turn rotation
        boolean quarterTurn = page.getRotation() == 90 || page.getRotation() == 270;
        float widthPt = quarterTurn ? cropBox.getHeight() : cropBox.getWidth();
        float heightPt = quarterTurn ? cropBox.getWidth() : cropBox.getHeight();
        // Half a pixel past the cap, so PDFRenderer rounding the size down cannot leave the edge a pixel short
        float dpi = heightPt >= widthPt
                ? (STORED_COVER_MAX_WIDTH + 0.5f) * 72 / widthPt
                : (STORED_COVER_MAX_HEIGHT + 0.5f) * 72 / heightPt;
        return Math.min(dpi, maxDpi);
    }

    /**
     * How many pixels {@link #renderCover} renders the page with at {@code maxDpi}: PDFRenderer's raster for the crop
     * box at {@link #coverDpi}, each side rounded down and at least one pixel. A quarter-turn rotation swaps the sides,
     * which leaves the count alone. Lets a caller budget memory for the render before running it.
     */
    public static long coverPixels(PDPage page, float maxDpi) {
        PDRectangle cropBox = page.getCropBox();
        float scale = coverDpi(page, maxDpi) / 72f;
        long width = (long) Math.max(Math.floor(cropBox.getWidth() * scale), 1);
        long height = (long) Math.max(Math.floor(cropBox.getHeight() * scale), 1);
        return width * height;
    }

    /**
     * Heap PDFBox needs to decode the largest image {@link #renderCover} draws on the page. PDFRenderer decodes each
     * image at its full size however small it draws it, so a page that is one scanned image needs far more heap than
     * the raster it renders into: about 400 MB for an A4 page scanned at 600 dpi. It draws one image at a time and
     * holds what it keeps of earlier ones softly, so the largest is what counts. Lets a caller budget memory for the
     * render before running it.
     *
     * <p>Reads only what the render reads anyway: the page's content, the dictionaries of the images it draws, and
     * the content of the forms it draws, {@value #MAX_FORM_DEPTH} deep. No image is decoded, and an image the page's
     * resources list but its content does not draw is not counted. Images in patterns, soft masks, annotations and
     * Type 3 glyphs are not looked for.
     *
     * @return 0 when the page draws no image; what was found before the content stopped parsing, when it does
     */
    public static long imageDecodeBytes(PDPage page) {
        return largestImageDecodeBytes(page, page.getResources(), Collections.newSetFromMap(new IdentityHashMap<>()), 0);
    }

    private static long largestImageDecodeBytes(PDContentStream content, PDResources resources, Set<COSBase> seen, int depth) {
        COSDictionary xObjects = resources != null ? resources.getCOSObject().getCOSDictionary(COSName.XOBJECT) : null;
        long largest = 0;
        PDFStreamParser parser = null;
        try {
            parser = new PDFStreamParser(content);
            // The operand just before the current token, if it was a name: what a Do operator draws
            COSName name = null;
            for (Object token = parser.parseNextToken(); token != null; token = parser.parseNextToken()) {
                if (token instanceof Operator operator) {
                    if (OperatorName.BEGIN_INLINE_IMAGE.equals(operator.getName()) && operator.getImageParameters() != null) {
                        largest = Math.max(largest, decodeBytes(operator.getImageParameters()));
                    } else if (OperatorName.DRAW_OBJECT.equals(operator.getName()) && name != null && xObjects != null
                            && xObjects.getDictionaryObject(name) instanceof COSStream xObject && seen.add(xObject)) {
                        largest = Math.max(largest, xObjectDecodeBytes(xObject, resources, seen, depth));
                    }
                }
                name = token instanceof COSName tokenName ? tokenName : null;
            }
        } catch (IOException | RuntimeException e) {
            log.debug("Stopped sizing the images of a PDF page at a content parse error: {}", e.getMessage());
        } finally {
            if (parser != null) {
                try {
                    parser.close();
                } catch (IOException e) {
                    log.debug("Failed to close a PDF content parser: {}", e.getMessage());
                }
            }
        }
        return largest;
    }

    private static long xObjectDecodeBytes(COSStream xObject, PDResources resources, Set<COSBase> seen, int depth) {
        COSName subtype = xObject.getCOSName(COSName.SUBTYPE);
        if (COSName.IMAGE.equals(subtype)) {
            return decodeBytes(xObject);
        }
        if (COSName.FORM.equals(subtype) && depth < MAX_FORM_DEPTH) {
            PDFormXObject form = new PDFormXObject(xObject);
            // As PDFBox draws it: a form without resources of its own uses those of what draws it
            PDResources formResources = form.getResources() != null ? form.getResources() : resources;
            return largestImageDecodeBytes(form, formResources, seen, depth + 1);
        }
        return 0;
    }

    /** What decoding the image takes, from its dictionary: an image XObject's, or an inline image's abbreviated one. */
    private static long decodeBytes(COSDictionary image) {
        long width = Math.max(image.getInt(COSName.WIDTH, COSName.W, 0), 0);
        long height = Math.max(image.getInt(COSName.HEIGHT, COSName.H, 0), 0);
        int bitsPerComponent = image.getInt(COSName.BITS_PER_COMPONENT, COSName.BPC, 8);
        long bytesPerPixel;
        if (isJpx(image.getDictionaryObject(COSName.FILTER, COSName.F))) {
            bytesPerPixel = JPX_DECODE_BYTES_PER_PIXEL;
        } else if (image.getBoolean(COSName.IMAGE_MASK, COSName.IM, false) || bitsPerComponent == 1) {
            bytesPerPixel = BILEVEL_DECODE_BYTES_PER_PIXEL;
        } else {
            bytesPerPixel = IMAGE_DECODE_BYTES_PER_PIXEL * Math.clamp(bitsPerComponent, 8, 16) / 8;
        }
        // No more pixels than a Java array holds can be decoded
        return Math.min(width * height, Integer.MAX_VALUE) * bytesPerPixel;
    }

    private static boolean isJpx(COSBase filter) {
        if (filter instanceof COSArray filters) {
            return filters.indexOfObject(COSName.JPX_DECODE) >= 0;
        }
        return COSName.JPX_DECODE.equals(filter);
    }

    @Override
    public BookMetadata extractMetadata(File file) {
        if (!file.exists() || !file.isFile()) {
            log.warn("File does not exist or is not a file: {}", file.getPath());
            return BookMetadata.builder().build();
        }

        BookMetadata metadata = BookMetadata.builder().build();
        try (RandomAccessReadBufferedFile randomAccessRead = new RandomAccessReadBufferedFile(file);
             PDDocument pdf = Loader.loadPDF(randomAccessRead)) {
            metadata = extractMetadata(pdf, file);
        } catch (Exception e) {
            log.error("Failed to load PDF file: {}", file.getPath(), e);
        }
        return metadata;
    }

    /**
     * Extracts metadata from a document the caller has already loaded, so a caller that also renders the cover loads
     * the file once. The file is not read: it only supplies the name used as the title fallback and in log messages.
     */
    public BookMetadata extractMetadata(PDDocument pdf, File file) {
        BookMetadata.BookMetadataBuilder metadataBuilder = BookMetadata.builder();

        try {
            PDDocumentInformation info = pdf.getDocumentInformation();

            if (info != null) {
                if (StringUtils.isNotBlank(info.getTitle())) {
                    metadataBuilder.title(info.getTitle());
                } else {
                    metadataBuilder.title(FilenameUtils.getBaseName(file.getName()));
                }

                if (StringUtils.isNotBlank(info.getAuthor())) {
                    List<String> authors = parseAuthors(info.getAuthor());
                    if (!authors.isEmpty()) {
                        metadataBuilder.authors(authors);
                    }
                }

                if (StringUtils.isNotBlank(info.getSubject())) {
                    metadataBuilder.description(info.getSubject());
                }

                COSDictionary cosDict = info.getCOSObject();
                if (cosDict != null && cosDict.containsKey(COSName.getPDFName("EBX_PUBLISHER"))) {
                    String ebxPublisher = cosDict.getString(COSName.getPDFName("EBX_PUBLISHER"));
                    if (StringUtils.isNotBlank(ebxPublisher)) {
                        metadataBuilder.publisher(ebxPublisher);
                    }
                }

                if (info.getCreationDate() != null) {
                    LocalDate createdDate = convertCalendarToLocalDate(info.getCreationDate());
                    if (createdDate != null) {
                        metadataBuilder.publishedDate(createdDate);
                    }
                }

                metadataBuilder.pageCount(pdf.getNumberOfPages());

                if (StringUtils.isNotBlank(info.getKeywords())) {
                    String keywords = info.getKeywords();
                    String[] parts;
                    if (keywords.contains(";")) {
                        parts = keywords.split(";");
                    } else {
                        parts = keywords.split(",");
                    }
                    Set<String> categories = Arrays.stream(parts)
                            .map(String::trim)
                            .filter(StringUtils::isNotBlank)
                            .collect(Collectors.toSet());
                    if (!categories.isEmpty()) {
                        metadataBuilder.categories(categories);
                    }
                }

                String languageValue = info.getCustomMetadataValue("Language");
                if (StringUtils.isNotBlank(languageValue)) {
                    metadataBuilder.language(languageValue);
                }
            }

            PDMetadata metadata = pdf.getDocumentCatalog().getMetadata();

            if (metadata != null) {
                try (InputStream is = metadata.createInputStream()) {
                    if (is == null) {
                        log.warn("PDMetadata InputStream is null");
                    } else {
                        String rawXmp = IOUtils.toString(is, StandardCharsets.UTF_8);
                        if (StringUtils.isNotBlank(rawXmp)) {
                            DocumentBuilder dBuilder = SecureXmlUtils.createSecureDocumentBuilder(true);
                            Document doc = dBuilder.parse(new ByteArrayInputStream(rawXmp.getBytes(StandardCharsets.UTF_8)));

                            XPathFactory xPathfactory = XPathFactory.newInstance();
                            XPath xpath = xPathfactory.newXPath();
                            xpath.setNamespaceContext(new XmpNamespaceContext());

                            extractDublinCoreMetadata(xpath, doc, metadataBuilder);
                            extractCalibreMetadata(xpath, doc, metadataBuilder);
                            extractBookloreMetadata(xpath, doc, metadataBuilder);
                            
                            // Debug logging for troubleshooting extraction issues
                            if (log.isDebugEnabled()) {
                                BookMetadata debugMeta = metadataBuilder.build();
                                log.debug("PDF XMP extraction results - subtitle: '{}', moods: {}, tags: {}", 
                                    debugMeta.getSubtitle(), debugMeta.getMoods(), debugMeta.getTags());
                            }

                            Map<String, String> identifiers = extractIdentifiers(xpath, doc);
                            if (!identifiers.isEmpty()) {
                                // Extract generic ISBN first
                                String isbn = identifiers.get("isbn");
                                if (StringUtils.isNotBlank(isbn)) {
                                    isbn = ISBN_CLEANUP_PATTERN.matcher(isbn).replaceAll("");
                                    if (isbn.length() == 10) {
                                        metadataBuilder.isbn10(isbn);
                                    } else if (isbn.length() == 13) {
                                        metadataBuilder.isbn13(isbn);
                                    } else {
                                        metadataBuilder.isbn13(isbn); // Fallback
                                    }
                                }

                                // Extract specific ISBN schemes (overwrites generic only if valid)
                                String isbn13 = identifiers.get("isbn13");
                                if (StringUtils.isNotBlank(isbn13)) {
                                    String cleaned = ISBN_CLEANUP_PATTERN.matcher(isbn13).replaceAll("");
                                    if (!cleaned.isBlank()) {
                                        metadataBuilder.isbn13(cleaned);
                                    }
                                }

                                String isbn10 = identifiers.get("isbn10");
                                if (StringUtils.isNotBlank(isbn10)) {
                                    String cleaned = ISBN_CLEANUP_PATTERN.matcher(isbn10).replaceAll("");
                                    if (!cleaned.isBlank()) {
                                        metadataBuilder.isbn10(cleaned);
                                    }
                                }

                                String google = identifiers.get("google");
                                if (StringUtils.isNotBlank(google)) {
                                    metadataBuilder.googleId(google);
                                }

                                String amazon = identifiers.get("amazon");
                                if (StringUtils.isNotBlank(amazon)) {
                                    metadataBuilder.asin(amazon);
                                }

                                String goodreads = identifiers.get("goodreads");
                                if (StringUtils.isNotBlank(goodreads)) {
                                    metadataBuilder.goodreadsId(goodreads);
                                }

                                String comicvine = identifiers.get("comicvine");
                                if (StringUtils.isNotBlank(comicvine)) {
                                    metadataBuilder.comicvineId(comicvine);
                                }

                                String ranobedb = identifiers.get("ranobedb");
                                if (StringUtils.isNotBlank(ranobedb)) {
                                    metadataBuilder.ranobedbId(ranobedb);
                                }

                                String hardcover = identifiers.get("hardcover");
                                if (StringUtils.isNotBlank(hardcover)) {
                                    metadataBuilder.hardcoverId(hardcover);
                                }

                                String hardcoverBookId = identifiers.get("hardcover_book_id");
                                if (StringUtils.isNotBlank(hardcoverBookId)) {
                                    metadataBuilder.hardcoverBookId(hardcoverBookId);
                                }

                                String lubimyczytac = identifiers.get("lubimyczytac");
                                if (StringUtils.isNotBlank(lubimyczytac)) {
                                    metadataBuilder.lubimyczytacId(lubimyczytac);
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    log.warn("Failed to parse XMP metadata with XML parser: {}", e.getMessage(), e);
                }
            }

        } catch (Exception e) {
            log.error("Failed to read PDF metadata: {}", file.getPath(), e);
        }

        return metadataBuilder.build();
    }

    private void extractDublinCoreMetadata(XPath xpath, Document doc, BookMetadata.BookMetadataBuilder builder) throws XPathExpressionException {
        String title = xpathEvaluate(xpath, doc, "//dc:title/rdf:Alt/rdf:li/text()");
        if (StringUtils.isNotBlank(title)) {
            builder.title(title);
        }

        String description = xpathEvaluate(xpath, doc, "//dc:description/rdf:Alt/rdf:li/text()");
        if (StringUtils.isNotBlank(description)) {
            builder.description(description);
        }

        String publisher = xpathEvaluate(xpath, doc, "//dc:publisher/rdf:Bag/rdf:li/text()");
        if (StringUtils.isNotBlank(publisher)) {
            builder.publisher(publisher);
        }

        String language = xpathEvaluate(xpath, doc, "//dc:language/rdf:Bag/rdf:li/text()");
        if (StringUtils.isNotBlank(language)) {
            builder.language(language);
        }

        List<String> creators = xpathEvaluateMultiple(xpath, doc, "//dc:creator/rdf:Seq/rdf:li/text()");
        if (!creators.isEmpty()) {
            builder.authors(creators);
        }

        Set<String> subjects = new HashSet<>(xpathEvaluateMultiple(xpath, doc, "//dc:subject/rdf:Bag/rdf:li/text()"));
        if (!subjects.isEmpty()) {
            Set<String> knownNonCategories = new HashSet<>();
            
            try {
                String moods = xpath.evaluate("//booklore:Moods/text()", doc);
                if (StringUtils.isNotBlank(moods)) {
                    Arrays.stream(moods.split(";")).map(String::trim).forEach(knownNonCategories::add);
                }
                String tags = xpath.evaluate("//booklore:Tags/text()", doc);
                if (StringUtils.isNotBlank(tags)) {
                    Arrays.stream(tags.split(";")).map(String::trim).forEach(knownNonCategories::add);
                }
            } catch (Exception ignored) {}
            
            subjects.removeAll(knownNonCategories);
            
            if (!subjects.isEmpty()) {
                builder.categories(subjects);
            }
        }
    }

    private void extractCalibreMetadata(XPath xpath, Document doc, BookMetadata.BookMetadataBuilder builder) {
        try {
            String series = xpath.evaluate("//calibre:series/rdf:value/text()", doc);
            if (StringUtils.isNotBlank(series)) {
                builder.seriesName(series);
            }

            // Try fully qualified series_index
            String seriesIndex = xpath.evaluate("//calibre:series/calibreSI:series_index/text()", doc);

            if (StringUtils.isBlank(seriesIndex)) {
                // Try without prefix, in case it's missing namespace
                seriesIndex = xpath.evaluate("//calibre:series/*[local-name()='series_index']/text()", doc);
            }

            if (StringUtils.isNotBlank(seriesIndex)) {
                try {
                    builder.seriesNumber(Float.parseFloat(seriesIndex));
                } catch (NumberFormatException e) {
                    log.warn("Invalid series index: {}", seriesIndex);
                }
            }

        } catch (Exception e) {
            log.warn("Failed to extract calibre metadata: {}", e.getMessage(), e);
        }
    }

    private void extractBookloreMetadata(XPath xpath, Document doc, BookMetadata.BookMetadataBuilder builder) {
        try {
            // Series information (now in Booklore namespace, not Calibre)
            String seriesName = extractBookloreField(xpath, doc, "seriesName");
            if (StringUtils.isNotBlank(seriesName)) {
                builder.seriesName(seriesName);
            }

            String seriesNumber = extractBookloreField(xpath, doc, "seriesNumber");
            if (StringUtils.isNotBlank(seriesNumber)) {
                try {
                    builder.seriesNumber(Float.parseFloat(seriesNumber.trim()));
                } catch (NumberFormatException e) {
                    log.warn("Invalid series number: {}", seriesNumber);
                }
            }

            String seriesTotal = extractBookloreField(xpath, doc, "seriesTotal");
            if (StringUtils.isNotBlank(seriesTotal)) {
                try {
                    builder.seriesTotal(Integer.parseInt(seriesTotal.trim()));
                } catch (NumberFormatException e) {
                    log.warn("Invalid series total: {}", seriesTotal);
                }
            }

            // Subtitle (try both old PascalCase and new camelCase)
            String subtitle = extractBookloreField(xpath, doc, "subtitle");
            log.debug("Extracted subtitle (camelCase): '{}'", subtitle);
            if (StringUtils.isBlank(subtitle)) {
                subtitle = xpath.evaluate("//booklore:Subtitle/text()", doc);
                log.debug("Extracted subtitle (PascalCase fallback): '{}'", subtitle);
            }
            if (StringUtils.isNotBlank(subtitle)) {
                builder.subtitle(subtitle.trim());
            }

            // ISBNs from Booklore namespace
            String isbn13 = extractBookloreField(xpath, doc, "isbn13");
            if (StringUtils.isNotBlank(isbn13)) {
                builder.isbn13(isbn13.trim());
            }
            
            String isbn10 = extractBookloreField(xpath, doc, "isbn10");
            if (StringUtils.isNotBlank(isbn10)) {
                builder.isbn10(isbn10.trim());
            }

            // External IDs from Booklore namespace
            String googleId = extractBookloreField(xpath, doc, "googleId");
            if (StringUtils.isNotBlank(googleId)) {
                builder.googleId(googleId.trim());
            }

            String goodreadsId = extractBookloreField(xpath, doc, "goodreadsId");
            if (StringUtils.isNotBlank(goodreadsId)) {
                builder.goodreadsId(goodreadsId.trim());
            }

            String hardcoverId = extractBookloreField(xpath, doc, "hardcoverId");
            if (StringUtils.isNotBlank(hardcoverId)) {
                builder.hardcoverId(hardcoverId.trim());
            }

            String hardcoverBookId = extractBookloreField(xpath, doc, "hardcoverBookId");
            if (StringUtils.isNotBlank(hardcoverBookId)) {
                builder.hardcoverBookId(hardcoverBookId.trim());
            }

            String asin = extractBookloreField(xpath, doc, "asin");
            if (StringUtils.isNotBlank(asin)) {
                builder.asin(asin.trim());
            }

            String comicvineId = extractBookloreField(xpath, doc, "comicvineId");
            if (StringUtils.isNotBlank(comicvineId)) {
                builder.comicvineId(comicvineId.trim());
            }

            String lubimyczytacId = extractBookloreField(xpath, doc, "lubimyczytacId");
            if (StringUtils.isNotBlank(lubimyczytacId)) {
                builder.lubimyczytacId(lubimyczytacId.trim());
            }

            String ranobedbId = extractBookloreField(xpath, doc, "ranobedbId");
            if (StringUtils.isNotBlank(ranobedbId)) {
                builder.ranobedbId(ranobedbId.trim());
            }

            // Page count: Do NOT read from XMP metadata for PDFs.
            // The actual PDF page count (from pdf.getNumberOfPages()) is set earlier
            // and should not be overridden by metadata that describes the original book.

            // Moods (try new RDF Bag format first, then legacy semicolon-separated)
            Set<String> moods = extractBookloreBag(xpath, doc, "moods");
            log.debug("Extracted moods from RDF Bag: {}", moods);
            if (moods.isEmpty()) {
                // Legacy format support
                String moodsLegacy = xpath.evaluate("//booklore:Moods/text()", doc);
                log.debug("Legacy moods string: '{}'", moodsLegacy);
                if (StringUtils.isNotBlank(moodsLegacy)) {
                    moods = Arrays.stream(moodsLegacy.split(";"))
                            .map(String::trim)
                            .filter(StringUtils::isNotBlank)
                            .collect(Collectors.toSet());
                }
            }
            if (!moods.isEmpty()) {
                builder.moods(moods);
            }

            // Tags (try new RDF Bag format first, then legacy semicolon-separated)
            Set<String> tags = extractBookloreBag(xpath, doc, "tags");
            log.debug("Extracted tags from RDF Bag: {}", tags);
            if (tags.isEmpty()) {
                // Legacy format support
                String tagsLegacy = xpath.evaluate("//booklore:Tags/text()", doc);
                log.debug("Legacy tags string: '{}'", tagsLegacy);
                if (StringUtils.isNotBlank(tagsLegacy)) {
                    tags = Arrays.stream(tagsLegacy.split(";"))
                            .map(String::trim)
                            .filter(StringUtils::isNotBlank)
                            .collect(Collectors.toSet());
                }
            }
            if (!tags.isEmpty()) {
                builder.tags(tags);
            }

            // Ratings (try both old PascalCase and new camelCase)
            extractBookloreRating(xpath, doc, "amazonRating", "AmazonRating", builder::amazonRating);
            extractBookloreRating(xpath, doc, "goodreadsRating", "GoodreadsRating", builder::goodreadsRating);
            extractBookloreRating(xpath, doc, "hardcoverRating", "HardcoverRating", builder::hardcoverRating);
            extractBookloreRating(xpath, doc, "lubimyczytacRating", "LubimyczytacRating", builder::lubimyczytacRating);
            extractBookloreRating(xpath, doc, "ranobedbRating", "RanobedbRating", builder::ranobedbRating);
            
            // User rating
            extractBookloreRating(xpath, doc, "rating", "Rating", builder::rating);

        } catch (Exception e) {
            log.warn("Failed to extract booklore metadata: {}", e.getMessage(), e);
        }
    }

    /**
     * Extracts a simple text field from Booklore namespace.
     */
    private String extractBookloreField(XPath xpath, Document doc, String fieldName) {
        try {
            return xpath.evaluate("//booklore:" + fieldName + "/text()", doc);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Extracts an RDF Bag as a Set of strings from Booklore namespace.
     */
    private Set<String> extractBookloreBag(XPath xpath, Document doc, String fieldName) {
        Set<String> values = new HashSet<>();
        try {
            String xpathExpr = "//booklore:" + fieldName + "/rdf:Bag/rdf:li/text()";
            log.debug("Executing XPath for {}: {}", fieldName, xpathExpr);
            NodeList nodes = (NodeList) xpath.evaluate(xpathExpr, doc, XPathConstants.NODESET);
            log.debug("XPath for {} returned {} nodes", fieldName, nodes != null ? nodes.getLength() : 0);
            if (nodes != null) {
                for (int i = 0; i < nodes.getLength(); i++) {
                    String text = nodes.item(i).getNodeValue();
                    if (StringUtils.isNotBlank(text)) {
                        values.add(text.trim());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to extract RDF Bag for booklore:{}: {}", fieldName, e.getMessage());
        }
        return values;
    }

    /**
     * Extracts a rating field, trying both new camelCase and old PascalCase format.
     */
    private void extractBookloreRating(XPath xpath, Document doc, String newName, String legacyName, 
                                        java.util.function.Consumer<Double> setter) {
        try {
            String value = xpath.evaluate("//booklore:" + newName + "/text()", doc);
            if (StringUtils.isBlank(value)) {
                value = xpath.evaluate("//booklore:" + legacyName + "/text()", doc);
            }
            if (StringUtils.isNotBlank(value)) {
                setter.accept(Double.parseDouble(value.trim()));
            }
        } catch (NumberFormatException e) {
            log.warn("Invalid rating value for {}", newName);
        } catch (Exception e) {
            // Ignore
        }
    }

    private Map<String, String> extractIdentifiers(XPath xpath, Document doc) throws XPathExpressionException {
        Map<String, String> ids = new HashMap<>();
        NodeList idNodes = (NodeList) xpath.evaluate(
                "//xmp:Identifier/rdf:Bag/rdf:li", doc, XPathConstants.NODESET);

        if (idNodes != null) {
            for (int i = 0; i < idNodes.getLength(); i++) {
                String scheme = xpathEvaluate(xpath, idNodes.item(i), "xmpidq:Scheme/text()");
                String value = xpathEvaluate(xpath, idNodes.item(i), "rdf:value/text()");
                if (StringUtils.isNotBlank(scheme) && StringUtils.isNotBlank(value)) {
                    ids.put(scheme.toLowerCase(Locale.ROOT), value);
                }
            }
        }
        return ids;
    }

    private List<String> parseAuthors(String authorString) {
        if (authorString == null) return Collections.emptyList();
        return Arrays.stream(COMMA_AMPERSAND_PATTERN.split(authorString))
                .map(String::trim)
                .filter(StringUtils::isNotBlank)
                .toList();
    }

    private LocalDate convertCalendarToLocalDate(Calendar calendar) {
        if (calendar == null) return null;
        return calendar.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
    }

    private String xpathEvaluate(XPath xpath, Document doc, String expression) throws XPathExpressionException {
        String result = xpath.evaluate(expression, doc);
        return result == null ? "" : result.trim();
    }

    private String xpathEvaluate(XPath xpath, org.w3c.dom.Node node, String expression) throws XPathExpressionException {
        String result = xpath.evaluate(expression, node);
        return result == null ? "" : result.trim();
    }

    private List<String> xpathEvaluateMultiple(XPath xpath, Document doc, String expression) throws XPathExpressionException {
        NodeList nodes = (NodeList) xpath.evaluate(expression, doc, XPathConstants.NODESET);
        List<String> results = new ArrayList<>();
        if (nodes != null) {
            for (int i = 0; i < nodes.getLength(); i++) {
                String text = nodes.item(i).getNodeValue();
                if (StringUtils.isNotBlank(text)) {
                    results.add(text.trim());
                }
            }
        }
        return results;
    }

    private static class XmpNamespaceContext implements NamespaceContext {
        private final Map<String, String> prefixMap = new HashMap<>();

        public XmpNamespaceContext() {
            prefixMap.put("dc", "http://purl.org/dc/elements/1.1/");
            prefixMap.put("rdf", "http://www.w3.org/1999/02/22-rdf-syntax-ns#");
            prefixMap.put("xmp", "http://ns.adobe.com/xap/1.0/");
            prefixMap.put("xmpidq", "http://ns.adobe.com/xmp/Identifier/qual/1.0/");
            prefixMap.put("calibre", "http://calibre-ebook.com/xmp-namespace");
            prefixMap.put("calibreSI", "http://calibre-ebook.com/xmp-namespace/seriesIndex");
            prefixMap.put("booklore", "http://booklore.org/metadata/1.0/");
        }

        @Override
        public String getNamespaceURI(String prefix) {
            return prefixMap.getOrDefault(prefix, "");
        }

        @Override
        public String getPrefix(String namespaceURI) {
            for (Map.Entry<String, String> e : prefixMap.entrySet()) {
                if (e.getValue().equals(namespaceURI)) {
                    return e.getKey();
                }
            }
            return null;
        }

        @Override
        public Iterator<String> getPrefixes(String namespaceURI) {
            return prefixMap.keySet().iterator();
        }
    }
}
