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
        if (globalLimit < 0 || perRegionLimit < 1) throw new IllegalArgumentException("Invalid density limit");
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
            var rotation = StructureInstancePlan.Rotation.values()[Math.floorMod(mixed, 4)];
            boolean quarterTurn = rotation == StructureInstancePlan.Rotation.CLOCKWISE_90
                    || rotation == StructureInstancePlan.Rotation.COUNTERCLOCKWISE_90;
            int width = quarterTurn ? capability.depth() : capability.width();
            int depth = quarterTurn ? capability.width() : capability.depth();
            var footprint = bounds(candidate, width, capability.height(), depth);
            int exclusion = Math.max(capability.clearance(), capability.minimumSpacing());
            var reservation = expand(footprint, exclusion);
            if (result.stream().anyMatch(plan -> plan.reservation().intersects(reservation))) continue;
            String id = CanonicalDigest.sha256().putString(candidate.structureId()).putInt(candidate.x())
                    .putInt(candidate.y()).putInt(candidate.z()).putLong(seed).finish();
            result.add(new StructureInstancePlan(candidate.structureId(), id, candidate.x(), candidate.y(),
                    candidate.z(), rotation, StructureInstancePlan.Mirror.NONE, mixed, footprint, reservation,
                    expand(footprint, capability.clearance())));
            regionCounts.merge(candidate.regionId(), 1, Integer::sum);
        }
        return List.copyOf(result);
    }

    private static StructureInstancePlan.Bounds bounds(Candidate candidate, int width, int height, int depth) {
        int minX = candidate.x() - (width - 1) / 2;
        int minZ = candidate.z() - (depth - 1) / 2;
        return new StructureInstancePlan.Bounds(minX, candidate.y(), minZ, minX + width - 1,
                Math.addExact(candidate.y(), height - 1), minZ + depth - 1);
    }

    private static StructureInstancePlan.Bounds expand(StructureInstancePlan.Bounds value, int amount) {
        return new StructureInstancePlan.Bounds(value.minX() - amount, value.minY(), value.minZ() - amount,
                value.maxX() + amount, value.maxY(), value.maxZ() + amount);
    }

    private static long mix(long seed, Candidate candidate) {
        return seed ^ ((long) candidate.x() * 341873128712L) ^ ((long) candidate.z() * 132897987541L)
                ^ candidate.structureId().hashCode();
    }
}
