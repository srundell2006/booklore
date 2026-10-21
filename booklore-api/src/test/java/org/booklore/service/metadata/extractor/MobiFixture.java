package org.booklore.service.metadata.extractor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds small PalmDB files with a MOBI header, an EXTH block and image records, laid out where
 * MobiBaseMetadataExtractor looks for them.
 */
public final class MobiFixture {

    private static final int MOBI_HEADER_LENGTH = 232;

    private String title = "The Fixture Book";
    private int mobiType = 2;
    private final Map<Integer, List<byte[]>> exth = new LinkedHashMap<>();
    private final List<byte[]> images = new ArrayList<>();
    private boolean mobiIdentifier = true;

    public static MobiFixture book() {
        return new MobiFixture();
    }

    public MobiFixture title(String title) {
        this.title = title;
        return this;
    }

    /** 2 marks a KF8 (AZW3) book. */
    public MobiFixture mobiType(int mobiType) {
        this.mobiType = mobiType;
        return this;
    }

    public MobiFixture exth(int type, String value) {
        exth.computeIfAbsent(type, t -> new ArrayList<>()).add(value.getBytes(StandardCharsets.UTF_8));
        return this;
    }

    public MobiFixture exthInt(int type, int value) {
        exth.computeIfAbsent(type, t -> new ArrayList<>()).add(ByteBuffer.allocate(4).putInt(value).array());
        return this;
    }

    public MobiFixture image(byte[] image) {
        images.add(image);
        return this;
    }

    public MobiFixture withoutMobiIdentifier() {
        mobiIdentifier = false;
        return this;
    }

    public Path writeTo(Path file) throws IOException {
        List<byte[]> records = new ArrayList<>();
        records.add(record0());
        records.add("Some text of the book.".getBytes(StandardCharsets.UTF_8));
        records.addAll(images);

        int headerLength = 78 + 8 * records.size() + 2;
        ByteBuffer header = ByteBuffer.allocate(headerLength);
        byte[] name = "Fixture_Book".getBytes(StandardCharsets.ISO_8859_1);
        header.put(name);
        header.position(60);
        header.put("BOOKMOBI".getBytes(StandardCharsets.ISO_8859_1));
        header.position(76);
        header.putShort((short) records.size());
        int offset = headerLength;
        for (int i = 0; i < records.size(); i++) {
            header.putInt(offset);
            header.put((byte) 0);
            header.put((byte) 0).put((byte) 0).put((byte) (2 * i));
            offset += records.get(i).length;
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(header.array());
        for (byte[] record : records) {
            out.write(record);
        }
        Files.write(file, out.toByteArray());
        return file;
    }

    private byte[] record0() throws IOException {
        ByteArrayOutputStream exthBlock = new ByteArrayOutputStream();
        int count = 0;
        for (Map.Entry<Integer, List<byte[]>> entry : exth.entrySet()) {
            for (byte[] value : entry.getValue()) {
                exthBlock.write(ByteBuffer.allocate(8).putInt(entry.getKey()).putInt(8 + value.length).array());
                exthBlock.write(value);
                count++;
            }
        }
        byte[] exthRecords = exthBlock.toByteArray();
        byte[] titleBytes = title.getBytes(StandardCharsets.UTF_8);

        int exthStart = 16 + MOBI_HEADER_LENGTH;
        int exthLength = 12 + exthRecords.length;
        int titleOffset = exthStart + exthLength;
        ByteBuffer record = ByteBuffer.allocate(titleOffset + titleBytes.length + 4);

        // PalmDOC header: no compression, one text record of 4096 bytes, no encryption
        record.putShort((short) 1).putShort((short) 0).putInt(22).putShort((short) 1).putShort((short) 4096)
                .putShort((short) 0).putShort((short) 0);

        record.position(16);
        record.put((mobiIdentifier ? "MOBI" : "TEXt").getBytes(StandardCharsets.US_ASCII));
        record.putInt(MOBI_HEADER_LENGTH);
        record.putInt(mobiType);
        record.putInt(65001);
        record.position(16 + 68);
        record.putInt(titleOffset);
        record.putInt(titleBytes.length);
        record.position(16 + 92);
        record.putInt(images.isEmpty() ? -1 : 2);
        record.position(16 + 112);
        record.putInt(exth.isEmpty() ? 0 : 0x40);
        record.position(16 + 192);
        record.putInt(0xFFFFFFFF);

        record.position(exthStart);
        record.put("EXTH".getBytes(StandardCharsets.US_ASCII));
        record.putInt(exthLength);
        record.putInt(count);
        record.put(exthRecords);
        record.put(titleBytes);
        return record.array();
    }
}
