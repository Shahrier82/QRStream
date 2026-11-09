package com.example.qrstream.protocol;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Collects frames in any order, repairs lost chunks with parity, rebuilds the file and
 * verifies it with SHA-256.
 *
 * <h3>Behaviour</h3>
 * <ul>
 *   <li>Frames with wrong magic, bad CRC, a different session id (once a META has locked
 *       the session) or an out-of-range index are ignored and counted.</li>
 *   <li>Frames received before the first META are buffered (up to
 *       {@link ProtocolConstants#MAX_PENDING_FRAMES}) and replayed when a META arrives; only
 *       frames of the META's session are kept.</li>
 *   <li>Idempotent: a duplicate frame never changes the state, it only increments the
 *       duplicate counter.</li>
 *   <li>After every accepted frame the affected group is checked: with its parity present
 *       and exactly one chunk missing, that chunk is rebuilt.</li>
 * </ul>
 *
 * <h3>Complexity</h3>
 * <p>N = number of chunks, C = chunk size, K = group size, n = N*C = file size.</p>
 * <ul>
 *   <li>{@code accept(Frame)}: O(1) for META/DATA/PARITY bookkeeping plus a group check of
 *       O(K) and, only when a chunk is actually rebuilt, O(K*C). Amortised over a whole
 *       transfer each group is rebuilt at most once, so the total recovery cost is
 *       O(N*C) = O(n).</li>
 *   <li>{@code accept(String)} adds Base64 decoding and CRC, O(frame size) = O(C).</li>
 *   <li>Completion (assembly + SHA-256): O(n) once.</li>
 *   <li>{@code getMissingIndices}: O(N).</li>
 *   <li>Space: O(n) for chunks, O(n/K) for parity, O(N) flags, plus the pending buffer of at
 *       most {@link ProtocolConstants#MAX_PENDING_FRAMES} frames before the META arrives.</li>
 * </ul>
 *
 * <p>All public methods are synchronized, so one thread may feed frames while another reads
 * progress.</p>
 */
public final class Reassembler {

    /** What happened to a frame that was handed to the reassembler. */
    public enum Outcome {
        /** The frame was new and has been stored (or buffered while waiting for META). */
        ACCEPTED,
        /** The frame had already been received; the state did not change. */
        DUPLICATE,
        /** The frame was invalid or does not belong to this transfer. */
        IGNORED
    }

    /** Result of the final SHA-256 check. */
    public enum Verification {
        /** Not every chunk is present yet. */
        INCOMPLETE,
        /** The rebuilt file matches the hash announced in META. */
        OK,
        /** The rebuilt file does not match the announced hash. */
        HASH_MISMATCH
    }

    /** Chunk state: not received and not recovered. */
    public static final byte STATE_MISSING = 0;
    /** Chunk state: the DATA frame itself arrived. */
    public static final byte STATE_RECEIVED = 1;
    /** Chunk state: rebuilt from parity. */
    public static final byte STATE_RECOVERED = 2;

    private final List<Frame> pending = new ArrayList<>();

    private boolean locked;
    private int sessionId;
    private FileMeta meta;
    private byte[][] chunks;
    private boolean[] have;
    private byte[] states;
    private byte[][] parity;
    private boolean[] parityPresent;

    private int receivedCount;
    private int recoveredCount;
    private int heldCount;
    private int parityReceivedCount;
    private int framesAccepted;
    private int duplicateCount;
    private int invalidCount;
    private int ignoredCount;
    private int totalSeen;

    private Verification verification = Verification.INCOMPLETE;
    private byte[] assembled;

    // ------------------------------------------------------------------ feeding frames

    /**
     * Decodes the text of a scanned QR code and processes the frame.
     *
     * @param encoded URL-safe Base64 text, may be null
     * @return what happened to the frame; undecodable text is {@link Outcome#IGNORED}
     */
    public synchronized Outcome accept(String encoded) {
        Frame frame;
        try {
            frame = FrameCodec.decodeFromString(encoded == null ? null : encoded.trim());
        } catch (FrameFormatException e) {
            totalSeen++;
            invalidCount++;
            return Outcome.IGNORED;
        }
        return accept(frame);
    }

    /**
     * Processes one decoded frame.
     *
     * @param frame the frame, may be null
     * @return what happened to the frame
     */
    public synchronized Outcome accept(Frame frame) {
        totalSeen++;
        if (frame == null) {
            ignoredCount++;
            return Outcome.IGNORED;
        }
        if (!locked) {
            return acceptBeforeLock(frame);
        }
        if (frame.getSessionId() != sessionId) {
            ignoredCount++;
            return Outcome.IGNORED;
        }
        return acceptLocked(frame);
    }

    private Outcome acceptBeforeLock(Frame frame) {
        if (frame.getType() == FrameType.META) {
            FileMeta parsed = parseMeta(frame);
            if (parsed == null) {
                invalidCount++;
                return Outcome.IGNORED;
            }
            lock(frame.getSessionId(), parsed);
            framesAccepted++;
            List<Frame> replay = new ArrayList<>(pending);
            pending.clear();
            for (Frame buffered : replay) {
                if (buffered.getSessionId() == sessionId) {
                    acceptLocked(buffered);
                }
            }
            finishIfComplete();
            return Outcome.ACCEPTED;
        }
        if (pending.size() >= ProtocolConstants.MAX_PENDING_FRAMES) {
            ignoredCount++;
            return Outcome.IGNORED;
        }
        pending.add(frame);
        return Outcome.ACCEPTED;
    }

    private static FileMeta parseMeta(Frame frame) {
        try {
            FileMeta parsed = FileMeta.fromPayload(frame.payloadRef());
            if (!parsed.isValid() || parsed.getTotalChunks() != frame.getTotal()) {
                return null;
            }
            return parsed;
        } catch (FrameFormatException e) {
            return null;
        }
    }

    private void lock(int newSessionId, FileMeta newMeta) {
        locked = true;
        sessionId = newSessionId;
        meta = newMeta;
        int total = newMeta.getTotalChunks();
        chunks = new byte[total][];
        have = new boolean[total];
        states = new byte[total];
        int groups = ParityEncoder.groupCount(total, newMeta.getGroupSize());
        parity = new byte[groups][];
        parityPresent = new boolean[groups];
    }

    private Outcome acceptLocked(Frame frame) {
        switch (frame.getType()) {
            case META:
                duplicateCount++;
                return Outcome.DUPLICATE;
            case DATA:
                return acceptData(frame);
            case PARITY:
                return acceptParity(frame);
            default:
                ignoredCount++;
                return Outcome.IGNORED;
        }
    }

    private Outcome acceptData(Frame frame) {
        int index = frame.getIndex();
        int total = meta.getTotalChunks();
        if (frame.getTotal() != total || index >= total
                || frame.getPayloadLength()
                != Chunker.chunkLength(meta.getFileSize(), meta.getChunkSize(), index)) {
            ignoredCount++;
            return Outcome.IGNORED;
        }
        if (have[index]) {
            duplicateCount++;
            return Outcome.DUPLICATE;
        }
        chunks[index] = frame.getPayload();
        have[index] = true;
        states[index] = STATE_RECEIVED;
        receivedCount++;
        heldCount++;
        framesAccepted++;
        if (meta.getGroupSize() > 0) {
            tryRecoverGroup(ParityEncoder.groupOf(index, meta.getGroupSize()));
        }
        finishIfComplete();
        return Outcome.ACCEPTED;
    }

    private Outcome acceptParity(Frame frame) {
        int group = frame.getIndex();
        if (meta.getGroupSize() == 0 || frame.getTotal() != meta.getTotalChunks()
                || group >= parity.length || frame.getPayloadLength() != meta.getChunkSize()) {
            ignoredCount++;
            return Outcome.IGNORED;
        }
        if (parityPresent[group]) {
            duplicateCount++;
            return Outcome.DUPLICATE;
        }
        parity[group] = frame.getPayload();
        parityPresent[group] = true;
        parityReceivedCount++;
        framesAccepted++;
        tryRecoverGroup(group);
        finishIfComplete();
        return Outcome.ACCEPTED;
    }

    private void tryRecoverGroup(int group) {
        int groupSize = meta.getGroupSize();
        int missing = ParityRecovery.findRecoverable(have, parityPresent[group], group, groupSize);
        if (missing == ParityRecovery.NOT_RECOVERABLE) {
            return;
        }
        int end = ParityEncoder.groupEnd(group, groupSize, meta.getTotalChunks());
        List<byte[]> others = new ArrayList<>(groupSize);
        for (int i = ParityEncoder.groupStart(group, groupSize); i < end; i++) {
            if (i != missing) {
                others.add(chunks[i]);
            }
        }
        int length = Chunker.chunkLength(meta.getFileSize(), meta.getChunkSize(), missing);
        chunks[missing] = ParityRecovery.recover(parity[group], others, length);
        have[missing] = true;
        states[missing] = STATE_RECOVERED;
        recoveredCount++;
        heldCount++;
    }

    private void finishIfComplete() {
        if (!locked || heldCount != meta.getTotalChunks()
                || verification != Verification.INCOMPLETE) {
            return;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream((int) meta.getFileSize());
        for (byte[] chunk : chunks) {
            out.write(chunk, 0, chunk.length);
        }
        byte[] all = out.toByteArray();
        assembled = all.length == meta.getFileSize()
                ? all : Arrays.copyOf(all, (int) meta.getFileSize());
        verification = HashUtil.sha256Hex(assembled).equalsIgnoreCase(meta.getSha256())
                ? Verification.OK : Verification.HASH_MISMATCH;
    }

    // ------------------------------------------------------------------ reporting

    /** @return true once a META frame has locked the session */
    public synchronized boolean isSessionLocked() {
        return locked;
    }

    /** @return the locked session id, or 0 before the first META */
    public synchronized int getSessionId() {
        return sessionId;
    }

    /** @return the file description, or null before the first META */
    public synchronized FileMeta getMeta() {
        return meta;
    }

    /** @return number of DATA chunks announced by META, 0 before META */
    public synchronized int getTotalChunks() {
        return locked ? meta.getTotalChunks() : 0;
    }

    /** @return DATA chunks that arrived as DATA frames */
    public synchronized int getReceivedCount() {
        return receivedCount;
    }

    /** @return chunks rebuilt from parity */
    public synchronized int getRecoveredCount() {
        return recoveredCount;
    }

    /** @return chunks available, received plus recovered */
    public synchronized int getHeldCount() {
        return heldCount;
    }

    /** @return PARITY frames that arrived */
    public synchronized int getParityReceivedCount() {
        return parityReceivedCount;
    }

    /** @return new frames stored (META, DATA and PARITY, each counted once) */
    public synchronized int getFramesAccepted() {
        return framesAccepted;
    }

    /** @return frames dropped as duplicates (including repeated META frames) */
    public synchronized int getDuplicateCount() {
        return duplicateCount;
    }

    /** @return texts or frames that failed magic, version, type, length or CRC checks */
    public synchronized int getInvalidFrameCount() {
        return invalidCount;
    }

    /** @return valid frames ignored: wrong session, out-of-range index, wrong size */
    public synchronized int getIgnoredFrameCount() {
        return ignoredCount;
    }

    /** @return every frame or text ever passed to {@code accept} */
    public synchronized int getTotalSeen() {
        return totalSeen;
    }

    /** @return indices of chunks that are neither received nor recovered, ascending */
    public synchronized List<Integer> getMissingIndices() {
        if (!locked) {
            return Collections.emptyList();
        }
        List<Integer> missing = new ArrayList<>();
        for (int i = 0; i < have.length; i++) {
            if (!have[i]) {
                missing.add(i);
            }
        }
        return missing;
    }

    /** @return progress from 0 to 100; 0 before META, 100 for an empty file after META */
    public synchronized double getPercentComplete() {
        if (!locked) {
            return 0.0;
        }
        int total = meta.getTotalChunks();
        return total == 0 ? 100.0 : 100.0 * heldCount / total;
    }

    /** @return true when META has arrived and every chunk is held */
    public synchronized boolean isComplete() {
        return locked && heldCount == meta.getTotalChunks();
    }

    /** @return INCOMPLETE until the last chunk arrives, then OK or HASH_MISMATCH */
    public synchronized Verification getVerification() {
        return verification;
    }

    /**
     * @return the rebuilt file (trimmed to the announced size) once complete, else null.
     *         The array is shared, do not modify it. Check {@link #getVerification()}
     *         before trusting the content.
     */
    public synchronized byte[] getAssembledBytes() {
        return assembled;
    }

    /**
     * @param index chunk index
     * @return {@link #STATE_MISSING}, {@link #STATE_RECEIVED} or {@link #STATE_RECOVERED}
     * @throws IndexOutOfBoundsException if there is no such chunk
     */
    public synchronized byte getChunkState(int index) {
        if (!locked || index < 0 || index >= states.length) {
            throw new IndexOutOfBoundsException("No chunk " + index);
        }
        return states[index];
    }

    /** @return a copy of all chunk states (empty before META), for drawing the chunk grid */
    public synchronized byte[] getChunkStates() {
        return locked ? states.clone() : new byte[0];
    }

    /** Forgets everything and returns to the initial state, ready for a new transfer. */
    public synchronized void reset() {
        pending.clear();
        locked = false;
        sessionId = 0;
        meta = null;
        chunks = null;
        have = null;
        states = null;
        parity = null;
        parityPresent = null;
        receivedCount = 0;
        recoveredCount = 0;
        heldCount = 0;
        parityReceivedCount = 0;
        framesAccepted = 0;
        duplicateCount = 0;
        invalidCount = 0;
        ignoredCount = 0;
        totalSeen = 0;
        verification = Verification.INCOMPLETE;
        assembled = null;
    }
}
