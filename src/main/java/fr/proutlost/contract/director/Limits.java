package fr.proutlost.contract.director;

/** Compiled ceilings, not tunable permissions. */
public final class Limits {
    private Limits() {}
    public static final int MAX_JSON_BYTES = 1_048_576;
    /** Inner record bytes; independent of the ordinary field-string limit. */
    public static final int MAX_ROW_PAYLOAD_BYTES = MAX_JSON_BYTES;
    /** Escaping a row string can expand each byte sixfold; aggregate transactions remain bounded. */
    public static final int MAX_ENVELOPE_JSON_BYTES = 8 * MAX_JSON_BYTES;
    /** Plain shortest finite double decimals need up to 327 characters and scale 325. */
    public static final int MAX_NUMBER_CHARS = 384;
    public static final int MAX_NUMBER_SCALE = 340;
    public static final int MAX_DEPTH = 24;
    public static final int MAX_ITEMS = 1024;
    public static final int MAX_STRING = 4096;
    public static final int MAX_QUERY = 128;
    public static final long MAX_DURATION_MS = 604_800_000L;
    public static final long MAX_MUTATION_CELLS = 4096;
    public static final int MAX_BUDGET = 1_048_576;
    public static final long ACTIVE_PLAY_WINDOW_MS = 604_800_000L;
    public static final int GLOBAL_MAJOR_CAPACITY = 1;
}
