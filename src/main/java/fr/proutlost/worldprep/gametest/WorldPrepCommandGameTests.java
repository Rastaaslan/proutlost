package fr.proutlost.worldprep.gametest;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import fr.proutlost.worldprep.WorldPrepConfig;
import fr.proutlost.worldprep.persistence.WorldPrepSavedData;
import fr.proutlost.worldprep.runtime.BlockMutationRuntime;
import fr.proutlost.worldprep.runtime.WorldPrepRuntime;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.UUID;

@GameTestHolder("proutlost")
@PrefixGameTestTemplate(false)
public final class WorldPrepCommandGameTests {
    @GameTest(templateNamespace="minecraft",template="village/plains/houses/plains_small_house_1",timeoutTicks=200)
    public static void quotedAreaCommandsExecutePreviewApplyAndRollback(GameTestHelper h) {
        var level=h.getLevel();
        var data=WorldPrepRuntime.data(level);
        var dispatcher=level.getServer().getCommands().getDispatcher();
        var source=level.getServer().createCommandSourceStack().withLevel(level).withPermission(4).withSuppressedOutput();
        var id="proutlost:commands/"+UUID.randomUUID().toString().replace("-","");
        var quoted="\""+id+"\"";
        var origin=h.absolutePos(new BlockPos(2,1,2));
        var ground=new BlockPos(origin.getX(),Math.max(level.getSeaLevel()+20,origin.getY()+20),origin.getZ());
        var saved=new java.util.ArrayList<net.minecraft.world.level.block.state.BlockState>();
        boolean enabled=WorldPrepConfig.ENABLED.get();
        WorldPrepConfig.ENABLED.set(true);
        try {
            for(int y=ground.getY();y<level.getMaxBuildHeight();y++){
                var pos=new BlockPos(ground.getX(),y,ground.getZ());saved.add(level.getBlockState(pos));
                level.setBlock(pos,y==ground.getY()?Blocks.DIRT.defaultBlockState():Blocks.AIR.defaultBlockState(),18);
            }
            String define="proutlost worldprep area define "+quoted+" "+ground.getX()+" "+ground.getZ()+" "+ground.getX()+" "+ground.getZ();
            boolean denied=false;
            try{dispatcher.execute(define,source.withPermission(0));}
            catch(CommandSyntaxException expected){denied=true;}
            h.assertTrue(denied&&!data.areas().containsKey(id),"Permission gate was bypassed");
            h.assertTrue(dispatcher.execute(define,source)==1,"Quoted definition failed");
            h.assertTrue(data.areas().get(id).minX()==ground.getX(),"Quoted ID or bounds changed");
            h.assertTrue(dispatcher.execute("proutlost worldprep area info "+quoted,source)==1,"Quoted info failed");
            for(String action:new String[]{"preview","apply","rollback"}){
                var before=new java.util.HashSet<>(data.jobs().keySet());
                UUID snapshot=null;
                if(action.equals("rollback"))
                    snapshot=data.jobs().values().stream().filter(j->j.area().equals(id)&&j.operation()==WorldPrepSavedData.Operation.APPLY_SOILS)
                            .findFirst().orElseThrow().snapshotId();
                String command="proutlost worldprep "+action+" soils "+quoted+(snapshot==null?"":" "+snapshot);
                h.assertTrue(dispatcher.execute(command,source)==1,"Command did not enqueue: "+command);
                var added=data.jobs().values().stream().filter(j->!before.contains(j.id())).toList();
                h.assertTrue(added.size()==1,"Command enqueued unexpected jobs");
                var job=added.getFirst();
                for(int i=0;i<8&&data.jobs().get(job.id()).state()!=WorldPrepSavedData.JobState.COMPLETED;i++)
                    CheckpointFixture.process(level,data,job.id(),data.jobs().get(job.id()),256);
                h.assertTrue(data.jobs().get(job.id()).state()==WorldPrepSavedData.JobState.COMPLETED,"Quoted command job did not complete");
                if(action.equals("apply")) {
                    level.setBlock(ground,Blocks.DIRT.defaultBlockState(),18);
                    h.assertTrue(dispatcher.execute("proutlost worldprep job "+job.id()+" recover",source)==1,
                            "Recover command failed");
                    h.assertTrue(level.getBlockState(ground).is(Blocks.GRASS_BLOCK),"Recover command omitted terrain");
                }
                if(action.equals("rollback")) {
                    var applyId=data.jobs().values().stream().filter(j->j.area().equals(id)
                            &&j.operation()==WorldPrepSavedData.Operation.APPLY_SOILS).findFirst().orElseThrow().id();
                    boolean refused=false;
                    try { dispatcher.execute("proutlost worldprep job "+applyId+" recover",source); }
                    catch(IllegalStateException expected){refused=expected.getMessage().contains("rollback history");}
                    h.assertTrue(refused,"Recover command replayed a rolled-back journal");
                }
                h.assertTrue(level.getBlockState(ground).is(action.equals("apply")?Blocks.GRASS_BLOCK:Blocks.DIRT),
                        "Unexpected terrain after "+action);
            }
            h.assertTrue(dispatcher.execute("proutlost worldprep area delete "+quoted,source)==1&&!data.areas().containsKey(id),
                    "Quoted delete failed");
            h.succeed();
        }catch(CommandSyntaxException e){throw new AssertionError("Production command grammar rejected quoted namespaced area",e);}
        finally{
            for(int i=0;i<saved.size();i++)level.setBlock(ground.above(i),saved.get(i),18);
            data.jobs().entrySet().removeIf(e->e.getValue().area().equals(id));
            data.areas().remove(id);data.changed();
            WorldPrepConfig.ENABLED.set(enabled);
        }
    }
    private WorldPrepCommandGameTests(){}
    @GameTest(templateNamespace="minecraft",template="village/plains/houses/plains_small_house_1",timeoutTicks=100)
    public static void unqualifiedStructuresAreaCommandEnqueuesNothing(GameTestHelper h) throws Exception {
        var level=h.getLevel();var data=WorldPrepRuntime.data(level);
        var source=level.getServer().createCommandSourceStack().withLevel(level).withPermission(4).withSuppressedOutput();
        var dispatcher=level.getServer().getCommands().getDispatcher();
        var id="proutlost:structure_refusal/"+UUID.randomUUID().toString().replace("-","");
        var pos=h.absolutePos(new BlockPos(2,1,2));
        boolean enabled=WorldPrepConfig.ENABLED.get();
        WorldPrepConfig.ENABLED.set(true);
        try {
            data.areas().put(id,new WorldPrepSavedData.Area(id,level.dimension().location().toString(),pos.getX(),pos.getZ(),pos.getX(),pos.getZ()));
            int jobs=data.jobs().size();
            var before=level.getBlockState(pos);
            h.assertTrue(dispatcher.execute("proutlost worldprep apply structures \""+id+"\" exact-static-template-v1 proutlost:qualification/platform 64 80 64 NONE NONE",source)==0,"Unqualified placement accepted");
            h.assertTrue(data.jobs().size()==jobs,"Refusal enqueued a mutation job");
            h.assertTrue(level.getBlockState(pos).equals(before),"Refusal changed terrain");
        } finally {data.areas().remove(id);WorldPrepConfig.ENABLED.set(enabled);}
        h.succeed();
    }

}
