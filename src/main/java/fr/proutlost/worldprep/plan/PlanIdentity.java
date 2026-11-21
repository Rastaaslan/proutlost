package fr.proutlost.worldprep.plan;

import fr.proutlost.worldprep.persistence.WorldPrepSavedData;
import fr.proutlost.worldprep.runtime.BiomeProfileRuntime;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import java.util.Comparator;
import java.util.TreeMap;

/** Captures the actual profile, seed, registry and policies used by the biome planner. */
public final class PlanIdentity {
    public static final String PROFILE="proutlost:river_test";
    /** Compatibility constant for old fixtures only. Runtime code reads the profile seed. */
    public static final long SEED=7640891576956012809L;
    private static final String SEMANTIC_VERSION="semantic-config-v2-data-driven-biome-profile";
    public static long seed(ServerLevel level){return BiomeProfileRuntime.seed(level);}
    public static WorldPrepSavedData.PlanInput capture(ServerLevel level,WorldPrepSavedData d,WorldPrepSavedData.Area a){
        var loaded=BiomeProfileRuntime.resolve(level);loaded.resolved().requireValid();
        String dimension=level.dimension().location().toString();
        String config=a.mask()==WorldPrepSavedData.AreaMask.FULL_AREA
            ?PlanFingerprint.of(SEMANTIC_VERSION).value()
            :PlanFingerprint.of(SEMANTIC_VERSION,"area-mask-v1",a.mask().name()).value();
        var registry=level.registryAccess().registryOrThrow(Registries.BIOME);
        var states=new TreeMap<>(loaded.resolved().registryState());
        for(var override:d.overrides().values())if(override.exactBiome()!=null){
            boolean exists=registry.containsKey(ResourceLocation.parse(override.exactBiome()));
            states.put(override.exactBiome(),exists);
            if(!exists)throw new IllegalStateException("Missing override biome "+override.exactBiome());
        }
        String registryDigest=PlanFingerprint.of(states.entrySet().stream()
            .map(e->e.getKey()+"="+(e.getValue()?e.getKey():"MISSING")).toArray(String[]::new)).value();
        String overrideDigest=PlanFingerprint.of(d.overrides().values().stream()
            .sorted(Comparator.comparingInt(WorldPrepSavedData.OverrideCell::quartZ)
                .thenComparingInt(WorldPrepSavedData.OverrideCell::quartX))
            .map(Object::toString).toArray(String[]::new)).value();
        return new WorldPrepSavedData.PlanInput(dimension,a.minX(),a.minZ(),a.maxX(),a.maxZ(),
            loaded.profile().seed(),loaded.profile().id(),loaded.digest(),config,registryDigest,
            overrideDigest,d.protectionFingerprint());
    }
    public static String fingerprint(WorldPrepSavedData.PlanInput i){
        return PlanFingerprint.of(i.dimension(),Integer.toString(i.minX()),Integer.toString(i.minZ()),
            Integer.toString(i.maxX()),Integer.toString(i.maxZ()),Long.toString(i.seed()),i.profile(),
            i.profileDigest(),i.configDigest(),i.registryDigest(),i.overrideDigest(),i.protectionDigest()).value();
    }
    public static void requireCurrent(WorldPrepSavedData.PlanInput persisted,WorldPrepSavedData.PlanInput current){
        if(!persisted.equals(current))throw new IllegalStateException("PREVIEW STALE: plan inputs changed");
    }
    private PlanIdentity(){}
}
