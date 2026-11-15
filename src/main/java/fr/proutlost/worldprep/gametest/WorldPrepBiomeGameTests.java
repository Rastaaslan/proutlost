package fr.proutlost.worldprep.gametest;

import fr.proutlost.worldprep.WorldPrepConfig;
import fr.proutlost.worldprep.persistence.WorldPrepSavedData;
import fr.proutlost.worldprep.plan.PlanIdentity;
import fr.proutlost.worldprep.runtime.BiomeRuntime;
import fr.proutlost.worldprep.runtime.MinecraftTerrainSampler;
import fr.proutlost.worldprep.runtime.RuntimeBiomeJournal;
import fr.proutlost.worldprep.runtime.WorldPrepRuntime;
import net.minecraft.core.BlockPos;
import net.minecraft.core.QuartPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

@GameTestHolder("proutlost")
@PrefixGameTestTemplate(false)
public final class WorldPrepBiomeGameTests {
    private static final String TEMPLATE="village/plains/houses/plains_small_house_1";
    @GameTest(templateNamespace="minecraft",template=TEMPLATE,timeoutTicks=40)
    public static void actualBiomeWriteProtectedExclusionAndExactRollback(GameTestHelper helper){var level=helper.getLevel();BlockPos pos=helper.absolutePos(new BlockPos(2,2,2));int qx=QuartPos.fromBlock(pos.getX()),qz=QuartPos.fromBlock(pos.getZ()),qy=QuartPos.fromBlock(pos.getY());var d=WorldPrepRuntime.data(level);String areaId="proutlost:gametest_"+UUID.randomUUID().toString().replace("-","");var area=new WorldPrepSavedData.Area(areaId,level.dimension().location().toString(),qx<<2,qz<<2,(qx<<2)+7,(qz<<2)+3);d.areas().put(areaId,area);var input=PlanIdentity.capture(level,d,area);String original=BiomeRuntime.biomeId(level,qx,qy,qz);String target=original.equals("minecraft:desert")?"minecraft:plains":"minecraft:desert";var sample=MinecraftTerrainSampler.sample(level,(qx<<2)+2,(qz<<2)+2);var plan=new WorldPrepSavedData.Plan(areaId,input,fr.proutlost.worldprep.plan.PlanFingerprint.of("gametest").value());var cell=new WorldPrepSavedData.BiomeCell(qx,qz,target,sample.signature());plan.cells.put(WorldPrepSavedData.cellKey(qx,qz),cell);d.plans().put(areaId,plan);UUID applyJob=UUID.randomUUID(),snapshotId=UUID.randomUUID();var snapshot=new WorldPrepSavedData.Snapshot(snapshotId,applyJob,UUID.randomUUID(),area.dimension(),areaId,"BIOMES",plan.fingerprint);d.snapshots().put(snapshotId,snapshot);String unrelated=BiomeRuntime.biomeId(level,qx+1,qy,qz);if(!WorldPrepRuntime.applyPlannedCell(level,d,area,plan,snapshot,cell))helper.fail("eligible cell unexpectedly protected");if(!BiomeRuntime.biomeId(level,qx,qy,qz).equals(target))helper.fail("real chunk biome palette was not mutated");var changedChunk=level.getChunk(pos.getX()>>4,pos.getZ()>>4);if(!changedChunk.isUnsaved())helper.fail("changed chunk was not marked unsaved");level.getChunkSource().save(true);if(!BiomeRuntime.biomeId(level,qx,qy,qz).equals(target))helper.fail("biome was lost across real chunk save");if(!BiomeRuntime.biomeId(level,qx+1,qy,qz).equals(unrelated))helper.fail("unrelated quart cell changed");RuntimeBiomeJournal.visit(level,snapshot,(sequence,cells)->{for(var saved:cells)BiomeRuntime.restoreOwnedCell(level,saved);});if(!BiomeRuntime.biomeId(level,qx,qy,qz).equals(original))helper.fail("exact rollback did not restore original biome");d.protections().put("test",new WorldPrepSavedData.ProtectedZone("test",area.dimension(),qx<<2,qz<<2,(qx<<2)+3,(qz<<2)+3));var protectedInput=PlanIdentity.capture(level,d,area);var protectedPlan=new WorldPrepSavedData.Plan(areaId,protectedInput,fr.proutlost.worldprep.plan.PlanFingerprint.of("protected").value());var protectedCell=new WorldPrepSavedData.BiomeCell(qx,qz,target,MinecraftTerrainSampler.sample(level,(qx<<2)+2,(qz<<2)+2).signature());var protectedSnapshot=new WorldPrepSavedData.Snapshot(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),area.dimension(),areaId,"BIOMES",protectedPlan.fingerprint);try{WorldPrepRuntime.applyPlannedCell(level,d,area,protectedPlan,protectedSnapshot,protectedCell);helper.fail("sealed protected cell was silently skipped");}catch(IllegalStateException expected){if(!expected.getMessage().contains("protected"))helper.fail("wrong protected failure");}if(!BiomeRuntime.biomeId(level,qx,qy,qz).equals(original))helper.fail("protected biome changed");
        WorldPrepConfig.ENABLED.set(true);UUID filtered=WorldPrepRuntime.enqueue(level,WorldPrepSavedData.Operation.PREVIEW_BIOMES,areaId);
        for(int i=0;i<8&&d.jobs().get(filtered).state()!=WorldPrepSavedData.JobState.COMPLETED;i++)WorldPrepRuntime.tickLevel(level,1000000);
        var filteredPlan=d.plans().get(areaId);if(d.jobs().get(filtered).state()!=WorldPrepSavedData.JobState.COMPLETED||filteredPlan.entryCount!=1)helper.fail("Real preview did not exclude exactly the protected quart");
        try{var manifest=fr.proutlost.worldprep.storage.PagedPlanStore.readManifest(fr.proutlost.worldprep.runtime.RuntimePlanStore.directory(level,filteredPlan.planId));fr.proutlost.worldprep.storage.PagedPlanStore.read(fr.proutlost.worldprep.runtime.RuntimePlanStore.directory(level,filteredPlan.planId),manifest,fr.proutlost.worldprep.runtime.RuntimePlanStore.BIOME_CODEC,planned->{if(planned.quartX()==qx&&planned.quartZ()==qz)helper.fail("Protected quart was sealed into normal preview");});}
        catch(java.io.IOException failed){throw new AssertionError("Filtered preview unavailable",failed);}
        if(!BiomeRuntime.biomeId(level,qx,qy,qz).equals(original))helper.fail("Read-only filtered preview mutated protected biome");helper.succeed();}
    @GameTest(templateNamespace="minecraft",template=TEMPLATE,timeoutTicks=40)
    public static void controlledApplyFailurePreservesCursorAndSnapshot(GameTestHelper helper){var level=helper.getLevel();BlockPos pos=helper.absolutePos(new BlockPos(2,2,2));int qx=QuartPos.fromBlock(pos.getX()),qz=QuartPos.fromBlock(pos.getZ());var d=WorldPrepRuntime.data(level);String areaId="proutlost:failure_"+UUID.randomUUID().toString().replace("-","");var area=new WorldPrepSavedData.Area(areaId,level.dimension().location().toString(),qx<<2,qz<<2,(qx<<2)+7,(qz<<2)+3);d.areas().put(areaId,area);var input=PlanIdentity.capture(level,d,area);var plan=new WorldPrepSavedData.Plan(areaId,input,"failure-fingerprint");String target="minecraft:desert";var first=MinecraftTerrainSampler.sample(level,(qx<<2)+2,(qz<<2)+2);var second=MinecraftTerrainSampler.sample(level,((qx+1)<<2)+2,(qz<<2)+2);plan.cells.put(WorldPrepSavedData.cellKey(qx,qz),new WorldPrepSavedData.BiomeCell(qx,qz,target,first.signature()));plan.cells.put(WorldPrepSavedData.cellKey(qx+1,qz),new WorldPrepSavedData.BiomeCell(qx+1,qz,target,second.signature()+1));d.plans().put(areaId,plan);UUID job=WorldPrepRuntime.enqueue(level,WorldPrepSavedData.Operation.APPLY_BIOMES,areaId);helper.runAfterDelay(3,()->{var failed=d.jobs().get(job);if(failed.state()!=WorldPrepSavedData.JobState.FAILED)helper.fail("job did not enter FAILED");if(failed.cursor()!=1)helper.fail("successful cursor was not preserved");if(failed.error().isBlank()||!failed.error().contains("PREVIEW STALE"))helper.fail("failure context missing");var snapshot=d.snapshots().get(failed.snapshotId());if(snapshot==null||snapshot.entryCount==0||snapshot.pageCount==0)helper.fail("durable rollback snapshot was not preserved");snapshot=new WorldPrepSavedData.Snapshot(snapshot.id,snapshot.jobId,snapshot.planId,snapshot.dimension,snapshot.area,snapshot.pass,snapshot.planFingerprint);d.snapshots().put(snapshot.id,snapshot);var recoveryCells=new java.util.ArrayList<WorldPrepSavedData.SnapshotCell>();RuntimeBiomeJournal.visit(level,snapshot,(sequence,cells)->recoveryCells.addAll(cells));try{java.nio.file.Files.delete(RuntimeBiomeJournal.pagePath(level,snapshot.id,0));}catch(java.io.IOException e){throw new IllegalStateException(e);}UUID rollback=WorldPrepRuntime.enqueueRollback(level,WorldPrepSavedData.Operation.ROLLBACK_BIOMES,areaId,snapshot.id);WorldPrepRuntime.tickLevel(level,1000);if(d.jobs().get(rollback).state()!=WorldPrepSavedData.JobState.FAILED)helper.fail("missing biome journal page did not fail closed");for(var cell:recoveryCells)BiomeRuntime.restoreOwnedCell(level,cell);helper.succeed();});}
    @GameTest(templateNamespace="minecraft",template=TEMPLATE,timeoutTicks=200)
    public static void administratorCommandDispatcherSurface(GameTestHelper helper){
        var server=helper.getLevel().getServer();
        var dispatcher=server.getCommands().getDispatcher();
        var root=dispatcher.getRoot().getChild("proutlost");
        if(root==null||root.getChild("worldprep")==null)helper.fail("WorldPrep command root missing");
        var wp=root.getChild("worldprep");
        for(String name:new String[]{"pos1","pos2","area","registry","analyze","preview","inspect","apply","status","pause","resume","cancel","rollback","override"})
            if(wp.getChild(name)==null)helper.fail("Missing command "+name);
        var area=wp.getChild("area");
        for(String name:new String[]{"create","info","list","delete","mask","trees"})
            if(area.getChild(name)==null)helper.fail("Missing area command "+name);
        var source=server.createCommandSourceStack();
        var ordinary=source.withPermission(0);
        var administrator=source.withPermission(2);
        if(!root.canUse(ordinary))helper.fail("Shared proutlost root must remain available to ordinary players");
        if(wp.canUse(ordinary))helper.fail("Non-OP source can access administrative WorldPrep branch");
        if(!wp.canUse(administrator))helper.fail("OP2 source cannot access WorldPrep branch");
        var location=root.getChild("location");
        if(location==null||location.canUse(ordinary)||!location.canUse(administrator))
            helper.fail("Administrative location branch does not enforce OP2 independently of shared root");
        // Valid command text, parsed only: no terrain or job mutation is performed.
        String apply="proutlost worldprep apply biomes permission_fixture";
        var denied=dispatcher.parse(apply,ordinary);
        if(denied.getContext().getCommand()!=null||!denied.getReader().canRead())
            helper.fail("Non-OP administrative apply parsed as executable");
        var allowed=dispatcher.parse(apply,administrator);
        if(allowed.getContext().getCommand()==null||allowed.getReader().canRead())
            helper.fail("OP2 apply command surface is not executable when fully parsed");
        for(String pass:new String[]{"soils","flora","trees","aquatic"}){
            for(String action:new String[]{"preview","apply","rollback"}){
                String command="proutlost worldprep "+action+" "+pass+" permission_fixture"+(action.equals("rollback")?" 00000000-0000-0000-0000-000000000001":"");
                var deniedEnvironment=dispatcher.parse(command,ordinary);var allowedEnvironment=dispatcher.parse(command,administrator);
                if(deniedEnvironment.getContext().getCommand()!=null||!deniedEnvironment.getReader().canRead())helper.fail("Non-OP environment command parsed as executable");
                if(allowedEnvironment.getContext().getCommand()==null||allowedEnvironment.getReader().canRead())helper.fail("OP2 environment command did not fully parse: "+command);
            }
        }
        for(String tail:new String[]{"preview habitats permission_fixture","override biome minecraft:plains","override palette coastal","override clear","area trees permission_fixture NORMAL"}){
            var deniedEnvironment=dispatcher.parse("proutlost worldprep "+tail,ordinary);var allowedEnvironment=dispatcher.parse("proutlost worldprep "+tail,administrator);
            if(deniedEnvironment.getContext().getCommand()!=null||!deniedEnvironment.getReader().canRead())helper.fail("Non-OP authored policy command parsed as executable");
            if(allowedEnvironment.getContext().getCommand()==null||allowedEnvironment.getReader().canRead())helper.fail("OP2 authored policy command did not fully parse: "+tail);
        }
        for(String[] search:new String[][]{{"biome","minecraft:plains"},{"block","minecraft:stone"},{"entity","minecraft:pig"}}){
            String command="proutlost worldprep registry "+search[0]+" "+search[1];
            var forbidden=dispatcher.parse(command,ordinary);
            if(forbidden.getContext().getCommand()!=null||!forbidden.getReader().canRead())helper.fail("Non-OP registry command parsed as executable");
            try{if(dispatcher.execute(command,administrator)<1)helper.fail("Actual runtime registry search found no known ID");}
            catch(com.mojang.brigadier.exceptions.CommandSyntaxException failure){helper.fail("Registered runtime search command failed: "+failure.getMessage());}
        }
        try{if(dispatcher.execute("proutlost worldprep registry biome proutlost:__unknown_registry_probe__",administrator)!=0)helper.fail("Unknown runtime registry search was reported successful");}
        catch(com.mojang.brigadier.exceptions.CommandSyntaxException failure){helper.fail("Unknown-search refusal did not use the real registry handler");}
        try{if(dispatcher.execute("proutlost worldprep registry biome "+"a".repeat(257),administrator)!=0)helper.fail("Oversized registry search was not refused");}
        catch(com.mojang.brigadier.exceptions.CommandSyntaxException failure){helper.fail("Oversized registry query bypassed the actual bounded handler");}
        server.getCommands().performPrefixedCommand(source,"proutlost worldprep status");
        server.getCommands().performPrefixedCommand(source,"proutlost worldprep area list");
        String legacyId="legacy_mask_"+UUID.randomUUID().toString().replace("-","");
        var commandData=WorldPrepRuntime.data(helper.getLevel());commandData.areas().put(legacyId,new WorldPrepSavedData.Area(legacyId,helper.getLevel().dimension().location().toString(),0,0,3,3));commandData.changed();
        try{if(dispatcher.execute("proutlost worldprep area mask "+legacyId+" LAND",administrator)!=1||commandData.areas().get(legacyId).mask()!=WorldPrepSavedData.AreaMask.LAND||commandData.areas().containsKey("minecraft:"+legacyId))helper.fail("Mask command renamed or failed to find legacy unqualified area ID");}
        catch(com.mojang.brigadier.exceptions.CommandSyntaxException failure){helper.fail("Legacy area mask command did not parse");}
        try{
            if(dispatcher.execute("proutlost worldprep area trees "+legacyId+" SPARSE",administrator)!=1
                ||commandData.areas().get(legacyId).treeMode()!=fr.proutlost.worldprep.ecology.VegetationPlanner.TreeMode.SPARSE
                ||commandData.areas().get(legacyId).mask()!=WorldPrepSavedData.AreaMask.LAND
                ||commandData.areas().containsKey("minecraft:"+legacyId))helper.fail("Tree mode command renamed legacy area or lost its mask");
            if(dispatcher.execute("proutlost worldprep area mask "+legacyId+" COAST",administrator)!=1
                ||commandData.areas().get(legacyId).treeMode()!=fr.proutlost.worldprep.ecology.VegetationPlanner.TreeMode.SPARSE)helper.fail("Mask command lost selected tree mode");
            UUID locked=UUID.randomUUID();commandData.jobs().put(locked,new WorldPrepSavedData.Job(locked,WorldPrepSavedData.Operation.APPLY_TREES,legacyId,WorldPrepSavedData.JobState.PAUSED,0,"",UUID.randomUUID()));
            try{dispatcher.execute("proutlost worldprep area trees "+legacyId+" DENSE",administrator);helper.fail("Tree metadata changed during overlapping destructive job");}
            catch(IllegalStateException expected){if(commandData.areas().get(legacyId).treeMode()!=fr.proutlost.worldprep.ecology.VegetationPlanner.TreeMode.SPARSE)helper.fail("Rejected tree metadata changed");}
            finally{commandData.jobs().remove(locked);}
        }catch(com.mojang.brigadier.exceptions.CommandSyntaxException failure){helper.fail("Legacy tree mode command did not parse");}
        commandData.areas().remove(legacyId);commandData.changed();
        var malformed=dispatcher.parse("proutlost worldprep apply biomes",source);
        if(malformed.getContext().getCommand()!=null)helper.fail("malformed apply unexpectedly parsed as executable");
        // Execute actual authoring handlers, not just command-tree visibility.
        for(String name:new String[]{"pos1","pos2","create","info","list","delete","anchor","zone","protect","unprotect","debug","validate","export"})
            if(location.getChild(name)==null)helper.fail("Missing location command "+name);
        var level=helper.getLevel();var data=WorldPrepRuntime.data(level);
        var fake=net.neoforged.neoforge.common.util.FakePlayerFactory.get(level,
            new com.mojang.authlib.GameProfile(UUID.randomUUID(),"LocationQA"));
        var playerSource=fake.createCommandSourceStack().withPermission(2);
        String id="proutlost:location_qa_"+UUID.randomUUID().toString().replace("-","");
        BlockPos origin=helper.absolutePos(new BlockPos(1,2,1));
        String protectedId="location_qa_"+UUID.randomUUID().toString().replace("-","");
        fake.setPos(origin.getX(),origin.getY(),origin.getZ());worldPrepCommand(dispatcher,playerSource,"pos1");
        fake.setPos(origin.getX()+3,origin.getY(),origin.getZ()+3);worldPrepCommand(dispatcher,playerSource,"pos2");
        if(worldPrepCommand(dispatcher,playerSource,"protect add "+protectedId)!=1||!data.protectedBlock(level.dimension().location().toString(),origin.getX(),origin.getZ()))helper.fail("Actual generic protect handler did not preserve selected terrain");
        if(worldPrepCommand(dispatcher,playerSource,"protect list")!=data.protections().size())helper.fail("Generic protect list did not report actual saved entries");
        var genericRestored=WorldPrepSavedData.load(data.save(new net.minecraft.nbt.CompoundTag(),level.registryAccess()),level.registryAccess());
        if(!genericRestored.protections().get(protectedId).equals(data.protections().get(protectedId)))helper.fail("Generic protection was not persisted exactly");
        try{dispatcher.execute("proutlost worldprep protect remove "+protectedId,ordinary);helper.fail("Non-OP removed actual protected zone");}catch(com.mojang.brigadier.exceptions.CommandSyntaxException expected){}
        worldPrepCommand(dispatcher,playerSource,"protect remove "+protectedId);
        fake.setPos(origin.getX(),origin.getY(),origin.getZ());locationCommand(dispatcher,playerSource,"pos1");
        fake.setPos(origin.getX()+3,origin.getY(),origin.getZ()+3);locationCommand(dispatcher,playerSource,"pos2");
        locationCommand(dispatcher,playerSource,"create "+id+" NARRATIVE_TRANSFORMABLE");
        if(!data.protectedBlock(level.dimension().location().toString(),origin.getX(),origin.getZ()))helper.fail("Transformable authoring default bypassed explicit protection authorization");
        fake.setPos(origin.getX()+1,origin.getY(),origin.getZ()+1);locationCommand(dispatcher,playerSource,"anchor add entrance");
        locationCommand(dispatcher,playerSource,"unprotect");
        if(data.protectedBlock(level.dimension().location().toString(),origin.getX(),origin.getZ()))helper.fail("Explicit location unprotect did not reach actual WorldPrep protection resolver");
        locationCommand(dispatcher,playerSource,"pos1");fake.setPos(origin.getX()+2,origin.getY(),origin.getZ()+2);locationCommand(dispatcher,playerSource,"pos2");
        locationCommand(dispatcher,playerSource,"zone add manual MANUAL_ENVIRONMENT");
        if(!data.protectedBlock(level.dimension().location().toString(),origin.getX()+1,origin.getZ()+1))helper.fail("Manual location zone is not protected after parent unprotect");
        locationCommand(dispatcher,playerSource,"info "+id);locationCommand(dispatcher,playerSource,"list");
        locationCommand(dispatcher,playerSource,"debug on");locationCommand(dispatcher,playerSource,"debug off");
        if(locationCommand(dispatcher,playerSource,"validate")!=1)helper.fail("Valid authored location unexpectedly refused");
        var persisted=data.save(new net.minecraft.nbt.CompoundTag(),level.registryAccess());
        var restored=WorldPrepSavedData.load(persisted,level.registryAccess());
        if(!restored.locations().require(id).equals(data.locations().require(id))||!restored.locations().zones(id).equals(data.locations().zones(id))
                ||!restored.locations().transformationAllowed(id)||!restored.protectionFingerprint().equals(data.protectionFingerprint()))helper.fail("Authored locations/zones/protection were not restored exactly");
        var maskOnly=persisted.copy();maskOnly.remove("locations");maskOnly.remove("locationRegistrySchema");
        var legacyMasks=WorldPrepSavedData.load(maskOnly,level.registryAccess());
        if(!legacyMasks.locations().isEmpty()||!legacyMasks.areas().equals(data.areas()))helper.fail("Earlier schema6 mask-only data lost its masks during registry migration");
        var missingMarked=persisted.copy();missingMarked.remove("locations");
        try{WorldPrepSavedData.load(missingMarked,level.registryAccess());helper.fail("Marked authored registry disappeared without refusal");}catch(IllegalStateException expected){if(!expected.getMessage().contains("marked location registry"))throw expected;}
        var corrupt=persisted.copy();corrupt.getCompound("locations").putInt("schema",99);
        try{WorldPrepSavedData.load(corrupt,level.registryAccess());helper.fail("Corrupt location schema was accepted");}catch(IllegalStateException expected){if(!expected.getMessage().contains("location registry schema"))throw expected;}
        byte[] exported=data.locations().authoringJson();long revision=data.locations().revision();
        String dimension=level.dimension().location().toString().replace(':','_').replace('/','_');
        var path=server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).toAbsolutePath().normalize().resolve("worldprep-exports/locations")
            .resolve(dimension+"-authoring-"+revision+"-"+fr.proutlost.worldprep.plan.PlanFingerprint.ofBytes(exported).value()+".json");
        locationCommand(dispatcher,playerSource,"export");
        locationCommand(dispatcher,playerSource,"zone remove manual");locationCommand(dispatcher,playerSource,"anchor remove entrance");
        locationCommand(dispatcher,playerSource,"protect");locationCommand(dispatcher,playerSource,"delete "+id);
        data.selections().remove(fake.getUUID());data.changed();
        if(data.locations().locations().containsKey(id))helper.fail("Location delete did not remove authored state");
        helper.succeedWhen(()->{
            if(!java.nio.file.Files.isRegularFile(path))helper.fail("Waiting for actual immutable location export publication");
            try{if(!java.util.Arrays.equals(exported,java.nio.file.Files.readAllBytes(path)))helper.fail("Location export did not preserve the captured revision");}
            catch(java.io.IOException e){throw new IllegalStateException(e);}
            System.out.println("WORLDPREP_LOCATION_REAL_HANDLERS_PERSISTENCE_EXPORT_PASS");
        });
    }
    private static int locationCommand(com.mojang.brigadier.CommandDispatcher<net.minecraft.commands.CommandSourceStack> dispatcher,
            net.minecraft.commands.CommandSourceStack source,String command){
        try{return dispatcher.execute("proutlost location "+command,source);}
        catch(com.mojang.brigadier.exceptions.CommandSyntaxException error){throw new IllegalStateException("Actual location handler refused: "+command,error);}
    }
    private static int worldPrepCommand(com.mojang.brigadier.CommandDispatcher<net.minecraft.commands.CommandSourceStack> dispatcher,
            net.minecraft.commands.CommandSourceStack source,String command){
        try{return dispatcher.execute("proutlost worldprep "+command,source);}
        catch(com.mojang.brigadier.exceptions.CommandSyntaxException error){throw new IllegalStateException("Actual WorldPrep handler refused: "+command,error);}
    }
    @GameTest(templateNamespace="minecraft",template=TEMPLATE,timeoutTicks=200,batch="worldprep_audit")
    public static void analysisIsIncrementalReadOnlyAndRollbackUsesExactSnapshot(GameTestHelper helper){
        var level=helper.getLevel();
        var data=WorldPrepRuntime.data(level);
        boolean priorEnabled=WorldPrepConfig.ENABLED.get();
        WorldPrepConfig.ENABLED.set(true);
        // A distinct adjacent quart avoids the older protected-cell fixture.
        BlockPos pos=helper.absolutePos(new BlockPos(6,2,2));
        int qx=QuartPos.fromBlock(pos.getX()),qz=QuartPos.fromBlock(pos.getZ()),qy=QuartPos.fromBlock(pos.getY());
        String areaId="proutlost:audit_"+UUID.randomUUID().toString().replace("-","");
        var area=new WorldPrepSavedData.Area(areaId,level.dimension().location().toString(),qx<<2,qz<<2,(qx<<2)+7,(qz<<2)+3);
        data.areas().put(areaId,area);data.changed();
        if(data.protectedAt(area.dimension(),qx,qz))helper.fail("Audit quart unexpectedly intersects another fixture protection");
        String original=BiomeRuntime.biomeId(level,qx,qy,qz);
        String target=original.equals("minecraft:desert")?"minecraft:plains":"minecraft:desert";
        int waterX=(qx<<2)+2,waterZ=(qz<<2)+2;
        int waterY=Math.max(level.getSeaLevel()+4,fr.proutlost.worldprep.world.ServerExistingChunkAccess.requireSample(level,waterX,waterZ).getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE,waterX,waterZ)+4);
        var waterPos=new BlockPos(waterX,waterY,waterZ);var bedPos=waterPos.below();
        var beforeWater=level.getBlockState(waterPos);var beforeBed=level.getBlockState(bedPos);
        level.setBlock(bedPos,net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(),2);
        level.setBlock(waterPos,net.minecraft.world.level.block.Blocks.WATER.defaultBlockState(),2);
        int beforePlans=data.plans().size(),beforeSnapshots=data.snapshots().size();
        UUID analysis=WorldPrepRuntime.enqueue(level,WorldPrepSavedData.Operation.ANALYZE,areaId);
        WorldPrepRuntime.tickLevel(level,1);
        if(data.jobs().get(analysis).state()!=WorldPrepSavedData.JobState.RUNNING||data.jobs().get(analysis).cursor()!=1
                ||data.analyses().get(analysis).samples!=1||data.analyses().get(analysis).complete)
            helper.fail("Real ANALYZE did not retain exactly one sampled cell");
        if(data.plans().size()!=beforePlans||data.snapshots().size()!=beforeSnapshots||!BiomeRuntime.biomeId(level,qx,qy,qz).equals(original))
            helper.fail("ANALYZE mutated terrain, preview authority or rollback snapshots");
        var source=level.getServer().createCommandSourceStack().withPermission(2);
        level.getServer().getCommands().performPrefixedCommand(source,"proutlost worldprep pause");
        WorldPrepRuntime.tickLevel(level,1);
        if(data.jobs().get(analysis).state()!=WorldPrepSavedData.JobState.PAUSED||data.analyses().get(analysis).samples!=1)
            helper.fail("Paused ANALYZE advanced its persisted prefix");
        var loaded=WorldPrepSavedData.load(data.save(new net.minecraft.nbt.CompoundTag(),level.registryAccess()),level.registryAccess());
        data.analyses().put(analysis,loaded.analyses().get(analysis));data.jobs().put(analysis,loaded.jobs().get(analysis));data.changed();
        level.getServer().getCommands().performPrefixedCommand(source,"proutlost worldprep resume");
        WorldPrepRuntime.tickLevel(level,1);
        if(data.jobs().get(analysis).state()!=WorldPrepSavedData.JobState.COMPLETED||data.jobs().get(analysis).cursor()!=2
                ||data.analyses().get(analysis).samples!=2||!data.analyses().get(analysis).complete)
            helper.fail("ANALYZE failed to resume the exact SavedData prefix");
        var measured=data.analyses().get(analysis);
        if(measured.terrainSamples!=2||!Double.isFinite(measured.noiseSum)||measured.noiseMin>measured.noiseMax)
            helper.fail("ANALYZE did not persist real local inland-water/noise observations");
        var localInland=MinecraftTerrainSampler.inlandWater(level,(qx<<2)+2,(qz<<2)+2);
        if(localInland.observedDistance()!=0||localInland.noWater())
            helper.fail("Local inland-water observation missed the real above-marine water fixture");
        String analyzedDigest=data.analyses().get(analysis).terrainDigest;
        UUID repeated=WorldPrepRuntime.enqueue(level,WorldPrepSavedData.Operation.ANALYZE,areaId);
        WorldPrepRuntime.tickLevel(level,1);WorldPrepRuntime.tickLevel(level,1);
        if(!data.analyses().get(repeated).complete||!analyzedDigest.equals(data.analyses().get(repeated).terrainDigest))
            helper.fail("Unchanged real terrain produced a different ANALYZE digest");
        UUID stale=WorldPrepRuntime.enqueue(level,WorldPrepSavedData.Operation.ANALYZE,areaId);
        WorldPrepRuntime.tickLevel(level,1);
        String changedProtection="analysis_stale_"+stale;
        data.protections().put(changedProtection,new WorldPrepSavedData.ProtectedZone(changedProtection,area.dimension(),area.minX(),area.minZ(),area.maxX(),area.maxZ()));data.changed();
        WorldPrepRuntime.tickLevel(level,1);
        if(data.jobs().get(stale).state()!=WorldPrepSavedData.JobState.FAILED||data.jobs().get(stale).cursor()!=1||data.analyses().get(stale).samples!=1)
            helper.fail("ANALYZE accepted changed semantic inputs or restarted its prefix");
        data.protections().remove(changedProtection);data.changed();
        if(data.plans().size()!=beforePlans||data.snapshots().size()!=beforeSnapshots||!BiomeRuntime.biomeId(level,qx,qy,qz).equals(original))
            helper.fail("Completed or refused ANALYZE changed the world or preview/snapshot state");
        if(!level.getBlockState(waterPos).is(net.minecraft.world.level.block.Blocks.WATER)||!level.getBlockState(bedPos).is(net.minecraft.world.level.block.Blocks.STONE))
            helper.fail("ANALYZE mutated the real inland-water fixture");
        level.setBlock(waterPos,beforeWater,2);level.setBlock(bedPos,beforeBed,2);
        // A complete wet quart beside a deliberately dry quart proves actual footprint filtering.
        int maskY=level.getSeaLevel()+4;
        for(int mz=area.minZ();mz<=area.maxZ();mz++)for(int mx=area.minX();mx<=area.maxX();mx++)maskY=Math.max(maskY,fr.proutlost.worldprep.world.ServerExistingChunkAccess.requireSample(level,mx,mz).getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE,mx,mz)+4);
        var maskFixture=new java.util.LinkedHashMap<BlockPos,net.minecraft.world.level.block.state.BlockState>();
        for(int mz=area.minZ();mz<=area.maxZ();mz++)for(int mx=area.minX();mx<=area.maxX();mx++){
            var bed=new BlockPos(mx,maskY-1,mz);var top=bed.above();maskFixture.put(bed,level.getBlockState(bed));maskFixture.put(top,level.getBlockState(top));
            level.setBlock(bed,net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(),2);level.setBlock(top,(mx<area.minX()+4?net.minecraft.world.level.block.Blocks.WATER:net.minecraft.world.level.block.Blocks.AIR).defaultBlockState(),2);
        }
        for(var mask:WorldPrepSavedData.AreaMask.values()){
            boolean wet=MinecraftTerrainSampler.matchesMask(level,mask,area.minX(),area.minZ(),4,4),dry=MinecraftTerrainSampler.matchesMask(level,mask,area.minX()+4,area.minZ(),4,4);
            if(mask==WorldPrepSavedData.AreaMask.MARINE&&(!wet||dry)||mask==WorldPrepSavedData.AreaMask.LAND&&(wet||!dry)||(mask==WorldPrepSavedData.AreaMask.FULL_AREA||mask==WorldPrepSavedData.AreaMask.COAST||mask==WorldPrepSavedData.AreaMask.LAND_AND_COAST)&&(!wet||!dry))helper.fail("Real area mask did not distinguish the wet and dry footprints");
        }
        var dispatcher=level.getServer().getCommands().getDispatcher();
        try{if(dispatcher.execute("proutlost worldprep area mask "+areaId+" MARINE",source)!=1)helper.fail("Namespaced mask command failed");}
        catch(com.mojang.brigadier.exceptions.CommandSyntaxException failure){helper.fail("Namespaced area ID did not parse in mask command");}
        var maskPreview=WorldPrepRuntime.enqueue(level,WorldPrepSavedData.Operation.PREVIEW_BIOMES,areaId);WorldPrepRuntime.tickLevel(level,16);WorldPrepRuntime.tickLevel(level,16);
        if(data.jobs().get(maskPreview).state()!=WorldPrepSavedData.JobState.COMPLETED||data.plans().get(areaId).entryCount!=1)helper.fail("Sealed MARINE preview did not exclude the whole dry quart");
        var maskedInput=data.plans().get(areaId).input;
        data.areas().put(areaId,area.withMask(WorldPrepSavedData.AreaMask.LAND));data.changed();
        try{PlanIdentity.requireCurrent(maskedInput,PlanIdentity.capture(level,data,data.areas().get(areaId)));helper.fail("Changed mask did not stale the old preview identity");}catch(IllegalStateException expected){}
        data.areas().put(areaId,area);data.jobs().remove(maskPreview);data.plans().remove(areaId);maskFixture.forEach((position,state)->level.setBlock(position,state,2));data.changed();
        UUID[] jobs=new UUID[4];UUID[] snapshot=new UUID[1];UUID[] appliedPlan=new UUID[1];
        jobs[0]=WorldPrepRuntime.enqueue(level,WorldPrepSavedData.Operation.PREVIEW_BIOMES,areaId);
        WorldPrepRuntime.tickLevel(level,1); // A real two-column preview must scan only one column.
        if(data.jobs().get(jobs[0]).state()!=WorldPrepSavedData.JobState.RUNNING||data.jobs().get(jobs[0]).cursor()!=1)
            helper.fail("BIOMES preview ignored the one-cell budget");
        if(data.plans().containsKey(areaId))helper.fail("Unsealed preview became authoritative");
        helper.startSequence()
            .thenWaitUntil(()->{
                if(data.jobs().get(jobs[0]).state()!=WorldPrepSavedData.JobState.COMPLETED)
                    helper.fail("Completed/refused ANALYZE prevents the next real preview from completing");
            })
            .thenExecute(()->{
                if(!BiomeRuntime.biomeId(level,qx,qy,qz).equals(original))
                    helper.fail("Real preview modified the biome");
                var input=PlanIdentity.capture(level,data,area);
                var sample=MinecraftTerrainSampler.sample(level,(qx<<2)+2,(qz<<2)+2);
                var plan=new WorldPrepSavedData.Plan(areaId,input,
                    fr.proutlost.worldprep.plan.PlanFingerprint.of("audit-authored-preview").value());
                plan.cells.put(WorldPrepSavedData.cellKey(qx,qz),
                    new WorldPrepSavedData.BiomeCell(qx,qz,target,sample.signature()));
                data.plans().put(areaId,plan);data.changed();
                // The normal runtime seals this tiny plan and publishes its real ownership journal.
                jobs[1]=WorldPrepRuntime.enqueue(level,WorldPrepSavedData.Operation.APPLY_BIOMES,areaId);
            })
            .thenWaitUntil(()->{
                if(data.jobs().get(jobs[1]).state()!=WorldPrepSavedData.JobState.COMPLETED)
                    helper.fail("Audit biome apply did not complete");
            })
            .thenExecute(()->{
                if(!BiomeRuntime.biomeId(level,qx,qy,qz).equals(target))
                    helper.fail("Real biome apply was not observed");
                snapshot[0]=data.jobs().get(jobs[1]).snapshotId();
                appliedPlan[0]=data.plans().get(areaId).planId;
                if(snapshot[0]==null||appliedPlan[0]==null||data.snapshots().get(snapshot[0]).entryCount==0)
                    helper.fail("Applied biome has no durable exact snapshot");
                jobs[2]=WorldPrepRuntime.enqueue(level,WorldPrepSavedData.Operation.PREVIEW_BIOMES,areaId);
            })
            .thenWaitUntil(()->{
                if(data.jobs().get(jobs[2]).state()!=WorldPrepSavedData.JobState.COMPLETED)
                    helper.fail("Second real preview did not complete");
            })
            .thenExecute(()->{
                if(appliedPlan[0].equals(data.plans().get(areaId).planId))
                    helper.fail("Second preview did not replace the current plan identity");
                if(!BiomeRuntime.biomeId(level,qx,qy,qz).equals(target))
                    helper.fail("Second preview unexpectedly changed the real biome");
                data.areas().put(areaId,area.withMask(WorldPrepSavedData.AreaMask.MARINE));data.changed(); // Current mask cannot revoke exact historical rollback ownership.
                jobs[3]=WorldPrepRuntime.enqueueRollback(level,WorldPrepSavedData.Operation.ROLLBACK_BIOMES,areaId,snapshot[0]);
            })
            .thenWaitUntil(()->{
                if(data.jobs().get(jobs[3]).state()!=WorldPrepSavedData.JobState.COMPLETED)
                    helper.fail("Historical exact snapshot rollback was blocked by the newer preview");
            })
            .thenExecute(()->{
                if(!BiomeRuntime.biomeId(level,qx,qy,qz).equals(original))
                    helper.fail("Historical snapshot rollback did not restore the real biome");
                for(var id:new UUID[]{analysis,repeated,stale}){data.jobs().remove(id);data.analyses().remove(id);}
                for(var id:jobs)data.jobs().remove(id);
                data.snapshots().remove(snapshot[0]);data.plans().remove(areaId);data.areas().remove(areaId);data.changed();
                WorldPrepConfig.ENABLED.set(priorEnabled);
            })
            .thenSucceed();
    }
    private WorldPrepBiomeGameTests(){}
}
