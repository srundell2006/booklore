package org.booklore.util;

import com.github.junrar.Archive;
import com.github.junrar.exception.RarException;
import com.github.junrar.rarfile.FileHeader;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A CBZ, CB7 or CBR archive opened once. The entry list is read when the archive is opened and
 * entries are then read by name from the same open archive, so a caller that needs several
 * entries (the cover and ComicInfo.xml) pays for a single open. On a network share every open
 * costs round trips, and junrar walks every RAR header on each open.
 */
@Slf4j
public abstract class CbxArchive implements AutoCloseable {

    /**
     * Names of the file entries in archive order, without directories (the unrar CLI listing does
     * not mark them, so a RAR read through it can include them). A name that occurs more than once
     * is listed once and reads as its first entry.
     */
    public abstract List<String> fileNames();

    public abstract byte[] readEntry(String name) throws IOException;

    /** Closes the archive. A read-only archive has nothing to lose on close, so failures are only logged. */
    @Override
    public abstract void close();

    /**
     * Opens {@code file} as {@code type}, which the caller has already detected with
     * {@link ArchiveUtils#detectArchiveType(File)}. A RAR that junrar cannot read (RAR 5) is opened
     * through the unrar CLI when it is installed.
     */
    public static CbxArchive open(File file, ArchiveUtils.ArchiveType type) throws IOException {
        return switch (type) {
            case ZIP -> openZip(file);
            case SEVEN_ZIP -> new SevenZ(SevenZFile.builder().setFile(file).get());
            case RAR -> openRar(file);
            case UNKNOWN -> throw new IOException("Not a ZIP, 7z or RAR archive: " + file.getName());
        };
    }

    private static CbxArchive openZip(File file) throws IOException {
        // Fast path: read only the central directory
        try {
            return new Zip(zipBuilder(file).setIgnoreLocalFileHeader(true).get());
        } catch (Exception e) {
            log.debug("Fast path failed for ZIP {}: {}", file.getName(), e.getMessage());
        }
        // Slow path: also read every local file header
        return new Zip(zipBuilder(file).setIgnoreLocalFileHeader(false).get());
    }

    private static ZipFile.Builder zipBuilder(File file) {
        return ZipFile.builder().setFile(file).setUseUnicodeExtraFields(true);
    }

    private static CbxArchive openRar(File file) throws IOException {
        Archive archive;
        try {
            archive = new Archive(file);
        } catch (Exception e) {
            if (!UnrarHelper.isAvailable()) {
                throw new IOException("junrar could not open " + file.getName() + ": " + e.getMessage(), e);
            }
            log.info("junrar failed for {}, falling back to unrar CLI: {}", file.getName(), e.getMessage());
            return new RarCli(file.toPath(), UnrarHelper.listEntries(file.toPath()));
        }
        return new Rar(archive);
    }

    private static final class Zip extends CbxArchive {

        private final ZipFile zipFile;
        private final List<String> fileNames;

        private Zip(ZipFile zipFile) {
            this.zipFile = zipFile;
            this.fileNames = Collections.list(zipFile.getEntries()).stream()
                    .filter(entry -> !entry.isDirectory())
                    .map(ZipArchiveEntry::getName)
                    .distinct()
                    .toList();
        }

        @Override
        public List<String> fileNames() {
            return fileNames;
        }

        @Override
        public byte[] readEntry(String name) throws IOException {
            // getEntry returns the first entry of that name, as fileNames promises
            ZipArchiveEntry entry = zipFile.getEntry(name);
            if (entry == null || entry.isDirectory()) {
                throw new FileNotFoundException("No entry " + name);
            }
            try (InputStream is = zipFile.getInputStream(entry)) {
                return is.readAllBytes();
            }
        }

        @Override
        public void close() {
            try {
                zipFile.close();
            } catch (IOException e) {
                log.debug("Failed to close ZIP: {}", e.getMessage());
            }
        }
    }

    private static final class SevenZ extends CbxArchive {

        private final SevenZFile sevenZFile;
        private final Map<String, SevenZArchiveEntry> entries = new LinkedHashMap<>();
        private final List<String> fileNames;

        private SevenZ(SevenZFile sevenZFile) {
            this.sevenZFile = sevenZFile;
            for (SevenZArchiveEntry entry : sevenZFile.getEntries()) {
                if (!entry.isDirectory() && entry.getName() != null) {
                    entries.putIfAbsent(entry.getName(), entry);
                }
            }
            this.fileNames = List.copyOf(entries.keySet());
        }

        @Override
        public List<String> fileNames() {
            return fileNames;
        }

        @Override
        public byte[] readEntry(String name) throws IOException {
            SevenZArchiveEntry entry = entries.get(name);
            if (entry == null) {
                throw new FileNotFoundException("No entry " + name);
            }
            // Random access within the open archive rather than a second pass over its entries
            try (InputStream is = sevenZFile.getInputStream(entry)) {
                return is.readAllBytes();
            }
        }

        @Override
        public void close() {
            try {
                sevenZFile.close();
            } catch (IOException e) {
                log.debug("Failed to close 7z: {}", e.getMessage());
            }
        }
    }

    private static final class Rar extends CbxArchive {

        private final Archive archive;
        private final Map<String, FileHeader> headers = new LinkedHashMap<>();
        private final List<String> fileNames;

        private Rar(Archive archive) {
            this.archive = archive;
            for (FileHeader header : archive.getFileHeaders()) {
                if (!header.isDirectory() && header.getFileName() != null) {
                    headers.putIfAbsent(header.getFileName(), header);
                }
            }
            this.fileNames = List.copyOf(headers.keySet());
        }

        @Override
        public List<String> fileNames() {
            return fileNames;
        }

        @Override
        public byte[] readEntry(String name) throws IOException {
            FileHeader header = headers.get(name);
            if (header == null) {
                throw new FileNotFoundException("No entry " + name);
            }
            try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                archive.extractFile(header, baos);
                return baos.toByteArray();
            } catch (RarException e) {
                throw new IOException("Failed to extract " + name + ": " + e.getMessage(), e);
            }
        }

        @Override
        public void close() {
            try {
                archive.close();
            } catch (IOException e) {
                log.debug("Failed to close RAR: {}", e.getMessage());
            }
        }
    }

    /** RAR read through the unrar CLI: one listing up front, then one extraction per entry read. */
    private static final class RarCli extends CbxArchive {

        private final Path path;
        private final List<String> fileNames;

        private RarCli(Path path, List<String> listing) {
            this.path = path;
            this.fileNames = listing.stream().distinct().toList();
        }

        @Override
        public List<String> fileNames() {
            return fileNames;
        }

        @Override
        public byte[] readEntry(String name) throws IOException {
            return UnrarHelper.extractEntryBytes(path, name);
        }

        @Override
        public void close() {
        }
    }
}
