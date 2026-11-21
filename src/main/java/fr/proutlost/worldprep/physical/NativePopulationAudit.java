package fr.proutlost.worldprep.physical;

import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

/** Audits living population separately from issued receipts, after an operator-controlled save.
 * Every issued animal must currently be located and persisted for qualification to pass.
 * Missing/dead animals are reported, never restored. One entity region read in flight.
 */
public final class NativePopulationAudit {
    public record Receipt(int version,UUID operation,String planSha256,int issued,int living,int diskVerified,String status) {}
    private static final ExecutorService IO=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"worldprep-population-audit");t.setDaemon(true);t.setPriority(Thread.MIN_PRIORITY);return t;});
    private final NativeFaunaPass.Plan plan;
    private final PhysicalStore store;
    private int cursor;
    private CompletableFuture<CompoundTag> pending;
    public NativePopulationAudit(NativeFaunaPass.Plan plan,PhysicalStore store){this.plan=plan;this.store=store;}
    public NativeFaunaPass.Plan plan(){return plan;}
    public boolean done(){return cursor==plan.animals().size();}
    public boolean step(ServerLevel level)throws IOException {
        NativePhysicalRuntime.requireThread(level);
        if(done())return true;
        var animal=plan.animals().get(cursor);
        if(!store.exists("issued-"+animal.uuid()))throw new IOException("Initial population receipt absent");
        var live=level.getEntity(animal.uuid());
        if(live==null||!live.isAlive())throw new IOException("Issued animal not currently living/loaded: "+animal.uuid());
        if(!live.getPersistentData().getString("proutlost_initial_operation").equals(plan.operation().toString()))throw new IOException("Live UUID ownership conflict");
        int x=live.blockPosition().getX()>>4,z=live.blockPosition().getZ()>>4;
        if(pending==null) {
            var region=DimensionType.getStorageFolder(level.dimension(),level.getServer().getWorldPath(LevelResource.ROOT)).resolve("entities");
            pending=CompletableFuture.supplyAsync(()->{try{return ReadOnlyChunkNbt.entities(region,x,z);}catch(IOException e){throw new CompletionException(e);}},IO);return false;
        }
        if(!pending.isDone())return false;
        CompoundTag nbt;try{nbt=pending.join();}catch(CompletionException e){throw new IOException("Entity disk readback failed",e.getCause());}
        var entities=nbt.getList("Entities",Tag.TAG_COMPOUND);if(entities.size()>4096)throw new IOException("Entity chunk limit");
        int matches=0;
        for(int i=0;i<entities.size();i++) {
            var entity=entities.getCompound(i);
            if(entity.hasUUID("UUID")&&entity.getUUID("UUID").equals(animal.uuid())) {
                matches++;
                if(!entity.getString("id").equals(plan.rule().species())
                    ||!entity.getCompound("NeoForgeData").getString("proutlost_initial_operation").equals(plan.operation().toString())
                    ||(plan.rule().persistent()&&!entity.getBoolean("PersistenceRequired")))throw new IOException("Persisted entity identity/policy mismatch");
            }
        }
        if(matches!=1)throw new IOException("Persisted entity absent/duplicated");
        cursor++;pending=null;
        if(done())store.write("population-disk-verified",new Receipt(1,plan.operation(),PhysicalStore.sha(store.readText("plan")),plan.animals().size(),cursor,cursor,"POPULATION_SAVE_READBACK_VERIFIED"));
        return done();
    }
}
