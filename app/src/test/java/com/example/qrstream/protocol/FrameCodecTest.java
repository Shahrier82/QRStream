package com.example.qrstream.protocol;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Random;
import org.junit.Test;

/** Tests for {@link FrameCodec}, {@link Base64Url}, {@link FileMeta} and the CRC/hash helpers. */
public class FrameCodecTest {

    private static final int SESSION = 0xCAFEBABE;

    private static byte[] randomBytes(int n, long seed) {
        byte[] b = new byte[n];
        new Random(seed).nextBytes(b);
        return b;
    }

    @Test
    public void roundTripAllTypes() throws Exception {
        Frame[] frames = {
                Frame.meta(SESSION, 7, randomBytes(120, 1)),
                Frame.data(SESSION, 3, 7, randomBytes(400, 2)),
                Frame.parity(SESSION, 1, 7, randomBytes(400, 3)),
                Frame.data(SESSION, 65535, 65535, new byte[0])
        };
        for (Frame frame : frames) {
            assertEquals(frame, FrameCodec.decode(FrameCodec.encode(frame)));
            assertEquals(frame, FrameCodec.decodeFromString(FrameCodec.encodeToString(frame)));
        }
    }

    @Test
    public void wireLayoutMatchesSpecification() {
        Frame frame = Frame.data(0x01020304, 0x0506, 0x0708, new byte[]{(byte) 0x09, (byte) 0x0A});
        byte[] bytes = FrameCodec.encode(frame);
        assertEquals(ProtocolConstants.HEADER_SIZE + 2 + ProtocolConstants.CRC_SIZE, bytes.length);
        assertEquals(0x51, bytes[0] & 0xFF);
        assertEquals(0x53, bytes[1] & 0xFF);
        assertEquals(1, bytes[2]);
        assertEquals(1, bytes[3]);
        assertEquals(0x01, bytes[4]);
        assertEquals(0x04, bytes[7]);
        assertEquals(0x05, bytes[8]);
        assertEquals(0x06, bytes[9]);
        assertEquals(0x07, bytes[10]);
        assertEquals(0x08, bytes[11]);
        assertEquals(0x00, bytes[12]);
        assertEquals(0x02, bytes[13]);
        assertEquals(0x09, bytes[14]);
        long crc = Crc32Util.compute(bytes, 0, bytes.length - 4);
        long stored = ((bytes[bytes.length - 4] & 0xFFL) << 24) | ((bytes[bytes.length - 3] & 0xFFL) << 16)
                | ((bytes[bytes.length - 2] & 0xFFL) << 8) | (bytes[bytes.length - 1] & 0xFFL);
        assertEquals(crc, stored);
    }

    @Test
    public void corruptedPayloadIsRejectedByCrc() {
        byte[] bytes = FrameCodec.encode(Frame.data(SESSION, 0, 1, randomBytes(50, 4)));
        bytes[ProtocolConstants.HEADER_SIZE + 10] ^= 0x01;
        assertRejected(bytes, "CRC");
    }

    @Test
    public void corruptedCrcFieldIsRejected() {
        byte[] bytes = FrameCodec.encode(Frame.data(SESSION, 0, 1, randomBytes(50, 5)));
        bytes[bytes.length - 1] ^= 0x01;
        assertRejected(bytes, "CRC");
    }

    @Test
    public void everySingleBitFlipIsRejected() {
        byte[] original = FrameCodec.encode(Frame.parity(SESSION, 2, 9, randomBytes(30, 6)));
        for (int i = 0; i < original.length; i++) {
            for (int bit = 0; bit < 8; bit++) {
                byte[] copy = original.clone();
                copy[i] ^= (byte) (1 << bit);
                try {
                    FrameCodec.decode(copy);
                    fail("Bit flip at byte " + i + " bit " + bit + " was accepted");
                } catch (FrameFormatException expected) {
                    // good
                }
            }
        }
    }

    @Test
    public void wrongMagicIsRejected() {
        byte[] bytes = FrameCodec.encode(Frame.meta(SESSION, 0, new byte[3]));
        bytes[0] = 0x00;
        assertRejected(bytes, "magic");
    }

    @Test
    public void wrongVersionAndTypeAreRejected() {
        byte[] bytes = FrameCodec.encode(Frame.meta(SESSION, 0, new byte[3]));
        byte[] badVersion = bytes.clone();
        badVersion[2] = 9;
        assertRejected(badVersion, "version");
        byte[] badType = bytes.clone();
        badType[3] = 7;
        assertRejected(badType, "type");
    }

    @Test
    public void truncatedAndExtendedFramesAreRejected() {
        byte[] bytes = FrameCodec.encode(Frame.data(SESSION, 0, 1, randomBytes(20, 7)));
        assertRejected(java.util.Arrays.copyOf(bytes, bytes.length - 1), "");
        assertRejected(java.util.Arrays.copyOf(bytes, bytes.length + 1), "");
        assertRejected(new byte[5], "");
        assertRejected(null, "");
    }

    @Test
    public void invalidTextIsRejected() {
        for (String text : new String[]{null, "", "!!!", "QVJ", "not base64 at all"}) {
            try {
                FrameCodec.decodeFromString(text);
                fail("Accepted: " + text);
            } catch (FrameFormatException expected) {
                // good
            }
        }
    }

    @Test
    public void encodedTextUsesOnlyUrlSafeCharactersWithoutPadding() {
        for (int len = 0; len < 40; len++) {
            String text = Base64Url.encode(randomBytes(len, len));
            assertTrue(text.matches("[A-Za-z0-9_-]*"));
            assertFalse(text.contains("="));
            assertArrayEquals(randomBytes(len, len), Base64Url.decode(text));
        }
    }

    @Test
    public void base64MatchesKnownVectors() {
        assertEquals("", Base64Url.encode(new byte[0]));
        assertEquals("Zg", Base64Url.encode("f".getBytes()));
        assertEquals("Zm8", Base64Url.encode("fo".getBytes()));
        assertEquals("Zm9v", Base64Url.encode("foo".getBytes()));
        assertEquals("Zm9vYg", Base64Url.encode("foob".getBytes()));
        assertEquals("-_8", Base64Url.encode(new byte[]{(byte) 0xFB, (byte) 0xFF}));
        assertArrayEquals(new byte[]{(byte) 0xFB, (byte) 0xFF}, Base64Url.decode("-_8="));
    }

    @Test
    public void frameRejectsOutOfRangeFields() {
        try {
            Frame.data(1, 65536, 1, new byte[0]);
            fail();
        } catch (IllegalArgumentException expected) {
            // good
        }
        try {
            Frame.data(1, 0, -1, new byte[0]);
            fail();
        } catch (IllegalArgumentException expected) {
            // good
        }
    }

    @Test
    public void framePayloadIsDefensivelyCopied() {
        byte[] payload = {1, 2, 3};
        Frame frame = Frame.data(1, 0, 1, payload);
        payload[0] = 99;
        assertEquals(1, frame.getPayload()[0]);
        frame.getPayload()[0] = 55;
        assertEquals(1, frame.getPayload()[0]);
    }

    @Test
    public void fileMetaJsonRoundTripWithSpecialCharacters() throws Exception {
        FileMeta meta = new FileMeta("my \"report\" \\ v2\né中.txt", 1234, "text/plain",
                400, 4, 4, HashUtil.sha256Hex(new byte[0]));
        FileMeta parsed = FileMeta.fromPayload(meta.toPayload());
        assertEquals(meta, parsed);
        assertTrue(parsed.isValid());
    }

    @Test
    public void fileMetaRejectsMalformedJson() {
        String[] bad = {"", "[]", "{", "{\"fileName\":1}", "{\"fileName\":\"a\"} trailing",
                "{\"fileName\":\"a\",\"fileSize\":1.5}", "{\"a\":{\"b\":1}}"};
        for (String json : bad) {
            try {
                FileMeta.fromPayload(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                fail("Accepted: " + json);
            } catch (FrameFormatException expected) {
                // good
            }
        }
    }

    @Test
    public void fileMetaValidityChecksRanges() {
        String hash = HashUtil.sha256Hex(new byte[0]);
        assertTrue(new FileMeta("a", 0, null, 400, 0, 0, hash).isValid());
        assertFalse(new FileMeta("a", -1, null, 400, 0, 0, hash).isValid());
        assertFalse(new FileMeta("a", ProtocolConstants.MAX_FILE_SIZE_BYTES + 1, null, 400, 513, 0, hash).isValid());
        assertFalse(new FileMeta("a", 1000, null, 0, 1, 0, hash).isValid());
        assertFalse(new FileMeta("a", 1000, null, 400, 5, 0, hash).isValid());
        assertFalse(new FileMeta("a", 1000, null, 400, 3, -1, hash).isValid());
        assertFalse(new FileMeta("a", 1000, null, 400, 3, 0, "abc").isValid());
    }

    @Test
    public void longFileNamesKeepTheirExtension() {
        StringBuilder name = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            name.append('x');
        }
        String shortened = FileMeta.limitName(name + ".pdf");
        assertEquals(ProtocolConstants.MAX_FILE_NAME_LENGTH, shortened.length());
        assertTrue(shortened.endsWith(".pdf"));
        assertEquals("short.txt", FileMeta.limitName("short.txt"));
    }

    @Test
    public void crcAndHashKnownValues() {
        assertEquals(0xCBF43926L, Crc32Util.compute("123456789".getBytes()));
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                HashUtil.sha256Hex(new byte[0]));
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                HashUtil.sha256Hex("abc".getBytes()));
    }

    private static void assertRejected(byte[] bytes, String reasonFragment) {
        try {
            FrameCodec.decode(bytes);
            fail("Frame was accepted");
        } catch (FrameFormatException e) {
            assertTrue("Unexpected message: " + e.getMessage(),
                    e.getMessage().toLowerCase().contains(reasonFragment.toLowerCase()));
        }
    }
}
