package fr.proutlost.quest.qa;

import com.google.gson.*;
import com.mojang.brigadier.arguments.StringArgumentType;
import fr.proutlost.director.adapter.neoforge.network.DirectorNativeNetworkBootstrap;
import fr.proutlost.director.adapter.neoforge.server.effects.*;
import fr.proutlost.director.adapter.neoforge.server.lifecycle.DirectorSavedIdentity;
import fr.proutlost.director.api.codec.ModelCodec;
import fr.proutlost.director.api.error.ReasonCode;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.permission.CommitPermit;
import fr.proutlost.director.api.port.PlanningPort;
import fr.proutlost.director.bootstrap.*;
import fr.proutlost.director.engine.*;
import fr.proutlost.director.integration.quest.DirectorCommittedFactEvent;
import fr.proutlost.director.persistence.*;
import fr.proutlost.director.runtime.*;
import fr.proutlost.quest.core.QuestCore;
import fr.proutlost.quest.runtime.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Distinct real-client QA: server-owned J06 sessions and scoreboard teams,
 * native J03/admission/J04/J05/lifecycle/Quest. No fake players, canon, terrain generation, fixture
 * reset, journal injection or hand-posted completion event is performed here. */
public final class RealGroupClientsQaRuntime {
 private RealGroupClientsQaRuntime(){}
 private static final String FILE=RealGroupClientsQaSavedData.FILE_ID;
 private static final Map<MinecraftServer,Probe> ACTIVE=new IdentityHashMap<>();
 private static final StoreOptions OPTIONS=new StoreOptions(1_048_620,2_097_152,4_194_304,16_777_216,32,0,128);
 private static final BudgetVector CAP=new BudgetVector(100,100,100,100,100,100,100,100,100,100);
 public static void install(){
  NeoForge.EVENT_BUS.addListener(RealGroupClientsQaRuntime::register);
  NeoForge.EVENT_BUS.addListener(RealGroupClientsQaRuntime::started);
  NeoForge.EVENT_BUS.addListener(RealGroupClientsQaRuntime::tick);
  NeoForge.EVENT_BUS.addListener(RealGroupClientsQaRuntime::stopped);
  NeoForge.EVENT_BUS.addListener(RealGroupClientsQaRuntime::confirmed);
 }
 private static Path guard(MinecraftServer server){
  var root=DirectorRealGroupClientsQaFence.require(server);
  if(QuestConfig.ENABLED.get()||QuestConfig.TEST_COMMANDS.get())throw new IllegalStateException("canonical campaign must be disabled");
  return root;
 }
 private static void started(ServerStartedEvent event){guard(event.getServer());}
 private static void register(RegisterCommandsEvent event){
  var root=Commands.literal("real_group_qa").requires(s->Boolean.getBoolean("proutlost.quest.realGroupClientsQa"));
  root.then(Commands.literal("produce").requires(s->s.hasPermission(4)).then(Commands.argument("first",EntityArgument.player())
   .then(Commands.argument("second",EntityArgument.player()).then(Commands.argument("cell",BlockPosArgument.blockPos()).executes(c->{
   var server=c.getSource().getServer();guard(server);
   if(ACTIVE.containsKey(server))throw new IllegalStateException("public GROUP QA already active");
   var cell=BlockPosArgument.getLoadedBlockPos(c,"cell");
   try{ACTIVE.put(server,new Probe(server,cell,false,List.of(EntityArgument.getPlayer(c,"first"),EntityArgument.getPlayer(c,"second"))));}catch(java.io.IOException e){throw new IllegalStateException(e);}
   c.getSource().sendSuccess(()->Component.literal("REAL_GROUP_CLIENTS_QA_STARTED TEST_ONLY; inspect status and retained receipt"),false);return 1;
  })))));
  root.then(Commands.literal("reopen").requires(s->s.hasPermission(4)).executes(c->{
   var server=c.getSource().getServer();guard(server);
   if(ACTIVE.containsKey(server))throw new IllegalStateException("new JVM required; QA already active");
   try{ACTIVE.put(server,new Probe(server,null,true,List.of()));}catch(java.io.IOException e){throw new IllegalStateException(e);}
   return 1;
  }));
  root.then(Commands.literal("status").requires(s->s.hasPermission(4)).executes(c->{
   guard(c.getSource().getServer());var p=ACTIVE.get(c.getSource().getServer());
   c.getSource().sendSuccess(()->Component.literal(p==null?"REAL_GROUP_CLIENTS_QA_NOT_STARTED":p.status()),false);return 1;
  }));
  root.then(Commands.literal("journal").executes(c->{
   var player=c.getSource().getPlayerOrException();guard(player.server);
   var p=Objects.requireNonNull(ACTIVE.get(player.server),"start or reopen this distinct QA first");
   try{p.journal(player);return 1;}catch(IllegalStateException|SecurityException refused){c.getSource().sendFailure(Component.literal("QA GROUP refusé : "+refused.getMessage()));return 0;}
  }));
  root.then(Commands.literal("confirm_read").then(Commands.argument("nonce",StringArgumentType.word()).executes(c->{
   var player=c.getSource().getPlayerOrException();guard(player.server);
   var p=Objects.requireNonNull(ACTIVE.get(player.server),"this QA is not active");
   try{p.confirmRead(player,StringArgumentType.getString(c,"nonce"));return 1;}catch(IllegalStateException|SecurityException refused){c.getSource().sendFailure(Component.literal("QA GROUP refusé : "+refused.getMessage()));return 0;}
  })));
  event.getDispatcher().register(Commands.literal("proutlost").then(root));
 }
 private static void tick(ServerTickEvent.Post event){
  var p=ACTIVE.get(event.getServer());if(p==null||p.done||p.failure!=null)return;
  try{guard(event.getServer());p.advance();}catch(RuntimeException|java.io.IOException failed){p.failure=failed.toString();System.out.println("REAL_GROUP_CLIENTS_QA_FAILED "+p.failure);}
 }
 private static void confirmed(DirectorCommittedFactEvent event){
  var p=ACTIVE.get(event.server());if(p==null||p.failure!=null||!event.fact().factType().equals(RealGroupClientsQaFixture.FACT))return;
  p.consume(event.fact());
 }
 private static void stopped(ServerStoppedEvent event){
  var p=ACTIVE.remove(event.getServer());if(p==null)return;
  if(p.store!=null)p.store.closeAsync();
  else p.opening.thenCompose(JournalStore::closeAsync);
 }
 private static void require(boolean yes,String why){if(!yes)throw new IllegalStateException(why);}
 private static Fingerprint hash(String text){return BlobContent.hash(text.getBytes(StandardCharsets.UTF_8));}
 private static String process(){return ProcessHandle.current().pid()+"@"+ProcessHandle.current().info().startInstant().orElseThrow();}
 private static String jar(Class<?> type,String property){
  // NeoForge may expose a union: CodeSource. Check the operator-named installed
  // JAR against the actual loaded class resource instead of guessing its URL.
  try{
   var path=Path.of(System.getProperty(property,""));require(path.isAbsolute()&&Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)&&!Files.isSymbolicLink(path)&&Files.size(path)<=64*1024*1024L,"bounded installed QA JAR path required: "+property);
   String entry=type.getName().replace('.','/')+".class";
   try(var archive=new java.util.jar.JarFile(path.toFile());var runtime=type.getResourceAsStream("/"+entry)){
    require(runtime!=null&&archive.getJarEntry(entry)!=null,"QA class missing from installed JAR");
    try(var packed=archive.getInputStream(archive.getJarEntry(entry))){require(Arrays.equals(runtime.readNBytes(8*1024*1024+1),packed.readNBytes(8*1024*1024+1)),"loaded QA class and installed JAR differ");}
   }
   var digest=java.security.MessageDigest.getInstance("SHA-256");try(var bytes=Files.newInputStream(path)){byte[] chunk=new byte[65536];for(int n;(n=bytes.read(chunk))>=0;)digest.update(chunk,0,n);}return HexFormat.of().formatHex(digest.digest());
  }catch(java.io.IOException|java.security.NoSuchAlgorithmException invalid){throw new IllegalStateException("QA JAR identity unavailable",invalid);}
 }
 private static String source(){String v=System.getProperty("proutlost.quest.realGroupClientsQaSourcesSha256","");require(v.matches("[0-9a-f]{64}"),"exact qualified-source SHA-256 property required");return v;}
 private static String clientManifest(){String v=System.getProperty("proutlost.quest.realGroupClientsQaClientManifestSha256","");require(v.matches("[0-9a-f]{64}"),"external root-verified real client binary manifest SHA-256 required");return v;}
 private static JsonObject read(Path path)throws java.io.IOException {require(Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)&&!Files.isSymbolicLink(path)&&Files.size(path)<=65536,"bounded regular QA receipt required");return JsonParser.parseString(Files.readString(path)).getAsJsonObject();}
 private static void writeNew(Path target,JsonObject body)throws java.io.IOException{
  require(!Files.exists(target),"retain the previous QA receipt");
  Path temporary=target.resolveSibling(target.getFileName()+".tmp");
  try(var channel=java.nio.channels.FileChannel.open(temporary,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){
   var data=StandardCharsets.UTF_8.encode(new GsonBuilder().setPrettyPrinting().create().toJson(body)+"\n");while(data.hasRemaining())channel.write(data);channel.force(true);
  }
  Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE);
 }
 private static final class Probe {
  final MinecraftServer server;final Path worldRoot,root,wal;final boolean reopening;final long startTick;
  final WorldId world;final RegionId region;final ScopeRef worldScope,regionScope;final ScopeSet scopes;
  final ConfigView config;final CatalogSnapshot catalog;final String fixtureSha,mainSha,directorSha,sourceSha,processIdentity,clientManifestSha;
  final BlockPos cell;final DimensionId dimension;final BlockPosition position;final ResourceKey resource;final Footprint footprint;
  final Set<UUID> participants;final RealGroupClientsQaSavedData saved;final QuestSavedData data;final JsonObject prior;
  private record ReadReceipt(SessionIdentity session,String coreSha,FactId fact,String nonceHash,String team){}
  final Map<UUID,RealGroupClientReadFence.Offer> offers=new HashMap<>();final Map<UUID,ReadReceipt> reads=new HashMap<>();
  final AtomicReference<OwnershipView> owners=new AtomicReference<>();
  final CompletableFuture<JournalStore> opening;JournalStore store;
  DirectorNativeEventLifecycle lifecycle;DirectorDecisionJournal journal;DecisionInput input,currentInput;DecisionProposal proposal;
  SelectedEventStart selected;EffectIntent intent;BlockTransformBinding binding;EffectPreparation capture;PreparedEffect prepared;
  AdmissionService admission;AdmissionRequest request;ResourceArbiter arbiter;ReservationToken token;PrepareRecord prepare;LiveValidationSnapshot live;
  EventRunner runner;CompletableFuture<CommitAck> pending;CompletableFuture<Void> closing;DirectorFact observed;OwnershipRecord retainedOwner;
  int phase;boolean done;String failure;long events;
  Probe(MinecraftServer server,BlockPos supplied,boolean reopen,List<ServerPlayer> bindingPlayers)throws java.io.IOException{
   this.server=server;worldRoot=guard(server);root=worldRoot.resolve("real-group-clients-qa");wal=root.resolve("wal");reopening=reopen;startTick=server.getTickCount();
   sourceSha=source();clientManifestSha=clientManifest();mainSha=jar(RealGroupClientsQaRuntime.class,"proutlost.quest.realGroupClientsQaMainJar");directorSha=jar(DirectorNativeEventLifecycle.class,"proutlost.quest.realGroupClientsQaDirectorJar");processIdentity=process();
   config=DirectorLabObservationProfile.load();catalog=RealGroupClientsQaFixture.catalog(config);fixtureSha=catalog.hash().value();
   world=server.overworld().getDataStorage().computeIfAbsent(DirectorSavedIdentity.FACTORY,DirectorSavedIdentity.KEY).worldId();
   region=new RegionId(UUID.nameUUIDFromBytes(("QA_REAL_GROUP_CLIENTS_REGION\0"+world.value()).getBytes(StandardCharsets.UTF_8)));worldScope=new ScopeRef(ScopeKind.WORLD,world.value());regionScope=new ScopeRef(ScopeKind.REGION,region.value());scopes=new ScopeSet(world,Set.of(worldScope,regionScope));
   Path savedFile=worldRoot.resolve("data").resolve(FILE+".dat");
   if(reopen){
    prior=read(root.resolve("produce.json"));
    require(!prior.get("process").getAsString().equals(processIdentity),"new JVM PID/start identity required");
    for(var e:Map.of("world_path",worldRoot.toString(),"wal_path",wal.toString(),"world_id",world.value().toString(),"source_sha256",sourceSha,"main_jar_sha256",mainSha,"director_jar_sha256",directorSha,"fixture_sha256",fixtureSha,"external_client_manifest_sha256",clientManifestSha).entrySet())require(prior.get(e.getKey()).getAsString().equals(e.getValue()),"paired cold identity mismatch: "+e.getKey());
    require(Files.isRegularFile(savedFile,LinkOption.NOFOLLOW_LINKS),"cold proof requires existing actual Quest SavedData");
    cell=new BlockPos(prior.get("x").getAsInt(),prior.get("y").getAsInt(),prior.get("z").getAsInt());
   }else{
    require(!Files.exists(root)&&!Files.exists(savedFile),"retain previous QA; use a new distinct world");prior=null;cell=Objects.requireNonNull(supplied).immutable();
   }
   var level=server.overworld();require(level.getChunkSource().getChunkNow(cell.getX()>>4,cell.getZ()>>4)!=null,"existing loaded QA cell required; no loading or generation");
   require(level.getWorldBorder().isWithinBounds(cell)&&cell.getY()>=level.getMinBuildHeight()&&cell.getY()<level.getMaxBuildHeight(),"bounded in-world QA cell required");
   require(level.getBlockEntity(cell)==null&&level.getBlockState(cell).is(reopen?Blocks.MOSS_BLOCK:Blocks.STONE),"QA physical PRE/POST cell mismatch");
   dimension=new DimensionId(level.dimension().location().toString());position=new BlockPosition(dimension,cell.getX(),cell.getY(),cell.getZ());resource=new ResourceKey(OwnershipDomain.BLOCK,dimension,cell.getX()+","+cell.getY()+","+cell.getZ());
   footprint=new Footprint(dimension,Set.of(new ChunkRef(dimension,cell.getX()>>4,cell.getZ()>>4)),Set.of(position),List.of());
   if(!reopen)requireBindingPair(server,bindingPlayers);
   saved=level.getDataStorage().computeIfAbsent(RealGroupClientsQaSavedData.FACTORY,FILE);data=saved.quest();
   if(!reopen)saved.enroll(server,bindingPlayers);participants=saved.participants();
   if(reopen)require(hash(data.core().save()).value().equals(prior.get("quest_core_sha256").getAsString()),"SavedData cold core differs from retained receipt");
   require(pairReady(false),"two actual negotiated participants in native scoreboard teams required");
   if(reopen){requireSavedTeamsMatchCurrent();require(scoreboardProof().equals(prior.get("scoreboard_teams_sha256").getAsString()),"cold native scoreboard team evidence differs");verifyColdSessions();verifyGroups();}else{require(pairReady(true),"current teams required");require(cellUnoccupied(),"QA cell envelope must be unoccupied");Files.createDirectory(root);saved.setDirty();}
   opening=JournalStore.open(wal,world,OPTIONS).toCompletableFuture();
  }
  // Actual world gameTime is persisted by the same cold checkpoint. The
  // constant offset is explicitly QA; restarting a JVM cannot reset the clock.
  Tick now(){return new Tick(Math.addExact(20000L,server.overworld().getGameTime()));}
  static void requireBindingPair(MinecraftServer server,List<ServerPlayer> players){
   require(players.size()==2&&players.getFirst()!=players.getLast()&&server.getPlayerList().getPlayers().size()==2,"two distinct real connected players required");
   var teams=new HashSet<String>();for(var p:players){
    require(!(p instanceof FakePlayer)&&p.server==server&&server.getPlayerList().getPlayer(p.getUUID())==p&&p.isAlive()&&!p.isSpectator()&&!p.isCreative(),"actual live survival connection required");
    var identity=DirectorNativeNetworkBootstrap.currentSession(server,new PlayerId(p.getUUID()));require(identity.isPresent()&&DirectorNativeNetworkBootstrap.sessionNegotiated(server,identity.orElseThrow()),"current negotiated J06 session required");
    require(p.getTeam()!=null,"native scoreboard team required");teams.add(p.getTeam().getName());
   }require(teams.equals(Set.of("p008_rg_a","p008_rg_b")),"two distinct native QA teams required");
  }
  boolean pairReady(boolean synchronize){
   if(participants.size()!=2||server.getPlayerList().getPlayers().size()!=2)return false;
   var actual=new ArrayList<ServerPlayer>();for(var id:participants){var player=server.getPlayerList().getPlayer(id);if(player==null)return false;actual.add(player);}
   try{requireBindingPair(server,actual);}catch(IllegalStateException refused){return false;}
   if(synchronize){for(var player:actual)QuestServerTeams.assign(data.core(),player);data.core().evaluate(System.currentTimeMillis());saved.setDirty();}
   return true;
  }
  Map<UUID,String> actualTeams(){
   var result=new TreeMap<UUID,String>();for(var id:participants){var p=server.getPlayerList().getPlayer(id);require(p!=null&&p.getTeam()!=null,"current native team missing");result.put(id,p.getTeam().getName());}return result;
  }
  void requireSavedTeamsMatchCurrent(){
   var snapshot=new Gson().fromJson(data.core().save(),QuestCore.Snapshot.class);
   require(snapshot.groups()!=null&&snapshot.groups().equals(actualTeams()),"cold Quest teams differ from actual scoreboard before any reassignment");
  }
  String scoreboardProof()throws java.io.IOException{
   Path file=worldRoot.resolve("data/scoreboard.dat");require(Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)&&!Files.isSymbolicLink(file)&&Files.size(file)<=16*1024*1024L,"actual native scoreboard SavedData required");
   var nbt=net.minecraft.nbt.NbtIo.readCompressed(file,net.minecraft.nbt.NbtAccounter.create(16*1024*1024L));var teams=nbt.getCompound("data").getList("Teams",10);require(teams.size()<=32,"bounded private QA scoreboard required");
   var disk=new TreeMap<String,List<String>>();for(int i=0;i<teams.size();i++){var row=teams.getCompound(i);String name=row.getString("Name");if(!Set.of("p008_rg_a","p008_rg_b").contains(name))continue;
    var members=new ArrayList<String>();var players=row.getList("Players",8);for(int j=0;j<players.size();j++)members.add(players.getString(j));Collections.sort(members);require(disk.putIfAbsent(name,List.copyOf(members))==null,"duplicate native QA team");}
   var expected=new TreeMap<String,List<String>>();for(var id:participants){var p=server.getPlayerList().getPlayer(id);require(p!=null&&p.getTeam()!=null,"actual native team missing");require(expected.putIfAbsent(p.getTeam().getName(),List.of(p.getGameProfile().getName()))==null,"distinct actual QA teams required");}
   require(disk.equals(expected),"native scoreboard disk membership differs from connected player teams");return hash(new Gson().toJson(disk)).value();
  }
  void verifyColdSessions(){
   var old=new HashMap<UUID,SessionIdentity>();for(var value:prior.getAsJsonArray("real_client_reads")){
    var row=value.getAsJsonObject();UUID id=UUID.fromString(row.get("player_uuid").getAsString());
    var identity=new ModelCodec<>(SessionIdentity.class).decode(Base64.getDecoder().decode(row.get("session_base64").getAsString()));
    require(identity.playerId().value().equals(id)&&old.putIfAbsent(id,identity)==null,"original real-client session evidence invalid");
   }
   require(old.keySet().equals(participants),"original client pair does not match saved enrollment");
   for(var id:participants){var current=participantSession(server.getPlayerList().getPlayer(id));require(current.worldId().equals(old.get(id).worldId())&&!current.equals(old.get(id)),"new negotiated connection/boot identity required after cold JVM");}
  }
  SessionIdentity participantSession(ServerPlayer player){
   require(!(player instanceof FakePlayer)&&participants.contains(player.getUUID())&&player.server==server&&server.getPlayerList().getPlayer(player.getUUID())==player,"actual enrolled server player required");
   var actual=DirectorNativeNetworkBootstrap.currentSession(server,new PlayerId(player.getUUID())).orElseThrow(()->new IllegalStateException("current J06 identity required"));
   require(DirectorNativeNetworkBootstrap.sessionNegotiated(server,actual),"current J06 negotiation required");return actual;
  }
  boolean cellUnoccupied(){
   var box=new net.minecraft.world.phys.AABB(cell).inflate(2);
   return server.getPlayerList().getPlayers().stream().noneMatch(p->p.serverLevel()==server.overworld()&&p.getBoundingBox().intersects(box));
  }
  void journal(ServerPlayer player){
   var session=participantSession(player);String team=QuestServerTeams.assign(data.core(),player);data.core().evaluate(System.currentTimeMillis());saved.setDirty();
   var state=data.core().state(RealGroupClientsQaFixture.QUEST,player.getUUID());
   for(var page:data.core().journal(player.getUUID()))player.sendSystemMessage(Component.literal(page.id()+" ["+page.state()+"] "+page.summary()));
   player.sendSystemMessage(Component.literal("TEST GROUP joueur="+player.getUUID()+" équipe="+Objects.toString(team,"NONE")+" état="+state));
   if(observed==null||state!=QuestCore.State.RESOLVED){offers.remove(player.getUUID());return;}
   require(pairReady(true),"both real negotiated participants and teams must be current");
   String coreSha=hash(data.core().save()).value();var offer=RealGroupClientReadFence.issue(session,new Fingerprint(coreSha),observed.factId(),server.getTickCount());String nonce=offer.nonce();offers.put(player.getUUID(),offer);
   player.sendSystemMessage(Component.literal("TEST fait="+observed.factId().value()+" événement="+observed.eventId().orElseThrow().value()+" lecture="+coreSha));
   player.sendSystemMessage(Component.literal("Confirmer cette lecture : /proutlost real_group_qa confirm_read "+nonce));
  }
  void confirmRead(ServerPlayer player,String nonce){
   var session=participantSession(player);var offer=offers.get(player.getUUID());
   require(observed!=null&&RealGroupClientReadFence.matches(offer,nonce,session,hash(data.core().save()),observed.factId(),server.getTickCount()),"foreign, stale or changed read offer");
   require(pairReady(false),"both current real participants and native teams required");
   String team=Objects.requireNonNull(player.getTeam()).getName();require(data.core().state(RealGroupClientsQaFixture.QUEST,player.getUUID())==QuestCore.State.RESOLVED,"GROUP state changed");
   reads.put(player.getUUID(),new ReadReceipt(session,offer.core().value(),offer.fact(),hash(nonce).value(),team));offers.remove(player.getUUID());player.sendSystemMessage(Component.literal("TEST contexte joueur GROUP confirmé ; conserver la capture client."));
  }
  boolean readsCurrent(){
   if(!pairReady(false)||reads.size()!=2||observed==null)return false;String digest=hash(data.core().save()).value();
   for(var id:participants){var p=server.getPlayerList().getPlayer(id);var read=reads.get(id);if(read==null||!read.session().equals(participantSession(p))||!read.fact().equals(observed.factId())||!read.coreSha().equals(digest)||p.getTeam()==null||!read.team().equals(p.getTeam().getName()))return false;}return true;
  }
  String status(){return "REAL_GROUP_CLIENTS_QA TEST_ONLY stage="+phase+" done="+done+" reopening="+reopening+" events="+events+" confirmed_client_reads="+reads.size()+" failure="+Objects.toString(failure,"NONE")+" actual_connected_clients="+server.getPlayerList().getPlayers().size()+" actors=REAL_SERVER_PLAYERS";}
  void verifyGroups(){
   for(var id:participants)require(data.core().state(RealGroupClientsQaFixture.QUEST,id)==QuestCore.State.RESOLVED,"public result missing for real participant's native team");
  }
  void consume(DirectorFact fact){
   require(pairReady(true),"actual negotiated pair must be present for native Quest delivery");
   require(fact.durable()&&fact.evidenceStatus()==EvidenceStatus.CONFIRMED&&fact.eventId().isPresent(),"actual confirmed native completion required");
   require(new HashSet<>(fact.subjectRefs()).equals(scopes.scopes())&&fact.subjectRefs().stream().noneMatch(s->s.kind()==ScopeKind.PLAYER),"original public WORLD/REGION scopes required");
   var expectedEvent=reopening?new EventInstanceId(UUID.fromString(prior.get("event_id").getAsString())):intent.instanceId();
   require(fact.eventId().equals(Optional.of(expectedEvent))&&fact.factId().value().equals(UUID.nameUUIDFromBytes(("catalogue-completion-v1\0"+expectedEvent.value()).getBytes(StandardCharsets.UTF_8))),"actual native completion identity");
   String before=data.core().save();QuestDirectorFacts.acceptConfirmed(server,data,fact,System.currentTimeMillis());
   if(observed!=null)require(before.equals(data.core().save()),"actual J04 completion replay changed Quest state");
   observed=fact;events++;saved.setDirty();verifyGroups();
  }
  void advance()throws java.io.IOException{
   require(server.getTickCount()-startTick<6000,"bounded real-client GROUP QA timeout");
   boolean ready=pairReady(true);
   if(!ready&&phase!=4&&phase!=6)return;
   if(ready&&store!=null&&lifecycle!=null&&phase!=6){lifecycle.poll();require(!lifecycle.failed(),"actual EVENT lifecycle failed: "+lifecycle.status());}
   switch(phase){
    case 0->{if(!opening.isDone())return;store=opening.join();require(!store.readOnlySafe(),"actual J04 writer required");
     lifecycle=DirectorNativeEventLifecycle.realGroupClientsQualification(server,world,store,catalog,this::now);
     if(reopening){require(store.session().bootEpoch().value()>prior.get("wal_boot_epoch").getAsLong(),"new J04 boot required");require(now().value()>=prior.get("qa_tick").getAsLong(),"persisted native world clock moved backwards");phase=20;return;}
     setup();phase=1;}
    case 1->{if(!durable(pending))return;journal=new DirectorDecisionJournal(store,4);require(!journal.submit(proposal).mayProceedToAdmission(),"no admission before actual audit ACK");phase=2;}
    case 2->{var audit=journal.poll(proposal.proposalId());require(audit.state()!=DirectorDecisionJournal.State.REJECTED&&audit.state()!=DirectorDecisionJournal.State.UNAVAILABLE,"native J03 audit rejected");if(!audit.mayProceedToAdmission()||lifecycle.recovering())return;
     var gate=lifecycle.beginAudited(selected,journal);require(gate.status()!=DirectorNativeCatalogueExecution.GateStatus.REJECTED,"native EVENT rejected");if(gate.status()!=DirectorNativeCatalogueExecution.GateStatus.READY)return;prepareAdmission();phase=3;}
    case 3->{var result=admission.admit(request);require(result.status()!=AdmissionStatus.REJECTED,"actual admission rejected: "+result.reasons());
     var epochs=new HashMap<ResourceKey,Epoch>();prepared.claims().forEach(c->epochs.put(c.resource(),c.expectedEpoch()));
     admission.resolveWindow(epochs,Map.of(worldScope,CAP,regionScope,CAP));result=admission.admit(request);if(result.status()==AdmissionStatus.DEFERRED)return;
     require(result.status()==AdmissionStatus.ACCEPTED,"actual admission unavailable");token=result.token().orElseThrow();submitPhysical();phase=4;}
    case 4->{live=currentLive();var result=runner.poll(prepare.transactionId(),live);require(result.stage()!=EventRunner.Stage.REJECTED&&result.stage()!=EventRunner.Stage.RECOVERY_REQUIRED,"native EventRunner failed: "+result.failure());
     if(result.stage()!=EventRunner.Stage.COMMITTED)return;require(result.result().orElseThrow().outcome()==Outcome.SUCCEEDED&&server.overworld().getBlockState(cell).is(Blocks.MOSS_BLOCK),"actual Minecraft POST not observed");phase=5;}
    case 5->{if(observed==null||!lifecycle.realGroupClientsQualificationSettled(intent.instanceId())||!readsCurrent())return;verifyJournalAndSave();phase=6;}
    case 6->{if(closing==null)closing=store.closeAsync().toCompletableFuture();if(!closing.isDone())return;closing.join();done=true;System.out.println((reopening?"REAL_GROUP_CLIENTS_QA_REOPEN_PASS ":"REAL_GROUP_CLIENTS_QA_PRODUCE_PASS ")+root);}
    case 20->{if(observed==null||!lifecycle.realGroupClientsQualificationSettled(observed.eventId().orElseThrow())||!readsCurrent())return;require(data.core().factById(observed.factId().value().toString()).isPresent(),"actual cold Quest fact missing");verifyGroups();verifyNativeOwnership();
     require(hash(data.core().save()).value().equals(prior.get("quest_core_sha256").getAsString()),"cold replay mutated durable Quest core");
     var receipt=receipt("NEW_JVM_NATIVE_GROUP_READBACK");receipt.addProperty("producing_process",prior.get("process").getAsString());writeNew(root.resolve("reopen-"+ProcessHandle.current().pid()+"-"+ProcessHandle.current().info().startInstant().orElseThrow().toEpochMilli()+".json"),receipt);phase=6;}
    default->throw new IllegalStateException("invalid public GROUP QA phase");
   }
  }
  boolean durable(CompletableFuture<CommitAck> future){if(!future.isDone())return false;var ack=future.join();require(ack.ackStatus()==AckStatus.DURABLE&&ack.durableSequence().value()>0,"actual durable ACK required");return true;}
  void setup(){
   require(pairReady(false)&&cellUnoccupied(),"actual pair and unoccupied cell required before native planning");
   var original=server.overworld().getBlockState(cell);var pre=BlockTransformBinding.stateBlob(original);
   var owner=new OwnershipRecord(new AggregateHeader(1,UUID.randomUUID(),new Revision(1),new Sequence(0),now()),resource,Set.of(new OwnerToken(UUID.randomUUID())),new Epoch(1),OwnershipDisposition.DIRECTOR_ACTIVE,new MutationId(UUID.randomUUID()),pre.reference().hash(),new Epoch(0),Optional.empty());owners.set(new OwnershipView(Map.of(resource,owner)));
   binding=new BlockTransformBinding(dim->server.overworld(),owners::get,(p,next)->{require(p.equals(position),"foreign QA cell");owners.set(new OwnershipView(Map.of(resource,next)));},this::now,Map.of(RealGroupClientsQaFixture.RECIPE,new BlockTransformBinding.Recipe(Map.of(new ResourceId("minecraft:stone"),new ResourceId("minecraft:moss_block")),true)),1);
   var regionRecord=new RegionRecord(new AggregateHeader(1,region.value(),new Revision(1),new Sequence(0),now()),region,dimension,RegionStatus.ACTIVE,new SupportShape(List.of(),List.of(),List.of()),Set.of(),new UnitValue(.5),Set.of(),Set.of(),new Revision(1),new RegionPersonality(Map.of()),Set.of());
   var control=new EngineControlState(1,new Revision(0),now(),now(),new UnitValue(0),now(),Optional.empty(),new UnitValue(.5));
   var initialWorld=new DirectorWorldState(new AggregateHeader(1,world.value(),new Revision(1),new Sequence(0),now()),world,store.session().storeGeneration(),now(),RunControl.RUNNING,HealthMode.NORMAL,PacingState.ACTIVE,GlobalTension.CALM,new ResourceId("proutlost_director:qa_real_group_clients"),catalog.hash(),config.provenanceHash(),store.session().bootEpoch(),new Sequence(0),control);
   input=input(initialWorld,regionRecord);currentInput=input;
   var generated=new CandidateGenerator().generate(input,new DecisionRandom(input.seed())).candidates();require(generated.size()==1,"one actual generated public QA candidate required");var candidate=generated.getFirst();
   require(candidate.scopes().scopes().equals(scopes.scopes())&&candidate.roles().roles().values().stream().allMatch(List::isEmpty),"public scope must arise from zero-role generation");
   var step=candidate.definition().steps().getFirst();var bindings=new PlanningBindings(candidate.id(),candidate.reference(),new EventInstanceId(UUID.randomUUID()),new OperationToken(UUID.randomUUID()),step.id(),candidate.roles(),candidate.scopes(),footprint,Set.of(),true);
   var planning=new PlanningSnapshot(input.context(),owners.get(),input.state().budgets(),new BlobBundle(List.of()),Optional.of(bindings));
   PlanningPort port=r->{var b=r.snapshot().bindings().orElseThrow();var i=new EffectIntent(b.operationToken(),r.step().primitive(),1,r.step().args(),b.footprint(),b.instanceId());return new PlannedPrimitive(b,binding.capture(i,r.snapshot()).prepared().orElseThrow());};
   var engine=new WorldDirector(ProviderMode.TEST_ONLY,new CandidatePlanner(port,Map.of(candidate.id(),List.of(planning))));proposal=engine.evaluate(input);
   require(proposal.selectedPlan().isPresent()&&engine.replay(input,proposal.audit()).equals(proposal),"real deterministic J03 selection required");
   selected=new SelectedEventStart(input,proposal,proposal.selectedPlan().orElseThrow(),Optional.empty());intent=selected.plan().effects().getFirst();
   pending=store.append(ResultTransaction.batch(new TransactionId(UUID.randomUUID()),store.session().storeGeneration(),Map.of(),List.of(new DurableOwnership(owner),new DurableAggregate(new WorldAggregate(initialWorld)),new DurableAggregate(new RegionAggregate(regionRecord))),List.of())).toCompletableFuture();
  }
  DecisionInput input(DirectorWorldState actualWorld,RegionRecord rr){
   var revisions=Map.of(worldScope,actualWorld.header().revision(),regionScope,rr.header().revision());var rows=new HashMap<ScopeRef,ScopeSnapshot>();revisions.forEach((k,v)->rows.put(k,new ScopeSnapshot(k,v,EnumSet.allOf(SensorKind.class),Set.of(),Map.of())));
   var terrain=new TerrainCellSnapshot(position,BlockTransformBinding.stateBlob(server.overworld().getBlockState(cell)).reference().hash(),BlockTransformBinding.STATE_CODEC,Set.of(),Set.of(),false,true,owners.get().records().get(resource).epoch(),new Epoch(0));
   var capId=new CapabilityId("mutation.plants_v1");var capability=new CapabilityDescriptor(capId,1,ProviderMode.TEST_ONLY,CapabilityStatus.AVAILABLE,new ResourceId("proutlost_director:qa_real_group_clients"),Set.of("TEST_ONLY_SYNTHETIC_REGION_ZERO_PLAYERS"));
   var context=new ObservationContext(rows,Map.of(),Map.of(),Map.of(region,new RegionSnapshot(rr,Set.of(new TagId("proutlost:qa_real_group_clients_site")),Set.of(),List.of(),Map.of())),Map.of(),Map.of(),List.of(new TerrainSnapshot(footprint,Map.of(position,terrain),List.of(),Map.of(),new Revision(1))),Map.of(capId,capability));
   var snapshot=new SnapshotBatch(new RequestId(UUID.randomUUID()),scopes,now(),revisions,context,Set.of(),footprint.loadedChunks(),HealthMode.NORMAL);
   var memory=new MemoryView(scopes,revisions,List.of(new RegionMemory(rr.header(),region,Map.of(),now(),now(),Set.of(),Set.of(),new RegionActivityState(Map.of(),Map.of()))),List.of(),List.of(),Map.of(region,rr),Map.of(),Map.of());
   return new DecisionInput(snapshot,memory,catalog,config,List.of(),List.of(),42,new DecisionState(actualWorld,new BudgetView(CAP,BudgetMath.zero(),Map.of(regionScope,BudgetMath.zero())),owners.get(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of()),Optional.empty());
  }
  void prepareAdmission(){
   var q=new StoreQuery(world,Set.of(new AggregateKey(AggregateKind.WORLD,world.value()),new AggregateKey(AggregateKind.REGION,region.value())),new Sequence(0),Optional.empty(),8);var view=store.view(q);ReadContracts.validate(view,q,store.session());require(view.status()==ReadStatus.AVAILABLE,"actual admission scopes resident");
   DirectorWorldState w=null;RegionRecord r=null;for(var row:view.page().orElseThrow().aggregates()){if(row.state() instanceof WorldAggregate a)w=a.value();if(row.state() instanceof RegionAggregate a)r=a.value();}
   require(w!=null&&r!=null,"actual J04 post-audit WORLD/REGION required");currentInput=input(w,r);
   capture=binding.capture(intent,new PlanningSnapshot(currentInput.context(),owners.get(),currentInput.state().budgets(),new BlobBundle(List.of())));require(capture.validation().valid(),"native post-audit capture failed");prepared=capture.prepared().orElseThrow();
   var plan=new ExecutionPlan(selected.plan().candidateId(),selected.plan().definition(),selected.plan().effects(),selected.plan().roles(),selected.plan().scopes(),prepared.preconditions());request=new AdmissionRequest(proposal.proposalId(),plan.readSet(),prepared.claims(),plan.roles(),plan);
   arbiter=new ResourceArbiter(world,new Epoch(0),1000,4,new ResourceArbiter.Compatibility(){public boolean share(ResourceClaim a,ResourceClaim b){return false;}public boolean merge(ResourceId id,ResourceClaim a,ResourceClaim b){return false;}});
   admission=new AdmissionService(()->catalog,new AdmissionService.HardValidator(){
    public ProviderMode mode(){return ProviderMode.TEST_ONLY;}
    public AdmissionService.Assessment assess(AdmissionRequest actual,Tick tick){
     boolean valid=actual.equals(request)&&pairReady(false)&&cellUnoccupied()&&binding.revalidate(prepared,capture.blobs())&&server.overworld().getBlockState(cell).is(Blocks.STONE);
     var validation=new ValidationResult(valid,valid?List.of():List.of(ReasonCode.STALE_CONTEXT),prepared.preconditions());
     return new AdmissionService.Assessment(validation,prepared.claims(),new ResourceArbiter.Rank(false,false,false,Priority.AMBIENT,0,1,42),Map.of(worldScope,prepared.budgetEstimate(),regionScope,prepared.budgetEstimate()));
    }
   },arbiter,this::now,new DurationTicks(600),4,ProviderMode.TEST_ONLY);
  }
  void submitPhysical(){
   var slice=new PreparedSlice(new SliceId(UUID.randomUUID()),intent.operationToken(),footprint,prepared.preconditions(),List.of(intent),prepared.budgetEstimate());
   prepare=new PrepareRecord(new TransactionId(UUID.randomUUID()),intent.operationToken(),token.generation(),prepared.preconditions(),now().plus(600),slice,token,capture.validation(),List.of(),Set.of(),List.of(),prepared.durableBlobs());
   live=new LiveValidationSnapshot(prepared.preconditions(),footprint.loadedChunks(),HealthMode.NORMAL,now(),token.generation());
   var authority=new CommitPermit.Authority(store,4);var port=new ServerEffectPort(authority,i->{throw new IllegalStateException("native capture already sealed");},s->currentLive(),Map.of(intent.primitive(),binding),Set.of(new CapabilityId("mutation.plants_v1")),4,s->new StabilizationLiveSnapshot(store.session(),currentLive(),owners.get()),m->{},(m,result)->{});port.hydrate(prepared,capture.blobs());runner=new EventRunner(authority,store,port,arbiter,4);
   var batch=new DurableBatch(prepare.transactionId(),store.session().storeGeneration(),prepare.readSet().revisions(),List.of(new DurablePrepare(prepare)),capture.blobs().contents(),Optional.empty(),CommitPermit.Authority.prepareHash(prepare));
   runner.submit(batch,prepared.expectedPost(),live,actual->{
    require(actual.outcome()==Outcome.SUCCEEDED&&actual.appliedOperationIds().contains(intent.operationToken())&&actual.observedHashes().equals(prepared.expectedPost()),"only actual successful exact POST may complete native event");
    var rows=new ArrayList<DurableRecord>();rows.add(new DurableResult(new ResultRecord(prepare.transactionId(),prepare.operationToken(),actual,Set.of(),List.of(),List.of())));rows.add(new DurableOwnership(owners.get().records().get(resource)));
    rows.addAll(selected.completionRecords(Map.of("test_only",new JsonValue.Bool(true),"actual_cells",new JsonValue.Text("1"),"observed_post",new JsonValue.Text(hash(actual.observedHashes().toString()).value()))));
    return ResultTransaction.batch(new TransactionId(UUID.randomUUID()),store.session().storeGeneration(),Map.of(),rows,List.of());
   });
   require(server.overworld().getBlockState(cell).is(Blocks.STONE),"native physical effect before PREPARE ACK");
  }
  LiveValidationSnapshot currentLive(){return new LiveValidationSnapshot(prepared.preconditions(),footprint.loadedChunks(),pairReady(false)&&cellUnoccupied()?HealthMode.NORMAL:HealthMode.CRITICAL,now(),token.generation());}
  void verifyJournalAndSave()throws java.io.IOException{
   verifyGroups();verifyNativeOwnership();require(data.core().factById(observed.factId().value().toString()).isPresent(),"actual completion missing from Quest core");
   server.saveEverything(true,true,true);server.overworld().getDataStorage().save();net.neoforged.neoforge.common.IOUtilities.waitUntilIOWorkerComplete();
   Path saved=worldRoot.resolve("data").resolve(FILE+".dat");require(Files.isRegularFile(saved,LinkOption.NOFOLLOW_LINKS),"native SavedData file missing");
   var nbt=net.minecraft.nbt.NbtIo.readCompressed(saved,net.minecraft.nbt.NbtAccounter.create(16*1024*1024L));
   var reread=RealGroupClientsQaSavedData.load(nbt.getCompound("data"),server.overworld().registryAccess());require(reread.participants().equals(participants)&&reread.quest().core().save().equals(data.core().save()),"native disk enrolled Quest SavedData readback differs");
   writeNew(root.resolve("produce.json"),receipt("NATIVE_PRODUCE_SAVED_COLD_PENDING"));
  }
  void verifyNativeOwnership(){
   UUID id=reopening?UUID.fromString(prior.get("ownership_id").getAsString()):owners.get().records().get(resource).header().id();
   var q=new StoreQuery(world,Set.of(new AggregateKey(AggregateKind.OWNERSHIP,id)),new Sequence(0),Optional.empty(),1);var view=store.view(q);ReadContracts.validate(view,q,store.session());
   require(view.status()==ReadStatus.AVAILABLE&&view.page().orElseThrow().aggregates().size()==1,"retained native J04 ownership head unavailable");
   var row=view.page().orElseThrow().aggregates().getFirst();require(row.committedSequence().value()>0&&row.state() instanceof OwnershipAggregate,"actual committed native ownership required");
   retainedOwner=((OwnershipAggregate)row.state()).value();require(retainedOwner.resourceKey().equals(resource)&&retainedOwner.expectedPostHash().equals(BlockTransformBinding.stateBlob(server.overworld().getBlockState(cell)).reference().hash()),"native cell and retained J04 ownership POST differ");
   if(reopening)require(BlobContent.hash(new ModelCodec<>(OwnershipRecord.class).encode(retainedOwner)).value().equals(prior.get("ownership_record_sha256").getAsString()),"cold native ownership record changed");
  }
  JsonObject receipt(String phaseName)throws java.io.IOException{
   require(retainedOwner!=null&&readsCurrent(),"current native ownership and both real client read confirmations required");
   var v=new JsonObject();v.addProperty("schema",1);v.addProperty("phase",phaseName);
   v.addProperty("scope","TEST_ONLY_TWO_REAL_CLIENTS_NATIVE_GROUP_READBACK");
   v.addProperty("synthetic_selection_context","region,regional-memory,capability-descriptor,budgets,ACTIVE-pacing,zero-role-definition");
   v.addProperty("native_observed_chain","ServerPlayerList,current-J06-sessions,native-scoreboard-teams,loaded-Minecraft-cell,J03-engine,AdmissionService,J04-PREPARE,one-use-permit,BlockTransformBinding,J04-RESULT,EVENT-lifecycle,shared-Quest-consumer,enrolled-SavedData-disk,two-session-bound-journal-read-confirmations");
   v.addProperty("not_claimed","natural-observation,production-canon,human-perception,20-25-player-load,private-and-prerequisite-native-negative-tests,cleanup,release,inverse,physical-restoration");
   v.addProperty("process",processIdentity);v.addProperty("world_path",worldRoot.toString());v.addProperty("wal_path",wal.toString());v.addProperty("world_id",world.value().toString());
   v.addProperty("source_sha256",sourceSha);v.addProperty("main_jar_sha256",mainSha);v.addProperty("director_jar_sha256",directorSha);v.addProperty("fixture_sha256",fixtureSha);
   v.addProperty("external_client_manifest_sha256",clientManifestSha);v.addProperty("scoreboard_teams_sha256",scoreboardProof());
   v.addProperty("quest_core_sha256",hash(data.core().save()).value());v.addProperty("world_game_time",server.overworld().getGameTime());v.addProperty("qa_tick",now().value());
   v.addProperty("wal_boot_epoch",store.session().bootEpoch().value());v.addProperty("wal_authority",store.session().authoritySession().toString());v.addProperty("fact_id",observed.factId().value().toString());v.addProperty("event_id",observed.eventId().orElseThrow().value().toString());v.addProperty("original_subjects",observed.subjectRefs().toString());
   v.addProperty("ownership_id",retainedOwner.header().id().toString());v.addProperty("ownership_disposition",retainedOwner.disposition().name());v.addProperty("ownership_record_sha256",BlobContent.hash(new ModelCodec<>(OwnershipRecord.class).encode(retainedOwner)).value());
   v.addProperty("x",cell.getX());v.addProperty("y",cell.getY());v.addProperty("z",cell.getZ());v.addProperty("physical_post","minecraft:moss_block");v.addProperty("native_clients",participants.size());v.addProperty("synthetic_player_actors",0);
   var actualReads=new JsonArray();for(var id:participants.stream().sorted().toList()){
    var r=reads.get(id);var player=server.getPlayerList().getPlayer(id);var row=new JsonObject();
    row.addProperty("player_uuid",id.toString());row.addProperty("profile_name",player.getGameProfile().getName());row.addProperty("team",r.team());
    row.addProperty("session_base64",Base64.getEncoder().encodeToString(new ModelCodec<>(SessionIdentity.class).encode(r.session())));
    row.addProperty("read_core_sha256",r.coreSha());row.addProperty("read_fact_id",r.fact().value().toString());row.addProperty("confirmed_nonce_sha256",r.nonceHash());actualReads.add(row);
   }v.add("real_client_reads",actualReads);v.addProperty("command_context_alone_proves_physical_packet_origin",false);v.addProperty("requires_correlated_real_client_logs_and_captures",true);v.addProperty("human_reading_or_perception_accepted",false);return v;
  }
 }
}
