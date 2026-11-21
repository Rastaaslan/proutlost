package fr.proutlost.worldprep.runtime;

import com.google.gson.JsonParser;
import fr.proutlost.worldprep.WorldPrepConfig;
import fr.proutlost.worldprep.biome.BiomeCandidate;
import fr.proutlost.worldprep.persistence.WorldPrepSavedData;
import fr.proutlost.worldprep.plan.PlanIdentity;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

/** Startup inventory only: no terrain reads, jobs, dirty flags, or acceptance claim. */
public final class WorldPrepStartupReport {
    private static final String PROFILE_RESOURCE = "data/proutlost/worldprep/profiles/river_test.json";
    private WorldPrepStartupReport() {}

    public static void started(ServerStartedEvent event) {
        var server = event.getServer();
        boolean enabled = WorldPrepConfig.ENABLED.get();
        boolean failed = false, degraded = false;
        int levels = 0;
        System.out.println("=== PROUTLOST WORLDPREP ===");
        System.out.println("WORLDPREP_STARTUP enabled=" + enabled + " mode=" + WorldPrepConfig.MODE.get()
                + " cellsPerTick=" + WorldPrepConfig.CELLS_PER_TICK.get() + " profile=" + PlanIdentity.PROFILE);
        try {
            var biomes = server.registryAccess().registryOrThrow(Registries.BIOME);
            var counts = profileCandidates(biomes);
            System.out.println("WORLDPREP_STARTUP environmentProfile=" + EcologyMutationPlanner.validateProfile() + " plannerVersion=" + EcologyMutationPlanner.VERSION + " suitability=UNKNOWN_NOT_PLANNED");
            failed = counts.get(BiomeCandidate.Status.MISSING) > 0;
            degraded = counts.get(BiomeCandidate.Status.FALLBACK) > 0;
            System.out.println("WORLDPREP_STARTUP profileResource=LOADED declaredBiomeCandidates=" + counts
                    + " biomeRegistry=" + biomes.size() + " plannerSuitability=UNKNOWN_NOT_PLANNED");
            var blocks = server.registryAccess().registryOrThrow(Registries.BLOCK);
            var entities = server.registryAccess().registryOrThrow(Registries.ENTITY_TYPE);
            for (var name : new String[]{"geology_replaceable", "ore_replaceable/stone", "ore_replaceable/deepslate", "soil_replaceable"}) {
                int size = tagSize(blocks, TagKey.create(Registries.BLOCK, id(name)));
                System.out.println("WORLDPREP_STARTUP hostTag=proutlost:" + name + " members=" + size);
                degraded |= size == 0;
            }
            for (var name : new String[]{"wild_animals", "predators", "prey", "defensive_animals", "camp_tolerant", "marine_animals"}) {
                int size = tagSize(entities, TagKey.create(Registries.ENTITY_TYPE, id(name)));
                System.out.println("WORLDPREP_STARTUP entityTag=proutlost:" + name + " members=" + size
                        + " populationSuitability=UNKNOWN_NOT_PLANNED");
                degraded |= size == 0;
            }
            System.out.println("WORLDPREP_STARTUP blockRegistry=" + blocks.size() + " entityRegistry=" + entities.size()
                    + " geologyOreFloraTreeAquaticSuitability=UNKNOWN_NOT_PLANNED");
        } catch (RuntimeException | IOException unavailable) {
            failed = true;
            System.out.println("WORLDPREP_STARTUP profileOrRegistry=FAILED reason=" + unavailable.getClass().getSimpleName()
                    + " unresolvedCounts=UNKNOWN");
        }
        for (var level : server.getAllLevels()) {
            levels++;
            if (!enabled) continue; // Do not instantiate/load legacy SavedData on a disabled public server.
            try {
                var data = WorldPrepRuntime.data(level);
                long nonterminal = data.jobs().values().stream().filter(job -> switch (job.state()) {
                    case QUEUED, RUNNING, PAUSED -> true;
                    default -> false;
                }).count();
                long failedJobs = data.jobs().values().stream()
                        .filter(job -> job.state() == WorldPrepSavedData.JobState.FAILED).count();
                var blockData = BlockMutationRuntime.data(level);
                failed |= !blockData.compatible();
                degraded |= nonterminal > 0 || failedJobs > 0;
                System.out.println("WORLDPREP_STARTUP dimension=" + level.dimension().location()
                        + " areas=" + data.areas().size() + " locations=" + data.locations().locations().size()
                        + " directProtectedZones=" + data.protections().size() + " jobs=" + data.jobs().size()
                        + " nonterminalJobs=" + nonterminal + " failedJobs=" + failedJobs
                        + " blockPersistenceCompatible=" + blockData.compatible()
                        + " journalRecoveryVerification=NOT_RUN");
            } catch (RuntimeException unavailable) {
                failed = true;
                System.out.println("WORLDPREP_STARTUP dimension=" + level.dimension().location()
                        + " persistedState=FAILED reason=" + unavailable.getClass().getSimpleName());
            }
        }
        System.out.println("WORLDPREP_STARTUP levels=" + levels + " persistedState=" + (enabled ? "READ_ONLY_INVENTORY" : "NOT_LOADED_DISABLED")
                + " status=" + (!enabled ? "DISABLED" : failed ? "FAILED" : degraded ? "DEGRADED" : "READY")
                + " statusScope=STARTUP_CONTENT_AND_STATE_INVENTORY acceptance=NOT_RUN autoJobs=NONE");
    }

    private static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath("proutlost", path); }
    private static <T> int tagSize(Registry<T> registry, TagKey<T> tag) {
        return registry.getTag(tag).map(values -> values.size()).orElse(0);
    }
    private static EnumMap<BiomeCandidate.Status, Integer> profileCandidates(Registry<Biome> registry) throws IOException {
        var counts = new EnumMap<BiomeCandidate.Status, Integer>(BiomeCandidate.Status.class);
        for (var status : BiomeCandidate.Status.values()) counts.put(status, 0);
        try (var stream = WorldPrepStartupReport.class.getClassLoader().getResourceAsStream(PROFILE_RESOURCE)) {
            if (stream == null) throw new IOException("Missing profile");
            byte[] bytes = stream.readNBytes(65537);
            if (bytes.length > 65536) throw new IOException("Profile byte bound");
            var profile = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
            if (profile.get("schema").getAsInt() != 1 || !PlanIdentity.PROFILE.equals(profile.get("id").getAsString()))
                throw new IOException("Profile identity/schema");
            for (var family : profile.getAsJsonObject("biomes").entrySet()) {
                for (var element : family.getValue().getAsJsonArray()) {
                    var candidate = element.getAsJsonObject();
                    var resolution = new BiomeCandidate(candidate.get("id").getAsString(),
                            candidate.has("fallback") ? candidate.get("fallback").getAsString() : null)
                            .resolve(value -> registry.containsKey(ResourceLocation.parse(value)));
                    counts.merge(resolution.status(), 1, Math::addExact);
                }
            }
            if (counts.values().stream().mapToInt(Integer::intValue).sum() == 0) throw new IOException("Empty candidate profile");
            return counts;
        }
    }
}
