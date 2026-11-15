package fr.proutlost.worldprep.structure;

import fr.proutlost.worldprep.biome.v2.TerrainSemantic;
import fr.proutlost.worldprep.plan.CanonicalDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Canonically scored global allocator with verified envelopes, eligibility, rarity, density, and spacing. */
public final class AutomaticStructurePlanner {
    public record Candidate(String structureId, int x, int y, int z, int suitability,
            TerrainSemantic terrain, int regionId) {
        public Candidate {
            if (structureId == null || structureId.isBlank() || terrain == null) throw new IllegalArgumentException();
        }
    }

    public List<StructureInstancePlan> plan(Collection<Candidate> input, StructureCatalog catalog, long seed,
            int globalLimit, int perRegionLimit) {
        return plan(input, catalog, seed, globalLimit, perRegionLimit,
                CanonicalDigest.sha256().putString("default-structure-policy-v3").finish());
    }

    public List<StructureInstancePlan> plan(Collection<Candidate> input, StructureCatalog catalog, long seed,
            int globalLimit, int perRegionLimit, String frozenConfigSha256) {
        if (frozenConfigSha256 == null || !frozenConfigSha256.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Frozen configuration SHA256 required");
        if (input.size() > 100_000 || catalog.all().size() > 10_000)
            throw new IllegalArgumentException("Bounded catalogue/candidate limit exceeded");
        var digest = CanonicalDigest.sha256().putString("structure-plan-v3").putLong(seed)
                .putString(frozenConfigSha256).putInt(globalLimit).putInt(perRegionLimit);
        for (var c : catalog.all()) {
            digest.putString(c.structureId()).putString(c.status().name()).putString(c.adapterFingerprint())
                    .putInt(c.width()).putInt(c.height()).putInt(c.depth()).putInt(c.clearance())
                    .putInt(c.minimumSpacing()).putInt(c.rarityDivisor()).putInt(c.eligibleTerrain().size());
            c.eligibleTerrain().stream().sorted().forEach(t -> digest.putString(t.name()));
        }
        String revision = digest.finish();
        if (globalLimit < 0 || globalLimit > 100_000 || perRegionLimit < 1) throw new IllegalArgumentException("Invalid density limit");
        var ordered = input.stream().sorted(Comparator.comparingInt(Candidate::suitability).reversed()
                .thenComparing(Candidate::structureId).thenComparingInt(Candidate::regionId)
                .thenComparingInt(Candidate::x).thenComparingInt(Candidate::z).thenComparingInt(Candidate::y)).toList();
        var result = new ArrayList<StructureInstancePlan>();
        Map<Integer, Integer> regionCounts = new HashMap<>();
        for (var candidate : ordered) {
            if (result.size() >= globalLimit) break;
            var capability = catalog.requireProductionSupported(candidate.structureId());
            if (!capability.eligibleTerrain().contains(candidate.terrain())) continue;
            long mixed = mix(seed, candidate);
            if (Math.floorMod(mixed, capability.rarityDivisor()) != 0) continue;
            if (regionCounts.getOrDefault(candidate.regionId(), 0) >= perRegionLimit) continue;
            // Rarity and rotation MUST draw from independent salted bits.
            // Otherwise rarityDivisor=4 forces every admitted site to rotation=NONE.
            var rotation = StructureInstancePlan.Rotation.values()[
                Math.floorMod(avalanche(mixed ^ 0x9e3779b97f4a7c15L),4)];
            boolean quarterTurn = rotation == StructureInstancePlan.Rotation.CLOCKWISE_90
                    || rotation == StructureInstancePlan.Rotation.COUNTERCLOCKWISE_90;
            int width = quarterTurn ? capability.depth() : capability.width();
            int depth = quarterTurn ? capability.width() : capability.depth();
            var footprint = bounds(candidate, width, capability.height(), depth);
            int exclusion = Math.max(capability.clearance(), capability.minimumSpacing());
            var reservation = expand(footprint, exclusion);
            if (result.stream().anyMatch(plan -> plan.reservation().intersects(reservation))) continue;
            String id = CanonicalDigest.sha256().putString(revision).putString(candidate.structureId()).putInt(candidate.x())
                    .putInt(candidate.y()).putInt(candidate.z()).putLong(seed).finish();
            result.add(new StructureInstancePlan(candidate.structureId(), id, candidate.x(), candidate.y(),
                    candidate.z(), rotation, StructureInstancePlan.Mirror.NONE, mixed, footprint, reservation,
                    expand(footprint, capability.clearance())));
            regionCounts.merge(candidate.regionId(), 1, Integer::sum);
        }
        return List.copyOf(result);
    }

    private static StructureInstancePlan.Bounds bounds(Candidate candidate, int width, int height, int depth) {
        int minX = Math.subtractExact(candidate.x(), (width - 1) / 2);
        int minZ = Math.subtractExact(candidate.z(), (depth - 1) / 2);
        return new StructureInstancePlan.Bounds(minX, candidate.y(), minZ, Math.addExact(minX, width - 1),
                Math.addExact(candidate.y(), height - 1), Math.addExact(minZ, depth - 1));
    }

    private static StructureInstancePlan.Bounds expand(StructureInstancePlan.Bounds value, int amount) {
        return new StructureInstancePlan.Bounds(Math.subtractExact(value.minX(), amount), value.minY(), Math.subtractExact(value.minZ(), amount),
                Math.addExact(value.maxX(), amount), value.maxY(), Math.addExact(value.maxZ(), amount));
    }

    /** Cheap deterministic rarity preflight, identical to plan()'s v3
     * selection rule and independent of terrain height/config. This MAY
     * reject a site before leasing chunks, but never grants native placement.
     * The regular plan() method still performs all original checks.
     */
    public static boolean rarityAdmitted(long seed,String structure,int x,int z,int divisor) {
        if(structure==null||structure.isBlank()||divisor<1)
            throw new IllegalArgumentException("Exact structure and rarity bound required");
        long raw=seed ^ ((long)x*341873128712L)
            ^ ((long)z*132897987541L) ^ structure.hashCode();
        return Math.floorMod(avalanche(raw),divisor)==0;
    }

    /** Avalanche all coordinate bits before applying the rarity divisor.
     * The v2 raw XOR correlated with even-offset sampling, systematically
     * rejecting EVERY S2 structure candidate for some seeds/templates.
     * A stable SplitMix64 finalizer retains deterministic seed-dependent
     * rarity and rotation while breaking this low-bit parity artifact.
     * The planner identity is explicitly revisioned to v3 above.
     */
    private static long mix(long seed, Candidate candidate) {
        long value=seed ^ ((long)candidate.x()*341873128712L)
            ^ ((long)candidate.z()*132897987541L)
            ^ candidate.structureId().hashCode();
        return avalanche(value);
    }
    private static long avalanche(long value) {
        value=(value^(value>>>30))*0xbf58476d1ce4e5b9L;
        value=(value^(value>>>27))*0x94d049bb133111ebL;
        return value^(value>>>31);
    }
}
