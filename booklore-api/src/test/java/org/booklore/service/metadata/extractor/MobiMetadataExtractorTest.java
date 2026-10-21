package org.booklore.service.metadata.extractor;

import org.booklore.model.dto.BookMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class MobiMetadataExtractorTest {

    private static final int RECORD_0_OFFSET = 88;
    private static final int MOBI_HEADER_LENGTH = 232;
    private static final int EXTH_AUTHOR = 100;

    private final MobiMetadataExtractor extractor = new MobiMetadataExtractor();

    @TempDir
    Path tempDir;

    /** A PalmDB with only record 0: a PalmDOC header, a MOBI header with the given text encoding, EXTH, and the title. */
    private File writeMobi(int textEncoding, byte[] title, byte[] author) throws IOException {
        ByteBuffer exth = ByteBuffer.allocate(12 + 8 + author.length);
        exth.put("EXTH".getBytes(StandardCharsets.US_ASCII)).putInt(exth.capacity()).putInt(1);
        exth.putInt(EXTH_AUTHOR).putInt(8 + author.length).put(author);

        int titleOffset = 16 + MOBI_HEADER_LENGTH + exth.capacity();
        ByteBuffer record0 = ByteBuffer.allocate(titleOffset + title.length);
        record0.putShort((short) 1).putShort((short) 0).putInt(0).putShort((short) 0).putShort((short) 4096);
        record0.position(16);
        record0.put("MOBI".getBytes(StandardCharsets.US_ASCII)).putInt(MOBI_HEADER_LENGTH).putInt(2).putInt(textEncoding);
        record0.putInt(16 + 68, titleOffset).putInt(16 + 72, title.length);
        record0.putInt(16 + 92, -1);
        record0.putInt(16 + 112, 0x40);
        record0.position(16 + MOBI_HEADER_LENGTH);
        record0.put(exth.array()).put(title);

        ByteBuffer palmDb = ByteBuffer.allocate(RECORD_0_OFFSET);
        palmDb.put("Test Book".getBytes(StandardCharsets.US_ASCII));
        palmDb.putShort(76, (short) 1);
        palmDb.putInt(78, RECORD_0_OFFSET);

        ByteArrayOutputStream file = new ByteArrayOutputStream();
        file.write(palmDb.array());
        file.write(record0.array());
        Path path = tempDir.resolve("book.mobi");
        Files.write(path, file.toByteArray());
        return path.toFile();
    }

    @Test
    void windows1252Text_keepsCurlyQuotesAndDashes() throws IOException {
        Charset windows1252 = Charset.forName("windows-1252");
        File mobi = writeMobi(1252, "It’s Kind of a Funny Story – Part 1".getBytes(windows1252), "Renée O’Hara".getBytes(windows1252));

        BookMetadata metadata = extractor.extractMetadata(mobi);

        assertThat(metadata.getTitle()).isEqualTo("It’s Kind of a Funny Story – Part 1");
        assertThat(metadata.getAuthors()).containsExactly("Renée O’Hara");
    }

    @Test
    void utf8Text_isStillReadAsUtf8() throws IOException {
        File mobi = writeMobi(65001, "Война и мир — It’s".getBytes(StandardCharsets.UTF_8), "Лев Толстой".getBytes(StandardCharsets.UTF_8));

        BookMetadata metadata = extractor.extractMetadata(mobi);

        assertThat(metadata.getTitle()).isEqualTo("Война и мир — It’s");
        assertThat(metadata.getAuthors()).containsExactly("Лев Толстой");
    }
}
