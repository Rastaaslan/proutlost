package fr.proutlost.worldprep.runtime;

import fr.proutlost.worldprep.biome.BiomeProfile;
import fr.proutlost.worldprep.plan.PlanFingerprint;
import fr.proutlost.worldprep.plan.PlanIdentity;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.packs.resources.ResourceManager;

/** Server-data-pack profile snapshot; a new resource manager after reload creates a new cache entry. */
public final class BiomeProfileRuntime {
    public static final ResourceLocation RESOURCE =
        ResourceLocation.fromNamespaceAndPath("proutlost","worldprep/profiles/river_test.json");
    private static final Map<ResourceManager,Cached> CACHE = new WeakHashMap<>();
    private static final class Cached {
        final BiomeProfile profile;final String digest;
        final Map<Object,BiomeProfile.Resolved> registries=new IdentityHashMap<>();
        Cached(BiomeProfile profile,String digest){this.profile=profile;this.digest=digest;}
    }
    public record Loaded(BiomeProfile profile,String digest,BiomeProfile.Resolved resolved) {}
    private BiomeProfileRuntime(){}

    private static Cached profile(ServerLevel level){
        ResourceManager manager=Objects.requireNonNull(level.getServer().getResourceManager(),"Server resource manager");
        synchronized(CACHE){
            var cached=CACHE.get(manager);
            if(cached!=null)return cached;
            try(var stream=manager.getResource(RESOURCE)
                    .orElseThrow(()->new IOException("Missing biome profile "+RESOURCE)).open()){
                byte[] bytes=stream.readNBytes(BiomeProfile.MAX_BYTES+1);
                if(bytes.length>BiomeProfile.MAX_BYTES)throw new IOException("Profile byte bound");
                String text=new String(bytes,StandardCharsets.UTF_8);
                var profile=BiomeProfile.parse(text);
                if(!profile.id().equals(PlanIdentity.PROFILE))
                    throw new IllegalArgumentException("Biome profile identity mismatch: "+profile.id());
                var result=new Cached(profile,PlanFingerprint.of(text).value());
                CACHE.put(manager,result);return result;
            }catch(IOException failure){
                throw new IllegalStateException("Cannot read WorldPrep biome profile "+RESOURCE,failure);
            }
        }
    }
    public static long seed(ServerLevel level){return profile(level).profile.seed();}
    public static Loaded resolve(ServerLevel level){
        var cached=profile(level);
        var registry=level.registryAccess().registryOrThrow(Registries.BIOME);
        synchronized(cached){
            var resolved=cached.registries.computeIfAbsent(registry,ignored->
                cached.profile.resolve(id->registry.containsKey(ResourceLocation.parse(id))));
            return new Loaded(cached.profile,cached.digest,resolved);
        }
    }
}
