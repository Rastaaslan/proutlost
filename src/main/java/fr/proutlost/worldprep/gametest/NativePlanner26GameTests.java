package fr.proutlost.worldprep.gametest;

import fr.proutlost.worldprep.physical.*;
import fr.proutlost.worldprep.persistence.WorldPrepSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.nio.file.Files;
import java.util.Comparator;

/** Exclusive disposable native game-test namespace; never registered for player worlds.
 * This suite verifies physical placement in a temporary GameTestServer world only.
 */
@GameTestHolder("proutlost")
@PrefixGameTestTemplate(false)
public final class NativePlanner26GameTests {
    @GameTest(templateNamespace="proutlost",template="qualification/arena",
              timeoutTicks=400,batch="cb20126_native_planner")
    public static void boundedPlannerCandidatePlacesAndRollsBackRealStructure(GameTestHelper h) throws Exception {
        requireDisposableLab(h);
        var level=h.getLevel();
        var center=h.absolutePos(new BlockPos(5,2,5));
        int minX=center.getX()-24,maxX=center.getX()+23;
        int minZ=center.getZ()-24,maxZ=center.getZ()+23;
        var area=new WorldPrepSavedData.Area("proutlost:disposable_planner26_lab",
            level.dimension().location().toString(),minX,minZ,maxX,maxZ);
        try(var planner=new NativeTemplateSitePlanner(area,"proutlost:worldprep/stone_platform_v1",
                                                     "cb20126_lab_fixed",level.getSeed())) {
            var points=planner.auditCandidates();
            h.assertTrue(!points.isEmpty()&&points.size()<=36*16,
                "Finite deterministic candidate window absent or too large");
            var second=new NativeTemplateSitePlanner(area,"proutlost:worldprep/stone_platform_v1",
                                                      "cb20126_lab_fixed",level.getSeed());
            try {h.assertTrue(points.equals(second.auditCandidates()),"Native candidate order changed");}
            finally {second.close();}
            var chosen=points.stream().min(Comparator.comparingLong(p ->
                (long)(p.getX()-center.getX())*(p.getX()-center.getX())+
                (long)(p.getZ()-center.getZ())*(p.getZ()-center.getZ()))).orElseThrow();
            // No generation: operate on an already existing, fully loaded GameTest fixture chunk.
            int y=center.getY();
            var pos=new BlockPos(chosen.getX(),y,chosen.getZ());
            for(int dx=0;dx<3;dx++)for(int dz=0;dz<3;dz++) {
                var foundation=pos.offset(dx,-1,dz);
                var air=pos.offset(dx,0,dz);
                level.setBlock(foundation,Blocks.STONE.defaultBlockState(),18);
                level.setBlock(air,Blocks.AIR.defaultBlockState(),18);
            }
            var plan=NativeStructurePass.plan(level,area,
                "proutlost:worldprep/stone_platform_v1",pos,
                Rotation.NONE,Mirror.NONE,"cb20126_native_lab");
            h.assertTrue(plan.cells().size()==9,"Actual loaded structure template is not 3x3");
            Files.createDirectories(NativePhysicalRuntime.root(level));
            var journal=new PhysicalStore(Files.createTempDirectory(
                NativePhysicalRuntime.root(level),"cb20126-native-test-"));
            var pass=new NativeStructurePass(plan,journal);
            for(int tick=0;tick<32&&!pass.done();tick++)pass.step(level,1);
            h.assertTrue(pass.done(),"Actual native physical placement failed");
            for(int dx=0;dx<3;dx++)for(int dz=0;dz<3;dz++)
                h.assertTrue(level.getBlockState(pos.offset(dx,0,dz)).is(Blocks.STONE),
                    "Placed real stone platform differs from the exact template");
            pass.rollback();
            for(int tick=0;tick<32&&!pass.done();tick++)pass.step(level,1);
            h.assertTrue(pass.done(),"Actual physical rollback did not complete");
            for(int dx=0;dx<3;dx++)for(int dz=0;dz<3;dz++)
                h.assertTrue(level.getBlockState(pos.offset(dx,0,dz)).isAir(),
                    "Rollback left actual blocks in the disposable test world");
        }
        h.succeed();
    }
    @GameTest(templateNamespace="proutlost",template="qualification/arena",
              timeoutTicks=600,batch="cb20127_real_step")
    public static void plannerStepFindsPreparedExistingChunkSite(GameTestHelper h) throws Exception {
        requireDisposableLab(h);
        var level=h.getLevel();
        var center=h.absolutePos(new BlockPos(5,2,5));
        var area=new WorldPrepSavedData.Area("proutlost:cb20127_real_step",
            level.dimension().location().toString(),
            center.getX()-24,center.getZ()-24,center.getX()+23,center.getZ()+23);
        var planner=new NativeTemplateSitePlanner(area,"proutlost:worldprep/stone_platform_v1",
            "cb20127-real-step",level.getSeed());
        h.assertTrue(!planner.auditCandidates().isEmpty(),"No bounded candidate");
        var chosen=planner.auditCandidates().getFirst();
        var elevated=new BlockPos(chosen.getX(),200,chosen.getZ());
        for(int dx=0;dx<3;dx++)for(int dz=0;dz<3;dz++) {
            level.setBlock(elevated.offset(dx,-1,dz),Blocks.STONE.defaultBlockState(),18);
            for(int dy=0;dy<3;dy++)
                level.setBlock(elevated.offset(dx,dy,dz),Blocks.AIR.defaultBlockState(),18);
        }
        // EXPLICIT DEV FIXTURE PREPARATION: this exists only in the disposable
        // GameTestServer build, never in a public WorldPrep run.
        var root=level.getServer().getWorldPath(
            net.minecraft.world.level.storage.LevelResource.ROOT).toRealPath();
        var marker=root.resolve("worldprep-candidate.json");
        if(!Files.exists(marker)) {
            var pinned=new com.google.gson.JsonObject();
            pinned.addProperty("schemaVersion",1);
            pinned.addProperty("candidateDirectory",root.toString());
            pinned.addProperty("offlineBaseline",true);
            Files.writeString(marker,pinned.toString());
        }
        var cx=chosen.getX()>>4;var cz=chosen.getZ()>>4;
        for(int dx=-2;dx<=2;dx++)for(int dz=-2;dz<=2;dz++)
            level.getChunk(cx+dx,cz+dz); // disposable explicit fixture worldgen BEFORE safety gate
        level.save(null,true,false); // explicit normal save, never running against S2 ecology
        System.out.println("CB20127_TEST_FIXTURE_SAVED root="+root+
            " centerChunk="+cx+","+cz+" site="+elevated);
        var windowHash=planner.auditCandidates().size();
        h.succeedWhen(()->{
            try {
                var possible=planner.step(level);
                if(possible.isEmpty()) {
                    h.assertTrue(!planner.exhausted(),
                        "Native planner exhausted without accepting prepared stone candidate; candidates="+windowHash);
                    return;
                }
                var plan=possible.get();
                h.assertTrue(plan.cells().size()==9,"Native planner produced wrong actual template");
                Files.createDirectories(NativePhysicalRuntime.root(level));
                var store=new PhysicalStore(Files.createTempDirectory(NativePhysicalRuntime.root(level),"cb20127-step-"));
                var pass=new NativeStructurePass(plan,store);
                for(int i=0;i<32&&!pass.done();i++)pass.step(level,1);
                h.assertTrue(pass.done(),"Actual planner plan never applied");
                for(var cell:plan.cells())
                    h.assertTrue(level.getBlockState(new BlockPos(cell.x(),cell.y(),cell.z())).is(Blocks.STONE),
                        "Native plan placement mismatch");
                pass.rollback();
                for(int i=0;i<32&&!pass.done();i++)pass.step(level,1);
                h.assertTrue(pass.done(),"Actual planner-generated structure rollback failed");
                planner.close();
                h.succeed();
            }catch(net.minecraft.gametest.framework.GameTestAssertException pending) {
                throw pending;
            }catch(Exception failure) {
                planner.close();
                h.fail("CB20127 native planner step failed: "+failure);
            }
        });
    }
    private static void requireDisposableLab(GameTestHelper h) {
        h.assertTrue(Boolean.getBoolean("proutlost.planner27.labOnly")
            &&!net.neoforged.fml.loading.FMLLoader.isProduction()
            &&h.getLevel().getServer().getClass().getSimpleName().equals("GameTestServer"),
            "Refused: native lab fixture is allowed ONLY on a dedicated disposable DEV GameTestServer");
    }
    private NativePlanner26GameTests(){}
}
