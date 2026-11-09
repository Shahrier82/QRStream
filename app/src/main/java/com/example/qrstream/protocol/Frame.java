package com.example.qrstream.protocol;

import java.util.Arrays;

/**
 * One immutable protocol frame (the content of a single QR code).
 *
 * <p>The payload is defensively copied on the way in and on the way out, so a frame can
 * never be changed after construction.</p>
 */
public final class Frame {

    private final FrameType type;
    private final int sessionId;
    private final int index;
    private final int total;
    private final byte[] payload;

    /**
     * @param type      frame type, not null
     * @param sessionId random id shared by all frames of one transfer
     * @param index     chunk index (DATA), group index (PARITY) or 0 (META); 0..65535
     * @param total     total number of DATA chunks in the transfer; 0..65535
     * @param payload   payload bytes, not null, at most 65535 bytes
     * @throws IllegalArgumentException if a field is out of range
     */
    public Frame(FrameType type, int sessionId, int index, int total, byte[] payload) {
        if (type == null) {
            throw new IllegalArgumentException("Frame type must not be null");
        }
        if (payload == null) {
            throw new IllegalArgumentException("Frame payload must not be null");
        }
        if (index < 0 || index > ProtocolConstants.MAX_UNSIGNED_SHORT) {
            throw new IllegalArgumentException("Frame index out of range: " + index);
        }
        if (total < 0 || total > ProtocolConstants.MAX_UNSIGNED_SHORT) {
            throw new IllegalArgumentException("Frame total out of range: " + total);
        }
        if (payload.length > ProtocolConstants.MAX_PAYLOAD_LENGTH) {
            throw new IllegalArgumentException("Frame payload too long: " + payload.length);
        }
        this.type = type;
        this.sessionId = sessionId;
        this.index = index;
        this.total = total;
        this.payload = payload.clone();
    }

    /** @return a META frame (index 0) */
    public static Frame meta(int sessionId, int total, byte[] payload) {
        return new Frame(FrameType.META, sessionId, 0, total, payload);
    }

    /** @return a DATA frame for chunk {@code index} */
    public static Frame data(int sessionId, int index, int total, byte[] chunk) {
        return new Frame(FrameType.DATA, sessionId, index, total, chunk);
    }

    /** @return a PARITY frame for group {@code groupIndex} */
    public static Frame parity(int sessionId, int groupIndex, int total, byte[] parity) {
        return new Frame(FrameType.PARITY, sessionId, groupIndex, total, parity);
    }

    /** @return the frame type */
    public FrameType getType() {
        return type;
    }

    /** @return the session id */
    public int getSessionId() {
        return sessionId;
    }

    /** @return chunk index, group index, or 0 for META */
    public int getIndex() {
        return index;
    }

    /** @return total number of DATA chunks announced by the sender */
    public int getTotal() {
        return total;
    }

    /** @return a copy of the payload */
    public byte[] getPayload() {
        return payload.clone();
    }

    /** @return payload length in bytes, without copying */
    public int getPayloadLength() {
        return payload.length;
    }

    /**
     * Package-private zero-copy payload access for the protocol classes.
     * Callers must not modify the returned array.
     */
    byte[] payloadRef() {
        return payload;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Frame)) {
            return false;
        }
        Frame other = (Frame) o;
        return type == other.type && sessionId == other.sessionId && index == other.index
                && total == other.total && Arrays.equals(payload, other.payload);
    }

    @Override
    public int hashCode() {
        int result = type.hashCode();
        result = 31 * result + sessionId;
        result = 31 * result + index;
        result = 31 * result + total;
        result = 31 * result + Arrays.hashCode(payload);
        return result;
    }

    @Override
    public String toString() {
        return "Frame{" + type + ", session=" + Integer.toHexString(sessionId)
                + ", index=" + index + ", total=" + total
                + ", payload=" + payload.length + "B}";
    }
}
