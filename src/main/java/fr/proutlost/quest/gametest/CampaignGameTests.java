package fr.proutlost.quest.gametest;

import fr.proutlost.quest.runtime.*;
import fr.proutlost.quest.core.*;
import fr.proutlost.quest.content.*;
import fr.proutlost.ambient.AmbientRuntime;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.Container;
import net.minecraft.world.item.*;
import net.minecraft.world.InteractionHand;
import net.neoforged.neoforge.gametest.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Dedicated disposable-world campaign run. No facts injected, no graph/gates replaced.
 * Terrain fixtures stand in for prepared locations and player construction, never progression. */
@PrefixGameTestTemplate(false)
public final class CampaignGameTests {
    @GameTest(templateNamespace="cb213campaign",template="empty",timeoutTicks=2000000)
    public static void campaignAcrossProcessRestart(GameTestHelper helper){new Run(helper).start();}
    private static final class Run {
        final GameTestHelper h;
        final net.minecraft.server.MinecraftServer server;
        final String variant=System.getProperty("proutlost.quest.campaignVariant","repair_filter_unite");
        final boolean restart=Boolean.getBoolean("proutlost.quest.restartVerification");
        final String passage=variant.startsWith("repair")?"REPAIR":"DETOUR";
        final String mode=variant.contains("filter")?"FILTER":"AMPLIFY";
        final String memory=variant.endsWith("unite")?"UNITE":"DISTRIBUTE";
        final Deque<Step> steps=new ArrayDeque<>();
        ServerPlayer a,b;int elapsed;boolean finished;long stageStarted;
        record Step(String label,BooleanSupplier action){}
        Run(GameTestHelper helper){h=helper;server=h.getLevel().getServer();}
        QuestSavedData data(){return QuestRuntime.data(server);}
        void check(boolean value,String message){if(!value)throw new IllegalStateException(message);}
        void step(String label,Runnable action){steps.add(new Step(label,()->{action.run();return true;}));}
        void until(String label,BooleanSupplier action){steps.add(new Step(label,action));}
        void delay(){long[] start={0};until("real circulation/discussion delay",()->{if(start[0]==0)start[0]=System.currentTimeMillis();return System.currentTimeMillis()-start[0]>=1100;});}
        void resolved(String id){until(id+" resolved",()->data().core().state(id)==QuestCore.State.RESOLVED);}
        void notResolved(String id){check(data().core().state(id)!=QuestCore.State.RESOLVED,id+" resolved prematurely");}
        int command(ServerPlayer p,String command){try{return server.getCommands().getDispatcher().execute("proutlost quest "+command,p.createCommandSourceStack());}catch(Exception e){throw new IllegalStateException(command+": "+e,e);}}
        void cmd(ServerPlayer p,String command){check(command(p,command)==1,"Command failed: "+command);}
        PreparedSites.Site site(String id){return Objects.requireNonNull(data().sites().sites().get(id),id);}
        void at(ServerPlayer p,String id){var s=site(id);for(int x=-(s.kind()==PreparedSites.Kind.SIGNAL?32:16);x<=(s.kind()==PreparedSites.Kind.SIGNAL?32:16);x+=16)for(int z=-(s.kind()==PreparedSites.Kind.SIGNAL?32:16);z<=(s.kind()==PreparedSites.Kind.SIGNAL?32:16);z+=16){var pos=s.pos().offset(x,0,z);h.getLevel().setChunkForced(pos.getX()>>4,pos.getZ()>>4,true);h.getLevel().getChunkAt(pos);}p.setPos(s.x()+.5,s.y(),s.z()+.5);}
        String quote(String id){return EvidenceArguments.quoted(id);}
        List<QuestCore.Observation> evidence(ServerPlayer p,String type){return data().core().journal(p.getUUID()).stream().flatMap(page->page.evidence().stream()).map(QuestCore.Evidence::observation).filter(o->o.type().equals(type)).distinct().toList();}
        String proof(ServerPlayer p,String type){return evidence(p,type).getFirst().id();}
        void share(ServerPlayer p,String id,ServerPlayer recipient){cmd(p,"share "+quote(id)+" "+recipient.getGameProfile().getName());}
        ServerPlayer join(String name){
            var profile=new com.mojang.authlib.GameProfile(UUID.nameUUIDFromBytes(("cb213:"+name).getBytes(java.nio.charset.StandardCharsets.UTF_8)),name);
            var cookie=net.minecraft.server.network.CommonListenerCookie.createInitial(profile,false);
            var player=new ServerPlayer(server,h.getLevel(),profile,cookie.clientInformation()){
                @Override public boolean isSpectator(){return false;}
                @Override public boolean isCreative(){return true;}
                @Override public void sendSystemMessage(net.minecraft.network.chat.Component message){System.out.println("QUEST_PLAYER "+name+": "+message.getString());super.sendSystemMessage(message);}
            };
            var connection=new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
            new io.netty.channel.embedded.EmbeddedChannel(connection);
            server.getPlayerList().placeNewPlayer(connection,player,cookie);return player;
        }
        void start(){
            QuestConfig.ENABLED.set(true);QuestConfig.TEST_COMMANDS.set(false);
            QuestConfig.CIRCULATION_SECONDS.set(1);QuestConfig.DISCUSSION_SECONDS.set(1);QuestConfig.BALLOT_SECONDS.set(1);
            QuestConfig.SCENE_INTERVAL_SECONDS.set(1);QuestConfig.TRANSMISSION_INTERVAL_SECONDS.set(1);QuestConfig.STABILIZATION_SECONDS.set(1);
            QuestConfig.CAMP_ACTIVITY_SECONDS.set(1);QuestConfig.CAMP_VISITS.set(2);
            QuestConfig.MIN_CONTRIBUTORS.set(2);QuestConfig.ACTIVE_RATIO.set(.6);QuestConfig.WORK_CONTRIBUTORS.set(2);
            QuestConfig.AMPLIFY_PERTURBATION_PROBABILITY.set(variant.contains("disturbed")?1.0:0.0);
            AmbientRuntime.ENABLED.set(true);AmbientRuntime.VISITS.set(2);AmbientRuntime.MIN_SECONDS.set(1);AmbientRuntime.COOLDOWN_SECONDS.set(1);
            a=join("quest_alice");b=join("quest_bob");
            if(!restart){check(data().core().state("P0")!=QuestCore.State.RESOLVED,"Fresh campaign required");prepareWorld();early();if(passage.equals("REPAIR"))names();places();perceptionsFirst();
                step("checkpoint",()->{check(data().core().state("C1")==QuestCore.State.RESOLVED,"C1 checkpoint missing");save();System.out.println("QUEST_CAMPAIGN_CHECKPOINT "+variant+" C1; graph and gates intact");});
            }else{
                check(data().core().state("C1")==QuestCore.State.RESOLVED,"C1 not restored from previous process");
                check(data().core().state("C2")!=QuestCore.State.RESOLVED,"Second process must resume unfinished C2");
                if(passage.equals("DETOUR"))names();perceptionsSecond();synthesis("J1");middle();finale();
                step("final verification",()->{check(data().core().state("F5")==QuestCore.State.RESOLVED,"F5 absent");check(data().state().vote("D3").orElseThrow().result().equals(passage),"Passage lost");check(data().state().vote("F3").orElseThrow().result().equals(memory),"Memory choice lost");reloadSavedData();check(data().core().state("F5")==QuestCore.State.RESOLVED,"Finale lost on disk reload");System.out.println("QUEST_CAMPAIGN_COMPLETE "+variant+" F5; real commands; two actors; process restart");});
            }
            step("release fixture players and tickets",this::cleanup);
            long[] drainStart={0};int[] drainTicks={0};
            until("allow pending chunk generation to finish before server shutdown",()->{
                if(drainStart[0]==0)drainStart[0]=System.currentTimeMillis();
                return ++drainTicks[0]>=100&&System.currentTimeMillis()-drainStart[0]>=5000;
            });
            h.onEachTick(()->{
                if(finished)return;
                try{
                    elapsed++;
                    if(steps.isEmpty()){finished=true;h.succeed();return;}
                    var next=steps.getFirst();if(stageStarted==0){stageStarted=System.currentTimeMillis();System.out.println("QUEST_CAMPAIGN_STEP "+variant+" "+next.label());}
                    if(next.action().getAsBoolean()){steps.removeFirst();stageStarted=0;}
                    else if(System.currentTimeMillis()-stageStarted>25000)throw new IllegalStateException("Timeout: "+next.label()+" journal="+data().core().journal(a.getUUID()));
                }catch(Throwable e){finished=true;cleanup();h.fail("Campaign step "+(steps.isEmpty()?"end":steps.getFirst().label())+": "+e);}
            });
        }
        void cleanup(){
            server.getPlayerList().remove(a);server.getPlayerList().remove(b);
            // Release only this disposable GameTest world's fixture tickets before shutdown.
            for(long packed:h.getLevel().getForcedChunks().toLongArray()){
                var chunk=new net.minecraft.world.level.ChunkPos(packed);
                h.getLevel().setChunkForced(chunk.x,chunk.z,false);
            }
        }
        void save(){data().setDirty();server.overworld().getDataStorage().save();net.neoforged.neoforge.common.IOUtilities.waitUntilIOWorkerComplete();}
        void reloadSavedData(){
            save();
            try {
                var path=server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("data").resolve(QuestSavedData.FILE_ID+".dat");
                var nbt=net.minecraft.nbt.NbtIo.readCompressed(path,net.minecraft.nbt.NbtAccounter.unlimitedHeap());
                server.overworld().getDataStorage().set(QuestSavedData.FILE_ID,QuestSavedData.load(nbt.getCompound("data"),h.getLevel().registryAccess()));
            }catch(java.io.IOException e){throw new IllegalStateException("SavedData disk reload",e);}
        }
        void bind(String id,PreparedSites.Kind kind,BlockPos pos){
            h.getLevel().getChunkAt(pos);h.getLevel().setBlock(pos.below(),Blocks.STONE.defaultBlockState(),3);
            h.getLevel().setBlock(pos,Blocks.AIR.defaultBlockState(),3);h.getLevel().setBlock(pos.above(),Blocks.AIR.defaultBlockState(),3);
            a.setPos(pos.getX()+.5,pos.getY(),pos.getZ()+.5);
            try{check(server.getCommands().getDispatcher().execute("proutlost quest bind "+id+" "+kind,a.createCommandSourceStack().withPermission(4))==1,"Bind failed");}catch(Exception e){throw new IllegalStateException(e);}
        }
        void chest(BlockPos pos,Item item){h.getLevel().setBlock(pos,Blocks.CHEST.defaultBlockState(),3);((Container)h.getLevel().getBlockEntity(pos)).setItem(0,new ItemStack(item,4));}
        void prepareWorld(){
            var base=h.absolutePos(new BlockPos(0,0,0));base=new BlockPos(base.getX(),200,base.getZ());int n=0;
            for(var kind:PreparedSites.Kind.values()){
                String id=kind.name().toLowerCase(Locale.ROOT);bind(id,kind,base.offset(n++*64,0,0));
                if(kind==PreparedSites.Kind.PASSAGE||kind==PreparedSites.Kind.DETOUR||kind==PreparedSites.Kind.SIGNAL_REGION)bind(id+"_2",kind,base.offset(n++*64+128,0,32));
            }
            var signal=site("signal").pos();
            for(int x=-26;x<=26;x++)for(int z=-26;z<=26;z++) {var p=signal.offset(x,-1,z);h.getLevel().getChunkAt(p);h.getLevel().setBlock(p,Blocks.STONE.defaultBlockState(),3);h.getLevel().setBlock(p.above(),Blocks.AIR.defaultBlockState(),3);}
            for(int x=0;x<=8;x+=4)h.getLevel().setBlock(signal.offset(x,0,0),Blocks.CAMPFIRE.defaultBlockState(),3);
            var camp=site("camp").pos();for(var floor:BlockPos.betweenClosed(camp.offset(-6,-1,-6),camp.offset(6,-1,6)))h.getLevel().setBlock(floor,Blocks.STONE.defaultBlockState(),3);h.getLevel().setBlock(camp.offset(0,0,2),Blocks.CAMPFIRE.defaultBlockState(),3);h.getLevel().setBlock(camp.offset(2,0,0),Blocks.RED_BED.defaultBlockState(),2);h.getLevel().setBlock(camp.offset(2,0,0).relative(Blocks.RED_BED.defaultBlockState().getValue(net.minecraft.world.level.block.BedBlock.FACING)),Blocks.RED_BED.defaultBlockState().setValue(net.minecraft.world.level.block.BedBlock.PART,net.minecraft.world.level.block.state.properties.BedPart.HEAD),2);chest(camp.offset(-2,0,0),Items.BREAD);
            var path=site("passage").pos();h.getLevel().setBlock(path.offset(1,0,0),Blocks.CHAIN.defaultBlockState(),3);chest(path.offset(-1,0,0),Items.BREAD);
        }
        void early(){
            step("P0 arrival and safe shore",()->{at(a,"wreck");at(b,"wreck");cmd(a,"examine wreck_motif");at(a,"safe_shore");cmd(a,"reach_shore");at(b,"safe_shore");cmd(b,"reach_shore");});resolved("P0");
            step("camp attendance",()->{at(a,"camp");at(b,"camp");b.setPos(b.getX()+1,b.getY(),b.getZ());});
            until("real movement activity",()->{if(elapsed%21==0){a.setPos(a.getX()+(elapsed%2==0?1:-1),a.getY(),a.getZ());b.setPos(b.getX()+(elapsed%2==0?-1:1),b.getY(),b.getZ());}return data().sites().activeTicks("camp")>=20&&data().core().activePlayers(System.currentTimeMillis()).size()>=2&&data().sites().visits("camp")>=2;});
            step("P1 claim",()->cmd(a,"claim_camp"));resolved("P1");
            step("P2 built signal",()->{at(a,"signal");cmd(a,"activate_distress_signal");});resolved("P2");
            step("P3 exact comparison",()->{at(a,"old_camp");cmd(a,"examine camp_motif");String first=proof(a,"WRECK_MOTIF"),second=proof(a,"OLD_CAMP_MOTIF");share(a,first,b);share(a,second,b);cmd(a,"compare_prepare "+quote(first)+" "+quote(second));cmd(a,"compare_here");});resolved("P3");
        }
        void inspect(ServerPlayer p,String support,String place){at(p,place);cmd(p,"inspect "+support);var definition=Investigation.content().supports().stream().filter(s->s.id().equals(support)).findFirst().orElseThrow();share(p,proof(p,definition.factType()),p==a?b:a);}
        void relate(ServerPlayer p,String comparison,String first,String second,String place){at(p,place);cmd(p,"relate "+comparison+" "+quote(proof(p,first))+" "+quote(proof(p,second)));cmd(p,"test_relation");}
        void names(){
            step("A1 documents",()->{for(var id:List.of("daily_1","daily_2","signature"))inspect(a,id,"old_camp");});resolved("A1");
            step("A2 contradictions",()->{inspect(b,"other_camille","archive");relate(b,"dates","CAMILLE_SIGNATURE","SECOND_CAMILLE","archive");relate(b,"family","CAMILLE_SIGNATURE","SECOND_CAMILLE","archive");});resolved("A2");
            step("A3 recombinations",()->{inspect(a,"other_story_1","old_shelter");inspect(b,"other_story_2","archive");relate(a,"objects","CAMILLE_DAILY_1","OTHER_STORY_1","old_shelter");relate(b,"habits","CAMILLE_DAILY_2","OTHER_STORY_2","archive");});resolved("A3");
        }
        void places(){
            step("B1 gather around actual fire",()->{at(a,"camp");at(b,"camp");});resolved("B1");
            step("B2 physical arrangement",()->{var p=site("camp").pos();chest(p.offset(-1,-1,0),Items.STICK);chest(p.below(),Items.FLINT);chest(p.offset(1,-1,0),Items.FEATHER);cmd(a,"campaign record_arrangement");});resolved("B2");
            step("B3 comparison",()->{at(b,"old_shelter");cmd(b,"campaign ancient_arrangement");cmd(b,"campaign prepare_arrangement "+quote(proof(b,"ANCIENT_ARRANGEMENT")));cmd(b,"campaign compare_arrangement");});resolved("B3");
        }
        fr.proutlost.quest.perception.PerceptionSession scene(){var c=PerceptionContent.load();return data().state().scene("perception",c.sounds(),c.symbols());}
        void collect(ServerPlayer p){String id=scene().forPlayer(p.getUUID()).getLast().id();cmd(p,"record_perception "+quote(id));share(p,id,p==a?b:a);}
        void perceptionsFirst(){
            step("C1 first scene",()->{at(a,"perception");at(b,"perception");b.setPos(b.getX()+3,b.getY(),b.getZ());cmd(a,"scene");check(data().core().evidence(b.getUUID(),scene().forPlayer(b.getUUID()).getLast().id()).isEmpty(),"Perception auto-collected");});
            step("absent witness reconnects before collection",()->{b.connection.disconnect(net.minecraft.network.chat.Component.literal("GameTest reconnect"));server.getPlayerList().remove(b);b=join("quest_bob");at(b,"perception");b.setPos(b.getX()+3,b.getY(),b.getZ());cmd(b,"recall_perception");collect(b);collect(a);cmd(a,"compare_perceptions "+quote(scene().forPlayer(a.getUUID()).getLast().id())+" "+quote(scene().forPlayer(b.getUUID()).getLast().id()));});resolved("C1");
        }
        void perceptionsSecond(){
            step("restored journal and private fragments",()->{cmd(a,"journal");cmd(b,"news");check(!scene().forPlayer(a.getUUID()).isEmpty(),"Scene lost across process restart");at(a,"perception");at(b,"perception");a.setPos(a.getX()+3,a.getY(),a.getZ());cmd(a,"scene");collect(a);collect(b);var history=scene().forPlayer(a.getUUID());cmd(a,"compare_perceptions "+quote(history.getFirst().id())+" "+quote(history.getLast().id()));});resolved("C2");
            step("C3 wrong then correct response",()->{cmd(a,"sequence_input ICI");notResolved("C3");cmd(b,"sequence_input "+String.join(" ",PerceptionContent.load().sequence()));});resolved("C3");
        }
        void synthesis(String id){step(id+" first contribution",()->{cmd(a,"synthesis "+id);notResolved(id);});step(id+" second contribution",()->cmd(b,"synthesis "+id));resolved(id);}
        void vote(String id,String option){
            step(id+" dossiers",()->{cmd(a,"choice "+id+" dossier");cmd(b,"choice "+id+" dossier");});delay();
            step(id+" confirmed ballots",()->{cmd(a,"choice "+id+" open");cmd(a,"choice "+id+" confirm "+option);check(command(a,"choice "+id+" confirm "+option)==0,"Duplicate ballot accepted");cmd(b,"choice "+id+" confirm "+option);reloadSavedData();check(command(a,"choice "+id+" confirm "+option)==0,"Ballot lost after disk reload");});delay();step(id+" close",()->cmd(b,"choice "+id+" close"));
        }
        void middle(){
            for(int i=0;i<3;i++){step("D1 observe occurrence "+i,()->{at(a,"camp");cmd(a,"campaign observe_scene");});delay();}resolved("D1");
            step("D2 departure evidence",()->{inspect(a,"departure_1","archive");inspect(b,"departure_2","old_shelter");inspect(a,"departure_trace","passage");relate(b,"departures","DEPARTURE_STORY_1","DEPARTURE_STORY_2","passage");});resolved("D2");
            step("D3 explore both alternatives",()->{at(a,"passage");cmd(a,"campaign explore REPAIR");check(command(a,"choice D3 dossier")==0,"Vote before both alternatives");at(b,"detour");cmd(b,"campaign explore DETOUR");});vote("D3",passage);
            step("D3 actual two-point work",()->{String id=passage.equals("REPAIR")?"passage":"detour";int i=0;for(var p:List.of(a,b)){String point=id+(i++==0?"":"_2");at(p,point);h.getLevel().setBlock(site(point).pos().below(),Blocks.OAK_PLANKS.defaultBlockState(),3);p.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Items.OAK_PLANKS,4));cmd(p,"campaign work "+passage);}});resolved("D3");
            step("E1 regional signals",()->{at(a,"signal_region");cmd(a,"campaign listen");cmd(a,"campaign record_signal");share(a,proof(a,"SIGNAL_FRAGMENT"),b);at(b,"signal_region_2");cmd(b,"campaign listen");cmd(b,"campaign record_signal");String own=evidence(b,"SIGNAL_FRAGMENT").stream().filter(o->o.witness().equals(b.getUUID())).findFirst().orElseThrow().id();share(b,own,a);cmd(a,"campaign compare_signals "+quote(proof(a,"SIGNAL_FRAGMENT"))+" "+quote(own));});resolved("E1");
            step("E2 immutable versions",()->{inspect(a,"signal_original","transmitter");inspect(b,"signal_masked","transmitter");relate(a,"missing","SIGNAL_ORIGINAL","SIGNAL_MASKED","transmitter");});resolved("E2");
            for(int i=0;i<(mode.equals("FILTER")?3:1);i++){step("E3 activation "+i,()->{at(a,"transmitter");cmd(a,"campaign transmit "+mode);reloadSavedData();});delay();}
            if(variant.contains("disturbed")){
                step("R1 retreat",()->{notResolved("E3");at(b,"retreat");cmd(b,"campaign reach_retreat");});delay();
                step("R1 repair and resume",()->{at(b,"transmitter");b.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Items.OAK_PLANKS,4));cmd(b,"campaign repair_transmitter");notResolved("E3");cmd(b,"campaign transmit AMPLIFY");});resolved("R1");
            }resolved("E3");synthesis("J2");
        }
        void finale(){
            synthesis("F1");step("F2 intelligible call",()->{at(a,"transmitter");cmd(a,"campaign transmit "+mode);cmd(a,"campaign record_transmission");});resolved("F2");vote("F3",memory);resolved("F3");
            step("F4 distributed contributions",()->{cmd(a,"campaign final_link "+quote(proof(a,"SOUND_RECORDED"))+" "+quote(proof(a,"SYMBOLS_RECORDED")));reloadSavedData();at(b,passage.equals("REPAIR")?"passage":"detour");cmd(b,"campaign final_evolve");at(a,"transmitter");check(command(a,"campaign final_reply ICI")==0,"Incomplete final reply accepted");cmd(a,"campaign final_reply "+String.join(" ",PerceptionContent.load().sequence()));});resolved("F4");
            step("F5 epilogue",()->cmd(b,"campaign epilogue"));resolved("F5");
        }
    }
}
