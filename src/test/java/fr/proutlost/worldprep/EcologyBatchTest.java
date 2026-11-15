package fr.proutlost.worldprep;

import fr.proutlost.worldprep.ecology.ControlledOak;
import fr.proutlost.worldprep.ecology.EcologyTiles;
import fr.proutlost.worldprep.persistence.WorldPrepSavedData;
import fr.proutlost.worldprep.recovery.ReferenceInventory;
import fr.proutlost.worldprep.runtime.EcologyBatchRuntime;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class EcologyBatchTest {
    @TempDir Path temp;
    @Test void islandTilesCoverBoundsExactlyWithoutOverlap() {
        var tiles=new EcologyTiles(4496,-1666,5236,-943);
        assertEquals(2209,tiles.count());
        var visited=new HashSet<Long>();
        for(long i=0;i<tiles.count();i++) {
            var tile=tiles.at(i);
            assertTrue((tile.maxX()-tile.minX()+1)*(tile.maxZ()-tile.minZ()+1)<=256);
            assertEquals(tile.minX()>>4,tile.maxX()>>4);
            assertEquals(tile.minZ()>>4,tile.maxZ()>>4);
            for(int z=tile.minZ();z<=tile.maxZ();z++)for(int x=tile.minX();x<=tile.maxX();x++)
                assertTrue(visited.add(((long)x<<32)^(z&0xffffffffL)));
        }
        assertEquals(741*724,visited.size());
        assertThrows(IndexOutOfBoundsException.class,()->tiles.at(tiles.count()));
        assertThrows(IllegalArgumentException.class,()->new EcologyTiles(10,0,9,0));
    }
    @Test void treeFootprintsAreUniqueBoundedAndIndependentOfOrder() {
        var coords=new HashSet<String>();
        for(var voxel:ControlledOak.SHAPE)assertTrue(coords.add(voxel.x()+","+voxel.y()+","+voxel.z()));
        assertTrue(ControlledOak.SHAPE.size()>30&&ControlledOak.SHAPE.size()<256);
        for(int cx=-100;cx<100;cx++)for(int cz=-3;cz<=3;cz++) {
            int x=ControlledOak.rootX(101,cx,cz),z=ControlledOak.rootZ(101,cx,cz);
            for(var voxel:ControlledOak.SHAPE) {
                assertEquals(cx,(x+voxel.x())>>4);
                assertEquals(cz,(z+voxel.z())>>4);
            }
            assertEquals(x,ControlledOak.rootX(101,cx,cz));
        }
    }
    @Test void safePassOrderHasTreesBeforeFloraAndDiscoveryBeforeMutation() {
        assertEquals("STRUCTURES",EcologyBatchRuntime.STAGES.getFirst());
        assertTrue(EcologyBatchRuntime.STAGES.indexOf("APPLY_TREES")<EcologyBatchRuntime.STAGES.indexOf("PREVIEW_FLORA"));
        assertEquals("HABITATS",EcologyBatchRuntime.STAGES.getLast());
    }
    @Test void restartPausesParentAndChildWithoutLosingCursorOrOwnership() {
        var data=new WorldPrepSavedData();var parent=UUID.randomUUID();var child=UUID.randomUUID();
        data.jobs().put(parent,new WorldPrepSavedData.Job(parent,WorldPrepSavedData.Operation.BATCH_ECOLOGY,"proutlost:area",WorldPrepSavedData.JobState.RUNNING,124,"",child));
        data.jobs().put(child,new WorldPrepSavedData.Job(child,WorldPrepSavedData.Operation.APPLY_TREES,"proutlost:tile",WorldPrepSavedData.JobState.PAUSED,8,"",UUID.randomUUID()));
        var loaded=WorldPrepSavedData.load(data.save(new CompoundTag(),null),null);
        assertEquals(WorldPrepSavedData.JobState.PAUSED,loaded.jobs().get(parent).state());
        assertEquals(124,loaded.jobs().get(parent).cursor());
        assertEquals(child,loaded.jobs().get(parent).snapshotId());
        assertEquals(data.jobs().get(child),loaded.jobs().get(child));
    }
    @Test void completedBatchNeedsTerrainRecoveryAfterMetadataReload() {
        var data=new WorldPrepSavedData();var id=UUID.randomUUID();
        data.jobs().put(id,new WorldPrepSavedData.Job(id,WorldPrepSavedData.Operation.BATCH_ECOLOGY,
                "proutlost:fixture",WorldPrepSavedData.JobState.COMPLETED,10,"",null));
        var loaded=WorldPrepSavedData.load(data.save(new CompoundTag(),null),null);
        assertEquals(WorldPrepSavedData.JobState.PAUSED,loaded.jobs().get(id).state());
        assertEquals(10,loaded.jobs().get(id).cursor());
    }
    @Test void completedEcologyRollbackRequiresExactJournalRecheckAfterRestart() {
        for (String pass : List.of("SOILS", "TREES", "FLORA", "AQUATIC")) {
            var data = new WorldPrepSavedData();
            var id = UUID.randomUUID(); var journal = UUID.randomUUID();
            var operation = WorldPrepSavedData.Operation.valueOf("ROLLBACK_" + pass);
            for (var state : List.of(WorldPrepSavedData.JobState.COMPLETED,
                    WorldPrepSavedData.JobState.CANCELLED, WorldPrepSavedData.JobState.FAILED)) {
                var original = new WorldPrepSavedData.Job(id, operation, "proutlost:tile", state, 55, "", journal);
                data.jobs().put(id, original);
                var loaded = WorldPrepSavedData.load(data.save(new CompoundTag(), null), null);
                assertEquals(original.withState(state == WorldPrepSavedData.JobState.COMPLETED
                        ? WorldPrepSavedData.JobState.PAUSED : state), loaded.jobs().get(id));
                var again = WorldPrepSavedData.load(loaded.save(new CompoundTag(), null), null);
                assertEquals(loaded.jobs().get(id), again.jobs().get(id));
            }
        }
    }
    @Test void standaloneRecoveryRejectsRollbackSupersessionAndBatchOwnership() {
        for (String pass : List.of("SOILS", "TREES", "FLORA", "AQUATIC")) {
            var d=new WorldPrepSavedData(); var id=UUID.randomUUID(); var snapshot=UUID.randomUUID();
            var area=new WorldPrepSavedData.Area("proutlost:test","minecraft:overworld",0,0,15,15);
            d.areas().put(area.id(),area);
            var job=new WorldPrepSavedData.Job(id,WorldPrepSavedData.Operation.valueOf("APPLY_"+pass),area.id(),WorldPrepSavedData.JobState.COMPLETED,55,"",snapshot);
            d.jobs().put(id,job);
            var loaded=WorldPrepSavedData.load(d.save(new CompoundTag(),null),null);
            assertDoesNotThrow(()->fr.proutlost.worldprep.runtime.BlockMutationRuntime.requireStandaloneRecovery(loaded,id));
            var other=UUID.randomUUID();
            d.jobs().put(other,new WorldPrepSavedData.Job(other,WorldPrepSavedData.Operation.valueOf("ROLLBACK_"+pass),area.id(),WorldPrepSavedData.JobState.CANCELLED,0,"",snapshot));
            assertThrows(IllegalStateException.class,()->fr.proutlost.worldprep.runtime.BlockMutationRuntime.requireStandaloneRecovery(d,id));
            d.jobs().put(other,new WorldPrepSavedData.Job(other,job.operation(),area.id(),WorldPrepSavedData.JobState.COMPLETED,55,"",UUID.randomUUID()));
            assertThrows(IllegalStateException.class,()->fr.proutlost.worldprep.runtime.BlockMutationRuntime.requireStandaloneRecovery(d,id));
            d.jobs().remove(other);
            d.jobs().put(id,new WorldPrepSavedData.Job(id,job.operation(),"proutlost:batch_fixture",job.state(),55,"",snapshot));
            assertThrows(IllegalStateException.class,()->fr.proutlost.worldprep.runtime.BlockMutationRuntime.requireStandaloneRecovery(d,id));
        }
    }
    @Test void oldRollbackCannotRemoveLaterIdenticalApplyAfterRestart() {
        for (String pass : List.of("SOILS", "TREES", "FLORA", "AQUATIC")) {
            var d=new WorldPrepSavedData(); var a=UUID.randomUUID(); var rollback=UUID.randomUUID();
            var b=UUID.randomUUID(); var journal=UUID.randomUUID();
            var area=new WorldPrepSavedData.Area("proutlost:tile","minecraft:overworld",0,0,15,15);
            d.areas().put(area.id(),area);
            var apply=WorldPrepSavedData.Operation.valueOf("APPLY_"+pass);
            var undo=WorldPrepSavedData.Operation.valueOf("ROLLBACK_"+pass);
            d.jobs().put(a,new WorldPrepSavedData.Job(a,apply,area.id(),WorldPrepSavedData.JobState.COMPLETED,55,"",journal));
            d.jobs().put(rollback,new WorldPrepSavedData.Job(rollback,undo,area.id(),WorldPrepSavedData.JobState.COMPLETED,55,"",journal));
            assertDoesNotThrow(()->fr.proutlost.worldprep.runtime.BlockMutationRuntime.requireRollbackRecoveryOwnership(d,d.jobs().get(rollback)));
            d.jobs().put(b,new WorldPrepSavedData.Job(b,apply,area.id(),WorldPrepSavedData.JobState.COMPLETED,55,"",UUID.randomUUID()));
            var loaded=WorldPrepSavedData.load(d.save(new CompoundTag(),null),null);
            assertEquals(WorldPrepSavedData.JobState.PAUSED,loaded.jobs().get(rollback).state());
            assertThrows(IllegalStateException.class,()->fr.proutlost.worldprep.runtime.BlockMutationRuntime.requireRollbackRecoveryOwnership(loaded,loaded.jobs().get(rollback)));
            var retry=UUID.randomUUID();
            loaded.jobs().put(retry,new WorldPrepSavedData.Job(retry,undo,area.id(),WorldPrepSavedData.JobState.RUNNING,0,"",journal));
            assertThrows(IllegalStateException.class,()->fr.proutlost.worldprep.runtime.BlockMutationRuntime.requireRollbackRecoveryOwnership(loaded,loaded.jobs().get(retry)));
            assertEquals(d.jobs().get(b),loaded.jobs().get(b));
        }
    }
    @Test void earlierRollbackPausedByRestartBlocksOnlyOverlappingApplyRecovery() {
        for (String pass : List.of("SOILS", "TREES", "FLORA", "AQUATIC")) {
            for (boolean overlapping : List.of(true, false)) {
                var d=new WorldPrepSavedData();
                var oldArea=new WorldPrepSavedData.Area("proutlost:old","minecraft:overworld",0,0,7,7);
                int x=overlapping?0:16;
                var newArea=new WorldPrepSavedData.Area("proutlost:new","minecraft:overworld",x,0,x+7,7);
                d.areas().put(oldArea.id(),oldArea);d.areas().put(newArea.id(),newArea);
                var oldApply=UUID.randomUUID();var oldRollback=UUID.randomUUID();var current=UUID.randomUUID();
                var oldJournal=UUID.randomUUID();var newJournal=UUID.randomUUID();
                var apply=WorldPrepSavedData.Operation.valueOf("APPLY_"+pass);
                var rollback=WorldPrepSavedData.Operation.valueOf("ROLLBACK_"+pass);
                d.jobs().put(oldApply,new WorldPrepSavedData.Job(oldApply,apply,oldArea.id(),WorldPrepSavedData.JobState.COMPLETED,12,"",oldJournal));
                d.jobs().put(oldRollback,new WorldPrepSavedData.Job(oldRollback,rollback,oldArea.id(),WorldPrepSavedData.JobState.COMPLETED,12,"",oldJournal));
                d.jobs().put(current,new WorldPrepSavedData.Job(current,apply,newArea.id(),WorldPrepSavedData.JobState.COMPLETED,12,"",newJournal));
                assertDoesNotThrow(()->fr.proutlost.worldprep.runtime.BlockMutationRuntime.requireStandaloneRecovery(d,current));
                var loaded=WorldPrepSavedData.load(d.save(new CompoundTag(),null),null);
                assertEquals(List.of(oldApply,oldRollback,current),List.copyOf(loaded.jobs().keySet()));
                var paused=loaded.jobs().get(oldRollback);
                assertEquals(WorldPrepSavedData.JobState.PAUSED,paused.state());
                assertEquals(oldJournal,paused.snapshotId());
                if(overlapping) assertThrows(IllegalStateException.class,
                        ()->fr.proutlost.worldprep.runtime.BlockMutationRuntime.requireStandaloneRecovery(loaded,current));
                else assertDoesNotThrow(()->fr.proutlost.worldprep.runtime.BlockMutationRuntime.requireStandaloneRecovery(loaded,current));
                assertEquals(paused,loaded.jobs().get(oldRollback));
                assertEquals(d.jobs().get(current),loaded.jobs().get(current));
            }
        }
    }
    @Test void rollbackLoaderIsOptInEcologyOnlyAndSingleChunk() {
        var one=new WorldPrepSavedData.Area("test","minecraft:overworld",-16,-32,-1,-17);
        var cross=new WorldPrepSavedData.Area("test","minecraft:overworld",-1,-32,0,-17);
        for(var op:WorldPrepSavedData.Operation.values()) {
            assertNull(fr.proutlost.worldprep.runtime.WorldPrepRuntime.rollbackLoadingTarget(op,cross,false));
            boolean supported=List.of("ROLLBACK_SOILS","ROLLBACK_TREES","ROLLBACK_FLORA","ROLLBACK_AQUATIC").contains(op.name());
            if(supported) {
                assertEquals(new net.minecraft.world.level.ChunkPos(-1,-2),
                        fr.proutlost.worldprep.runtime.WorldPrepRuntime.rollbackLoadingTarget(op,one,true));
                assertThrows(IllegalStateException.class,()->fr.proutlost.worldprep.runtime.WorldPrepRuntime.rollbackLoadingTarget(op,cross,true));
            } else assertNull(fr.proutlost.worldprep.runtime.WorldPrepRuntime.rollbackLoadingTarget(op,cross,true));
        }
    }
    @Test void playersAndTickPressureStopBeforeNextCheckpoint() {
        assertEquals(fr.proutlost.worldprep.ecology.ExecutionSafety.Stop.PLAYER_PRESENT,fr.proutlost.worldprep.ecology.ExecutionSafety.check(1,0,100));
        assertEquals(fr.proutlost.worldprep.ecology.ExecutionSafety.Stop.TICK_PRESSURE,fr.proutlost.worldprep.ecology.ExecutionSafety.check(0,100_000_001L,100));
        assertEquals(fr.proutlost.worldprep.ecology.ExecutionSafety.Stop.NONE,fr.proutlost.worldprep.ecology.ExecutionSafety.check(0,100_000_000L,100));
    }
    @Test void candidateMarkerIsBoundToExactDirectoryAndRequired() throws Exception {
        assertThrows(IllegalStateException.class,()->fr.proutlost.worldprep.runtime.CandidateGuard.requireCandidate(temp));
        var marker=new com.google.gson.JsonObject();marker.addProperty("schemaVersion",1);marker.addProperty("offlineBaseline",true);
        marker.addProperty("candidateDirectory",temp.resolve("wrong").toString());
        Files.writeString(temp.resolve("worldprep-candidate.json"),marker.toString());
        assertThrows(IllegalStateException.class,()->fr.proutlost.worldprep.runtime.CandidateGuard.requireCandidate(temp));
        marker.addProperty("candidateDirectory",temp.toRealPath().toString());
        Files.writeString(temp.resolve("worldprep-candidate.json"),marker.toString());
        fr.proutlost.worldprep.runtime.CandidateGuard.requireCandidate(temp);
    }
    @Test void missingHistoryIsReportedAndNeverRecreated() throws Exception {
        var ref=new ReferenceInventory.Reference("journals",UUID.randomUUID());
        var result=ReferenceInventory.inspect(temp,List.of(ref));
        assertEquals("ORPHANED_HISTORY_UNAVAILABLE",result.getFirst().status());
        try(var files=Files.list(temp)){assertEquals(0,files.count());}
        var dir=Files.createDirectories(temp.resolve("journals").resolve(ref.id().toString()));
        var file=dir.resolve("manifest.wjm");Files.writeString(file,"intentionally invalid fixture");
        assertEquals("PRESENT_CONTENT_NOT_VERIFIED",ReferenceInventory.inspect(temp,List.of(ref)).getFirst().status());
        assertEquals("intentionally invalid fixture",Files.readString(file));
        assertThrows(IllegalArgumentException.class,()->ReferenceInventory.inspect(temp,java.util.Collections.nCopies(65,ref)));
    }
}
