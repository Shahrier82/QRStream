package com.example.qrstream.protocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

/**
 * Runs the parity benchmark (no parity vs K=8, 4, 2 at 0, 5, 10, 20 percent loss, 100 seeded
 * trials each) and prints the results table used in the project report.
 */
public class BenchmarkTest {

    private static final int[] GROUP_SIZES = {ProtocolConstants.NO_PARITY, 8, 4, 2};
    private static final double[] LOSSES = {0.0, 0.05, 0.10, 0.20};

    @Test
    public void printResultsTableAndCheckSanity() {
        List<BenchmarkRunner.Cell> cells = BenchmarkRunner.run(BenchmarkRunner.DEFAULT_FILE_SIZE,
                ProtocolConstants.DEFAULT_CHUNK_SIZE, GROUP_SIZES, LOSSES,
                BenchmarkRunner.DEFAULT_TRIALS);
        System.out.println();
        System.out.println(BenchmarkRunner.formatTables(cells, GROUP_SIZES, LOSSES,
                BenchmarkRunner.DEFAULT_FILE_SIZE, ProtocolConstants.DEFAULT_CHUNK_SIZE));

        assertEquals(GROUP_SIZES.length * LOSSES.length, cells.size());
        // 0% loss: every configuration succeeds in a single pass, every time.
        for (int g = 0; g < GROUP_SIZES.length; g++) {
            assertEquals(100.0, cells.get(g * LOSSES.length).getSinglePassSuccessPercent(), 0.0001);
        }
        // 5% loss: every parity setting beats "no parity" in a single pass.
        double noParity = cells.get(1).getSinglePassSuccessPercent();
        for (int g = 1; g < GROUP_SIZES.length; g++) {
            double parity = cells.get(g * LOSSES.length + 1).getSinglePassSuccessPercent();
            assertTrue("K=" + GROUP_SIZES[g] + " should beat no parity at 5% loss", parity > noParity);
        }
        // Smaller groups repair more, so K=2 >= K=8 at 5% loss in a single pass.
        assertTrue(cells.get(3 * LOSSES.length + 1).getSinglePassSuccessPercent()
                >= cells.get(1 * LOSSES.length + 1).getSinglePassSuccessPercent());
        // Parity costs frames per pass: overhead grows as K shrinks.
        assertTrue(cells.get(3 * LOSSES.length).getPerPassOverheadPercent()
                > cells.get(1 * LOSSES.length).getPerPassOverheadPercent());
    }

    @Test
    public void benchmarkIsReproducible() {
        List<BenchmarkRunner.Cell> a = BenchmarkRunner.run(4096, 400, new int[]{4}, new double[]{0.1}, 10);
        List<BenchmarkRunner.Cell> b = BenchmarkRunner.run(4096, 400, new int[]{4}, new double[]{0.1}, 10);
        assertEquals(a.get(0).getSinglePassSuccesses(), b.get(0).getSinglePassSuccesses());
        assertEquals(a.get(0).getMeanFramesUntilComplete(), b.get(0).getMeanFramesUntilComplete(), 0.0);
    }
}
