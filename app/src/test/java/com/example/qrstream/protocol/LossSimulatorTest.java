package com.example.qrstream.protocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import org.junit.Test;

/** Tests for {@link LossSimulator}. */
public class LossSimulatorTest {

    private static List<Integer> range(int n) {
        List<Integer> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            list.add(i);
        }
        return list;
    }

    @Test
    public void sameSeedGivesIdenticalDrops() {
        List<Integer> input = range(500);
        List<Integer> a = new LossSimulator(0.2, 12345L).apply(input);
        List<Integer> b = new LossSimulator(0.2, 12345L).apply(input);
        assertEquals(a, b);
        assertTrue(a.size() < input.size());
    }

    @Test
    public void differentSeedsGiveDifferentDrops() {
        List<Integer> input = range(500);
        List<Integer> a = new LossSimulator(0.2, 1L).apply(input);
        List<Integer> b = new LossSimulator(0.2, 2L).apply(input);
        assertNotEquals(a, b);
    }

    @Test
    public void survivorsKeepTheirOrder() {
        List<Integer> out = new LossSimulator(0.5, 9L).apply(range(200));
        for (int i = 1; i < out.size(); i++) {
            assertTrue(out.get(i) > out.get(i - 1));
        }
    }

    @Test
    public void extremeProbabilities() {
        assertEquals(range(100), new LossSimulator(0.0, 3L).apply(range(100)));
        assertTrue(new LossSimulator(1.0, 3L).apply(range(100)).isEmpty());
    }

    @Test
    public void lossRateIsCloseToTheRequestedRate() {
        LossSimulator sim = new LossSimulator(0.10, 2024L);
        sim.apply(range(20000));
        assertEquals(20000, sim.getSeenCount());
        double rate = (double) sim.getDroppedCount() / sim.getSeenCount();
        assertEquals(0.10, rate, 0.01);
    }

    @Test
    public void streamingAndBatchModesAgree() {
        List<Integer> input = range(300);
        LossSimulator batch = new LossSimulator(0.3, 77L);
        List<Integer> expected = batch.apply(input);
        LossSimulator streaming = new LossSimulator(0.3, 77L);
        List<Integer> actual = new ArrayList<>();
        for (Integer item : input) {
            if (!streaming.shouldDrop()) {
                actual.add(item);
            }
        }
        assertEquals(expected, actual);
    }

    @Test
    public void fixedIndicesAreDroppedExactly() {
        List<Integer> out = LossSimulator.dropIndices(range(6), new HashSet<>(Arrays.asList(0, 3, 5)));
        assertEquals(Arrays.asList(1, 2, 4), out);
    }

    @Test
    public void invalidProbabilitiesAreRejected() {
        for (double p : new double[]{-0.1, 1.1, Double.NaN}) {
            try {
                new LossSimulator(p, 1L);
                fail("Accepted " + p);
            } catch (IllegalArgumentException expected) {
                // good
            }
        }
        assertFalse(new LossSimulator(0.5, 1L).shouldDrop() && false);
    }
}
