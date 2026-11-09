package com.example.qrstream.protocol;

/**
 * Every numeric constant of the QR Stream wire protocol and its user-facing limits.
 *
 * <p>This class lives in the pure-Java {@code protocol} package: it must never import
 * anything from {@code android.*}.</p>
 */
public final class ProtocolConstants {

    private ProtocolConstants() {
        // Constants holder, not instantiable.
    }

    // ---------------------------------------------------------------- wire format

    /** Frame magic number, the ASCII letters "QS". */
    public static final int MAGIC = 0x5153;
    /** Protocol version carried in every frame. */
    public static final int VERSION = 1;

    /** Size of the magic field in bytes. */
    public static final int MAGIC_SIZE = 2;
    /** Size of the version field in bytes. */
    public static final int VERSION_SIZE = 1;
    /** Size of the type field in bytes. */
    public static final int TYPE_SIZE = 1;
    /** Size of the session id field in bytes. */
    public static final int SESSION_ID_SIZE = 4;
    /** Size of the index field in bytes. */
    public static final int INDEX_SIZE = 2;
    /** Size of the total-chunks field in bytes. */
    public static final int TOTAL_SIZE = 2;
    /** Size of the payload length field in bytes. */
    public static final int PAYLOAD_LENGTH_SIZE = 2;
    /** Size of the trailing CRC-32 in bytes. */
    public static final int CRC_SIZE = 4;

    /** Header size: magic + version + type + sessionId + index + total + payloadLength = 14. */
    public static final int HEADER_SIZE = MAGIC_SIZE + VERSION_SIZE + TYPE_SIZE
            + SESSION_ID_SIZE + INDEX_SIZE + TOTAL_SIZE + PAYLOAD_LENGTH_SIZE;
    /** Smallest valid frame: header + empty payload + CRC. */
    public static final int MIN_FRAME_SIZE = HEADER_SIZE + CRC_SIZE;

    /** Largest value of an unsigned 16-bit field. */
    public static final int MAX_UNSIGNED_SHORT = 0xFFFF;
    /** Largest payload that fits the 16-bit payloadLength field. */
    public static final int MAX_PAYLOAD_LENGTH = MAX_UNSIGNED_SHORT;
    /** Mask that turns a signed int into an unsigned 32-bit value held in a long. */
    public static final long UNSIGNED_INT_MASK = 0xFFFFFFFFL;
    /** Mask that turns a signed byte into an unsigned 8-bit value held in an int. */
    public static final int BYTE_MASK = 0xFF;

    /** Wire code of a META frame. */
    public static final int TYPE_CODE_META = 0;
    /** Wire code of a DATA frame. */
    public static final int TYPE_CODE_DATA = 1;
    /** Wire code of a PARITY frame. */
    public static final int TYPE_CODE_PARITY = 2;

    // ---------------------------------------------------------------- transfer parameters

    /** Default chunk size in bytes. */
    public static final int DEFAULT_CHUNK_SIZE = 400;
    /** Smallest chunk size the UI offers. */
    public static final int MIN_CHUNK_SIZE = 200;
    /** Largest chunk size the UI offers. */
    public static final int MAX_CHUNK_SIZE = 800;

    /** Group size value meaning "no parity frames at all". */
    public static final int NO_PARITY = 0;
    /** Default parity group size K. */
    public static final int DEFAULT_GROUP_SIZE = 4;
    /** Smallest group size the UI offers. */
    public static final int MIN_GROUP_SIZE = 2;
    /** Largest group size the UI offers. */
    public static final int MAX_GROUP_SIZE = 8;

    /** Default frames per second. */
    public static final int DEFAULT_FPS = 4;
    /** Slowest frame rate. */
    public static final int MIN_FPS = 1;
    /** Fastest frame rate. */
    public static final int MAX_FPS = 10;

    /** The META frame is repeated after this many other frames. */
    public static final int META_REPEAT_INTERVAL = 10;

    /** Maximum supported file size: 200 KB. Keeps a transfer short and memory use bounded. */
    public static final int MAX_FILE_SIZE_BYTES = 200 * 1024;
    /** Longest file name carried in META; longer names are shortened (extension kept). */
    public static final int MAX_FILE_NAME_LENGTH = 128;
    /** Longest file extension preserved when a name is shortened. */
    public static final int MAX_EXTENSION_LENGTH = 16;

    /** Minimum rendered QR code edge length in pixels. */
    public static final int QR_SIZE_PX = 600;
    /** Quiet-zone width around the QR code, in modules (QR specification says 4). */
    public static final int QR_QUIET_ZONE_MODULES = 4;

    // ---------------------------------------------------------------- receiver limits

    /** Frames kept while waiting for the first META frame. */
    public static final int MAX_PENDING_FRAMES = 2048;
    /** Length of a SHA-256 digest written as hex. */
    public static final int SHA256_HEX_LENGTH = 64;
    /** Default MIME type when the sender could not determine one. */
    public static final String DEFAULT_MIME_TYPE = "application/octet-stream";
}
