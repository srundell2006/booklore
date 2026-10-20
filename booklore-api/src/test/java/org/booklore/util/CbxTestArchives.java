package org.booklore.util;

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Builds small CBZ, CB7 and CBR archives for tests. Entries are written in the order given, so a
 * test can make archive order differ from name order. Commons-compress cannot write RAR, so
 * {@link #rar(Path, Entry...)} writes a RAR 4 archive with stored (uncompressed) entries by hand,
 * which junrar reads like any other.
 */
public final class CbxTestArchives {

    /*
     * Solid CB7s, as 7-Zip writes by default (7z a -ms=on): every entry is in one LZMA2 block, so
     * reading one entry after another goes on through the same decoder. SevenZOutputFile cannot
     * write these, as it gives every entry a block of its own, so they are checked in (made with
     * py7zr). Each holds pages/002.png, pages/001.png and pages/003.png in that order, images 2, 1
     * and 3 pixels wide, plus a ComicInfo.xml with Series "Solid Series" and Number 3. In the
     * first the ComicInfo.xml comes after the pages, with Title "Solid Last"; in the second it
     * comes before them, with Title "Solid First".
     */
    public static final Path SOLID_CB7_COMIC_INFO_LAST = Path.of("src/test/resources/cbx/solid-comicinfo-last.cb7");
    public static final Path SOLID_CB7_COMIC_INFO_FIRST = Path.of("src/test/resources/cbx/solid-comicinfo-first.cb7");

    private CbxTestArchives() {
    }

    /** The archive formats a CBX book can be in, with the type ArchiveUtils detects for each. */
    public enum Format {
        CBZ(".cbz", ArchiveUtils.ArchiveType.ZIP),
        CB7(".cb7", ArchiveUtils.ArchiveType.SEVEN_ZIP),
        CBR(".cbr", ArchiveUtils.ArchiveType.RAR);

        private final String extension;
        private final ArchiveUtils.ArchiveType archiveType;

        Format(String extension, ArchiveUtils.ArchiveType archiveType) {
            this.extension = extension;
            this.archiveType = archiveType;
        }

        public String extension() {
            return extension;
        }

        public ArchiveUtils.ArchiveType archiveType() {
            return archiveType;
        }

        /** Writes {@code baseName} plus this format's extension into {@code dir}. */
        public Path write(Path dir, String baseName, Entry... entries) throws IOException {
            Path path = dir.resolve(baseName + extension);
            return switch (this) {
                case CBZ -> zip(path, entries);
                case CB7 -> sevenZip(path, entries);
                case CBR -> rar(path, entries);
            };
        }
    }

    /** A file entry, or a directory entry when {@code data} is null. */
    public record Entry(String name, byte[] data) {
        boolean isDirectory() {
            return data == null;
        }
    }

    public static Entry file(String name, byte[] data) {
        return new Entry(name, data);
    }

    public static Entry file(String name, String text) {
        return new Entry(name, text.getBytes(StandardCharsets.UTF_8));
    }

    public static Entry dir(String name) {
        return new Entry(name, null);
    }

    /**
     * An image whose width identifies it once decoded. The encoding follows the extension of
     * {@code entryName} (jpg, png, gif, bmp); webp and anything else are written as PNG, which
     * ImageIO decodes whatever the name says.
     */
    public static byte[] image(String entryName, int width) {
        String lower = entryName.toLowerCase();
        String format = lower.endsWith(".jpg") || lower.endsWith(".jpeg") ? "jpg"
                : lower.endsWith(".gif") ? "gif"
                : lower.endsWith(".bmp") ? "bmp"
                : "png";
        BufferedImage image = new BufferedImage(width, 4, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setColor(Color.ORANGE);
        graphics.fillRect(0, 0, width, 4);
        graphics.dispose();
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, format, out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static Entry imageFile(String name, int width) {
        return file(name, image(name, width));
    }

    public static String comicInfo(String innerXml) {
        return """
                <?xml version="1.0" encoding="utf-8"?>
                <ComicInfo>
                %s
                </ComicInfo>
                """.formatted(innerXml);
    }

    public static Path zip(Path path, Entry... entries) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(path))) {
            for (Entry entry : entries) {
                zos.putNextEntry(new ZipEntry(entry.isDirectory() && !entry.name().endsWith("/") ? entry.name() + "/" : entry.name()));
                if (!entry.isDirectory()) {
                    zos.write(entry.data());
                }
                zos.closeEntry();
            }
        }
        return path;
    }

    public static Path sevenZip(Path path, Entry... entries) throws IOException {
        try (SevenZOutputFile out = new SevenZOutputFile(path.toFile())) {
            for (Entry entry : entries) {
                SevenZArchiveEntry archiveEntry = new SevenZArchiveEntry();
                archiveEntry.setName(entry.name());
                archiveEntry.setDirectory(entry.isDirectory());
                out.putArchiveEntry(archiveEntry);
                if (!entry.isDirectory()) {
                    out.write(entry.data());
                }
                out.closeArchiveEntry();
            }
        }
        return path;
    }

    public static Path rar(Path path, Entry... entries) throws IOException {
        try (OutputStream out = Files.newOutputStream(path)) {
            out.write(new byte[]{0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00});
            out.write(rarBlock(0x73, 0, new byte[6]));
            for (Entry entry : entries) {
                byte[] data = entry.isDirectory() ? new byte[0] : entry.data();
                out.write(rarFileHeader(entry.name(), data, entry.isDirectory()));
                out.write(data);
            }
            out.write(rarBlock(0x7B, 0x4000, new byte[0]));
        }
        return path;
    }

    private static byte[] rarFileHeader(String name, byte[] data, boolean directory) {
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        CRC32 crc = new CRC32();
        crc.update(data);
        ByteBuffer body = ByteBuffer.allocate(25 + nameBytes.length).order(ByteOrder.LITTLE_ENDIAN);
        body.putInt(data.length);              // packed size
        body.putInt(data.length);              // unpacked size
        body.put((byte) 3);                    // host OS: Unix
        body.putInt((int) crc.getValue());     // file CRC
        body.putInt(0x21);                     // DOS timestamp
        body.put((byte) 29);                   // version needed to extract
        body.put((byte) 0x30);                 // method: store
        body.putShort((short) nameBytes.length);
        body.putInt(directory ? 0x10 : 0x20);  // attributes
        body.put(nameBytes);
        // 0x8000: data follows the header; 0x00E0: directory
        return rarBlock(0x74, 0x8000 | (directory ? 0x00E0 : 0), body.array());
    }

    private static byte[] rarBlock(int type, int flags, byte[] body) {
        ByteBuffer block = ByteBuffer.allocate(7 + body.length).order(ByteOrder.LITTLE_ENDIAN);
        block.putShort((short) 0);
        block.put((byte) type);
        block.putShort((short) flags);
        block.putShort((short) (7 + body.length));
        block.put(body);
        byte[] bytes = block.array();
        CRC32 crc = new CRC32();
        crc.update(bytes, 2, bytes.length - 2);
        bytes[0] = (byte) crc.getValue();
        bytes[1] = (byte) (crc.getValue() >> 8);
        return bytes;
    }
}
