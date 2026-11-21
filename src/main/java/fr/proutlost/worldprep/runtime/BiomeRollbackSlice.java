package fr.proutlost.worldprep.runtime;

import java.util.Objects;

/** One write-budgeted slice over authenticated biome snapshot cells.
 * Snapshot ownership, area, protection and complete journal preflight remain with the caller.
 * Progress is reconciled from actual cells, including cells already restored before a restart.
 */
public final class BiomeRollbackSlice {
    private final int writeBudget;
    private int writes;
    private long reconciledCells;
    private boolean pending;

    public BiomeRollbackSlice(int writeBudget) { this.writeBudget = Math.max(1, writeBudget); }

    public void reconcileCell(String before, String applied, String current, Runnable restore) {
        Objects.requireNonNull(before); Objects.requireNonNull(applied);
        Objects.requireNonNull(current); Objects.requireNonNull(restore);
        // Includes journaled no-ops (before == applied): they never spend write budget.
        if (current.equals(before)) {
            reconciledCells = Math.incrementExact(reconciledCells);
            return;
        }
        if (!current.equals(applied)) throw new IllegalStateException("Biome ownership conflict during rollback");
        if (writes >= writeBudget) { pending = true; return; }
        restore.run();
        writes++;
        reconciledCells = Math.incrementExact(reconciledCells);
    }

    public int writes() { return writes; }
    public long reconciledCells() { return reconciledCells; }
    public boolean pending() { return pending; }
}
