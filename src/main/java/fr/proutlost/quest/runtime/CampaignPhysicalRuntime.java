package fr.proutlost.quest.runtime;

import com.google.gson.*;
import fr.proutlost.quest.core.QuestCore;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.level.PistonEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Prepared campaign metadata and ordinary player adapters. Never runs WorldPrep
 * or generates terrain. All runtime edits are limited to declared display/service
 * cells of the installed campaign. Core facts and quest scopes remain unchanged. */
public final class CampaignPhysicalRuntime {
 private CampaignPhysicalRuntime(){}
 public record Point(int x,int y,int z){public BlockPos pos(){return new BlockPos(x,y,z);}}
 public record Site(String id,String kind,Point anchor,String baselineState){}
 public record Support(String id,String site,Point at,String block,String action,String quest){}
 public record Zone(String id,int minX,int minY,int minZ,int maxX,int maxY,int maxZ,boolean playerBuild){
  boolean contains(BlockPos p){return p.getX()>=minX&&p.getX()<=maxX&&p.getY()>=minY&&p.getY()<=maxY&&p.getZ()>=minZ&&p.getZ()<=maxZ;}
 }
 public record Edit(Point at,String option){}
 public record Frame(String site,String uuid,int slot,String purpose){}
 public record Manifest(int schema,String authorDecision,String worldToken,long worldSeed,List<Site> sites,List<Support> supports,List<Zone> zones,List<Edit> editable,List<Frame> frames,Point repairPoint,Point signalIndicator){}
 public static final class Stamp extends SavedData {
  public static final Factory<Stamp> FACTORY=new Factory<>(Stamp::new,Stamp::load);
  public String token="";
  private static Stamp load(CompoundTag n,HolderLookup.Provider r){var v=new Stamp();v.token=n.getString("token");return v;}
  public CompoundTag save(CompoundTag n,HolderLookup.Provider r){n.putString("token",token);return n;}
 }
 private static final Map<MinecraftServer,Optional<Manifest>> INSTALLED=new WeakHashMap<>();
 private static final Map<MinecraftServer,String> FAILED=new WeakHashMap<>();
 private static final Map<UUID,List<String>> INPUT=new HashMap<>();
 private static final Gson G=new Gson();
 public static void started(ServerStartedEvent e){reload(e.getServer());}
 public static void stopped(ServerStoppedEvent e){INSTALLED.remove(e.getServer());FAILED.remove(e.getServer());INPUT.clear();}
 public static void reload(MinecraftServer server){INSTALLED.remove(server);FAILED.remove(server);ensure(server,QuestRuntime.data(server));}
 public static void ensure(MinecraftServer server,QuestSavedData data){
  if(INSTALLED.containsKey(server))return;
  var root=server.getWorldPath(LevelResource.ROOT).resolve("proutlost/campaign");
  if(!Files.isRegularFile(root.resolve("manifest.json"))){INSTALLED.put(server,Optional.empty());return;}
  try{
   byte[] raw=Files.readAllBytes(root.resolve("manifest.json"));if(raw.length>262144)throw new IllegalStateException("Campaign manifest bound");
   var digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));
   if(!Files.readString(root.resolve("manifest.sha256")).trim().equals(digest))throw new IllegalStateException("Campaign manifest pin differs");
   var m=G.fromJson(new String(raw,java.nio.charset.StandardCharsets.UTF_8),Manifest.class);
   if(m.schema()!=1||!m.authorDecision().equals("PLS2-S2-AUTHOR-20261123-CAMPAIGN-IMPLANTATION")||m.worldSeed()!=server.overworld().getSeed()||m.sites().size()<17||m.sites().size()>128||m.supports().size()>256)throw new IllegalStateException("Prepared campaign identity/schema");
   var stamp=server.overworld().getDataStorage().computeIfAbsent(Stamp.FACTORY,"proutlost_campaign_physical_identity");
   if(!stamp.token.equals(m.worldToken())||stamp.token.isBlank())throw new IllegalStateException("Campaign physical world differs");
   var ids=new HashSet<String>();
   for(var s:m.sites()){
    if(!ids.add(s.id())||!server.overworld().isInWorldBounds(s.anchor().pos()))throw new IllegalStateException("Campaign site identity");
    data.sites().bind(s.id(),new PreparedSites.Site(PreparedSites.Kind.valueOf(s.kind()),"minecraft:overworld",s.anchor().x(),s.anchor().y(),s.anchor().z()));
    data.sites().captureBaseline(s.id(),s.baselineState());
   }
   for(var s:m.supports())if(!ids.contains(s.site())||s.action().contains("\n")||s.action().length()>160)throw new IllegalStateException("Campaign support reference");
   data.setDirty();INSTALLED.put(server,Optional.of(m));
   System.out.println("CAMPAIGN_PHYSICAL_INSTALLED sites="+m.sites().size()+" supports="+m.supports().size()+" token="+m.worldToken()+" pin="+digest);
  }catch(Exception x){FAILED.put(server,x.toString());INSTALLED.put(server,Optional.empty());System.err.println("CAMPAIGN_PHYSICAL_FAILED_SAFE "+x);}
 }
 public static boolean healthy(MinecraftServer server){return !FAILED.containsKey(server);}
 public static Optional<Manifest> manifest(MinecraftServer server){return INSTALLED.getOrDefault(server,Optional.empty());}
 private static boolean present(ServerLevel level,Support s){
  return level.hasChunkAt(s.at().pos())&&BuiltInRegistries.BLOCK.getKey(level.getBlockState(s.at().pos()).getBlock()).toString().equals(s.block())
   &&level.getBlockEntity(s.at().pos())!=null&&level.getBlockEntity(s.at().pos()).getPersistentData().getString("pls2_support").equals(s.id());
 }
 public static void requireSupport(ServerPlayer player,String action,String site){
  var m=manifest(player.server);if(m.isEmpty())return;
  if(m.get().supports().stream().noneMatch(s->s.site().equals(site)&&s.action().equals(action)&&present(player.serverLevel(),s)))throw new IllegalStateException("Physical support absent");
 }
 public static void use(PlayerInteractEvent.RightClickBlock e){
  if(e.getHand()!=InteractionHand.MAIN_HAND||!(e.getEntity()instanceof ServerPlayer p)||p.serverLevel()!=p.server.overworld())return;
  var m=manifest(p.server);if(m.isEmpty())return;
  var support=m.get().supports().stream().filter(s->s.at().pos().equals(e.getPos())).findFirst();if(support.isEmpty())return;
  e.setCanceled(true);e.setCancellationResult(InteractionResult.SUCCESS);var s=support.get();
  if(!present(p.serverLevel(),s))return;
  var data=QuestRuntime.data(p.server);
  if(!s.quest().isBlank()&&data.core().state(s.quest(),p.getUUID())==QuestCore.State.LOCKED){p.sendSystemMessage(Component.literal("Ce support ne peut pas encore être interprété. Consultez le carnet."));return;}
  try{
   String command=s.action();
   if(command.startsWith("sequence:")){
    String concept=command.substring(9);var content=fr.proutlost.quest.content.PerceptionContent.load();
    PerceptionDelivery.schedule(p,List.of(content.supportPitch().get(concept)));
    var sequence=INPUT.computeIfAbsent(p.getUUID(),k->new ArrayList<>());sequence.add(concept);
    p.sendSystemMessage(Component.literal("Support activé : "+concept+" ("+sequence.size()+"/3)"));
    if(sequence.size()<3)return;command="sequence_input "+String.join(" ",sequence);INPUT.remove(p.getUUID());
   }
   p.server.getCommands().getDispatcher().execute("proutlost quest "+command,p.createCommandSourceStack());
  }catch(Exception x){p.sendSystemMessage(Component.literal("Action indisponible : consultez les prérequis du carnet."));}
 }
 private static boolean protectedAt(MinecraftServer server,BlockPos p){
  var m=manifest(server);if(m.isEmpty())return false;
  for(var z:m.get().zones())if(z.contains(p)){
   if(!z.playerBuild())return true;
   if(m.get().supports().stream().anyMatch(s->s.at().pos().equals(p)))return true;
   var camp=m.get().sites().stream().filter(s->s.kind().equals("CAMP")).findFirst();
   if(camp.isPresent()&&p.getY()==camp.get().anchor().y()-1&&p.getZ()==camp.get().anchor().z()&&Math.abs(p.getX()-camp.get().anchor().x())<=1)return true;
  }
  return false;
 }
 private static boolean editable(ServerPlayer player,BlockPos p){
  if(player.hasPermissions(4))return true;
  var m=manifest(player.server);if(m.isEmpty())return false;var d=QuestRuntime.data(player.server);
  for(var e:m.get().editable())if(e.at().pos().equals(p))return d.core().state("D3")!=QuestCore.State.RESOLVED&&d.state().vote("D3").map(v->v.result().equals(e.option())).orElse(false);
  return m.get().repairPoint().pos().equals(p)&&d.actions().disturbed()&&!d.actions().repaired();
 }
 public static void breaking(BlockEvent.BreakEvent e){
  if(!(e.getPlayer()instanceof ServerPlayer p)||p.hasPermissions(4)||p.serverLevel()!=p.server.overworld())return;
  if(protectedAt(p.server,e.getPos())&&!editable(p,e.getPos()))e.setCanceled(true);
 }
 public static void placing(BlockEvent.EntityPlaceEvent e){
  if(!(e.getEntity()instanceof ServerPlayer p)||p.hasPermissions(4)||p.serverLevel()!=p.server.overworld())return;
  if(protectedAt(p.server,e.getPos())&&(!editable(p,e.getPos())||!e.getPlacedBlock().is(BlockTags.PLANKS)))e.setCanceled(true);
 }
 public static void exploding(ExplosionEvent.Detonate e){if(e.getLevel()instanceof ServerLevel l&&l==l.getServer().overworld())e.getAffectedBlocks().removeIf(p->protectedAt(l.getServer(),p));}
 public static void piston(PistonEvent.Pre e){
  if(!(e.getLevel()instanceof ServerLevel l)||l!=l.getServer().overworld())return;
  for(int i=0;i<=13;i++)if(protectedAt(l.getServer(),e.getPos().relative(e.getDirection(),i))){e.setCanceled(true);return;}
 }
 public static void ancientArrangement(ServerPlayer p,QuestSavedData d,String site){
  var m=manifest(p.server);if(m.isEmpty())return;
  var a=d.actions().recordedArrangement(d.state()).orElseThrow();
  var bound=d.sites().sites().get(site);
  for(int i=0;i<3;i++){
   var pos=bound.pos().offset(i-1,-1,0);if(!(p.serverLevel().getBlockEntity(pos)instanceof Container container))throw new IllegalStateException("Prepared trace slots absent");
   var item=BuiltInRegistries.ITEM.get(net.minecraft.resources.ResourceLocation.parse(a.slots().get(i).itemId()));
   container.setItem(0,new ItemStack(item));p.serverLevel().getBlockEntity(pos).setChanged();
   final int slot=i;for(var f:m.get().frames())if(f.site().equals(site)&&f.purpose().equals("arrangement")&&f.slot()==slot&&p.serverLevel().getEntity(UUID.fromString(f.uuid()))instanceof ItemFrame frame)frame.setItem(new ItemStack(item));
  }
 }
 public static void damageTransmitter(ServerPlayer p,QuestSavedData d){
  var m=manifest(p.server);if(m.isEmpty()||!d.actions().disturbed()||d.actions().repaired())return;
  var pos=m.get().repairPoint().pos();if(p.serverLevel().getBlockState(pos).is(BlockTags.PLANKS))p.serverLevel().setBlock(pos,Blocks.AIR.defaultBlockState(),3);
 }
 public static boolean repairReady(ServerPlayer p){return manifest(p.server).map(m->p.serverLevel().hasChunkAt(m.repairPoint().pos())&&p.serverLevel().getBlockState(m.repairPoint().pos()).is(BlockTags.PLANKS)).orElse(true);}
 public static boolean clearSignal(MinecraftServer server){
  var m=manifest(server);if(m.isEmpty())return true;var level=server.overworld();var pos=m.get().signalIndicator().pos();
  if(!level.hasChunkAt(pos))return false;
  if(level.getBlockState(pos).is(Blocks.PURPLE_STAINED_GLASS))level.setBlock(pos,Blocks.LIME_STAINED_GLASS.defaultBlockState(),3);
  return level.getBlockState(pos).is(Blocks.LIME_STAINED_GLASS);
 }
 public static void readFrame(PlayerInteractEvent.EntityInteract e){
  if(e.getHand()!=InteractionHand.MAIN_HAND||!(e.getEntity()instanceof ServerPlayer p)||!(e.getTarget()instanceof ItemFrame frame)||p.serverLevel()!=p.server.overworld())return;
  var m=manifest(p.server);if(m.isEmpty())return;
  var declared=m.get().frames().stream().filter(f->f.uuid().equals(frame.getUUID().toString())).findFirst();if(declared.isEmpty())return;
  e.setCanceled(true);e.setCancellationResult(InteractionResult.SUCCESS);
  if(!declared.get().purpose().startsWith("memory_"))return;
  var d=QuestRuntime.data(p.server);var vote=d.state().vote("F3");if(vote.isEmpty()||vote.get().result().isEmpty())return;
  boolean central=vote.get().result().equals("UNITE");if(!declared.get().purpose().equals(central?"memory_central":"memory_local"))return;
  for(var page:d.core().journal(p.getUUID()))if(page.state()==QuestCore.State.RESOLVED)p.sendSystemMessage(Component.literal(page.title()+" : "+page.summary()));
 }
 public static void tick(ServerTickEvent.Post e){
  var server=e.getServer();if(server.getTickCount()%40!=0||manifest(server).isEmpty()||server.getPlayerList().getPlayers().isEmpty())return;
  var d=QuestRuntime.data(server);var choice=d.state().vote("F3");if(choice.isEmpty()||choice.get().result().isEmpty())return;
  var reader=server.getPlayerList().getPlayers().getFirst();var pages=d.core().journal(reader.getUUID()).stream().filter(p->p.state()==QuestCore.State.RESOLVED).<Component>map(p->Component.literal(p.title()+" : "+p.summary())).toList();
  if(pages.isEmpty())return;boolean central=choice.get().result().equals("UNITE");
  for(var f:manifest(server).orElseThrow().frames())if(f.purpose().equals(central?"memory_central":"memory_local")&&server.overworld().getEntity(UUID.fromString(f.uuid()))instanceof ItemFrame frame){
   var paper=new ItemStack(Items.PAPER);paper.set(DataComponents.CUSTOM_NAME,Component.literal("Témoignages établis"));paper.set(DataComponents.LORE,new ItemLore(pages));
   if(!ItemStack.isSameItemSameComponents(frame.getItem(),paper))frame.setItem(paper);
  }
 }
}
