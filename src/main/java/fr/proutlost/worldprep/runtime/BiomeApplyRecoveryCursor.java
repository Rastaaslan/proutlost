package fr.proutlost.worldprep.runtime;

import java.util.Objects;
import java.util.function.LongToIntFunction;

/**
 * A bounded two-pass recovery over an immutable, authenticated journal prefix.
 * Every cell is checked before any recovery write. A process restart or pause
 * discards this cursor; the durable journal remains the only authority.
 */
public final class BiomeApplyRecoveryCursor {
    public enum Phase { VALIDATE, REPLAY, READY }
    @FunctionalInterface public interface PageSlice {
        void visit(long page, int offset, int cells, Phase phase);
    }
    public record Progress(Phase phase, long page, int offset, long pages,
                           long visitedCells, int lastSliceCells, int maximumSliceCells) {}
    private final long pages;
    private Phase phase;
    private long page, visitedCells;
    private int offset, lastSliceCells, maximumSliceCells;

    public BiomeApplyRecoveryCursor(long pages) {
        if (pages < 0) throw new IllegalArgumentException("Negative journal prefix");
        this.pages = pages;
        phase = pages == 0 ? Phase.READY : Phase.VALIDATE;
    }

    public Progress progress() {
        return new Progress(phase, page, offset, pages, visitedCells,
                            lastSliceCells, maximumSliceCells);
    }

    /** Counts validation and replay cells in the same unchanged cell budget. */
    public int advance(int budget, LongToIntFunction pageCells, PageSlice visitor) {
        Objects.requireNonNull(pageCells); Objects.requireNonNull(visitor);
        lastSliceCells = 0;
        while (lastSliceCells < budget && phase != Phase.READY) {
            int size = pageCells.applyAsInt(page);
            if (size < 1 || offset >= size)
                throw new IllegalStateException("Journal page length changed");
            int count = Math.min(budget - lastSliceCells, size - offset);
            visitor.visit(page, offset, count, phase);
            // A failed visitor never grants progress or authorizes later writes.
            offset += count;
            lastSliceCells += count;
            visitedCells = Math.addExact(visitedCells, count);
            maximumSliceCells = Math.max(maximumSliceCells, lastSliceCells);
            if (offset == size) {
                offset = 0; page++;
                if (page == pages) {
                    page = 0;
                    phase = phase == Phase.VALIDATE ? Phase.REPLAY : Phase.READY;
                }
            }
        }
        return lastSliceCells;
    }
}
