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
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
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
            return new Zip(readZip(Files.newByteChannel(file.toPath()), true));
        } catch (Exception e) {
            log.debug("Fast path failed for ZIP {}: {}", file.getName(), e.getMessage());
        }
        // Slow path: also read every local file header
        return new Zip(readZip(Files.newByteChannel(file.toPath()), false));
    }

    /**
     * Opens the ZIP in {@code file}, reading it through a {@link BlockReadChannel}. The ZipFile
     * owns {@code file} from here on, and it is closed if the ZIP cannot be read. Package-private
     * so tests can count the reads that reach the file.
     */
    static ZipFile readZip(SeekableByteChannel file, boolean ignoreLocalFileHeader) throws IOException {
        try {
            return ZipFile.builder()
                    .setSeekableByteChannel(new BlockReadChannel(file))
                    .setUseUnicodeExtraFields(true)
                    .setIgnoreLocalFileHeader(ignoreLocalFileHeader)
                    .get();
        } catch (IOException | RuntimeException e) {
            try {
                file.close();
            } catch (IOException closeFailure) {
                e.addSuppressed(closeFailure);
            }
            throw e;
        }
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

    /**
     * A read-only view of a file that reads it a block at a time and serves reads from the last
     * block read. Commons-compress parses a ZIP central directory with several small reads per
     * entry, and searches backwards for the end record one byte per read; on a network share
     * without client-side caching every read is a round trip. Through blocks those become a few
     * large reads, as when java.util.zip reads the whole central directory at once.
     */
    static final class BlockReadChannel implements SeekableByteChannel {

        private static final int BLOCK_SIZE = 64 * 1024;

        private final SeekableByteChannel file;
        private final long size;
        private final ByteBuffer block = ByteBuffer.allocate(BLOCK_SIZE).limit(0);
        private long blockStart;
        private long position;

        BlockReadChannel(SeekableByteChannel file) throws IOException {
            this.file = file;
            this.size = file.size();
        }

        @Override
        public int read(ByteBuffer dst) throws IOException {
            if (!isOpen()) {
                throw new ClosedChannelException();
            }
            int read = 0;
            while (dst.hasRemaining()) {
                if ((position < blockStart || position >= blockStart + block.limit()) && !readBlock()) {
                    break;
                }
                int offset = (int) (position - blockStart);
                int length = Math.min(dst.remaining(), block.limit() - offset);
                dst.put(block.array(), offset, length);
                position += length;
                read += length;
            }
            return read == 0 && dst.hasRemaining() ? -1 : read;
        }

        /** Reads the block holding {@code position}; false when that is at or past the end of the file. */
        private boolean readBlock() throws IOException {
            if (position >= size) {
                return false;
            }
            // Aligned blocks, except that the last one ends at the end of the file, so the end record
            // and a central directory of up to a block come in one read
            blockStart = Math.max(0, Math.min(position - position % BLOCK_SIZE, size - BLOCK_SIZE));
            block.clear();
            file.position(blockStart);
            while (block.hasRemaining() && file.read(block) >= 0) {
                // A read may return fewer bytes than asked for
            }
            block.flip();
            return position < blockStart + block.limit();
        }

        @Override
        public long position() {
            return position;
        }

        @Override
        public SeekableByteChannel position(long newPosition) {
            if (newPosition < 0) {
                throw new IllegalArgumentException("Negative position: " + newPosition);
            }
            position = newPosition;
            return this;
        }

        @Override
        public long size() {
            return size;
        }

        @Override
        public int write(ByteBuffer src) {
            throw new NonWritableChannelException();
        }

        @Override
        public SeekableByteChannel truncate(long newSize) {
            throw new NonWritableChannelException();
        }

        @Override
        public boolean isOpen() {
            return file.isOpen();
        }

        @Override
        public void close() throws IOException {
            file.close();
        }
    }
}
