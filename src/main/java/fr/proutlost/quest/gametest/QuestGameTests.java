package fr.proutlost.quest.gametest;

import fr.proutlost.quest.runtime.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.UUID;

/** Real server integration fixtures; JVM unit tests do not execute these. */
@GameTestHolder("proutlost")
@PrefixGameTestTemplate(false)
public final class QuestGameTests {
    private QuestGameTests(){}
    @GameTest(templateNamespace="minecraft",template="village/plains/houses/plains_small_house_1",timeoutTicks=80)
    public static void realCommandHandlersAcceptGeneratedEvidenceIds(GameTestHelper helper) throws Exception {
        boolean enabled=QuestConfig.ENABLED.get();QuestConfig.ENABLED.set(true);
        try{
            var player=helper.makeMockServerPlayerInLevel();var pos=helper.absolutePos(new BlockPos(2,2,2));player.setPos(pos.getX()+.5,pos.getY(),pos.getZ()+.5);
            var data=QuestRuntime.data(helper.getLevel().getServer());String prefix="test_"+UUID.randomUUID().toString().replace("-","");
            if(Boolean.getBoolean("proutlost.quest.restartVerification")) {
                helper.assertTrue(data.core().hasCollective("MOTIF_EXACT_COMPARISON"),"comparison lost after server process restart");
                var snapshot=new com.google.gson.Gson().fromJson(data.core().save(),fr.proutlost.quest.core.QuestCore.Snapshot.class);
                helper.assertTrue(snapshot.observations().values().stream().anyMatch(o->o.type().equals("WRECK_MOTIF")),"private observation lost after server process restart");
                helper.assertTrue(!data.sites().sites().isEmpty(),"prepared sites lost after server process restart");
            }
            String wreck=prefix+"_w",camp=prefix+"_c";String dimension=helper.getLevel().dimension().location().toString();
            data.sites().bind(wreck,new PreparedSites.Site(PreparedSites.Kind.WRECK,dimension,pos.getX(),pos.getY(),pos.getZ()));
            data.sites().bind(camp,new PreparedSites.Site(PreparedSites.Kind.OLD_CAMP,dimension,pos.getX()+16,pos.getY(),pos.getZ()));
            var dispatcher=helper.getLevel().getServer().getCommands().getDispatcher();var source=player.createCommandSourceStack();
            helper.assertTrue(!data.core().activePlayers(System.currentTimeMillis()).contains(player.getUUID()),"fresh stationary actor already active");
            dispatcher.execute("proutlost quest journal",source);
            dispatcher.execute("proutlost quest examine unknown_qa_support",source); // refusal still returns1
            helper.assertTrue(!data.core().activePlayers(System.currentTimeMillis()).contains(player.getUUID()),"menu/refused support counted as gameplay");
            var stationary=player.blockPosition();
            helper.assertTrue(dispatcher.execute("proutlost quest examine wreck_motif",source)==1,"examine wreck failed");
            helper.assertTrue(stationary.equals(player.blockPosition()),"fixture moved instead of exercising stationary activity");
            helper.assertTrue(data.core().activePlayers(System.currentTimeMillis()).contains(player.getUUID()),"valid stationary examination did not count as gameplay");
            player.setPos(pos.getX()+16.5,pos.getY(),pos.getZ()+.5);
            helper.assertTrue(dispatcher.execute("proutlost quest examine camp_motif",source)==1,"examine camp failed");
            String first=EvidenceArguments.documentId(player.getUUID(),"wreck_motif",wreck),second=EvidenceArguments.documentId(player.getUUID(),"camp_motif",camp);
            helper.assertTrue(data.core().evidence(player.getUUID(),first).isPresent(),"generated wreck evidence absent");
            helper.assertTrue(dispatcher.execute("proutlost quest share "+EvidenceArguments.quoted(first)+" "+player.getGameProfile().getName(),source)==1,"share parse/handler failed");
            helper.assertTrue(dispatcher.execute("proutlost quest compare_prepare "+EvidenceArguments.quoted(first)+" "+EvidenceArguments.quoted(second),source)==1,"compare preparation failed");
            helper.assertTrue(dispatcher.execute("proutlost quest compare_here",source)==1,"on-site comparison failed");
            helper.assertTrue(data.core().hasCollective("MOTIF_EXACT_COMPARISON"),"comparison handler did not produce proof");
            data.setDirty();helper.getLevel().getServer().overworld().getDataStorage().save();
            net.neoforged.neoforge.common.IOUtilities.waitUntilIOWorkerComplete();
            var path=helper.getLevel().getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                .resolve("data").resolve(QuestSavedData.FILE_ID+".dat");
            var nbt=net.minecraft.nbt.NbtIo.readCompressed(path,net.minecraft.nbt.NbtAccounter.unlimitedHeap());
            var restored=QuestSavedData.load(nbt.getCompound("data"),helper.getLevel().registryAccess());
            helper.assertTrue(restored.core().evidence(player.getUUID(),first).isPresent(),"server SavedData lost evidence on disk reload");
            helper.assertTrue(restored.core().hasCollective("MOTIF_EXACT_COMPARISON"),"server SavedData lost comparison");
            helper.succeed();
        }finally{QuestConfig.ENABLED.set(enabled);}
    }
    @GameTest(templateNamespace="minecraft",template="village/plains/houses/plains_small_house_1",timeoutTicks=120)
    public static void actualTerrainScanRejectsPilesAndAcceptsSpacedFires(GameTestHelper helper) {
        var level=helper.getLevel();var origin=helper.absolutePos(new BlockPos(2,2,2));
        var anchor=new BlockPos(origin.getX(),200,origin.getZ());
        // Only this disposable GameTest world is edited. Production scans never load chunks or write blocks.
        for(int x=-26;x<=26;x++)for(int z=-26;z<=26;z++) {
            var floor=anchor.offset(x,-1,z);level.getChunkAt(floor);
            level.setBlock(floor,net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(),3);
            level.setBlock(floor.above(),net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(),3);
        }
        for(int x=0;x<3;x++)for(int z=0;z<3;z++)level.setBlock(anchor.offset(x,0,z),
            (x%2==0?net.minecraft.world.level.block.Blocks.WHITE_WOOL:net.minecraft.world.level.block.Blocks.BLACK_WOOL).defaultBlockState(),3);
        var pile=MinecraftDistressScan.inspect(level,anchor,3);
        helper.assertTrue(!pile.readable()&&!pile.valid(),"unreadable two-colour pile accepted by runtime scan");
        for(int x=0;x<3;x++)for(int z=0;z<3;z++)level.setBlock(anchor.offset(x,0,z),net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(),3);
        for(int x=0;x<=8;x+=4)level.setBlock(anchor.offset(x,0,0),net.minecraft.world.level.block.Blocks.CAMPFIRE.defaultBlockState(),3);
        var fires=MinecraftDistressScan.inspect(level,anchor,3);
        helper.assertTrue(fires.valid()&&fires.highPoint(),"spaced visible fires on a real high plateau rejected");
        level.setBlock(anchor.offset(4,1,0),net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(),3);
        helper.assertTrue(!MinecraftDistressScan.inspect(level,anchor,3).valid(),"covered smoke column accepted");
        helper.succeed();
    }

    @GameTest(templateNamespace="minecraft",template="village/plains/houses/plains_small_house_1",timeoutTicks=80)
    public static void lostPerceptionMessageCanBeRecalledWithoutAutomaticCollection(GameTestHelper helper) throws Exception {
        boolean enabled=QuestConfig.ENABLED.get();QuestConfig.ENABLED.set(true);
        try {
            var player=helper.makeMockServerPlayerInLevel();var pos=helper.absolutePos(new BlockPos(2,2,2));
            player.setPos(pos.getX()+.5,pos.getY(),pos.getZ()+.5);
            var server=helper.getLevel().getServer();var data=QuestRuntime.data(server);
            String prefix="recall_"+UUID.randomUUID().toString().replace("-","").substring(0,16);
            String dimension=helper.getLevel().dimension().location().toString();
            for(var kind:java.util.List.of(PreparedSites.Kind.SAFE_SHORE,PreparedSites.Kind.CAMP,PreparedSites.Kind.SIGNAL,PreparedSites.Kind.WRECK,PreparedSites.Kind.OLD_CAMP,PreparedSites.Kind.PERCEPTION))
                data.sites().bind(prefix+kind.name().toLowerCase(java.util.Locale.ROOT),new PreparedSites.Site(kind,dimension,pos.getX(),pos.getY(),pos.getZ()));
            QuestRuntime.data(server);
            // Explicit prerequisite fixture: this test proves recovery commands, not P0–P3 gameplay.
            for(var q:fr.proutlost.quest.core.Campaign.load())if(java.util.Set.of("P0","P1","P2","P3").contains(q.questId())) {
                for(var type:q.resolutionConditions().getFirst())data.core().accept(new fr.proutlost.quest.core.WorldFact(UUID.randomUUID().toString(),type,"gametest_fixture",java.util.Set.of(),"","",prefix,1,java.util.Map.of(),true,fr.proutlost.quest.core.WorldFact.Category.NARRATIVE));
                data.core().evaluate(1);
            }
            var content=fr.proutlost.quest.content.PerceptionContent.load();
            var scene=data.state().scene(prefix+"perception",content.sounds(),content.symbols());
            scene.play(java.util.Map.of(player.getUUID(),new fr.proutlost.quest.perception.PerceptionSession.Position(dimension,pos.getX(),pos.getY(),pos.getZ()),UUID.randomUUID(),new fr.proutlost.quest.perception.PerceptionSession.Position(dimension,pos.getX()+4,pos.getY(),pos.getZ())),2);
            var fragment=scene.forPlayer(player.getUUID()).getLast();
            var loaded=QuestSavedData.load(data.save(new net.minecraft.nbt.CompoundTag(),helper.getLevel().registryAccess()),helper.getLevel().registryAccess());
            server.overworld().getDataStorage().set(QuestSavedData.FILE_ID,loaded);
            var dispatcher=server.getCommands().getDispatcher();var source=player.createCommandSourceStack();
            var foreignWitness=scene.participants().stream().filter(id->!id.equals(player.getUUID())).findFirst().orElseThrow();
            var foreignFragment=scene.forPlayer(foreignWitness).getLast();
            helper.assertTrue(dispatcher.execute("proutlost quest record_perception "+EvidenceArguments.quoted(foreignFragment.id()),source)==0,"foreign fragment command must be denied without throwing");
            helper.assertTrue(loaded.core().evidence(player.getUUID(),foreignFragment.id()).isEmpty(),"foreign fragment must remain private");
            helper.assertTrue(dispatcher.execute("proutlost quest recall_perception",source)==1,"recovery command unavailable while C2 locked");
            helper.assertTrue(loaded.core().evidence(player.getUUID(),fragment.id()).isEmpty(),"recall silently collected private perception");
            helper.assertTrue(dispatcher.execute("proutlost quest record_perception "+EvidenceArguments.quoted(fragment.id()),source)==1,"voluntary collection failed after recall");
            helper.assertTrue(loaded.core().evidence(player.getUUID(),fragment.id()).isPresent(),"voluntary proof missing");
            var restoredScene=loaded.state().scene(prefix+"perception",content.sounds(),content.symbols());
            var departed=restoredScene.participants().stream().filter(id->!id.equals(player.getUUID())).findFirst().orElseThrow();
            var substitute=UUID.randomUUID();
            var activeWitnesses=java.util.Set.of(player.getUUID(),substitute);
            var replacement=restoredScene.replaceWitness(departed,substitute,new fr.proutlost.quest.perception.PerceptionSession.Position(dimension,pos.getX()+4,pos.getY(),pos.getZ()),activeWitnesses,3,
                candidate -> activeWitnesses.stream().anyMatch(holder -> loaded.core().evidence(holder,candidate.id()).isPresent())).orElseThrow();
            restoredScene.record(substitute,replacement.id(),loaded.core());
            loaded.core().share(substitute,replacement.id(),java.util.Set.of(player.getUUID()));
            loaded.core().share(player.getUUID(),fragment.id(),java.util.Set.of(substitute));
            String compare="proutlost quest compare_perceptions "+EvidenceArguments.quoted(fragment.id())+" ";
            helper.assertTrue(dispatcher.execute(compare+EvidenceArguments.quoted(replacement.id()),source)==1,"first trial comparison failed");
            restoredScene.play(java.util.Map.of(player.getUUID(),new fr.proutlost.quest.perception.PerceptionSession.Position(dimension,pos.getX()+4,pos.getY(),pos.getZ()),substitute,new fr.proutlost.quest.perception.PerceptionSession.Position(dimension,pos.getX(),pos.getY(),pos.getZ())),4);
            String beforeRejectedComparison=loaded.core().save();
            helper.assertTrue(dispatcher.execute(compare+EvidenceArguments.quoted(replacement.id()),source)==0,"old replacement timestamps bypassed second-trial collection");
            helper.assertTrue(beforeRejectedComparison.equals(loaded.core().save()),"old fragments changed canonical state");
            var secondTrial=restoredScene.forPlayer(player.getUUID()).getLast();
            helper.assertTrue(dispatcher.execute("proutlost quest record_perception "+EvidenceArguments.quoted(secondTrial.id()),source)==1,"second-trial collection failed");
            helper.assertTrue(dispatcher.execute(compare+EvidenceArguments.quoted(secondTrial.id()),source)==1,"genuine cross-trial comparison failed");
            helper.assertTrue(loaded.core().hasCollective("SECOND_TRIAL_COMPARED"),"second-trial comparison proof absent");
            helper.succeed();
        } finally {QuestConfig.ENABLED.set(enabled);}
    }

    @GameTest(templateNamespace="minecraft",template="village/plains/houses/plains_small_house_1",timeoutTicks=80)
    public static void durableDirectorFactEventReachesQuestCoreOnceWithPrivateScope(GameTestHelper helper) {
        // Synthetic seam check ONLY. A real Director production fact remains unverified.
        boolean enabled=QuestConfig.ENABLED.get();QuestConfig.ENABLED.set(true);
        try {
            var server=helper.getLevel().getServer();
            var data=QuestRuntime.data(server);
            var pos=helper.absolutePos(new BlockPos(2,2,2));
            var key="wdq_seam_"+UUID.randomUUID().toString().replace("-","");
            var dimension=helper.getLevel().dimension().location().toString();
            data.sites().bind(key,new PreparedSites.Site(PreparedSites.Kind.SAFE_SHORE,dimension,pos.getX(),pos.getY(),pos.getZ()));
            QuestRuntime.data(server);
            var target=UUID.randomUUID();
            var factId=UUID.randomUUID();
            var questType=fr.proutlost.quest.core.Campaign.load().stream()
                .filter(q->q.questId().equals("P0")).findFirst().orElseThrow().observations().iterator().next();
            var type="proutlost_director:quest_fact/"+questType.toLowerCase(java.util.Locale.ROOT);
            var fact=new fr.proutlost.director.api.model.DirectorFact(
                new fr.proutlost.director.api.model.FactId(factId),
                new fr.proutlost.director.api.model.FactType(type),
                java.util.List.of(new fr.proutlost.director.api.model.ScopeRef(fr.proutlost.director.api.model.ScopeKind.PLAYER,target)),
                java.util.Optional.empty(),fr.proutlost.director.api.model.EvidenceStatus.CONFIRMED,
                java.util.Map.of("actor",new fr.proutlost.director.api.model.JsonValue.Text(target.toString())),
                true,java.util.Optional.empty());
            helper.assertTrue(data.core().factById(factId.toString()).isEmpty(),"WDQ fixture collision");
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(
                new fr.proutlost.director.integration.quest.DirectorCommittedFactEvent(server,fact));
            var accepted=data.core().factById(factId.toString()).orElseThrow();
            helper.assertTrue(accepted.factType().equals(questType),"WDQ canonical fact mapping lost");
            helper.assertTrue(accepted.playerScope().equals(java.util.Set.of(target)),"WDQ private scope widened");
            helper.assertTrue(accepted.sourceSystem().equals("proutlost_director:j04"),"WDQ provenance lost");
            var snapshot=data.core().save();
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(
                new fr.proutlost.director.integration.quest.DirectorCommittedFactEvent(server,fact));
            helper.assertTrue(snapshot.equals(data.core().save()),"WDQ duplicate mutated state");
            var restored=QuestSavedData.load(data.save(new net.minecraft.nbt.CompoundTag(),helper.getLevel().registryAccess()),
                helper.getLevel().registryAccess());
            helper.assertTrue(accepted.equals(restored.core().factById(factId.toString()).orElseThrow()),"WDQ fact not restored");
            helper.succeed();
        } finally { QuestConfig.ENABLED.set(enabled); }
    }

}
