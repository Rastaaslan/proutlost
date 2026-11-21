package fr.proutlost.worldprep;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class WorldPrepConfig {
    public enum Mode { TEST, PRODUCTION }
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.BooleanValue ENABLED;
    public static final ModConfigSpec.BooleanValue PHYSICAL_PASSES;
    public static final ModConfigSpec.BooleanValue STRUCTURES_ONLY;
    public static final ModConfigSpec.BooleanValue PARALLEL_DISCOVERY;
    public static final ModConfigSpec.BooleanValue PARALLEL_ECOLOGY_PLANNING;
    public static final ModConfigSpec.IntValue PARALLEL_ECOLOGY_WORKERS;
    public static final ModConfigSpec.BooleanValue MULTITILE_PREFETCH;
    public static final ModConfigSpec.IntValue MULTITILE_WORKERS;
    public static final ModConfigSpec.BooleanValue LOAD_EXISTING_CHUNKS;
    public static final ModConfigSpec.EnumValue<Mode> MODE;
    public static final ModConfigSpec.IntValue CELLS_PER_TICK;
    public static final ModConfigSpec.IntValue MAX_TICK_MILLIS;
    static {
        var builder = new ModConfigSpec.Builder();
        builder.push("worldPrep");
        STRUCTURES_ONLY = builder.comment("Independent native-structures LAB: disables ecology dispatch and legacy SavedData loading; requires enabled=false.").define("structuresOnly", false);
        PHYSICAL_PASSES = builder.comment("Isolated qualification only; explicit operator activation required. Never enable on active ecology.").define("physicalPasses", false);
        ENABLED = builder.comment("WorldPrep is disabled on public servers by default.").define("enabled", false);
        PARALLEL_DISCOVERY = builder.comment("Two bounded workers for immutable habitat classification only; commits stay on server thread.").define("parallelDiscovery", false);
        PARALLEL_ECOLOGY_PLANNING = builder.comment("RUN39 DEV opt-in only. Pure ecological candidate filtering on immutable snapshots, never Minecraft writes off-thread. Disabled by default.").define("parallelEcologyPlanning", false);
        PARALLEL_ECOLOGY_WORKERS = builder.comment("RUN39 DEV bounded immutable candidate workers: benchmark 1/2/4/8 and prefer 4 only after certification.").defineInRange("parallelEcologyWorkers", 4, 1, 8);
        MULTITILE_PREFETCH = builder.comment("RUN41 DEV opt-in: four independent full-tile pure CPU tasks in flight, deterministic Minecraft commit; never enable RUN35 live.").define("multiTilePrefetch", false);
        MULTITILE_WORKERS = builder.comment("RUN41 DEV maximum independent tiles simultaneously prepared: 1..4, no extra Minecraft writers.").defineInRange("multiTileWorkers",4,1,4);
        LOAD_EXISTING_CHUNKS = builder.comment("Experimental candidate TEST only: verify persisted FULL 3x3 before promotion; operator GameTests required.").define("loadExistingChunks", false);
        MODE = builder.defineEnum("mode", Mode.TEST);
        MAX_TICK_MILLIS = builder.defineInRange("pauseAboveTickMillis", 100, 50, 1000);
        CELLS_PER_TICK = builder.defineInRange("cellsPerTick", 256, 1, 65536);
        builder.pop();
        SPEC = builder.build();
    }
    private WorldPrepConfig() {}
}
