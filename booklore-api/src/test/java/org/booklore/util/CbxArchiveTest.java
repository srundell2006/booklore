package org.booklore.util;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.booklore.util.CbxTestArchives.Entry;
import org.booklore.util.CbxTestArchives.Format;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.MockedStatic;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.booklore.util.CbxTestArchives.SOLID_CB7_COMIC_INFO_LAST;
import static org.booklore.util.CbxTestArchives.dir;
import static org.booklore.util.CbxTestArchives.file;
import static org.booklore.util.CbxTestArchives.imageFile;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

class CbxArchiveTest {

    @TempDir
    Path tempDir;

    private File write(Format format, Entry... entries) throws IOException {
        return format.write(tempDir, "archive", entries).toFile();
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static int imageWidth(byte[] image) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(image)).getWidth();
    }

    @ParameterizedTest
    @EnumSource(Format.class)
    void listsFileEntriesInArchiveOrderWithoutDirectories(Format format) throws IOException {
        File file = write(format,
                dir("b"),
                imageFile("b/2.jpg", 2),
                dir("a"),
                file("a/ComicInfo.xml", "<ComicInfo/>"),
                imageFile("1.png", 1),
                file("a/notes.txt", "notes"));

        try (CbxArchive archive = CbxArchive.open(file, format.archiveType())) {
            assertThat(archive.fileNames()).containsExactly("b/2.jpg", "a/ComicInfo.xml", "1.png", "a/notes.txt");
        }
    }

    @ParameterizedTest
    @EnumSource(Format.class)
    void readsEntriesInAnyOrderFromOneOpen(Format format) throws IOException {
        File file = write(format,
                file("first.txt", "one"),
                dir("deep"),
                dir("deep/er"),
                file("deep/er/second.txt", "two"),
                file("third.txt", "three"));

        try (CbxArchive archive = CbxArchive.open(file, format.archiveType())) {
            assertThat(archive.readEntry("third.txt")).isEqualTo(bytes("three"));
            assertThat(archive.readEntry("first.txt")).isEqualTo(bytes("one"));
            assertThat(archive.readEntry("deep/er/second.txt")).isEqualTo(bytes("two"));
            assertThat(archive.readEntry("third.txt")).isEqualTo(bytes("three"));
        }
    }

    @ParameterizedTest
    @EnumSource(Format.class)
    void readsEmptyEntries(Format format) throws IOException {
        File file = write(format, file("empty.txt", new byte[0]), file("after.txt", "after"));

        try (CbxArchive archive = CbxArchive.open(file, format.archiveType())) {
            assertThat(archive.readEntry("empty.txt")).isEmpty();
            assertThat(archive.readEntry("after.txt")).isEqualTo(bytes("after"));
        }
    }

    @ParameterizedTest
    @EnumSource(value = Format.class, names = {"CB7", "CBR"})
    void duplicateNameIsListedOnceAndReadsAsTheFirstEntry(Format format) throws IOException {
        File file = write(format,
                file("page.jpg", "first copy"),
                file("other.txt", "other"),
                file("page.jpg", "second copy"));

        try (CbxArchive archive = CbxArchive.open(file, format.archiveType())) {
            assertThat(archive.fileNames()).containsExactly("page.jpg", "other.txt");
            assertThat(archive.readEntry("page.jpg")).isEqualTo(bytes("first copy"));
        }
    }

    @ParameterizedTest
    @EnumSource(Format.class)
    void readingAMissingEntryOrADirectoryFails(Format format) throws IOException {
        File file = write(format, dir("folder"), file("folder/page.txt", "page"));

        try (CbxArchive archive = CbxArchive.open(file, format.archiveType())) {
            assertThatThrownBy(() -> archive.readEntry("missing.txt")).isInstanceOf(FileNotFoundException.class);
            assertThatThrownBy(() -> archive.readEntry(format == Format.CBZ ? "folder/" : "folder"))
                    .isInstanceOf(FileNotFoundException.class);
        }
    }

    @Test
    void zipOpensWhateverTheFileIsNamed() throws IOException {
        File zipNamedCbr = CbxTestArchives.zip(tempDir.resolve("mismatched.cbr"), file("a.txt", "a")).toFile();

        try (CbxArchive archive = CbxArchive.open(zipNamedCbr, ArchiveUtils.ArchiveType.ZIP)) {
            assertThat(archive.readEntry("a.txt")).isEqualTo(bytes("a"));
        }
    }

    @Test
    void unknownTypeDoesNotOpen() throws IOException {
        File text = Files.writeString(tempDir.resolve("notes.txt"), "plain text").toFile();

        assertThatThrownBy(() -> CbxArchive.open(text, ArchiveUtils.ArchiveType.UNKNOWN))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("notes.txt");
    }

    @ParameterizedTest
    @EnumSource(value = Format.class, names = {"CBZ", "CB7"})
    void corruptArchiveDoesNotOpen(Format format) throws IOException {
        File garbage = Files.writeString(tempDir.resolve("garbage"), "not an archive").toFile();

        assertThatThrownBy(() -> CbxArchive.open(garbage, format.archiveType())).isInstanceOf(IOException.class);
    }

    @Nested
    class SolidSevenZip {

        @Test
        void readsEntriesOfOneBlockInAnyOrderFromOneOpen() throws IOException {
            try (CbxArchive archive = CbxArchive.open(SOLID_CB7_COMIC_INFO_LAST.toFile(), ArchiveUtils.ArchiveType.SEVEN_ZIP)) {
                assertThat(archive.fileNames()).containsExactly("pages/002.png", "pages/001.png", "pages/003.png", "ComicInfo.xml");

                // The cover as a scan reads it, decoding past 002.png in the block
                assertThat(imageWidth(archive.readEntry("pages/001.png"))).isEqualTo(1);
                // Then ComicInfo.xml further on: the decoder first drains the cover's entry stream,
                // which the read above has already closed
                assertThat(archive.readEntry("ComicInfo.xml")).asString(StandardCharsets.UTF_8)
                        .contains("<Title>Solid Last</Title>");
                // Entries before the last one read start decoding the block again
                assertThat(imageWidth(archive.readEntry("pages/002.png"))).isEqualTo(2);
                assertThat(imageWidth(archive.readEntry("pages/003.png"))).isEqualTo(3);
                assertThat(imageWidth(archive.readEntry("pages/001.png"))).isEqualTo(1);
            }
        }
    }

    @Nested
    class ZipReadsInBlocks {

        /** Counts the reads that reach the file. */
        private static final class CountingChannel implements SeekableByteChannel {

            private final SeekableByteChannel file;
            private int reads;

            CountingChannel(SeekableByteChannel file) {
                this.file = file;
            }

            @Override
            public int read(ByteBuffer dst) throws IOException {
                reads++;
                return file.read(dst);
            }

            @Override
            public int write(ByteBuffer src) throws IOException {
                return file.write(src);
            }

            @Override
            public long position() throws IOException {
                return file.position();
            }

            @Override
            public SeekableByteChannel position(long newPosition) throws IOException {
                file.position(newPosition);
                return this;
            }

            @Override
            public long size() throws IOException {
                return file.size();
            }

            @Override
            public SeekableByteChannel truncate(long size) throws IOException {
                file.truncate(size);
                return this;
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

        @Test
        void readsTheCentralDirectoryOfAManyPageZipInOneRead() throws IOException {
            Entry[] entries = new Entry[300];
            Random random = new Random(42);
            for (int i = 0; i < entries.length; i++) {
                byte[] page = new byte[1024];
                random.nextBytes(page);
                entries[i] = file("pages/page-%03d.jpg".formatted(i), page);
            }
            Path cbz = CbxTestArchives.zip(tempDir.resolve("many.cbz"), entries);
            CountingChannel file = new CountingChannel(Files.newByteChannel(cbz));

            // Commons-compress reads the central directory with about three reads per entry, so
            // straight from the file this ZIP takes about nine hundred
            try (ZipFile zip = CbxArchive.readZip(file, true)) {
                assertThat(Collections.list(zip.getEntries())).hasSize(300);
                assertThat(file.reads).isEqualTo(1);

                ZipArchiveEntry page = zip.getEntry("pages/page-123.jpg");
                try (InputStream is = zip.getInputStream(page)) {
                    assertThat(is.readAllBytes()).isEqualTo(entries[123].data());
                }
            }
            assertThat(file.isOpen()).isFalse();
        }

        @Test
        void closesTheFileWhenItIsNotAZip() throws IOException {
            Path garbage = Files.writeString(tempDir.resolve("garbage.cbz"), "not a zip at all");
            SeekableByteChannel file = Files.newByteChannel(garbage);

            assertThatThrownBy(() -> CbxArchive.readZip(file, true)).isInstanceOf(IOException.class);
            assertThat(file.isOpen()).isFalse();
        }

        @Test
        void blockReadsReturnWhatTheFileHolds() throws IOException {
            // Several blocks and a partial one, read forwards, backwards, across block boundaries,
            // in pieces larger than a block, at the end of the file and past it
            byte[] content = new byte[200_003];
            Random random = new Random(7);
            random.nextBytes(content);
            Path path = Files.write(tempDir.resolve("content.bin"), content);
            // Each read is {position, length}
            List<int[]> reads = new ArrayList<>();
            for (int position = content.length - 4; position > content.length - 70_000; position -= 997) {
                reads.add(new int[]{position, 4});
            }
            for (int i = 0; i < 500; i++) {
                reads.add(new int[]{random.nextInt(content.length + 10), random.nextInt(i % 50 == 0 ? 150_000 : 300)});
            }
            reads.add(new int[]{0, content.length + 5});
            reads.add(new int[]{content.length - 1, 10});
            reads.add(new int[]{content.length, 10});
            reads.add(new int[]{content.length + 100, 1});
            reads.add(new int[]{5, 0});

            try (SeekableByteChannel channel = new CbxArchive.BlockReadChannel(Files.newByteChannel(path))) {
                assertThat(channel.size()).isEqualTo(content.length);
                for (int[] read : reads) {
                    int position = read[0];
                    ByteBuffer dst = ByteBuffer.allocate(read[1]);
                    channel.position(position);

                    int count = channel.read(dst);

                    int expected = Math.max(0, Math.min(dst.capacity(), content.length - position));
                    if (expected == 0 && dst.capacity() > 0) {
                        assertThat(count).as("read of %d at %d", dst.capacity(), position).isEqualTo(-1);
                        assertThat(channel.position()).isEqualTo(position);
                    } else {
                        assertThat(count).as("read of %d at %d", dst.capacity(), position).isEqualTo(expected);
                        assertThat(Arrays.copyOf(dst.array(), count))
                                .isEqualTo(Arrays.copyOfRange(content, position, position + count));
                        assertThat(channel.position()).isEqualTo(position + count);
                    }
                }
            }
        }
    }

    @Nested
    class RarThatJunrarCannotRead {

        private final Path rar5 = Path.of("src/test/resources/cbx/test-rar5.cbr");

        @Test
        void failsToOpenWithoutUnrarCli() {
            try (MockedStatic<UnrarHelper> unrar = mockStatic(UnrarHelper.class)) {
                unrar.when(UnrarHelper::isAvailable).thenReturn(false);

                assertThatThrownBy(() -> CbxArchive.open(rar5.toFile(), ArchiveUtils.ArchiveType.RAR))
                        .isInstanceOf(IOException.class)
                        .hasMessageContaining("test-rar5.cbr");
                unrar.verify(() -> UnrarHelper.listEntries(any()), never());
            }
        }

        @Test
        void listsOnceThroughUnrarCliAndExtractsPerRead() throws IOException {
            try (MockedStatic<UnrarHelper> unrar = mockStatic(UnrarHelper.class)) {
                unrar.when(UnrarHelper::isAvailable).thenReturn(true);
                unrar.when(() -> UnrarHelper.listEntries(rar5))
                        .thenReturn(List.of("ComicInfo.xml", "pages", "pages/001.jpg", "ComicInfo.xml"));
                unrar.when(() -> UnrarHelper.extractEntryBytes(rar5, "ComicInfo.xml")).thenReturn(bytes("<ComicInfo/>"));
                unrar.when(() -> UnrarHelper.extractEntryBytes(rar5, "pages/001.jpg")).thenReturn(bytes("jpeg"));

                try (CbxArchive archive = CbxArchive.open(rar5.toFile(), ArchiveUtils.ArchiveType.RAR)) {
                    assertThat(archive.fileNames()).containsExactly("ComicInfo.xml", "pages", "pages/001.jpg");
                    assertThat(archive.readEntry("pages/001.jpg")).isEqualTo(bytes("jpeg"));
                    assertThat(archive.readEntry("ComicInfo.xml")).isEqualTo(bytes("<ComicInfo/>"));
                }

                unrar.verify(() -> UnrarHelper.listEntries(rar5), times(1));
                unrar.verify(() -> UnrarHelper.extractEntryBytes(eq(rar5), any()), times(2));
            }
        }
    }
}
