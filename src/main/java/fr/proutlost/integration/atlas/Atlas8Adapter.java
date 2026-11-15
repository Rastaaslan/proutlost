package fr.proutlost.integration.atlas;

import java.lang.reflect.*;
import java.lang.invoke.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.fml.ModList;

/** Exact published 8.0.1/12.0.18 integration. Only public members are reflected. */
final class Atlas8Adapter {
    static final Map<String,String> HASHES=Map.of(
        "antiqueatlas","b5b406b7b48fbdd68e66160a24145fc399f21e39f9f391120bb172e8180e45fc",
        "unionlib","c17d45e053844b1a5f6fa847f2658c0476a0770d476d3ffd3808ed877f8b6a96");
    private final Object item,handler;
    private final Method atlasId,dataForStack,dimensions,markers,id,type,label,world,x,z,ahead,global,create;
    private final MethodHandle sendToPlayer;
    private final Constructor<?> packet;
    private Atlas8Adapter() throws ReflectiveOperationException {
        var loader=Atlas8Adapter.class.getClassLoader();
        var api=Class.forName("hunternif.mc.api.AtlasAPI",false,loader);
        var atlasItem=Class.forName("hunternif.mc.impl.atlas.item.AtlasItem",false,loader);
        var atlas=Class.forName("hunternif.mc.impl.atlas.AntiqueAtlas",false,loader);
        var data=Class.forName("hunternif.mc.impl.atlas.marker.MarkersData",false,loader);
        var marker=Class.forName("hunternif.mc.impl.atlas.marker.Marker",false,loader);
        var payload=Class.forName("hunternif.mc.impl.atlas.network.packet.s2c.play.PutMarkersS2CPacket",false,loader);
        item=api.getMethod("getAtlasItem").invoke(null);handler=atlas.getField("markersData").get(null);
        atlasId=atlasItem.getMethod("getAtlasID",ItemStack.class);
        dataForStack=handler.getClass().getMethod("getMarkersData",ItemStack.class,Level.class);
        dimensions=data.getMethod("getVisitedDimensions");markers=data.getMethod("getMarkersInWorld",ResourceKey.class);
        id=marker.getMethod("getId");type=marker.getMethod("getType");label=marker.getMethod("getLabel");
        world=marker.getMethod("getWorld");x=marker.getMethod("getX");z=marker.getMethod("getZ");
        ahead=marker.getMethod("isVisibleAhead");global=marker.getMethod("isGlobal");
        create=data.getMethod("createAndSaveMarker",ResourceLocation.class,ResourceKey.class,int.class,int.class,boolean.class,Component.class);
        packet=payload.getConstructor(int.class,ResourceKey.class,Collection.class);
        // Deliberately never resolve/send(ServerLevel) or send(MinecraftServer): both broadcast.
        // Exact JVM member resolution avoids enumerating UnionLib client-only signatures.
        sendToPlayer=MethodHandles.publicLookup().findVirtual(payload,"send",
            MethodType.methodType(void.class,ServerPlayer.class));
    }
    static CompletableFuture<Atlas8Adapter> verifyAsync(){
        // Bounded artifact reads off the server actor, no network or filesystem writes.
        var io=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"proutlost-atlas-pin-check");t.setDaemon(true);return t;});
        var result=CompletableFuture.supplyAsync(()->{
            try{
                for(var entry:HASHES.entrySet()){
                    var info=ModList.get().getModContainerById(entry.getKey()).orElseThrow().getModInfo();
                    var expectedVersion=entry.getKey().equals("antiqueatlas")?"8.0.1":"12.0.18";
                    AtlasShareGate.require(info.getVersion().toString().equals(expectedVersion),"Version Atlas/UnionLib non qualifiee.");
                    Path path=ModList.get().getModFileById(entry.getKey()).getFile().getFilePath();
                    AtlasShareGate.require(Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS),"Artefact Atlas/UnionLib non regulier.");
                    var digest=MessageDigest.getInstance("SHA-256");
                    try(var stream=Files.newInputStream(path)){
                        byte[] buffer=new byte[16384];long size=0;int n;
                        while((n=stream.read(buffer))!=-1){size+=n;AtlasShareGate.require(size<=2*1024*1024,"Artefact Atlas/UnionLib hors limite.");digest.update(buffer,0,n);}
                    }
                    AtlasShareGate.require(HexFormat.of().formatHex(digest.digest()).equals(entry.getValue()),"Empreinte Atlas/UnionLib non qualifiee.");
                }
                return new Atlas8Adapter();
            }catch(Exception e){throw new CompletionException(e);}
        },io);
        result.whenComplete((v,e)->{
            io.shutdown();
            if(e==null)System.out.println("PROUTLOST_ATLAS_SERVER_API_LINKAGE_READY atlas=8.0.1 unionlib=12.0.18 target=send(ServerPlayer) marker_writes=0 client=NOT_RUN");
            else{
                System.err.println("PROUTLOST_ATLAS_SERVER_API_LINKAGE_FAILED root="+rootCause(e));
                e.printStackTrace(System.err);
            }
        });return result;
    }
    static Throwable rootCause(Throwable failure){
        var seen=Collections.newSetFromMap(new IdentityHashMap<Throwable,Boolean>());
        while(failure.getCause()!=null&&seen.add(failure))failure=failure.getCause();
        return failure;
    }
    private static Object invoke(Method method,Object receiver,Object... args){
        try{return method.invoke(receiver,args);}catch(ReflectiveOperationException e){throw new IllegalStateException("Atlas public API unavailable; do not retry an uncertain write",e);}
    }
    int heldAtlas(ServerPlayer player){
        var stack=player.getMainHandItem();AtlasShareGate.require(stack.is((Item)item),"Tenir un atlas initialise dans la main principale.");
        int value=(Integer)invoke(atlasId,null,stack);AtlasShareGate.require(value>=0,"Ouvrir cet atlas une fois avant le partage.");return value;
    }
    private Object data(ServerPlayer player,int expectedAtlas){
        AtlasShareGate.require(heldAtlas(player)==expectedAtlas,"Atlas en main modifie.");
        return Objects.requireNonNull(invoke(dataForStack,handler,player.getMainHandItem(),player.serverLevel()));
    }
    private AtlasShareGate.Marker snapshot(Object marker){
        var key=(ResourceKey<?>)invoke(world,marker);
        return new AtlasShareGate.Marker((Integer)invoke(id,marker),invoke(type,marker).toString(),
            ((Component)invoke(label,marker)).getString(),key.location().toString(),(Integer)invoke(x,marker),
            (Integer)invoke(z,marker),(Boolean)invoke(ahead,marker),(Boolean)invoke(global,marker));
    }
    List<AtlasShareGate.Marker> all(ServerPlayer player,int atlas){
        Object data=data(player,atlas);var dims=(Set<?>)invoke(dimensions,data);
        AtlasShareGate.require(dims.size()<=64,"Atlas hors limites de dimensions.");
        var result=new ArrayList<AtlasShareGate.Marker>();
        for(var dimension:dims){
            var rows=(Collection<?>)invoke(markers,data,dimension);
            AtlasShareGate.require(rows.size()<=AtlasShareGate.MAX_MARKERS-result.size(),"Atlas hors limite de 32 marqueurs.");
            for(var row:rows)result.add(snapshot(row));
        }
        return List.copyOf(result);
    }
    AtlasShareGate.Marker selected(ServerPlayer player,int atlas,int marker){
        return all(player,atlas).stream().filter(m->m.id()==marker&&!m.global()
            &&m.dimension().equals(player.serverLevel().dimension().location().toString())).findFirst()
            .orElseThrow(()->new IllegalArgumentException("Marqueur personnel absent dans cet atlas et cette dimension."));
    }
    Object appendOnly(ServerPlayer player,int atlas,AtlasShareGate.Marker marker){
        var server=Objects.requireNonNull(player.getServer());AtlasShareGate.require(server.isSameThread(),"Atlas server actor required");
        AtlasShareGate.require(marker.dimension().equals(player.serverLevel().dimension().location().toString()),"Dimension modifiee.");
        AtlasShareGate.require(all(player,atlas).size()<AtlasShareGate.MAX_MARKERS,"Atlas plein.");
        // Canonical Atlas SavedData method marks itself dirty. No mirror store or world/chunk API.
        return invoke(create,data(player,atlas),ResourceLocation.parse(marker.type()),player.serverLevel().dimension(),
            marker.x(),marker.z(),marker.visibleAhead(),Component.literal(marker.label()));
    }
    void sendOnly(ServerPlayer recipient,int atlas,Object created){
        AtlasShareGate.require(heldAtlas(recipient)==atlas,"Atlas destinataire modifie apres creation.");
        try{
            Object message=packet.newInstance(atlas,recipient.serverLevel().dimension(),List.of(created));
            sendToPlayer.invoke(message,recipient);
        }catch(VirtualMachineError|ThreadDeath fatal){throw fatal;}
        catch(Throwable e){throw new IllegalStateException("Marker saved; targeted packet outcome uncertain; do not retry",e);}
    }
}
