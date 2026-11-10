package com.example.qrstream.protocol;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.Test;

/** Tests for {@link Reassembler} and {@link SendPlan}. */
public class ReassemblerTest {

    private static final int CHUNK = 50;
    private static final int SESSION = 1001;

    private static byte[] file(int size, long seed) {
        byte[] b = new byte[size];
        new Random(seed).nextBytes(b);
        return b;
    }

    private static SendPlan plan(byte[] data, int groupSize, int session) {
        return SendPlan.build("doc.txt", "text/plain", data, CHUNK, groupSize, session);
    }

    @Test
    public void outOfOrderDeliveryCompletes() {
        byte[] data = file(CHUNK * 10 + 3, 1);
        List<Frame> frames = new ArrayList<>(plan(data, 4, SESSION).getLoopFrames());
        Collections.shuffle(frames, new Random(42));
        Reassembler r = new Reassembler();
        for (Frame f : frames) {
            r.accept(f);
        }
        assertTrue(r.isComplete());
        assertEquals(Reassembler.Verification.OK, r.getVerification());
        assertArrayEquals(data, r.getAssembledBytes());
        assertEquals("doc.txt", r.getMeta().getFileName());
        assertEquals(100.0, r.getPercentComplete(), 0.0001);
        assertTrue(r.getMissingIndices().isEmpty());
    }

    @Test
    public void duplicatesNeverCorruptState() {
        byte[] data = file(CHUNK * 6, 2);
        List<Frame> frames = plan(data, 4, SESSION).getLoopFrames();
        Reassembler r = new Reassembler();
        int duplicates = 0;
        for (int round = 0; round < 3; round++) {
            for (Frame f : frames) {
                Reassembler.Outcome outcome = r.accept(f);
                if (outcome == Reassembler.Outcome.DUPLICATE) {
                    duplicates++;
                }
            }
        }
        assertTrue(r.isComplete());
        assertEquals(duplicates, r.getDuplicateCount());
        assertEquals(6, r.getReceivedCount());
        assertEquals(0, r.getRecoveredCount());
        assertArrayEquals(data, r.getAssembledBytes());
        // Feeding frames after completion changes nothing.
        byte[] before = r.getAssembledBytes().clone();
        r.accept(frames.get(2));
        assertArrayEquals(before, r.getAssembledBytes());
    }

    @Test
    public void duplicateReportsOutcome() {
        byte[] data = file(CHUNK * 2, 3);
        SendPlan p = plan(data, 0, SESSION);
        Reassembler r = new Reassembler();
        assertEquals(Reassembler.Outcome.ACCEPTED, r.accept(p.getMetaFrame()));
        assertEquals(Reassembler.Outcome.DUPLICATE, r.accept(p.getMetaFrame()));
        Frame d0 = p.getLoopFrames().get(1);
        assertEquals(Reassembler.Outcome.ACCEPTED, r.accept(d0));
        assertEquals(Reassembler.Outcome.DUPLICATE, r.accept(d0));
    }

    @Test
    public void lateMetaIsHandledByBufferingEarlierFrames() {
        byte[] data = file(CHUNK * 5, 4);
        SendPlan p = plan(data, 4, SESSION);
        List<Frame> frames = p.getLoopFrames();
        Reassembler r = new Reassembler();
        // Everything except META first.
        for (Frame f : frames.subList(1, frames.size())) {
            if (f.getType() != FrameType.META) {
                r.accept(f);
            }
        }
        assertFalse(r.isSessionLocked());
        assertEquals(0.0, r.getPercentComplete(), 0.0001);
        assertTrue(r.getMissingIndices().isEmpty());
        assertFalse(r.isComplete());
        r.accept(p.getMetaFrame());
        assertTrue(r.isSessionLocked());
        assertTrue(r.isComplete());
        assertArrayEquals(data, r.getAssembledBytes());
    }

    @Test
    public void lateJoinerStartingMidLoopCompletesOnSecondPass() {
        byte[] data = file(CHUNK * 20, 5);
        List<Frame> frames = plan(data, 4, SESSION).getLoopFrames();
        Reassembler r = new Reassembler();
        int start = frames.size() / 2;
        for (int i = start; i < frames.size(); i++) {
            r.accept(frames.get(i));
        }
        assertFalse(r.isComplete());
        for (Frame f : frames) {
            r.accept(f);
        }
        assertTrue(r.isComplete());
        assertArrayEquals(data, r.getAssembledBytes());
    }

    @Test
    public void wrongSessionIsIgnoredAfterLock() {
        byte[] data = file(CHUNK * 4, 6);
        byte[] other = file(CHUNK * 4, 7);
        SendPlan a = plan(data, 0, 111);
        SendPlan b = plan(other, 0, 222);
        Reassembler r = new Reassembler();
        r.accept(a.getMetaFrame());
        for (Frame f : b.getLoopFrames()) {
            assertEquals(Reassembler.Outcome.IGNORED, r.accept(f));
        }
        assertEquals(0, r.getHeldCount());
        assertEquals(111, r.getSessionId());
        for (Frame f : a.getLoopFrames()) {
            r.accept(f);
        }
        assertTrue(r.isComplete());
        assertArrayEquals(data, r.getAssembledBytes());
        assertTrue(r.getIgnoredFrameCount() >= b.getLoopLength());
    }

    @Test
    public void bufferedFramesOfAnotherSessionAreDiscardedWhenMetaLocks() {
        byte[] data = file(CHUNK * 3, 8);
        byte[] other = file(CHUNK * 3, 9);
        SendPlan a = plan(data, 0, 111);
        SendPlan b = plan(other, 0, 222);
        Reassembler r = new Reassembler();
        for (Frame f : b.getLoopFrames()) {
            if (f.getType() == FrameType.DATA) {
                r.accept(f);
            }
        }
        r.accept(a.getMetaFrame());
        assertEquals(0, r.getHeldCount());
        for (Frame f : a.getLoopFrames()) {
            r.accept(f);
        }
        assertArrayEquals(data, r.getAssembledBytes());
    }

    @Test
    public void hashMismatchIsDetected() {
        byte[] data = file(CHUNK * 3, 10);
        SendPlan p = plan(data, 0, SESSION);
        FileMeta real = p.getMeta();
        FileMeta lying = new FileMeta(real.getFileName(), real.getFileSize(), real.getMimeType(),
                real.getChunkSize(), real.getTotalChunks(), real.getGroupSize(),
                HashUtil.sha256Hex(new byte[]{1}));
        Reassembler r = new Reassembler();
        r.accept(Frame.meta(SESSION, real.getTotalChunks(), lying.toPayload()));
        for (Frame f : p.getLoopFrames()) {
            if (f.getType() == FrameType.DATA) {
                r.accept(f);
            }
        }
        assertTrue(r.isComplete());
        assertEquals(Reassembler.Verification.HASH_MISMATCH, r.getVerification());
        assertNotNull(r.getAssembledBytes());
    }

    @Test
    public void corruptedTextAndWrongMagicAreIgnoredAndCounted() {
        byte[] data = file(CHUNK * 2, 11);
        SendPlan p = plan(data, 0, SESSION);
        Reassembler r = new Reassembler();
        String good = FrameCodec.encodeToString(p.getMetaFrame());
        byte[] raw = FrameCodec.encode(p.getLoopFrames().get(1));
        byte[] badCrc = raw.clone();
        badCrc[badCrc.length - 1] ^= 1;
        byte[] badMagic = raw.clone();
        badMagic[0] = 0;
        assertEquals(Reassembler.Outcome.IGNORED, r.accept(Base64Url.encode(badCrc)));
        assertEquals(Reassembler.Outcome.IGNORED, r.accept(Base64Url.encode(badMagic)));
        assertEquals(Reassembler.Outcome.IGNORED, r.accept("hello world"));
        assertEquals(Reassembler.Outcome.IGNORED, r.accept((String) null));
        assertEquals(4, r.getInvalidFrameCount());
        assertEquals(Reassembler.Outcome.ACCEPTED, r.accept(good));
        assertEquals(1, r.getFramesAccepted());
    }

    @Test
    public void outOfRangeAndWrongSizedFramesAreIgnored() {
        byte[] data = file(CHUNK * 2 + 5, 12); // 3 chunks, last has 5 bytes
        SendPlan p = plan(data, 0, SESSION);
        Reassembler r = new Reassembler();
        r.accept(p.getMetaFrame());
        assertEquals(Reassembler.Outcome.IGNORED, r.accept(Frame.data(SESSION, 3, 3, new byte[CHUNK])));
        assertEquals(Reassembler.Outcome.IGNORED, r.accept(Frame.data(SESSION, 0, 3, new byte[CHUNK - 1])));
        assertEquals(Reassembler.Outcome.IGNORED, r.accept(Frame.data(SESSION, 2, 3, new byte[CHUNK])));
        assertEquals(Reassembler.Outcome.IGNORED, r.accept(Frame.data(SESSION, 0, 9, new byte[CHUNK])));
        assertEquals(Reassembler.Outcome.IGNORED, r.accept(Frame.parity(SESSION, 0, 3, new byte[CHUNK])));
        assertEquals(0, r.getHeldCount());
        assertEquals(5, r.getIgnoredFrameCount());
    }

    @Test
    public void maliciousMetaIsRejected() {
        String hash = HashUtil.sha256Hex(new byte[0]);
        Reassembler r = new Reassembler();
        FileMeta huge = new FileMeta("x", 2_000_000_000L, null, 60000, 33334, 4, hash);
        assertEquals(Reassembler.Outcome.IGNORED, r.accept(Frame.meta(1, 33334, huge.toPayload())));
        FileMeta inconsistent = new FileMeta("x", 100, null, 50, 5, 4, hash);
        assertEquals(Reassembler.Outcome.IGNORED, r.accept(Frame.meta(1, 5, inconsistent.toPayload())));
        FileMeta mismatchTotal = new FileMeta("x", 100, null, 50, 2, 4, hash);
        assertEquals(Reassembler.Outcome.IGNORED, r.accept(Frame.meta(1, 3, mismatchTotal.toPayload())));
        assertEquals(Reassembler.Outcome.IGNORED, r.accept(Frame.meta(1, 0, "{}".getBytes())));
        assertFalse(r.isSessionLocked());
    }

    @Test
    public void emptyFileCompletesAsSoonAsMetaArrives() {
        SendPlan p = plan(new byte[0], 4, SESSION);
        assertEquals(1, p.getLoopLength());
        Reassembler r = new Reassembler();
        r.accept(p.getMetaFrame());
        assertTrue(r.isComplete());
        assertEquals(Reassembler.Verification.OK, r.getVerification());
        assertEquals(0, r.getAssembledBytes().length);
        assertEquals(100.0, r.getPercentComplete(), 0.0001);
    }

    @Test
    public void oneByteFileRoundTrips() {
        byte[] data = {(byte) 0xAB};
        SendPlan p = plan(data, 4, SESSION);
        Reassembler r = new Reassembler();
        for (Frame f : p.getLoopFrames()) {
            r.accept(FrameCodec.encodeToString(f));
        }
        assertArrayEquals(data, r.getAssembledBytes());
    }

    @Test
    public void metaIsRepeatedEveryTenFramesAndNotAtTheEnd() {
        byte[] data = file(CHUNK * 40, 13);
        SendPlan p = plan(data, 4, SESSION);
        List<Frame> frames = p.getLoopFrames();
        assertEquals(FrameType.META, frames.get(0).getType());
        assertTrue(frames.get(frames.size() - 1).getType() != FrameType.META);
        int sinceMeta = 0;
        int metas = 1;
        for (int i = 1; i < frames.size(); i++) {
            if (frames.get(i).getType() == FrameType.META) {
                assertEquals(ProtocolConstants.META_REPEAT_INTERVAL, sinceMeta);
                sinceMeta = 0;
                metas++;
            } else {
                sinceMeta++;
            }
        }
        assertTrue(sinceMeta <= ProtocolConstants.META_REPEAT_INTERVAL);
        assertTrue(metas > 1);
        assertEquals(40, p.getDataFrameCount());
        assertEquals(10, p.getParityFrameCount());
    }

    @Test
    public void loopOrderIsDataThenParityPerGroup() {
        byte[] data = file(CHUNK * 4, 14);
        SendPlan p = plan(data, 2, SESSION);
        List<Frame> frames = p.getLoopFrames();
        // META, d0, d1, p0, d2, d3, p1 (fewer than 10 frames, so no repeated META)
        assertEquals(7, frames.size());
        FrameType[] expected = {FrameType.META, FrameType.DATA, FrameType.DATA, FrameType.PARITY,
                FrameType.DATA, FrameType.DATA, FrameType.PARITY};
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], frames.get(i).getType());
        }
        assertEquals(0, frames.get(1).getIndex());
        assertEquals(1, frames.get(2).getIndex());
        assertEquals(0, frames.get(3).getIndex());
        assertEquals(1, frames.get(6).getIndex());
    }

    @Test
    public void resetReturnsToInitialState() {
        byte[] data = file(CHUNK * 2, 15);
        Reassembler r = new Reassembler();
        for (Frame f : plan(data, 0, SESSION).getLoopFrames()) {
            r.accept(f);
        }
        assertTrue(r.isComplete());
        r.reset();
        assertFalse(r.isSessionLocked());
        assertFalse(r.isComplete());
        assertNull(r.getAssembledBytes());
        assertEquals(0, r.getTotalSeen());
        // A different transfer can now be received.
        byte[] second = file(CHUNK * 3, 16);
        for (Frame f : plan(second, 0, 5).getLoopFrames()) {
            r.accept(f);
        }
        assertArrayEquals(second, r.getAssembledBytes());
    }

    @Test
    public void sendPlanRejectsOversizedFilesAndBadParameters() {
        byte[] tooBig = new byte[ProtocolConstants.MAX_FILE_SIZE_BYTES + 1];
        try {
            SendPlan.build("big", null, tooBig, 400, 4, 1);
            org.junit.Assert.fail();
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("200"));
        }
        try {
            SendPlan.build("x", null, new byte[10], 0, 4, 1);
            org.junit.Assert.fail();
        } catch (IllegalArgumentException expected) {
            // good
        }
        // Exactly the limit is fine.
        SendPlan ok = SendPlan.build("max", null, new byte[ProtocolConstants.MAX_FILE_SIZE_BYTES], 200, 4, 1);
        assertEquals(1024, ok.getDataFrameCount());
    }

    @Test
    public void loopLengthEstimateMatchesTheBuiltPlan() {
        int[] sizes = {0, 1, 49, 50, 51, 500, 501, 999, 2000, 5555};
        int[] groups = {0, 1, 2, 4, 8};
        for (int size : sizes) {
            for (int group : groups) {
                SendPlan built = SendPlan.build("e", null, new byte[size], CHUNK, group, 1);
                assertEquals("size=" + size + " group=" + group,
                        built.getLoopLength(), SendPlan.estimateLoopLength(size, CHUNK, group));
            }
        }
    }

    @Test
    public void percentAndCountsProgressMonotonically() {
        byte[] data = file(CHUNK * 8, 17);
        SendPlan p = plan(data, 0, SESSION);
        Reassembler r = new Reassembler();
        r.accept(p.getMetaFrame());
        double last = 0;
        for (Frame f : p.getLoopFrames()) {
            r.accept(f);
            assertTrue(r.getPercentComplete() >= last);
            last = r.getPercentComplete();
        }
        assertEquals(100.0, last, 0.0001);
        assertEquals(8, r.getChunkStates().length);
    }
}
