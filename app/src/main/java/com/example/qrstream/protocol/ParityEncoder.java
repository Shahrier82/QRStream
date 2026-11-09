package com.example.qrstream.protocol;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Builds XOR parity chunks and defines how chunks are grouped.
 *
 * <p>With group size K, group g holds chunks [g*K, min((g+1)*K, total)). The parity of a
 * group is the XOR of its chunks, each zero-padded to the chunk size. A group size of
 * {@link ProtocolConstants#NO_PARITY} (0) means "no parity": there are no groups.</p>
 *
 * <p>Complexity: {@link #encodeGroup} is O(K*C) time and O(C) space for chunk size C;
 * {@link #encodeAll} is O(N*C) time and O(N*C/K) space for N chunks.</p>
 */
public final class ParityEncoder {

    private ParityEncoder() {
    }

    /**
     * @param totalChunks number of DATA chunks
     * @param groupSize   group size K, or 0 for no parity
     * @return number of parity groups, ceil(total / K), or 0 without parity
     */
    public static int groupCount(int totalChunks, int groupSize) {
        if (groupSize <= 0 || totalChunks <= 0) {
            return 0;
        }
        return (totalChunks + groupSize - 1) / groupSize;
    }

    /**
     * @param chunkIndex chunk index
     * @param groupSize  group size K, at least 1
     * @return the index of the group holding this chunk
     */
    public static int groupOf(int chunkIndex, int groupSize) {
        return chunkIndex / groupSize;
    }

    /**
     * @param groupIndex group index
     * @param groupSize  group size K
     * @return first chunk index of the group (inclusive)
     */
    public static int groupStart(int groupIndex, int groupSize) {
        return groupIndex * groupSize;
    }

    /**
     * @param groupIndex  group index
     * @param groupSize   group size K
     * @param totalChunks number of DATA chunks
     * @return one past the last chunk index of the group (exclusive)
     */
    public static int groupEnd(int groupIndex, int groupSize, int totalChunks) {
        return Math.min((groupIndex + 1) * groupSize, totalChunks);
    }

    /**
     * Computes the parity chunk of one group.
     *
     * @param chunks     all DATA chunks of the file
     * @param groupIndex group to encode
     * @param groupSize  group size K, at least 1
     * @param chunkSize  parity length; shorter chunks are zero-padded to this length
     * @return a new array of {@code chunkSize} bytes
     */
    public static byte[] encodeGroup(List<byte[]> chunks, int groupIndex, int groupSize,
                                     int chunkSize) {
        byte[] parity = new byte[chunkSize];
        int end = groupEnd(groupIndex, groupSize, chunks.size());
        for (int i = groupStart(groupIndex, groupSize); i < end; i++) {
            byte[] chunk = chunks.get(i);
            for (int b = 0; b < chunk.length; b++) {
                parity[b] ^= chunk[b];
            }
        }
        return parity;
    }

    /**
     * Computes the parity chunks of every group.
     *
     * @param chunks    all DATA chunks of the file
     * @param groupSize group size K; 0 yields an empty list
     * @param chunkSize parity length
     * @return an unmodifiable list with one parity array per group
     */
    public static List<byte[]> encodeAll(List<byte[]> chunks, int groupSize, int chunkSize) {
        int groups = groupCount(chunks.size(), groupSize);
        List<byte[]> result = new ArrayList<>(groups);
        for (int g = 0; g < groups; g++) {
            result.add(encodeGroup(chunks, g, groupSize, chunkSize));
        }
        return Collections.unmodifiableList(result);
    }
}
