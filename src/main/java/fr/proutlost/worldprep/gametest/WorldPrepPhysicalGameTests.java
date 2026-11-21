package fr.proutlost.worldprep.gametest;

import fr.proutlost.worldprep.physical.*;
import fr.proutlost.worldprep.persistence.WorldPrepSavedData;
import fr.proutlost.worldprep.runtime.WorldPrepRuntime;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.level.block.*;
import net.neoforged.neoforge.gametest.*;
import java.nio.file.Files;
import java.util.*;

/** Native tests for an operator-run disposable GameTest server only. Not run on the ecology instance. */
@GameTestHolder("proutlost")
@PrefixGameTestTemplate(false)
public final class WorldPrepPhysicalGameTests {
    /** Operator-staged private QA only: marker must explicitly contain
     * physicalSurveyScope=SYNTHETIC_QA and WorldPrep enabled=false. Exercises
     * the real command/LoadedCell/Anvil/export route, not production authoring. */
    @GameTest(templateNamespace="proutlost",template="qualification/arena",timeoutTicks=400,batch="p008-physical-survey")
    public static void observedPhysicalExportUsesRealLoadedAndAnvilWitnesses(GameTestHelper h)throws Exception {
        prepare(h);var level=h.getLevel();var server=level.getServer();
        h.assertTrue(!fr.proutlost.worldprep.WorldPrepConfig.ENABLED.get(),"Operator must disable WorldPrep mutations in this isolated survey fixture");
        var world=server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).toAbsolutePath().normalize();
        fr.proutlost.worldprep.runtime.CandidateGuard.requireCandidate(level);
        var marker=world.resolve("worldprep-candidate.json");String markerText=Files.readString(marker);
        var markerJson=com.google.gson.JsonParser.parseString(markerText).getAsJsonObject();
        h.assertTrue(markerJson.has("physicalSurveyScope")&&markerJson.get("physicalSurveyScope").getAsString().equals("SYNTHETIC_QA"),"Explicit TEST_ONLY survey scope marker required");
        UUID survey=UUID.randomUUID();var low=fixturePos(h,1,0,1);var high=fixturePos(h,3,2,3);
        var dimension=new fr.proutlost.contract.director.Id(level.dimension().location().toString());
        var box=new fr.proutlost.contract.director.Bounds(dimension,new fr.proutlost.contract.director.BlockPos(low.getX(),low.getY(),low.getZ()),new fr.proutlost.contract.director.BlockPos(high.getX(),high.getY(),high.getZ()));
        var request=new com.google.gson.JsonObject();request.addProperty("schema_version",1);request.addProperty("survey_id",survey.toString());
        request.addProperty("source_world","proutlost:qa_observed_cells");request.addProperty("source_build","proutlost:p008_observed_"+survey);
        request.addProperty("revision","qa-r1");request.addProperty("expected_seed",Long.toString(level.getSeed()));
        request.addProperty("candidate_marker_sha256",PhysicalStore.sha(markerText));request.addProperty("scope","SYNTHETIC_QA");
        var target=new com.google.gson.JsonObject();target.addProperty("location_id","proutlost:qa_observed_support/"+survey);
        target.addProperty("source_ref","proutlost:observed_existing_cells");target.addProperty("origin","OBSERVED_EXISTING_CELLS");
        target.add("module_directory",com.google.gson.JsonNull.INSTANCE);target.add("module_record_sha256",com.google.gson.JsonNull.INSTANCE);
        target.add("tags",new com.google.gson.JsonArray());var geometry=new com.google.gson.JsonArray();
        geometry.add(com.google.gson.JsonParser.parseString(fr.proutlost.contract.director.StrictCodec.encode(box)));target.add("geometry",geometry);
        var targets=new com.google.gson.JsonArray();targets.add(target);request.add("targets",targets);
        var inputDirectory=world.resolve("worldprep-surveys/requests");Files.createDirectories(inputDirectory);
        String filename="qa-p008-survey-"+survey+".json";Files.writeString(inputDirectory.resolve(filename),request.toString(),java.nio.file.StandardOpenOption.CREATE_NEW);
        // The harness owns its private arena save. The collector itself never saves.
        level.save(null,true,false);
        h.runAfterDelay(4,()->{
            try{
                var witness=fr.proutlost.integration.physical.NativeLoadedAndDiskCellWitness.capture(server,List.of(box));
                h.assertTrue(witness.cells()==27&&witness.loadedDigest().equals(witness.diskDigest()),"Actual loaded/Anvil witness differs");
                var source=server.createCommandSourceStack().withLevel(level).withPermission(4).withSuppressedOutput();
                int result=server.getCommands().getDispatcher().execute("proutlost worldprep physical survey-export "+filename,source);
                h.assertTrue(result==1,"Real registered survey command refused the staged request");
                var file=world.resolve("worldprep-surveys/exports").resolve(survey.toString()).resolve("catalog.json");
                String json=Files.readString(file);var exported=fr.proutlost.contract.director.StrictCodec.decode(json,fr.proutlost.contract.director.CatalogSnapshot.class);
                fr.proutlost.contract.director.CatalogValidation.requireSealed(exported);
                var entry=exported.entries().getFirst();
                h.assertTrue(entry.geometry().equals(List.of(box))&&entry.capabilities().isEmpty()&&entry.presentation_channels().isEmpty()&&entry.anchors().isEmpty(),"Actual survey acquired unprovided semantics or lost geometry");
                h.assertTrue(entry.validation_evidence().stream().filter(e->e.validator_id().equals(fr.proutlost.integration.physical.NativeLoadedCellWitness.VALIDATOR)&&e.digest().equals(witness.loadedDigest())).count()==1,"Actual D0 loaded-state witness not retained");
                var retained=NativeObservedPhysicalCatalogExport.export(level,filename);
                h.assertTrue(retained.status().startsWith("RETAINED_SNAPSHOT_VERIFIED")&&retained.sha256().equals(PhysicalStore.sha(json)),"Actual retained immutable export readback differs");
                var absent=new fr.proutlost.contract.director.Bounds(dimension,new fr.proutlost.contract.director.BlockPos(20000000,64,20000000),new fr.proutlost.contract.director.BlockPos(20000000,65,20000000));
                h.assertTrue(level.getChunkSource().getChunkNow(20000000>>4,20000000>>4)==null,"Unloaded refusal fixture must be absent");
                boolean refused=false;try{fr.proutlost.integration.physical.NativeLoadedAndDiskCellWitness.capture(server,List.of(absent));}catch(java.io.IOException expected){refused=true;}
                h.assertTrue(refused&&level.getChunkSource().getChunkNow(20000000>>4,20000000>>4)==null,"Collector requested/generated a missing chunk");
                System.out.println("P008_NATIVE_OBSERVED_PHYSICAL_EXPORT_PASS survey="+survey+" cells=27 source=SYNTHETIC_QA realCommand=true realAnvil=true authorSemantics=false imported=false");
                h.succeed();
            }catch(Exception failure){h.testInfo.fail(failure);}
        });
    }
    /** Template coordinates: the pinned structure block places the template at helper Y + 1. */
    private static BlockPos fixturePos(GameTestHelper h,int x,int y,int z) {
        return h.absolutePos(new BlockPos(x,Math.addExact(y,1),z));
    }
    private static WorldPrepSavedData.Area area(GameTestHelper h) {
        var a=fixturePos(h,0,0,0);var b=fixturePos(h,11,7,11);
        return new WorldPrepSavedData.Area("proutlost:physical_test",h.getLevel().dimension().location().toString(),Math.min(a.getX(),b.getX()),Math.min(a.getZ(),b.getZ()),Math.max(a.getX(),b.getX()),Math.max(a.getZ(),b.getZ()));
    }
    private static PhysicalStore store(GameTestHelper h)throws Exception {
        return new PhysicalStore(Files.createTempDirectory(NativePhysicalRuntime.root(h.getLevel()),"test-"));
    }
    private static void prepare(GameTestHelper h)throws Exception {
        Files.createDirectories(NativePhysicalRuntime.root(h.getLevel()));
        // Assert the actual arena contract before invoking either engine. Never repair terrain here.
        var level=h.getLevel();
        for(int x=0;x<12;x++)for(int z=0;z<12;z++) {
            var floor=fixturePos(h,x,0,z);
            fr.proutlost.worldprep.world.ServerExistingChunkAccess.requireBlock(level,area(h),floor.getX(),floor.getY(),floor.getZ());
            h.assertTrue(level.getBlockState(floor).is(Blocks.STONE),"Fixture stone foundation missing at "+floor+" (templateY0/helperY1)");
            for(int y=1;y<8;y++) {
                var empty=fixturePos(h,x,y,z);
                h.assertTrue(level.getBlockState(empty).isAir(),"Fixture clearance occupied at "+empty+" (templateY"+y+")");
            }
        }
        var origin=fixturePos(h,5,1,5);var spawn=h.getLevel().getSharedSpawnPos();
        h.assertTrue(Math.abs((long)origin.getX()-spawn.getX())>20||Math.abs((long)origin.getZ()-spawn.getZ())>20,
            "Run fixture at a fixed existing-chunk location outside the spawn reservation; spawn is never moved");
    }
    /** Separate operator-staged pipeline qualification, never part of the ten ordinary tests.
     * Requires real completed ecology, two-copy paged backup and DEV D2 provider. No receipt
     * is manufactured here. Missing inputs or a paused engine fail the named native test.
     */
    @GameTest(templateNamespace="proutlost",template="qualification/arena",timeoutTicks=12000,batch="cb201-pipeline")
    public static void automaticQualificationWithRealInputsAndD2(GameTestHelper h)throws Exception {
        h.assertTrue(!net.neoforged.fml.loading.FMLLoader.isProduction(),"Pipeline qualification requires DEV");
        var providers=java.util.ServiceLoader.load(fr.proutlost.worldprep.director.CatalogImportProbe.class).stream().limit(2).toList();
        h.assertTrue(providers.size()==1,"Exactly one real DEV D2 provider required; never bundled in production");
        var level=h.getLevel();
        fr.proutlost.worldprep.physical.NativeQualificationRuntime.start(level);
        awaitPipeline(h,()->{});
    }
    /** Fixed centers with native halos fully inside the operator's inspected -3..3 square. */
    @GameTest(templateNamespace="proutlost",template="qualification/arena",timeoutTicks=500,batch="cb201-window")
    public static void verifiedWindowTransitionsBetweenUnloadedCenters(GameTestHelper h) {
        var level=h.getLevel();
        h.assertTrue(!NativeQualificationRuntime.active(level)&&!NativePhysicalRuntime.hasActive(level),"Another physical owner is active");
        h.assertTrue(level.getChunkSource().getChunkNow(-2,-2)==null&&level.getChunkSource().getChunkNow(2,-2)==null,"Both fixed centers must initially be unloaded; operator fixture prerequisite");
        var window=new NativeChunkWindow(level);
        h.testInfo.addListener(new GameTestListener(){
            public void testStructureLoaded(GameTestInfo t){}
            public void testPassed(GameTestInfo t,GameTestRunner r){window.close();}
            public void testFailed(GameTestInfo t,GameTestRunner r){window.close();}
            public void testAddedForRerun(GameTestInfo t,GameTestInfo next,GameTestRunner r){window.close();}
        });
        int[] phase={0};
        h.succeedWhen(()->{
            try {
                int cx=phase[0]==0?-2:2;
                if(!window.blocks(cx<<4,-2<<4,cx<<4,-2<<4)){h.assertTrue(false,"Waiting for verified lease");return;}
                h.assertTrue(level.getChunkSource().getChunkNow(cx,-2)!=null,"Verified center not accessible");
                h.assertTrue(window.ownedLeases()==1,"Window grew beyond one center");
                window.release();h.assertTrue(window.ownedLeases()==0,"Owned lease was not released");
                if(phase[0]++==0){h.assertTrue(false,"First center released; waiting for distant center");return;}
                window.close();h.assertTrue(window.ownedLeases()==0,"Final lease cleanup failed");
            } catch(net.minecraft.gametest.framework.GameTestAssertException pending){throw pending;}
            catch(RuntimeException failure){window.close();throw failure;}
        });
    }
    /** Operator stages a separate intact grassland anchor within ONE FULL chunk, away from arena. */
    @GameTest(templateNamespace="proutlost",template="qualification/arena",timeoutTicks=16000,batch="cb201-natural-pipeline")
    public static void automaticNaturalSiteWithDiskAccessAndD2(GameTestHelper h)throws Exception {
        h.assertTrue(!net.neoforged.fml.loading.FMLLoader.isProduction(),"Natural qualification requires DEV");
        var level=h.getLevel();var config=level.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("worldprep-qualification.json");
        if(Files.size(config)>4096)throw new IllegalStateException("Request bound");
        var json=com.google.gson.JsonParser.parseString(Files.readString(config)).getAsJsonObject();
        var batch=UUID.fromString(json.get("batch").getAsString());
        var catalog=NativePhysicalRuntime.root(level).resolve("qualification-"+batch).resolve("catalog.json");
        NativeQualificationRuntime.start(level);
        awaitPipeline(h,()->{
            try {
                if(Files.size(catalog)>2*1024*1024)throw new IllegalStateException("Catalogue bound");
                var snapshot=fr.proutlost.contract.director.StrictCodec.decode(Files.readString(catalog),fr.proutlost.contract.director.CatalogSnapshot.class);
                var natural=snapshot.entries().stream().filter(e->e.source_type()==fr.proutlost.contract.director.Vocabulary.SourceType.worldprep_natural_site).toList();
                h.assertTrue(natural.size()==1,"Exactly one admitted natural walking site required");
                h.assertTrue(natural.getFirst().anchors().size()==1&&natural.getFirst().structure_instance_id()==null&&natural.getFirst().capabilities().isEmpty(),"Physical anchor without invented starts/capabilities");
            } catch(java.io.IOException failure){throw new IllegalStateException("Actual catalogue read failed",failure);}
        });
    }
    /** Scheduled callbacks are caught by GameTestInfo.tickInternal, unlike sequence predicates. */
    private static void awaitPipeline(GameTestHelper h,Runnable verified) {
        Runnable[] step={null};
        step[0]=()->{
            if(h.testInfo.isDone())return;
            if(PipelineTestPoll.step(()->NativeQualificationRuntime.status(h.getLevel()),verified,h::succeed,h.testInfo::fail))return;
            h.runAfterDelay(1,DistinctTickCallback.wrap(step[0]));
        };
        h.runAfterDelay(1,DistinctTickCallback.wrap(step[0]));
    }
    @GameTest(templateNamespace="proutlost",template="qualification/arena",timeoutTicks=200,batch="cb201-physical")
    public static void vanillaIglooExactNbtResumeAndRollback(GameTestHelper h)throws Exception {
        prepare(h);var level=h.getLevel();h.setBiome(net.minecraft.world.level.biome.Biomes.SNOWY_PLAINS);
        var plan=NativeStructurePass.plan(level,area(h),"minecraft:igloo/top",fixturePos(h,2,1,2),Rotation.NONE,Mirror.NONE,"igloo-real-v1");
        h.assertTrue(plan.cells().stream().filter(c->c.blockEntity()!=null).count()==3,"Actual igloo template must have three supported BlockEntities");
        var store=store(h);var pass=new NativeStructurePass(plan,store);pass.step(level,64);
        pass=new NativeStructurePass(store.read("plan",NativeStructurePass.Plan.class),store);
        while(!pass.done())pass.step(level,64);
        for(var cell:plan.cells())NativeStructurePass.verifyLiveCell(level,cell);
        var furnace=plan.cells().stream().filter(c->c.blockEntity()!=null&&c.blockEntity().contains("minecraft:furnace")).findFirst().orElseThrow();
        var pos=new BlockPos(furnace.x(),furnace.y(),furnace.z());
        var entity=(net.minecraft.world.level.block.entity.FurnaceBlockEntity)level.getBlockEntity(pos);
        entity.setItem(0,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND));
        var resumed=new NativeStructurePass(plan,store);
        boolean changedNbtRefused=false;
        try{resumed.step(level,1);}catch(java.io.IOException expected){
            if(!"THIRD_STATE BlockEntity".equals(expected.getMessage()))throw expected;
            changedNbtRefused=true;
        }
        h.assertTrue(changedNbtRefused,"Changed furnace inventory was not refused");
        // Fixture-only restoration: setItem also changes CookTimeTotal, so clearing the slot is insufficient.
        entity.loadWithComponents(net.minecraft.nbt.TagParser.parseTag(furnace.blockEntity()),level.registryAccess());
        entity.setChanged();
        NativeStructurePass.verifyLiveCell(level,furnace);
        pass.rollback();while(!pass.done())pass.step(level,64);
        for(var cell:plan.cells())h.assertTrue(level.getBlockEntity(new BlockPos(cell.x(),cell.y(),cell.z()))==null,"Rollback retained owned BlockEntity");
        h.succeed();
    }
    @GameTest(templateNamespace="proutlost",template="qualification/arena",timeoutTicks=400,batch="cb201-physical")
    public static void vanillaIglooBlockEntitiesSavedAndReadFromAnvil(GameTestHelper h)throws Exception {
        prepare(h);var level=h.getLevel();h.setBiome(net.minecraft.world.level.biome.Biomes.SNOWY_PLAINS);
        var plan=NativeStructurePass.plan(level,area(h),"minecraft:igloo/top",fixturePos(h,2,1,2),Rotation.NONE,Mirror.NONE,"igloo-disk-v1");
        var store=store(h);var pass=new NativeStructurePass(plan,store);while(!pass.done())pass.step(level,64);
        level.save(null,true,false);var audit=new NativeStructureAudit(plan,store);
        h.succeedWhen(()->{try {h.assertTrue(audit.step(level),"Awaiting actual Anvil BlockEntity readback");}
            catch(java.io.IOException e){throw new IllegalStateException(e);}});
    }
    @GameTest(templateNamespace="proutlost",template="qualification/arena",timeoutTicks=100,batch="cb201-physical")
    public static void exactTemplateRotationMirrorInterruptionResumeRollback(GameTestHelper h)throws Exception {
        var level=h.getLevel();
        try {
            prepare(h);
            for(var rotation:Rotation.values())for(var mirror:Mirror.values()) {
                var plan=NativeStructurePass.plan(level,area(h),"proutlost:qualification/platform",fixturePos(h,5,1,5),rotation,mirror,"fixture-v1");
                var store=store(h);var pass=new NativeStructurePass(plan,store);
                pass.step(level,1);h.assertTrue(pass.cursor()==1,"Tick budget ignored");
                pass=new NativeStructurePass(store.read("plan",NativeStructurePass.Plan.class),store);
                while(!pass.done())pass.step(level,2);
                for(var c:plan.cells())h.assertTrue(fr.proutlost.worldprep.runtime.BlockMutationRuntime.stateString(level.getBlockState(new BlockPos(c.x(),c.y(),c.z()))).equals(c.after()),"Exact transformed cell mismatch");
                pass.rollback();while(!pass.done())pass.step(level,2);
                for(var c:plan.cells())h.assertTrue(level.getBlockState(new BlockPos(c.x(),c.y(),c.z())).isAir(),"Rollback missed cell");
            }
        } finally { /* Fixture coordinates and spawn stay unchanged. */ }
        h.succeed();
    }
    @GameTest(templateNamespace="proutlost",template="qualification/arena",timeoutTicks=100,batch="cb201-physical")
    public static void opaqueBoundsExistingTerrainAndMissingChunkRefused(GameTestHelper h)throws Exception {
        var level=h.getLevel();
        try {
            prepare(h);var pos=fixturePos(h,5,1,5);
            reject(h,()->NativeStructurePass.plan(level,area(h),"minecraft:village",pos,Rotation.NONE,Mirror.NONE,"fixture"));
            reject(h,()->NativeStructurePass.plan(level,area(h),"proutlost:qualification/platform",fixturePos(h,11,1,11),Rotation.NONE,Mirror.NONE,"fixture"));
            var plan=NativeStructurePass.plan(level,area(h),"proutlost:qualification/platform",pos,Rotation.NONE,Mirror.NONE,"fixture");
            level.setBlock(pos,Blocks.DIAMOND_BLOCK.defaultBlockState(),18);
            reject(h,()->new NativeStructurePass(plan,store(h)).step(level,1));
            reject(h,()->NativeStructurePass.plan(level,area(h),"proutlost:qualification/platform",pos,Rotation.NONE,Mirror.NONE,"fixture"));
            level.setBlock(pos,Blocks.AIR.defaultBlockState(),18);
            reject(h,()->fr.proutlost.worldprep.world.ServerExistingChunkAccess.requireChunk(level,1_500_000,1_500_000));
        } finally { /* Fixture coordinates and spawn stay unchanged. */ }
        h.succeed();
    }
    @GameTest(templateNamespace="proutlost",template="qualification/arena",timeoutTicks=100,batch="cb201-physical")
    public static void nativeFaunaSpawnResumeAndDeadIssuedEntityNotReplaced(GameTestHelper h)throws Exception {
        var level=h.getLevel();
        UUID animal=null;
        try {
            prepare(h);var pos=fixturePos(h,5,1,5);
            String biome=level.getBiome(pos).unwrapKey().orElseThrow().location().toString();
            var rule=new NativeFaunaPass.Rule("minecraft:cow",Set.of(biome),Set.of("minecraft:stone"),-64,320,-10,10,0,256,256,64,2,true);
            var plan=NativeFaunaPass.plan(level,area(h),rule,List.of(pos),"fixture-v1");var store=store(h);
            var pass=new NativeFaunaPass(plan,store);pass.step(level,1);animal=plan.animals().getFirst().uuid();
            h.assertTrue(level.getEntity(animal) instanceof net.minecraft.world.entity.Mob,"Native spawn absent");
            h.assertTrue(((net.minecraft.world.entity.Mob)level.getEntity(animal)).isPersistenceRequired(),"Persistence policy ignored");
            new NativeFaunaPass(store.read("plan",NativeFaunaPass.Plan.class),store).step(level,1);
            h.assertTrue(level.getEntity(animal)!=null,"Resume lost owned entity");
            level.getEntity(animal).discard();
            new NativeFaunaPass(plan,store).step(level,1);
            h.assertTrue(level.getEntity(animal)==null,"Dead issued animal was recreated");
        } finally {if(animal!=null&&level.getEntity(animal)!=null)level.getEntity(animal).discard();}
        h.succeed();
    }
    @GameTest(templateNamespace="proutlost",template="qualification/arena",timeoutTicks=100,batch="cb201-physical")
    public static void spawnIntentWithoutEntityQuarantinesAfterRestart(GameTestHelper h)throws Exception {
        var level=h.getLevel();
        try {
            prepare(h);var pos=fixturePos(h,5,1,5);var biome=level.getBiome(pos).unwrapKey().orElseThrow().location().toString();
            var rule=new NativeFaunaPass.Rule("minecraft:cow",Set.of(biome),Set.of("minecraft:stone"),-64,320,-10,10,0,256,256,64,2,true);
            var plan=NativeFaunaPass.plan(level,area(h),rule,List.of(pos),"fixture");var store=store(h);
            new NativeFaunaPass(plan,store);var animal=plan.animals().getFirst();store.write("intent-"+animal.uuid(),animal);
            reject(h,()->new NativeFaunaPass(plan,store).step(level,1));
            h.assertTrue(level.getEntity(animal.uuid())==null,"Ambiguous missing spawn was retried");
        } finally { /* Fixture coordinates and spawn stay unchanged. */ }
        h.succeed();
    }

    @GameTest(templateNamespace="proutlost",template="qualification/arena",timeoutTicks=100,batch="cb201-physical")
    public static void actualStructuresCommandPlacesSupportedProfile(GameTestHelper h)throws Exception {
        prepare(h);var level=h.getLevel();var data=WorldPrepRuntime.data(level);
        var area=area(h);String id="proutlost:command_exact_"+UUID.randomUUID().toString().replace("-","");
        data.areas().put(id,new WorldPrepSavedData.Area(id,area.dimension(),area.minX(),area.minZ(),area.maxX(),area.maxZ()));data.changed();
        boolean enabled=fr.proutlost.worldprep.WorldPrepConfig.ENABLED.get(),physical=fr.proutlost.worldprep.WorldPrepConfig.PHYSICAL_PASSES.get();
        var mode=fr.proutlost.worldprep.WorldPrepConfig.MODE.get();
        fr.proutlost.worldprep.WorldPrepConfig.ENABLED.set(true);fr.proutlost.worldprep.WorldPrepConfig.PHYSICAL_PASSES.set(true);
        fr.proutlost.worldprep.WorldPrepConfig.MODE.set(fr.proutlost.worldprep.WorldPrepConfig.Mode.TEST);
        try {
            var pos=fixturePos(h,5,1,5);
            var messages=new ArrayList<String>();
            var sink=new net.minecraft.commands.CommandSource() {
                public void sendSystemMessage(net.minecraft.network.chat.Component message){messages.add(message.getString());}
                public boolean acceptsSuccess(){return true;}
                public boolean acceptsFailure(){return true;}
                public boolean shouldInformAdmins(){return false;}
            };
            var source=level.getServer().createCommandSourceStack().withLevel(level).withPermission(4).withSource(sink);
            String command="proutlost worldprep apply structures \""+id+"\" exact-static-template-v1 proutlost:worldprep/stone_platform_v1 "
                +pos.getX()+" "+pos.getY()+" "+pos.getZ()+" NONE NONE";
            h.assertTrue(level.getServer().getCommands().getDispatcher().execute(command,source)==1,"Supported exact command refused: "+String.join(" | ",messages));
            var operation=UUID.fromString(NativePhysicalRuntime.status(level).substring("RUNNING ".length()));
            NativePhysicalRuntime.tickLevel(level);
            h.assertTrue(level.getBlockState(pos).is(Blocks.STONE),"Command never invoked native executor");
            NativePhysicalRuntime.resume(level,"structure",operation,true);NativePhysicalRuntime.tickLevel(level);
            h.assertTrue(level.getBlockState(pos).isAir(),"Native command rollback failed");
        } finally {
            NativePhysicalRuntime.pause(level);
            fr.proutlost.worldprep.WorldPrepConfig.ENABLED.set(enabled);fr.proutlost.worldprep.WorldPrepConfig.PHYSICAL_PASSES.set(physical);fr.proutlost.worldprep.WorldPrepConfig.MODE.set(mode);
        }
        h.succeed();
    }
    @GameTest(templateNamespace="proutlost",template="qualification/arena",timeoutTicks=100,batch="cb201-physical")
    public static void changedProtectionAndTemplateBlockEntityAreRefused(GameTestHelper h)throws Exception {
        prepare(h);var level=h.getLevel();var pos=fixturePos(h,5,1,5);
        var plan=NativeStructurePass.plan(level,area(h),"proutlost:qualification/platform",pos,Rotation.NONE,Mirror.NONE,"fixture");
        var data=WorldPrepRuntime.data(level);String protection="proutlost:physical_test_"+UUID.randomUUID();
        data.protections().put(protection,new WorldPrepSavedData.ProtectedZone(protection,area(h).dimension(),pos.getX(),pos.getZ(),pos.getX(),pos.getZ()));
        try {reject(h,()->new NativeStructurePass(plan,store(h)).step(level,1));}finally{data.protections().remove(protection);}
        var template=level.getStructureManager().get(net.minecraft.resources.ResourceLocation.parse("proutlost:qualification/platform")).orElseThrow();
        var original=template.save(new net.minecraft.nbt.CompoundTag());var invalid=original.copy();
        invalid.getList("blocks",net.minecraft.nbt.Tag.TAG_COMPOUND).getCompound(0).put("nbt",new net.minecraft.nbt.CompoundTag());
        try {
            template.load(net.minecraft.core.registries.BuiltInRegistries.BLOCK.asLookup(),invalid);
            reject(h,()->NativeStructurePass.plan(level,area(h),"proutlost:qualification/platform",pos,Rotation.NONE,Mirror.NONE,"fixture"));
        } finally {template.load(net.minecraft.core.registries.BuiltInRegistries.BLOCK.asLookup(),original);}
        h.succeed();
    }
    @GameTest(templateNamespace="proutlost",template="qualification/arena",timeoutTicks=200,batch="cb201-physical")
    public static void structureSaveAndActualAnvilReadback(GameTestHelper h)throws Exception {
        prepare(h);var level=h.getLevel();var plan=NativeStructurePass.plan(level,area(h),"proutlost:worldprep/stone_platform_v1",fixturePos(h,5,1,5),Rotation.NONE,Mirror.NONE,"fixture");
        var store=store(h);var pass=new NativeStructurePass(plan,store);while(!pass.done())pass.step(level,32);
        level.save(null,true,false);var audit=new NativeStructureAudit(plan,store);
        h.succeedWhen(()->{
            try {h.assertTrue(audit.step(level),"Awaiting bounded Anvil readback");h.assertTrue(store.read("disk-verified",NativeStructureAudit.Receipt.class).status().equals("PLACED_VERIFIED"),"Missing disk receipt");}
            catch(java.io.IOException e){throw new IllegalStateException(e);}
        });
    }
    @GameTest(templateNamespace="proutlost",template="qualification/arena",timeoutTicks=200,batch="cb201-physical")
    public static void faunaSaveAndActualEntityRegionReadback(GameTestHelper h)throws Exception {
        prepare(h);var level=h.getLevel();var pos=fixturePos(h,5,1,5);var biome=level.getBiome(pos).unwrapKey().orElseThrow().location().toString();
        var rule=new NativeFaunaPass.Rule("minecraft:cow",Set.of(biome),Set.of("minecraft:stone"),-64,320,-10,10,0,256,256,64,2,true);
        var plan=NativeFaunaPass.plan(level,area(h),rule,List.of(pos),"disk-fixture");var store=store(h);new NativeFaunaPass(plan,store).step(level,1);
        var audit=new NativePopulationAudit(plan,store);
        h.runAfterDelay(20,()->level.save(null,true,false));
        h.runAfterDelay(21,()->h.succeedWhen(()->{
            try {h.assertTrue(audit.step(level),"Awaiting entity region readback");var animal=level.getEntity(plan.animals().getFirst().uuid());if(animal!=null)animal.discard();}
            catch(java.io.IOException e){throw new IllegalStateException(e);}
        }));
    }
    // Two-process scenarios: operator must run phase two at another fixed fixture position,
    // so vanilla's fixture clearing cannot remove phase-one blocks/entities before reconciliation.
    @GameTest(templateNamespace="proutlost",template="qualification/arena",timeoutTicks=100,batch="cb201-restart")
    public static void crashStructurePhaseOneDurableIntentThenOneWrite(GameTestHelper h)throws Exception {
        prepare(h);var level=h.getLevel();var store=new PhysicalStore(NativePhysicalRuntime.root(level).resolve("restart-structure-v2"));
        var plan=NativeStructurePass.plan(level,area(h),"proutlost:worldprep/stone_platform_v1",fixturePos(h,5,1,5),Rotation.NONE,Mirror.NONE,"restart-fixture-v2");
        var pass=new NativeStructurePass(plan,store);
        level.save(null,true,false); // durable fixture foundation, before the crash-window mutation
        pass.step(level,1);store.write("crash-window-ready",Map.of("operation",plan.operation().toString(),"cursor",1));
        h.succeed();
    }
    @GameTest(templateNamespace="proutlost",template="qualification/arena",timeoutTicks=100,batch="cb201-restart")
    public static void crashStructurePhaseTwoReconcilesSameJournal(GameTestHelper h)throws Exception {
        prepare(h);var level=h.getLevel();var store=new PhysicalStore(NativePhysicalRuntime.root(level).resolve("restart-structure-v2"));
        h.runAfterDelay(40,()->{
            try {
                store.readText("crash-window-ready");var plan=store.read("plan",NativeStructurePass.Plan.class);var pass=new NativeStructurePass(plan,store);
                while(!pass.done())pass.step(level,32);
                for(var c:plan.cells())h.assertTrue(fr.proutlost.worldprep.runtime.BlockMutationRuntime.stateString(level.getBlockState(new BlockPos(c.x(),c.y(),c.z()))).equals(c.after()),"Restart did not reconcile exact journal");
                h.succeed();
            } catch(Exception e){throw new IllegalStateException(e);}
        });
    }
    private static final UUID RESTART_JVM=UUID.randomUUID();
    @GameTest(templateNamespace="proutlost",template="qualification/arena",timeoutTicks=500,batch="cb201-restart")
    public static void faunaRestartPhaseOneSpawnAndSave(GameTestHelper h)throws Exception {faunaOutsideCleanup(h,false);}
    @GameTest(templateNamespace="proutlost",template="qualification/arena",timeoutTicks=500,batch="cb201-restart")
    public static void faunaRestartPhaseTwoSameUuidNoDuplicate(GameTestHelper h)throws Exception {faunaOutsideCleanup(h,true);}

    private static void faunaOutsideCleanup(GameTestHelper h,boolean restart)throws Exception {
        prepare(h);var level=h.getLevel();
        var store=new PhysicalStore(NativePhysicalRuntime.root(level).resolve("restart-fauna-v3-outside-cleanup"));
        var window=new NativeChunkWindow(level);
        h.testInfo.addListener(new GameTestListener(){
            public void testStructureLoaded(GameTestInfo t){}
            public void testPassed(GameTestInfo t,GameTestRunner r){window.close();}
            public void testFailed(GameTestInfo t,GameTestRunner r){window.close();}
            public void testAddedForRerun(GameTestInfo t,GameTestInfo next,GameTestRunner r){window.close();}
        });
        var arena=h.testInfo.getStructureBounds();
        int cx=(h.testInfo.getStructureBlockPos().getX()>>4)+1,cz=(h.testInfo.getStructureBlockPos().getZ()>>4)+1;
        var local=new WorldPrepSavedData.Area("proutlost:restart_fauna_external_v3",level.dimension().location().toString(),cx<<4,cz<<4,(cx<<4)+15,(cz<<4)+15);
        NativeFaunaPass.Plan[] plan={restart?store.read("plan",NativeFaunaPass.Plan.class):null};
        if(restart) {
            if(store.exists("phase1-post-cleanup-failed"))throw new IllegalStateException("Prior post-cleanup qualification failed; retain history");
            var prior=store.read("phase1-post-cleanup",fr.proutlost.worldprep.orchestration.FaunaRestartEvidence.class);
            prior.requireRestart(PhysicalStore.sha(store.readText("plan")),plan[0].animals().getFirst().uuid(),RESTART_JVM);
            if(!plan[0].area().equals(local))throw new IllegalStateException("Restart requires same fixed arena/neighbor, world and journal");
        } else if(store.exists("plan"))throw new IllegalStateException("Phase1 journal already exists; retain history and use a new independent clone");
        int[] ticks={0},spawnedAt={-1};net.minecraft.world.entity.Entity[] original={null};
        Runnable[] step={null};step[0]=()->{
            try {
                if(++ticks[0]>450)throw new IllegalStateException("Verified loading/entity restart timeout; no replacement allowed");
                if(!window.blocks(local.minX(),local.minZ(),local.maxX(),local.maxZ())){h.runAfterDelay(1,DistinctTickCallback.wrap(step[0]));return;}
                if(plan[0]==null) {
                    int x=(cx<<4)+8,z=(cz<<4)+8;
                    int y=fr.proutlost.worldprep.world.ServerExistingChunkAccess.requireChunk(level,cx,cz).getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,8,8)+1;
                    var pos=new BlockPos(x,y,z);var biome=level.getBiome(pos).unwrapKey().orElseThrow().location().toString();
                    var rule=new NativeFaunaPass.Rule("minecraft:cow",Set.of(biome),Set.of("minecraft:grass_block","minecraft:dirt"),-64,320,-10,10,0,256,256,64,2,true);
                    if(arena.inflate(9).intersects(new net.minecraft.world.phys.AABB(x,y,z,x+1,y+2,z+1)))throw new IllegalStateException("Existing habitat too close to cleanup envelope");
                    plan[0]=NativeFaunaPass.plan(level,local,rule,List.of(pos),"restart-fixture-v3-outside-cleanup");
                    new NativeFaunaPass(plan[0],store).step(level,1);spawnedAt[0]=ticks[0];
                    original[0]=level.getEntity(plan[0].animals().getFirst().uuid());
                }
                var uuid=plan[0].animals().getFirst().uuid();var entity=level.getEntity(uuid);
                if(entity==null&&restart){h.runAfterDelay(1,DistinctTickCallback.wrap(step[0]));return;}
                if(entity==null||!entity.isAlive()||arena.inflate(1).intersects(entity.getBoundingBox()))throw new IllegalStateException("Animal missing or entered framework cleanup bounds");
                if(restart){original[0]=entity;new NativeFaunaPass(plan[0],store).step(level,1);}
                if(entity!=original[0]||level.getEntity(uuid)!=entity)throw new IllegalStateException("Entity replaced");
                if(!restart&&ticks[0]-spawnedAt[0]<20){h.runAfterDelay(1,DistinctTickCallback.wrap(step[0]));return;}
                level.save(null,true,false);
                h.succeed(); // pinned implementation synchronously removes entities INSIDE inflate(1)
                // A named PASS alone is insufficient. These receipts are mandatory operator gates.
                try {
                    if(level.getEntity(uuid)!=entity||!entity.isAlive()||arena.inflate(1).intersects(entity.getBoundingBox()))throw new IllegalStateException("Framework cleanup removed/changed entity");
                    level.save(null,true,false);
                    var folder=net.minecraft.world.level.dimension.DimensionType.getStorageFolder(level.dimension(),level.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)).resolve("entities");
                    var disk=ReadOnlyChunkNbt.entitiesBounded(folder,entity.blockPosition().getX()>>4,entity.blockPosition().getZ()>>4);
                    var entries=disk.getList("Entities",net.minecraft.nbt.Tag.TAG_COMPOUND);if(entries.size()>4096)throw new IllegalStateException("Entity inventory bound");
                    int matches=0;String digest=null;
                    for(int i=0;i<entries.size();i++){var e=entries.getCompound(i);if(e.hasUUID("UUID")&&e.getUUID("UUID").equals(uuid)){
                        if(!e.getString("id").equals(plan[0].rule().species())||!e.getCompound("NeoForgeData").getString("proutlost_initial_operation").equals(plan[0].operation().toString())||!e.getBoolean("PersistenceRequired"))throw new IllegalStateException("Persisted animal ownership/type/persistence mismatch");
                        matches++;digest=PhysicalStore.sha(e.toString());
                    }}
                    if(matches!=1)throw new IllegalStateException("Post-cleanup disk UUID absent/duplicated");
                    store.write(restart?"phase2-post-cleanup":"phase1-post-cleanup",new fr.proutlost.worldprep.orchestration.FaunaRestartEvidence(3,PhysicalStore.sha(store.readText("plan")),uuid,RESTART_JVM,digest,"POST_CLEANUP_SAVE_READBACK_NOT_GLOBAL_QUALIFICATION"));
                } catch(Exception failure){store.write(restart?"phase2-post-cleanup-failed":"phase1-post-cleanup-failed",Map.of("reason",failure.toString()));throw failure;}
                finally{window.close();}
            } catch(Exception failure){window.close();throw new IllegalStateException(failure);}
        };
        h.runAfterDelay(1,DistinctTickCallback.wrap(step[0]));
    }
    @FunctionalInterface private interface Attempt {void run()throws Exception;}
    private static void reject(GameTestHelper h,Attempt attempt)throws Exception {
        boolean refused=false;try{attempt.run();}catch(IllegalStateException|IllegalArgumentException expected){refused=true;}
        h.assertTrue(refused,"Unsafe operation accepted");
    }
    private WorldPrepPhysicalGameTests(){}
}
