package fr.proutlost.director.adapter.neoforge.server;

import fr.proutlost.director.api.codec.ModelCodec;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.port.DurableStore;
import fr.proutlost.director.bootstrap.*;
import fr.proutlost.director.integration.locations.StandaloneLocationImporter;
import fr.proutlost.director.neoforge.DirectorStandaloneMod;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.gametest.framework.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.*;

/** Read-only observer of the EXISTING actor's real OP4 import. Never submits,
 * appends, spawns a player, invents physical receipts, or opens a second store.
 * Run only after real WorldPrep export and the connected QA operator command.
 * The sidecar is observer evidence, not a Director ACK. */
@GameTestHolder("proutlost_director")
@PrefixGameTestTemplate(false)
public final class DirectorNativeWorldPrepImportGameTests {
 private static final ModelCodec<LocationImport> INPUT=new ModelCodec<>(LocationImport.class);
 private static final String SOURCE="proutlost:worldprep_structures";
 private static final String SIDECAR="fullstack-worldprep-import-checkpoint.properties";
 private static final long PID=ProcessHandle.current().pid();
 private static final long START=ManagementFactory.getRuntimeMXBean().getStartTime();
 private DirectorNativeWorldPrepImportGameTests(){}
 /** Serialize the existing complete command tree after real registry bootstrap.
  * No player is constructed, no permission is granted, and no command executes. */
 @GameTestGenerator public static Collection<TestFunction> worldPrepCommandPacketObserver(){
  if(!Boolean.getBoolean("proutlost.director.nativeWorldPrepCommandPacketGameTest"))return List.of();
  if(!Boolean.getBoolean("proutlost.director.nativeLab")
    ||!Boolean.getBoolean("proutlost.director.nativeGameTests")
    ||!Boolean.getBoolean("proutlost.quest.neutralLab"))
   throw new IllegalArgumentException("explicit native LAB and neutral TEST_ONLY flags required");
  return List.of(new TestFunction("fullstack_worldprep_command_packet",
   "worldprep_actual_command_tree_packet_roundtrip","proutlost_director:smoke_empty",
   Rotation.NONE,200,0,true,DirectorNativeWorldPrepImportGameTests::commandPacket));
 }
 @SuppressWarnings("unchecked")
 private static void commandPacket(GameTestHelper h){
  MinecraftServer server=h.getLevel().getServer();
  h.assertTrue(server.isSameThread(),"command packet observer must run on actual server thread");
  try{
   Path world=realWorld(server),configured=Path.of(System.getProperty("proutlost.quest.neutralLabRoot","")).toRealPath();
   h.assertTrue(world.equals(configured),"actual world must equal the explicitly owned neutral TEST_ONLY world");
   Path marker=world.resolve("TEST_ONLY");
   h.assertTrue(!Files.isSymbolicLink(marker)&&Files.isRegularFile(marker,LinkOption.NOFOLLOW_LINKS)
     &&Files.size(marker)>0&&Files.size(marker)<=128,"owned neutral TEST_ONLY marker required");
   byte[] markerBytes;
   try(var input=Files.newInputStream(marker,LinkOption.NOFOLLOW_LINKS)){markerBytes=input.readNBytes(129);}
   h.assertTrue(markerBytes.length>0&&markerBytes.length<=128
     &&new String(markerBytes,StandardCharsets.UTF_8).trim().equals("PROUTLOST_NEUTRAL_QA_V1"),"exact owned neutral TEST_ONLY marker required");
  }catch(IOException e){throw new IllegalStateException("owned neutral TEST_ONLY world unreadable",e);}
  var actual=server.getCommands().getDispatcher().getRoot();
  int identifiers=worldPrepArgumentCount(actual,h);
  h.assertTrue(identifiers>0,"the actual command tree must contain the registered WorldPrep identifier parser");
  var root=(com.mojang.brigadier.tree.RootCommandNode<net.minecraft.commands.SharedSuggestionProvider>)
   (com.mojang.brigadier.tree.RootCommandNode<?>)actual;
  // The constructor performs the same argument metadata lookup that failed
  // during Commands.sendCommands/PlayerList.placeNewPlayer for the old lambda.
  var packet=new net.minecraft.network.protocol.game.ClientboundCommandsPacket(root);
  int maximum=8*1024*1024;
  var buffer=new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer(1024,maximum));
  try{
   net.minecraft.network.protocol.game.ClientboundCommandsPacket.STREAM_CODEC.encode(buffer,packet);
   int bytes=buffer.readableBytes();
   h.assertTrue(bytes>0&&bytes<=maximum,"actual command packet must fit the explicit 8 MiB test bound");
   var decoded=net.minecraft.network.protocol.game.ClientboundCommandsPacket.STREAM_CODEC.decode(buffer);
   h.assertTrue(decoded!=null&&buffer.readableBytes()==0,"actual registered command packet must decode with exact byte consumption");
   System.out.println("FULLSTACK_WORLDPREP_COMMAND_PACKET_NATIVE_ENCODE_PASS bytes="+bytes
    +" identifiers="+identifiers+" whole_actual_tree=true no_player=true actual_player_join_qualification_separate=true");
  }finally{buffer.release();}
  h.succeed();
 }
 private static int worldPrepArgumentCount(com.mojang.brigadier.tree.CommandNode<?> node,GameTestHelper h){
  int found=0;
  if(node instanceof com.mojang.brigadier.tree.ArgumentCommandNode<?,?> argument
    &&argument.getType().getClass().getName().equals("fr.proutlost.worldprep.WorldPrepIdArgument")){
   var info=net.minecraft.commands.synchronization.ArgumentTypeInfos.byClass(argument.getType());
   h.assertTrue(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("proutlost","worldprep_id")
     .equals(net.minecraft.core.registries.BuiltInRegistries.COMMAND_ARGUMENT_TYPE.getKey(info)),
    "actual WorldPrep argument class must map to the actual registered wire ID");
   found++;
  }
  for(var child:node.getChildren())found+=worldPrepArgumentCount(child,h);
  return found;
 }
 @GameTestGenerator public static Collection<TestFunction> worldPrepImportObserver(){
  if(!Boolean.getBoolean("proutlost.director.nativeWorldPrepImportGameTest"))return List.of();
  if(!Boolean.getBoolean("proutlost.director.nativeLab")||!Boolean.getBoolean("proutlost.director.nativeGameTests"))
   throw new IllegalArgumentException("explicit native LAB observer flags required");
  boolean cold=Boolean.getBoolean("proutlost.director.nativeWorldPrepImportRecoveryOnly");
  return List.of(new TestFunction("fullstack_worldprep_import",
   cold?"worldprep_import_same_directory_cold_readback":"worldprep_import_actual_j04_readback",
   "proutlost_director:smoke_empty",Rotation.NONE,2400,0,true,h->{
    Observer observer=new Observer(h,cold);
    h.succeedWhen(()->{observer.poll();h.assertTrue(observer.done,"actual WorldPrep import readback pending");});
   }));
 }
 private static String txt(DirectorFact f,String name){return ((JsonValue.Text)Objects.requireNonNull(f.payload().get(name),name)).value();}
 private static Aabb box(DimensionId d,ImportBounds b){return new Aabb(d,b.minX(),b.minY(),b.minZ(),b.maxXExclusive(),b.maxYExclusive(),b.maxZExclusive());}
 private static Path realWorld(MinecraftServer s)throws IOException{return s.getWorldPath(LevelResource.ROOT).toRealPath();}
 private static String fileKey(Path p)throws IOException{return Objects.toString(Files.readAttributes(p,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS).fileKey(),"");}
 private static String hash(byte[] b){return BlobContent.hash(b).value();}
 private static final class Observer {
  final GameTestHelper h;final MinecraftServer server;final boolean cold;
  final DirectorServerLifecycle life;final Path root;final String rootKey;
  final LocationImport input;final String inputSha;final Set<AggregateKey> keys=new HashSet<>();
  final Map<FactId,DirectorFact> facts=new HashMap<>();final Map<AggregateKey,AggregateState> states=new HashMap<>();
  DurableStore store;StoreSession session;StoreQuery query;CompletableFuture<StoreReadResult> pending;
  Optional<String> cursor=Optional.empty();int pages;long cut;boolean done;
  Observer(GameTestHelper h,boolean cold){
   this.h=h;this.cold=cold;server=h.getLevel().getServer();life=DirectorStandaloneMod.directorLifecycle();
   try{
    root=realWorld(server);rootKey=fileKey(root);
    Path dir=root.resolve("director-location-imports"),p=dir.resolve("worldprep-structures.json");
    require(!Files.isSymbolicLink(dir)&&!Files.isSymbolicLink(p)&&Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS),"actual bridge export file required");
    require(Files.size(p)>0&&Files.size(p)<=32768,"keep production 32768-byte import bound");
    byte[] bytes=Files.readAllBytes(p);require(bytes.length<=32768,"export changed or oversized");
    inputSha=hash(bytes);input=INPUT.decode(bytes);
    require(input.sourceNamespace().value().equals(SOURCE)&&input.exportDigest().equals(StandaloneLocationImporter.digest(input)),"bridge source/digest mismatch");
    require(input.regions().size()+input.locations().size()+input.anchors().size()<=96&&!input.locations().isEmpty(),"keep production 96-entry bound; no empty proof");
    input.regions().forEach(r->{keys.add(new AggregateKey(AggregateKind.REGION,r.regionId().value()));keys.add(new AggregateKey(AggregateKind.REGION_MEMORY,r.regionId().value()));});
    input.locations().forEach(l->keys.add(new AggregateKey(AggregateKind.LOCATION,l.locationId().value())));
    input.anchors().forEach(a->keys.add(new AggregateKey(AggregateKind.ANCHOR,a.anchorId().value())));
   }catch(IOException e){throw new IllegalStateException("actual bridge input unreadable",e);}
  }
  void require(boolean yes,String why){h.assertTrue(yes,why);}
  void row(AggregateState value){
   if(!keys.contains(value.key()))return;
   var old=states.get(value.key());
   if(old==null||value.header().revision().compareTo(old.header().revision())>0)states.put(value.key(),value);
   else if(value.header().revision().equals(old.header().revision()))require(old.equals(value),"conflicting exact aggregate revision");
  }
  void accept(DurableRecord record){
   if(record instanceof DurableAggregate a)row(a.state());
   if(record instanceof DurableFact f&&f.value().factType().equals(DirectorNativeLocationImports.APPLIED)){
    var old=facts.putIfAbsent(f.value().factId(),f.value());require(old==null||old.equals(f.value()),"conflicting manifest fact ID");
   }
  }
  void poll(){
   if(done)return;
   require(server.isSameThread(),"native observer must use existing server actor");
   var gameplay=life.runtimeGameplay(server);var candidate=life.labReadOnlyStoreSnapshot(server);
   if(gameplay.isEmpty()||candidate.isEmpty())return;
   var game=gameplay.orElseThrow();String status=game.locationImportStatus();
   require(!status.contains("FAILED_SAFE"),"existing import failed safe: "+status);
   // Initial command must instantiate/recover the import lane; no private-field access.
   if(!status.startsWith("LOCATION_IMPORT IDLE")||!status.contains("lane=false")
     ||!status.endsWith(cold?" ALREADY_DURABLE":" DURABLE"))return;
   if(store==null){store=candidate.orElseThrow();session=store.session();require(input.worldId().equals(session.worldId()),"input belongs to another actual world");}
   require(session.equals(candidate.orElseThrow().session()),"J04 actor changed during observer scan");
   if(pending==null){query=new StoreQuery(session.worldId(),Set.of(),new Sequence(0),cursor,128);pending=store.read(query).toCompletableFuture();return;}
   if(!pending.isDone())return;
   require(!pending.isCompletedExceptionally(),"actual J04 read failed");
   var read=pending.getNow(null);pending=null;ReadContracts.validate(read,query,session);
   if(read.status()==ReadStatus.STALE){states.clear();facts.clear();cursor=Optional.empty();pages=0;cut=0;return;}
   require(read.status()==ReadStatus.AVAILABLE,"actual J04 read unavailable: "+read.status());
   var page=read.page().orElseThrow();cut=page.lastDurableSequence().value();page.records().forEach(this::accept);page.aggregates().forEach(a->row(a.state()));
   require(++pages<=256,"bounded read-only WAL observer");cursor=page.nextCursor();if(page.hasMore())return;
   verify(game);done=true;
  }
  void verify(DirectorNativeGameplay game){
   require(states.keySet().equals(keys),"J04 missing exact imported aggregates");
   var matching=facts.values().stream().filter(f->SOURCE.equals(txt(f,"source_namespace"))&&input.exportDigest().value().equals(txt(f,"export_digest"))&&Long.toString(input.exportRevision().value()).equals(txt(f,"export_revision"))).toList();
   require(matching.size()==1,"exactly one durable original import manifest required");var fact=matching.getFirst();
   var expectedId=new FactId(UUID.nameUUIDFromBytes(("director-location-import-v1/"+input.worldId().value()+"/"+SOURCE+"/"+input.exportRevision().value()+"/"+input.exportDigest().value()).getBytes(StandardCharsets.UTF_8)));
   require(fact.factId().equals(expectedId)&&fact.durable()&&fact.evidenceStatus()==EvidenceStatus.CONFIRMED&&fact.eventId().isEmpty()&&fact.subjectRefs().contains(new ScopeRef(ScopeKind.WORLD,input.worldId().value())),"manifest identity/evidence invalid");
   require(INPUT.decode(Base64.getDecoder().decode(txt(fact,"location_import_base64"))).equals(input),"durable manifest differs from actual bridge input");
   MemoryView memory=life.runtimeMemoryView(server).orElseThrow();require(memory.scopes().worldId().equals(input.worldId()),"memory belongs to another world");
   for(var r:input.regions()){
    var row=((RegionAggregate)states.get(new AggregateKey(AggregateKind.REGION,r.regionId().value()))).value();
    require(row.equals(memory.regionRecords().get(r.regionId()))&&row.status()==RegionStatus.ACTIVE&&row.dimension().equals(r.dimension())&&row.supportShape().equals(new SupportShape(List.of(box(r.dimension(),r.bounds())),List.of(),List.of())),"region J04/Memory geometry mismatch");
    Set<AnchorId> expectedRegionAnchors=new HashSet<>();
    for(var a:input.anchors())if(input.locations().stream().filter(l->l.locationId().equals(a.locationId())).findFirst().orElseThrow().regionIds().contains(r.regionId()))expectedRegionAnchors.add(a.anchorId());
    require(row.anchorIds().equals(expectedRegionAnchors),"region exact anchor membership mismatch");
    var rm=((RegionMemoryAggregate)states.get(new AggregateKey(AggregateKind.REGION_MEMORY,r.regionId().value()))).value();
    require(memory.regions().stream().filter(m->m.regionId().equals(r.regionId())&&m.equals(rm)).count()==1,"region memory readback mismatch");
   }
   for(var l:input.locations()){
    var row=((LocationAggregate)states.get(new AggregateKey(AggregateKind.LOCATION,l.locationId().value()))).value();
    Set<AnchorId> anchors=new HashSet<>();input.anchors().stream().filter(a->a.locationId().equals(l.locationId())).forEach(a->anchors.add(a.anchorId()));
    require(!l.regionIds().isEmpty()&&!anchors.isEmpty(),"bridge location must retain witnessed region/anchor memberships");
    require(row.equals(memory.locations().get(l.locationId()))&&row.active()&&row.sourceNamespace().equals(input.sourceNamespace())&&row.importRevision().equals(input.exportRevision())&&row.externalId().equals(l.externalId())&&row.dimension().equals(l.dimension())&&row.bounds().equals(box(l.dimension(),l.bounds()))&&row.tags().equals(l.tags())&&row.anchorIds().equals(anchors),"location exact J04/Memory readback mismatch");
   }
   for(var a:input.anchors()){
    var row=((AnchorAggregate)states.get(new AggregateKey(AggregateKind.ANCHOR,a.anchorId().value()))).value();
    var location=input.locations().stream().filter(l->l.locationId().equals(a.locationId())).findFirst().orElseThrow();
    require(row.equals(memory.anchors().get(a.anchorId()))&&row.active()&&row.origin()==AnchorOrigin.IMPORTED&&row.evidence()==EvidenceStatus.CONFIRMED&&row.locationId().equals(Optional.of(a.locationId()))&&row.regionIds().equals(location.regionIds())&&row.dimension().equals(a.dimension())&&row.position().equals(new BlockPosition(a.dimension(),a.position().x(),a.position().y(),a.position().z()))&&row.tags().equals(a.tags()),"anchor exact J04/Memory readback mismatch");
   }
   var authored=game.importedRegionTags().orElseThrow(()->new IllegalStateException("semantic region projection unavailable"));
   for(var r:input.regions()){
    Set<TagId> expectedTags=new HashSet<>(r.tags());
    input.locations().stream().filter(l->l.regionIds().contains(r.regionId())).forEach(l->expectedTags.addAll(l.tags()));
    require(Set.copyOf(expectedTags).equals(authored.get(r.regionId())),"exact regional authored tag union mismatch");
   }
   require(game.locationImportStatus().endsWith(cold?" ALREADY_DURABLE":" DURABLE"),"matching successful operator command terminal state changed");
   try{checkpoint(fact.factId());}catch(IOException e){throw new IllegalStateException("same-directory proof checkpoint failed",e);}
   System.out.println("FULLSTACK_WORLDPREP_IMPORT_J04_READBACK cold="+cold+" world="+input.worldId().value()+" boot_epoch="+session.bootEpoch().value()+" cut="+cut+" input_sha256="+inputSha+" digest="+input.exportDigest().value()+" regions="+input.regions().size()+" locations="+input.locations().size()+" anchors="+input.anchors().size()+" physical_and_whole_catalogue_qualification_separate=true");
  }
  void checkpoint(FactId manifest)throws IOException{
   require(root.equals(realWorld(server))&&rootKey.equals(fileKey(root)),"actual world directory changed during test");
   Path p=root.resolve(SIDECAR);Properties values=new Properties();
   values.setProperty("world",input.worldId().value().toString());values.setProperty("directory",root.toString());values.setProperty("directory_file_key",rootKey);
   values.setProperty("input_sha256",inputSha);values.setProperty("export_digest",input.exportDigest().value());values.setProperty("manifest",manifest.value().toString());
   values.setProperty("boot_epoch",Long.toString(session.bootEpoch().value()));values.setProperty("cut",Long.toString(cut));values.setProperty("pid",Long.toString(PID));values.setProperty("process_start",Long.toString(START));
   if(cold){
    require(!Files.isSymbolicLink(p)&&Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)&&Files.size(p)<=8192,"actual first-run observer checkpoint required");
    Properties prior=new Properties();try(var in=Files.newInputStream(p)){prior.load(in);}
    for(String key:List.of("world","directory","directory_file_key","input_sha256","export_digest","manifest"))require(values.getProperty(key).equals(prior.getProperty(key)),"cold original import identity changed: "+key);
    require(Long.parseLong(prior.getProperty("boot_epoch"))<session.bootEpoch().value()&&Long.parseLong(prior.getProperty("process_start"))!=START&&Long.parseLong(prior.getProperty("pid"))>0&&Long.parseLong(prior.getProperty("cut"))<=cut,"genuine new JVM start/J04 epoch and nonregressing cut required (OS PID reuse allowed)");return;
   }
   require(!Files.exists(p,LinkOption.NOFOLLOW_LINKS),"preserve first checkpoint; restart with recoveryOnly=true");
   Path temp=Files.createTempFile(root,".worldprep-import-observer-",".tmp");
   try{
    var bytes=new java.io.ByteArrayOutputStream();values.store(bytes,"TEST_ONLY observer evidence; NOT an import ACK");
    try(var ch=FileChannel.open(temp,StandardOpenOption.WRITE)){ByteBuffer buffer=ByteBuffer.wrap(bytes.toByteArray());while(buffer.hasRemaining())ch.write(buffer);ch.force(true);}
    Files.move(temp,p,StandardCopyOption.ATOMIC_MOVE);try(var dir=FileChannel.open(root,StandardOpenOption.READ)){dir.force(true);}
   }finally{Files.deleteIfExists(temp);}
  }
 }
}
