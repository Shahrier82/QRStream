package com.example.qrstream.protocol;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** SHA-256 helper. */
public final class HashUtil {

    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private static final int NIBBLE_BITS = 4;
    private static final int NIBBLE_MASK = 0x0F;

    private HashUtil() {
    }

    /**
     * @param data bytes to hash, not null
     * @return the 32-byte SHA-256 digest
     */
    public static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            // Every Java and Android runtime is required to provide SHA-256.
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /**
     * @param data bytes to hash, not null
     * @return the digest as 64 lowercase hex characters
     */
    public static String sha256Hex(byte[] data) {
        return toHex(sha256(data));
    }

    /**
     * @param bytes bytes to convert, not null
     * @return lowercase hex text, two characters per byte
     */
    public static String toHex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & ProtocolConstants.BYTE_MASK;
            out[i * 2] = HEX[v >>> NIBBLE_BITS];
            out[i * 2 + 1] = HEX[v & NIBBLE_MASK];
        }
        return new String(out);
    }
}
