package fr.proutlost.director.adapter.neoforge.server.effects.weathercore;

import fr.proutlost.director.adapter.neoforge.server.effects.WeatherCoreLeaseState;
import fr.proutlost.director.adapter.neoforge.server.effects.WeatherCoreExpiryPolicy;
import io.github.steamautomata.weathercore.WeatherCore;
import io.github.steamautomata.weathercore.api.*;
import io.github.steamautomata.weathercore.api.events.RandomWeatherSelectEvent;
import java.util.*;
import java.util.function.Supplier;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.*;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.event.level.LevelEvent;

/** Technical, invisible, finite provider cursor. The committed global remains
 * a real Core vanilla weather. This marker is inserted BEFORE that global and
 * persists its exact UUID, owner, actual apply clock and bounded stop policy.
 * Expiry stops only that exact UUID; a foreign global is never reset/restored.
 * No new impact, re-spawn, journal or command is executed by this cursor.
 */
public final class WeatherCoreGlobalLeaseMarker extends Weather {
    private static final long RECEIPT_TTL=72000;
    private record Creation(WeatherCoreLeaseState.Lease lease,long tick){}
    private static final ThreadLocal<Creation> CREATING=new ThreadLocal<>();
    private static final Map<ServerLevel,WeatherCoreGlobalLeaseMarker> EXPIRING=new IdentityHashMap<>();
    private WeatherCoreLeaseState.Lease lease;
    private long born=-1,end=-1,clearTick=-1;
    private UUID clearedGlobal;
    private boolean expired,retired,clearPending;

    public WeatherCoreGlobalLeaseMarker(){
        var c=CREATING.get();
        if(c!=null){lease=c.lease();born=c.tick();end=Math.addExact(born,lease.duration());setSyncUuid(markerUuid(lease.operation()));}
    }
    static UUID markerUuid(UUID operation){return UUID.nameUUIDFromBytes(("proutlost-weathercore-marker-v1/"+operation).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    static <T>T duringCreation(WeatherCoreLeaseState.Lease lease,long tick,Supplier<T> create){
        if(CREATING.get()!=null)throw new IllegalStateException("nested Core lease creation");
        CREATING.set(new Creation(lease,tick));try{return create.get();}finally{CREATING.remove();}
    }
    static List<WeatherCoreGlobalLeaseMarker> markers(ServerLevel level){
        return WeatherManager.get(level).weathersAt(Vec3.ZERO).filter(w->w instanceof WeatherCoreGlobalLeaseMarker)
            .map(w->(WeatherCoreGlobalLeaseMarker)w).filter(m->m.lease!=null&&!m.retired).toList();
    }
    static Optional<WeatherCoreGlobalLeaseMarker> active(ServerLevel level){
        var live=markers(level).stream().filter(m->!m.expired).toList();
        if(live.size()>1)throw new IllegalStateException("ambiguous Core global owners");return live.stream().findFirst();
    }
    WeatherCoreLeaseState.Lease lease(){return Objects.requireNonNull(lease);}
    boolean expired(){return expired;}
    long end(){return end;}
    void retire(ServerLevel level){actor(level);retired=true;setTimeLeft(0);WeatherManager.get(level).setDirty();}
    private static void actor(ServerLevel level){if(!level.getServer().isSameThread())throw new IllegalStateException("Core lease actor");}
    private static List<Weather> globals(ServerLevel level){return WeatherManager.get(level).weathersAt(Vec3.ZERO).filter(w->w.scope() instanceof Scope.Global).toList();}
    private static boolean clearFlags(ServerLevel level){var data=(ServerLevelData)level.getLevelData();return !data.isRaining()&&!data.isThundering();}
    private boolean matches(Weather weather){return lease!=null&&weather.uuid().equals(lease.global())
        &&Objects.requireNonNull(WeatherCore.WEATHER_REGISTRY.getKey(weather.type())).toString().equals(lease.type());}
    boolean liveMatches(ServerLevel level,List<Weather> globals){
        if(lease==null||retired||expired||globals.size()!=1||!matches(globals.getFirst()))return false;
        var current=globals.getFirst();
        return WeatherCoreExpiryPolicy.decide(lease.global(),current.uuid(),lease.type(),
            Objects.requireNonNull(WeatherCore.WEATHER_REGISTRY.getKey(current.type())).toString(),
            born,lease.duration(),level.getGameTime(),current.getTimeLeft())!=WeatherCoreExpiryPolicy.Decision.YIELD;
    }
    @Override public void onServerTick(ServerLevel level){
        super.onServerTick(level);actor(level);
        if(lease==null||retired)return;
        long now=level.getGameTime();
        if(now<born){retired=true;setTimeLeft(0);return;}
        if(expired){setTimeLeft(Math.toIntExact(Math.max(0,Math.min(Integer.MAX_VALUE,Math.addExact(end,RECEIPT_TTL)-now))));return;}
        var current=globals(level);
        if(current.size()!=1||!matches(current.getFirst())){retire(level);return;} // Actual external override: relinquish.
        if(!liveMatches(level,current)){retire(level);return;}
        if(now>=end){
            // Finite energy expiry of the exact committed weather. No baseline
            // is fabricated. The normal inverse remains a separately permitted
            // J04 write. Core's otherwise-random replacement is fenced below.
            expired=true;clearPending=true;EXPIRING.put(level,this);
            current.getFirst().setTimeLeft(0);setTimeLeft(Math.toIntExact(RECEIPT_TTL));WeatherManager.get(level).setDirty();return;
        }
        // Prevent Core from reaching zero one phase before the fixed end clock.
        // This maintains only the existing UUID, under the committed finite end;
        // it neither changes weather flags nor extends the lease's deadline.
        current.getFirst().setTimeLeft(Math.toIntExact(Math.max(2,end-now+1)));
        setTimeLeft(Math.toIntExact(end-now+1));
    }
    boolean hasClearReceipt(ServerLevel level,WeatherCoreLeaseState.Lease expected){
        actor(level);
        if(!expired||retired||clearPending||clearedGlobal==null||!lease().equals(expected)
            ||clearTick<end||level.getGameTime()<clearTick)return false;
        var current=globals(level);
        return current.size()==1&&current.getFirst().uuid().equals(clearedGlobal)
            &&Objects.requireNonNull(WeatherCore.WEATHER_REGISTRY.getKey(current.getFirst().type())).toString().equals("minecraft:clear")
            &&clearFlags(level);
    }
    private static void select(RandomWeatherSelectEvent event){
        var level=event.getLevel();var marker=EXPIRING.remove(level);if(marker==null)return;
        // The marker and exact owned global were observed in THIS actor tick.
        // Cancellation is Core's documented finite CLEAR fallback, and affects
        // only this one expiry, never a later ambient weather choice.
        if(marker.expired&&!marker.retired&&marker.clearPending&&level.getGameTime()>=marker.end
            &&globals(level).isEmpty())event.setCanceled(true);
        else marker.clearPending=false;
    }
    private static void after(LevelTickEvent.Post event){
        if(!(event.getLevel() instanceof ServerLevel level))return;
        EXPIRING.remove(level);
        for(var marker:markers(level))if(marker.expired&&marker.clearPending){
            var current=globals(level);
            if(current.size()==1&&Objects.requireNonNull(WeatherCore.WEATHER_REGISTRY.getKey(current.getFirst().type())).toString().equals("minecraft:clear")
                &&clearFlags(level)){
                marker.clearedGlobal=current.getFirst().uuid();marker.clearTick=level.getGameTime();marker.clearPending=false;WeatherManager.get(level).setDirty();
            }
        }
    }
    static void register(){
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST,true,WeatherCoreGlobalLeaseMarker::select);
        NeoForge.EVENT_BUS.addListener(WeatherCoreGlobalLeaseMarker::after);
        NeoForge.EVENT_BUS.addListener((LevelEvent.Unload event)->{if(event.getLevel() instanceof ServerLevel level)EXPIRING.remove(level);});
    }
    @Override public CompoundTag saveNbt(){
        var n=new CompoundTag();n.putInt("schema",1);n.putBoolean("retired",retired);
        if(lease!=null){
            n.putUUID("event",lease.event());n.putUUID("operation",lease.operation());n.putUUID("owner",lease.owner());n.putLong("epoch",lease.epoch());n.putUUID("mutation",lease.mutation());
            n.putString("type",lease.type());n.putUUID("global",lease.global());n.putLong("duration",lease.duration());n.putLong("born",born);n.putLong("end",end);
            n.putBoolean("expired",expired);n.putBoolean("clear_pending",clearPending);
            if(clearedGlobal!=null){n.putUUID("cleared_global",clearedGlobal);n.putLong("clear_tick",clearTick);}
        }return n;
    }
    @Override public void loadNbt(CompoundTag n){
        // Core catches NbtFormatException and silently drops only that weather.
        // Dropping the owner cursor while retaining its global would remove the
        // deterministic expiry fence. Refuse the load instead of continuing it.
        if(n.getInt("schema")!=1)throw new IllegalStateException("Core lease marker version");
        retired=n.getBoolean("retired");
        if(!n.contains("operation")){if(!level().isClientSide())throw new IllegalStateException("unowned Core lease marker on disk");return;}
        for(var key:List.of("event","operation","owner","mutation","global"))if(!n.hasUUID(key))throw new IllegalStateException("Core lease marker UUID");
        for(var key:List.of("epoch","duration","born","end"))if(!n.contains(key,Tag.TAG_LONG))throw new IllegalStateException("Core lease marker clock");
        try{
            lease=new WeatherCoreLeaseState.Lease(n.getUUID("event"),n.getUUID("operation"),n.getUUID("owner"),n.getLong("epoch"),n.getUUID("mutation"),n.getString("type"),n.getUUID("global"),n.getLong("duration"));
            born=n.getLong("born");end=n.getLong("end");
            if(born<0||end!=Math.addExact(born,lease.duration())||!uuid().equals(markerUuid(lease.operation())))throw new IllegalArgumentException("Core marker immutable identity/clock");
            expired=n.getBoolean("expired");clearPending=n.getBoolean("clear_pending");
            if(n.hasUUID("cleared_global")){clearedGlobal=n.getUUID("cleared_global");clearTick=n.getLong("clear_tick");if(!expired||clearTick<end)throw new IllegalArgumentException("invalid Core expiry receipt");}
        }catch(RuntimeException bad){throw new IllegalStateException("invalid Core marker; refusing partial owner/global load",bad);}
    }
    @Override @OnlyIn(Dist.CLIENT) public void onClientTick(ClientLevel ignored){} // Invisible technical cursor; no generic sampler.
    @Override @OnlyIn(Dist.CLIENT) public WeatherRenderer<?> createRenderer(){return new WeatherRenderer<>(this);}
}
