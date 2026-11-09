package com.example.qrstream.protocol;

import java.util.Arrays;
import java.util.List;

/**
 * Recovers a missing DATA chunk from a group's parity chunk.
 *
 * <p>XOR parity can repair exactly one missing chunk per group: parity XOR (the K-1 chunks
 * that did arrive) equals the missing chunk, zero-padded. With two or more chunks missing in
 * the same group the system of equations is under-determined and nothing is recovered.</p>
 *
 * <p>Complexity: {@link #findRecoverable} is O(K) time and O(1) space;
 * {@link #recover} is O(K*C) time and O(C) space for group size K and chunk size C.</p>
 */
public final class ParityRecovery {

    /** Returned by {@link #findRecoverable} when nothing can be recovered. */
    public static final int NOT_RECOVERABLE = -1;

    private ParityRecovery() {
    }

    /**
     * Counts how many chunks of one group are still missing.
     *
     * @param have        {@code have[i]} is true when chunk i is available
     * @param groupIndex  group to inspect
     * @param groupSize   group size K, at least 1
     * @return number of missing chunks in the group
     */
    public static int countMissing(boolean[] have, int groupIndex, int groupSize) {
        int end = ParityEncoder.groupEnd(groupIndex, groupSize, have.length);
        int missing = 0;
        for (int i = ParityEncoder.groupStart(groupIndex, groupSize); i < end; i++) {
            if (!have[i]) {
                missing++;
            }
        }
        return missing;
    }

    /**
     * Decides whether a group can be repaired right now.
     *
     * @param have          {@code have[i]} is true when chunk i is available
     * @param parityPresent true when the group's parity frame has arrived
     * @param groupIndex    group to inspect
     * @param groupSize     group size K, at least 1
     * @return the index of the single missing chunk, or {@link #NOT_RECOVERABLE} when the
     *         parity is absent, no chunk is missing, or two or more are missing
     */
    public static int findRecoverable(boolean[] have, boolean parityPresent, int groupIndex,
                                      int groupSize) {
        if (!parityPresent) {
            return NOT_RECOVERABLE;
        }
        int end = ParityEncoder.groupEnd(groupIndex, groupSize, have.length);
        int missing = NOT_RECOVERABLE;
        for (int i = ParityEncoder.groupStart(groupIndex, groupSize); i < end; i++) {
            if (!have[i]) {
                if (missing != NOT_RECOVERABLE) {
                    return NOT_RECOVERABLE;
                }
                missing = i;
            }
        }
        return missing;
    }

    /**
     * Reconstructs the missing chunk.
     *
     * @param parity        the group's parity chunk ({@code chunkSize} bytes)
     * @param presentChunks every other chunk of the group (the K-1 that arrived)
     * @param missingLength true length of the missing chunk (shorter only for the last chunk)
     * @return the reconstructed chunk, trimmed to {@code missingLength}
     * @throws IllegalArgumentException if lengths are inconsistent
     */
    public static byte[] recover(byte[] parity, List<byte[]> presentChunks, int missingLength) {
        if (missingLength < 0 || missingLength > parity.length) {
            throw new IllegalArgumentException("Missing chunk length out of range");
        }
        byte[] work = parity.clone();
        for (byte[] chunk : presentChunks) {
            if (chunk.length > work.length) {
                throw new IllegalArgumentException("Chunk longer than parity");
            }
            for (int b = 0; b < chunk.length; b++) {
                work[b] ^= chunk[b];
            }
        }
        return Arrays.copyOf(work, missingLength);
    }
}
