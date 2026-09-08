package fr.proutlost.worldprep.pipeline;

/** Explicit operation kind; persistence uses names, never ordinal values. */
public enum OperationType {
    ANALYZE(false), PREVIEW(false), APPLY(true), ROLLBACK(true), RECONCILE(false), VALIDATE(false);
    private final boolean destructive;
    OperationType(boolean destructive) { this.destructive = destructive; }
    public boolean destructive() { return destructive; }
}
