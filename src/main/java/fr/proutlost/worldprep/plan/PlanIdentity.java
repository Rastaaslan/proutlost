package fr.proutlost.worldprep.plan;

import fr.proutlost.worldprep.WorldPrepConfig;
import fr.proutlost.worldprep.persistence.WorldPrepSavedData;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;

import java.io.IOException;
import java.io.InputStream;
import java.util.Comparator;

/** Captures every non-terrain material input used by the river_test biome planner. */
public final class PlanIdentity {
    public static final String PROFILE="proutlost:river_test";public static final long SEED=7640891576956012809L;
    private static final String[] BIOMES={"minecraft:beach","minecraft:stony_shore","minecraft:plains","minecraft:forest","minecraft:windswept_hills","minecraft:jagged_peaks","minecraft:lukewarm_ocean","minecraft:ocean","minecraft:deep_ocean"};
    public static WorldPrepSavedData.PlanInput capture(ServerLevel level,WorldPrepSavedData d,WorldPrepSavedData.Area a){String dimension=level.dimension().location().toString();String profile=digestResource("data/proutlost/worldprep/profiles/river_test.json");String config=PlanFingerprint.of(Boolean.toString(WorldPrepConfig.ENABLED.get()),WorldPrepConfig.MODE.get().name(),Integer.toString(WorldPrepConfig.CELLS_PER_TICK.get())).value();var registry=level.registryAccess().registryOrThrow(Registries.BIOME);String[] resolved=java.util.Arrays.stream(BIOMES).map(id->id+"="+registry.getHolder(net.minecraft.resources.ResourceLocation.parse(id)).map(h->h.unwrapKey().orElseThrow().location().toString()).orElse("MISSING")).toArray(String[]::new);String registryDigest=PlanFingerprint.of(resolved).value();String overrideDigest=PlanFingerprint.of(d.overrides().values().stream().sorted(Comparator.comparingInt(WorldPrepSavedData.OverrideCell::quartZ).thenComparingInt(WorldPrepSavedData.OverrideCell::quartX)).map(Object::toString).toArray(String[]::new)).value();String protectionDigest=PlanFingerprint.of(d.protections().values().stream().sorted(Comparator.comparing(WorldPrepSavedData.ProtectedZone::id)).map(Object::toString).toArray(String[]::new)).value();return new WorldPrepSavedData.PlanInput(dimension,a.minX(),a.minZ(),a.maxX(),a.maxZ(),SEED,PROFILE,profile,config,registryDigest,overrideDigest,protectionDigest);}
    public static String fingerprint(WorldPrepSavedData.PlanInput i){return PlanFingerprint.of(i.dimension(),Integer.toString(i.minX()),Integer.toString(i.minZ()),Integer.toString(i.maxX()),Integer.toString(i.maxZ()),Long.toString(i.seed()),i.profile(),i.profileDigest(),i.configDigest(),i.registryDigest(),i.overrideDigest(),i.protectionDigest()).value();}
    public static void requireCurrent(WorldPrepSavedData.PlanInput persisted,WorldPrepSavedData.PlanInput current){if(!persisted.equals(current))throw new IllegalStateException("PREVIEW STALE: plan inputs changed");}
    private static String digestResource(String path){try(InputStream in=PlanIdentity.class.getClassLoader().getResourceAsStream(path)){if(in==null)throw new IllegalStateException("Missing profile "+path);return PlanFingerprint.of(new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)).value();}catch(IOException e){throw new IllegalStateException("Cannot read profile",e);}}
    private PlanIdentity(){}
}
