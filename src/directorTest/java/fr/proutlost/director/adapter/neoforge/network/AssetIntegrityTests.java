package fr.proutlost.director.adapter.neoforge.network;

import fr.proutlost.director.api.model.*;
import fr.proutlost.director.client.perception.PerceptionAssetLoader;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import java.io.*;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Offline, byte-level J06 resource integrity; no client, sound or assets are promoted. */
final class AssetIntegrityTests {
    private AssetIntegrityTests() {}
    private static ResourceManager memory(Map<ResourceLocation,byte[]> files) {
        return (ResourceManager)Proxy.newProxyInstance(
            ResourceManager.class.getClassLoader(),
            new Class<?>[]{ResourceManager.class},
            (proxy,method,args)->{
                if(method.getName().equals("open")) {
                    byte[] bytes=files.get((ResourceLocation)args[0]);
                    if(bytes==null)throw new FileNotFoundException(String.valueOf(args[0]));
                    return new ByteArrayInputStream(bytes);
                }
                if(method.getName().equals("toString"))return "WorldDirector test-only bytes";
                throw new UnsupportedOperationException(method.getName());
            });
    }
    private static Fingerprint fingerprint(ResourceManager resources,
            PerceptionAssetLoader.Definition definition,int maxBytes)throws IOException {
        return PerceptionAssetLoader.prepare(resources,List.of(definition),
            ProviderMode.TEST_ONLY,maxBytes).get(definition.id()).hash();
    }
    static void run() {
        try {
            var texture=ResourceLocation.parse("proutlost_director:textures/director/icon.png");
            var alternate=ResourceLocation.parse("proutlost_director:textures/director/other.png");
            var missing=ResourceLocation.parse("proutlost_director:textures/director/missing.png");
            var id=new ResourceId("proutlost_director:visual_asset_a");
            var otherId=new ResourceId("proutlost_director:visual_asset_b");
            var closure=Set.of(texture,alternate);
            var files=new HashMap<ResourceLocation,byte[]>();
            files.put(texture,"KNOWN_TEXTURE_BYTES".getBytes(StandardCharsets.UTF_8));
            files.put(alternate,"KNOWN_SECOND_TEXTURE".getBytes(StandardCharsets.UTF_8));
            var resource=memory(files);
            var original=new PerceptionAssetLoader.Definition(id,
                PerceptionKind.SYMBOL,texture,closure,ProviderMode.TEST_ONLY);
            var originalHash=fingerprint(resource,original,1024);
            var actualBytes=files.get(texture).clone();
            var declared=BlobContent.hash(actualBytes);
            var shipped=Map.of(id,new DirectorGameplayAssets.Spec(PerceptionKind.SYMBOL,texture.toString(),Map.of(texture.toString(),declared),declared));
            var firstPrepared=PerceptionAssetLoader.prepare(resource,shipped,1024);
            NetworkTests.check(firstPrepared.containsKey(id),"effective shipped asset bytes initially verified");
            files.put(texture,"RELOADED_DIFFERENT_TEXTURE".getBytes(StandardCharsets.UTF_8));
            NetworkTests.check(!PerceptionAssetLoader.prepare(resource,shipped,1024).containsKey(id),"reload cannot retain the previous approved hash for changed bytes");
            files.put(texture,actualBytes);
            NetworkTests.check(PerceptionAssetLoader.prepare(resource,shipped,1024).equals(firstPrepared),"restoring the actual bytes permits a fresh matching registry");
            var reordered=new LinkedHashSet<ResourceLocation>();
            reordered.add(alternate);reordered.add(texture);
            NetworkTests.check(originalHash.equals(fingerprint(resource,
                new PerceptionAssetLoader.Definition(id,PerceptionKind.SYMBOL,
                    texture,reordered,ProviderMode.TEST_ONLY),1024)),
                "asset closure fingerprint depends on unordered Set insertion");
            NetworkTests.check(!originalHash.equals(fingerprint(resource,
                new PerceptionAssetLoader.Definition(otherId,PerceptionKind.SYMBOL,
                    texture,closure,ProviderMode.TEST_ONLY),1024)),
                "two different asset IDs shared an authorized fingerprint");
            NetworkTests.check(!originalHash.equals(fingerprint(resource,
                new PerceptionAssetLoader.Definition(id,PerceptionKind.SYMBOL,
                    alternate,closure,ProviderMode.TEST_ONLY),1024)),
                "render target swapped without changing fingerprint");
            NetworkTests.check(!originalHash.equals(fingerprint(resource,
                new PerceptionAssetLoader.Definition(id,PerceptionKind.APPARITION,
                    texture,closure,ProviderMode.TEST_ONLY),1024)),
                "effect kind swapped without changing fingerprint");
            NetworkTests.check(!originalHash.equals(fingerprint(resource,
                new PerceptionAssetLoader.Definition(id,PerceptionKind.SYMBOL,
                    texture,closure,ProviderMode.PRODUCTION),1024)),
                "TEST_ONLY and PRODUCTION reused one fingerprint");
            files.put(texture,"CHANGED_TEXTURE_BYTES".getBytes(StandardCharsets.UTF_8));
            NetworkTests.check(!originalHash.equals(fingerprint(resource,original,1024)),
                "changed resource bytes retained old fingerprint");
            NetworkTests.reject(()->new PerceptionAssetLoader.Definition(id,
                PerceptionKind.SYMBOL,missing,closure,ProviderMode.TEST_ONLY),
                "symbol texture omitted from authenticated dependency closure");
            NetworkTests.reject(()->new PerceptionAssetLoader.Definition(id,
                PerceptionKind.LOCAL_WEATHER,missing,closure,ProviderMode.TEST_ONLY),
                "local weather texture omitted from closure");
            NetworkTests.reject(()->new PerceptionAssetLoader.Definition(id,
                PerceptionKind.APPARITION,missing,closure,ProviderMode.TEST_ONLY),
                "apparition texture omitted from closure");
            // Sound identifiers refer to a registry event, not a direct pack
            // texture; its authored JSON/OGG closure can have different paths.
            NetworkTests.check(new PerceptionAssetLoader.Definition(id,
                PerceptionKind.SOUND,missing,closure,ProviderMode.TEST_ONLY)
                .dependencies().equals(closure),
                "registered sound dependencies were erroneously treated as textures");
            try {
                fingerprint(resource,original,1);
                throw new AssertionError("oversized asset bytes accepted");
            } catch(IOException expected){
                NetworkTests.check(true,"bounded asset stream");
            }
            NetworkTests.reject(()->{
                try {PerceptionAssetLoader.prepare(resource,List.of(original,original),
                    ProviderMode.TEST_ONLY,1024);}
                catch(IOException e){throw new IllegalStateException(e);}
            },"duplicate client asset ID allowed");
            NetworkTests.reject(()->{
                try {PerceptionAssetLoader.prepare(resource,List.of(original),
                    ProviderMode.PRODUCTION,1024);}
                catch(IOException e){throw new IllegalStateException(e);}
            },"TEST_ONLY resource promoted to production");
        } catch(IOException e) {
            throw new AssertionError("in-memory J06 asset verification failed",e);
        }
    }
}
