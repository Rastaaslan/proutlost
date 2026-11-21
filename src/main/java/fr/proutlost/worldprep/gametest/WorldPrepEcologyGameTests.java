package fr.proutlost.worldprep.gametest;

import fr.proutlost.worldprep.WorldPrepConfig;
import fr.proutlost.worldprep.persistence.BlockWorldPrepData;
import fr.proutlost.worldprep.persistence.WorldPrepSavedData;
import fr.proutlost.worldprep.runtime.BlockMutationRuntime;
import fr.proutlost.worldprep.runtime.WorldPrepRuntime;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.ArrayList;
import java.util.UUID;

@GameTestHolder("proutlost")
@PrefixGameTestTemplate(false)
public final class WorldPrepEcologyGameTests {
    @GameTest(templateNamespace="minecraft", template="village/plains/houses/plains_small_house_1", timeoutTicks=200)
    public static void realEcologyPreviewApplyRollback(GameTestHelper h) {
        WorldPrepConfig.ENABLED.set(true);
        var level = h.getLevel();
        var data = WorldPrepRuntime.data(level);
        var base = h.absolutePos(new BlockPos(1, 1, 1));
        int passIndex = 0;
        for (String pass : new String[]{"SOILS", "FLORA", "AQUATIC"}) {
            // Separate X/Z footprints: metadata reload intentionally pauses every old
            // rollback, which must continue to block recovery in an overlapping area.
            var passBase = base.offset(16 * passIndex++, 0, 0);
            String id = "proutlost:ecology_" + UUID.randomUUID().toString().replace("-", "");
            var area = new WorldPrepSavedData.Area(id, level.dimension().location().toString(),
                    passBase.getX(), passBase.getZ(), passBase.getX() + 7, passBase.getZ() + 7);
            for (var previous : data.areas().values()) {
                if (previous.id().startsWith("proutlost:ecology_"))
                    h.assertTrue(!previous.overlaps(area), "Ecology pass fixtures overlap");
            }
            for (int z = area.minZ(); z <= area.maxZ(); z++) for (int x = area.minX(); x <= area.maxX(); x++)
                h.assertTrue(level.getChunkSource().getChunkNow(x >> 4, z >> 4) != null,
                        "Ecology fixture must use already loaded chunks");
            data.areas().put(id, area);
            data.protections().put(id, new WorldPrepSavedData.ProtectedZone(id, area.dimension(),
                    area.minX(), area.minZ(), area.minX(), area.minZ()));
            int y = Math.max(level.getSeaLevel() + 20, base.getY() + 20);
            var positions = new ArrayList<BlockPos>();
            var before = new ArrayList<String>();
            var beforeStates = new ArrayList<net.minecraft.world.level.block.state.BlockState>();
            for (int z = area.minZ(); z <= area.maxZ(); z++) for (int x = area.minX(); x <= area.maxX(); x++) {
                // Clear a controlled test column, never used by production planning.
                for (int top = y + 1; top < level.getMaxBuildHeight(); top++)
                    level.setBlock(new BlockPos(x, top, z), Blocks.AIR.defaultBlockState(), 2);
                var ground = new BlockPos(x, y, z);
                level.setBlock(ground, (pass.equals("FLORA") ? Blocks.GRASS_BLOCK : Blocks.DIRT).defaultBlockState(), 2);
                if (x == area.minX() + 1 && z == area.minZ()) level.setBlock(ground, Blocks.GOLD_BLOCK.defaultBlockState(), 2);
                if (pass.equals("AQUATIC")) for (int dy = 1; dy <= 3; dy++)
                    level.setBlock(ground.above(dy), Blocks.WATER.defaultBlockState(), 2);
                for (int dy = 0; dy <= 3; dy++) {
                    positions.add(ground.above(dy));
                    beforeStates.add(level.getBlockState(ground.above(dy)));
                    before.add(BlockMutationRuntime.stateString(level.getBlockState(ground.above(dy))));
                }
            }
            var preview = WorldPrepRuntime.enqueue(level, WorldPrepSavedData.Operation.valueOf("PREVIEW_" + pass), id);
            finish(h, preview);
            var plan = BlockMutationRuntime.data(level).plans().get(BlockWorldPrepData.planKey(id, pass));
            h.assertTrue(plan.entryCount() > 0, pass + " generated no actual placements");
            for (int i = 0; i < positions.size(); i++) h.assertTrue(before.get(i).equals(
                    BlockMutationRuntime.stateString(level.getBlockState(positions.get(i)))), "Preview mutated world");
            var apply = WorldPrepRuntime.enqueue(level, WorldPrepSavedData.Operation.valueOf("APPLY_" + pass), id);
            finish(h, apply);
            h.assertTrue(java.util.stream.IntStream.range(0, positions.size()).anyMatch(i -> !before.get(i).equals(
                    BlockMutationRuntime.stateString(level.getBlockState(positions.get(i))))), "Apply made no changes");
            for (int i = 0; i < 8; i++) h.assertTrue(before.get(i).equals(
                    BlockMutationRuntime.stateString(level.getBlockState(positions.get(i)))), "Protected or unknown support changed");
            var appliedStates = positions.stream().map(level::getBlockState).toList();
            for (int i = 0; i < positions.size(); i++) level.setBlock(positions.get(i), beforeStates.get(i), 18);
            var applyReload = WorldPrepSavedData.load(data.save(new net.minecraft.nbt.CompoundTag(), level.registryAccess()), level.registryAccess());
            BlockMutationRuntime.requireStandaloneRecovery(applyReload, apply);
            BlockMutationRuntime.reconcileCompleted(level, applyReload, applyReload.jobs().get(apply));
            for (int i = 0; i < positions.size(); i++) h.assertTrue(level.getBlockState(positions.get(i)).equals(appliedStates.get(i)),
                    "Completed standalone apply lost terrain: " + pass);

            var rollback = WorldPrepRuntime.enqueueRollback(level, WorldPrepSavedData.Operation.valueOf("ROLLBACK_" + pass),
                    id, data.jobs().get(apply).snapshotId());
            finish(h, rollback);
            // Simulate durable COMPLETED metadata but lost rollback chunk writes.
            var reloaded = WorldPrepSavedData.load(data.save(new net.minecraft.nbt.CompoundTag(), level.registryAccess()), level.registryAccess());
            h.assertTrue(reloaded.jobs().get(rollback).state() == WorldPrepSavedData.JobState.PAUSED,
                    "Completed rollback trusted without terrain reconciliation");
            for (int i = 0; i < positions.size(); i++) level.setBlock(positions.get(i), appliedStates.get(i), 18);
            CheckpointFixture.process(level, reloaded, rollback, reloaded.jobs().get(rollback), 256);
            h.assertTrue(reloaded.jobs().get(rollback).state() == WorldPrepSavedData.JobState.COMPLETED,
                    "Exact rollback journal recovery did not finish");
            for (int i = 0; i < positions.size(); i++) h.assertTrue(before.get(i).equals(
                    BlockMutationRuntime.stateString(level.getBlockState(positions.get(i)))), "Rollback did not restore exact state");
            verifySupersededRollback(h, id, pass, rollback, positions);
        }
        h.succeed();
    }
    @GameTest(templateNamespace="minecraft", template="village/plains/houses/plains_small_house_1", timeoutTicks=200)
    public static void controlledTreeCheckpointReloadAndRollback(GameTestHelper h) {
        WorldPrepConfig.ENABLED.set(true);
        var level=h.getLevel();var data=WorldPrepRuntime.data(level);
        var base=h.absolutePos(new BlockPos(3,1,3));
        var root=new BlockPos(base.getX(),Math.max(level.getSeaLevel()+24,base.getY()+24),base.getZ());
        String id="proutlost:tree_"+UUID.randomUUID().toString().replace("-","");
        var area=new WorldPrepSavedData.Area(id,level.dimension().location().toString(),root.getX()-2,root.getZ()-2,root.getX()+2,root.getZ()+2);
        data.areas().put(id,area);
        level.setBlock(root.below(),Blocks.GRASS_BLOCK.defaultBlockState(),18);
        var plan=new BlockWorldPrepData.Plan(id,"TREES","gametest",BlockMutationRuntime.inputIdentity(level,data,area,"TREES"),"");
        for(var voxel:fr.proutlost.worldprep.ecology.ControlledOak.SHAPE){
            var pos=root.offset(voxel.x(),voxel.y(),voxel.z());
            level.setBlock(pos,Blocks.AIR.defaultBlockState(),18);
            plan.add(new BlockWorldPrepData.Change(pos.getX(),pos.getY(),pos.getZ(),"minecraft:air",voxel.blockState(),
                    "CONTROLLED_OAK_V1:"+root.getX()+":"+root.getY()+":"+root.getZ()));
        }
        // A sealed explicit fixture exercises the actual production journal and safe tree envelope.
        BlockMutationRuntime.publishSealed(level,data,area,plan);
        var blocks=BlockMutationRuntime.data(level);blocks.plans().put(BlockWorldPrepData.planKey(id,"TREES"),plan);
        var apply=WorldPrepRuntime.enqueue(level,WorldPrepSavedData.Operation.APPLY_TREES,id);
        for(int phase=0;phase<8 && data.jobs().get(apply).cursor()==0;phase++)
            CheckpointFixture.tick(level,8);
        h.assertTrue(data.jobs().get(apply).cursor()==8,"Expected a partial tree checkpoint");
        var restored=WorldPrepSavedData.load(data.save(new net.minecraft.nbt.CompoundTag(),level.registryAccess()),level.registryAccess());
        var restoredBlocks=BlockWorldPrepData.load(blocks.save(new net.minecraft.nbt.CompoundTag(),level.registryAccess()),level.registryAccess());
        h.assertTrue(restored.jobs().get(apply).state()==WorldPrepSavedData.JobState.PAUSED,"Restart did not pause tree job");
        data.jobs().put(apply,restored.jobs().get(apply).withState(WorldPrepSavedData.JobState.RUNNING));
        blocks.plans().putAll(restoredBlocks.plans());blocks.journals().putAll(restoredBlocks.journals());
        finish(h,apply);
        for(var voxel:fr.proutlost.worldprep.ecology.ControlledOak.SHAPE){
            var pos=root.offset(voxel.x(),voxel.y(),voxel.z());
            h.assertTrue(BlockMutationRuntime.stateString(level.getBlockState(pos)).equals(voxel.blockState()),"Incomplete tree after metadata reload");
        }
        for(var voxel:fr.proutlost.worldprep.ecology.ControlledOak.SHAPE)
            level.setBlock(root.offset(voxel.x(),voxel.y(),voxel.z()),Blocks.AIR.defaultBlockState(),18);
        var applyReload=WorldPrepSavedData.load(data.save(new net.minecraft.nbt.CompoundTag(),level.registryAccess()),level.registryAccess());
        BlockMutationRuntime.requireStandaloneRecovery(applyReload,apply);
        BlockMutationRuntime.reconcileCompleted(level,applyReload,applyReload.jobs().get(apply));
        var appliedTree=fr.proutlost.worldprep.ecology.ControlledOak.SHAPE.stream()
                .map(v->level.getBlockState(root.offset(v.x(),v.y(),v.z()))).toList();
        for(int i=0;i<appliedTree.size();i++) h.assertTrue(BlockMutationRuntime.stateString(appliedTree.get(i)).equals(
                fr.proutlost.worldprep.ecology.ControlledOak.SHAPE.get(i).blockState()),"Completed tree apply recovery failed");
        var rollback=WorldPrepRuntime.enqueueRollback(level,WorldPrepSavedData.Operation.ROLLBACK_TREES,id,data.jobs().get(apply).snapshotId());
        finish(h,rollback);
        var rollbackReload=WorldPrepSavedData.load(data.save(new net.minecraft.nbt.CompoundTag(),level.registryAccess()),level.registryAccess());
        h.assertTrue(rollbackReload.jobs().get(rollback).state()==WorldPrepSavedData.JobState.PAUSED,"Tree rollback trusted after restart");
        for(int i=0;i<appliedTree.size();i++) {
            var v=fr.proutlost.worldprep.ecology.ControlledOak.SHAPE.get(i);
            level.setBlock(root.offset(v.x(),v.y(),v.z()),appliedTree.get(i),18);
        }
        CheckpointFixture.process(level,rollbackReload,rollback,rollbackReload.jobs().get(rollback),256);
        for(var voxel:fr.proutlost.worldprep.ecology.ControlledOak.SHAPE)
            h.assertTrue(level.getBlockState(root.offset(voxel.x(),voxel.y(),voxel.z())).isAir(),"Tree rollback incomplete");
        verifySupersededRollback(h,id,"TREES",rollback,fr.proutlost.worldprep.ecology.ControlledOak.SHAPE.stream()
                .map(v->root.offset(v.x(),v.y(),v.z())).toList());
        h.succeed();
    }
    @GameTest(templateNamespace="minecraft", template="village/plains/houses/plains_small_house_1", timeoutTicks=200)
    public static void batchRunsRealStagesOnCandidateFixture(GameTestHelper h) { runBatchFixture(h,false); }
    @GameTest(templateNamespace="minecraft", template="village/plains/houses/plains_small_house_1", timeoutTicks=200)
    public static void batchWithVerifiedExistingChunkLoader(GameTestHelper h) { runBatchFixture(h,true); }
    private static void runBatchFixture(GameTestHelper h,boolean loadExisting) {
        WorldPrepConfig.ENABLED.set(true);var level=h.getLevel();var data=WorldPrepRuntime.data(level);
        var root=level.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT);
        var marker=root.resolve("worldprep-candidate.json");
        boolean created=false;
        boolean previousLoading=WorldPrepConfig.LOAD_EXISTING_CHUNKS.get();
        WorldPrepConfig.LOAD_EXISTING_CHUNKS.set(loadExisting);
        try {
            // Marker is created only inside the isolated GameTest world, never on an operator world.
            if(java.nio.file.Files.exists(marker))throw new IllegalStateException("GameTest requires a clean candidate marker path");
            var json=new com.google.gson.JsonObject();json.addProperty("schemaVersion",1);
            json.addProperty("offlineBaseline",true);json.addProperty("candidateDirectory",root.toRealPath().toString());
            java.nio.file.Files.writeString(marker,json.toString(),java.nio.file.StandardOpenOption.CREATE_NEW);created=true;
            var base=h.absolutePos(new BlockPos(2,1,2));int y=Math.max(base.getY()+30,level.getSeaLevel()+30);
            var ground=new BlockPos(base.getX(),y,base.getZ());
            for(int yy=y+1;yy<level.getMaxBuildHeight();yy++)level.setBlock(new BlockPos(base.getX(),yy,base.getZ()),Blocks.AIR.defaultBlockState(),18);
            level.setBlock(ground,Blocks.DIRT.defaultBlockState(),18);
            if(loadExisting){
                // Fixture preparation only: persist already loaded GameTest chunks, never generate neighbors.
                int cx=base.getX()>>4,cz=base.getZ()>>4;
                for(int dz=-1;dz<=1;dz++)for(int dx=-1;dx<=1;dx++)
                    h.assertTrue(level.getChunkSource().getChunkNow(cx+dx,cz+dz)!=null,"GameTest needs a loaded 3x3 fixture");
                level.getChunkSource().save(true);
                int farX=cx+100_000,farZ=cz+100_000;
                var region=net.minecraft.world.level.dimension.DimensionType.getStorageFolder(level.dimension(),root).resolve("region")
                        .resolve("r."+Math.floorDiv(farX-1,32)+"."+Math.floorDiv(farZ-1,32)+".mca");
                h.assertTrue(!java.nio.file.Files.exists(region),"Missing-chunk fixture already exists");
                boolean refused=false;
                try{fr.proutlost.worldprep.runtime.ExistingChunkLoader.ensure(level,UUID.randomUUID(),farX,farZ);}
                catch(IllegalStateException expected){refused=true;}
                h.assertTrue(refused,"Missing region was accepted");
                h.assertTrue(!java.nio.file.Files.exists(region),"Missing region was created");
                h.assertTrue(level.getChunkSource().getChunkNow(farX,farZ)==null,"Missing chunk promoted");
            }
            String id="proutlost:batch_fixture_"+UUID.randomUUID().toString().replace("-","");
            int maxX=base.getX()+(loadExisting?0:32);
            for(int xx=base.getX();xx<=maxX;xx++) {
                h.assertTrue(level.getChunkSource().getChunkNow(xx>>4,base.getZ()>>4)!=null,"Receipt fixture requires existing chunks");
                for(int yy=y+1;yy<level.getMaxBuildHeight();yy++)level.setBlock(new BlockPos(xx,yy,base.getZ()),Blocks.AIR.defaultBlockState(),18);
                level.setBlock(new BlockPos(xx,y,base.getZ()),Blocks.DIRT.defaultBlockState(),18);
            }
            data.areas().put(id,new WorldPrepSavedData.Area(id,level.dimension().location().toString(),base.getX(),base.getZ(),maxX,base.getZ()));
            var parent=WorldPrepRuntime.enqueue(level,WorldPrepSavedData.Operation.BATCH_ECOLOGY,id);
            for(int i=0;i<160&&data.jobs().get(parent).state()!=WorldPrepSavedData.JobState.COMPLETED
                    &&(loadExisting||data.jobs().get(parent).cursor()<22);i++){
                CheckpointFixture.tick(level,256);
                h.assertTrue(data.jobs().get(parent).state()!=WorldPrepSavedData.JobState.FAILED,data.jobs().get(parent).error());
            }
            if(!loadExisting) {
                h.assertTrue(data.jobs().get(parent).cursor()==22,"Expected exactly 22 durable stages");
                var manifestRoot=root.resolve("worldprep-v2");
                var header=new fr.proutlost.worldprep.storage.BatchManifestStore.Header(1,parent,level.dimension().location().toString(),id,
                        level.getSeed(),base.getX(),base.getZ(),maxX,base.getZ(),30,"ecology-batch-v3");
                for(int i=0;i<22;i++)fr.proutlost.worldprep.storage.BatchManifestStore.read(manifestRoot,header,i);
                var receipt=fr.proutlost.worldprep.storage.BatchManifestStore.read(manifestRoot,header,2);
                var stale=WorldPrepSavedData.load(data.save(new net.minecraft.nbt.CompoundTag(),level.registryAccess()),level.registryAccess());
                var originalChild=stale.jobs().get(receipt.childId());
                stale.jobs().put(parent,stale.jobs().get(parent).withCursor(2).withSnapshot(originalChild.id()).withState(WorldPrepSavedData.JobState.PAUSED));
                stale.jobs().put(originalChild.id(),originalChild.withCursor(0).withState(WorldPrepSavedData.JobState.PAUSED));
                level.getDataStorage().set(WorldPrepSavedData.FILE_ID,stale);
                stale.changed();BlockMutationRuntime.data(level).changed();
                saveFixtureStores(level);
                stale=reloadFixtureStores(level);
                h.assertTrue(stale==WorldPrepRuntime.data(level),"Recovery uses detached SavedData");
                for(int yy=y+1;yy<level.getMaxBuildHeight();yy++)level.setBlock(new BlockPos(base.getX(),yy,base.getZ()),Blocks.AIR.defaultBlockState(),18);
                level.setBlock(ground,Blocks.DIRT.defaultBlockState(),18);
                // Invalid evidence must survive refusal, without falsely completing the child.
                var journal=BlockMutationRuntime.data(level).journals().get(originalChild.snapshotId());
                var journalDir=fr.proutlost.worldprep.runtime.RuntimePageJournal.directory(level,journal.id);
                for(var evidence:java.util.List.of(
                        fr.proutlost.worldprep.storage.PagedJournalStore.manifestPath(journalDir),
                        fr.proutlost.worldprep.storage.PagedJournalStore.pagePath(journalDir,0))) {
                    byte[] original=java.nio.file.Files.readAllBytes(evidence);
                    boolean manifest=evidence.getFileName().toString().equals("manifest.wjm");
                    byte[] damaged=java.util.Arrays.copyOf(original,manifest?original.length:Math.max(0,original.length-1));
                    if(manifest)damaged[0]^=1;
                    java.nio.file.Files.write(evidence,damaged);
                    try {
                        boolean refused=false;
                        try { for(int i=0;i<3;i++)CheckpointFixture.batch(level,stale,stale.jobs().get(parent),256); }
                        catch(IllegalStateException expected){refused=true;}
                        h.assertTrue(refused,"Incomplete journal accepted");
                        h.assertTrue(stale.jobs().get(parent).cursor()==2&&stale.jobs().get(originalChild.id()).state()==WorldPrepSavedData.JobState.PAUSED,
                                "Invalid journal published completion");
                        h.assertTrue(java.util.Arrays.equals(damaged,java.nio.file.Files.readAllBytes(evidence)),"Refusal rewrote evidence");
                        h.assertTrue(level.getBlockState(ground).is(Blocks.DIRT),"Invalid journal mutated terrain");
                    } finally { java.nio.file.Files.write(evidence,original); }
                    stale=reloadFixtureStores(level);
                }
                for(int i=0;i<3;i++)CheckpointFixture.batch(level,stale,stale.jobs().get(parent),256);
                // Interrupt immediately after the first recovered stage: read files actually saved
                // by production advance/reconciliation, not an in-memory NBT clone.
                net.neoforged.neoforge.common.IOUtilities.waitUntilIOWorkerComplete();
                stale=reloadFixtureStores(level);
                h.assertTrue(stale.jobs().get(parent).cursor()==3,"Recovered cursor was not persisted");
                h.assertTrue(stale.jobs().get(originalChild.id()).state()==WorldPrepSavedData.JobState.COMPLETED,
                        "Recovered child was not persisted");
                h.assertTrue(BlockMutationRuntime.data(level).journals().get(journal.id).planId().equals(journal.planId()),"Reload changed journal ownership");
                var later=fr.proutlost.worldprep.storage.BatchManifestStore.read(manifestRoot,header,3);
                var missing=stale.jobs().remove(later.childId());
                h.assertTrue(missing!=null,"Missing-metadata fixture had no child");
                stale.changed();saveFixtureStores(level);stale=reloadFixtureStores(level);
                byte[] receiptBytes=java.nio.file.Files.readAllBytes(fr.proutlost.worldprep.storage.BatchManifestStore.stagePath(manifestRoot,parent,3));
                boolean refusedMissing=false;
                try { for(int i=0;i<4;i++)CheckpointFixture.batch(level,stale,stale.jobs().get(parent),256); }
                catch(IllegalStateException expected){refusedMissing=true;}
                h.assertTrue(refusedMissing&&stale.jobs().get(parent).cursor()==3&&!stale.jobs().containsKey(later.childId()),"Missing ownership reconstructed or skipped");
                h.assertTrue(java.util.Arrays.equals(receiptBytes,java.nio.file.Files.readAllBytes(fr.proutlost.worldprep.storage.BatchManifestStore.stagePath(manifestRoot,parent,3))),"Refusal changed receipt");
                stale.jobs().put(missing.id(),missing);stale.changed();saveFixtureStores(level);stale=reloadFixtureStores(level);
                for(int i=0;i<160&&stale.jobs().get(parent).state()!=WorldPrepSavedData.JobState.COMPLETED;i++)
                    CheckpointFixture.batch(level,stale,stale.jobs().get(parent),256);
                h.assertTrue(stale.jobs().get(parent).cursor()==30&&stale.jobs().get(parent).state()==WorldPrepSavedData.JobState.COMPLETED,
                        "Receipt-ahead recovery failed");
                h.assertTrue(level.getBlockState(ground).is(Blocks.GRASS_BLOCK),"Receipt trusted without terrain reconciliation");
                h.assertTrue(stale.jobs().get(originalChild.id()).snapshotId().equals(originalChild.snapshotId()),"Recovery replaced exact journal");
                fr.proutlost.worldprep.storage.BatchManifestStore.validateAll(manifestRoot,header);
                net.neoforged.neoforge.common.IOUtilities.waitUntilIOWorkerComplete();
                stale=reloadFixtureStores(level);
                h.assertTrue(stale.jobs().get(parent).cursor()==30,"Final cursor not on disk");
                for(var savedJournal:BlockMutationRuntime.data(level).journals().values()) {
                    if(!savedJournal.area.startsWith("proutlost:batch_"+parent.toString().replace("-","")+"_"))continue;
                    fr.proutlost.worldprep.runtime.RuntimePageJournal.visit(level,savedJournal,(sequence,changes)->{
                        for(var change:changes)h.assertTrue(BlockMutationRuntime.stateString(level.getBlockState(new BlockPos(change.x(),change.y(),change.z()))).equals(change.applied()),
                                "Journaled block differs after second registered reload");
                    });
                }
                stale.jobs().put(parent,stale.jobs().get(parent).withState(WorldPrepSavedData.JobState.CANCELLED));stale.changed();
                h.succeed();return;
            }
            h.assertTrue(data.jobs().get(parent).state()==WorldPrepSavedData.JobState.COMPLETED,"Batch did not complete");
            h.assertTrue(data.jobs().get(parent).cursor()==10,"Batch skipped a stage");
            h.assertTrue(level.getBlockState(ground).is(Blocks.GRASS_BLOCK),"Batch did not apply soils");
            var children=data.jobs().values().stream().filter(j->j.area().startsWith("proutlost:batch_"+parent.toString().replace("-","")+"_")).toList();
            h.assertTrue(children.size()==8&&children.stream().allMatch(j->j.state()==WorldPrepSavedData.JobState.COMPLETED),"Children did not complete exact pass order");
            var manifestRoot=root.resolve("worldprep-v2");
            var header=new fr.proutlost.worldprep.storage.BatchManifestStore.Header(1,parent,level.dimension().location().toString(),id,
                    level.getSeed(),base.getX(),base.getZ(),base.getX(),base.getZ(),10,"ecology-batch-v3");
            fr.proutlost.worldprep.storage.BatchManifestStore.validateAll(manifestRoot,header);
            var first=fr.proutlost.worldprep.storage.BatchManifestStore.read(manifestRoot,header,0);
            var last=fr.proutlost.worldprep.storage.BatchManifestStore.read(manifestRoot,header,9);
            h.assertTrue(first.stage().equals("STRUCTURES")&&last.stage().equals("HABITATS"),"Discovery receipts do not identify exact stages");
            h.assertTrue(!first.artifactSha256().equals(last.artifactSha256()),"Soil change missing from before/after evidence");
            // Model crash AFTER completed receipts/SavedData, BEFORE this chunk was saved.
            // Keep receipts and exact journal bytes; restore the old terrain image only.
            data.changed();BlockMutationRuntime.data(level).changed();saveFixtureStores(level);
            var reloaded=reloadFixtureStores(level);
            h.assertTrue(reloaded.jobs().get(parent).state()==WorldPrepSavedData.JobState.PAUSED,"Completed batch trusted after reload");
            for(int yy=y+1;yy<level.getMaxBuildHeight();yy++)level.setBlock(new BlockPos(base.getX(),yy,base.getZ()),Blocks.AIR.defaultBlockState(),18);
            level.setBlock(ground,Blocks.DIRT.defaultBlockState(),18);
            for(int i=0;i<40&&reloaded.jobs().get(parent).state()!=WorldPrepSavedData.JobState.COMPLETED;i++)
                CheckpointFixture.batch(level,reloaded,reloaded.jobs().get(parent),256);
            h.assertTrue(reloaded.jobs().get(parent).state()==WorldPrepSavedData.JobState.COMPLETED,"Receipt recovery did not finish");
            h.assertTrue(level.getBlockState(ground).is(Blocks.GRASS_BLOCK),"Receipt skipped lost terrain writes");
            fr.proutlost.worldprep.storage.BatchManifestStore.validateAll(manifestRoot,header);
            h.succeed();
        } catch(java.io.IOException e){throw new AssertionError(e);}
        finally {fr.proutlost.worldprep.runtime.ExistingChunkLoader.release(level);WorldPrepConfig.LOAD_EXISTING_CHUNKS.set(previousLoading);if(created)try{java.nio.file.Files.delete(marker);}catch(java.io.IOException e){throw new AssertionError(e);}}
    }
    @GameTest(templateNamespace="minecraft",template="village/plains/houses/plains_small_house_1",timeoutTicks=200)
    public static void productionTreePreviewApplyRollback(GameTestHelper h){
        WorldPrepConfig.ENABLED.set(true);var level=h.getLevel();var data=WorldPrepRuntime.data(level);
        var origin=h.absolutePos(new BlockPos(2,2,2));BlockPos selected=null;
        // Search already loaded test chunks only; refuse rather than generate a fixture chunk.
        search:for(int dz=-4;dz<=4;dz++)for(int dx=-4;dx<=4;dx++){
            int cx=(origin.getX()>>4)+dx,cz=(origin.getZ()>>4)+dz;
            var chunk=level.getChunkSource().getChunkNow(cx,cz);
            if(chunk==null||!chunk.getAllStarts().isEmpty()||!chunk.getAllReferences().isEmpty()
                    ||!fr.proutlost.worldprep.ecology.ControlledOak.selected(level.getSeed(),cx,cz))continue;
            int x=fr.proutlost.worldprep.ecology.ControlledOak.rootX(level.getSeed(),cx,cz);
            int z=fr.proutlost.worldprep.ecology.ControlledOak.rootZ(level.getSeed(),cx,cz);
            boolean protectedColumn=false;for(int zz=z-2;zz<=z+2;zz++)for(int xx=x-2;xx<=x+2;xx++)
                protectedColumn|=data.protectedBlock(level.dimension().location().toString(),xx,zz);
            if(!protectedColumn){selected=new BlockPos(x,level.getMaxBuildHeight()-12,z);break search;}
        }
        h.assertTrue(selected!=null,"Fixture requires an already loaded selected chunk; no chunks generated");
        var root=java.util.Objects.requireNonNull(selected);
        var originals=new java.util.LinkedHashMap<BlockPos,net.minecraft.world.level.block.state.BlockState>();
        String id="proutlost:production_tree_"+UUID.randomUUID().toString().replace("-","");
        var area=new WorldPrepSavedData.Area(id,level.dimension().location().toString(),root.getX()-2,root.getZ()-2,root.getX()+2,root.getZ()+2);
        data.areas().put(id,area);
        try{
            for(int z=area.minZ();z<=area.maxZ();z++)for(int x=area.minX();x<=area.maxX();x++)for(int y=root.getY()-1;y<level.getMaxBuildHeight();y++){
                var pos=new BlockPos(x,y,z);originals.put(pos,level.getBlockState(pos));level.setBlock(pos,Blocks.AIR.defaultBlockState(),18);
            }
            level.setBlock(root.below(),Blocks.GRASS_BLOCK.defaultBlockState(),18);
            finish(h,WorldPrepRuntime.enqueue(level,WorldPrepSavedData.Operation.PREVIEW_TREES,id));
            var plan=BlockMutationRuntime.data(level).plans().get(BlockWorldPrepData.planKey(id,"TREES"));
            h.assertTrue(plan.entryCount()==fr.proutlost.worldprep.ecology.ControlledOak.SHAPE.size(),"Production tree preview did not plan a full tree");
            h.assertTrue(level.getBlockState(root).isAir(),"Tree preview changed world");
            var apply=WorldPrepRuntime.enqueue(level,WorldPrepSavedData.Operation.APPLY_TREES,id);finish(h,apply);
            h.assertTrue(level.getBlockState(root).is(Blocks.OAK_LOG),"Production tree was not applied");
            for(var voxel:fr.proutlost.worldprep.ecology.ControlledOak.SHAPE)
            level.setBlock(root.offset(voxel.x(),voxel.y(),voxel.z()),Blocks.AIR.defaultBlockState(),18);
        var applyReload=WorldPrepSavedData.load(data.save(new net.minecraft.nbt.CompoundTag(),level.registryAccess()),level.registryAccess());
        BlockMutationRuntime.requireStandaloneRecovery(applyReload,apply);
        BlockMutationRuntime.reconcileCompleted(level,applyReload,applyReload.jobs().get(apply));
        var appliedTree=fr.proutlost.worldprep.ecology.ControlledOak.SHAPE.stream()
                .map(v->level.getBlockState(root.offset(v.x(),v.y(),v.z()))).toList();
        for(int i=0;i<appliedTree.size();i++) h.assertTrue(BlockMutationRuntime.stateString(appliedTree.get(i)).equals(
                fr.proutlost.worldprep.ecology.ControlledOak.SHAPE.get(i).blockState()),"Completed tree apply recovery failed");
        var rollback=WorldPrepRuntime.enqueueRollback(level,WorldPrepSavedData.Operation.ROLLBACK_TREES,id,data.jobs().get(apply).snapshotId());
        finish(h,rollback);
        var rollbackReload=WorldPrepSavedData.load(data.save(new net.minecraft.nbt.CompoundTag(),level.registryAccess()),level.registryAccess());
        h.assertTrue(rollbackReload.jobs().get(rollback).state()==WorldPrepSavedData.JobState.PAUSED,"Tree rollback trusted after restart");
        for(int i=0;i<appliedTree.size();i++) {
            var v=fr.proutlost.worldprep.ecology.ControlledOak.SHAPE.get(i);
            level.setBlock(root.offset(v.x(),v.y(),v.z()),appliedTree.get(i),18);
        }
        CheckpointFixture.process(level,rollbackReload,rollback,rollbackReload.jobs().get(rollback),256);
            for(var voxel:fr.proutlost.worldprep.ecology.ControlledOak.SHAPE)h.assertTrue(level.getBlockState(root.offset(voxel.x(),voxel.y(),voxel.z())).isAir(),"Production tree rollback incomplete");
            level.setBlock(root.offset(1,2,0),Blocks.GOLD_BLOCK.defaultBlockState(),18);
            finish(h,WorldPrepRuntime.enqueue(level,WorldPrepSavedData.Operation.PREVIEW_TREES,id));
            h.assertTrue(BlockMutationRuntime.data(level).plans().get(BlockWorldPrepData.planKey(id,"TREES")).entryCount()==0,"Unknown canopy obstruction was overwritten");
        }finally{originals.forEach((pos,state)->level.setBlock(pos,state,18));}
        h.succeed();
    }
    /** GameTest-only IO barrier: never add this global wait to the bounded production tick. */
    private static void saveFixtureStores(net.minecraft.server.level.ServerLevel level) {
        level.getDataStorage().save();
        net.neoforged.neoforge.common.IOUtilities.waitUntilIOWorkerComplete();
    }
    private static WorldPrepSavedData reloadFixtureStores(net.minecraft.server.level.ServerLevel level) throws java.io.IOException {
        var storage=level.getDataStorage();
        var world=WorldPrepSavedData.load(storage.readTagFromDisk(WorldPrepSavedData.FILE_ID,null,0).getCompound("data"),level.registryAccess());
        var blocks=BlockWorldPrepData.load(storage.readTagFromDisk(BlockWorldPrepData.FILE_ID,null,0).getCompound("data"),level.registryAccess());
        storage.set(WorldPrepSavedData.FILE_ID,world);
        storage.set(BlockWorldPrepData.FILE_ID,blocks);
        if(WorldPrepRuntime.data(level)!=world||BlockMutationRuntime.data(level)!=blocks)
            throw new IllegalStateException("Fixture stores not registered");
        return world;
    }
    private static void verifySupersededRollback(GameTestHelper h, String area, String pass,
            UUID oldRollback, java.util.List<BlockPos> positions) {
        var level=h.getLevel(); var data=WorldPrepRuntime.data(level);
        // APPLY B uses the same sealed placements as A, so value-only ownership is insufficient.
        var b=WorldPrepRuntime.enqueue(level,WorldPrepSavedData.Operation.valueOf("APPLY_"+pass),area);
        finish(h,b);
        var expected=positions.stream().map(level::getBlockState).toList();
        var loaded=WorldPrepSavedData.load(data.save(new net.minecraft.nbt.CompoundTag(),level.registryAccess()),level.registryAccess());
        var resumed=loaded.jobs().get(oldRollback).withState(WorldPrepSavedData.JobState.RUNNING);
        loaded.jobs().put(oldRollback,resumed);
        boolean refused=false;
        try { CheckpointFixture.process(level,loaded,oldRollback,resumed,256); }
        catch (IllegalStateException expectedRefusal) {
            refused=expectedRefusal.getMessage().startsWith("Rollback superseded");
        }
        h.assertTrue(refused,"Superseded rollback accepted: "+pass);
        for(int i=0;i<positions.size();i++) h.assertTrue(level.getBlockState(positions.get(i)).equals(expected.get(i)),
                "Old rollback removed B's changes: "+pass);
        finish(h,WorldPrepRuntime.enqueueRollback(level,WorldPrepSavedData.Operation.valueOf("ROLLBACK_"+pass),
                area,data.jobs().get(b).snapshotId()));
    }
    private static void finish(GameTestHelper h, UUID id) {
        for (int tick = 0; tick < 16; tick++) {
            CheckpointFixture.tick(h.getLevel(), 256);
            var job = WorldPrepRuntime.data(h.getLevel()).jobs().get(id);
            h.assertTrue(job.state() != WorldPrepSavedData.JobState.FAILED, job.error());
            if (job.state() == WorldPrepSavedData.JobState.COMPLETED) return;
        }
        h.fail("Ecology job failed to terminate");
    }
    private WorldPrepEcologyGameTests() {}
}
