package com.example.qrstream.protocol;

import java.util.zip.CRC32;

/** CRC-32 helper (IEEE 802.3 polynomial, same as {@link java.util.zip.CRC32}). */
public final class Crc32Util {

    private Crc32Util() {
    }

    /**
     * Computes the CRC-32 of a slice of an array.
     *
     * @param data   source bytes, not null
     * @param offset first byte to include
     * @param length number of bytes to include
     * @return the checksum as an unsigned 32-bit value in a long
     */
    public static long compute(byte[] data, int offset, int length) {
        CRC32 crc = new CRC32();
        crc.update(data, offset, length);
        return crc.getValue();
    }

    /**
     * Computes the CRC-32 of a whole array.
     *
     * @param data source bytes, not null
     * @return the checksum as an unsigned 32-bit value in a long
     */
    public static long compute(byte[] data) {
        return compute(data, 0, data.length);
    }
}
