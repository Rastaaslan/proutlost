package fr.proutlost.worldprep.runtime;

import fr.proutlost.worldprep.WorldPrepConfig;
import fr.proutlost.worldprep.biome.BiomeCandidate;
import fr.proutlost.worldprep.persistence.WorldPrepSavedData;
import fr.proutlost.worldprep.plan.PlanIdentity;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

/** Startup inventory only: no terrain reads, jobs, dirty flags, or acceptance claim. */
public final class WorldPrepStartupReport {
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
            var loadedProfile=BiomeProfileRuntime.resolve(server.overworld());
            var counts = loadedProfile.resolved().counts();
            System.out.println("WORLDPREP_STARTUP environmentProfile=" + EcologyMutationPlanner.validateProfile(server.overworld()) + " plannerVersion=" + EcologyMutationPlanner.VERSION + " suitability=UNKNOWN_NOT_PLANNED");
            failed = counts.get(BiomeCandidate.Status.MISSING) > 0;
            degraded = counts.get(BiomeCandidate.Status.FALLBACK) > 0;
            System.out.println("WORLDPREP_STARTUP profileResource=LOADED declaredBiomeCandidates=" + counts
                    + " profileSeed=" + loadedProfile.profile().seed() + " profileDigest=" + loadedProfile.digest()
                    + " biomeRegistry=" + biomes.size() + " plannerSuitability=UNKNOWN_NOT_PLANNED");
            loadedProfile.resolved().requireValid();
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
        } catch (RuntimeException unavailable) {
            failed = true;
            System.out.println("WORLDPREP_STARTUP profileOrRegistry=FAILED reason=" + unavailable.getClass().getSimpleName()
                    + " detail=" + diagnostic(unavailable) + " unresolvedCounts=UNKNOWN");
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

    private static String diagnostic(Throwable failure) {
        var text=new StringBuilder();
        for(int depth=0;failure!=null&&depth<4;depth++,failure=failure.getCause()){
            if(depth>0)text.append(" <- ");
            text.append(failure.getClass().getSimpleName()).append(": ").append(failure.getMessage());
        }
        String line=text.toString().replaceAll("[\\p{Cntrl}]", " ");
        return line.substring(0,Math.min(512,line.length()));
    }
    private static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath("proutlost", path); }
    private static <T> int tagSize(Registry<T> registry, TagKey<T> tag) {
        return registry.getTag(tag).map(values -> values.size()).orElse(0);
    }
}
