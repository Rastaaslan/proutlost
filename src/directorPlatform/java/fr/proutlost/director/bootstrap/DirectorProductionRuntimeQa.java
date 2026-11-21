package fr.proutlost.director.bootstrap;

import fr.proutlost.director.api.codec.*;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.port.*;
import fr.proutlost.director.persistence.JournalStore;
import java.util.concurrent.CompletableFuture;
import fr.proutlost.director.adapter.neoforge.network.DirectorNativeNetworkBootstrap;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.common.util.FakePlayer;

/** Closed qualification only. The normal operator loader and PRODUCTION
 * composition are not entered or re-labelled by this typed mode. */
public final class DirectorProductionRuntimeQa implements DirectorBootstrap.TestOnlyBackend {
 public static final String FLAG="proutlost.director.productionRuntimeQa";
 public static final String ROOT="proutlost.director.productionRuntimeQaRoot";
 public static final String FIXTURE_PIN="proutlost.director.productionRuntimeQaFixtureSha256";
 public static final String MARKER="PROUTLOST_DECLARED_SYNTHETIC_RUNTIME_QA_V1";
 public static final ResourceId PROFILE=new ResourceId("proutlost_director:declared_synthetic_runtime_qa");
 public static final DefinitionId LANDSLIDE=new DefinitionId("proutlost_director:candidate/controlled_landslide");
 private final MinecraftServer server;private final Path root;private final ConfigView configuration;
 private final Fingerprint fixture;private final String sourceSha,directorSha;private Inputs inputs;
 private DirectorProductionCohortQa cohort;
 public Optional<DirectorProductionCohortQa> cohort(){return Optional.ofNullable(cohort);}
 boolean cohortAutomaticAllowed(){return cohort!=null&&cohort.plan().automaticSelection();}
 private StoreSession retainedSession;private StoreQuery retainedQuery;private CompletableFuture<StoreReadResult> retainedRead;private boolean retainedIndexReady;
 private DirectorProductionRuntimeQa(MinecraftServer server,Path root,ConfigView configuration,Fingerprint fixture,String sourceSha,String directorSha){
  this.server=server;this.root=root;this.configuration=configuration;this.fixture=fixture;this.sourceSha=sourceSha;this.directorSha=directorSha;
 }
 public static boolean requested(){return Boolean.getBoolean(FLAG)||DirectorProductionCohortQa.requested()&&"DIRECTOR".equals(System.getProperty(DirectorProductionCohortQa.BRANCH,""));}
 private static void require(boolean yes,String why){if(!yes)throw new IllegalStateException(why);}
 /** Deterministic closure of the unchanged pinned defaults and 11 source documents.
  * This does not bind an invented world UUID, route, actor, capability or approval. */
 public static Fingerprint fixtureFingerprint(){
  var config=DirectorLabObservationProfile.load();var candidate=new DirectorNativeCandidateCatalog(config);
  var body=new JsonValue.ObjectValue(Map.of(
   "kind",new JsonValue.Text(MARKER),"calibration",new JsonValue.Text(Calibration.DEFAULT_FOR_TESTING.name()),
   "profile",new JsonValue.Text(PROFILE.value()),"profile_sha256",new JsonValue.Text(config.provenanceHash().value()),
   "candidate_manifest_sha256",new JsonValue.Text(candidate.status().manifestSha256()),
   "max_real_clients",new JsonValue.Number(java.math.BigDecimal.valueOf(2)),
   "operator_approval_obtained",new JsonValue.Bool(false)));
  return BlobContent.hash(CanonicalJsonV1.encode(body));
 }
 public static DirectorProductionRuntimeQa open(MinecraftServer server){
  require(server!=null&&server.isSameThread()&&requested(),"declared synthetic QA actor/flag required");
  if(DirectorProductionCohortQa.requested()){
   var policy=DirectorProductionCohortQa.open(server);require(policy.branch()==DeclaredCohortQaPlan.Branch.DIRECTOR,"baseline may not open runtime inputs");
   var result=new DirectorProductionRuntimeQa(server,policy.root(),DirectorLabObservationProfile.load(),policy.fixture(),
    policy.plan().pins().get("sources_sha256"),policy.plan().pins().get("director_jar_sha256"));
   result.cohort=policy;result.requireWorld();return result;
  }
  require(!Boolean.getBoolean("proutlost.director.nativeLab")&&System.getProperty("proutlost.director.productionManifestSha256","").isBlank()
   &&!Boolean.getBoolean("proutlost.quest.neutralLab")&&!Boolean.getBoolean("proutlost.quest.publicGroupQa")&&!Boolean.getBoolean("proutlost.quest.realGroupClientsQa"),"distinct production-runtime QA; no LAB/operator/GROUP modes");
  try{
   String selected=System.getProperty(ROOT,"");Path requested=Path.of(selected),actual=server.getWorldPath(LevelResource.ROOT).toRealPath();
   require(!selected.isBlank()&&requested.isAbsolute()&&requested.toRealPath().equals(actual),"exact independent QA world root required");
   Path marker=actual.resolve("DECLARED_SYNTHETIC_RUNTIME_TEST_ONLY");
   require(Files.isRegularFile(marker,LinkOption.NOFOLLOW_LINKS)&&!Files.isSymbolicLink(marker)&&Files.size(marker)<=128&&Files.readString(marker).trim().equals(MARKER),"closed declared synthetic QA marker required");
   for(String forbidden:List.of("PUBLIC_GROUP_TEST_ONLY","REAL_GROUP_CLIENTS_TEST_ONLY","data/proutlost_quest_v1.dat","data/proutlost_quest_neutral_qa_v1.dat","data/proutlost_quest_public_group_qa_v1.dat","data/proutlost_quest_real_group_clients_qa_v1.dat","proutlost/director-profile/manifest.json"))
    require(!Files.exists(actual.resolve(forbidden)),"separate QA world/profile required: "+forbidden);
   String source=System.getProperty("proutlost.director.productionRuntimeQaSourcesSha256","");require(source.matches("[0-9a-f]{64}"),"qualified QA source SHA-256 required");
   var expected=new Fingerprint(System.getProperty(FIXTURE_PIN,""));var fixture=fixtureFingerprint();require(fixture.equals(expected),"exact declared QA fixture/defaults pin required");
   String jar=System.getProperty("proutlost.director.productionRuntimeQaDirectorJar","");String sha=System.getProperty("proutlost.director.productionRuntimeQaDirectorSha256","");
   require(sha.matches("[0-9a-f]{64}"),"exact installed Director JAR SHA-256 required");Path file=Path.of(jar);
   require(file.isAbsolute()&&Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)&&!Files.isSymbolicLink(file)&&Files.size(file)<=64*1024*1024L,"bounded installed Director JAR required");
   var digest=java.security.MessageDigest.getInstance("SHA-256");try(var in=Files.newInputStream(file)){byte[] block=new byte[65536];for(int n;(n=in.read(block))!=-1;)digest.update(block,0,n);}require(HexFormat.of().formatHex(digest.digest()).equals(sha),"Director candidate hash mismatch");
   try(var archive=new java.util.jar.JarFile(file.toFile())){
    for(var type:List.of(DirectorProductionRuntimeQa.class,DirectorProductionRuntime.class,DirectorRuntimeContent.class,DirectorServerLifecycle.class,DirectorNativeTerrainDisasterGameplay.class,DirectorNativeEffectRouter.class,fr.proutlost.director.runtime.SingleSlicePrimitive.class,DirectorNativeObservationQualification.class)){
     String name=type.getName().replace('.','/')+".class";
     try(var loaded=type.getResourceAsStream("/"+name)){
      require(loaded!=null&&archive.getJarEntry(name)!=null,"loaded QA runtime class absent from pinned Director JAR");
      try(var packed=archive.getInputStream(archive.getJarEntry(name))){byte[] a=loaded.readNBytes(1048577),b=packed.readNBytes(1048577);require(a.length<=1048576&&b.length<=1048576&&Arrays.equals(a,b),"loaded QA runtime class differs from candidate");}
     }
    }
   }
   var mode=new DirectorProductionRuntimeQa(server,actual,DirectorLabObservationProfile.load(),fixture,source,sha);mode.requireWorld();return mode;
  }catch(IOException|java.security.NoSuchAlgorithmException invalid){throw new IllegalStateException("declared synthetic QA world/pin unavailable",invalid);}
 }
 public void requireWorld(){
  if(cohort!=null){cohort.requireWorld();require(cohort.branch()==DeclaredCohortQaPlan.Branch.DIRECTOR,"cohort runtime branch required");return;}
  require(server.isSameThread()&&Boolean.getBoolean(FLAG)&&!DirectorProductionCohortQa.requested()&&!Boolean.getBoolean("proutlost.director.nativeLab")&&System.getProperty("proutlost.director.productionManifestSha256","").isBlank()
   &&server.getPort()==25579&&server.getPlayerList().isUsingWhitelist()&&server.getPlayerList().getPlayers().size()<=2,"private QA root, port, whitelist and at most two real clients required");
  require(root.equals(server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize()),"QA world path changed");
  for(var player:server.getPlayerList().getPlayers())require(!(player instanceof FakePlayer)&&player.server==server&&server.getPlayerList().getPlayer(player.getUUID())==player,"actual PlayerList clients required");
 }
 public ConfigView configuration(){requireWorld();return configuration;}
 public Fingerprint fixture(){return fixture;}public String sourceSha(){return sourceSha;}public String directorSha(){return directorSha;}public Path root(){return root;}
 /** Bootstrap can open J04 with zero clients. Complete catalogue survey waits
  * for the actual negotiated pair and never supplies a fake actor or exit. */
 /** Existing durable DISASTER routes are data for catalogue validation, not
  * new admission authority. They let cleanup recovery boot without clients. */
 private List<RouteSegment> retainedRoutes(JournalStore store){
  requireWorld();var session=store.session();require(!store.readOnlySafe(),"current J04 QA writer required");
  if(retainedSession==null)retainedSession=session;require(retainedSession.equals(session),"QA bootstrap J04 session changed");
  retainedIndexReady=false;
  if(retainedRead==null){
   var index=store.residentAggregateKeys(Set.of(AggregateKind.DISASTER),128);
   if(index.isEmpty())return List.of();require(index.orElseThrow().session().equals(session),"retained disaster index session mismatch");
   if(index.orElseThrow().keys().isEmpty()){retainedIndexReady=true;return List.of();}
   retainedQuery=new StoreQuery(session.worldId(),index.orElseThrow().keys(),new Sequence(0),Optional.empty(),128);
   var resident=store.view(retainedQuery);retainedRead=resident.status()==ReadStatus.NOT_RESIDENT?store.read(retainedQuery).toCompletableFuture():CompletableFuture.completedFuture(resident);
  }
  if(!retainedRead.isDone())return List.of();var read=retainedRead.join();retainedRead=null;
  ReadContracts.validate(read,retainedQuery,session);require(read.status()==ReadStatus.AVAILABLE&&!read.page().orElseThrow().hasMore(),"bounded actual disaster readback required");
  var current=store.residentAggregateKeys(Set.of(AggregateKind.DISASTER),128);if(current.isEmpty()||!current.orElseThrow().session().equals(session)||!current.orElseThrow().cut().equals(read.page().orElseThrow().lastDurableSequence()))return List.of();
  var found=new HashSet<AggregateKey>();read.page().orElseThrow().aggregates().forEach(row->found.add(row.state().key()));
  require(found.equals(current.orElseThrow().keys())&&found.equals(retainedQuery.aggregateKeys()),"complete same-cut disaster heads required");
  var routes=new LinkedHashSet<RouteSegment>();
  for(var row:read.page().orElseThrow().aggregates()){
   require(row.committedSequence().value()>0&&row.state() instanceof DisasterAggregate,"actual durable disaster head required");
   var disaster=((DisasterAggregate)row.state()).value();require(disaster.recipeId().equals(LANDSLIDE)&&!disaster.evacuationRoutes().isEmpty(),"closed QA retained landslide with real routes required");
   for(var route:disaster.evacuationRoutes()){
    routes.add(route);require(routes.size()<=64,"retained QA disaster routes exceed complete catalogue bound");
   }
  }
  retainedIndexReady=true;return List.copyOf(routes);
 }
 public Optional<Inputs> complete(JournalStore store){
  requireWorld();if(inputs!=null)return Optional.of(inputs);
  var recovered=retainedRoutes(store);if(!retainedIndexReady)return Optional.empty();
  List<RouteSegment> routes;String geometrySource;
  if(!recovered.isEmpty()){routes=recovered;geometrySource="ACTUAL_RETAINED_J04_DISASTER_EVACUATION_ROUTES_NO_NEW_PERMISSION";}
  else{
   var players=server.getPlayerList().getPlayers().stream().filter(p->cohort==null||cohort.plan().surveyWitnesses().contains(new PlayerId(p.getUUID()))).sorted(Comparator.comparing(ServerPlayer::getUUID)).toList();if(players.size()!=2)return Optional.empty();
   for(var player:players){if(!player.isAlive()||player.serverLevel()!=server.overworld())return Optional.empty();var current=DirectorNativeNetworkBootstrap.currentSession(server,new PlayerId(player.getUUID()));if(current.isEmpty()||!DirectorNativeNetworkBootstrap.sessionNegotiated(server,current.orElseThrow()))return Optional.empty();}
   var surveyed=new ArrayList<RouteSegment>();for(var player:players)surveyed.addAll(DirectorNativeCatalogueCoordinator.surveyExits(player));
   if(surveyed.isEmpty())return Optional.empty();routes=List.copyOf(new LinkedHashSet<>(surveyed));geometrySource="ACTUAL_CONNECTED_J06_PAIR_EIGHT_BLOCK_LOADED_EXIT_SURVEY";
  }
  var candidate=new DirectorNativeCatalogueCatalog(configuration,routes);
  inputs=new Inputs(this,candidate.snapshot(),candidate.references(),geometrySource,routes);return Optional.of(inputs);
 }
 public static final class Inputs {
  private final DirectorProductionRuntimeQa owner;private final CatalogSnapshot catalog;private final fr.proutlost.director.catalog.ContentReferences references;
  private final String geometrySource;private final Fingerprint geometryHash;
  private Inputs(DirectorProductionRuntimeQa owner,CatalogSnapshot catalog,fr.proutlost.director.catalog.ContentReferences references,String geometrySource,List<RouteSegment> routes){this.owner=owner;this.catalog=catalog;this.references=references;this.geometrySource=geometrySource;
   var codec=new ModelCodec<RouteSegment>(RouteSegment.class);this.geometryHash=BlobContent.hash(CanonicalJsonV1.encode(new JsonValue.Array(routes.stream().map(route->CanonicalJsonV1.decode(codec.encode(route))).toList())));
   DirectorProductionRuntimeQa.require(references.capabilities().size()==13&&catalog.events().size()==9&&catalog.chains().size()==2&&catalog.primitives().size()==10&&catalog.events().values().stream().allMatch(e->e.calibration()==Calibration.DEFAULT_FOR_TESTING)&&catalog.chains().values().stream().allMatch(e->e.calibration()==Calibration.DEFAULT_FOR_TESTING)&&catalog.primitives().values().stream().allMatch(p->p.mode()==ProviderMode.TEST_ONLY),"exact unapproved nine/two/ten fixture required");}
  void require(MinecraftServer server){owner.requireWorld();DirectorProductionRuntimeQa.require(owner.server==server&&owner.inputs==this,"world-local typed QA inputs required");}
  public ConfigView configuration(){return owner.configuration();}public CatalogSnapshot catalog(){return catalog;}public ResourceId defaultProfile(){return owner.cohort==null?PROFILE:new ResourceId("proutlost_director:declared_cohort_runtime_qa");}
  public Map<ResourceId,ConfigView> profiles(){return Map.of(defaultProfile(),configuration());}public fr.proutlost.director.catalog.ContentReferences references(){return references;}
  public DirectorProductionRuntimeQa owner(){return owner;}public String geometrySource(){return geometrySource;}public Fingerprint geometryHash(){return geometryHash;}
 }
 /** Explicit mixed-provenance qualification report. This is not a normal
  * Composition and cannot satisfy DirectorReleaseDiagnostic or production approval. */
 public record NinePorts(Map<String,String> implementations,Map<String,Set<ProviderMode>> modes,Fingerprint fixture,String geometrySource,Fingerprint geometryHash,boolean operatorApprovalObtained){
  public NinePorts{Objects.requireNonNull(fixture);Objects.requireNonNull(geometrySource);Objects.requireNonNull(geometryHash);implementations=Map.copyOf(implementations);var copied=new TreeMap<String,Set<ProviderMode>>();modes.forEach((k,v)->copied.put(k,Set.copyOf(v)));modes=Map.copyOf(copied);require(implementations.size()==9&&modes.size()==9&&!operatorApprovalObtained,"declared QA nine-port report required");}
 }
 static NinePorts bind(Inputs token,MinecraftServer server,Object observation,Object memory,Object catalog,Object decision,Object admission,Object effects,Object store,Object facts,Object admin){
  token.require(server);require(store==facts&&store instanceof JournalStore&&((JournalStore)store).session().equals(token.owner.retainedSession),"same actual recovered J04 store/fact bus required");var providers=List.of(observation,memory,catalog,decision,admission,effects,store,facts,admin);
  var names=List.of("ObservationPort","MemoryPort","CatalogPort","DecisionPort","AdmissionPort","WorldEffectPort","DurableStore","FactBusPort","AdminPort");
  var types=List.of(ObservationPort.class,MemoryPort.class,CatalogPort.class,DecisionPort.class,AdmissionPort.class,WorldEffectPort.class,DurableStore.class,FactBusPort.class,AdminPort.class);
  var actual=new TreeMap<String,String>();var modes=new TreeMap<String,Set<ProviderMode>>();
  for(int i=0;i<names.size();i++){var provider=providers.get(i);require(types.get(i).isInstance(provider)&&provider instanceof ProviderProvenance,"actual typed native port with explicit provenance required: "+names.get(i));actual.put(names.get(i),provider.getClass().getName());var supported=Set.copyOf(((ProviderProvenance)provider).supportedModes());require(!supported.isEmpty(),"native port provenance missing");modes.put(names.get(i),supported);}
  return new NinePorts(actual,modes,token.owner.fixture,token.geometrySource,token.geometryHash,false);
 }
}
