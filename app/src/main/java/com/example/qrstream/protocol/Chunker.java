package com.example.qrstream.protocol;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Splits file bytes into fixed-size chunks.
 *
 * <p>The result is deterministic: chunk i holds bytes [i*chunkSize, min((i+1)*chunkSize, n)).
 * An empty file produces zero chunks; an exact multiple of the chunk size produces no
 * short chunk; otherwise the last chunk is shorter.</p>
 *
 * <p>Complexity: {@link #split} is O(n) time and O(n) space for a file of n bytes (every
 * byte is copied once). {@link #chunkCount} and {@link #chunkLength} are O(1).</p>
 */
public final class Chunker {

    private Chunker() {
    }

    /**
     * @param fileSize  file size in bytes, at least 0
     * @param chunkSize bytes per chunk, at least 1
     * @return number of chunks, which is ceil(fileSize / chunkSize)
     * @throws IllegalArgumentException if an argument is out of range
     */
    public static int chunkCount(long fileSize, int chunkSize) {
        if (fileSize < 0) {
            throw new IllegalArgumentException("File size must not be negative");
        }
        if (chunkSize < 1) {
            throw new IllegalArgumentException("Chunk size must be at least 1");
        }
        long count = (fileSize + chunkSize - 1) / chunkSize;
        if (count > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Too many chunks");
        }
        return (int) count;
    }

    /**
     * @param fileSize  file size in bytes
     * @param chunkSize bytes per chunk
     * @param index     chunk index, 0-based
     * @return exact length of chunk {@code index}
     * @throws IllegalArgumentException if the index is outside the file
     */
    public static int chunkLength(long fileSize, int chunkSize, int index) {
        int count = chunkCount(fileSize, chunkSize);
        if (index < 0 || index >= count) {
            throw new IllegalArgumentException("Chunk index out of range: " + index);
        }
        long remaining = fileSize - (long) index * chunkSize;
        return (int) Math.min(chunkSize, remaining);
    }

    /**
     * Splits data into chunks.
     *
     * @param data      file bytes, not null
     * @param chunkSize bytes per chunk, at least 1
     * @return an unmodifiable list of chunk copies, empty for an empty file
     */
    public static List<byte[]> split(byte[] data, int chunkSize) {
        int count = chunkCount(data.length, chunkSize);
        List<byte[]> chunks = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int from = i * chunkSize;
            int to = Math.min(from + chunkSize, data.length);
            chunks.add(Arrays.copyOfRange(data, from, to));
        }
        return Collections.unmodifiableList(chunks);
    }
}
