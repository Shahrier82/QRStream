package com.example.qrstream.protocol;

/** The three kinds of frame carried by a QR code. */
public enum FrameType {
    /** File description: name, size, chunk size, hash. */
    META(ProtocolConstants.TYPE_CODE_META),
    /** One chunk of file content. */
    DATA(ProtocolConstants.TYPE_CODE_DATA),
    /** XOR of every DATA chunk in one group. */
    PARITY(ProtocolConstants.TYPE_CODE_PARITY);

    private final int code;

    FrameType(int code) {
        this.code = code;
    }

    /** @return the single-byte wire code of this type */
    public int getCode() {
        return code;
    }

    /**
     * Maps a wire code back to a type.
     *
     * @param code value read from the type byte
     * @return the matching type, or {@code null} if the code is unknown
     */
    public static FrameType fromCode(int code) {
        for (FrameType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        return null;
    }
}
