package fr.proutlost.director.memory;

import fr.proutlost.director.api.codec.CanonicalJsonV1;
import fr.proutlost.director.api.model.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Standalone JDK21 domain checks against the frozen contract JAR. No testkit in gameplay. */
public final class MemoryDomainTest {
    private static int assertions;
    private static Map<String, JsonValue> defaults;
    private static final DimensionId OVERWORLD = new DimensionId("minecraft:overworld");

    public static void main(String[] args) throws Exception {
        byte[] profile = Files.readAllBytes(Path.of(args[0]));
        check(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(profile))
                .equals("a0ba94dfec141788b6f3768a585fec5208d9270e10fc0202139b4dae228cd81d"), "authentic calibration");
        defaults = ((JsonValue.ObjectValue) ((JsonValue.ObjectValue) CanonicalJsonV1.decode(profile)).values().get("values")).values();
        memory(); fairness(); geometry(); contractGap();
        System.out.println("PASS MemoryDomainTest assertions=" + assertions);
        System.out.println("Arithmetic/geometry oracles only; native and end-to-end qualification NOT_RUN");
    }

    private static double number(String key) { return ((JsonValue.Number) defaults.get(key)).value().doubleValue(); }
    private static long ticks(String key) { return ((JsonValue.Number) defaults.get(key)).value().longValueExact(); }
    private static void check(boolean value, String message) { assertions++; if (!value) throw new AssertionError(message); }
    private static void near(double actual, double expected, String message) {
        check(Math.abs(actual - expected) <= 1e-12, message + ": " + actual + " != " + expected);
    }
    private static void rejects(Runnable action, String message) {
        try { action.run(); } catch (IllegalArgumentException e) { check(true, message); return; }
        throw new AssertionError("expected rejection: " + message);
    }

    private static void memory() {
        long visit = ticks("memory.visit_half_life_ticks"), activity = ticks("memory.activity_half_life_ticks");
        Tick start = new Tick(11), half = start.plus(visit);
        near(MemoryMath.decay(1, start, half, visit), .5, "half life");
        near(MemoryMath.decay(1, start, start.plus(visit * 2), visit), .25, "skipped ticks");
        check(MemoryMath.decay(1, start, half, activity) < .001, "component specific decay");
        near(MemoryMath.decay(.8, start, start, visit), .8, "stopped simulation clock");
        rejects(() -> MemoryMath.decay(1, half, start, visit), "rollback");
        rejects(() -> MemoryMath.decay(1, start, half, 0), "zero half life");
        rejects(() -> MemoryMath.decay(Double.NaN, start, half, visit), "NaN");
        rejects(() -> MemoryMath.decay(Double.POSITIVE_INFINITY, start, half, visit), "infinity");
        DecayValue before = new DecayValue(1, start, "memory.visit_half_life_ticks", 4, new UnitValue(.7));
        DecayValue next = MemoryMath.advance(MemoryComponent.VISIT, before, half, visit, .1, 1);
        near(next.value(), .6, "decay then addition");
        check(next.evidenceCount() == 5 && before.evidenceCount() == 4, "immutable evidence");
        near(MemoryMath.advance(MemoryComponent.VISIT, next, half, visit, 0, 1).value(), .6, "no double decay");
        rejects(() -> MemoryMath.advance(MemoryComponent.NARRATIVE_FACT, before, half, visit, 0, 1), "durable facts excluded");
        rejects(() -> MemoryMath.advance(MemoryComponent.WORLD_CHANGE, before, half, visit, 0, 1), "tombstones excluded");
        for (int i = 1; i <= 100; i++) {
            Tick mid = start.plus(i * 71L), end = mid.plus(i * 193L);
            near(MemoryMath.decay(MemoryMath.decay(.9, start, mid, activity), mid, end, activity),
                    MemoryMath.decay(.9, start, end, activity), "partition invariant");
        }
        long personality = ticks("region.personality_half_life_ticks");
        var old = new RegionPersonality(Map.of(RegionTrait.SOCIAL, new UnitValue(.2)));
        var evolved = MemoryMath.personality(old, Map.of(RegionTrait.SOCIAL, new UnitValue(.8)), Map.of(),
                start, start.plus(personality), personality, number("region.major_impulse_cap"));
        near(evolved.traits().get(RegionTrait.SOCIAL).value(), .5, "EMA");
        var impulse = MemoryMath.personality(old, Map.of(), Map.of(RegionTrait.SOCIAL, 10.0),
                start, start, personality, number("region.major_impulse_cap"));
        near(impulse.traits().get(RegionTrait.SOCIAL).value(), .45, "bounded major impulse");
    }

    private static void fairness() {
        double gain = number("fairness.debt_gain_per_tick"), cap = number("fairness.debt_cap");
        near(FairnessMath.debt(new UnitValue(.5), new DurationTicks(0), gain, cap, 0).value(), .5, "offline has no debt gain");
        near(FairnessMath.debt(new UnitValue(.5), new DurationTicks(1000), gain, cap, 0).value(), .51, "eligible gain");
        near(FairnessMath.debt(new UnitValue(.5), new DurationTicks(0), gain, cap,
                number("fairness.confirmed_exposure_cost")).value(), .25, "confirmed exposure");
        near(FairnessMath.debt(new UnitValue(.5), new DurationTicks(0), gain, cap,
                number("fairness.micro_exposure_cost")).value(), .49, "micro does not reset debt");
        near(FairnessMath.debt(new UnitValue(.9), new DurationTicks(Long.MAX_VALUE), gain, cap, 0).value(), 1, "debt cap");
        rejects(() -> FairnessMath.debt(new UnitValue(0), new DurationTicks(2), Double.MAX_VALUE, cap, 0), "overflow");
        PlayerId a = new PlayerId(new UUID(0, 1)), b = new PlayerId(new UUID(0, 2));
        Map<PlayerId, UnitValue> scores = Map.of(a, new UnitValue(.8), b, new UnitValue(.2));
        near(FairnessMath.audienceScore(List.of(a, a, b), scores, new UnitValue(1)).value(), .5, "deduplicated mean");
        near(FairnessMath.audienceScore(List.of(a), scores, new UnitValue(1)).value(), .8, "solo not evicted by sum");
        near(FairnessMath.audienceScore(List.of(a, b), scores, new UnitValue(.5)).value(), .35, "individual cap");
        Map<PlayerId, UnitValue> identical = Map.of(a, new UnitValue(.6), b, new UnitValue(.6));
        near(FairnessMath.audienceScore(List.of(a, b), identical, new UnitValue(1)).value(), .6, "identical group equals solo");
        Exposure early = new Exposure(new DefinitionId("test:a"), Family.AMBIENCE, "witness", new Tick(1), new UnitValue(.2));
        Exposure late = new Exposure(new DefinitionId("test:b"), Family.SOCIAL, "witness", new Tick(2), new UnitValue(.3));
        check(FairnessMath.recent(List.of(late, early), 1).equals(List.of(late)), "bounded recent ring");
    }

    private static RegionRecord region(long id, SupportShape shape, double importance) {
        UUID uuid = new UUID(0, id);
        return new RegionRecord(new AggregateHeader(1, uuid, new Revision(1), new Sequence(0), new Tick(0)),
                new RegionId(uuid), OVERWORLD, RegionStatus.ACTIVE, shape, Set.of(), new UnitValue(importance),
                Set.of(), Set.of(), new Revision(1), new RegionPersonality(Map.of()), Set.of());
    }
    private static BlockPosition point(int x, int y, int z) { return new BlockPosition(OVERWORLD, x, y, z); }

    private static void geometry() {
        SupportShape disc = new SupportShape(List.of(), List.of(new SupportDisc(point(-32, 64, -32), 32)), List.of());
        RegionRecord first = region(1, disc, .5), second = region(2, disc, .8);
        int cellSize = (int) ticks("spatial.region_cell_blocks");
        SparseRegionIndex index = new SparseRegionIndex(List.of(second, first), cellSize, 100);
        check(index.occupiedCells() == 4, "sparse negative-coordinate cells");
        for (int x = -70; x <= 6; x++) for (int z = -70; z <= 6; z++) {
            boolean expected = (x + 32) * (x + 32) + (z + 32) * (z + 32) <= 32 * 32;
            check(!index.containing(point(x, 64, z)).isEmpty() == expected, "disc exact membership");
        }
        check(index.primary(point(-32, 64, -32), Map.of()).orElseThrow().equals(second.regionId()), "importance");
        check(index.primary(point(-32, 64, -32), Map.of(first.regionId(), new UnitValue(1))).orElseThrow().equals(first.regionId()), "activity relevance first");
        RegionRecord tie = region(3, disc, .5);
        check(new SparseRegionIndex(List.of(tie, first), cellSize, 100).primary(point(-32, 64, -32), Map.of())
                .orElseThrow().equals(first.regionId()), "canonical tie break");
        check(index.containing(new BlockPosition(new DimensionId("minecraft:the_nether"), -32, 64, -32)).isEmpty(), "dimensions isolated");
        SupportShape box = new SupportShape(List.of(new Aabb(OVERWORLD, 0, 0, 0, 2, 2, 2)), List.of(), List.of());
        check(SupportGeometry.contains(box, point(0, 0, 0)), "min included");
        check(!SupportGeometry.contains(box, point(2, 1, 1)), "max excluded");
        SupportShape route = new SupportShape(List.of(), List.of(), List.of(new RouteSegment(point(0, 0, 0), point(10, 0, 0), 2)));
        SparseRegionIndex routeIndex = new SparseRegionIndex(List.of(region(4, route, .5)), cellSize, 100);
        check(!routeIndex.containing(point(5, 2, 0)).isEmpty(), "route radius boundary");
        check(routeIndex.containing(point(5, 3, 0)).isEmpty(), "route narrow phase");
        check(routeIndex.containing(point(13, 0, 0)).isEmpty(), "segment endpoint");
        rejects(() -> new SparseRegionIndex(List.of(first, first), cellSize, 100), "duplicate identity");
        rejects(() -> new SparseRegionIndex(List.of(first), cellSize, 1), "budget overflow explicit");
    }

    private static void contractGap() {
        check(ticks("pattern.activity_time_bins") == 12, "normative time bins");
        check(new ActivityTimeCounts(12, java.util.Collections.nCopies(12,0L)).counts().size()==12, "indexed histogram represents twelve bins");
        Set<String> fields = new HashSet<>();
        for (var field : Exposure.class.getRecordComponents()) fields.add(field.getName());
        check(!fields.contains("eventId") && !fields.contains("outcomeRef"), "exposure lacks significant result provenance");
        System.out.println("Contract checks PASS: indexed time histogram API1.0.1 available; Exposure remains operational (significant archive creation assigned to J05)");
    }
}
