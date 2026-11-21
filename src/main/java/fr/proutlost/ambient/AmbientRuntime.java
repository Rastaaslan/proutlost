package fr.proutlost.ambient;

import net.minecraft.core.BlockPos;
import fr.proutlost.quest.runtime.SavedDataText;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.util.*;

/** Independent ambient execution. Does not import or ask Quest Core about progression. */
public final class AmbientRuntime {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.BooleanValue ENABLED;
    public static final ModConfigSpec.IntValue VISITS,MIN_SECONDS,COOLDOWN_SECONDS;
    static{var b=new ModConfigSpec.Builder();ENABLED=b.define("campRoutineEnabled",false);VISITS=b.defineInRange("samples",30,2,10000);MIN_SECONDS=b.defineInRange("minimumSeconds",60,1,86400);COOLDOWN_SECONDS=b.defineInRange("cooldownSeconds",300,1,86400);SPEC=b.build();}
    public static final class Data extends SavedData {
        public static final Factory<Data> FACTORY=new Factory<>(Data::new,Data::load);
        CampRoutineMemory memory=new CampRoutineMemory();
        Set<String> suppressed=new HashSet<>();
        static Data load(CompoundTag n,HolderLookup.Provider r){if(n.getInt("schema")!=1)throw new IllegalStateException("Ambient schema");var d=new Data();d.memory=CampRoutineMemory.restore(SavedDataText.get(n,"memory"));
            if(n.contains("suppressed"))d.suppressed.addAll(Arrays.asList(SavedDataText.get(n,"suppressed").split("\n")));return d;}
        public CompoundTag save(CompoundTag n,HolderLookup.Provider r){n.putInt("schema",1);SavedDataText.put(n,"memory",memory.save());SavedDataText.put(n,"suppressed",String.join("\n",suppressed));return n;}
    }
    private AmbientRuntime(){}
    public static void suppress(net.minecraft.server.MinecraftServer server,Set<String> locations){
        var data=server.overworld().getDataStorage().computeIfAbsent(Data.FACTORY,"proutlost_ambient_routines");
        data.suppressed.addAll(locations);data.setDirty();
    }
    public static void tick(ServerTickEvent.Post event){
        if(!ENABLED.get()||event.getServer().getTickCount()%20!=0)return;
        var server=event.getServer();var d=server.overworld().getDataStorage().computeIfAbsent(Data.FACTORY,"proutlost_ambient_routines");
        var gatherings=new HashMap<String,Map<UUID,Integer>>();
        for(var p:server.getPlayerList().getPlayers())if(!p.isSpectator()) {
            for(var pos:BlockPos.betweenClosed(p.blockPosition().offset(-4,-2,-4),p.blockPosition().offset(4,2,4))){
                if(!p.serverLevel().hasChunkAt(pos))continue;var state=p.serverLevel().getBlockState(pos);
                if(state.getBlock() instanceof CampfireBlock&&state.getValue(BlockStateProperties.LIT)){
                    String id=p.level().dimension().location()+":"+pos.asLong();int sector=(p.getX()>=pos.getX()?1:0)+(p.getZ()>=pos.getZ()?2:0);
                    gatherings.computeIfAbsent(id,k->new HashMap<>()).put(p.getUUID(),sector);break;
                }
            }
        }
        long now=System.currentTimeMillis();
        gatherings.entrySet().removeIf(e->d.suppressed.contains(e.getKey()));
        gatherings.forEach((location,players)->d.memory.sample(location,players,now,VISITS.get(),MIN_SECONDS.get()*1000L,COOLDOWN_SECONDS.get()*1000L).ifPresent(replay->{
            for(var id:replay.witnesses()){var player=server.getPlayerList().getPlayer(id);if(player!=null)player.playNotifySound(SoundEvents.WOOD_STEP,SoundSource.AMBIENT,.6f,.8f);}
            NeoForge.EVENT_BUS.post(new CampRoutineEvent(server,replay));
        }));
        d.setDirty();
    }
}
