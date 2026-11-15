package fr.proutlost.director.adapter.neoforge.server.effects.weathercore;

import fr.proutlost.director.adapter.neoforge.server.effects.WeatherCoreRegionalState;
import io.github.steamautomata.weathercore.api.*;
import java.util.*;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.api.distmarker.*;

/** Existing committed regional state survives a cold load with the same UUID.
 * Loading is projection of SavedData, never an invocation of the mutation path.
 * New region creation and retirement are package-private and only used by the
 * ServerEffectPort binding after its exact J04 permit has been consumed.
 */
public final class DirectorRegionalMistWeather extends Weather {
    private static final ThreadLocal<WeatherCoreRegionalState> CREATION=new ThreadLocal<>();
    private WeatherCoreRegionalState lease=WeatherCoreRegionalState.absent();
    private int clientCursor;
    private boolean renderArmed;
    public DirectorRegionalMistWeather(){
        var creating=CREATION.get();
        if(creating!=null){lease=creating;renderArmed=true;setSyncUuid(creating.weather());}
    }
    static <T> T creating(WeatherCoreRegionalState state,Supplier<T> creation){
        if(state.kind()!=WeatherCoreRegionalState.Kind.ACTIVE||CREATION.get()!=null)
            throw new IllegalStateException("regional weather creation context");
        CREATION.set(state);try{return creation.get();}finally{CREATION.remove();}
    }
    WeatherCoreRegionalState lease(){return lease;}
    boolean renderArmed(){return renderArmed;}
    void rearm(WeatherCoreRegionalState expected){
        if(!(level() instanceof ServerLevel l)||!l.getServer().isSameThread())throw new IllegalStateException("regional server actor required");
        if(renderArmed||!lease.equals(expected)||!uuid().equals(expected.weather()))throw new IllegalStateException("regional rearm identity/authority");
        expected.remainingAt(l.getGameTime());renderArmed=true;WeatherManager.get(l).setDirty();
    }
    void retire(WeatherCoreRegionalState expected){
        if(!(level() instanceof ServerLevel l)||!l.getServer().isSameThread())throw new IllegalStateException("regional server actor required");
        if(!lease.equals(expected)||!uuid().equals(expected.weather()))throw new IllegalStateException("regional owner changed before retirement");
        lease=lease.retired();renderArmed=false;setTimeLeft(0);WeatherManager.get(l).setDirty();
    }
    @Override public void onServerTick(ServerLevel level){
        super.onServerTick(level);
        if(lease.kind()==WeatherCoreRegionalState.Kind.ABSENT)return; // An unowned direct third-party summon has no renderer.
        if(lease.kind()==WeatherCoreRegionalState.Kind.RETIRED){setTimeLeft(0);return;}
        long remaining=lease.deadline()-level.getGameTime();
        if(remaining<=0)renderArmed=false;
        setTimeLeft(remaining<=0?0:Math.toIntExact(Math.min(remaining,72000)));
        WeatherManager.get(level).setDirty();
    }
    @Override public CompoundTag saveNbt(){
        var nbt=new CompoundTag();nbt.putInt("schema",1);nbt.putByteArray("sealed_lease",lease.bytes());nbt.putBoolean("render_armed",renderArmed);return nbt;
    }
    @Override public void loadNbt(CompoundTag nbt){
        if(nbt.getInt("schema")!=1||!nbt.contains("sealed_lease",Tag.TAG_BYTE_ARRAY))throw new NbtFormatException("regional weather data version/payload");
        try{lease=WeatherCoreRegionalState.read(nbt.getByteArray("sealed_lease"));}
        catch(RuntimeException invalid){throw new NbtFormatException("invalid regional weather owner/geometry/deadline");}
        if(lease.kind()!=WeatherCoreRegionalState.Kind.ABSENT&&!uuid().equals(lease.weather()))throw new NbtFormatException("regional weather UUID mismatch");
        if(lease.kind()!=WeatherCoreRegionalState.Kind.ABSENT&&(!(scope() instanceof Scope.Centered centered)
            ||Double.compare(centered.getCenter().x,lease.region().centerX())!=0||Double.compare(centered.getCenter().z,lease.region().centerZ())!=0
            ||Float.compare(centered.getRadius(),lease.region().radius())!=0))throw new NbtFormatException("regional weather scope mismatch");
        if(lease.kind()==WeatherCoreRegionalState.Kind.ACTIVE&&!level().isClientSide()
            &&lease.deadline()-level().getGameTime()>72000)throw new NbtFormatException("regional weather TTL exceeds bound");
        // A SavedData load never renews renderer authority. Only a fresh
        // exact REARM PREPARE/permit can rearm this existing native UUID.
        renderArmed=level().isClientSide()&&nbt.getBoolean("render_armed")&&lease.kind()==WeatherCoreRegionalState.Kind.ACTIVE;
        // No ClientLevel reference in common load code. Server is reloading an
        // existing applied world state; client is receiving the server's view.
        // Neither path creates another region nor grants a new CommitPermit.
    }
    @Override @OnlyIn(Dist.CLIENT) public WeatherRenderer<?> createRenderer(){return new WeatherRenderer<>(this);}
    @Override @OnlyIn(Dist.CLIENT) public void onClientTick(ClientLevel world){
        var player=Minecraft.getInstance().player;if(player==null)return;
        boolean live=renderArmed&&lease.kind()==WeatherCoreRegionalState.Kind.ACTIVE;
        if(renderer!=null){if(!live)renderer.fog=null;else if(renderer.fog==null)renderer.fog=new WeatherRenderer.Fog(.60f,.65f,.70f,8f,64f);}
        if(!live||isFadingOut||!world.hasChunkAt(player.blockPosition())||scope().intensityAt(player.position())<=0)return;
        // Avoid WeatherCore's generic recursive centered sampler outside a scope.
        if((++clientCursor&3)!=0)return;
        for(int n=0;n<2;n++){
            var pos=new net.minecraft.world.phys.Vec3(player.getX()+random.nextDouble()*12-6,player.getY()+.5+random.nextDouble()*2,player.getZ()+random.nextDouble()*12-6);
            if(scope().intensityAt(pos)>0)world.addParticle(ParticleTypes.CLOUD,pos.x,pos.y,pos.z,.015,0,0);
        }
    }
}
