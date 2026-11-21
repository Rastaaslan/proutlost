package fr.proutlost.worldprep.ecology;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** Pure initial-population allocation. Receipts count issued animals, never current survivors.
 * Caller supplies a complete existing-entity census and post-ecology habitat evidence.
 * This class does not spawn entities or authorize chunk loading.
 */
public final class InitialPopulationPlanner {
    public record Candidate(String species, FaunaSuitability.Evidence habitat, int region) {
        public Candidate { Objects.requireNonNull(species); Objects.requireNonNull(habitat); }
        long chunk() { return ((long)(habitat.x() >> 4) << 32) ^ ((habitat.z() >> 4) & 0xffffffffL); }
    }
    public record Limits(int global, int perSpecies, int perRegion, int perChunk, int separation) {
        public Limits {
            if (global < 0 || global > 100_000 || perSpecies < 0 || perRegion < 0 || perChunk < 0
                    || separation < 0 || separation > 4096) throw new IllegalArgumentException("Invalid density policy");
        }
    }
    public record Placement(UUID operation, UUID entity, Candidate candidate) {}

    public static List<Placement> plan(UUID batch, String frozenConfigDigest, long seed,
            Collection<Candidate> candidates, Collection<Candidate> existing,
            Set<UUID> issuedEntities, Set<String> registeredSpecies, Limits limits) {
        Objects.requireNonNull(batch);
        if (frozenConfigDigest == null || !frozenConfigDigest.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Frozen config digest required");
        if (candidates.size() > 100_000 || existing.size() > 100_000 || issuedEntities.size() > 100_000)
            throw new IllegalArgumentException("Population census/plan exceeds bounded policy");
        var occupied = new ArrayList<>(existing);
        Map<String,Integer> species = new HashMap<>();
        Map<Integer,Integer> regions = new HashMap<>();
        Map<Long,Integer> chunks = new HashMap<>();
        for (var animal : existing) count(animal, species, regions, chunks);
        var identities = new HashMap<UUID,Candidate>();
        for (var candidate : candidates) {
            var previous = identities.putIfAbsent(identity(batch, frozenConfigDigest, seed, candidate), candidate);
            if (previous != null && !previous.equals(candidate))
                throw new IllegalArgumentException("Conflicting habitat evidence for one entity identity");
        }
        var ordered = candidates.stream().distinct().sorted(Comparator
                .comparing((Candidate c) -> identity(batch, frozenConfigDigest, seed, c).toString())).toList();
        List<Placement> result = new ArrayList<>();
        for (var candidate : ordered) {
            // Durable issued receipts consume the initial budget even after death or migration.
            if ((long)result.size() + Math.max(existing.size(), issuedEntities.size()) >= limits.global()) break;
            UUID id = identity(batch, frozenConfigDigest, seed, candidate);
            if (issuedEntities.contains(id)) continue;
            // A partial historical census cannot safely reconstruct per-species/region budgets.
            // Resume returns no new plan: execute/reconcile the original immutable plan instead.
            if (!issuedEntities.isEmpty()) continue;
            if (!"SUITABLE_BY_RULE".equals(FaunaSuitability.evaluate(candidate.habitat(), registeredSpecies).species().get(candidate.species()))) continue;
            if (species.getOrDefault(candidate.species(), 0) >= limits.perSpecies()
                    || regions.getOrDefault(candidate.region(), 0) >= limits.perRegion()
                    || chunks.getOrDefault(candidate.chunk(), 0) >= limits.perChunk()) continue;
            boolean near = occupied.stream().anyMatch(other -> tooClose(candidate, other, limits.separation()));
            if (near) continue;
            result.add(new Placement(UUID.nameUUIDFromBytes(("spawn/" + id).getBytes(StandardCharsets.UTF_8)), id, candidate));
            occupied.add(candidate); count(candidate, species, regions, chunks);
        }
        return List.copyOf(result);
    }
    private static boolean tooClose(Candidate a, Candidate b, int separation) {
        double dx = (double)a.habitat().x() - b.habitat().x(), dz = (double)a.habitat().z() - b.habitat().z();
        return dx * dx + dz * dz <= (double)separation * separation;
    }
    private static UUID identity(UUID batch, String config, long seed, Candidate c) {
        var e = c.habitat();
        return UUID.nameUUIDFromBytes((batch + "/" + config + "/" + seed + "/" + c.species() + "/"
                + e.x() + "/" + e.y() + "/" + e.z()).getBytes(StandardCharsets.UTF_8));
    }
    private static void count(Candidate c, Map<String,Integer> species, Map<Integer,Integer> regions, Map<Long,Integer> chunks) {
        species.merge(c.species(), 1, Integer::sum); regions.merge(c.region(), 1, Integer::sum); chunks.merge(c.chunk(), 1, Integer::sum);
    }
    private InitialPopulationPlanner() {}
}
