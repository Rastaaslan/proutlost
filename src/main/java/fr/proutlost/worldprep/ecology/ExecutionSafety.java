package fr.proutlost.worldprep.ecology;

/** Stop at checkpoint; returning NONE does not automatically resume a paused job. */
public final class ExecutionSafety {
    public enum Stop { NONE, PLAYER_PRESENT, TICK_PRESSURE }
    public static Stop check(int players, long averageTickNanos, int limitMillis) {
        if (players < 0 || averageTickNanos < 0 || limitMillis < 1) throw new IllegalArgumentException();
        if (players > 0) return Stop.PLAYER_PRESENT;
        return averageTickNanos > limitMillis * 1_000_000L ? Stop.TICK_PRESSURE : Stop.NONE;
    }
    private ExecutionSafety() {}
}
