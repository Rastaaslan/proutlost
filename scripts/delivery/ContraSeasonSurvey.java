package fr.proutlost.season;
import com.google.gson.*;
import java.nio.file.*;
import java.io.*;
import java.security.*;
import java.util.*;
import net.neoforged.fml.common.Mod;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.storage.LevelResource;
import fr.proutlost.contract.director.Bounds;
import fr.proutlost.contract.director.Id;
import fr.proutlost.integration.physical.NativeLoadedCellWitness;
import fr.proutlost.director.adapter.neoforge.server.lifecycle.DirectorSavedIdentity;
@Mod("proutlost_season_survey")
public final class ContraSeasonSurvey {
 private fr.proutlost.integration.contra.ContraRealDirectorCatalogImportProbe importer;
 private String sourceJson;
 public ContraSeasonSurvey(IEventBus bus){NeoForge.EVENT_BUS.addListener(this::commands);NeoForge.EVENT_BUS.addListener(this::tick);}
 private void tick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event){
  if(importer==null)return;
  try{var source=fr.proutlost.contract.director.StrictCodec.decode(sourceJson,fr.proutlost.contract.director.CatalogSnapshot.class);
   var admitted=importer.pollImportAndReadAdmitted(sourceJson,source.world_id(),source.build_id());
   if(admitted.isPresent()){System.out.println("CONTRA_SEASON_REAL_D0_IMPORTED_AND_READBACK entries="+admitted.orElseThrow().entries().size());importer=null;}
  }catch(Exception failure){failure.printStackTrace();importer=null;}
 }
 private void commands(RegisterCommandsEvent e){
 e.getDispatcher().register(Commands.literal("proutlost_season_import").requires(s->s.hasPermission(4)).executes(c->{
  try{var server=c.getSource().getServer();if(!server.getPlayerList().getPlayers().isEmpty())throw new IllegalStateException("Preparation requires no players");
   sourceJson=Files.readString(server.getWorldPath(LevelResource.ROOT).resolve("director-physical-catalogs/contra_saison2/catalog.json"));
   importer=new fr.proutlost.integration.contra.ContraRealDirectorCatalogImportProbe();System.out.println("CONTRA_SEASON_REAL_D0_IMPORT_POLL_STARTED");return 1;
  }catch(Exception x){x.printStackTrace();return 0;}
 }));e.getDispatcher().register(Commands.literal("proutlost_season_survey").requires(s->s.hasPermission(4)).executes(c->{
  try{survey(c.getSource().getServer());return 1;}catch(Exception x){x.printStackTrace();return 0;}
 }));}
 private static String hash(Path p)throws Exception{var h=MessageDigest.getInstance("SHA-256");try(var f=Files.newInputStream(p)){byte[] b=new byte[65536];for(int n;(n=f.read(b))!=-1;)h.update(b,0,n);}return HexFormat.of().formatHex(h.digest());}
 private static boolean existing(Path world,int x,int z)throws Exception{
  var file=world.resolve("region/r."+Math.floorDiv(x,32)+"."+Math.floorDiv(z,32)+".mca");if(!Files.isRegularFile(file))return false;
  try(var f=new RandomAccessFile(file.toFile(),"r")){f.seek(4L*(Math.floorMod(x,32)+32*Math.floorMod(z,32)));return(f.readInt()>>>8)!=0;}
 }
 private static List<int[]> subtract(int[] b,BlockPos p){
  int x=p.getX(),y=p.getY(),z=p.getZ();var out=new ArrayList<int[]>();
  if(x>b[0])out.add(new int[]{b[0],b[1],b[2],x-1,b[4],b[5]});
  if(x<b[3])out.add(new int[]{x+1,b[1],b[2],b[3],b[4],b[5]});
  if(y>b[1])out.add(new int[]{x,b[1],b[2],x,y-1,b[5]});
  if(y<b[4])out.add(new int[]{x,y+1,b[2],x,b[4],b[5]});
  if(z>b[2])out.add(new int[]{x,y,b[2],x,y,z-1});
  if(z<b[5])out.add(new int[]{x,y,z+1,x,y,b[5]});
  return out;
 }
 private static void survey(net.minecraft.server.MinecraftServer server)throws Exception{
  if(!server.isSameThread()||!server.getPlayerList().getPlayers().isEmpty())throw new IllegalStateException("Closed source survey requires real server actor and no players");
  var root=server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();var g0=root.resolve("director-location-imports/contra-functional-sites-v3.json");
  var json=JsonParser.parseString(Files.readString(g0)).getAsJsonObject();var level=server.overworld();
  var identity=level.getDataStorage().computeIfAbsent(DirectorSavedIdentity.FACTORY,DirectorSavedIdentity.KEY).worldId().value();
  var origin=JsonParser.parseString(Files.readString(root.resolve("proutlost/season-origin/context.json"))).getAsJsonObject();
  if(!origin.get("source_world_id").getAsString().equals(json.get("world_id").getAsString())||!origin.get("author_decision").getAsString().equals("PLS2-S2-AUTHOR-20261122-CONTRA-INITIAL"))throw new IllegalStateException("Explicit source/season context required");
  var results=new ArrayList<Map<String,Object>>();var disk=new TreeMap<String,String>();var skipped=new ArrayList<Map<String,Object>>();
  siteLoop: for(var item:json.getAsJsonArray("locations")){
   var site=item.getAsJsonObject();var b=site.getAsJsonObject("bounds");String id=site.get("location_id").getAsString();
   int[] box={b.get("min_x").getAsInt(),b.get("min_y").getAsInt(),b.get("min_z").getAsInt(),b.get("max_x_exclusive").getAsInt()-1,b.get("max_y_exclusive").getAsInt()-1,b.get("max_z_exclusive").getAsInt()-1};
   long cells=(long)(box[3]-box[0]+1)*(box[4]-box[1]+1)*(box[5]-box[2]+1);
   if(cells<1||cells>32768){skipped.add(Map.of("location",id,"reason","EXISTING_GEOMETRY_EXCEEDS_32768_NATIVE_WITNESS_BOUND","cells",cells));continue;}
   for(int x=box[0]>>4;x<=box[3]>>4;x++)for(int z=box[2]>>4;z<=box[5]>>4;z++){
    if(!existing(root,x,z))throw new IllegalStateException("Refusing generation of absent chunk "+x+","+z);
    level.getChunk(x,z);
    var region=root.resolve("region/r."+Math.floorDiv(x,32)+"."+Math.floorDiv(z,32)+".mca");
    String rel=root.relativize(region).toString();if(!disk.containsKey(rel))disk.put(rel,hash(region));
   }
   var boxes=new ArrayList<int[]>();boxes.add(box);int excluded=0;
   for(var p:BlockPos.betweenClosed(box[0],box[1],box[2],box[3],box[4],box[5])){
    if(level.getBlockEntity(p)==null)continue;var copy=p.immutable();var next=new ArrayList<int[]>();
    for(var v:boxes)if(copy.getX()>=v[0]&&copy.getX()<=v[3]&&copy.getY()>=v[1]&&copy.getY()<=v[4]&&copy.getZ()>=v[2]&&copy.getZ()<=v[5])next.addAll(subtract(v,copy));else next.add(v);
    boxes=next;excluded++;if(boxes.size()>128){skipped.add(Map.of("location",id,"reason","PROTECTED_GEOMETRY_EXCEEDS_128_READ_ONLY_FRAGMENTS"));continue siteLoop;}
   }
   var geometry=boxes.stream().map(v->new Bounds(new Id("minecraft:overworld"),new fr.proutlost.contract.director.BlockPos(v[0],v[1],v[2]),new fr.proutlost.contract.director.BlockPos(v[3],v[4],v[5]))).toList();
   var fingerprint=NativeLoadedCellWitness.stateFingerprint(server,geometry).orElseThrow();
   var standing=new ArrayList<Map<String,Object>>();
   for(var a:json.getAsJsonArray("anchors")){
    var an=a.getAsJsonObject();if(!id.equals(an.get("location_id").getAsString()))continue;var xyz=an.getAsJsonObject("position");
    int x=(int)Math.floor(xyz.get("x").getAsDouble()),y=(int)Math.floor(xyz.get("y").getAsDouble()),z=(int)Math.floor(xyz.get("z").getAsDouble());var p=new BlockPos(x,y,z);
    if(!level.hasChunkAt(p))continue;
    boolean clear=level.getBlockState(p).getCollisionShape(level,p).isEmpty()&&level.getBlockState(p.above()).getCollisionShape(level,p.above()).isEmpty()&&level.getBlockState(p.below()).isFaceSturdy(level,p.below(),Direction.UP);
    if(clear)standing.add(Map.of("anchor_id",an.get("anchor_id").getAsString(),"position",List.of(x,y,z)));
   }
   results.add(Map.of("location_id",id,"geometry",boxes,"state_sha256",fingerprint.value(),"standing_anchors",standing,"protected_block_entities_excluded",excluded,"observed_native_tick",server.getTickCount()));
  }
  var output=new TreeMap<String,Object>();output.put("schema_version",1);output.put("world_id",identity.toString());output.put("source_origin_world_id",json.get("world_id").getAsString());output.put("world_seed",level.getSeed());output.put("g0_source_sha256",hash(g0));output.put("locations",results);output.put("disk_regions_sha256",disk);output.put("skipped",skipped);output.put("read_only",true);output.put("new_chunks_generated",0);output.put("humans",0);
  var target=root.resolve("proutlost/season-authoring/NATIVE_PHYSICAL_SURVEY.json");Files.createDirectories(target.getParent());Files.writeString(target,new GsonBuilder().setPrettyPrinting().create().toJson(output));
  json.addProperty("world_id",identity.toString());json.addProperty("export_revision","4");json.addProperty("export_digest","0".repeat(64));
  byte[] raw=new Gson().toJson(json).getBytes(java.nio.charset.StandardCharsets.UTF_8);
  var codec=new fr.proutlost.director.api.codec.ModelCodec<>(fr.proutlost.director.api.model.LocationImport.class);
  var imported=codec.decode(raw);var actual=new fr.proutlost.director.api.model.LocationImport(1,imported.worldId(),imported.sourceNamespace(),imported.exportRevision(),fr.proutlost.director.integration.locations.StandaloneLocationImporter.digest(imported),imported.regions(),imported.locations(),imported.anchors());
  Files.write(root.resolve("director-location-imports/contra-saison2.json"),codec.encode(actual));
  System.out.println("CONTRA_SEASON_NATIVE_PHYSICAL_SURVEY_WRITTEN locations="+results.size()+" skipped="+skipped.size()+" path="+target);
 }
}

