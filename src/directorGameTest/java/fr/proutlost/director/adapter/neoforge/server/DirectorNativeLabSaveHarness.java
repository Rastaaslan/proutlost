package fr.proutlost.director.adapter.neoforge.server;

import fr.proutlost.director.adapter.neoforge.server.ownership.EntityDirectorAttachment;
import fr.proutlost.director.api.codec.ModelCodec;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.neoforge.DirectorStandaloneMod;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Native GameTest source set ONLY. A world save is separate from a J04 ACK.
 * Load this class in the existing native-suite reflection list so the stop
 * mailbox is installed even before the physical Fauna test reaches success.
 */
public final class DirectorNativeLabSaveHarness {
 private DirectorNativeLabSaveHarness(){}
 private static final ModelCodec<EntityOwnershipRecord> OWNER=new ModelCodec<>(EntityOwnershipRecord.class);
 private static final String REQUEST="director-native-stop.request";
 private static final Set<MinecraftServer> STOPPING=Collections.newSetFromMap(new IdentityHashMap<>());
 static {
  if(Boolean.getBoolean("proutlost.director.nativeGameTests"))
   NeoForge.EVENT_BUS.addListener(DirectorNativeLabSaveHarness::pollStopRequest);
 }
 private static void actor(MinecraftServer server){
  if(!(server instanceof GameTestServer)||!Boolean.getBoolean("proutlost.director.nativeGameTests")||
     !Boolean.getBoolean("proutlost.director.nativeLab")||!server.isSameThread())
   throw new IllegalStateException("explicit native GameTest server actor required");
 }
 private static Path world(MinecraftServer server){return server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();}
 private static Path region(Path root,int cx,int cz){return root.resolve("entities").resolve("r."+(cx>>5)+"."+(cz>>5)+".mca");}
 private static String sha(byte[] bytes){return BlobContent.hash(bytes).value();}
 private static String fileProof(Path path)throws IOException{
  if(!Files.isRegularFile(path))throw new IOException("native saved file missing: "+path);
  return path+" bytes="+Files.size(path)+" mtime="+Files.getLastModifiedTime(path)+" sha256="+sha(Files.readAllBytes(path));
 }
 private static void saveAndReport(MinecraftServer server,String reason,Set<Path> expectedFiles)throws IOException{
  actor(server);System.out.println("DIRECTOR_NATIVE_MINECRAFT_SAVE_BEGIN reason="+reason+" world="+world(server)+" game_time="+server.overworld().getGameTime());
  boolean saved=server.saveEverything(false,true,true);
  // Read file hashes only AFTER the pinned forced+flush save returns.
  // The external stopped-world NBT check must additionally verify every UUID
  // and attachment; hashes or a Java return value alone never grant PASS.
  for(var path:expectedFiles.stream().sorted().toList())
   System.out.println("DIRECTOR_NATIVE_SAVED_FILE "+fileProof(path));
  System.out.println("DIRECTOR_NATIVE_MINECRAFT_SAVE_COMPLETE reason="+reason+" return="+saved+" world="+world(server));
 }
 public static void saveFaunaCheckpoint(MinecraftServer server,StoreSession session,long walCut,
      UUID event,Set<UUID> ids,String expectedOwnerHash){
  saveFaunaCheckpoint(server,session,walCut,event,ids,expectedOwnerHash,new ResourceId("minecraft:sheep"));
 }
 public static void saveFaunaCheckpoint(MinecraftServer server,StoreSession session,long walCut,
      UUID event,Set<UUID> ids,String expectedOwnerHash,ResourceId species){
  actor(server);
  if(!Set.of(new ResourceId("minecraft:sheep"),new ResourceId("reanimal:capybara")).contains(species))
   throw new IllegalArgumentException("explicit native fauna checkpoint species profile required");
  if(ids.size()!=24||walCut<=0)throw new IllegalArgumentException("actual24 and verified J04 cut required");
  var current=DirectorStandaloneMod.directorLifecycle().labReadOnlyStoreSnapshot(server).orElseThrow(()->new IllegalStateException("J04 must remain live before Minecraft checkpoint"));
  if(!current.session().equals(session))throw new IllegalStateException("checkpoint J04 session changed");
  var files=new HashSet<Path>();files.add(world(server).resolve("level.dat"));var owners=new TreeMap<UUID,EntityOwnershipRecord>();
  for(var id:ids){
   var entity=server.overworld().getEntity(id);
   if(!(entity instanceof Animal animal)||!animal.isAlive()
     ||!BuiltInRegistries.ENTITY_TYPE.getKey(animal.getType()).toString().equals(species.value()))
    throw new IllegalStateException("original exact-species native animal unavailable before save: "+id);
   var owner=EntityDirectorAttachment.read(entity).orElseThrow();
   if(!owner.entityUuid().equals(id)||!owner.instanceId().value().equals(event)||owner.phase()!=EntityPhase.SETTLED||owner.disposition()!=OwnershipDisposition.DIRECTOR_DURABLE)
    throw new IllegalStateException("native checkpoint cannot certify unsettled/unowned fauna");
   owners.put(id,owner);files.add(region(world(server),entity.chunkPosition().x,entity.chunkPosition().z));
  }
  var text=new StringBuilder();owners.forEach((id,owner)->text.append(id).append(':').append(sha(OWNER.encode(owner))).append('\n'));
  String actual=sha(text.toString().getBytes(StandardCharsets.UTF_8));
  if(!actual.equals(expectedOwnerHash))throw new IllegalStateException("native owners changed after verified J04 cut");
  try{saveAndReport(server,"FAUNA24_BEFORE_TEST_FINISH",files);}catch(IOException failure){throw new IllegalStateException("native Minecraft checkpoint failed",failure);}
  if(!current.session().equals(session))throw new IllegalStateException("J04 session revoked during Minecraft checkpoint");
  System.out.println("DIRECTOR_NATIVE_FAUNA24_WORLD_CHECKPOINT species="+species.value()+" event="+event+" world_id="+session.worldId().value()+" boot_epoch="+session.bootEpoch().value()+" wal_cut="+walCut+" owner_sha256="+actual+" original_uuids="+new TreeSet<>(ids));
 }
 private static void pollStopRequest(ServerTickEvent.Post event){
  var server=event.getServer();if(!(server instanceof GameTestServer)||
   !Boolean.getBoolean("proutlost.director.nativeGameTests")||!Boolean.getBoolean("proutlost.director.nativeLab")||
   STOPPING.contains(server)||server.getTickCount()%20!=0)return;
  actor(server);Path request=world(server).resolve(REQUEST);
  try{
   if(!Files.isRegularFile(request)||!Files.readString(request,StandardCharsets.UTF_8).trim().equals("SAVE_AND_HALT"))return;
   var files=new HashSet<Path>();files.add(world(server).resolve("level.dat"));
   for(var entity:server.overworld().getAllEntities())if(EntityDirectorAttachment.read(entity).isPresent())
    files.add(region(world(server),entity.chunkPosition().x,entity.chunkPosition().z));
   saveAndReport(server,"CONTROLLED_NATIVE_STOP",files);
   Files.delete(request);STOPPING.add(server);
   System.out.println("DIRECTOR_NATIVE_CONTROLLED_HALT_REQUESTED world="+world(server)+" tests_pass_claimed=false");
   // Exit the server loop normally. Its finally block calls stopServer,
   // saves worlds, closes storages, emits lifecycle events, then exits.
   server.halt(false);
  }catch(IOException|RuntimeException failure){
   // Preserve the running process if saving failed; never replace this with SIGTERM.
   System.out.println("DIRECTOR_NATIVE_CONTROLLED_STOP_SAVE_FAILED "+failure);failure.printStackTrace();
  }
 }
}
