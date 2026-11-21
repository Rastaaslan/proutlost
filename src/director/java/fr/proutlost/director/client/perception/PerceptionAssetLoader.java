package fr.proutlost.director.client.perception;

import fr.proutlost.director.api.model.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import java.io.*;
import java.security.*;
import java.util.*;

/** Resource-reload preparation only: call off client/server tick threads. Hash the actual dependency
 * closure declared by the authored asset, sorted by path. Missing/oversized assets are rejected,
 * never mapped to a test texture. Apply the resulting immutable registry on the client thread. */
public final class PerceptionAssetLoader {
    private PerceptionAssetLoader(){}
    public record Definition(ResourceId id,PerceptionKind kind,ResourceLocation renderResource,Set<ResourceLocation> dependencies,ProviderMode mode){
        public Definition{
            Objects.requireNonNull(id);Objects.requireNonNull(kind);
            Objects.requireNonNull(renderResource);Objects.requireNonNull(mode);
            dependencies=Set.copyOf(dependencies);
            Checks.require(!dependencies.isEmpty()&&dependencies.size()<=128,"asset dependencies");
            // These effects directly render the ResourceManager path. Every
            // byte of that path must be in the authenticated closure, not just
            // another resource with a conveniently matching fingerprint.
            if(kind==PerceptionKind.SYMBOL||kind==PerceptionKind.LOCAL_WEATHER
                    ||kind==PerceptionKind.APPARITION)
                Checks.require(dependencies.contains(renderResource),
                    "rendered texture absent from verified closure");
        }
    }
    public static Map<ResourceId,MinecraftPerceptionEffects.Asset> prepare(ResourceManager resources,List<Definition> definitions,ProviderMode mode,int maxAssetBytes)throws IOException{
        Checks.require(definitions.size()<=128&&maxAssetBytes>0&&maxAssetBytes<=16777216,"asset bounds");
        Map<ResourceId,MinecraftPerceptionEffects.Asset> loaded=new HashMap<>();
        for(var d:definitions){
            Checks.require(mode!=ProviderMode.PRODUCTION||d.mode()==ProviderMode.PRODUCTION,"TEST_ONLY asset in production");
            try{
                var digest=MessageDigest.getInstance("SHA-256");long total=0;
                // Domain separation + bound metadata prevent a different ID,
                // kind, render target or provider mode from reusing the same
                // asset fingerprint merely because its dependencies coincide.
                field(digest,"proutlost-director:perception-asset-manifest:v1");
                field(digest,d.id().value());
                field(digest,d.kind().name());
                field(digest,d.renderResource().toString());
                field(digest,d.mode().name());
                for(var path:d.dependencies().stream().sorted(Comparator.comparing(Object::toString)).toList()){
                    field(digest,path.toString());
                    var part=MessageDigest.getInstance("SHA-256");
                    try(var in=resources.open(path)){byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1){total+=n;if(total>maxAssetBytes)throw new IOException("asset byte limit: "+d.id());part.update(buffer,0,n);}}
                    digest.update(part.digest());
                }
                Checks.require(loaded.put(d.id(),new MinecraftPerceptionEffects.Asset(d.kind(),d.renderResource(),new Fingerprint(HexFormat.of().formatHex(digest.digest()))))==null,"duplicate asset");
            }catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
        }
        return Map.copyOf(loaded);
    }
    /** Prepare the effective bytes of the shipped manifest on a reload worker.
     * A resource pack may remove approval but can never invent a server-authorized hash. */
    public static Map<ResourceId,MinecraftPerceptionEffects.Asset> prepare(ResourceManager resources,
            Map<ResourceId,fr.proutlost.director.adapter.neoforge.network.DirectorGameplayAssets.Spec> manifest,
            int maximumAssetBytes) {
        Checks.require(manifest.size()<=128&&maximumAssetBytes>0&&maximumAssetBytes<=16777216,"asset bounds");
        Map<ResourceId,MinecraftPerceptionEffects.Asset> verified=new HashMap<>();
        for(var entry:manifest.entrySet()) {
            var spec=entry.getValue();boolean matches=true;long total=0;
            for(var dependency:spec.dependencies().entrySet()) {
                var resource=ResourceLocation.parse(dependency.getKey());
                try {
                    if(dependency.getKey().equals("minecraft:sounds.json")) {
                        boolean base=false;
                        for(var layer:resources.getResourceStack(resource)) {
                            byte[] bytes;
                            try(var input=layer.open()){bytes=readBounded(input,maximumAssetBytes-total);}
                            total+=bytes.length;
                            if(BlobContent.hash(bytes).equals(dependency.getValue()))base=true;
                            else {
                                var json=com.google.gson.JsonParser.parseString(new String(bytes,java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
                                var key=spec.resource().substring(spec.resource().indexOf(':')+1);
                                if(json.has(key)){matches=false;break;}
                            }
                        }
                        if(!base)matches=false;
                    } else try(var input=resources.open(resource)) {
                        byte[] bytes=readBounded(input,maximumAssetBytes-total);total+=bytes.length;
                        if(!BlobContent.hash(bytes).equals(dependency.getValue()))matches=false;
                    }
                    if(!matches)break;
                }catch(IOException|RuntimeException absent){matches=false;break;}
            }
            if(matches)verified.put(entry.getKey(),new MinecraftPerceptionEffects.Asset(spec.kind(),ResourceLocation.parse(spec.resource()),spec.hash()));
            else System.out.println("DIRECTOR_GAMEPLAY_ASSET_UNAVAILABLE id="+entry.getKey().value());
        }
        return Map.copyOf(verified);
    }
    private static byte[] readBounded(InputStream input,long maximum)throws IOException {
        var output=new ByteArrayOutputStream();byte[] buffer=new byte[8192];long total=0;int count;
        while((count=input.read(buffer))!=-1){total+=count;if(total>maximum)throw new IOException("asset byte limit");output.write(buffer,0,count);}
        return output.toByteArray();
    }
    private static void field(MessageDigest digest,String value){
        digest.update(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        digest.update((byte)0);
    }
}
