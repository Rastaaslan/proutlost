package fr.proutlost.worldprep.storage;

/** Stable streaming cursor. It is a checkpoint, not evidence that world chunks were saved. */
public record PageCursor(long pageIndex, int entryIndex) {
    public static final PageCursor START = new PageCursor(0, 0);
    public PageCursor { if (pageIndex < 0 || entryIndex < 0) throw new IllegalArgumentException("Negative cursor"); }
}
