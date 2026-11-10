package com.example.qrstream.protocol;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.Test;

/** Tests for {@link ParityEncoder}, {@link ParityRecovery} and parity behaviour of the reassembler. */
public class ParityRecoveryTest {

    private static final int CHUNK = 64;
    private static final int K = 4;
    private static final int SESSION = 77;

    private static byte[] file(int size, long seed) {
        byte[] b = new byte[size];
        new Random(seed).nextBytes(b);
        return b;
    }

    private static List<Frame> frames(byte[] data, int chunkSize, int groupSize) {
        return SendPlan.build("f.bin", null, data, chunkSize, groupSize, SESSION).getLoopFrames();
    }

    private static List<Frame> without(List<Frame> frames, FrameType type, int... indices) {
        List<Frame> out = new ArrayList<>();
        for (Frame f : frames) {
            boolean drop = false;
            if (f.getType() == type) {
                for (int index : indices) {
                    if (f.getIndex() == index) {
                        drop = true;
                    }
                }
            }
            if (!drop) {
                out.add(f);
            }
        }
        return out;
    }

    private static Reassembler feed(List<Frame> frames) {
        Reassembler r = new Reassembler();
        for (Frame f : frames) {
            r.accept(f);
        }
        return r;
    }

    @Test
    public void groupArithmetic() {
        assertEquals(0, ParityEncoder.groupCount(10, 0));
        assertEquals(0, ParityEncoder.groupCount(0, 4));
        assertEquals(3, ParityEncoder.groupCount(10, 4));
        assertEquals(2, ParityEncoder.groupCount(8, 4));
        assertEquals(1, ParityEncoder.groupOf(5, 4));
        assertEquals(4, ParityEncoder.groupStart(1, 4));
        assertEquals(8, ParityEncoder.groupEnd(1, 4, 100));
        assertEquals(10, ParityEncoder.groupEnd(2, 4, 10));
    }

    @Test
    public void parityIsXorOfZeroPaddedChunks() {
        byte[] data = file(CHUNK * 3 + 10, 1);
        List<byte[]> chunks = Chunker.split(data, CHUNK);
        byte[] parity = ParityEncoder.encodeGroup(chunks, 0, K, CHUNK);
        assertEquals(CHUNK, parity.length);
        for (int b = 0; b < CHUNK; b++) {
            byte expected = 0;
            for (byte[] c : chunks) {
                if (b < c.length) {
                    expected ^= c[b];
                }
            }
            assertEquals(expected, parity[b]);
        }
    }

    @Test
    public void recoverRebuildsShortLastChunk() {
        byte[] data = file(CHUNK * 3 + 10, 2);
        List<byte[]> chunks = Chunker.split(data, CHUNK);
        byte[] parity = ParityEncoder.encodeGroup(chunks, 0, K, CHUNK);
        List<byte[]> others = new ArrayList<>(chunks.subList(0, 3));
        byte[] recovered = ParityRecovery.recover(parity, others, 10);
        assertArrayEquals(chunks.get(3), recovered);
    }

    @Test
    public void findRecoverableRules() {
        boolean[] have = {true, false, true, true, true, true, true, true};
        assertEquals(1, ParityRecovery.findRecoverable(have, true, 0, 4));
        assertEquals(ParityRecovery.NOT_RECOVERABLE, ParityRecovery.findRecoverable(have, false, 0, 4));
        assertEquals(ParityRecovery.NOT_RECOVERABLE, ParityRecovery.findRecoverable(have, true, 1, 4));
        have[2] = false;
        assertEquals(ParityRecovery.NOT_RECOVERABLE, ParityRecovery.findRecoverable(have, true, 0, 4));
        assertEquals(2, ParityRecovery.countMissing(have, 0, 4));
        assertEquals(0, ParityRecovery.countMissing(have, 1, 4));
    }

    @Test
    public void oneLossPerGroupIsRecoveredInEveryGroup() {
        byte[] data = file(CHUNK * 12, 3);
        // Lose chunk 1, 6 and 11: one per group of 4, a single pass, no repeats.
        Reassembler r = feed(without(frames(data, CHUNK, K), FrameType.DATA, 1, 6, 11));
        assertTrue(r.isComplete());
        assertEquals(3, r.getRecoveredCount());
        assertEquals(9, r.getReceivedCount());
        assertEquals(Reassembler.Verification.OK, r.getVerification());
        assertArrayEquals(data, r.getAssembledBytes());
        assertEquals(Reassembler.STATE_RECOVERED, r.getChunkState(6));
        assertEquals(Reassembler.STATE_RECEIVED, r.getChunkState(5));
    }

    @Test
    public void lastShortGroupAndShortChunkAreRecovered() {
        byte[] data = file(CHUNK * 5 + 7, 4); // 6 chunks, groups {0..3} and {4,5}
        Reassembler r = feed(without(frames(data, CHUNK, K), FrameType.DATA, 5));
        assertTrue(r.isComplete());
        assertEquals(1, r.getRecoveredCount());
        assertArrayEquals(data, r.getAssembledBytes());
    }

    @Test
    public void twoLossesInOneGroupAreNotRecoveredAndAreReported() {
        byte[] data = file(CHUNK * 8, 5);
        Reassembler r = feed(without(frames(data, CHUNK, K), FrameType.DATA, 1, 2));
        assertFalse(r.isComplete());
        assertEquals(0, r.getRecoveredCount());
        assertEquals(Arrays.asList(1, 2), r.getMissingIndices());
        assertEquals(Reassembler.Verification.INCOMPLETE, r.getVerification());
        assertEquals(75.0, r.getPercentComplete(), 0.0001);
        assertEquals(Reassembler.STATE_MISSING, r.getChunkState(1));
    }

    @Test
    public void twoLossesInDifferentGroupsAreBothRecovered() {
        byte[] data = file(CHUNK * 8, 6);
        Reassembler r = feed(without(frames(data, CHUNK, K), FrameType.DATA, 0, 7));
        assertTrue(r.isComplete());
        assertEquals(2, r.getRecoveredCount());
        assertArrayEquals(data, r.getAssembledBytes());
    }

    @Test
    public void lossOfTheParityFrameAloneStillCompletes() {
        byte[] data = file(CHUNK * 8, 7);
        Reassembler r = feed(without(frames(data, CHUNK, K), FrameType.PARITY, 0, 1));
        assertTrue(r.isComplete());
        assertEquals(0, r.getRecoveredCount());
        assertEquals(0, r.getParityReceivedCount());
        assertArrayEquals(data, r.getAssembledBytes());
    }

    @Test
    public void lossOfParityPlusOneDataChunkCannotRecover() {
        byte[] data = file(CHUNK * 8, 8);
        List<Frame> lossy = without(without(frames(data, CHUNK, K), FrameType.PARITY, 0),
                FrameType.DATA, 2);
        Reassembler r = feed(lossy);
        assertFalse(r.isComplete());
        assertEquals(Arrays.asList(2), r.getMissingIndices());
    }

    @Test
    public void parityArrivingBeforeTheLastDataChunkStillTriggersRecovery() {
        byte[] data = file(CHUNK * 4, 9);
        List<Frame> all = new ArrayList<>(frames(data, CHUNK, K));
        List<Frame> reordered = new ArrayList<>();
        Frame meta = all.get(0);
        reordered.add(meta);
        for (Frame f : all) {
            if (f.getType() == FrameType.PARITY) {
                reordered.add(f);
            }
        }
        for (Frame f : all) {
            if (f.getType() == FrameType.DATA && f.getIndex() != 2) {
                reordered.add(f);
            }
        }
        Reassembler r = feed(reordered);
        assertTrue(r.isComplete());
        assertEquals(1, r.getRecoveredCount());
        assertArrayEquals(data, r.getAssembledBytes());
    }

    @Test
    public void noParityModeSendsNoParityFramesAndIgnoresStrayOnes() {
        byte[] data = file(CHUNK * 4, 10);
        SendPlan plan = SendPlan.build("f.bin", null, data, CHUNK, ProtocolConstants.NO_PARITY, SESSION);
        assertEquals(0, plan.getParityFrameCount());
        for (Frame f : plan.getLoopFrames()) {
            assertTrue(f.getType() != FrameType.PARITY);
        }
        Reassembler r = new Reassembler();
        r.accept(plan.getMetaFrame());
        assertEquals(Reassembler.Outcome.IGNORED,
                r.accept(Frame.parity(SESSION, 0, 4, new byte[CHUNK])));
    }

    @Test
    public void groupSizeOneActsAsDuplication() {
        byte[] data = file(CHUNK * 3, 11);
        Reassembler r = feed(without(frames(data, CHUNK, 1), FrameType.DATA, 0, 1, 2));
        assertTrue(r.isComplete());
        assertEquals(3, r.getRecoveredCount());
        assertArrayEquals(data, r.getAssembledBytes());
    }
}
