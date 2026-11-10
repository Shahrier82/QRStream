package com.example.qrstream.protocol;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Random;
import org.junit.Test;

/** Tests for {@link Chunker}. */
public class ChunkerTest {

    private static byte[] sequence(int n) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) {
            b[i] = (byte) i;
        }
        return b;
    }

    private static byte[] join(List<byte[]> chunks) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] c : chunks) {
            out.write(c, 0, c.length);
        }
        return out.toByteArray();
    }

    @Test
    public void emptyFileHasNoChunks() {
        assertTrue(Chunker.split(new byte[0], 400).isEmpty());
        assertEquals(0, Chunker.chunkCount(0, 400));
    }

    @Test
    public void oneByteFileIsOneShortChunk() {
        List<byte[]> chunks = Chunker.split(new byte[]{42}, 400);
        assertEquals(1, chunks.size());
        assertArrayEquals(new byte[]{42}, chunks.get(0));
    }

    @Test
    public void exactMultipleHasNoShortChunk() {
        List<byte[]> chunks = Chunker.split(sequence(800), 400);
        assertEquals(2, chunks.size());
        assertEquals(400, chunks.get(0).length);
        assertEquals(400, chunks.get(1).length);
        assertArrayEquals(sequence(800), join(chunks));
    }

    @Test
    public void multiplePlusOneHasOneByteLastChunk() {
        List<byte[]> chunks = Chunker.split(sequence(801), 400);
        assertEquals(3, chunks.size());
        assertEquals(1, chunks.get(2).length);
        assertArrayEquals(sequence(801), join(chunks));
    }

    @Test
    public void shortLastChunkAndLengthsAgree() {
        int size = 1234;
        int chunkSize = 400;
        List<byte[]> chunks = Chunker.split(sequence(size), chunkSize);
        assertEquals(Chunker.chunkCount(size, chunkSize), chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            assertEquals(Chunker.chunkLength(size, chunkSize, i), chunks.get(i).length);
        }
        assertEquals(34, chunks.get(chunks.size() - 1).length);
    }

    @Test
    public void splitIsDeterministicAndCopiesData() {
        byte[] data = new byte[1000];
        new Random(5).nextBytes(data);
        List<byte[]> a = Chunker.split(data, 128);
        List<byte[]> b = Chunker.split(data, 128);
        for (int i = 0; i < a.size(); i++) {
            assertArrayEquals(a.get(i), b.get(i));
        }
        byte first = a.get(0)[0];
        data[0] ^= 0x7F;
        assertEquals(first, a.get(0)[0]);
    }

    @Test
    public void invalidArgumentsAreRejected() {
        try {
            Chunker.chunkCount(10, 0);
            fail();
        } catch (IllegalArgumentException expected) {
            // good
        }
        try {
            Chunker.chunkCount(-1, 10);
            fail();
        } catch (IllegalArgumentException expected) {
            // good
        }
        try {
            Chunker.chunkLength(10, 5, 2);
            fail();
        } catch (IllegalArgumentException expected) {
            // good
        }
    }
}
