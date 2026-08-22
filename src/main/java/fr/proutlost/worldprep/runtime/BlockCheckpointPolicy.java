package fr.proutlost.worldprep.runtime;

/** Pure batching policy, separated so save-frequency invariants are unit-testable. */
public final class BlockCheckpointPolicy {
    public static final int BATCH_SIZE = 256;

    public static int batchLength(long cursor, int total, int tickBudget) {
        if (cursor < 0 || cursor > total || tickBudget < 0) throw new IllegalArgumentException();
        return (int) Math.min(Math.min(BATCH_SIZE, tickBudget), total - cursor);
    }

    public static long maximumApplyCheckpoints(int changes) {
        if (changes < 0) throw new IllegalArgumentException();
        return changes == 0 ? 0 : 2L * ((changes + BATCH_SIZE - 1L) / BATCH_SIZE);
    }

    private BlockCheckpointPolicy() {}
}
