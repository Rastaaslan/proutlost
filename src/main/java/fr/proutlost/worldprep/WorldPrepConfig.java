package fr.proutlost.worldprep;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class WorldPrepConfig {
    public enum Mode { TEST, PRODUCTION }
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.BooleanValue ENABLED;
    public static final ModConfigSpec.EnumValue<Mode> MODE;
    public static final ModConfigSpec.IntValue CELLS_PER_TICK;
    static {
        var builder = new ModConfigSpec.Builder();
        builder.push("worldPrep");
        ENABLED = builder.comment("WorldPrep is disabled on public servers by default.").define("enabled", false);
        MODE = builder.defineEnum("mode", Mode.TEST);
        CELLS_PER_TICK = builder.defineInRange("cellsPerTick", 256, 1, 65536);
        builder.pop();
        SPEC = builder.build();
    }
    private WorldPrepConfig() {}
}
