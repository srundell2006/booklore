package org.booklore.service.metadata.extractor;

import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.model.FileHeader;
import org.booklore.model.dto.BookMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The cover lookup now shares one zip open with metadata extraction and reads epub4j's decision without epub4j's
 * full read. Every case here checks the bytes against {@link LegacyEpubCoverExtractor}, the lookup as it was.
 */
class EpubCoverLookupEquivalenceTest {

    private static final String DISTRACTOR = "<item id=\"cover-thumb\" href=\"images/cover-thumb.jpg\" media-type=\"image/jpeg\"/>";

    private EpubMetadataExtractor extractor;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        extractor = new EpubMetadataExtractor();
    }

    @Nested
    class Epub3CoverImageProperty {

        @Test
        void coverImagePropertyWins() throws IOException {
            File epub = new EpubBuilder()
                    .container("OEBPS/content.opf")
                    .text("OEBPS/content.opf", opf(
                            "<meta name=\"cover\" content=\"thumb\"/>",
                            DISTRACTOR + "<item id=\"thumb\" href=\"images/thumb.jpg\" media-type=\"image/jpeg\"/>"
                                    + "<item id=\"front\" href=\"images/front.jpg\" media-type=\"image/jpeg\" properties=\"cover-image\"/>"))
                    .image("OEBPS/images/cover-thumb.jpg")
                    .image("OEBPS/images/thumb.jpg")
                    .image("OEBPS/images/front.jpg")
                    .write();

            assertThat(sameCoverAsBefore(epub)).isEqualTo(imageBytes("OEBPS/images/front.jpg"));
            assertThat(extractor.extractMetadata(epub).getTitle()).isEqualTo("Fixture");
        }

        @Test
        void coverImagePropertyIsReturnedEvenWhenNotABitmap() throws IOException {
            File epub = new EpubBuilder()
                    .container("OEBPS/content.opf")
                    .text("OEBPS/content.opf", opf("", "<item id=\"c\" href=\"images/front.svg\" media-type=\"image/svg+xml\" properties=\"cover-image\"/>"))
                    .image("OEBPS/images/front.svg")
                    .write();

            assertThat(sameCoverAsBefore(epub)).isEqualTo(imageBytes("OEBPS/images/front.svg"));
        }

        @Test
        void missingCoverImageFileFallsThroughToEpub4jCover() throws IOException {
            File epub = new EpubBuilder()
                    .container("OEBPS/content.opf")
                    .text("OEBPS/content.opf", opf(
                            "<meta name=\"cover\" content=\"front\"/>",
                            "<item id=\"gone\" href=\"images/gone.jpg\" media-type=\"image/jpeg\" properties=\"cover-image\"/>"
                                    + "<item id=\"front\" href=\"images/front.jpg\" media-type=\"image/jpeg\"/>"))
                    .image("OEBPS/images/front.jpg")
                    .write();

            assertThat(sameCoverAsBefore(epub)).isEqualTo(imageBytes("OEBPS/images/front.jpg"));
        }

        @Test
        void opfInSubfolderWithParentAndEncodedHref() throws IOException {
            File epub = new EpubBuilder()
                    .container("OPS/package/content.opf")
                    .text("OPS/package/content.opf", opf("", "<item id=\"c\" href=\"../images/my%20cover.jpg\" media-type=\"image/jpeg\" properties=\"cover-image\"/>"))
                    .image("OPS/images/my cover.jpg")
                    .write();

            assertThat(sameCoverAsBefore(epub)).isEqualTo(imageBytes("OPS/images/my cover.jpg"));
        }
    }

    @Nested
    class Epub2MetaCover {

        @Test
        void metaCoverBeatsManifestHeuristic() throws IOException {
            File epub = new EpubBuilder()
                    .container("OEBPS/content.opf")
                    .text("OEBPS/content.opf", opf(
                            "<meta name=\"cover\" content=\"front\"/>",
                            DISTRACTOR + "<item id=\"front\" href=\"images/front.jpg\" media-type=\"image/jpeg\"/>"))
                    .image("OEBPS/images/cover-thumb.jpg")
                    .image("OEBPS/images/front.jpg")
                    .write();

            assertThat(sameCoverAsBefore(epub)).isEqualTo(imageBytes("OEBPS/images/front.jpg"));
        }

        @Test
        void metaCoverIdMatchesCaseInsensitively() throws IOException {
            File epub = new EpubBuilder()
                    .container("OEBPS/content.opf")
                    .text("OEBPS/content.opf", opf(
                            "<meta name=\"COVER\" content=\"FRONT\"/>",
                            DISTRACTOR + "<item id=\"front\" href=\"images/front.jpg\" media-type=\"image/jpeg\"/>"))
                    .image("OEBPS/images/cover-thumb.jpg")
                    .image("OEBPS/images/front.jpg")
                    .write();

            assertThat(sameCoverAsBefore(epub)).isEqualTo(imageBytes("OEBPS/images/front.jpg"));
        }

        @Test
        void metaCoverContentUsedAsHrefWhenNoItemHasThatId() throws IOException {
            File epub = new EpubBuilder()
                    .container("OEBPS/content.opf")
                    .text("OEBPS/content.opf", opf(
                            "<meta name=\"cover\" content=\"images/front.jpg\"/>",
                            DISTRACTOR + "<item id=\"front\" href=\"images/front.jpg\" media-type=\"image/jpeg\"/>"))
                    .image("OEBPS/images/cover-thumb.jpg")
                    .image("OEBPS/images/front.jpg")
                    .write();

            assertThat(sameCoverAsBefore(epub)).isEqualTo(imageBytes("OEBPS/images/front.jpg"));
        }

        @Test
        void webpMetaCoverIsNotABitmapToEpub4jSoHeuristicsDecide() throws IOException {
            File epub = new EpubBuilder()
                    .container("OEBPS/content.opf")
                    .text("OEBPS/content.opf", opf(
                            "<meta name=\"cover\" content=\"front\"/>",
                            DISTRACTOR + "<item id=\"front\" href=\"images/front.webp\" media-type=\"image/webp\"/>"))
                    .image("OEBPS/images/cover-thumb.jpg")
                    .image("OEBPS/images/front.webp")
                    .write();

            assertThat(sameCoverAsBefore(epub)).isEqualTo(imageBytes("OEBPS/images/cover-thumb.jpg"));
        }

        @Test
        void encodedHrefInOpfFolderIsDecodedLikeEpub4j() throws IOException {
            File epub = new EpubBuilder()
                    .container("OPS/package/content.opf")
                    .text("OPS/package/content.opf", opf(
                            "<meta name=\"cover\" content=\"front\"/>",
                            "<item id=\"front\" href=\"images/front%20page.jpg\" media-type=\"image/jpeg\"/>"))
                    .image("OPS/package/images/front page.jpg")
                    .write();

            assertThat(sameCoverAsBefore(epub)).isEqualTo(imageBytes("OPS/package/images/front page.jpg"));
        }

        @Test
        void parentHrefIsNotResolvedByEpub4jButIsByTheManifestHeuristic() throws IOException {
            File epub = new EpubBuilder()
                    .container("OPS/package/content.opf")
                    .text("OPS/package/content.opf", opf(
                            "<meta name=\"cover\" content=\"cover\"/>",
                            "<item id=\"cover\" href=\"../images/front%20page.jpg\" media-type=\"image/jpeg\"/>"))
                    .image("OPS/images/front page.jpg")
                    .write();

            assertThat(sameCoverAsBefore(epub)).isEqualTo(imageBytes("OPS/images/front page.jpg"));
        }

        @Test
        void prefixedOpfIsOnlySeenByEpub4j() throws IOException {
            String opf = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <opf:package xmlns:opf="http://www.idpf.org/2007/opf" version="2.0">
                      <opf:metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                        <dc:title>Prefixed</dc:title>
                        <opf:meta name="cover" content="front"/>
                      </opf:metadata>
                      <opf:manifest>
                        <opf:item id="cover-thumb" href="images/cover-thumb.jpg" media-type="image/jpeg"/>
                        <opf:item id="front" href="images/front.jpg" media-type="image/jpeg"/>
                      </opf:manifest>
                    </opf:package>""";
            File epub = new EpubBuilder()
                    .container("OEBPS/content.opf")
                    .text("OEBPS/content.opf", opf)
                    .image("OEBPS/images/cover-thumb.jpg")
                    .image("OEBPS/images/front.jpg")
                    .write();

            assertThat(sameCoverAsBefore(epub)).isEqualTo(imageBytes("OEBPS/images/front.jpg"));
            assertThat(extractor.extractMetadata(epub).getTitle()).isEqualTo("Prefixed");
        }

        @Test
        void undecodableManifestHrefMakesEpub4jDropTheWholeManifest() throws IOException {
            File epub = new EpubBuilder()
                    .container("OEBPS/content.opf")
                    .text("OEBPS/content.opf", opf(
                            "<meta name=\"cover\" content=\"front\"/>",
                            "<item id=\"front\" href=\"images/front.jpg\" media-type=\"image/jpeg\"/>"
                                    + "<item id=\"odd\" href=\"text/100%.xhtml\" media-type=\"application/xhtml+xml\"/>"))
                    .image("OEBPS/images/front.jpg")
                    .text("OEBPS/text/100%.xhtml", "<html/>")
                    .image("OEBPS/images/cover-small.jpg")
                    .write();

            assertThat(sameCoverAsBefore(epub)).isEqualTo(imageBytes("OEBPS/images/cover-small.jpg"));
        }
    }

    @Nested
    class GuideReference {

        @Test
        void guideCoverImageBeatsManifestHeuristic() throws IOException {
            File epub = new EpubBuilder()
                    .container("OEBPS/content.opf")
                    .text("OEBPS/content.opf", opf("",
                            DISTRACTOR + "<item id=\"front\" href=\"images/front.jpg\" media-type=\"image/jpeg\"/>",
                            "<guide><reference type=\"cover\" title=\"Cover\" href=\"images/front.jpg\"/></guide>"))
                    .image("OEBPS/images/cover-thumb.jpg")
                    .image("OEBPS/images/front.jpg")
                    .write();

            assertThat(sameCoverAsBefore(epub)).isEqualTo(imageBytes("OEBPS/images/front.jpg"));
        }

        @Test
        void guideCoverPageIsNotAnImageSoHeuristicsDecide() throws IOException {
            File epub = new EpubBuilder()
                    .container("OEBPS/content.opf")
                    .text("OEBPS/content.opf", opf("",
                            "<item id=\"titlepage\" href=\"cover.xhtml\" media-type=\"application/xhtml+xml\"/>"
                                    + "<item id=\"art\" href=\"images/cover-art.png\" media-type=\"image/png\"/>",
                            "<spine><itemref idref=\"titlepage\"/></spine><guide><reference type=\"cover\" href=\"cover.xhtml\"/></guide>"))
                    .text("OEBPS/cover.xhtml", "<html/>")
                    .image("OEBPS/images/cover-art.png")
                    .write();

            assertThat(sameCoverAsBefore(epub)).isEqualTo(imageBytes("OEBPS/images/cover-art.png"));
        }

        @Test
        void metaAndGuideNamingDifferentBitmapsAgreeWithEpub4j() throws IOException {
            File epub = new EpubBuilder()
                    .container("OEBPS/content.opf")
                    .text("OEBPS/content.opf", opf(
                            "<meta name=\"cover\" content=\"front\"/>",
                            "<item id=\"front\" href=\"images/front.jpg\" media-type=\"image/jpeg\"/>"
                                    + "<item id=\"back\" href=\"images/back.jpg\" media-type=\"image/jpeg\"/>",
                            "<guide><reference type=\"cover\" href=\"images/back.jpg\"/></guide>"))
                    .image("OEBPS/images/front.jpg")
                    .image("OEBPS/images/back.jpg")
                    .write();

            byte[] cover = sameCoverAsBefore(epub);
            assertThat(Arrays.equals(cover, imageBytes("OEBPS/images/front.jpg")) || Arrays.equals(cover, imageBytes("OEBPS/images/back.jpg"))).isTrue();
        }
    }

    @Nested
    class Heuristics {

        @Test
        void manifestHeuristicOnly() throws IOException {
            File epub = new EpubBuilder()
                    .container("OEBPS/content.opf")
                    .text("OEBPS/content.opf", opf("", "<item id=\"cover-img\" href=\"images/cover.jpg\" media-type=\"image/jpeg\"/>"))
                    .image("OEBPS/images/cover.jpg")
                    .write();

            assertThat(sameCoverAsBefore(epub)).isEqualTo(imageBytes("OEBPS/images/cover.jpg"));
        }

        @Test
        void epub4jResourceHeuristicWhenFirstManifestCandidateIsMissing() throws IOException {
            File epub = new EpubBuilder()
                    .container("OEBPS/content.opf")
                    .text("OEBPS/content.opf", opf("",
                            "<item id=\"cover-missing\" href=\"images/nothere.jpg\" media-type=\"image/jpeg\"/>"
                                    + "<item id=\"cover2\" href=\"images/second.png\" media-type=\"image/png\"/>"))
                    .image("OEBPS/aaa/cover-unlisted.jpg")
                    .image("OEBPS/images/second.png")
                    .write();

            assertThat(sameCoverAsBefore(epub)).isEqualTo(imageBytes("OEBPS/images/second.png"));
        }

        @Test
        void epub4jResourceHeuristicTakesMediaTypeFromExtension() throws IOException {
            File epub = new EpubBuilder()
                    .container("OEBPS/content.opf")
                    .text("OEBPS/content.opf", opf("",
                            "<item id=\"cover-a\" href=\"images/a.jpg\"/>"
                                    + "<item id=\"cover-b\" href=\"images/b.png\" media-type=\"application/octet-stream\"/>"
                                    + "<item id=\"cover-c\" href=\"images/c.gif\"/>"))
                    .image("OEBPS/aaa/cover-unlisted.jpg")
                    .image("OEBPS/images/a.jpg")
                    .image("OEBPS/images/b.png")
                    .image("OEBPS/images/c.gif")
                    .write();

            byte[] cover = sameCoverAsBefore(epub);
            assertThat(cover).isNotNull().isNotEqualTo(imageBytes("OEBPS/aaa/cover-unlisted.jpg"));
        }

        @Test
        void zipNameHeuristicOnly() throws IOException {
            File epub = new EpubBuilder()
                    .container("OEBPS/content.opf")
                    .text("OEBPS/content.opf", opf("", "<item id=\"text\" href=\"chapter1.xhtml\" media-type=\"application/xhtml+xml\"/>"))
                    .text("OEBPS/chapter1.xhtml", "<html/>")
                    .image("OEBPS/images/Cover.JPEG")
                    .write();

            assertThat(sameCoverAsBefore(epub)).isEqualTo(imageBytes("OEBPS/images/Cover.JPEG"));
        }

        @Test
        void noCover() throws IOException {
            File epub = new EpubBuilder()
                    .container("OEBPS/content.opf")
                    .text("OEBPS/content.opf", opf("", "<item id=\"text\" href=\"chapter1.xhtml\" media-type=\"application/xhtml+xml\"/>"))
                    .text("OEBPS/chapter1.xhtml", "<html/>")
                    .image("OEBPS/images/photo.jpg")
                    .write();

            assertThat(sameCoverAsBefore(epub)).isNull();
            assertThat(extractor.extractMetadataAndCover(epub).metadata().getTitle()).isEqualTo("Fixture");
        }
    }

    @Nested
    class MalformedContainer {

        @Test
        void unparseableContainerLeavesEpub4jOnItsDefaultOpfPath() throws IOException {
            File epub = new EpubBuilder()
                    .text("META-INF/container.xml", "<container><rootfiles><rootfile full-path=\"OEBPS/content.opf\"")
                    .text("OEBPS/content.opf", opf(
                            "<meta name=\"cover\" content=\"front\"/>",
                            DISTRACTOR + "<item id=\"front\" href=\"images/front.jpg\" media-type=\"image/jpeg\"/>"))
                    .image("OEBPS/images/cover-thumb.jpg")
                    .image("OEBPS/images/front.jpg")
                    .write();

            assertThat(sameCoverAsBefore(epub)).isEqualTo(imageBytes("OEBPS/images/front.jpg"));
            assertThat(extractor.extractMetadata(epub)).isNull();
        }

        @Test
        void unparseableContainerWithOpfElsewhereFallsToZipHeuristic() throws IOException {
            File epub = new EpubBuilder()
                    .text("META-INF/container.xml", "not xml at all")
                    .text("book/package.opf", opf(
                            "<meta name=\"cover\" content=\"front\"/>",
                            "<item id=\"front\" href=\"images/front.jpg\" media-type=\"image/jpeg\"/>"))
                    .image("book/images/front.jpg")
                    .image("book/images/cover.png")
                    .write();

            assertThat(sameCoverAsBefore(epub)).isEqualTo(imageBytes("book/images/cover.png"));
            assertThat(extractor.extractMetadata(epub)).isNull();
        }

        @Test
        void missingContainerLeavesEpub4jOnItsDefaultOpfPath() throws IOException {
            File epub = new EpubBuilder()
                    .text("OEBPS/content.opf", opf(
                            "<meta name=\"cover\" content=\"front\"/>",
                            DISTRACTOR + "<item id=\"front\" href=\"images/front.jpg\" media-type=\"image/jpeg\"/>"))
                    .image("OEBPS/images/cover-thumb.jpg")
                    .image("OEBPS/images/front.jpg")
                    .write();

            assertThat(sameCoverAsBefore(epub)).isEqualTo(imageBytes("OEBPS/images/front.jpg"));
            assertThat(extractor.extractMetadata(epub)).isNull();
        }

        @Test
        void rootfileOutsideRootfilesIsOnlyFoundByBooklore() throws IOException {
            File epub = new EpubBuilder()
                    .text("META-INF/container.xml", """
                            <?xml version="1.0"?>
                            <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                              <rootfile full-path="book/package.opf" media-type="application/oebps-package+xml"/>
                            </container>""")
                    .text("book/package.opf", opf(
                            "<meta name=\"cover\" content=\"front\"/>",
                            DISTRACTOR + "<item id=\"front\" href=\"images/front.jpg\" media-type=\"image/jpeg\"/>"))
                    .image("book/images/cover-thumb.jpg")
                    .image("book/images/front.jpg")
                    .write();

            assertThat(sameCoverAsBefore(epub)).isEqualTo(imageBytes("book/images/cover-thumb.jpg"));
            assertThat(extractor.extractMetadata(epub).getTitle()).isEqualTo("Fixture");
        }
    }

    @Nested
    class DamagedZip {

        @Test
        void unreadableEpub4jCoverEntryFallsThroughLikeAFailedFullRead() throws IOException {
            File epub = new EpubBuilder()
                    .container("OEBPS/content.opf")
                    .text("OEBPS/content.opf", opf(
                            "<meta name=\"cover\" content=\"front\"/>",
                            "<item id=\"front\" href=\"images/front.jpg\" media-type=\"image/jpeg\"/>"))
                    .corruptStored("OEBPS/images/front.jpg")
                    .image("OEBPS/images/cover-small.jpg")
                    .write();

            assertThat(sameCoverAsBefore(epub)).isEqualTo(imageBytes("OEBPS/images/cover-small.jpg"));
        }

        @Test
        void unreadableEpub4jCoverEntryAlsoSkipsTheEpub4jResourceHeuristic() throws IOException {
            File epub = new EpubBuilder()
                    .container("OEBPS/content.opf")
                    .text("OEBPS/content.opf", opf(
                            "<meta name=\"cover\" content=\"front\"/>",
                            "<item id=\"front\" href=\"images/front.jpg\" media-type=\"image/jpeg\"/>"
                                    + "<item id=\"cover-alt\" href=\"images/alt.png\"/>"))
                    .corruptStored("OEBPS/images/front.jpg")
                    .image("OEBPS/images/alt.png")
                    .image("OEBPS/images/cover-small.jpg")
                    .write();

            assertThat(sameCoverAsBefore(epub)).isEqualTo(imageBytes("OEBPS/images/cover-small.jpg"));
        }

        @Test
        void centralDirectoryCutOffFallsBackToEpub4jFullRead() throws IOException {
            File epub = new EpubBuilder()
                    .container("OEBPS/content.opf")
                    .text("OEBPS/content.opf", opf(
                            "<meta name=\"cover\" content=\"front\"/>",
                            DISTRACTOR + "<item id=\"front\" href=\"images/front.jpg\" media-type=\"image/jpeg\"/>"))
                    .image("OEBPS/images/cover-thumb.jpg")
                    .image("OEBPS/images/front.jpg")
                    .cutInsideCentralDirectory()
                    .write();

            assertThat(sameCoverAsBefore(epub)).isEqualTo(imageBytes("OEBPS/images/front.jpg"));
            assertThat(extractor.extractMetadata(epub)).isNull();
        }

        @Test
        void unreadableEntryElsewhereNoLongerHidesEpub4jCover() throws IOException {
            // Intended difference: epub4j's full read used to fail on any damaged entry and lose its cover
            File epub = new EpubBuilder()
                    .container("OEBPS/content.opf")
                    .text("OEBPS/content.opf", opf(
                            "<meta name=\"cover\" content=\"front\"/>",
                            "<item id=\"front\" href=\"images/front.jpg\" media-type=\"image/jpeg\"/>"
                                    + "<item id=\"ch1\" href=\"text/ch1.xhtml\" media-type=\"application/xhtml+xml\"/>"))
                    .image("OEBPS/images/front.jpg")
                    .corruptStored("OEBPS/text/ch1.xhtml")
                    .write();

            assertThat(new LegacyEpubCoverExtractor().extractCover(epub)).isNull();
            assertThat(extractor.extractCover(epub)).isEqualTo(imageBytes("OEBPS/images/front.jpg"));
        }

        @Test
        void notAZipAndMissingFileHaveNoCover() throws IOException {
            File notZip = tempDir.resolve("not-a-zip.epub").toFile();
            Files.write(notZip.toPath(), "plain text".getBytes(StandardCharsets.UTF_8));
            File missing = tempDir.resolve("missing.epub").toFile();

            assertThat(sameCoverAsBefore(notZip)).isNull();
            assertThat(sameCoverAsBefore(missing)).isNull();
        }

        @Test
        void fileThatCannotBeOpenedFallsBackToEpub4jFullRead() throws IOException {
            File epub = new EpubBuilder()
                    .container("OEBPS/content.opf")
                    .text("OEBPS/content.opf", opf(
                            "<meta name=\"cover\" content=\"front\"/>",
                            DISTRACTOR + "<item id=\"front\" href=\"images/front.jpg\" media-type=\"image/jpeg\"/>"))
                    .image("OEBPS/images/cover-thumb.jpg")
                    .image("OEBPS/images/front.jpg")
                    .write();
            // As zip4j did on a file it may not read: no metadata, and epub4j's full read is the only way left to a cover
            EpubMetadataExtractor deniedOpen = new EpubMetadataExtractor() {
                @Override
                RandomAccessFile openFile(File epubFile) throws FileNotFoundException {
                    throw new FileNotFoundException(epubFile + " (Permission denied)");
                }
            };

            EpubMetadataExtractor.MetadataAndCover result = deniedOpen.extractMetadataAndCover(epub);

            assertThat(result.metadata()).isNull();
            assertThat(result.cover()).isEqualTo(imageBytes("OEBPS/images/front.jpg"));
        }

        @Test
        void zipClaimingToBeAPartOfASplitArchiveIsReadFromTheEpubItself() throws IOException {
            File epub = new EpubBuilder()
                    .container("OEBPS/content.opf")
                    .text("OEBPS/content.opf", opf(
                            "<meta name=\"cover\" content=\"front\"/>",
                            "<item id=\"front\" href=\"images/front.jpg\" media-type=\"image/jpeg\"/>"))
                    .image("OEBPS/images/front.jpg")
                    .claimSecondDiskOfASplitArchive()
                    .write();
            // Intended difference: zip4j's ZipFile looks for the first part beside the EPUB, so it read no entry and lost the metadata
            try (ZipFile zip = new ZipFile(epub)) {
                assertThat(zip.isSplitArchive()).isTrue();
                assertThatThrownBy(() -> zip.getInputStream(zip.getFileHeader("META-INF/container.xml"))).isInstanceOf(FileNotFoundException.class);
            }

            assertThat(sameCoverAsBefore(epub)).isEqualTo(imageBytes("OEBPS/images/front.jpg"));
            assertThat(extractor.extractMetadata(epub).getTitle()).isEqualTo("Fixture");
        }
    }

    @Nested
    class SingleOpen {

        private final AtomicInteger opens = new AtomicInteger();
        private final AtomicInteger stats = new AtomicInteger();
        private Path movedAside;

        // Moves the file aside as soon as it is open, so any other open of its path, by zip4j, epub4j or anything else, finds nothing
        private final EpubMetadataExtractor openOnceThenMoveAside = new EpubMetadataExtractor() {
            @Override
            RandomAccessFile openFile(File epubFile) throws FileNotFoundException {
                opens.incrementAndGet();
                RandomAccessFile file = super.openFile(epubFile);
                try {
                    movedAside = Files.move(epubFile.toPath(), epubFile.toPath().resolveSibling("moved-aside.epub"));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                return file;
            }
        };

        @Test
        @DisabledOnOs(value = OS.WINDOWS, disabledReason = "Windows cannot move a file that is open")
        void everyEntryPointOpensTheFileOnceAndNeverStatsIt() throws IOException {
            File epub2 = new EpubBuilder()
                    .container("OEBPS/content.opf")
                    .text("OEBPS/content.opf", opf(
                            "<meta name=\"cover\" content=\"front\"/>",
                            DISTRACTOR + "<item id=\"front\" href=\"images/front.jpg\" media-type=\"image/jpeg\"/>"))
                    .image("OEBPS/images/cover-thumb.jpg")
                    .image("OEBPS/images/front.jpg")
                    .write();
            File epub3 = new EpubBuilder()
                    .container("OEBPS/content.opf")
                    .text("OEBPS/content.opf", opf("", "<item id=\"c\" href=\"images/front.jpg\" media-type=\"image/jpeg\" properties=\"cover-image\"/>"))
                    .image("OEBPS/images/front.jpg")
                    .write();
            File zipNameOnly = new EpubBuilder()
                    .container("OEBPS/content.opf")
                    .text("OEBPS/content.opf", opf("", "<item id=\"text\" href=\"chapter1.xhtml\" media-type=\"application/xhtml+xml\"/>"))
                    .text("OEBPS/chapter1.xhtml", "<html/>")
                    .image("OEBPS/images/cover.png")
                    .write();

            assertOneOpenGives(epub2, imageBytes("OEBPS/images/front.jpg"));
            assertOneOpenGives(epub3, imageBytes("OEBPS/images/front.jpg"));
            assertOneOpenGives(zipNameOnly, imageBytes("OEBPS/images/cover.png"));
        }

        @Test
        void epub2CoverReadsOnlyContainerOpfAndTheCover() throws IOException {
            File epub = new EpubBuilder()
                    .container("OEBPS/content.opf")
                    .text("OEBPS/content.opf", opf(
                            "<meta name=\"cover\" content=\"front\"/>",
                            DISTRACTOR + "<item id=\"front\" href=\"images/front.jpg\" media-type=\"image/jpeg\"/>"
                                    + "<item id=\"ncx\" href=\"toc.ncx\" media-type=\"application/x-dtbncx+xml\"/>"
                                    + "<item id=\"ch1\" href=\"text/ch1.xhtml\" media-type=\"application/xhtml+xml\"/>",
                            "<spine toc=\"ncx\"><itemref idref=\"ch1\"/></spine>"))
                    .text("OEBPS/toc.ncx", "<ncx xmlns=\"http://www.daisy.org/z3986/2005/ncx/\"><navMap/></ncx>")
                    .text("OEBPS/text/ch1.xhtml", "<html/>")
                    .image("OEBPS/images/cover-thumb.jpg")
                    .image("OEBPS/images/front.jpg")
                    .write();
            Map<Long, String> entryAtOffset = new HashMap<>();
            try (ZipFile zip = new ZipFile(epub)) {
                for (FileHeader header : zip.getFileHeaders()) {
                    entryAtOffset.put(header.getOffsetLocalHeader(), header.getFileName());
                }
            }
            // Every entry is read by seeking the one open file to the entry's local header
            List<String> entriesRead = new ArrayList<>();
            EpubMetadataExtractor recording = new EpubMetadataExtractor() {
                @Override
                RandomAccessFile openFile(File epubFile) throws FileNotFoundException {
                    return new RandomAccessFile(epubFile, "r") {
                        @Override
                        public void seek(long pos) throws IOException {
                            if (entryAtOffset.containsKey(pos)) entriesRead.add(entryAtOffset.get(pos));
                            super.seek(pos);
                        }
                    };
                }
            };

            EpubMetadataExtractor.MetadataAndCover result = recording.extractMetadataAndCover(epub);

            assertThat(result.cover()).isEqualTo(imageBytes("OEBPS/images/front.jpg"));
            assertThat(entriesRead).containsExactly("META-INF/container.xml", "OEBPS/content.opf", "OEBPS/images/front.jpg");
        }

        private void assertOneOpenGives(File epub, byte[] cover) throws IOException {
            EpubMetadataExtractor.MetadataAndCover both = callOnce(epub, openOnceThenMoveAside::extractMetadataAndCover);
            assertThat(both.metadata().getTitle()).isEqualTo("Fixture");
            assertThat(both.cover()).isEqualTo(cover);

            assertThat(callOnce(epub, openOnceThenMoveAside::extractCover)).isEqualTo(cover);
            assertThat(callOnce(epub, openOnceThenMoveAside::extractMetadata).getTitle()).isEqualTo("Fixture");
        }

        // One call on a File that counts the stats zip4j made before every open, putting the file back afterwards
        private <T> T callOnce(File epub, Function<File, T> call) throws IOException {
            opens.set(0);
            stats.set(0);
            File watched = new File(epub.getPath()) {
                @Override
                public boolean exists() {
                    stats.incrementAndGet();
                    return super.exists();
                }

                @Override
                public boolean canRead() {
                    stats.incrementAndGet();
                    return super.canRead();
                }
            };
            try {
                return call.apply(watched);
            } finally {
                assertThat(opens).as("opens of %s", epub.getName()).hasValue(1);
                assertThat(stats).as("stats of %s", epub.getName()).hasValue(0);
                Files.move(movedAside, epub.toPath());
            }
        }
    }

    /**
     * Asserts the cover matches the old lookup, through both entry points, and that the combined call's metadata is
     * what {@link EpubMetadataExtractor#extractMetadata(File)} returns.
     */
    private byte[] sameCoverAsBefore(File epub) {
        byte[] before = new LegacyEpubCoverExtractor().extractCover(epub);
        EpubMetadataExtractor.MetadataAndCover both = extractor.extractMetadataAndCover(epub);

        assertThat(extractor.extractCover(epub)).isEqualTo(before);
        assertThat(both.cover()).isEqualTo(before);

        BookMetadata metadata = extractor.extractMetadata(epub);
        if (metadata == null) {
            assertThat(both.metadata()).isNull();
        } else {
            assertThat(both.metadata()).usingRecursiveComparison().isEqualTo(metadata);
        }
        return before;
    }

    private static byte[] imageBytes(String entryName) {
        return ("image bytes of " + entryName).getBytes(StandardCharsets.UTF_8);
    }

    private static String opf(String metadata, String manifest) {
        return opf(metadata, manifest, "");
    }

    private static String opf(String metadata, String manifest, String afterManifest) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>Fixture</dc:title>
                    <dc:creator>Some Author</dc:creator>
                    %s
                  </metadata>
                  <manifest>%s</manifest>
                  %s
                </package>""".formatted(metadata, manifest, afterManifest);
    }

    private class EpubBuilder {

        private final Map<String, byte[]> entries = new LinkedHashMap<>();
        private final Set<String> corrupt = new HashSet<>();
        private boolean cutCentralDirectory;
        private boolean secondDisk;

        EpubBuilder() {
            text("mimetype", "application/epub+zip");
        }

        EpubBuilder container(String opfPath) {
            return text("META-INF/container.xml", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                      <rootfiles>
                        <rootfile full-path="%s" media-type="application/oebps-package+xml"/>
                      </rootfiles>
                    </container>""".formatted(opfPath));
        }

        EpubBuilder text(String name, String content) {
            entries.put(name, content.getBytes(StandardCharsets.UTF_8));
            return this;
        }

        EpubBuilder image(String name) {
            entries.put(name, imageBytes(name));
            return this;
        }

        // Stored uncompressed with one byte flipped after the CRC was taken, so reading it fails the CRC check
        EpubBuilder corruptStored(String name) {
            entries.put(name, imageBytes(name));
            corrupt.add(name);
            return this;
        }

        // Keeps every entry but cuts the file inside the central directory, as an interrupted copy would
        EpubBuilder cutInsideCentralDirectory() {
            cutCentralDirectory = true;
            return this;
        }

        // Marks the end of central directory as the second disk of a split archive, every entry staying on the first
        EpubBuilder claimSecondDiskOfASplitArchive() {
            secondDisk = true;
            return this;
        }

        File write() throws IOException {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ZipOutputStream zos = new ZipOutputStream(bytes)) {
                for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                    ZipEntry zipEntry = new ZipEntry(entry.getKey());
                    if (corrupt.contains(entry.getKey())) {
                        CRC32 crc = new CRC32();
                        crc.update(entry.getValue());
                        zipEntry.setMethod(ZipEntry.STORED);
                        zipEntry.setSize(entry.getValue().length);
                        zipEntry.setCrc(crc.getValue());
                    }
                    zos.putNextEntry(zipEntry);
                    zos.write(entry.getValue());
                    zos.closeEntry();
                }
            }
            byte[] zip = bytes.toByteArray();
            for (String name : corrupt) {
                int at = indexOf(zip, imageBytes(name));
                zip[at + imageBytes(name).length - 1] ^= 0x01;
            }
            if (secondDisk) {
                ByteBuffer.wrap(zip, zip.length - 22 + 4, 4).order(ByteOrder.LITTLE_ENDIAN).putShort((short) 1).putShort((short) 1);
            }
            if (cutCentralDirectory) {
                int centralDirectoryOffset = ByteBuffer.wrap(zip, zip.length - 22 + 16, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
                zip = Arrays.copyOf(zip, centralDirectoryOffset + 10);
            }
            File file = Files.createTempFile(tempDir, "fixture", ".epub").toFile();
            Files.write(file.toPath(), zip);
            return file;
        }

        private int indexOf(byte[] haystack, byte[] needle) {
            outer:
            for (int i = 0; i <= haystack.length - needle.length; i++) {
                for (int j = 0; j < needle.length; j++) {
                    if (haystack[i + j] != needle[j]) continue outer;
                }
                return i;
            }
            throw new IllegalStateException("entry data not found in zip");
        }
    }
}
