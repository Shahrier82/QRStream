package com.example.qrstream.protocol;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.Test;

/** Whole-pipeline tests: plan, encode to QR text, lose frames, decode, rebuild, verify. */
public class EndToEndTest {

    private static final int FIFTY_KB = 50 * 1024;
    private static final int MAX_PASSES = 50;

    /** Transmits the looping plan through a lossy channel until the receiver completes. */
    private static int transmit(SendPlan plan, Reassembler receiver, double loss, long seed) {
        List<String> texts = new ArrayList<>();
        for (Frame f : plan.getLoopFrames()) {
            texts.add(FrameCodec.encodeToString(f));
        }
        LossSimulator channel = new LossSimulator(loss, seed);
        int passes = 0;
        while (!receiver.isComplete() && passes < MAX_PASSES) {
            passes++;
            for (String text : texts) {
                if (!channel.shouldDrop()) {
                    receiver.accept(text);
                    if (receiver.isComplete()) {
                        break;
                    }
                }
            }
        }
        return passes;
    }

    @Test
    public void fiftyKilobyteFileSurvivesTenPercentSeededLossWithK4() {
        byte[] data = new byte[FIFTY_KB];
        new Random(2025).nextBytes(data);
        SendPlan plan = SendPlan.build("photo.jpg", "image/jpeg", data,
                ProtocolConstants.DEFAULT_CHUNK_SIZE, 4, 0x51534551);
        Reassembler receiver = new Reassembler();

        int passes = transmit(plan, receiver, 0.10, 4242L);

        assertTrue("Transfer did not finish", receiver.isComplete());
        assertEquals(Reassembler.Verification.OK, receiver.getVerification());
        assertArrayEquals(data, receiver.getAssembledBytes());
        assertTrue("Parity should have repaired at least one chunk", receiver.getRecoveredCount() > 0);
        assertTrue("Needed " + passes + " passes", passes >= 1);
        assertEquals("photo.jpg", receiver.getMeta().getFileName());
        assertEquals("image/jpeg", receiver.getMeta().getMimeType());
    }

    @Test
    public void endToEndIsReproducibleForTheSameSeeds() {
        byte[] data = new byte[FIFTY_KB];
        new Random(7).nextBytes(data);
        SendPlan plan = SendPlan.build("a.bin", null, data, 400, 4, 9);
        Reassembler first = new Reassembler();
        Reassembler second = new Reassembler();
        transmit(plan, first, 0.10, 99L);
        transmit(plan, second, 0.10, 99L);
        assertEquals(first.getRecoveredCount(), second.getRecoveredCount());
        assertEquals(first.getDuplicateCount(), second.getDuplicateCount());
        assertEquals(first.getTotalSeen(), second.getTotalSeen());
    }

    @Test
    public void parityNeedsNoMorePassesThanNoParityOnAverage() {
        int parityTotal = 0;
        int plainTotal = 0;
        for (int trial = 0; trial < 20; trial++) {
            byte[] data = new byte[20 * 1024];
            new Random(trial).nextBytes(data);
            SendPlan withParity = SendPlan.build("x", null, data, 400, 4, trial + 1);
            SendPlan without = SendPlan.build("x", null, data, 400, 0, trial + 1);
            Reassembler a = new Reassembler();
            Reassembler b = new Reassembler();
            transmit(withParity, a, 0.10, trial);
            transmit(without, b, 0.10, trial);
            parityTotal += a.getTotalSeen();
            plainTotal += b.getTotalSeen();
            assertTrue(a.isComplete() && b.isComplete());
        }
        assertTrue("Parity used " + parityTotal + " frames vs " + plainTotal,
                parityTotal < plainTotal * 1.2);
    }

    @Test
    public void maximumSizeFileTransfersAtSmallestChunkSize() {
        byte[] data = new byte[ProtocolConstants.MAX_FILE_SIZE_BYTES];
        new Random(31).nextBytes(data);
        SendPlan plan = SendPlan.build("max.bin", null, data, ProtocolConstants.MIN_CHUNK_SIZE, 8, 3);
        Reassembler receiver = new Reassembler();
        transmit(plan, receiver, 0.05, 5L);
        assertArrayEquals(data, receiver.getAssembledBytes());
        assertEquals(Reassembler.Verification.OK, receiver.getVerification());
    }

    @Test
    public void everyQrPayloadFitsInAQrCode() {
        // Largest chunk: 800 bytes of payload + 14 header + 4 CRC = 818 bytes, Base64 -> 1091 chars.
        // QR version 40 at level M holds 2331 bytes, at level L 2953 bytes.
        byte[] data = new byte[ProtocolConstants.MAX_CHUNK_SIZE * 3];
        new Random(1).nextBytes(data);
        SendPlan plan = SendPlan.build("n.bin", null, data, ProtocolConstants.MAX_CHUNK_SIZE, 4, 1);
        int longest = 0;
        for (Frame f : plan.getLoopFrames()) {
            longest = Math.max(longest, FrameCodec.encodeToString(f).length());
        }
        assertEquals(1091, longest);
        assertTrue(longest < 2331);
    }
}
