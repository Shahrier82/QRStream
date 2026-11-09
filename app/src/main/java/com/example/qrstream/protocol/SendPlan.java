package com.example.qrstream.protocol;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * The complete, ordered list of frames the sender loops over.
 *
 * <p>Order: META, then for each group its K DATA frames followed by its PARITY frame. The
 * META frame is repeated whenever {@link ProtocolConstants#META_REPEAT_INTERVAL} other
 * frames have gone by, so a receiver that starts late can still join. No META is placed at
 * the very end, because the loop wraps around to the META at its start.</p>
 *
 * <p>Complexity: building the plan is O(n) time for the SHA-256, chunking and parity
 * (n = file size) and O(n + n/K) space for the stored frames.</p>
 */
public final class SendPlan {

    private final int sessionId;
    private final FileMeta meta;
    private final Frame metaFrame;
    private final List<Frame> loopFrames;
    private final int dataFrameCount;
    private final int parityFrameCount;

    private SendPlan(int sessionId, FileMeta meta, Frame metaFrame, List<Frame> loopFrames,
                     int dataFrameCount, int parityFrameCount) {
        this.sessionId = sessionId;
        this.meta = meta;
        this.metaFrame = metaFrame;
        this.loopFrames = loopFrames;
        this.dataFrameCount = dataFrameCount;
        this.parityFrameCount = parityFrameCount;
    }

    /**
     * Builds the plan for one file.
     *
     * @param fileName  display name (shortened if longer than 128 characters)
     * @param mimeType  MIME type, may be null
     * @param data      file bytes, at most 200 KB
     * @param chunkSize bytes per DATA chunk
     * @param groupSize parity group size K, or 0 for no parity
     * @param sessionId id shared by every frame of this transfer
     * @return the immutable plan
     * @throws IllegalArgumentException if the file is too large or a parameter is invalid
     */
    public static SendPlan build(String fileName, String mimeType, byte[] data, int chunkSize,
                                 int groupSize, int sessionId) {
        if (data.length > ProtocolConstants.MAX_FILE_SIZE_BYTES) {
            throw new IllegalArgumentException("File is larger than "
                    + ProtocolConstants.MAX_FILE_SIZE_BYTES / 1024 + " KB");
        }
        if (chunkSize < 1 || chunkSize > ProtocolConstants.MAX_PAYLOAD_LENGTH) {
            throw new IllegalArgumentException("Chunk size out of range: " + chunkSize);
        }
        if (groupSize < 0) {
            throw new IllegalArgumentException("Group size must not be negative");
        }
        int totalChunks = Chunker.chunkCount(data.length, chunkSize);
        if (totalChunks > ProtocolConstants.MAX_UNSIGNED_SHORT) {
            throw new IllegalArgumentException("Too many chunks: " + totalChunks);
        }

        List<byte[]> chunks = Chunker.split(data, chunkSize);
        List<byte[]> parities = ParityEncoder.encodeAll(chunks, groupSize, chunkSize);
        FileMeta meta = new FileMeta(FileMeta.limitName(fileName == null ? "" : fileName),
                data.length, mimeType, chunkSize, totalChunks, groupSize,
                HashUtil.sha256Hex(data));
        Frame metaFrame = Frame.meta(sessionId, totalChunks, meta.toPayload());

        List<Frame> base = new ArrayList<>(totalChunks + parities.size());
        int groups = ParityEncoder.groupCount(totalChunks, groupSize);
        if (groups == 0) {
            for (int i = 0; i < totalChunks; i++) {
                base.add(Frame.data(sessionId, i, totalChunks, chunks.get(i)));
            }
        } else {
            for (int g = 0; g < groups; g++) {
                int end = ParityEncoder.groupEnd(g, groupSize, totalChunks);
                for (int i = ParityEncoder.groupStart(g, groupSize); i < end; i++) {
                    base.add(Frame.data(sessionId, i, totalChunks, chunks.get(i)));
                }
                base.add(Frame.parity(sessionId, g, totalChunks, parities.get(g)));
            }
        }

        List<Frame> loop = new ArrayList<>(base.size() + base.size() / ProtocolConstants.META_REPEAT_INTERVAL + 1);
        loop.add(metaFrame);
        int sinceMeta = 0;
        for (Frame frame : base) {
            if (sinceMeta == ProtocolConstants.META_REPEAT_INTERVAL) {
                loop.add(metaFrame);
                sinceMeta = 0;
            }
            loop.add(frame);
            sinceMeta++;
        }
        return new SendPlan(sessionId, meta, metaFrame, Collections.unmodifiableList(loop),
                totalChunks, parities.size());
    }

    /**
     * Predicts the length of one loop pass without building it, for the Send screen preview.
     * Complexity: O(1).
     *
     * @param fileSize  file size in bytes
     * @param chunkSize bytes per DATA chunk
     * @param groupSize parity group size K, or 0 for no parity
     * @return the number of frames {@link #getLoopLength()} would report
     */
    public static int estimateLoopLength(long fileSize, int chunkSize, int groupSize) {
        int chunks = Chunker.chunkCount(fileSize, chunkSize);
        int base = chunks + ParityEncoder.groupCount(chunks, groupSize);
        int metaRepeats = base == 0 ? 0 : (base - 1) / ProtocolConstants.META_REPEAT_INTERVAL;
        return 1 + base + metaRepeats;
    }

    /**
     * @param random source of randomness, not null
     * @return a fresh random session id
     */
    public static int newSessionId(Random random) {
        return random.nextInt();
    }

    /** @return the session id */
    public int getSessionId() {
        return sessionId;
    }

    /** @return the file description */
    public FileMeta getMeta() {
        return meta;
    }

    /** @return the META frame */
    public Frame getMetaFrame() {
        return metaFrame;
    }

    /** @return the frames of one pass, in broadcast order (unmodifiable) */
    public List<Frame> getLoopFrames() {
        return loopFrames;
    }

    /** @return frames in one pass, including repeated META frames */
    public int getLoopLength() {
        return loopFrames.size();
    }

    /** @return number of DATA frames per pass */
    public int getDataFrameCount() {
        return dataFrameCount;
    }

    /** @return number of PARITY frames per pass */
    public int getParityFrameCount() {
        return parityFrameCount;
    }

    /**
     * @return extra frames per pass relative to DATA frames, in percent; 0 for an empty file
     */
    public double getOverheadPercent() {
        if (dataFrameCount == 0) {
            return 0.0;
        }
        return 100.0 * (loopFrames.size() - dataFrameCount) / dataFrameCount;
    }
}
