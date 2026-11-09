package com.example.qrstream.protocol;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * File description carried in the payload of a META frame (UTF-8 JSON).
 *
 * <p>Fields: fileName, fileSize, mimeType, chunkSize, totalChunks, groupSize, sha256 (hex).
 * A groupSize of 0 means the transfer carries no parity frames.</p>
 */
public final class FileMeta {

    private static final String KEY_FILE_NAME = "fileName";
    private static final String KEY_FILE_SIZE = "fileSize";
    private static final String KEY_MIME_TYPE = "mimeType";
    private static final String KEY_CHUNK_SIZE = "chunkSize";
    private static final String KEY_TOTAL_CHUNKS = "totalChunks";
    private static final String KEY_GROUP_SIZE = "groupSize";
    private static final String KEY_SHA256 = "sha256";

    private final String fileName;
    private final long fileSize;
    private final String mimeType;
    private final int chunkSize;
    private final int totalChunks;
    private final int groupSize;
    private final String sha256;

    /**
     * @param fileName    display name of the file, not null
     * @param fileSize    size in bytes
     * @param mimeType    MIME type, null is replaced by application/octet-stream
     * @param chunkSize   bytes per DATA chunk
     * @param totalChunks number of DATA chunks
     * @param groupSize   parity group size K, or 0 for no parity
     * @param sha256      lowercase hex SHA-256 of the whole file
     */
    public FileMeta(String fileName, long fileSize, String mimeType, int chunkSize,
                    int totalChunks, int groupSize, String sha256) {
        this.fileName = fileName == null ? "" : fileName;
        this.fileSize = fileSize;
        this.mimeType = mimeType == null ? ProtocolConstants.DEFAULT_MIME_TYPE : mimeType;
        this.chunkSize = chunkSize;
        this.totalChunks = totalChunks;
        this.groupSize = groupSize;
        this.sha256 = sha256 == null ? "" : sha256;
    }

    /** @return file name */
    public String getFileName() {
        return fileName;
    }

    /** @return file size in bytes */
    public long getFileSize() {
        return fileSize;
    }

    /** @return MIME type */
    public String getMimeType() {
        return mimeType;
    }

    /** @return bytes per chunk */
    public int getChunkSize() {
        return chunkSize;
    }

    /** @return number of DATA chunks */
    public int getTotalChunks() {
        return totalChunks;
    }

    /** @return parity group size K, 0 when parity is off */
    public int getGroupSize() {
        return groupSize;
    }

    /** @return lowercase hex SHA-256 of the file */
    public String getSha256() {
        return sha256;
    }

    /**
     * Shortens a long file name to {@link ProtocolConstants#MAX_FILE_NAME_LENGTH},
     * keeping a short extension.
     *
     * @param name original name, not null
     * @return the name unchanged if short enough, else a shortened name
     */
    public static String limitName(String name) {
        if (name.length() <= ProtocolConstants.MAX_FILE_NAME_LENGTH) {
            return name;
        }
        int dot = name.lastIndexOf('.');
        String ext = "";
        if (dot > 0 && name.length() - dot <= ProtocolConstants.MAX_EXTENSION_LENGTH) {
            ext = name.substring(dot);
        }
        int keep = ProtocolConstants.MAX_FILE_NAME_LENGTH - ext.length();
        return name.substring(0, keep) + ext;
    }

    /**
     * Checks that every field is inside the range the receiver is willing to allocate for.
     *
     * @return true if the description is safe and self-consistent
     */
    public boolean isValid() {
        if (fileSize < 0 || fileSize > ProtocolConstants.MAX_FILE_SIZE_BYTES) {
            return false;
        }
        if (chunkSize < 1 || chunkSize > ProtocolConstants.MAX_PAYLOAD_LENGTH) {
            return false;
        }
        if (groupSize < 0 || groupSize > ProtocolConstants.MAX_UNSIGNED_SHORT) {
            return false;
        }
        if (totalChunks < 0 || totalChunks > ProtocolConstants.MAX_UNSIGNED_SHORT
                || totalChunks != Chunker.chunkCount(fileSize, chunkSize)) {
            return false;
        }
        return sha256.length() == ProtocolConstants.SHA256_HEX_LENGTH;
    }

    /** @return the description as a JSON object string */
    public String toJson() {
        return "{"
                + SimpleJson.quote(KEY_FILE_NAME) + ":" + SimpleJson.quote(fileName) + ","
                + SimpleJson.quote(KEY_FILE_SIZE) + ":" + fileSize + ","
                + SimpleJson.quote(KEY_MIME_TYPE) + ":" + SimpleJson.quote(mimeType) + ","
                + SimpleJson.quote(KEY_CHUNK_SIZE) + ":" + chunkSize + ","
                + SimpleJson.quote(KEY_TOTAL_CHUNKS) + ":" + totalChunks + ","
                + SimpleJson.quote(KEY_GROUP_SIZE) + ":" + groupSize + ","
                + SimpleJson.quote(KEY_SHA256) + ":" + SimpleJson.quote(sha256)
                + "}";
    }

    /** @return the UTF-8 bytes of {@link #toJson()}, ready to be a META payload */
    public byte[] toPayload() {
        return toJson().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Parses a META payload.
     *
     * @param payload UTF-8 JSON bytes
     * @return the parsed description (not yet range-checked, see {@link #isValid()})
     * @throws FrameFormatException if the JSON is malformed or a field is missing or mistyped
     */
    public static FileMeta fromPayload(byte[] payload) throws FrameFormatException {
        Map<String, Object> map = SimpleJson.parseObject(new String(payload, StandardCharsets.UTF_8));
        return new FileMeta(
                requireString(map, KEY_FILE_NAME),
                requireLong(map, KEY_FILE_SIZE),
                requireString(map, KEY_MIME_TYPE),
                requireInt(map, KEY_CHUNK_SIZE),
                requireInt(map, KEY_TOTAL_CHUNKS),
                requireInt(map, KEY_GROUP_SIZE),
                requireString(map, KEY_SHA256));
    }

    private static String requireString(Map<String, Object> map, String key)
            throws FrameFormatException {
        Object value = map.get(key);
        if (!(value instanceof String)) {
            throw new FrameFormatException("META field missing or not text: " + key);
        }
        return (String) value;
    }

    private static long requireLong(Map<String, Object> map, String key)
            throws FrameFormatException {
        Object value = map.get(key);
        if (!(value instanceof Long)) {
            throw new FrameFormatException("META field missing or not a number: " + key);
        }
        return (Long) value;
    }

    private static int requireInt(Map<String, Object> map, String key)
            throws FrameFormatException {
        long value = requireLong(map, key);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new FrameFormatException("META number out of range: " + key);
        }
        return (int) value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof FileMeta)) {
            return false;
        }
        FileMeta m = (FileMeta) o;
        return fileSize == m.fileSize && chunkSize == m.chunkSize
                && totalChunks == m.totalChunks && groupSize == m.groupSize
                && fileName.equals(m.fileName) && mimeType.equals(m.mimeType)
                && sha256.equals(m.sha256);
    }

    @Override
    public int hashCode() {
        int result = fileName.hashCode();
        result = 31 * result + Long.hashCode(fileSize);
        result = 31 * result + chunkSize;
        result = 31 * result + totalChunks;
        result = 31 * result + groupSize;
        result = 31 * result + sha256.hashCode();
        return result;
    }

    @Override
    public String toString() {
        return toJson();
    }
}
