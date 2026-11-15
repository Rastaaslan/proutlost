package fr.proutlost.worldprep.gametest;

import fr.proutlost.worldprep.persistence.WorldPrepSavedData;
import fr.proutlost.worldprep.runtime.BlockMutationRuntime;
import fr.proutlost.worldprep.runtime.ExistingChunkLoader;
import fr.proutlost.worldprep.runtime.WorldPrepRuntime;
import fr.proutlost.worldprep.world.ExistingChunkLease;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

/** Actual native disk IO in an isolated GameTest world. Never run against operator worlds. */
@GameTestHolder("proutlost")
@PrefixGameTestTemplate(false)
public final class WorldPrepDiskLoadingGameTests {
    @GameTest(batch="cb200_disk_loading",templateNamespace="minecraft",
            template="village/plains/houses/plains_small_house_1",timeoutTicks=1200)
    public static void unloadedDiskApplyUnloadReloadRollback(GameTestHelper helper) {
        var run=new DiskRun(helper);
        try{run.prepare();}
        catch(IOException|RuntimeException failure){run.close();throw new IllegalStateException("Disk fixture setup failed",failure);}
        helper.onEachTick(()->{
            if(run.done)return;
            try {
                if(++run.ticks>1000)throw new IllegalStateException("Timed out waiting for native unload/loading; stage="+run.stage);
                run.step();
            } catch(RuntimeException|IOException failure){
                run.close();
                helper.fail("Disk loading acceptance failed at stage "+run.stage+": "+failure);
            }
        });
    }

    private static final class DiskRun {
        // One fresh region, far outside template/spawn tickets and within the world border.
        private int x,z;
        private final GameTestHelper helper;
        private final ServerLevel level;
        private BlockPos ground;
        private final String area="proutlost:disk_fixture_"+UUID.randomUUID().toString().replace("-","");
        private ExistingChunkLease lease;
        private Path marker,fixtureRegion;
        private boolean markerOwned,done;
        private int ticks,stage,interruption;
        private final String[] interruptions={"pause","cancel","disable","marker","mode","missing-halo"};
        private String markerContents;
        private LevelChunk first,second;
        private UUID snapshot,rollback;
        private final boolean previousLoading=fr.proutlost.worldprep.WorldPrepConfig.LOAD_EXISTING_CHUNKS.get();
        private final boolean previousEnabled=fr.proutlost.worldprep.WorldPrepConfig.ENABLED.get();

        DiskRun(GameTestHelper helper){this.helper=helper;level=helper.getLevel();}
        void prepare()throws IOException{
            if(!(level.getServer() instanceof net.minecraft.gametest.framework.GameTestServer))
                throw new IOException("Dedicated GameTestServer required; normal servers refused");
            var root=level.getServer().getWorldPath(LevelResource.ROOT);
            marker=root.resolve("worldprep-candidate.json");
            if(Files.exists(marker))throw new IOException("Clean isolated GameTest world required; existing marker refused");
            var json=new com.google.gson.JsonObject();
            json.addProperty("schemaVersion",1);json.addProperty("offlineBaseline",true);
            json.addProperty("candidateDirectory",root.toRealPath().toString());
            Files.writeString(marker,json.toString(),StandardOpenOption.CREATE_NEW);markerOwned=true;
            var dir=DimensionType.getStorageFolder(level.dimension(),root).resolve("region");
            Files.createDirectories(dir);
            Path file=null;
            for(int index=0;index<64;index++){
                var proposed=dir.resolve("r."+(8192+index)+".8192.mca");
                if(!Files.exists(proposed)){
                    x=(8192+index)*32+16;z=8192*32+16;file=proposed;break;
                }
            }
            if(file==null)throw new IOException("No unused fixture region in bounded test range; refusing overwrite");
            fixtureRegion=file;
            ground=new BlockPos(x*16+8,80,z*16+8);
            // Assert no live holder before creating disk-only fixtures. This lookup requests no ticket.
            for(int dz=-1;dz<=1;dz++)for(int dx=-1;dx<=1;dx++)
                helper.assertTrue(level.getChunkSource().getChunk(x+dx,z+dz,ChunkStatus.EMPTY,false)==null,
                        "Fixture unexpectedly resident before disk serialization");
            try(var region=new RegionFile(new RegionStorageInfo("cb200_fixture",level.dimension(),"chunk"),file,dir,true)){
                for(int dz=-1;dz<=1;dz++)for(int dx=-1;dx<=1;dx++){
                    var pos=new ChunkPos(x+dx,z+dz);
                    // Standalone LevelChunk is serialized, never registered with the live level.
                    // Synthetic sparse full-chunk fixture; not a playable-world lighting assertion.
                    var chunk=new LevelChunk(level,pos);
                    chunk.setBlockState(new BlockPos(pos.getMinBlockX()+8,80,pos.getMinBlockZ()+8),Blocks.DIRT.defaultBlockState(),false);
                    chunk.setLightCorrect(true);
                    try(var out=region.getChunkDataOutputStream(pos)){NbtIo.write(ChunkSerializer.write(level,chunk),out);}
                }
                region.flush();
            }
            var data=WorldPrepRuntime.data(level);
            data.areas().put(area,new WorldPrepSavedData.Area(area,level.dimension().location().toString(),
                    ground.getX(),ground.getZ(),ground.getX(),ground.getZ()));
            data.changed();
            helper.assertTrue(absent(),"Target was loaded while creating disk fixture");
            lease=ExistingChunkLoader.openVerifiedLease(level,x,z);
        }
        void step()throws IOException{
            switch(stage){
                case 0 -> {
                    if(!lease.step())return;
                    first=level.getChunkSource().getChunkNow(x,z);
                    helper.assertTrue(first!=null&&level.getBlockState(ground).is(Blocks.DIRT),"Disk fixture not restored");
                    run(WorldPrepSavedData.Operation.PREVIEW_SOILS,null);
                    snapshot=run(WorldPrepSavedData.Operation.APPLY_SOILS,null);
                    helper.assertTrue(snapshot!=null&&level.getBlockState(ground).is(Blocks.GRASS_BLOCK),"Production SOILS did not apply");
                    level.save(null,true,false);
                    lease.close();lease=null;stage=1;
                }
                case 1 -> {
                    if(!absent())return; // Must lose EMPTY residency too, not just FULL accessibility.
                    var data=WorldPrepRuntime.data(level);
                    fr.proutlost.worldprep.WorldPrepConfig.ENABLED.set(true);
                    fr.proutlost.worldprep.WorldPrepConfig.LOAD_EXISTING_CHUNKS.set(false);
                    rollback=WorldPrepRuntime.enqueueRollback(level,WorldPrepSavedData.Operation.ROLLBACK_SOILS,area,snapshot);
                    for(int phase=0;phase<4 && data.jobs().get(rollback).state()!=WorldPrepSavedData.JobState.PAUSED;phase++)
                        CheckpointFixture.tick(level,256);
                    helper.assertTrue(data.jobs().get(rollback).state()==WorldPrepSavedData.JobState.PAUSED,
                            "OFF must retain missing-chunk pause");
                    helper.assertTrue(absent(),"OFF loaded an absent chunk");
                    // Simulated metadata restart; exact job and journal survive, not a new rollback.
                    var reloaded=WorldPrepSavedData.load(data.save(new net.minecraft.nbt.CompoundTag(),level.registryAccess()),level.registryAccess());
                    data.jobs().put(rollback,reloaded.jobs().get(rollback));
                    fr.proutlost.worldprep.WorldPrepConfig.LOAD_EXISTING_CHUNKS.set(true);
                    WorldPrepRuntime.tickLevel(level,256);
                    helper.assertTrue(absent()&&data.jobs().get(rollback).state()==WorldPrepSavedData.JobState.PAUSED,
                            "Enabling loader implicitly resumed paused rollback");
                    command("resume");
                    stage=6;
                }
                case 6 -> {
                    // One EMPTY read acquires a real native pin, before FULL promotion/mutation.
                    WorldPrepRuntime.tickLevel(level,256);
                    helper.assertTrue(level.getChunkSource().getChunk(x-1,z-1,ChunkStatus.EMPTY,false)!=null,
                            "Interruption did not acquire a native rollback pin");
                    var action=interruptions[interruption];
                    if(action.equals("pause")||action.equals("cancel")) {
                        command(action);
                        WorldPrepRuntime.tickLevel(level,256);
                    } else if(action.equals("disable")) {
                        fr.proutlost.worldprep.WorldPrepConfig.ENABLED.set(false);
                        WorldPrepRuntime.tick(new net.neoforged.neoforge.event.tick.ServerTickEvent.Post(()->true,level.getServer()));
                    } else if(action.equals("marker")) {
                        markerContents=Files.readString(marker);
                        Files.writeString(marker,"{}");
                        try { WorldPrepRuntime.tickLevel(level,256); }
                        finally { Files.writeString(marker,markerContents); }
                    } else if(action.equals("missing-halo")) {
                        var data=WorldPrepRuntime.data(level);var original=data.areas().get(area);
                        // Fixture-only relocation to an absent halo; failure must precede any write.
                        data.areas().put(area,new WorldPrepSavedData.Area(area,original.dimension(),
                                original.minX()+48,original.minZ(),original.maxX()+48,original.maxZ()));
                        try { WorldPrepRuntime.tickLevel(level,256); }
                        finally { data.areas().put(area,original); }
                        helper.assertTrue(level.getChunkSource().getChunk(x+2,z-1,ChunkStatus.EMPTY,false)==null,
                                "Missing halo created a chunk holder with terrain");
                    } else {
                        var mode=fr.proutlost.worldprep.WorldPrepConfig.MODE.get();
                        fr.proutlost.worldprep.WorldPrepConfig.MODE.set(fr.proutlost.worldprep.WorldPrepConfig.Mode.PRODUCTION);
                        try { WorldPrepRuntime.tickLevel(level,256); }
                        finally { fr.proutlost.worldprep.WorldPrepConfig.MODE.set(mode); }
                    }
                    var job=WorldPrepRuntime.data(level).jobs().get(rollback);
                    var expected=switch(action) {
                        case "pause" -> WorldPrepSavedData.JobState.PAUSED;
                        case "cancel" -> WorldPrepSavedData.JobState.CANCELLED;
                        case "disable" -> WorldPrepSavedData.JobState.RUNNING;
                        default -> WorldPrepSavedData.JobState.FAILED;
                    };
                    helper.assertTrue(job.state()==expected&&job.cursor()==0,"Interruption mutated or lost job: "+action);
                    stage=7;
                }
                case 7 -> {
                    // Do not call release here: only production cleanup may remove these pins.
                    if(!haloAbsent())return;
                    var data=WorldPrepRuntime.data(level);
                    helper.assertTrue(data.jobs().get(rollback).cursor()==0,"Mutation after interrupted loading");
                    var action=interruptions[interruption++];
                    if(action.equals("disable")) {
                        // Disabled runtime does not implicitly pause; explicitly pause before re-enable.
                        command("pause");
                        fr.proutlost.worldprep.WorldPrepConfig.ENABLED.set(true);
                        command("resume");
                    } else if(action.equals("pause")) command("resume");
                    else {
                        rollback=WorldPrepRuntime.enqueueRollback(level,WorldPrepSavedData.Operation.ROLLBACK_SOILS,area,snapshot);
                    }
                    stage=interruption<interruptions.length?6:2;
                }
                case 2 -> {
                    var data=WorldPrepRuntime.data(level);
                    WorldPrepRuntime.tickLevel(level,256);
                    var job=data.jobs().get(rollback);
                    helper.assertTrue(job.state()==WorldPrepSavedData.JobState.RUNNING||job.state()==WorldPrepSavedData.JobState.COMPLETED,
                            "Automatic rollback loader stopped: "+job.error());
                    if(job.state()!=WorldPrepSavedData.JobState.COMPLETED)return;
                    second=level.getChunkSource().getChunkNow(x,z);
                    helper.assertTrue(second!=null&&second!=first,"Reused resident chunk instead of native disk reload");
                    helper.assertTrue(job.cursor()==1&&snapshot.equals(job.snapshotId()),"Rollback lost exact journal or omitted persisted change");
                    helper.assertTrue(level.getBlockState(ground).is(Blocks.DIRT),"Exact-journal rollback failed after unload");
                    level.save(null,true,false);
                    // tickLevel completion must have released its automatic lease.
                    stage=3;
                }
                case 3 -> {
                    if(!absent())return;
                    lease=ExistingChunkLoader.openVerifiedLease(level,x,z);stage=4;
                }
                case 4 -> {
                    if(!lease.step())return;
                    helper.assertTrue(level.getChunkSource().getChunkNow(x,z)!=second,"Rollback verification reused resident chunk");
                    helper.assertTrue(level.getBlockState(ground).is(Blocks.DIRT),"Rollback did not survive second unload");
                    lease.close();lease=null;stage=5;
                }
                case 5 -> {
                    // Final cleanup must also allow native unload; leaking pins fails this test.
                    if(!absent())return;
                    // Read-only Anvil inventory: only the nine authored fixture chunks may exist.
                    try(var input=new java.io.RandomAccessFile(fixtureRegion.toFile(),"r")) {
                        for(int slot=0;slot<1024;slot++) {
                            int cx=slot%32,cz=slot/32;
                            boolean expected=Math.abs(cx-Math.floorMod(x,32))<=1
                                    &&Math.abs(cz-Math.floorMod(z,32))<=1;
                            helper.assertTrue((input.readInt()!=0)==expected,"Unexpected fixture region chunk at slot "+slot);
                        }
                    }
                    var evidence=new com.google.gson.JsonObject();
                    evidence.addProperty("schemaVersion",1);
                    evidence.addProperty("kind","SYNTHETIC_RUNTIME_DISK_CHECKPOINTS");
                    evidence.addProperty("chunkX",x);evidence.addProperty("chunkZ",z);
                    evidence.addProperty("area",area);evidence.addProperty("soilJournal",snapshot.toString());
                    evidence.addProperty("appliedBlocks",1);evidence.addProperty("rolledBackBlocks",1);
                    evidence.addProperty("observedUnloads",3);
                    evidence.addProperty("fixtureRegionChunkCount",9);
                    evidence.addProperty("automaticStandaloneRollbackLoading",true);
                    evidence.addProperty("offPausesWithoutLoading",true);
                    evidence.addProperty("explicitResumeAfterMetadataReload",true);
                    evidence.addProperty("runtimeInterruptionCases",interruption);
                    evidence.addProperty("processRestartTested",false);
                    evidence.addProperty("candidateWorldTested",false);
                    var directory=level.getServer().getWorldPath(LevelResource.ROOT).resolve("worldprep-v2/disk-tests");
                    Files.createDirectories(directory);
                    Files.writeString(directory.resolve(snapshot+".json"),evidence.toString(),StandardOpenOption.CREATE_NEW);
                    close();helper.succeed();
                }
                default -> throw new IllegalStateException("Unknown disk test stage");
            }
        }
        private UUID run(WorldPrepSavedData.Operation operation,UUID journal){
            var data=WorldPrepRuntime.data(level);var id=UUID.randomUUID();
            var job=new WorldPrepSavedData.Job(id,operation,area,WorldPrepSavedData.JobState.PAUSED,0,"",journal);
            data.jobs().put(id,job);data.changed();
            BlockMutationRuntime.prepare(level,data,id,operation,data.areas().get(area));
            for(int i=0;i<8&&data.jobs().get(id).state()!=WorldPrepSavedData.JobState.COMPLETED;i++)
                CheckpointFixture.process(level,data,id,data.jobs().get(id),256);
            helper.assertTrue(data.jobs().get(id).state()==WorldPrepSavedData.JobState.COMPLETED,"Incomplete "+operation);
            return data.jobs().get(id).snapshotId();
        }
        private void command(String action) {
            var dispatcher=new com.mojang.brigadier.CommandDispatcher<net.minecraft.commands.CommandSourceStack>();
            fr.proutlost.worldprep.WorldPrepCommands.registerTree(dispatcher);
            try {
                helper.assertTrue(dispatcher.execute("proutlost worldprep job "+rollback+" "+action,
                        level.getServer().createCommandSourceStack().withLevel(level).withPermission(4))==1,
                        "Job command failed: "+action);
            } catch(com.mojang.brigadier.exceptions.CommandSyntaxException e) { throw new IllegalStateException(e); }
        }
        private boolean haloAbsent() {
            for(int dz=-1;dz<=1;dz++)for(int dx=-1;dx<=1;dx++)
                if(level.getChunkSource().getChunk(x+dx,z+dz,ChunkStatus.EMPTY,false)!=null)return false;
            return true;
        }
        private boolean absent(){
            // EMPTY,false returns null only once the holder/cache can no longer supply the chunk.
            return level.getChunkSource().getChunkNow(x,z)==null
                    &&level.getChunkSource().getChunk(x,z,ChunkStatus.EMPTY,false)==null;
        }
        void close(){
            done=true;
            if(rollback!=null) {
                var data=WorldPrepRuntime.data(level);var job=data.jobs().get(rollback);
                if(job!=null&&(job.state()==WorldPrepSavedData.JobState.RUNNING||job.state()==WorldPrepSavedData.JobState.QUEUED
                        ||job.state()==WorldPrepSavedData.JobState.PAUSED)) {
                    data.jobs().put(rollback,job.withState(WorldPrepSavedData.JobState.CANCELLED));data.changed();
                }
            }
            try{if(lease!=null){lease.close();lease=null;}ExistingChunkLoader.release(level);}
            finally{
                fr.proutlost.worldprep.WorldPrepConfig.LOAD_EXISTING_CHUNKS.set(previousLoading);
                fr.proutlost.worldprep.WorldPrepConfig.ENABLED.set(previousEnabled);
                if(markerOwned)try{Files.delete(marker);markerOwned=false;}
                catch(IOException failure){throw new IllegalStateException("Fixture marker cleanup failed",failure);}
            }
        }
    }
    private WorldPrepDiskLoadingGameTests(){}
}
