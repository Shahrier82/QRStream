package com.example.qrstream.protocol;

import java.nio.ByteBuffer;

/**
 * Converts {@link Frame}s to and from the wire format.
 *
 * <p>Layout (big-endian): magic(2) version(1) type(1) sessionId(4) index(2) total(2)
 * payloadLength(2) payload(N) crc32(4). The CRC covers every preceding byte. For a QR
 * code the bytes are written as URL-safe Base64 without padding.</p>
 *
 * <p>Complexity: encode and decode are O(n) time and O(n) space for a frame of n bytes.</p>
 */
public final class FrameCodec {

    private FrameCodec() {
    }

    /**
     * Serializes a frame to bytes.
     *
     * @param frame frame to serialize, not null
     * @return header + payload + CRC
     */
    public static byte[] encode(Frame frame) {
        byte[] payload = frame.payloadRef();
        int bodyLength = ProtocolConstants.HEADER_SIZE + payload.length;
        ByteBuffer buf = ByteBuffer.allocate(bodyLength + ProtocolConstants.CRC_SIZE);
        buf.putShort((short) ProtocolConstants.MAGIC);
        buf.put((byte) ProtocolConstants.VERSION);
        buf.put((byte) frame.getType().getCode());
        buf.putInt(frame.getSessionId());
        buf.putShort((short) frame.getIndex());
        buf.putShort((short) frame.getTotal());
        buf.putShort((short) payload.length);
        buf.put(payload);
        long crc = Crc32Util.compute(buf.array(), 0, bodyLength);
        buf.putInt((int) crc);
        return buf.array();
    }

    /**
     * Parses and validates a frame.
     *
     * @param bytes serialized frame
     * @return the decoded frame
     * @throws FrameFormatException if the length, magic, version, type or CRC is wrong
     */
    public static Frame decode(byte[] bytes) throws FrameFormatException {
        if (bytes == null || bytes.length < ProtocolConstants.MIN_FRAME_SIZE) {
            throw new FrameFormatException("Frame too short");
        }
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        int magic = buf.getShort() & ProtocolConstants.MAX_UNSIGNED_SHORT;
        if (magic != ProtocolConstants.MAGIC) {
            throw new FrameFormatException("Wrong magic number");
        }
        int version = buf.get() & ProtocolConstants.BYTE_MASK;
        if (version != ProtocolConstants.VERSION) {
            throw new FrameFormatException("Unsupported protocol version " + version);
        }
        int typeCode = buf.get() & ProtocolConstants.BYTE_MASK;
        FrameType type = FrameType.fromCode(typeCode);
        if (type == null) {
            throw new FrameFormatException("Unknown frame type " + typeCode);
        }
        int sessionId = buf.getInt();
        int index = buf.getShort() & ProtocolConstants.MAX_UNSIGNED_SHORT;
        int total = buf.getShort() & ProtocolConstants.MAX_UNSIGNED_SHORT;
        int payloadLength = buf.getShort() & ProtocolConstants.MAX_UNSIGNED_SHORT;
        int bodyLength = ProtocolConstants.HEADER_SIZE + payloadLength;
        if (bytes.length != bodyLength + ProtocolConstants.CRC_SIZE) {
            throw new FrameFormatException("Payload length does not match frame size");
        }
        long expectedCrc = buf.getInt(bodyLength) & ProtocolConstants.UNSIGNED_INT_MASK;
        long actualCrc = Crc32Util.compute(bytes, 0, bodyLength);
        if (expectedCrc != actualCrc) {
            throw new FrameFormatException("CRC mismatch");
        }
        byte[] payload = new byte[payloadLength];
        buf.get(payload);
        return new Frame(type, sessionId, index, total, payload);
    }

    /**
     * Serializes a frame to the text stored in a QR code.
     *
     * @param frame frame to serialize, not null
     * @return URL-safe Base64 text without padding
     */
    public static String encodeToString(Frame frame) {
        return Base64Url.encode(encode(frame));
    }

    /**
     * Parses the text read from a QR code.
     *
     * @param text URL-safe Base64 text
     * @return the decoded frame
     * @throws FrameFormatException if the text is not a valid frame
     */
    public static Frame decodeFromString(String text) throws FrameFormatException {
        if (text == null) {
            throw new FrameFormatException("Text is null");
        }
        byte[] bytes;
        try {
            bytes = Base64Url.decode(text);
        } catch (IllegalArgumentException e) {
            throw new FrameFormatException("Text is not valid Base64", e);
        }
        return decode(bytes);
    }
}
