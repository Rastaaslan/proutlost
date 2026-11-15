package fr.proutlost.worldprep.gametest;

import java.util.Objects;

/** GameTestInfo removes the executing map entry after run(); never reuse its key. */
public final class DistinctTickCallback {
    public static Runnable wrap(Runnable task) {
        Objects.requireNonNull(task);
        // Explicit allocation guarantees distinct identity, unlike a lambda expression.
        return new Runnable() {
            @Override public void run() { task.run(); }
        };
    }
    private DistinctTickCallback() {}
}
