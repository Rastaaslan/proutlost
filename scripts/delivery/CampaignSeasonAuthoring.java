package fr.proutlost.season;

import com.google.gson.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import net.neoforged.fml.common.Mod;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.world.Container;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.decoration.ItemFrame;
import fr.proutlost.quest.runtime.CampaignPhysicalRuntime;
import fr.proutlost.quest.runtime.QuestRuntime;
import fr.proutlost.integration.physical.NativeLoadedCellWitness;
import fr.proutlost.director.adapter.neoforge.server.lifecycle.DirectorSavedIdentity;

/** Temporary explicit preparation tool, excluded from every delivered mod set.
 * No quest facts or progression are manufactured. Geometry is authored only on
 * the closed R55-derived season copy after full prior-state preflight. */
@Mod("proutlost_campaign_authoring")
public final class CampaignSeasonAuthoring {
 private static final Gson G=new GsonBuilder().serializeNulls().setPrettyPrinting().create();
 private MinecraftServer server;private JsonObject plan,manifest;private JsonArray writes;private int cursor,phase;
 private final Map<String,BlockState> states=new HashMap<>();private final Set<Long> loaded=new HashSet<>();
 private fr.proutlost.integration.contra.ContraRealDirectorCatalogImportProbe importer;private String source;
 public CampaignSeasonAuthoring(IEventBus bus){NeoForge.EVENT_BUS.addListener(this::commands);NeoForge.EVENT_BUS.addListener(this::tick);}
 private void commands(RegisterCommandsEvent e){
  e.getDispatcher().register(Commands.literal("pls2_campaign_install").requires(s->s.hasPermission(4)).executes(c->{
   try{if(server!=null)throw new IllegalStateException("Owned authoring already running");server=c.getSource().getServer();
    if(!server.getPlayerList().getPlayers().isEmpty())throw new IllegalStateException("Preparation requires no players");
    var root=world();if(Files.exists(root.resolve("proutlost/campaign/INSTALLATION_RECEIPT.json")))throw new IllegalStateException("Installation already completed; never replay authoring from stale status");
    byte[] bytes=Files.readAllBytes(root.resolve("proutlost/campaign-authoring/AUTHORING_PLAN.json"));if(bytes.length>12000000)throw new IllegalStateException("Authoring input bound");
    plan=JsonParser.parseString(new String(bytes,StandardCharsets.UTF_8)).getAsJsonObject();manifest=plan.getAsJsonObject("manifest");writes=plan.getAsJsonArray("writes");
    if(!plan.get("sourceDirectorWorld").getAsString().equals(server.overworld().getDataStorage().computeIfAbsent(DirectorSavedIdentity.FACTORY,DirectorSavedIdentity.KEY).worldId().value().toString())||writes.size()>60000||manifest.get("worldSeed").getAsLong()!=server.overworld().getSeed())throw new IllegalStateException("Actual R55 physical identity required");
    phase=1;cursor=0;System.out.println("CAMPAIGN_AUTHORING_PREFLIGHT_STARTED planned="+writes.size());return 1;
   }catch(Exception x){x.printStackTrace();server=null;return 0;}
  }));
  e.getDispatcher().register(Commands.literal("pls2_campaign_import_d0").requires(s->s.hasPermission(4)).executes(c->{try{
   var s=c.getSource().getServer();source=Files.readString(s.getWorldPath(LevelResource.ROOT).resolve("director-physical-catalogs/contra_saison2/catalog.json"));importer=new fr.proutlost.integration.contra.ContraRealDirectorCatalogImportProbe();return 1;
  }catch(Exception x){x.printStackTrace();return 0;}}));
 }
 private Path world(){return server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();}
 private static BlockPos pos(JsonObject o){return new BlockPos(o.get("x").getAsInt(),o.get("y").getAsInt(),o.get("z").getAsInt());}
 private BlockState parse(String text){return states.computeIfAbsent(text,s->{try{return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK.asLookup(),s,false).blockState();}catch(Exception x){throw new IllegalArgumentException(s,x);}});}
 private void existing(BlockPos p)throws Exception{
  int x=p.getX()>>4,z=p.getZ()>>4;long key=((long)x<<32)^(z&0xffffffffL);if(loaded.contains(key))return;
  Path f=world().resolve("region/r."+Math.floorDiv(x,32)+"."+Math.floorDiv(z,32)+".mca");
  if(!Files.isRegularFile(f))throw new IllegalStateException("No generation: region absent");
  try(var r=new RandomAccessFile(f.toFile(),"r")){r.seek(4L*(Math.floorMod(x,32)+32*Math.floorMod(z,32)));if((r.readInt()>>>8)==0)throw new IllegalStateException("No generation: chunk absent");}
  server.overworld().getChunk(x,z);loaded.add(key);
 }
 private static boolean natural(BlockState state){
  String id=BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();if(!id.startsWith("minecraft:"))return false;
  String n=id.substring(10);return Set.of("air","cave_air","void_air","water","dirt","coarse_dirt","grass_block","podzol","rooted_dirt","mud","clay","sand","sandstone","red_sand","gravel","stone","andesite","diorite","granite","tuff","calcite","deepslate","dirt_path","moss_block","moss_carpet","snow","short_grass","tall_grass","fern","large_fern","dead_bush","vine","lily_pad","seagrass","tall_seagrass","kelp","kelp_plant","brown_mushroom","red_mushroom","allium","red_tulip","white_tulip","pink_tulip","orange_tulip","dandelion","poppy","blue_orchid","azure_bluet","cornflower","oxeye_daisy","lilac","rose_bush","peony","lily_of_the_valley","sunflower","sugar_cane","big_dripleaf","big_dripleaf_stem","small_dripleaf","glow_lichen","hanging_roots","azalea","flowering_azalea","cave_vines","cave_vines_plant").contains(n)||n.endsWith("_log")||n.endsWith("_wood")||n.endsWith("_leaves")||n.endsWith("_sapling")||n.endsWith("_ore");
 }
 private void tick(ServerTickEvent.Post e){
  if(importer!=null){try{
   var doc=fr.proutlost.contract.director.StrictCodec.decode(source,fr.proutlost.contract.director.CatalogSnapshot.class);
   if(importer.pollImportAndReadAdmitted(source,doc.world_id(),doc.build_id()).isPresent()){System.out.println("CAMPAIGN_D0_REAL_IMPORT_AND_READBACK entries="+doc.entries().size());importer=null;}
  }catch(Exception x){x.printStackTrace();importer=null;}}
  if(server==null||e.getServer()!=server)return;
  try{
   if(!server.getPlayerList().getPlayers().isEmpty())throw new IllegalStateException("Player entered authoring copy");
   int end=Math.min(writes.size(),cursor+(phase==1?700:128));
   for(;cursor<end;cursor++){
    var w=writes.get(cursor).getAsJsonObject();var p=pos(w.getAsJsonObject("at"));existing(p);var level=server.overworld();var before=level.getBlockState(p);
    if(!before.equals(parse(w.get("expected").getAsString())))throw new IllegalStateException("Prior state changed at "+p+" expected="+w.get("expected")+" actual="+before);
    if(phase==1){if(level.getBlockEntity(p)!=null||!natural(before))throw new IllegalStateException("Protected or non-natural original at "+p+" "+before);}
    else if(!level.setBlock(p,parse(w.get("state").getAsString()),2|16|32))throw new IllegalStateException("Native setBlock refused "+p);
   }
   if(cursor%1024<128||cursor==writes.size())System.out.println("CAMPAIGN_AUTHORING_PROGRESS phase="+phase+" cells="+cursor+"/"+writes.size());
   if(cursor<writes.size())return;
   if(phase==1){phase=2;cursor=0;System.out.println("CAMPAIGN_AUTHORING_PRIOR_STATE_PREFLIGHT_PASS");return;}
   finish();phase=0;server=null;
  }catch(Exception x){x.printStackTrace();System.out.println("CAMPAIGN_AUTHORING_ABORTED_KEEP_PLAN_AND_R55_BASELINE cursor="+cursor+" phase="+phase);server=null;}
 }
 private void finish()throws Exception{
  var level=server.overworld();var root=world();
  for(var v:manifest.getAsJsonArray("supports")){
   var s=v.getAsJsonObject();var p=pos(s.getAsJsonObject("at"));var be=level.getBlockEntity(p);
   if(be==null||!BuiltInRegistries.BLOCK.getKey(level.getBlockState(p).getBlock()).toString().equals(s.get("block").getAsString()))throw new IllegalStateException("Actual support absent "+s.get("id")+" "+p);
   be.getPersistentData().putString("pls2_support",s.get("id").getAsString());
   if(be instanceof LecternBlockEntity lectern)lectern.setBook(new ItemStack(Items.WRITABLE_BOOK));
   if(s.get("id").getAsString().equals("departure_trace")&&be instanceof Container c)c.setItem(0,new ItemStack(Items.BREAD,3));be.setChanged();
  }
  for(var v:manifest.getAsJsonArray("frames")){
   var f=v.getAsJsonObject();var p=pos(f.getAsJsonObject("at"));var frame=new ItemFrame(level,p,Direction.NORTH);frame.setUUID(UUID.fromString(f.get("uuid").getAsString()));frame.setInvulnerable(true);var fixed=frame.saveWithoutId(new net.minecraft.nbt.CompoundTag());fixed.putBoolean("Fixed",true);frame.load(fixed);frame.getPersistentData().putString("pls2_frame",f.get("purpose").getAsString());
   if(f.get("purpose").getAsString().equals("motif")){
    var stack=MapItem.create(level,p.getX(),p.getZ(),(byte)0,false,false);var map=MapItem.getSavedData(stack,level);if(map==null)throw new IllegalStateException("Actual motif map unavailable");
    for(int x=0;x<128;x++)for(int y=0;y<128;y++){
     int color=10;boolean flower=false,border=false;
     for(int[] c:new int[][]{{64,40},{40,64},{64,88}}){double d=Math.hypot(x-c[0],y-c[1]);flower|=d<=14;border|=d>12&&d<=14;}
     boolean square=Math.abs(x-88)<=13&&Math.abs(y-64)<=13;flower|=square;border|=square&&(Math.abs(x-88)>=12||Math.abs(y-64)>=12);
     if(flower)color=border?118:34;if(Math.hypot(x-64,y-64)<=11)color=74;if(y>98&&y<118&&x>=62&&x<=65)color=30;
     map.setColor(x,y,(byte)color);
    }
    map.setDirty();frame.setItem(stack);
   }
   if(!level.addFreshEntity(frame))throw new IllegalStateException("Actual display entity refused");
  }
  for(var v:manifest.getAsJsonArray("sites")){
   var s=v.getAsJsonObject();var p=pos(s.getAsJsonObject("anchor"));if(!standing(level,p))throw new IllegalStateException("Actual site anchor obstructed "+s.get("id"));s.addProperty("baselineState",level.getBlockState(p.below()).toString());
  }
  var stamp=level.getDataStorage().computeIfAbsent(CampaignPhysicalRuntime.Stamp.FACTORY,"proutlost_campaign_physical_identity");if(!stamp.token.isBlank()&&!stamp.token.equals(manifest.get("worldToken").getAsString()))throw new IllegalStateException("Physical installation belongs to another campaign");
  stamp.token=manifest.get("worldToken").getAsString();stamp.setDirty();var dest=root.resolve("proutlost/campaign");Files.createDirectories(dest);byte[] bytes=G.toJson(manifest).getBytes(StandardCharsets.UTF_8);Files.write(dest.resolve("manifest.json"),bytes);Files.writeString(dest.resolve("manifest.sha256"),sha(bytes)+"\n");
  CampaignPhysicalRuntime.reload(server);
  if(!CampaignPhysicalRuntime.healthy(server)||QuestRuntime.data(server).sites().sites().size()!=manifest.getAsJsonArray("sites").size())throw new IllegalStateException("Real quest bindings not admitted");
  var arrival=manifest.getAsJsonArray("sites").get(0).getAsJsonObject();var previousSpawn=level.getSharedSpawnPos();level.setDefaultSpawnPos(pos(arrival.getAsJsonObject("anchor")),0);
  var wp=level.getDataStorage().computeIfAbsent(fr.proutlost.worldprep.persistence.WorldPrepSavedData.FACTORY,"proutlost_worldprep");
  for(var v:manifest.getAsJsonArray("zones")){var z=v.getAsJsonObject();String id="campaign_r56_"+z.get("id").getAsString();wp.protections().put(id,new fr.proutlost.worldprep.persistence.WorldPrepSavedData.ProtectedZone(id,"minecraft:overworld",z.get("minX").getAsInt(),z.get("minZ").getAsInt(),z.get("maxX").getAsInt(),z.get("maxZ").getAsInt()));}wp.changed();
  server.saveEverything(true,true,true);net.neoforged.neoforge.common.IOUtilities.waitUntilIOWorkerComplete();
  exportG0AndSurvey();
  var receipt=new JsonObject();receipt.addProperty("schema",1);receipt.addProperty("status","PHYSICALLY_INSTALLED_AND_QUEST_BOUND");receipt.addProperty("worldToken",stamp.token);receipt.addProperty("manifest_sha256",sha(bytes));receipt.addProperty("actual_native_changes",writes.size());receipt.addProperty("sites",manifest.getAsJsonArray("sites").size());receipt.addProperty("supports",manifest.getAsJsonArray("supports").size());receipt.addProperty("new_chunks_generated",0);receipt.addProperty("original_spawn",previousSpawn.toShortString());receipt.addProperty("main_jar_sha256",hash(root.getParent().resolve("mods/proutlost-0.1.0.jar")));receipt.addProperty("SDK_jar_sha256",hash(root.getParent().resolve("mods/proutlost-director-1.0.0.jar")));receipt.addProperty("full_qualification",false);receipt.addProperty("short_progression_check","PENDING");Files.writeString(dest.resolve("INSTALLATION_RECEIPT.json"),G.toJson(receipt));
  System.out.println("CAMPAIGN_PHYSICAL_INSTALL_AND_BINDING_PASS sites="+manifest.getAsJsonArray("sites").size()+" supports="+manifest.getAsJsonArray("supports").size()+" changes="+writes.size());
 }
 private static boolean standing(ServerLevel l,BlockPos p){return l.getBlockState(p).getCollisionShape(l,p).isEmpty()&&l.getBlockState(p.above()).getCollisionShape(l,p.above()).isEmpty()&&l.getBlockState(p.below()).isFaceSturdy(l,p.below(),Direction.UP);}
 private static String sha(byte[] b)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));}
 private static String hash(Path p)throws Exception{return sha(Files.readAllBytes(p));}
 private static String uuid(String id){return UUID.nameUUIDFromBytes(("proutlost-r56/"+id).getBytes(StandardCharsets.UTF_8)).toString();}
 private static JsonObject bounds(JsonObject z){var b=new JsonObject();for(String axis:List.of("X","Y","Z")){b.addProperty("min_"+axis.toLowerCase(),z.get("min"+axis).getAsInt());b.addProperty("max_"+axis.toLowerCase()+"_exclusive",z.get("max"+axis).getAsInt()+1);}return b;}
 private void exportG0AndSurvey()throws Exception{
  var root=world();var old=JsonParser.parseString(Files.readString(root.resolve("director-location-imports/contra-saison2.json"))).getAsJsonObject();var next=new JsonObject();next.addProperty("schema_version",1);next.addProperty("world_id",old.get("world_id").getAsString());next.addProperty("source_namespace","proutlost:season_authoring");next.addProperty("export_revision","1");next.addProperty("export_digest","0".repeat(64));var regions=new JsonArray();var locations=new JsonArray();var anchors=new JsonArray();
  for(var v:manifest.getAsJsonArray("sites")){
   var s=v.getAsJsonObject();String id=s.get("id").getAsString(),lid=uuid("site/"+id),rid=uuid("region/"+id);var z=manifest.getAsJsonArray("zones").asList().stream().map(JsonElement::getAsJsonObject).filter(x->x.get("id").getAsString().equals(id)).findFirst().orElseThrow();var tags=new JsonArray();if(s.get("kind").getAsString().equals("PERCEPTION"))tags.add("proutlost:clearing");
   var r=new JsonObject();r.addProperty("dimension","minecraft:overworld");r.addProperty("region_id",rid);r.add("bounds",bounds(z));r.add("tags",tags);regions.add(r);
   var l=new JsonObject();l.addProperty("dimension","minecraft:overworld");l.addProperty("location_id",lid);l.addProperty("external_id","season-authoring/"+manifest.get("worldToken").getAsString()+"/"+id);l.add("bounds",bounds(z));var rids=new JsonArray();rids.add(rid);l.add("region_ids",rids);var lt=new JsonArray();lt.add("proutlost:structure");l.add("tags",lt);locations.add(l);
   var center=pos(s.getAsJsonObject("anchor"));int ordinal=0;
   int radius=s.get("kind").getAsString().equals("WRECK")?2:1;
   for(int dx=-radius;dx<=radius;dx++)for(int dz=-radius;dz<=radius;dz++){
    var p=center.offset(dx,0,dz);if(!standing(server.overworld(),p))continue;var a=new JsonObject();a.addProperty("dimension","minecraft:overworld");a.addProperty("anchor_id",uuid("anchor/"+id+"/"+(ordinal++)));a.addProperty("location_id",lid);var xyz=new JsonObject();xyz.addProperty("x",p.getX());xyz.addProperty("y",p.getY());xyz.addProperty("z",p.getZ());a.add("position",xyz);var at=new JsonArray();at.add("proutlost:anchor_observation");a.add("tags",at);anchors.add(a);
   }
  }
  next.add("regions",regions);next.add("locations",locations);next.add("anchors",anchors);var codec=new fr.proutlost.director.api.codec.ModelCodec<>(fr.proutlost.director.api.model.LocationImport.class);var parsed=codec.decode(G.toJson(next).getBytes(StandardCharsets.UTF_8));var sealed=new fr.proutlost.director.api.model.LocationImport(1,parsed.worldId(),parsed.sourceNamespace(),parsed.exportRevision(),fr.proutlost.director.integration.locations.StandaloneLocationImporter.digest(parsed),parsed.regions(),parsed.locations(),parsed.anchors());Files.write(root.resolve("director-location-imports/season-campaign-r56.json"),codec.encode(sealed));fr.proutlost.director.integration.locations.LocationTransferFiles.writeImmutable(root.resolve("director-location-imports/season-campaign-r56_transfer"),sealed);
  var combined=old.deepCopy();for(String key:List.of("regions","locations","anchors"))for(var v:next.getAsJsonArray(key))combined.getAsJsonArray(key).add(v.deepCopy());combined.addProperty("source_namespace","proutlost:physical_authoring_projection");combined.addProperty("export_revision","2");var input=root.resolve("proutlost/campaign/PRODUCER_G0_R56.json");Files.writeString(input,G.toJson(combined));survey(combined,input);
 }
 private static boolean inside(int[] b,BlockPos p){return p.getX()>=b[0]&&p.getX()<=b[3]&&p.getY()>=b[1]&&p.getY()<=b[4]&&p.getZ()>=b[2]&&p.getZ()<=b[5];}
 private static List<int[]> subtract(int[] b,BlockPos p){
  int x=p.getX(),y=p.getY(),z=p.getZ();var out=new ArrayList<int[]>();if(x>b[0])out.add(new int[]{b[0],b[1],b[2],x-1,b[4],b[5]});if(x<b[3])out.add(new int[]{x+1,b[1],b[2],b[3],b[4],b[5]});if(y>b[1])out.add(new int[]{x,b[1],b[2],x,y-1,b[5]});if(y<b[4])out.add(new int[]{x,y+1,b[2],x,b[4],b[5]});if(z>b[2])out.add(new int[]{x,y,b[2],x,y,z-1});if(z<b[5])out.add(new int[]{x,y,z+1,x,y,b[5]});return out;
 }
 private List<int[]> excludingEntities(List<int[]> input)throws Exception{
  var level=server.overworld();var boxes=new ArrayList<int[]>();
  for(var original:input){existing(new BlockPos(original[0],original[1],original[2]));existing(new BlockPos(original[3],original[4],original[5]));
   for(int x=original[0]>>4;x<=original[3]>>4;x++)for(int z=original[2]>>4;z<=original[5]>>4;z++)existing(new BlockPos(x*16,original[1],z*16));boxes.add(original);
   for(var p:BlockPos.betweenClosed(original[0],original[1],original[2],original[3],original[4],original[5]))if(level.getBlockEntity(p)!=null){var next=new ArrayList<int[]>();for(var b:boxes)if(inside(b,p))next.addAll(subtract(b,p));else next.add(b);boxes=next;if(boxes.size()>128)return null;}
  }
  return boxes;
 }
 private void survey(JsonObject g0,Path input)throws Exception{
  var results=new JsonArray();var skipped=new JsonArray();var disks=new TreeMap<String,String>();
  for(var v:g0.getAsJsonArray("locations")){
   var l=v.getAsJsonObject();String id=l.get("location_id").getAsString();var b=l.getAsJsonObject("bounds");int[] box={b.get("min_x").getAsInt(),b.get("min_y").getAsInt(),b.get("min_z").getAsInt(),b.get("max_x_exclusive").getAsInt()-1,b.get("max_y_exclusive").getAsInt()-1,b.get("max_z_exclusive").getAsInt()-1};
   long size=(long)(box[3]-box[0]+1)*(box[4]-box[1]+1)*(box[5]-box[2]+1);boolean bounded=false;List<int[]> boxes=size<=32768?excludingEntities(List.of(box)):null;
   if(boxes==null){bounded=true;var patches=new ArrayList<int[]>();for(var av:g0.getAsJsonArray("anchors")){var a=av.getAsJsonObject();if(!id.equals(a.get("location_id").getAsString()))continue;var p=a.getAsJsonObject("position");int x=p.get("x").getAsInt(),y=p.get("y").getAsInt(),z=p.get("z").getAsInt();patches.add(new int[]{Math.max(box[0],x-1),Math.max(box[1],y-1),Math.max(box[2],z-1),Math.min(box[3],x+1),Math.min(box[4],y+1),Math.min(box[5],z+1)});}boxes=excludingEntities(patches);}
   if(boxes==null||boxes.isEmpty())throw new IllegalStateException("No authentic functional geometry for "+id);
   var geometry=boxes.stream().map(a->new fr.proutlost.contract.director.Bounds(new fr.proutlost.contract.director.Id("minecraft:overworld"),new fr.proutlost.contract.director.BlockPos(a[0],a[1],a[2]),new fr.proutlost.contract.director.BlockPos(a[3],a[4],a[5]))).toList();var fingerprint=NativeLoadedCellWitness.stateFingerprint(server,geometry).orElseThrow();
   var standing=new JsonArray();for(var av:g0.getAsJsonArray("anchors")){var a=av.getAsJsonObject();if(!id.equals(a.get("location_id").getAsString()))continue;var pp=a.getAsJsonObject("position");var p=new BlockPos(pp.get("x").getAsInt(),pp.get("y").getAsInt(),pp.get("z").getAsInt());if(standing(server.overworld(),p)){var row=new JsonObject();row.addProperty("anchor_id",a.get("anchor_id").getAsString());row.add("position",G.toJsonTree(List.of(p.getX(),p.getY(),p.getZ())));standing.add(row);}}
   var r=new JsonObject();r.addProperty("location_id",id);r.add("geometry",G.toJsonTree(boxes));r.addProperty("state_sha256",fingerprint.value());r.add("standing_anchors",standing);r.addProperty("bounded_functional_windows",bounded);r.addProperty("source_full_emprise_cells",size);r.addProperty("observed_native_tick",server.getTickCount());results.add(r);
  }
  for(long key:loaded){int x=(int)(key>>32),z=(int)key;var p=world().resolve("region/r."+Math.floorDiv(x,32)+"."+Math.floorDiv(z,32)+".mca");String n=world().relativize(p).toString();if(!disks.containsKey(n))disks.put(n,hash(p));}
  var o=new JsonObject();o.addProperty("schema_version",1);o.addProperty("world_id",g0.get("world_id").getAsString());o.addProperty("source_origin_world_id","0cce2707-bd35-4d4b-874f-82103ef4209c");o.addProperty("world_seed",server.overworld().getSeed());o.addProperty("g0_source_sha256",hash(input));o.add("locations",results);o.add("disk_regions_sha256",G.toJsonTree(disks));o.add("skipped",skipped);o.addProperty("read_only",true);o.addProperty("new_chunks_generated",0);o.addProperty("humans",0);o.addProperty("campaign_manifest_sha256",hash(world().resolve("proutlost/campaign/manifest.json")));Files.writeString(world().resolve("proutlost/campaign/NATIVE_PHYSICAL_SURVEY_R56.json"),G.toJson(o));System.out.println("CAMPAIGN_AND_LEGACY_REAL_PHYSICAL_SURVEY locations="+results.size()+" bounded_legacy_windows=2");
 }
}
