package com.example.qrstream.protocol;

/**
 * URL-safe Base64 without padding (RFC 4648 section 5).
 *
 * <p>{@code java.util.Base64} needs API 26 but this app supports API 24, and the protocol
 * package may not use Android classes, so the codec is implemented here.</p>
 */
public final class Base64Url {

    private static final char[] ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_".toCharArray();
    private static final int[] LOOKUP = new int[128];
    private static final int INVALID = -1;
    private static final int SIX_BITS = 0x3F;
    private static final int BITS_PER_CHAR = 6;
    private static final int BITS_PER_BYTE = 8;
    private static final char PADDING = '=';

    static {
        java.util.Arrays.fill(LOOKUP, INVALID);
        for (int i = 0; i < ALPHABET.length; i++) {
            LOOKUP[ALPHABET[i]] = i;
        }
    }

    private Base64Url() {
    }

    /**
     * @param data bytes to encode, not null
     * @return text using only A-Z a-z 0-9 - _ with no padding
     */
    public static String encode(byte[] data) {
        StringBuilder sb = new StringBuilder((data.length * 4 + 2) / 3);
        int i = 0;
        while (i + 3 <= data.length) {
            int v = ((data[i] & ProtocolConstants.BYTE_MASK) << 16)
                    | ((data[i + 1] & ProtocolConstants.BYTE_MASK) << BITS_PER_BYTE)
                    | (data[i + 2] & ProtocolConstants.BYTE_MASK);
            sb.append(ALPHABET[(v >>> 18) & SIX_BITS]);
            sb.append(ALPHABET[(v >>> 12) & SIX_BITS]);
            sb.append(ALPHABET[(v >>> BITS_PER_CHAR) & SIX_BITS]);
            sb.append(ALPHABET[v & SIX_BITS]);
            i += 3;
        }
        int rest = data.length - i;
        if (rest == 1) {
            int v = (data[i] & ProtocolConstants.BYTE_MASK) << 16;
            sb.append(ALPHABET[(v >>> 18) & SIX_BITS]);
            sb.append(ALPHABET[(v >>> 12) & SIX_BITS]);
        } else if (rest == 2) {
            int v = ((data[i] & ProtocolConstants.BYTE_MASK) << 16)
                    | ((data[i + 1] & ProtocolConstants.BYTE_MASK) << BITS_PER_BYTE);
            sb.append(ALPHABET[(v >>> 18) & SIX_BITS]);
            sb.append(ALPHABET[(v >>> 12) & SIX_BITS]);
            sb.append(ALPHABET[(v >>> BITS_PER_CHAR) & SIX_BITS]);
        }
        return sb.toString();
    }

    /**
     * Decodes text produced by {@link #encode(byte[])}. Trailing '=' padding is tolerated.
     *
     * @param text Base64url text, not null
     * @return decoded bytes
     * @throws IllegalArgumentException if the text has an illegal character or length
     */
    public static byte[] decode(String text) {
        int end = text.length();
        while (end > 0 && text.charAt(end - 1) == PADDING) {
            end--;
        }
        if (end % 4 == 1) {
            throw new IllegalArgumentException("Invalid Base64 length");
        }
        byte[] out = new byte[end * BITS_PER_CHAR / BITS_PER_BYTE];
        int acc = 0;
        int bits = 0;
        int pos = 0;
        for (int i = 0; i < end; i++) {
            char c = text.charAt(i);
            int val = c < LOOKUP.length ? LOOKUP[c] : INVALID;
            if (val == INVALID) {
                throw new IllegalArgumentException("Invalid Base64 character at " + i);
            }
            acc = (acc << BITS_PER_CHAR) | val;
            bits += BITS_PER_CHAR;
            if (bits >= BITS_PER_BYTE) {
                bits -= BITS_PER_BYTE;
                out[pos++] = (byte) ((acc >>> bits) & ProtocolConstants.BYTE_MASK);
            }
        }
        return out;
    }
}
