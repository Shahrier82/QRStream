package com.example.qrstream.protocol;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Reproducible frame loss for tests, benchmarks and the "Simulate loss" demo mode.
 *
 * <p>Uses {@link java.util.Random} with an explicit seed. Its algorithm is fixed by the Java
 * specification, so the same seed gives the same drops on every JVM and on Android.</p>
 *
 * <p>Complexity: O(1) per decision, O(n) time and space to filter a list of n items.</p>
 */
public final class LossSimulator {

    private final double lossProbability;
    private final Random random;
    private int seen;
    private int dropped;

    /**
     * @param lossProbability chance in [0, 1] that any one item is dropped
     * @param seed            seed of the random generator
     * @throws IllegalArgumentException if the probability is outside [0, 1]
     */
    public LossSimulator(double lossProbability, long seed) {
        if (Double.isNaN(lossProbability) || lossProbability < 0.0 || lossProbability > 1.0) {
            throw new IllegalArgumentException("Loss probability must be in [0, 1]");
        }
        this.lossProbability = lossProbability;
        this.random = new Random(seed);
    }

    /**
     * Decides the fate of the next item of a stream.
     *
     * @return true if the item must be dropped
     */
    public boolean shouldDrop() {
        seen++;
        boolean drop = random.nextDouble() < lossProbability;
        if (drop) {
            dropped++;
        }
        return drop;
    }

    /**
     * Produces a lossy copy of a list, advancing the generator once per item.
     *
     * @param items stream of frames (or anything else), not null
     * @param <T>   element type
     * @return the items that survived, in their original order
     */
    public <T> List<T> apply(List<T> items) {
        List<T> survivors = new ArrayList<>(items.size());
        for (T item : items) {
            if (!shouldDrop()) {
                survivors.add(item);
            }
        }
        return survivors;
    }

    /**
     * Removes items at fixed positions. Needs no random numbers.
     *
     * @param items   stream of frames (or anything else), not null
     * @param indices positions to drop (0-based, positions in {@code items})
     * @param <T>     element type
     * @return the remaining items in order
     */
    public static <T> List<T> dropIndices(List<T> items, Set<Integer> indices) {
        List<T> survivors = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            if (!indices.contains(i)) {
                survivors.add(items.get(i));
            }
        }
        return survivors;
    }

    /** @return the configured loss probability */
    public double getLossProbability() {
        return lossProbability;
    }

    /** @return how many items have been judged so far */
    public int getSeenCount() {
        return seen;
    }

    /** @return how many items have been dropped so far */
    public int getDroppedCount() {
        return dropped;
    }
}
