package com.example.qrstream.protocol;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Seeded Monte-Carlo comparison of parity settings under frame loss.
 *
 * <p>For every (group size, loss rate) pair it runs a number of trials. Trial t uses the
 * same random file and the same loss seed for every configuration, so the rows are directly
 * comparable and the whole table is reproducible. Two metrics are measured:</p>
 * <ol>
 *   <li><b>Single-pass success rate</b>: the receiver sees the loop exactly once. The file
 *       must be rebuilt and verified from that one pass.</li>
 *   <li><b>Mean frames until complete</b>: the sender keeps looping (the real behaviour);
 *       count the frames shown until the receiver finishes. Lower is faster.</li>
 * </ol>
 *
 * <p>Complexity: O(trials * configs * n) where n is the file size (each trial encodes and
 * decodes every frame of a few passes).</p>
 *
 * <p>Run {@code main} to print the tables used in the project report.</p>
 */
public final class BenchmarkRunner {

    /** Default number of trials per cell of the table. */
    public static final int DEFAULT_TRIALS = 100;
    /** Default benchmark file size in bytes. */
    public static final int DEFAULT_FILE_SIZE = 20 * 1024;
    /** Passes after which a trial that is still incomplete is given up on. */
    public static final int MAX_PASSES = 200;

    private static final int PERCENT = 100;
    private static final long LOSS_SEED_MULTIPLIER = 7919L;

    private BenchmarkRunner() {
    }

    /** Result for one (group size, loss) cell. */
    public static final class Cell {
        private final int groupSize;
        private final double loss;
        private final int trials;
        private final int singlePassSuccesses;
        private final double meanFramesUntilComplete;
        private final double perPassOverheadPercent;

        Cell(int groupSize, double loss, int trials, int singlePassSuccesses,
             double meanFramesUntilComplete, double perPassOverheadPercent) {
            this.groupSize = groupSize;
            this.loss = loss;
            this.trials = trials;
            this.singlePassSuccesses = singlePassSuccesses;
            this.meanFramesUntilComplete = meanFramesUntilComplete;
            this.perPassOverheadPercent = perPassOverheadPercent;
        }

        /** @return group size K, 0 for no parity */
        public int getGroupSize() {
            return groupSize;
        }

        /** @return loss probability in [0, 1] */
        public double getLoss() {
            return loss;
        }

        /** @return number of trials */
        public int getTrials() {
            return trials;
        }

        /** @return trials that completed and verified within one pass */
        public int getSinglePassSuccesses() {
            return singlePassSuccesses;
        }

        /** @return single-pass success rate in percent */
        public double getSinglePassSuccessPercent() {
            return PERCENT * (double) singlePassSuccesses / trials;
        }

        /** @return mean number of frames shown until the receiver finished */
        public double getMeanFramesUntilComplete() {
            return meanFramesUntilComplete;
        }

        /** @return extra frames per pass (parity + repeated META) in percent of DATA frames */
        public double getPerPassOverheadPercent() {
            return perPassOverheadPercent;
        }
    }

    /**
     * Runs the whole grid.
     *
     * @param fileSize   size of the random test file in bytes
     * @param chunkSize  chunk size in bytes
     * @param groupSizes group sizes to compare, 0 means no parity
     * @param losses     loss probabilities in [0, 1]
     * @param trials     trials per cell
     * @return one cell per (group size, loss), group size major, in input order
     */
    public static List<Cell> run(int fileSize, int chunkSize, int[] groupSizes,
                                 double[] losses, int trials) {
        List<Cell> cells = new ArrayList<>();
        for (int groupSize : groupSizes) {
            for (double loss : losses) {
                cells.add(runCell(fileSize, chunkSize, groupSize, loss, trials));
            }
        }
        return cells;
    }

    private static Cell runCell(int fileSize, int chunkSize, int groupSize, double loss,
                                int trials) {
        int successes = 0;
        long frameSum = 0;
        double overhead = 0.0;
        for (int t = 0; t < trials; t++) {
            byte[] data = new byte[fileSize];
            new Random(t).nextBytes(data);
            SendPlan plan = SendPlan.build("bench.bin", null, data, chunkSize, groupSize, t + 1);
            overhead = plan.getOverheadPercent();
            List<String> loop = new ArrayList<>(plan.getLoopLength());
            for (Frame frame : plan.getLoopFrames()) {
                loop.add(FrameCodec.encodeToString(frame));
            }

            // Metric 1: exactly one pass.
            Reassembler single = new Reassembler();
            LossSimulator singleLoss = new LossSimulator(loss, t * LOSS_SEED_MULTIPLIER);
            for (String text : loop) {
                if (!singleLoss.shouldDrop()) {
                    single.accept(text);
                }
            }
            if (single.isComplete() && single.getVerification() == Reassembler.Verification.OK) {
                successes++;
            }

            // Metric 2: keep looping until done.
            Reassembler looping = new Reassembler();
            LossSimulator loopLoss = new LossSimulator(loss, t * LOSS_SEED_MULTIPLIER);
            long shown = 0;
            for (int pass = 0; pass < MAX_PASSES && !looping.isComplete(); pass++) {
                for (String text : loop) {
                    shown++;
                    if (!loopLoss.shouldDrop()) {
                        looping.accept(text);
                        if (looping.isComplete()) {
                            break;
                        }
                    }
                }
            }
            frameSum += shown;
        }
        return new Cell(groupSize, loss, trials, successes, (double) frameSum / trials, overhead);
    }

    /**
     * Formats the results as two plain-text tables.
     *
     * @param cells     result of {@link #run}
     * @param groupSizes group sizes in the same order as used for {@code run}
     * @param losses    loss rates in the same order as used for {@code run}
     * @param fileSize  file size, printed in the heading
     * @param chunkSize chunk size, printed in the heading
     * @return the report text
     */
    public static String formatTables(List<Cell> cells, int[] groupSizes, double[] losses,
                                      int fileSize, int chunkSize) {
        StringBuilder sb = new StringBuilder();
        int trials = cells.isEmpty() ? 0 : cells.get(0).getTrials();
        sb.append(String.format(Locale.ROOT,
                "File %d bytes, chunk %d bytes, %d seeded trials per cell%n%n",
                fileSize, chunkSize, trials));

        sb.append("Table 1: single-pass success rate (%), receiver sees the loop once\n");
        appendHeader(sb, losses);
        int idx = 0;
        for (int groupSize : groupSizes) {
            sb.append(String.format(Locale.ROOT, "%-18s", label(groupSize, cells.get(idx))));
            for (int l = 0; l < losses.length; l++) {
                sb.append(String.format(Locale.ROOT, "%9.1f", cells.get(idx + l).getSinglePassSuccessPercent()));
            }
            sb.append('\n');
            idx += losses.length;
        }

        sb.append("\nTable 2: mean frames shown until the receiver completes (sender loops)\n");
        appendHeader(sb, losses);
        idx = 0;
        for (int groupSize : groupSizes) {
            sb.append(String.format(Locale.ROOT, "%-18s", label(groupSize, cells.get(idx))));
            for (int l = 0; l < losses.length; l++) {
                sb.append(String.format(Locale.ROOT, "%9.1f", cells.get(idx + l).getMeanFramesUntilComplete()));
            }
            sb.append('\n');
            idx += losses.length;
        }
        return sb.toString();
    }

    private static void appendHeader(StringBuilder sb, double[] losses) {
        sb.append(String.format(Locale.ROOT, "%-18s", "Loss rate ->"));
        for (double loss : losses) {
            sb.append(String.format(Locale.ROOT, "%8.0f%%", loss * PERCENT));
        }
        sb.append('\n');
    }

    private static String label(int groupSize, Cell firstCell) {
        if (groupSize == ProtocolConstants.NO_PARITY) {
            return String.format(Locale.ROOT, "No parity (+%.0f%%)", firstCell.getPerPassOverheadPercent());
        }
        return String.format(Locale.ROOT, "K=%d (+%.0f%%)", groupSize, firstCell.getPerPassOverheadPercent());
    }

    /**
     * Prints the default report: no parity vs K=8, 4, 2 at 0, 5, 10 and 20 percent loss.
     *
     * @param args ignored
     */
    public static void main(String[] args) {
        int[] groupSizes = {ProtocolConstants.NO_PARITY, 8, 4, 2};
        double[] losses = {0.0, 0.05, 0.10, 0.20};
        List<Cell> cells = run(DEFAULT_FILE_SIZE, ProtocolConstants.DEFAULT_CHUNK_SIZE,
                groupSizes, losses, DEFAULT_TRIALS);
        System.out.println(formatTables(cells, groupSizes, losses, DEFAULT_FILE_SIZE,
                ProtocolConstants.DEFAULT_CHUNK_SIZE));
    }
}
