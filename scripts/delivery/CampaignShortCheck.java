package fr.proutlost.season;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import net.neoforged.fml.common.Mod;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.minecraft.commands.Commands;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.*;
import net.minecraft.server.level.*;
import net.minecraft.world.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.*;
import fr.proutlost.quest.runtime.*;
import fr.proutlost.quest.core.*;
/** One bounded R56 check, only in a disposable closed clone; no calibration override,
 * no synthetic quest facts, no automatic entry on a delivered server. */
@Mod("proutlost_campaign_shortcheck")
public final class CampaignShortCheck {
 MinecraftServer server;ServerPlayer a,b;int stage,ticks;long begun;boolean done;CampaignPhysicalRuntime.Manifest manifest;
 final List<String> checks=new ArrayList<>();java.util.List<ServerPlayer> fixtureActors;
 public CampaignShortCheck(IEventBus bus){NeoForge.EVENT_BUS.addListener(this::commands);NeoForge.EVENT_BUS.addListener(this::tick);}
 void commands(RegisterCommandsEvent e){e.getDispatcher().register(Commands.literal("pls2_campaign_shortcheck").requires(s->s.hasPermission(4)).executes(c->{
  if(server!=null||!Boolean.getBoolean("proutlost.campaign.shortCheck"))throw new IllegalStateException("Disposable-check JVM only");
  server=c.getSource().getServer();begun=System.currentTimeMillis();return 1;
 }));}
 void need(boolean v,String reason){if(!v)throw new IllegalStateException(reason);}
 QuestSavedData data(){return QuestRuntime.data(server);}
 CampaignPhysicalRuntime.Site site(String id){return manifest.sites().stream().filter(s->s.id().equals(id)).findFirst().orElseThrow();}
 void load(BlockPos p,int radius){var l=server.overworld();for(int x=-radius;x<=radius;x+=16)for(int z=-radius;z<=radius;z+=16)l.getChunkAt(p.offset(x,0,z));l.getChunkAt(p);}
 void at(ServerPlayer p,String id){var s=site(id);load(s.anchor().pos(),32);p.teleportTo(server.overworld(),s.anchor().x()+.5,s.anchor().y(),s.anchor().z()+.5,0,0);}
 ServerPlayer join(String name){
  var profile=new com.mojang.authlib.GameProfile(UUID.nameUUIDFromBytes(("r56-short:"+name).getBytes(java.nio.charset.StandardCharsets.UTF_8)),name);
  var cookie=net.minecraft.server.network.CommonListenerCookie.createInitial(profile,false);
  var p=new ServerPlayer(server,server.overworld(),profile,cookie.clientInformation()){
   @Override public void sendSystemMessage(net.minecraft.network.chat.Component text){System.out.println("R56_CHECK_PLAYER "+name+" "+text.getString());super.sendSystemMessage(text);}
  };
  p.setInvulnerable(true);var conn=new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);new io.netty.channel.embedded.EmbeddedChannel(conn);// Controlled server actors use the existing arrival adapter, without claiming a
  // new authenticated network login or negotiating unrelated mod transports.
  p.connection=new net.minecraft.server.network.ServerGamePacketListenerImpl(server,conn,p,cookie);
  try{var field=net.minecraft.server.players.PlayerList.class.getDeclaredField("players");field.setAccessible(true);@SuppressWarnings("unchecked") var list=(java.util.List<ServerPlayer>)field.get(server.getPlayerList());fixtureActors=list;fixtureActors.add(p);}catch(ReflectiveOperationException x){throw new IllegalStateException("Controlled actor membership",x);}Arrivals.joined(new net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent(p));return p;
 }
 void use(ServerPlayer p,String id){
  var s=manifest.supports().stream().filter(x->x.id().equals(id)).findFirst().orElseThrow();at(p,s.site());
  var event=new PlayerInteractEvent.RightClickBlock(p,InteractionHand.MAIN_HAND,s.at().pos(),new BlockHitResult(Vec3.atCenterOf(s.at().pos()),Direction.UP,s.at().pos(),false));NeoForge.EVENT_BUS.post(event);need(event.isCanceled(),"Physical interaction not routed: "+id);
 }
 void command(ServerPlayer p,String text)throws Exception{need(server.getCommands().getDispatcher().execute("proutlost quest "+text,p.createCommandSourceStack())==1,"Normal command failed");}
 void pass(String s){checks.add(s);System.out.println("R56_CAMPAIGN_SHORT_STEP_PASS "+s);}
 boolean resolved(String q){return data().core().state(q)==QuestCore.State.RESOLVED;}
 void tick(ServerTickEvent.Post e){if(server==null||done||e.getServer()!=server)return;try{
  ticks++;need(System.currentTimeMillis()-begun<150000,"Bounded short check timeout stage="+stage);
  switch(stage){
   case 0 -> {
    manifest=CampaignPhysicalRuntime.manifest(server).orElseThrow();need(CampaignPhysicalRuntime.healthy(server),"Manifest failed closed");need(QuestConfig.ENABLED.get()&&!QuestConfig.TEST_COMMANDS.get(),"Production quest config");need(QuestConfig.CAMP_ACTIVITY_SECONDS.get()==60&&QuestConfig.CAMP_VISITS.get()==2,"Approved calibration changed");need(data().core().state("P0")!=QuestCore.State.RESOLVED,"Only pristine preparation clone");
    for(var s:manifest.supports()){load(s.at().pos(),0);var l=server.overworld();need(BuiltInRegistries.BLOCK.getKey(l.getBlockState(s.at().pos()).getBlock()).toString().equals(s.block())&&l.getBlockEntity(s.at().pos())!=null&&l.getBlockEntity(s.at().pos()).getPersistentData().getString("pls2_support").equals(s.id()),"Actual support absent "+s.id());}
    need(manifest.sites().size()==17&&manifest.supports().size()==48,"Installation counts differ");pass("17_BINDINGS_48_ACTUAL_SUPPORTS");
    load(site("s2_arrival").anchor().pos(),32);a=join("r56_alice");b=join("r56_bob");need(data().arrivals().contains(a.getUUID())&&data().arrivals().contains(b.getUUID()),"Normal login arrival failed");need(a.blockPosition().distSqr(site("s2_arrival").anchor().pos())<4,"Actual arrival position");
    for(int x=2905;x<=2928;x++){int y=65+Math.min(4,Math.max(0,(x-2908)/4));var p=new BlockPos(x,y,2784);need(server.overworld().getBlockState(p).getCollisionShape(server.overworld(),p).isEmpty()&&server.overworld().getBlockState(p.above()).getCollisionShape(server.overworld(),p.above()).isEmpty()&&server.overworld().getBlockState(p.below()).isFaceSturdy(server.overworld(),p.below(),Direction.UP),"Arrival boardwalk blocked");a.setPos(x+.5,y,2784.5);}
    pass("STANDARD_ARRIVAL_ADAPTER_AND_REAL_BOARDWALK");
    use(a,"document_daily_1");need(data().core().journal(a.getUUID()).stream().flatMap(p->p.evidence().stream()).noneMatch(o->o.observation().type().equals("CAMILLE_DAILY_1")),"Future document bypass");pass("LOCKED_SUPPORT_REFUSED");
    use(a,"arrival_motif");use(a,"shore_arrival");use(b,"shore_arrival");stage=1;
   }
   case 1 -> {if(!resolved("P0"))return;pass("P0_REAL_MARKER_PROGRESS");at(a,"s2_camp");at(b,"s2_camp");((Container)server.overworld().getBlockEntity(site("s2_camp").anchor().pos().below())).setItem(0,new ItemStack(Items.BREAD,3));stage=2;}
   case 2 -> {
    if(ticks%21==0){var s=site("s2_camp");a.setPos(s.anchor().x()+.5+(ticks%2==0?1:0),s.anchor().y(),s.anchor().z()+.5);b.setPos(s.anchor().x()+.5+(ticks%2==0?0:-1),s.anchor().y(),s.anchor().z()+1.5);}
    if(data().sites().activeTicks("s2_camp")<QuestConfig.CAMP_ACTIVITY_SECONDS.get()*20L||data().sites().visits("s2_camp")<2)return;
    use(a,"camp_claim");stage=3;
   }
   case 3 -> {
    if(!resolved("P1"))return;pass("P1_DEFAULT_ACTIVITY_REAL_CAMP");
    var support=manifest.supports().stream().filter(s->s.id().equals("old_camp_motif")).findFirst().orElseThrow();at(a,"s2_old_camp");var event=new BlockEvent.BreakEvent(server.overworld(),support.at().pos(),server.overworld().getBlockState(support.at().pos()),a);NeoForge.EVENT_BUS.post(event);need(event.isCanceled(),"Physical protection missing");pass("SUPPORT_BREAK_PROTECTED");
    at(a,"s2_signal");var p=site("s2_signal").anchor().pos();load(p,48);for(int dx:new int[]{-4,0,4}){var f=p.offset(dx,0,4);need(server.overworld().getBlockState(f).getBlock()instanceof CampfireBlock,"Actual fire absent");server.overworld().setBlock(f,server.overworld().getBlockState(f).setValue(BlockStateProperties.LIT,true),3);}
    var scan=MinecraftDistressScan.inspect(server.overworld(),p,3);need(scan.valid(),"Actual native signal scan refused: "+scan);use(a,"signal_request");stage=4;
   }
   case 4 -> {if(!resolved("P2"))return;pass("P2_REAL_SIGNAL_AND_RESPONSE");need(data().core().state("P3")!=QuestCore.State.LOCKED,"Follow-on not available");finish(true,"");}
  }
 }catch(Throwable x){x.printStackTrace();finish(false,x.toString());}}
 void finish(boolean success,String error){done=true;try{
  if(fixtureActors!=null){fixtureActors.remove(a);fixtureActors.remove(b);}
  var r=new JsonObject();r.addProperty("candidate","R56");r.addProperty("success",success);r.addProperty("elapsed_seconds",(System.currentTimeMillis()-begun)/1000.);r.addProperty("error",error);r.addProperty("native_controlled_server_players",2);r.addProperty("authenticated_human_clients",0);r.addProperty("fixture_registration","native PlayerList membership and existing arrival adapter; no authenticated network login");r.addProperty("synthetic_quest_facts",0);r.addProperty("calibration_overridden",false);r.addProperty("disposable_world_not_shipped",true);r.add("checks",new Gson().toJsonTree(checks));Files.writeString(server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().getParent().resolve("R56_SHORT_CAMPAIGN_CHECK.json"),new GsonBuilder().setPrettyPrinting().create().toJson(r));System.out.println(success?"R56_SHORT_CAMPAIGN_CHECK_PASS":"R56_SHORT_CAMPAIGN_CHECK_FAIL "+error);
 }catch(Exception x){throw new IllegalStateException(x);}}
}
