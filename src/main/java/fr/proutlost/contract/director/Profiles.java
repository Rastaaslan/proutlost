package fr.proutlost.contract.director;

import java.util.*;
import static fr.proutlost.contract.director.Vocabulary.*;

/** Initial synthetic profile. D7 owns measurement/calibration, within these finite ceilings. */
public final class Profiles {
    private Profiles() {}
    public static final long LONG_SESSION_MS = 4 * 60 * 60 * 1000L;
    public static final int LOAD_PLAYERS = 25;
    public static final long PLATEAU_WINDOW_MS = 30 * 60 * 1000L;
    public static final int MAX_PLATEAU_GROWTH_PERCENT = 5;
    public static final long INITIAL_EFFECT_TTL_MS = 10_000;
    public static final long INITIAL_COOLDOWN_MS = 60_000;
    public static final int MAX_DEBT = 1_000_000;
    public static final Map<BudgetCategory,Integer> INITIAL_BUDGETS = Map.of(
        BudgetCategory.OBSERVATION, 256, BudgetCategory.SELECTION, 128, BudgetCategory.INSTANCE, 32,
        BudgetCategory.EFFECT, 128, BudgetCategory.NETWORK, 65_536, BudgetCategory.MEMORY, 1024,
        BudgetCategory.STORAGE, 262_144, BudgetCategory.CLEANUP, 64, BudgetCategory.DIAGNOSTICS, 256);
    static void validate(Map<BudgetCategory,Integer> budgets, Map<Id,Integer> weights,
                         Map<Id,Long> cooldown, Map<Id,Long> grace, Map<MemoryKind,Long> retention,
                         Map<Id,Integer> caps, Map<Id,Integer> votes, Map<Id,Integer> contributions) {
        Checks.require(budgets.keySet().equals(EnumSet.allOf(BudgetCategory.class)), "Every budget required");
        budgets.values().forEach(v -> Checks.require(v > 0 && v <= Limits.MAX_BUDGET, "Budget ceiling"));
        weights.values().forEach(v -> Checks.require(v >= 0 && v <= MAX_DEBT, "Weight ceiling"));
        cooldown.values().forEach(Checks::duration); grace.values().forEach(Checks::duration);
        Checks.require(retention.keySet().equals(EnumSet.allOf(MemoryKind.class)), "Every retention required");
        retention.values().forEach(Checks::duration);
        for (var map : List.of(caps, votes, contributions)) map.values().forEach(v -> Checks.require(v > 0 && v <= Limits.MAX_ITEMS, "Profile count ceiling"));
    }
    public static TuningProfile initial() {
        var retention = new EnumMap<MemoryKind,Long>(MemoryKind.class);
        for (var kind : MemoryKind.values()) retention.put(kind, Limits.ACTIVE_PLAY_WINDOW_MS);
        var zero = new Digest("0".repeat(64)); var id = new Id("proutlost:fixture_safe"); var revision = new Revision("d0-r1");
        var profile = new TuningProfile(SchemaVersion.EXECUTION, id, revision, zero, INITIAL_BUDGETS,
            Map.of(new Id("proutlost:attention_debt"), 1000), Map.of(id, INITIAL_COOLDOWN_MS), Map.of(id, 30_000L),
            List.of(), new HealthThresholds(45, 18, 10_000, 30_000), retention, Map.of(id, 1024),
            new MemoryGridDefinition(revision, new Id("minecraft:overworld"), 0, 128, 128, zero), Map.of(id, 2), Map.of(id, 2));
        return new TuningProfile(profile.schema_version(), id, revision, StrictCodec.contentDigest(profile), profile.budgets(), profile.weights(),
            profile.cooldown_ms(), profile.grace_ms(), profile.quiet_periods(), profile.health(), profile.retention_ms(), profile.summary_caps(),
            profile.grid(), profile.vote_calibration(), profile.contribution_calibration());
    }
}
