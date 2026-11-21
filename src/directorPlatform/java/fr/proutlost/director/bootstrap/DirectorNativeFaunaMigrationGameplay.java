package fr.proutlost.director.bootstrap;

import fr.proutlost.director.adapter.neoforge.server.effects.*;
import fr.proutlost.director.adapter.neoforge.server.ownership.EntityDirectorAttachment;
import fr.proutlost.director.api.codec.ModelCodec;
import fr.proutlost.director.api.codec.CanonicalJsonV1;
import fr.proutlost.director.api.error.ReasonCode;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.permission.CommitPermit;
import fr.proutlost.director.catalog.*;
import fr.proutlost.director.persistence.*;
import fr.proutlost.director.runtime.*;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;

/** Explicit OP4 TEST_ONLY cohort. Native fixtures assert no human authentication or client proof. */
public final class DirectorNativeFaunaMigrationGameplay implements AutoCloseable {
 private final DirectorRuntimeContent content;
 private static final PrimitiveId SPAWN=new PrimitiveId("proutlost:fauna_spawn_owned"),NAV=new PrimitiveId("proutlost:fauna_migrate");
 private static final FactType START=new FactType("proutlost_director:lab_fauna_start"),END=new FactType("proutlost_director:lab_fauna_end"),ROUTE=new FactType("proutlost_director:lab_fauna_route"),ARRIVAL=new FactType("proutlost_director:lab_fauna_arrival");
 private static final FactType WAITING=new FactType("proutlost_director:lab_fauna_waiting_missing_entities");
 private final Set<UUID> heldCohorts=new HashSet<>(),holding=new HashSet<>();
 private static final ModelCodec<EntityOwnershipRecord> OWNER=new ModelCodec<>(EntityOwnershipRecord.class);
 private static final ModelCodec<FaunaNavigationRoute> PATH=new ModelCodec<>(FaunaNavigationRoute.class);
 private static final ModelCodec<PreparedEffect> PLAN=new ModelCodec<>(PreparedEffect.class);
 private static final BudgetVector CAP=new BudgetVector(100,100,100,128,128,256,1048576,100,1048576,4096);
 private final MinecraftServer server;private final WorldId world;private final JournalStore store;private final CatalogSnapshot catalog;
 private final Map<UUID,EntityOwnershipRecord> rows=new HashMap<>();
 private final Map<UUID,ResourceId> expectedSpecies=new HashMap<>();
 private final Map<UUID,DirectorFact> starts=new LinkedHashMap<>();
 private final Set<UUID> ends=new HashSet<>(),arrivals=new HashSet<>(),spawnedIds=new HashSet<>(),ending=new HashSet<>(),startedCohorts=new HashSet<>();
 private final Map<UUID,PreparedEffect> routes=new HashMap<>();
 private final Map<UUID,EffectIntent> proposedNavigation=new HashMap<>();
 private final Map<OperationToken,PrepareRecord> prepares=new LinkedHashMap<>();private final Set<OperationToken> results=new HashSet<>();
 private CompletableFuture<StoreReadResult> pendingRead;private Optional<String> cursor=Optional.empty();
 private boolean scanned,shared,closed,failed;private long floor,clockFloor,offset;private Flight flight;private BlobBundle recoveryBlobs;private UUID lastEvent;
 private enum Phase{READY,SPAWNING,NAV_SEAL,NAVIGATING,MOVING,METADATA,REMOVING,SETTLED,REMOVED,RECOVER,WAITING}
 private static final class Exec{
  FaunaSpawnBinding spawnBinding;EventRunner runner;TransactionId tx;LiveValidationSnapshot live;ResourceArbiter arbiter;ReservationToken token;boolean committed,released;boolean selectedForward;EventRunner.Stage lastStage=EventRunner.Stage.PREPARING;
  ServerEffectPort.Binding navigationProvider;PreparedEffect navigationPrepared;BlobBundle navigationBlobs;StoreSession navigationSession;long navigationRecheckAt;
 }
 private static final class Slot{
  EntityOwnershipRecord owner,initial,pendingOwner,preparationOwner;List<BlockPosition> route;EffectIntent spawn;OperationToken reconciledOperation;
  Phase phase=Phase.READY;FaunaNavigationBinding navigator;PreparedEffect navPlan;BlobBundle navBlobs;
  EffectPreparation captured;CompletableFuture<CommitAck> ack;Exec exec;boolean unused;final List<Exec> held=new ArrayList<>();
 }
 private static final class Flight{
  UUID id,witness;ResourceId pinnedSpecies;CohortId cohort;List<Slot> slots=new ArrayList<>();long deadline;
  SelectedEventStart selection;ExecutionPlan selectedPlan;DecisionId selectedDecision;
  NativeCatalogueColdCompletion.Identity coldIdentity;NativeCatalogueColdCompletion.Read coldRead;
  Optional<ChainId> parentChain=Optional.empty();RegionId selectedRegion;
  boolean stopping,recovery,admitted,testOnly;CompletableFuture<CommitAck> ack;CompletableFuture<BlobReadResult> blobs;
  ResourceArbiter arbiter;ReservationToken token;AdmissionService admission;DecisionId decision;
 }
 public record LabStatus(UUID event,boolean recovering,boolean failed,boolean active,int spawned,int arrived,int settled,int live,int removedSlots,boolean ended){}
 public DirectorNativeFaunaMigrationGameplay(MinecraftServer server,WorldId world,JournalStore store){
  this.content=null;this.server=Objects.requireNonNull(server);this.world=Objects.requireNonNull(world);this.store=Objects.requireNonNull(store);catalog=catalogue();actor();}
 public DirectorNativeFaunaMigrationGameplay(MinecraftServer server,WorldId world,JournalStore store,DirectorRuntimeContent content){
  this.content=Objects.requireNonNull(content);this.server=Objects.requireNonNull(server);this.world=Objects.requireNonNull(world);this.store=Objects.requireNonNull(store);catalog=content.catalog();actor();}
 private ProviderMode providerMode(){return content==null?ProviderMode.TEST_ONLY:content.mode();}
 private void requireLaboratory(){if(content!=null)content.requireLaboratory();}
 private void requireSelected(SelectedEventStart selected){
  Objects.requireNonNull(selected);
  if(selected.mode()!=providerMode()||(content!=null&&!selected.definition().equals(content.definition(selected.definition().id()))))
   throw new IllegalArgumentException("selected runtime content/mode mismatch");
 }
 private void actor(){if(closed||!server.isSameThread())throw new IllegalStateException("fauna LAB actor");}
 private Tick now(){return new Tick(content!=null&&content.production()?fr.proutlost.director.neoforge.DirectorStandaloneMod.directorLifecycle().runtimeClock(server):Math.addExact(server.overworld().getGameTime(),offset));}
 private static BlockPos pos(BlockPosition p){return new BlockPos(p.x(),p.y(),p.z());}
 private ServerLevel level(DimensionId d){for(var l:server.getAllLevels())if(l.dimension().location().toString().equals(d.value()))return l;throw new IllegalStateException("fauna dimension");}
 private static ResourceKey key(EntityOwnershipRecord o){return new ResourceKey(OwnershipDomain.ENTITY,o.lastKnownChunk().dimension(),o.entityUuid().toString());}
 private static String encode(byte[] b){return Base64.getEncoder().encodeToString(b);}
 private static String text(DirectorFact f,String k){return ((JsonValue.Text)f.payload().get(k)).value();}
 private static byte[] decode(DirectorFact f,String k){return NativeCatalogueColdCompletion.decodedBytes(f.payload().get(k));}
 private EventDefinition definition(){return Objects.requireNonNull(catalog.events().get(new DefinitionId("proutlost_director:candidate/animal_migration")));}
 private FaunaMigrate migration(){return (FaunaMigrate)definition().steps().getFirst().args();}
 private FaunaMigrate migration(Flight f){
  if(f.selectedPlan!=null)return (FaunaMigrate)f.selectedPlan.effects().getFirst().typedArgs();
  var original=migration();return f.pinnedSpecies==null?original:new FaunaMigrate(f.pinnedSpecies,
    original.cohortSize(),original.corridorTag(),original.arrivalPolicy());
 }
 private static ResourceId species(Slot slot){return ((FaunaMigrate)slot.spawn.typedArgs()).species();}
 private static boolean expected(Animal animal,ResourceId species){
  return NativeFaunaHabitat.supports(species)&&BuiltInRegistries.ENTITY_TYPE.getKey(animal.getType()).toString().equals(species.value());
 }
 private ResourceId recordedSpecies(DirectorFact fact){
  ResourceId species=fact.payload().get("species") instanceof JsonValue.Text value?new ResourceId(value.value()):null;
  var selected=fact.payload().get("selected_plan");
  if(selected!=null){var plan=new ModelCodec<>(ExecutionPlan.class).decode(NativeCatalogueColdCompletion.decodedBytes(selected));
   if(plan.effects().isEmpty()||!(plan.effects().getFirst().typedArgs() instanceof FaunaMigrate args))throw new IllegalStateException("fauna saved species contract");
   if(species!=null&&!species.equals(args.species()))throw new IllegalStateException("fauna saved species substitution");species=args.species();}
  // Legacy non-selected LAB facts only ever spawned sheep.
  if(species==null)species=NativeFaunaHabitat.SHEEP;
  if(!NativeFaunaHabitat.supports(species))throw new IllegalStateException("unknown saved fauna species profile");return species;
 }
 private static FaunaMigrate unit(FaunaMigrate a){return new FaunaMigrate(a.species(),1,a.corridorTag(),a.arrivalPolicy());}
 private static Footprint footprint(List<BlockPosition> cells){var chunks=new HashSet<ChunkRef>();for(var p:cells)chunks.add(new ChunkRef(p.dimension(),p.x()>>4,p.z()>>4));return new Footprint(cells.getFirst().dimension(),chunks,new HashSet<>(cells),List.of());}
 private CompletableFuture<CommitAck> append(List<DurableRecord> r){return store.append(ResultTransaction.batch(new TransactionId(UUID.randomUUID()),store.session().storeGeneration(),Map.of(),r,List.of())).toCompletableFuture();}
 private boolean durable(CompletableFuture<CommitAck> f){if(f==null||!f.isDone())return false;if(f.isCompletedExceptionally()||f.getNow(null)==null||f.getNow(null).ackStatus()!=AckStatus.DURABLE)throw new IllegalStateException("fauna durable ACK");return true;}
 private DirectorFact fact(FactType t,Flight f,Map<String,JsonValue> p){
  var subjects=new LinkedHashSet<ScopeRef>();subjects.add(new ScopeRef(ScopeKind.WORLD,world.value()));
  var payload=new TreeMap<>(p);
  if(f.selectedPlan!=null){
   subjects.addAll(f.selectedPlan.scopes().scopes());
   payload.put("definition",new JsonValue.Text(f.selectedPlan.definition().id().value()));
   payload.put("catalogue_selected",new JsonValue.Bool(true));
   f.parentChain.ifPresent(id->payload.put("chain",new JsonValue.Text(id.value().toString())));
  }
  return new DirectorFact(new FactId(UUID.randomUUID()),t,List.copyOf(subjects),
      Optional.of(new EventInstanceId(f.id)),EvidenceStatus.CONFIRMED,payload,true,Optional.empty());
 }
 /** Explicit opt-in for the existing single-writer LAB physical cohort harness.
  * Production instances receive content.catalog() and never enter this factory. */
 private static ResourceId nativeFixtureSpecies(){
  var species=new ResourceId(System.getProperty("proutlost.director.nativeFaunaSpecies",NativeFaunaHabitat.SHEEP.value()).strip());
  if(species.equals(NativeFaunaHabitat.SHEEP))return species;
  if(!species.equals(NativeFaunaHabitat.CAPYBARA)
      ||!Boolean.getBoolean("proutlost.director.nativeLab")
      ||!Boolean.getBoolean("proutlost.director.nativeFaunaGameTest")
      ||!Boolean.getBoolean("proutlost.director.nativeModdedFaunaGameTest"))
   throw new IllegalArgumentException("non-sheep LAB catalogue requires explicit modded physical qualification flags");
  if(BuiltInRegistries.ENTITY_TYPE.getOptional(ResourceLocation.parse(species.value())).isEmpty())
   throw new IllegalStateException("MISSING_REQUIRED_DEPENDENCY "+species.value());
  return species;
 }
 /** Copy the existing source document; only the single species scalar changes semantically. */
 private static DefinitionLoader.Source nativeFixtureSource(DefinitionLoader.Source source,ResourceId species){
  if(species.equals(NativeFaunaHabitat.SHEEP))return source;
  var root=new LinkedHashMap<>(((JsonValue.ObjectValue)CanonicalJsonV1.decode(source.bytes())).values());
  var steps=((JsonValue.Array)root.get("steps")).values();
  if(steps.size()!=1)throw new IllegalStateException("native fauna fixture expects one existing migrate step");
  var step=new LinkedHashMap<>(((JsonValue.ObjectValue)steps.getFirst()).values());
  var args=new LinkedHashMap<>(((JsonValue.ObjectValue)step.get("args")).values());
  if(!new JsonValue.Text(NativeFaunaHabitat.SHEEP.value()).equals(args.get("species")))
   throw new IllegalStateException("native fauna fixture source species drift");
  args.put("species",new JsonValue.Text(species.value()));
  step.put("args",new JsonValue.ObjectValue(args));
  root.put("steps",new JsonValue.Array(List.of(new JsonValue.ObjectValue(step))));
  var copied=new DefinitionLoader.Source(source.path(),CanonicalJsonV1.encode(new JsonValue.ObjectValue(root)));
  System.out.println("DIRECTOR_NATIVE_FAUNA_FIXTURE_CATALOGUE species="+species.value()+" source_sha256="
      +BlobContent.hash(source.bytes()).value()+" fixture_sha256="+BlobContent.hash(copied.bytes()).value()
      +" mode=TEST_ONLY semantic_changes=steps[0].args.species");
  return copied;
 }
 private static CatalogSnapshot catalogue(){
  var fixtureSpecies=nativeFixtureSpecies();
  var config=DirectorLabObservationProfile.load();var primitive=new PrimitiveId("proutlost:fauna_migrate");var codec=new ResourceId("proutlost:codec_fauna_migrate");var resolver=new ResourceId("proutlost:footprint");var cleanup=new ResourceId("proutlost:cleanup");
  var caps=Set.of(new CapabilityId("entity.migration_v1"),new CapabilityId("habitat.corridor_v1"));
  var descriptor=new PrimitiveDescriptor(primitive,1,codec,caps,BudgetMath.zero(),resolver,Set.of(new ResourceId("proutlost_director:animal_route")),Set.of(OwnershipDomain.ENTITY),Set.of(),new DurationTicks(6000),PausePolicy.CLEANUP,RecoveryMode.RECOVER,cleanup,Optional.empty(),Optional.empty(),ProviderMode.TEST_ONLY);
  var spawn=new PrimitiveDescriptor(SPAWN,1,codec,Set.of(new CapabilityId("entity.spawn_owned_v1")),BudgetMath.zero(),resolver,Set.of(new ResourceId("proutlost_director:entity_slot")),Set.of(OwnershipDomain.ENTITY),Set.of(),new DurationTicks(6000),PausePolicy.CLEANUP,RecoveryMode.RECOVER,cleanup,Optional.empty(),Optional.empty(),ProviderMode.TEST_ONLY);
  var known=new EnumMap<ContentReferences.Kind,Set<ResourceId>>(ContentReferences.Kind.class);
  known.put(ContentReferences.Kind.ENTITY,Set.of(fixtureSpecies));
  known.put(ContentReferences.Kind.TAG,Set.of(new ResourceId("proutlost:fauna_corridor"),new ResourceId("proutlost:fauna_settled")));
  known.put(ContentReferences.Kind.FACT,Set.of(new ResourceId("proutlost:animal_migration_observed")));
  known.put(ContentReferences.Kind.CODEC,Set.of(codec,new ResourceId("proutlost_director:animal_route"),new ResourceId("proutlost_director:entity_slot")));
  known.put(ContentReferences.Kind.RESOLVER,Set.of(resolver));known.put(ContentReferences.Kind.CLEANUP,Set.of(cleanup));
  var all=new HashSet<>(caps);all.add(new CapabilityId("entity.spawn_owned_v1"));
  var refs=new ContentReferences(ProviderMode.TEST_ONLY,known,Map.of(),Map.of(),Map.of(),all,Set.of("ENTITY:COHORT","NAVIGATION:CORRIDOR"));
  var source=new DirectorNativeCandidateCatalog(config).candidateDocuments().stream().filter(s->s.path().equals("data/proutlost_director/director/events/candidate/animal_migration.json")).findFirst().orElseThrow();
  source=nativeFixtureSource(source,fixtureSpecies);
  var loaded=new DefinitionLoader(config,new PrimitiveRegistry(List.of(descriptor,spawn),ProviderMode.TEST_ONLY),refs,ProviderMode.TEST_ONLY,new DefinitionLoader.Limits(32,65536,4194304,128,128)).reload(List.of(source));
  if(!loaded.installed()||!loaded.diagnostics().isEmpty())throw new IllegalStateException("actual migration catalogue "+loaded.diagnostics());return loaded.snapshot();
 }
 private boolean freshSelection(Flight f){
  if(f.selectedPlan==null)return true;if(f.selection==null)return false;
  return f.selection.forwardContextAvailable();
 }
 private boolean witness(Flight f){
  if(f.testOnly)return true;
  if(f.selectedPlan!=null){
   if(f.selection==null)return false; // Restart cleans up owned active slots, without invented J03 input.
   var ids=f.selectedPlan.roles().roles().values().stream().flatMap(Collection::stream).distinct().toList();
   if(ids.isEmpty())return false;
   for(var id:ids){
    var p=server.getPlayerList().getPlayer(id.value());
    if(p==null||!p.isAlive()||p.serverLevel()!=level(f.slots.getFirst().owner.lastKnownChunk().dimension()))return false;
    var b=p.blockPosition();var at=new BlockPosition(f.slots.getFirst().owner.lastKnownChunk().dimension(),b.getX(),b.getY(),b.getZ());
    if(fr.proutlost.director.neoforge.DirectorStandaloneMod.directorLifecycle().runtimePrimaryRegion(server,at)
        .filter(f.selectedRegion::equals).isEmpty())return false;
   }
   return true;
  }
  var p=server.getPlayerList().getPlayer(f.witness);
  return p!=null&&p.hasPermissions(4)&&p.isAlive()&&p.serverLevel()==level(f.slots.getFirst().owner.lastKnownChunk().dimension());
 }
 private boolean same(EntityOwnershipRecord a,EntityOwnershipRecord b){return a.header().id().equals(b.header().id())&&a.ownerToken().equals(b.ownerToken())&&a.entityUuid().equals(b.entityUuid())&&a.instanceId().equals(b.instanceId())&&a.cohortId().equals(b.cohortId())&&a.slotId().equals(b.slotId());}
 private Animal ownedAnimal(Slot s){var e=level(s.owner.lastKnownChunk().dimension()).getEntity(s.owner.entityUuid());if(!(e instanceof Animal a)||!expected(a,species(s))||!a.isAlive()||a.isLeashed()||a.isPassenger()||a.isVehicle()||!EntityDirectorAttachment.read(a).equals(Optional.of(s.owner)))throw new IllegalStateException("owned profiled animal unavailable/changed; preserve UUID "+s.owner.entityUuid());return a;}
 private DirectorFact dossier(Flight f){
  var paths=new ArrayList<JsonValue>();for(var s:f.slots)paths.add(new JsonValue.Text(encode(PATH.encode(new FaunaNavigationRoute(s.initial,s.route,.8)))));
  var values=new TreeMap<String,JsonValue>();
  values.put("witness",new JsonValue.Text(f.witness==null?"NATIVE_FIXTURE_NO_HUMAN":f.witness.toString()));
  values.put("testOnly",new JsonValue.Text(Boolean.toString(f.testOnly)));
  values.put("cohort",new JsonValue.Text(f.cohort.value().toString()));
  values.put("species",new JsonValue.Text(migration(f).species().value()));
  values.put("deadline",new JsonValue.Text(Long.toString(f.deadline)));
  values.put("tick",new JsonValue.Text(Long.toString(now().value())));
  values.put("paths",new JsonValue.Array(paths));
  if(f.selectedPlan!=null){
   values.put("selected_plan",NativeCatalogueColdCompletion.encodedBytes(new ModelCodec<>(ExecutionPlan.class).encode(f.selectedPlan)));
   values.put("selected_region",new JsonValue.Text(f.selectedRegion.value().toString()));
   values.put("selected_decision",new JsonValue.Text(f.selectedDecision.value().toString()));
   NativeCatalogueColdCompletion.pin(Objects.requireNonNull(f.coldIdentity),values);
   f.parentChain.ifPresent(id->values.put("parent_chain",new JsonValue.Text(id.value().toString())));
  }
  return fact(START,f,values);
 }
 private boolean ready(){return scanned&&!failed&&!store.readOnlySafe()&&flight==null&&availableStart()&&available(new UUID(0,0)).persistentEntities()>=24;}
 public String begin(ServerPlayer player){requireLaboratory();actor();if(!ready())return "Migration LAB en attente/recuperation.";if(!player.hasPermissions(4)||!player.isAlive())return "OP4 vivant requis.";startCohort(player.getUUID(),NativeFaunaHabitat.plan(player),false);return "Migration LAB :24 vrais moutons, arrivee puis transfert permanent au monde.";}
 public UUID labTestBegin(NativeFaunaHabitat.Layout layout){requireLaboratory();actor();if(!Boolean.getBoolean("proutlost.director.nativeFaunaGameTest"))throw new IllegalStateException("native fauna fixture disabled");if(!ready())return null;return startCohort(null,layout,true);}
 /** Read-only physical planning before the external selected proposal is sealed. */
 public NativeFaunaHabitat.Layout preview(Candidate candidate,DecisionInput input){
  actor();return NativeFaunaSelectedHabitat.preview(server,candidate,input);
 }
 public boolean selectedAvailable(){actor();return scanned&&!failed&&!store.readOnlySafe()&&flight==null&&availableStart();}
 public Optional<UUID> activeEvent(){actor();return flight==null?Optional.empty():Optional.of(flight.id);}
 public boolean selectedEntityAvailable(UUID id){actor();return scanned&&!failed&&!store.readOnlySafe()&&!rows.containsKey(id)
   &&java.util.stream.StreamSupport.stream(server.getAllLevels().spliterator(),false).noneMatch(level->level.getEntity(id)!=null);}
 public String beginSelected(SelectedEventStart selected){requireSelected(selected);
  actor();Objects.requireNonNull(selected);
  if(!ready())throw new IllegalStateException("Migration catalogue en attente/recuperation.");
  var current=selected.refresh();var plan=selected.plan();var reference=new DefinitionRef(definition().id(),definition().contentRevision(),
      BlobContent.hash(new ModelCodec<>(EventDefinition.class).encode(definition())));
  if(!plan.definition().equals(reference)||!plan.scopes().worldId().equals(world)
      ||selected.proposal().selectedPlan().filter(plan::equals).isEmpty()
      ||plan.effects().size()!=1||!plan.effects().getFirst().primitive().equals(NAV)
      ||!plan.effects().getFirst().typedArgs().equals(migration()))
   throw new IllegalArgumentException("exact selected migration proposal required");
  var witnesses=plan.roles().roles().getOrDefault("witness",List.of());
  if(witnesses.size()!=1)throw new IllegalArgumentException("actual selected witness required");
  var actual=server.getPlayerList().getPlayer(witnesses.getFirst().value());
  var captured=current.context().context().players().get(witnesses.getFirst());
  if(actual==null||!actual.isAlive()||captured==null||captured.eligibility()!=PlayerEligibility.ELIGIBLE)
   throw new IllegalArgumentException("selected witness unavailable");
  var regionScopes=plan.scopes().scopes().stream().filter(s->s.kind()==ScopeKind.REGION).toList();
  if(regionScopes.size()!=1)throw new IllegalArgumentException("one selected region required");
  var region=new RegionId(regionScopes.getFirst().id());
  var observed=current.context().context().regions().get(region);
  if(observed==null||observed.region().status()!=RegionStatus.ACTIVE
      ||!observed.tags().contains(NativeFaunaHabitat.CORRIDOR))
   throw new IllegalArgumentException("actual selected corridor region required");
  var id=plan.effects().getFirst().instanceId().value();
  if(starts.containsKey(id)||ends.contains(id))throw new IllegalArgumentException("selected migration already consumed");
  var layout=NativeFaunaSelectedHabitat.plan(server,plan,current.context());
  startCohort(witnesses.getFirst().value(),layout,false,selected);
  return "Migration catalogue selectionnee "+id+" :24animaux reels "+migration(flight).species().value()+", arrivees puis transfert durable.";
 }
 private UUID startCohort(UUID witness,NativeFaunaHabitat.Layout layout,boolean fixture){
  return startCohort(witness,layout,fixture,null);
 }
 private UUID startCohort(UUID witness,NativeFaunaHabitat.Layout layout,boolean fixture,SelectedEventStart selected){
  var args=selected==null?migration():(FaunaMigrate)selected.plan().effects().getFirst().typedArgs();if(args.cohortSize()!=24||!NativeFaunaHabitat.supports(args.species())||args.arrivalPolicy()!=ArrivalPolicy.SETTLE_WORLD)throw new IllegalStateException("actual24 registered fauna candidate required");
  if(EntityCohorts.persistentSlots(rows.values())+24>CAP.persistentEntities())throw new IllegalStateException("persistent native LAB capacity");
  long pathCells=layout.routes().stream().mapToLong(List::size).sum();
  if(layout.routes().size()!=24||layout.routes().stream().map(List::getFirst).distinct().count()!=24||layout.routes().stream().flatMap(List::stream).distinct().count()!=pathCells||layout.routes().stream().anyMatch(r->r.size()<2||!NativeFaunaHabitat.qualified(level(r.getFirst().dimension()),r,r.getFirst(),true,args.species())))throw new IllegalArgumentException("24 real disjoint qualified native paths required");
  var f=new Flight();f.id=selected==null?UUID.randomUUID():selected.plan().effects().getFirst().instanceId().value();
  f.witness=witness;f.testOnly=fixture;f.pinnedSpecies=args.species();f.cohort=new CohortId(UUID.randomUUID());
  f.deadline=now().value()+(selected==null?definition():selected.definition()).timing().maxDurationTicks().value();
  if(selected!=null){
   f.selection=selected;f.selectedPlan=selected.plan();f.selectedDecision=selected.proposal().proposalId();f.parentChain=selected.parentChain();
   f.coldIdentity=NativeCatalogueColdCompletion.beforeApply(selected,selected.refresh());
   f.selectedRegion=new RegionId(selected.plan().scopes().scopes().stream()
       .filter(s->s.kind()==ScopeKind.REGION).findFirst().orElseThrow().id());
  }
  var positions=layout.routes().stream().map(List::getFirst).toList();var raw=CohortPlanner.spawnIntents(world,new EventInstanceId(f.id),args,positions,layout.chunks(),24);var byPosition=new HashMap<BlockPosition,EffectIntent>();for(var i:raw)byPosition.put(i.expectedFootprint().cells().iterator().next(),i);
  var records=new ArrayList<DurableRecord>();int selectedSlot=0;
  for(var route:layout.routes()){
   var p=route.getFirst();var id="lab-sheep/"+p.x()+"/"+p.y()+"/"+p.z();var entity=f.selectedPlan==null?EntityCohorts.stableEntityId(world,new EventInstanceId(f.id),f.cohort,id):UUID.nameUUIDFromBytes(("catalogue-fauna-v1\0"+f.id+"\0"+selectedSlot).getBytes(java.nio.charset.StandardCharsets.UTF_8));selectedSlot++;var s=new Slot();s.route=route;
   s.owner=new EntityOwnershipRecord(new AggregateHeader(1,UUID.randomUUID(),new Revision(1),new Sequence(0),now()),new OwnerToken(UUID.randomUUID()),entity,new EventInstanceId(f.id),f.cohort,id,EntityPhase.RESERVED,OwnershipDisposition.DIRECTOR_ACTIVE,new ChunkRef(p.dimension(),p.x()>>4,p.z()>>4),now(),ArrivalPolicy.SETTLE_WORLD,CleanupReason.COMPLETED,Optional.empty(),new Epoch(1));s.initial=s.owner;expectedSpecies.put(s.owner.entityUuid(),args.species());
   var i=byPosition.get(p);s.spawn=new EffectIntent(i.operationToken(),SPAWN,1,i.typedArgs(),i.expectedFootprint(),i.instanceId());f.slots.add(s);records.add(new DurableAggregate(new EntityOwnershipAggregate(s.owner)));
  }
  var start=dossier(f);records.add(new DurableFact(start));f.ack=append(records);starts.put(f.id,start);flight=f;lastEvent=f.id;return f.id;
 }
 private ResourceArbiter arbiter(){return new ResourceArbiter(world,new Epoch(Math.max(floor,store.session().bootEpoch().value())),1000,4,new ResourceArbiter.Compatibility(){public boolean share(ResourceClaim a,ResourceClaim b){return false;}public boolean merge(ResourceId id,ResourceClaim a,ResourceClaim b){return false;}});}
 private FaunaSpawnBinding spawner(Slot s){var species=species(s);return new FaunaSpawnBinding(this::level,i->s.owner,next->{s.owner=next;s.preparationOwner=next;rows.put(next.entityUuid(),next);},()->NativeFaunaHabitat.qualified(level(s.owner.lastKnownChunk().dimension()),s.route,s.route.getFirst(),true,species),Set.of(species),true,NativeFaunaHabitat.usesBrain(species));}
 private PlanningSnapshot faunaSnapshot(Flight f,List<BlockPosition> route){
  var nativeSnapshot=NativeFaunaHabitat.snapshot(server,world,route,now(),migration(f).species());
  return f.selection==null?nativeSnapshot:SelectedNativeSnapshot.terrain(f.selection.refresh(),nativeSnapshot.snapshot().context().terrain().getFirst());
 }
 private void admit(Flight f){
  var effects=new ArrayList<EffectIntent>();var claims=new ArrayList<ResourceClaim>();var epochs=new HashMap<ResourceKey,Epoch>();var hashes=new HashMap<ResourceKey,Fingerprint>();
  for(var s:f.slots){
   var context=faunaSnapshot(f,s.route);var captured=spawner(s).capture(s.spawn,context);var p=captured.prepared().orElseThrow();var k=key(s.owner);
   effects.add(s.spawn);var nav=new EffectIntent(new OperationToken(UUID.randomUUID()),NAV,1,unit(migration(f)),footprint(s.route),new EventInstanceId(f.id));effects.add(nav);proposedNavigation.put(s.owner.entityUuid(),nav);
   epochs.put(k,s.owner.generation());hashes.put(k,p.preconditions().fingerprints().get(k));
   claims.add(new ResourceClaim(k,ClaimPolicy.EXCLUSIVE,new BudgetVector(0,0,0,1,1,1,0,0,0,s.route.size()),Optional.empty(),s.owner.generation()));
  }
  var revisions=new HashMap<ScopeRef,Revision>();
  if(f.selectedPlan!=null){
   revisions.putAll(f.selectedPlan.readSet().revisions());
   for(var entry:f.selectedPlan.readSet().epochs().entrySet())if(!epochs.containsKey(entry.getKey()))epochs.put(entry.getKey(),entry.getValue());
   for(var entry:f.selectedPlan.readSet().fingerprints().entrySet())if(!hashes.containsKey(entry.getKey()))hashes.put(entry.getKey(),entry.getValue());
  }
  var read=new ReadSet(revisions,epochs,hashes);var def=definition();var scope=new ScopeRef(ScopeKind.WORLD,world.value());
  var roles=f.selectedPlan!=null?f.selectedPlan.roles():new RoleAssignments(f.testOnly?Map.of():Map.of("witness",List.of(new PlayerId(f.witness))));
  var reference=new DefinitionRef(def.id(),def.contentRevision(),BlobContent.hash(new ModelCodec<>(EventDefinition.class).encode(def)));
  var scopes=f.selectedPlan!=null?f.selectedPlan.scopes():new ScopeSet(world,f.testOnly?Set.of(scope):Set.of(scope,new ScopeRef(ScopeKind.PLAYER,f.witness)));
  var plan=new ExecutionPlan(f.selectedPlan==null?new CandidateId(UUID.randomUUID()):f.selectedPlan.candidateId(),reference,effects,roles,scopes,read);
  var request=new AdmissionRequest(f.selectedDecision==null?new DecisionId(UUID.randomUUID()):f.selectedDecision,read,claims,roles,plan);
  var cost=BudgetMath.add(def.budget(),new BudgetVector(0,0,0,0,0,24,0,0,0,f.slots.stream().mapToLong(s->s.route.size()).sum()));
  f.arbiter=arbiter();var validator=new AdmissionService.HardValidator(){
   public ProviderMode mode(){return providerMode();}
   public AdmissionService.Assessment assess(AdmissionRequest proposed,Tick tick){
    boolean budgetFits=BudgetMath.fits(cost,available(f));
    boolean valid=budgetFits&&request.equals(proposed)&&witness(f)&&!store.readOnlySafe()&&f.slots.size()==24&&f.slots.stream().map(s->s.owner.entityUuid()).distinct().count()==24&&
     f.slots.stream().allMatch(s->s.owner.phase()==EntityPhase.RESERVED&&NativeFaunaHabitat.qualified(level(s.owner.lastKnownChunk().dimension()),s.route,s.route.getFirst(),true,species(s)));
    return new AdmissionService.Assessment(new ValidationResult(valid,valid?List.of():List.of(budgetFits?ReasonCode.STALE_CONTEXT:ReasonCode.BUDGET_EXHAUSTED),read),claims,new ResourceArbiter.Rank(false,false,false,Priority.AMBIENT,0,1,42),Map.of(scope,cost));
   }
  };
  f.admission=new AdmissionService(()->catalog,validator,f.arbiter,this::now,new DurationTicks(6000),4,providerMode());f.decision=request.proposalId();
  if((content==null?f.admission.admit(request):content.admit(f.admission,request)).status()!=AdmissionStatus.DEFERRED)throw new IllegalStateException("fauna J03 full cohort rejected");
  var answer=f.admission.resolveWindow(epochs,Map.of(scope,available(f))).get(f.decision);if(answer==null||answer.status()!=AdmissionStatus.ACCEPTED)throw new IllegalStateException("fauna J03 denied "+answer);
  f.token=answer.token().orElseThrow();floor=Math.max(floor,f.token.generation().value());f.admitted=true;
  System.out.println("DIRECTOR_LAB_FAUNA_J02_J03_24_ACCEPTED "+f.id+" admission_mode="+(f.selectedPlan==null?"MANUAL_LAB":"CATALOGUE_SELECTED")+" native_phase_overhead=true native_fixture="+f.testOnly+" player_present="+!f.testOnly+" online_authentication="+(!f.testOnly&&server.usesAuthentication()));
 }
 private Exec run(Flight f,Slot s,EffectPreparation captured,ServerEffectPort.Binding provider){
  var p=captured.prepared().orElseThrow();var i=p.intent();var e=new Exec();e.selectedForward=f.selection!=null&&(i.primitive().equals(SPAWN)||i.primitive().equals(NAV));if(provider instanceof FaunaSpawnBinding b)e.spawnBinding=b;e.arbiter=arbiter();var scope=new ScopeRef(ScopeKind.WORLD,world.value());var epochs=new HashMap<ResourceKey,Epoch>();p.claims().forEach(c->epochs.put(c.resource(),c.expectedEpoch()));
  if(i.primitive().equals(NAV)){e.navigationProvider=provider;e.navigationPrepared=p;e.navigationBlobs=captured.blobs();e.navigationSession=store.session();}
  var candidate=new ResourceArbiter.Candidate(new ReservationId(UUID.randomUUID()),i.instanceId(),new ResourceArbiter.Rank(false,false,false,Priority.AMBIENT,0,1,42),now().plus(1200),p.claims(),Map.of(scope,p.budgetEstimate()));
  e.token=e.arbiter.arbitrate(List.of(candidate),now(),epochs,Map.of(scope,e.selectedForward?available(f):available(f.id))).getFirst().accepted().orElseThrow();floor=Math.max(floor,e.token.generation().value());
  e.live=new LiveValidationSnapshot(p.preconditions(),i.expectedFootprint().loadedChunks(),HealthMode.NORMAL,now(),e.token.generation());
  var slice=new PreparedSlice(new SliceId(UUID.randomUUID()),i.operationToken(),i.expectedFootprint(),p.preconditions(),List.of(i),p.budgetEstimate());var authority=new CommitPermit.Authority(store,4);
  var port=new ServerEffectPort(authority,intent->{throw new IllegalStateException("already captured");},x->e.live,Map.of(i.primitive(),provider),provider.capabilities(),4,x->new StabilizationLiveSnapshot(store.session(),e.live,new OwnershipView(Map.of())),m->{},(m,r)->{});port.hydrate(p,captured.blobs());
  var prepare=new PrepareRecord(new TransactionId(UUID.randomUUID()),i.operationToken(),e.token.generation(),p.preconditions(),e.token.expiryTick(),slice,e.token,captured.validation(),List.of(),Set.of(),List.of(),p.durableBlobs());e.tx=prepare.transactionId();prepares.put(i.operationToken(),prepare);
  e.runner=new EventRunner(authority,store,content==null?port:content.attach(p,captured.blobs(),port,e.selectedForward),e.arbiter,4);e.runner.submit(new DurableBatch(e.tx,store.session().storeGeneration(),prepare.readSet().revisions(),List.of(new DurablePrepare(prepare)),captured.blobs().contents(),Optional.empty(),CommitPermit.Authority.prepareHash(prepare)),p.expectedPost(),e.live,actual->{
   var records=new ArrayList<DurableRecord>();records.add(new DurableResult(new ResultRecord(e.tx,i.operationToken(),actual,Set.of(),List.of(),List.of())));
   if(i.primitive().equals(SPAWN)||i.primitive().equals(FaunaRemoveBinding.REMOVE))records.add(new DurableAggregate(new EntityOwnershipAggregate(s.owner)));
   return ResultTransaction.batch(new TransactionId(UUID.randomUUID()),store.session().storeGeneration(),Map.of(),records,List.of());
  });s.held.add(e);return e;
 }
 private boolean applied(Exec e){
  if(e.committed)return true;
  var current=now();
  boolean liveNavigationAuthority=e.navigationProvider!=null&&e.lastStage==EventRunner.Stage.PREPARING
   &&e.navigationSession.equals(store.session())&&current.compareTo(e.token.expiryTick())<0;
  if(e.selectedForward&&e.lastStage==EventRunner.Stage.PREPARING
     &&(e.navigationProvider==null||liveNavigationAuthority)&&!flight.selection.forwardContextAvailable())return false;
  if(liveNavigationAuthority){
   if(current.value()<e.navigationRecheckAt)return false;
   // Read only: the SAME immutable native plan must be valid before polling/consuming its authority.
   if(!e.navigationProvider.revalidate(e.navigationPrepared,e.navigationBlobs)){
    e.navigationRecheckAt=current.plus(20).value();
    System.out.println("DIRECTOR_LAB_FAUNA_NAV_WAITING_NATIVE event="+e.navigationPrepared.intent().instanceId().value()
     +" operation="+e.navigationPrepared.intent().operationToken().value()+" expiry="+e.token.expiryTick().value()+" next_check="+e.navigationRecheckAt);
    return false;
   }
   e.navigationRecheckAt=0;
  }
  // Original expiry/obsolete session still reaches authority rejection; RESULT_PENDING always drains.
  e.live=new LiveValidationSnapshot(e.live.readSet(),e.live.loadedChunks(),e.live.health(),current,e.token.generation());
  var result=e.runner.poll(e.tx,e.live);e.lastStage=result.stage();
  if(result.stage()==EventRunner.Stage.REJECTED||result.stage()==EventRunner.Stage.RECOVERY_REQUIRED)
   throw new IllegalStateException("fauna native slice "+result.stage()+" "+result.failure());
  if(result.stage()!=EventRunner.Stage.COMMITTED)return false;
  e.committed=true;var prepare=prepares.values().stream().filter(p->p.transactionId().equals(e.tx)).findFirst().orElseThrow();
  results.add(prepare.operationToken());return true;
 }
 private void release(Exec e){if(e==null||e.released)return;e.arbiter.release(e.token);e.released=true;}
 private void release(Slot s){s.held.forEach(this::release);}
 /** Actor-only release of ephemeral controls; durable slots and uncertain UUIDs are preserved. */
 private void releaseTransientControls(Flight f){
  for(var slot:f.slots)try{
   if(slot.navigator!=null)slot.navigator.releaseAllNavigation();
   if(slot.preparationOwner!=null){
    var entity=level(slot.preparationOwner.lastKnownChunk().dimension()).getEntity(slot.preparationOwner.entityUuid());
    if(entity instanceof Animal animal&&expected(animal,species(slot)))
     FaunaSpawnBinding.releasePreparationHold(animal,slot.preparationOwner);
   }
  }catch(RuntimeException cleanupFailure){
   System.out.println("DIRECTOR_FAUNA_TRANSIENT_CONTROL_RELEASE_FAILED entity="+slot.owner.entityUuid()+" reason="+cleanupFailure);
  }
 }
 private int inflight(Flight f){return (int)f.slots.stream().filter(s->s.exec!=null&&!s.exec.committed&&!s.exec.released).count();}
 private List<BlockPosition> remaining(Slot s,Animal a){
  var corridor=s.route;int nearest=-1;double best=Double.POSITIVE_INFINITY;
  for(int index=0;index<corridor.size()-1;index++){
   var p=corridor.get(index);double distance=a.distanceToSqr(p.x()+.5,p.y(),p.z()+.5);
   if(distance<best){best=distance;nearest=index;}
  }
  if(nearest<0||best>2.25)throw new IllegalStateException("animal outside captured corridor; no invented continuation");
  var suffix=List.copyOf(corridor.subList(nearest,corridor.size()));
  if(!NativeFaunaHabitat.qualified(level(s.owner.lastKnownChunk().dimension()),suffix,suffix.getFirst(),false,species(s)))throw new IllegalStateException("captured remaining corridor unavailable");
  return suffix;
 }
 private EntityOwnershipRecord permanent(EntityOwnershipRecord observed){return new EntityOwnershipRecord(observed.header(),observed.ownerToken(),observed.entityUuid(),observed.instanceId(),observed.cohortId(),observed.slotId(),EntityPhase.SETTLED,OwnershipDisposition.DIRECTOR_DURABLE,observed.lastKnownChunk(),observed.lastSeenTick(),observed.persistMode(),observed.cleanupPolicy(),observed.adoptedBy(),observed.generation().plus(1));}
 private void metadata(Flight f,Slot s,EntityOwnershipRecord next,String reason,boolean unused){metadata(f,s,next,reason,unused,List.of());}
 private void metadata(Flight f,Slot s,EntityOwnershipRecord next,String reason,boolean unused,List<DurableRecord> reconciled){
  s.pendingOwner=next;s.unused=unused;var records=new ArrayList<DurableRecord>();records.add(new DurableAggregate(new EntityOwnershipAggregate(next)));
  if(next.disposition()==OwnershipDisposition.DIRECTOR_DURABLE)records.add(new DurableFact(fact(ARRIVAL,f,Map.of("entity",new JsonValue.Text(next.entityUuid().toString()),"tick",new JsonValue.Text(Long.toString(now().value())),"owner",new JsonValue.Text(encode(OWNER.encode(next)))))));
  else records.add(new DurableFact(fact(new FactType("proutlost_director:lab_fauna_metadata"),f,Map.of("entity",new JsonValue.Text(next.entityUuid().toString()),"reason",new JsonValue.Text(reason)))));
  records.addAll(reconciled);s.ack=append(records);s.phase=Phase.METADATA;
 }
 private void prepareNavigation(Flight f,Slot s){
  if(!freshSelection(f))return;
  var a=ownedAnimal(s);s.route=remaining(s,a);var current=new ChunkRef(s.owner.lastKnownChunk().dimension(),a.chunkPosition().x,a.chunkPosition().z);
  if(!current.equals(s.owner.lastKnownChunk())){metadata(f,s,EntityCohorts.loaded(s.owner,a.getUUID(),s.owner.ownerToken(),s.owner.generation(),current,now()),"OBSERVED_CURRENT_CHUNK",false);return;}
  s.navigator=new FaunaNavigationBinding(this::level,i->new FaunaNavigationBinding.Route(s.owner,s.route,.8),NativeFaunaHabitat.usesBrain(species(s))?Set.of(species(s)):Set.of());
  var proposed=proposedNavigation.get(s.owner.entityUuid());var intent=new EffectIntent(proposed==null?new OperationToken(UUID.randomUUID()):proposed.operationToken(),NAV,1,unit(migration(f)),footprint(s.route),new EventInstanceId(f.id));
  try{s.captured=s.navigator.capture(intent,faunaSnapshot(f,s.route));}
  catch(SelectedEventStart.ContextUnavailable notReady){return;}
  proposedNavigation.remove(s.owner.entityUuid());
  s.navPlan=s.captured.prepared().orElseThrow();s.navBlobs=s.captured.blobs();routes.put(s.owner.entityUuid(),s.navPlan);
  s.ack=append(List.of(new DurableFact(fact(ROUTE,f,Map.of("entity",new JsonValue.Text(s.owner.entityUuid().toString()),"tick",new JsonValue.Text(Long.toString(now().value())),"plan",NativeCatalogueColdCompletion.encodedBytes(PLAN.encode(s.navPlan)))))));s.phase=Phase.NAV_SEAL;
 }
 private PlanningSnapshot removalContext(Footprint fp){var context=new ObservationContext(Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),List.of(),Map.of());return new PlanningSnapshot(new SnapshotBatch(new RequestId(UUID.randomUUID()),new ScopeSet(world,Set.of()),now(),Map.of(),context,Set.of(),fp.loadedChunks(),HealthMode.NORMAL),new OwnershipView(Map.of()),new BudgetView(CAP,BudgetMath.zero(),Map.of()),new BlobBundle(List.of()));}
 private void remove(Flight f,Slot s){
  if(s.navigator!=null)s.navigator.releaseNavigation(s.owner.entityUuid());
  var a=ownedAnimal(s);var b=a.blockPosition();var p=new BlockPosition(s.owner.lastKnownChunk().dimension(),b.getX(),b.getY(),b.getZ());var fp=footprint(List.of(p));var binding=new FaunaRemoveBinding(this::level,i->s.owner,next->{s.owner=next;rows.put(next.entityUuid(),next);},Set.of(species(s)));
  var intent=new EffectIntent(new OperationToken(UUID.randomUUID()),FaunaRemoveBinding.REMOVE,1,unit(migration(f)),fp,new EventInstanceId(f.id));s.exec=run(f,s,binding.capture(intent,removalContext(fp)),binding);s.phase=Phase.REMOVING;
 }
 private EntityOwnershipRecord unused(EntityOwnershipRecord o){var h=o.header();return new EntityOwnershipRecord(new AggregateHeader(h.schemaVersion(),h.id(),h.revision().plus(1),h.lastSequence(),now()),o.ownerToken(),o.entityUuid(),o.instanceId(),o.cohortId(),o.slotId(),EntityPhase.REMOVED,OwnershipDisposition.RELEASED,o.lastKnownChunk(),now(),o.persistMode(),o.cleanupPolicy(),Optional.empty(),o.generation().plus(1));}
 private void completeMetadata(Slot s){
  var next=s.pendingOwner;if(!s.unused){var entity=level(next.lastKnownChunk().dimension()).getEntity(next.entityUuid());if(!(entity instanceof Animal a)||!expected(a,species(s))||!a.isAlive()||a.isLeashed()||a.isPassenger()||a.isVehicle())throw new IllegalStateException("sheep changed before metadata CAS");var local=EntityDirectorAttachment.read(a);
   if(local.equals(Optional.of(s.owner)))EntityDirectorAttachment.compareAndSet(a,s.owner,next);else if(!local.equals(Optional.of(next)))throw new IllegalStateException("metadata owner changed");}
  if(next.disposition()==OwnershipDisposition.DIRECTOR_DURABLE&&s.navigator!=null)s.navigator.releaseNavigation(next.entityUuid());
  s.owner=next;rows.put(next.entityUuid(),next);s.pendingOwner=null;s.ack=null;
  if(s.reconciledOperation!=null){results.add(s.reconciledOperation);s.reconciledOperation=null;}
  if(next.phase()==EntityPhase.REMOVED){expectedSpecies.remove(next.entityUuid());s.phase=Phase.REMOVED;release(s);}else if(next.disposition()==OwnershipDisposition.DIRECTOR_DURABLE){arrivals.add(next.entityUuid());s.phase=Phase.SETTLED;release(s);}else s.phase=Phase.READY;
 }
 private void advance(Flight f,Slot s){
  switch(s.phase){
   case READY->{
    if(s.owner.phase()==EntityPhase.REMOVED){s.phase=Phase.REMOVED;return;}
    if(s.owner.disposition()==OwnershipDisposition.DIRECTOR_DURABLE){s.phase=Phase.SETTLED;return;}
    if(s.owner.phase()==EntityPhase.RESERVED){
     if(f.stopping){if(prepares.containsKey(s.spawn.operationToken())){s.phase=Phase.RECOVER;return;}metadata(f,s,unused(s.owner),"UNUSED_RESERVATION_NO_SPAWN",true);return;}
     if(!freshSelection(f)||inflight(f)>=4||!NativeFaunaHabitat.qualified(level(s.owner.lastKnownChunk().dimension()),s.route,s.route.getFirst(),true,species(s)))return;
     var binding=spawner(s);EffectPreparation captured;
     try{captured=binding.capture(s.spawn,faunaSnapshot(f,s.route));}
     catch(SelectedEventStart.ContextUnavailable notReady){return;}
     s.exec=run(f,s,captured,binding);s.phase=Phase.SPAWNING;
    }else if(inflight(f)<4){if(f.stopping)remove(f,s);else prepareNavigation(f,s);}
   }
   case SPAWNING->{if(applySpawn(f,s)){spawnedIds.add(s.owner.entityUuid());if(f.token!=null&&startedCohorts.add(f.id))f.arbiter.markStarted(f.token,now());s.phase=Phase.READY;System.out.println("DIRECTOR_LAB_FAUNA_REAL_SPAWN "+s.owner.entityUuid());}}
   case NAV_SEAL->{
    if(durable(s.ack)&&inflight(f)<4){
     if(f.stopping){s.phase=Phase.READY;return;}
     if(!freshSelection(f)||!s.navigator.revalidate(s.navPlan,s.navBlobs))return;
     // ROUTE seals the exact physical lineage. Its scope revisions may age while its
     // metadata ACK is pending; PREPARE must use a freshly observed CAS read set.
     EffectPreparation fresh;
     try{fresh=s.navigator.capture(s.navPlan.intent(),faunaSnapshot(f,s.route));}
     catch(SelectedEventStart.ContextUnavailable notReady){return;}
     var next=fresh.prepared().orElseThrow();var sealed=s.navPlan;
     if(!next.intent().equals(sealed.intent())||!next.claims().equals(sealed.claims())
        ||!next.expectedPost().equals(sealed.expectedPost())||!next.budgetEstimate().equals(sealed.budgetEstimate())
        ||!next.inversePlan().equals(sealed.inversePlan())||!next.capabilities().equals(sealed.capabilities())
        ||!next.durableBlobs().equals(sealed.durableBlobs())
        ||!next.preconditions().epochs().equals(sealed.preconditions().epochs())
        ||!next.preconditions().fingerprints().equals(sealed.preconditions().fingerprints())
        ||!fresh.blobs().contents().stream().map(BlobContent::reference).collect(java.util.stream.Collectors.toSet())
            .equals(s.navBlobs.contents().stream().map(BlobContent::reference).collect(java.util.stream.Collectors.toSet())))
      throw new IllegalStateException("sealed native navigation physical lineage changed");
     if(!next.preconditions().revisions().equals(sealed.preconditions().revisions()))
      System.out.println("DIRECTOR_LAB_FAUNA_NAV_CONTEXT_REFRESHED event="+f.id+" operation="+next.intent().operationToken().value()
       +" old_revisions="+sealed.preconditions().revisions()+" new_revisions="+next.preconditions().revisions()+" same_physical_route=true");
     s.captured=fresh;s.navPlan=next;s.navBlobs=fresh.blobs();routes.put(s.owner.entityUuid(),next);
     s.exec=run(f,s,fresh,s.navigator);s.phase=Phase.NAVIGATING;
    }
   }
   case NAVIGATING->{if(applied(s.exec)){s.phase=Phase.MOVING;System.out.println("DIRECTOR_LAB_FAUNA_REAL_NAVIGATION_STARTED "+s.owner.entityUuid());}}
   case MOVING->{
    if(f.stopping){if(inflight(f)<4)remove(f,s);return;}
    ownedAnimal(s);var observed=s.navigator.observeArrival(s.navPlan,s.navBlobs,now());
    if(observed.isPresent())metadata(f,s,permanent(observed.orElseThrow()),"ACTUAL_ENDPOINT_OBSERVED",false);
   }
   case METADATA->{if(durable(s.ack)){completeMetadata(s);if(s.phase==Phase.SETTLED)System.out.println("DIRECTOR_LAB_FAUNA_ACTUAL_ARRIVAL_DURABLE "+s.owner.entityUuid());}}
   case REMOVING->{if(applied(s.exec)){s.phase=Phase.REMOVED;release(s);System.out.println("DIRECTOR_LAB_FAUNA_ACTIVE_OWNED_REMOVED "+s.owner.entityUuid());}}
   case RECOVER->recoverSlot(f,s);
   case SETTLED,REMOVED,WAITING->{}
  }
 }
 private boolean terminal(Slot s){return s.phase==Phase.SETTLED||s.phase==Phase.REMOVED;}
 private boolean availableStart(){return starts.keySet().stream().allMatch(heldCohorts::contains);}
 /** Exclude this cohort's actual durable rows before its one full-cohort charge. */
 private BudgetVector available(Flight f){
  return f.selection==null?available(f.id):SelectedEventAdmission.available(f.selection,store,new EventInstanceId(f.id));
 }
 private BudgetVector available(UUID current){
  long occupied=rows.values().stream().filter(o->!o.instanceId().value().equals(current)&&o.disposition()!=OwnershipDisposition.RELEASED).count();
  return new BudgetVector(CAP.tickMillis(),CAP.globalIntensity(),CAP.regionalIntensity(),Math.max(0,CAP.liveEntities()-occupied),Math.max(0,CAP.persistentEntities()-occupied),CAP.mutationsInFlight(),CAP.queuedIoBytes(),CAP.clientUnits(),CAP.payloadBytes(),CAP.navigationNodes());
 }
 private void releaseFlight(Flight f){for(var s:f.slots)release(s);if(f.token!=null){f.arbiter.release(f.token);f.admission.released(f.decision,f.token);}}
 private void park(Flight f){
  var missing=new ArrayList<JsonValue>();for(var s:f.slots)if(s.phase==Phase.WAITING)missing.add(new JsonValue.Text(s.owner.entityUuid().toString()));
  if(missing.isEmpty())throw new IllegalStateException("empty fauna waiting cohort");
  long released=f.slots.stream().filter(s->s.owner.disposition()==OwnershipDisposition.RELEASED).count();
  f.ack=append(List.of(new DurableFact(fact(WAITING,f,Map.of("missing",new JsonValue.Array(missing),"released",new JsonValue.Text(Long.toString(released)),"tick",new JsonValue.Text(Long.toString(now().value())))))));holding.add(f.id);
 }
 private void wakeLoadedHeld(){
  for(var id:new ArrayList<>(heldCohorts))if(rows.values().stream().filter(o->o.instanceId().value().equals(id)&&o.disposition()!=OwnershipDisposition.RELEASED).anyMatch(o->level(o.lastKnownChunk().dimension()).getEntity(o.entityUuid())!=null))heldCohorts.remove(id);
 }
 private boolean applySpawn(Flight f,Slot s){
  var e=s.exec;if(e.selectedForward&&e.lastStage==EventRunner.Stage.PREPARING&&!f.selection.forwardContextAvailable())return false;e.live=new LiveValidationSnapshot(e.live.readSet(),e.live.loadedChunks(),e.live.health(),now(),e.token.generation());
  var view=e.runner.poll(e.tx,e.live);e.lastStage=view.stage();
  if(view.stage()==EventRunner.Stage.REJECTED)throw new IllegalStateException("fauna spawn PREPARE authority rejected: "+view.failure());
  if(view.stage()==EventRunner.Stage.RECOVERY_REQUIRED){
   // This stage entered apply only after a GRANTED durable PREPARE. REJECTED is not that proof.
   var p=prepares.get(s.spawn.operationToken());
   if(e.spawnBinding==null||p==null||!e.spawnBinding.definitivelyNeverWritten(s.spawn.operationToken())||!s.owner.equals(s.initial)||level(s.owner.lastKnownChunk().dimension()).getEntity(s.owner.entityUuid())!=null)throw new IllegalStateException("fauna uncertain spawn "+view.failure());
   var safe=new SliceResult(Outcome.ABORTED_SAFE,Set.of(),Map.of(),0,Set.of(),Set.of(),List.of());
   s.reconciledOperation=p.operationToken();f.stopping=true;
   metadata(f,s,unused(s.owner),"CURRENT_BOOT_PROVIDER_NEVER_ATTEMPTED_ADD",true,List.of(new DurableResult(new ResultRecord(p.transactionId(),p.operationToken(),safe,Set.of(),List.of(),List.of()))));return false;
  }
  if(view.stage()!=EventRunner.Stage.COMMITTED)return false;e.committed=true;results.add(s.spawn.operationToken());return true;
 }
 private void finish(Flight f){
  var completionBlobs=new ArrayList<BlobContent>();
  Map<ScopeRef,Revision> expected=Map.of();
  long settled=f.slots.stream().filter(s->s.phase==Phase.SETTLED&&s.owner.disposition()==OwnershipDisposition.DIRECTOR_DURABLE&&arrivals.contains(s.owner.entityUuid())).count();
  var records=new ArrayList<DurableRecord>();
  for(var p:prepares.values())if(p.reservation().instanceId().value().equals(f.id)&&!results.contains(p.operationToken()))records.add(new DurableResult(new ResultRecord(p.transactionId(),p.operationToken(),new SliceResult(Outcome.DELIVERY_UNCERTAIN,Set.of(),Map.of(),0,Set.of(),Set.of(),List.of()),Set.of(),List.of(),List.of())));
  if(settled==24){
   var evidence=new TreeMap<String,JsonValue>();
   evidence.put("count",new JsonValue.Text("24"));evidence.put("arrival_policy",new JsonValue.Text("SETTLE_WORLD"));
   evidence.put("cohort",new JsonValue.Text(f.cohort.value().toString()));
   evidence.put("actual_arrival_uuids",new JsonValue.Array(f.slots.stream()
       .map(s->(JsonValue)new JsonValue.Text(s.owner.entityUuid().toString())).toList()));
   if(f.selection!=null){var contribution=f.selection.completionContribution(evidence);records.addAll(contribution.records());completionBlobs.addAll(contribution.blobs());}
   else if(f.selectedPlan!=null){
    if(f.coldIdentity!=null){
     if(f.coldRead==null)f.coldRead=new NativeCatalogueColdCompletion.Read(store,f.coldIdentity);
     var cut=f.coldRead.poll();if(cut.isEmpty())return;
     var view=cut.orElseThrow();
     var contribution=NativeCatalogueColdCompletion.completed(f.coldIdentity,view,evidence,true,
       now());
     records.addAll(contribution.records());completionBlobs.addAll(contribution.blobs());expected=view.scopeRevisions();
    }else System.out.println("DIRECTOR_FAUNA_COLD_LEGACY_CLEANUP_ONLY_NO_ORIGINAL_OUTCOME "+f.id);
   }
   else records.add(new DurableFact(fact(new FactType("proutlost:animal_migration_observed"),f,evidence)));
  }else if(f.selectedPlan!=null&&f.coldIdentity!=null){
   // A partial cohort is recovered only after all original slots are terminal.
   // Permanent transfers are preserved; every other slot must already be released.
   if(f.slots.size()!=24)throw new IllegalStateException("partial fauna recovery requires original24slots");
   for(var s:f.slots){
    if(!s.owner.equals(rows.get(s.owner.entityUuid())))throw new IllegalStateException("partial fauna WAL owner changed");
    if(s.phase==Phase.SETTLED){
     var a=level(s.owner.lastKnownChunk().dimension()).getEntity(s.owner.entityUuid());
     if(s.owner.phase()!=EntityPhase.SETTLED||s.owner.disposition()!=OwnershipDisposition.DIRECTOR_DURABLE||
       !arrivals.contains(s.owner.entityUuid())||!(a instanceof Animal sheep)||!expected(sheep,species(s))||!sheep.isAlive()||
       !EntityDirectorAttachment.read(sheep).equals(Optional.of(s.owner)))
      throw new IllegalStateException("partial fauna permanent transfer not proven");
    }else if(s.phase!=Phase.REMOVED||s.owner.phase()!=EntityPhase.REMOVED||
      s.owner.disposition()!=OwnershipDisposition.RELEASED)
     throw new IllegalStateException("partial fauna active slot remains");
   }
   if(f.coldRead==null)f.coldRead=new NativeCatalogueColdCompletion.Read(store,f.coldIdentity);
   var cut=f.coldRead.poll();if(cut.isEmpty())return;
   var view=cut.orElseThrow();expected=view.scopeRevisions();
   var evidence=new TreeMap<String,JsonValue>();
   evidence.put("definition",new JsonValue.Text(f.coldIdentity.definition().id().value()));
   evidence.put("decision",new JsonValue.Text(f.coldIdentity.decision().value().toString()));
   evidence.put("source",new JsonValue.Text("CATALOGUE_SELECTED"));
   evidence.put("reason",new JsonValue.Text("PARTIAL_COHORT_CLEANUP_COMPLETE"));
   evidence.put("recovery_complete",new JsonValue.Bool(true));
   evidence.put("no_physical_effect",new JsonValue.Bool(false));
   evidence.put("settled",new JsonValue.Text(Long.toString(settled)));
   evidence.put("released",new JsonValue.Text(Long.toString(24-settled)));
   evidence.put("cohort",new JsonValue.Text(f.cohort.value().toString()));
   evidence.put("actual_arrival_uuids",new JsonValue.Array(f.slots.stream().filter(s->s.phase==Phase.SETTLED)
     .map(s->(JsonValue)new JsonValue.Text(s.owner.entityUuid().toString())).toList()));
   f.parentChain.ifPresent(id->evidence.put("parent_chain",new JsonValue.Text(id.value().toString())));
   var id=new FactId(UUID.nameUUIDFromBytes(("catalogue-aborted-v1\0"+f.id).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
   var aborted=new DirectorFact(id,new FactType("proutlost_director:catalogue_event_aborted"),
     f.selectedPlan.scopes().scopes().stream().sorted(Comparator.comparing(ScopeRef::toString)).toList(),
     Optional.of(new EventInstanceId(f.id)),EvidenceStatus.CONFIRMED,evidence,true,Optional.empty());
   records.add(new DurableFact(aborted));
   records.add(NativeCatalogueFactOutbox.abort(aborted,f.coldIdentity.decision(),NativeCatalogueColdCompletion.clock(view,now())));
  }
  records.add(new DurableFact(fact(END,f,Map.of("settled",new JsonValue.Text(Long.toString(settled)),"reason",new JsonValue.Text(settled==24?"24_ACTUAL_ARRIVALS_TRANSFERRED":"ABORTED_NO_REPLACEMENT_SPAWNS")))));
  f.ack=store.append(ResultTransaction.batch(new TransactionId(UUID.randomUUID()),store.session().storeGeneration(),expected,records,completionBlobs)).toCompletableFuture();ending.add(f.id);
 }
 public String cancel(ServerPlayer player){actor();if(!player.hasPermissions(4))return "OP4 requis.";if(flight==null)return "Aucune migration LAB en cours.";flight.stopping=true;return "Annulation : nettoyage des seuls moutons encore Director ACTIVE ; transferts permanents conserves.";}
 public boolean controlRecoveryReady(){actor();return !failed&&scanned&&!store.readOnlySafe();}
 /** Canonical admin ingress after its exact durable intent; completion observes this same physical flight. */
 public Optional<java.util.function.Supplier<AdminStatus>> requestStop(Optional<EventInstanceId> target){
  actor();var f=flight;if(f==null||target.filter(id->!id.value().equals(f.id)).isPresent())return Optional.empty();
  var event=f.id;f.stopping=true;
  return Optional.of(()->{actor();return ends.contains(event)?AdminStatus.SUCCEEDED:failed?AdminStatus.FAILED_SAFE:AdminStatus.PENDING;});
 }
 public void poll(){
  actor();
  if(failed){
   if(closed||!scanned||flight==null||flight.recovery||store.readOnlySafe())return;
   var f=flight;
   if(now().value()%200==0)for(var s:f.slots)
    if(s.phase==Phase.NAVIGATING&&s.navigator!=null&&s.navPlan!=null&&s.navBlobs!=null){
     System.out.println("DIRECTOR_FAUNA_NAV_CURRENT_READ_ONLY_BEGIN event="+f.id+" entity="+s.owner.entityUuid());
     try{
      var observed=ownedAnimal(s);
      System.out.println("DIRECTOR_FAUNA_NAV_CURRENT_READ_ONLY_ENTITY entity="+observed.getUUID()+" position="+observed.position()+" health="+observed.getHealth());
      System.out.println("DIRECTOR_FAUNA_NAV_CURRENT_READ_ONLY_RESULT valid="+s.navigator.revalidate(s.navPlan,s.navBlobs));
     }
     catch(RuntimeException unavailable){System.out.println("DIRECTOR_FAUNA_NAV_CURRENT_READ_ONLY_UNAVAILABLE "+unavailable);}
    }
   try{for(var s:f.slots){
    var e=s.exec;if(e==null||e.spawnBinding==null)continue;
    var p=prepares.get(s.spawn.operationToken());
    if(p==null||!p.transactionId().equals(e.tx)||!p.operationToken().equals(s.spawn.operationToken())||!p.reservation().equals(e.token)||!p.reservation().instanceId().value().equals(f.id)||results.contains(p.operationToken()))continue;
    if(e.lastStage!=EventRunner.Stage.PREPARING||!e.spawnBinding.definitivelyNeverWritten(p.operationToken())||!s.owner.equals(s.initial)||s.owner.phase()!=EntityPhase.RESERVED||s.owner.disposition()!=OwnershipDisposition.DIRECTOR_ACTIVE||level(s.owner.lastKnownChunk().dimension()).getEntity(s.owner.entityUuid())!=null)continue;
    if(s.phase==Phase.METADATA&&s.unused&&p.operationToken().equals(s.reconciledOperation)){
     if(!durable(s.ack))return;
     completeMetadata(s);release(s);
     System.out.println("DIRECTOR_LAB_FAUNA_FAILED_SPAWN_NEVER_WRITTEN_ABORT_DURABLE "+f.id+" entity="+s.owner.entityUuid());
     return;
    }
    if(s.phase!=Phase.SPAWNING)continue;
    var safe=new SliceResult(Outcome.ABORTED_SAFE,Set.of(),Map.of(),0,Set.of(),Set.of(),List.of());
    s.reconciledOperation=p.operationToken();f.stopping=true;
    metadata(f,s,unused(s.owner),"CURRENT_BOOT_PROVIDER_NEVER_ATTEMPTED_ADD",true,List.of(new DurableResult(new ResultRecord(p.transactionId(),p.operationToken(),safe,Set.of(),List.of(),List.of()))));
    return;
   }}catch(RuntimeException blocked){System.out.println("DIRECTOR_LAB_FAUNA_FAILED_SPAWN_ABORT_BLOCKED "+blocked);}
   return;
  }
  try{
   if(store.readOnlySafe())throw new IllegalStateException("fauna WAL unavailable");
   if(!scanned){if(!shared)scan();return;}
   if(flight==null){wakeLoadedHeld();recover();return;}var f=flight;
   if(holding.contains(f.id)){
    if(!durable(f.ack))return;releaseFlight(f);heldCohorts.add(f.id);holding.remove(f.id);
    System.out.println("DIRECTOR_LAB_FAUNA_WAITING_MISSING_ENTITIES_DURABLE "+f.id);flight=null;recoveryBlobs=null;return;
   }
   if(ending.contains(f.id)){
    if(!durable(f.ack))return;for(var s:f.slots)release(s);
    if(f.token!=null){f.arbiter.release(f.token);f.admission.released(f.decision,f.token);}
    starts.remove(f.id);ends.add(f.id);ending.remove(f.id);startedCohorts.remove(f.id);heldCohorts.remove(f.id);holding.remove(f.id);
    prepares.values().removeIf(p->p.reservation().instanceId().value().equals(f.id));for(var s:f.slots){routes.remove(s.owner.entityUuid());proposedNavigation.remove(s.owner.entityUuid());}
    System.out.println("DIRECTOR_LAB_FAUNA_END_DURABLE "+f.id);flight=null;recoveryBlobs=null;return;
   }
   if(f.recovery&&recoveryBlobs==null){
    if(!f.blobs.isDone())return;var read=f.blobs.getNow(null);if(read==null||read.status()!=AckStatus.DURABLE)throw new IllegalStateException("fauna recovery blobs unavailable");recoveryBlobs=new BlobBundle(read.contents());
   }
   if(!f.recovery&&!f.admitted){
    if(!durable(f.ack))return;for(var s:f.slots)rows.put(s.owner.entityUuid(),s.owner);
    if(!f.stopping&&witness(f)&&!freshSelection(f)){
     if(now().value()<f.deadline)return;f.stopping=true;
    }
    if(f.stopping||!witness(f)){f.stopping=true;f.admitted=true;}else try{admit(f);}catch(RuntimeException rejected){f.stopping=true;f.admitted=true;System.out.println("DIRECTOR_LAB_FAUNA_ADMISSION_CANCELLED "+rejected);}
   }
   if(!witness(f)||now().value()>=f.deadline)f.stopping=true;
   // A slot retains its place through every asynchronous J04 phase, including route/arrival ACKs.
   // Four EventRunners alone did not bound the 24 concurrent NAV_SEAL transactions.
   // Keep newborns held until every original birth has its durable RESULT.
   boolean birthsPending=false;
   if(!f.recovery&&!f.stopping)for(var birth:f.slots)
    if(birth.owner.phase()==EntityPhase.RESERVED||birth.phase==Phase.SPAWNING){birthsPending=true;break;}
   int activeSlots=0;
   for(var s:f.slots){
    if(terminal(s)||s.phase==Phase.WAITING)continue;
    if(birthsPending&&!f.stopping&&s.owner.phase()!=EntityPhase.RESERVED&&s.phase!=Phase.SPAWNING)continue;
    if(activeSlots++>=4)break;
    advance(f,s);
   }
   if(f.slots.stream().allMatch(this::terminal))finish(f);
   else if(f.slots.stream().allMatch(s->terminal(s)||s.phase==Phase.WAITING))park(f);
  }catch(RuntimeException failure){failed=true;if(flight!=null)releaseTransientControls(flight);System.out.println("DIRECTOR_LAB_FAUNA_RECOVERY_REQUIRED "+failure);failure.printStackTrace();}
 }
 private BlobContent content(BlobRef ref){return recoveryBlobs.contents().stream().filter(b->b.reference().equals(ref)).findFirst().orElseThrow(()->new IllegalStateException("missing exact fauna blob"));}
 private BlobBundle subset(EffectBlobs refs){return new BlobBundle(CommitPermit.Authority.references(refs).stream().map(this::content).toList());}
 private void recoverSlot(Flight f,Slot s){
  var level=level(s.owner.lastKnownChunk().dimension());var entity=level.getEntity(s.owner.entityUuid());
  if(s.owner.phase()==EntityPhase.REMOVED){if(entity!=null)throw new IllegalStateException("removed UUID physically present; manual reconcile");s.phase=Phase.REMOVED;return;}
  if(s.owner.phase()==EntityPhase.RESERVED){
   var p=prepares.get(s.spawn.operationToken());
   if(p==null){if(entity!=null)throw new IllegalStateException("unprepared spawn UUID occupied");f.stopping=true;s.phase=Phase.READY;return;}
   if(entity==null){s.phase=Phase.WAITING;f.stopping=true;return;}
   if(!(entity instanceof Animal a)||!expected(a,species(s))||!a.isAlive())throw new IllegalStateException("uncertain spawn UUID changed");
   var k=key(s.owner);var pre=p.durableBlobs().preStates().get(k);var post=p.durableBlobs().expectedPostStates().get(k);
   if(pre==null||post==null)throw new IllegalStateException("uncertain spawn lacks PRE/POST");
   var before=OWNER.decode(content(pre).bytes());var after=OWNER.decode(content(post).bytes());
   if(!before.equals(s.owner)||!same(before,after)||after.phase()!=EntityPhase.PRESENT||after.disposition()!=OwnershipDisposition.DIRECTOR_ACTIVE||after.header().revision().value()!=before.header().revision().value()+1||!after.generation().equals(before.generation())||!EntityDirectorAttachment.read(a).equals(Optional.of(after))||!p.readSet().fingerprints().get(k).equals(pre.hash()))throw new IllegalStateException("spawn POST not proven");
   if(results.contains(p.operationToken()))throw new IllegalStateException("spawn RESULT exists without its atomic owner aggregate");
   // The exact saved live POST proves reconciliation; it does not rewrite history as SUCCEEDED.
   var reconciled=new SliceResult(Outcome.RECONCILED,Set.of(p.operationToken()),Map.of(k,post.hash()),0,Set.of(),Set.of(),List.of());
   s.reconciledOperation=p.operationToken();spawnedIds.add(after.entityUuid());
   metadata(f,s,after,"EXACT_LIVE_DURABLE_SPAWN_POST",false,List.of(new DurableResult(new ResultRecord(p.transactionId(),p.operationToken(),reconciled,Set.of(),List.of(),List.of()))));return;
  }
  if(entity==null){s.phase=Phase.WAITING;f.stopping=true;return;}
  if(!(entity instanceof Animal a)||!expected(a,species(s))||!a.isAlive()||a.isLeashed()||a.isPassenger()||a.isVehicle())throw new IllegalStateException("recovery sheep changed/adopted");
  var local=EntityDirectorAttachment.read(a);
  if(s.owner.disposition()==OwnershipDisposition.DIRECTOR_DURABLE){
   if(!arrivals.contains(s.owner.entityUuid()))throw new IllegalStateException("durable transfer without actual arrival fact");
   if(!local.equals(Optional.of(s.owner))){var old=local.orElseThrow();if(!same(old,s.owner)||old.header().revision().value()+1!=s.owner.header().revision().value()||old.generation().value()+1!=s.owner.generation().value())throw new IllegalStateException("settlement metadata lineage mismatch");EntityDirectorAttachment.compareAndSet(a,old,s.owner);}
   s.phase=Phase.SETTLED;return;
  }
  if(!local.equals(Optional.of(s.owner)))throw new IllegalStateException("native/WAL fauna owner mismatch");
  if(f.stopping){s.phase=Phase.READY;return;}
  var plan=routes.get(s.owner.entityUuid());
  if(plan!=null&&prepares.containsKey(plan.intent().operationToken())){
   s.navPlan=plan;s.navBlobs=subset(plan.durableBlobs());var decoded=PATH.decode(content(plan.durableBlobs().preparedPlan()).bytes());
   s.route=decoded.cells();
   s.navigator=new FaunaNavigationBinding(this::level,i->new FaunaNavigationBinding.Route(s.owner,decoded.cells(),decoded.speed()),NativeFaunaHabitat.usesBrain(species(s))?Set.of(species(s)):Set.of());
   var arrived=s.navigator.observeArrival(plan,s.navBlobs,now());if(arrived.isPresent()){metadata(f,s,permanent(arrived.orElseThrow()),"RECOVERY_ACTUAL_ENDPOINT_OBSERVED",false);return;}
  }
  s.phase=Phase.READY;
 }
 public void useSharedRecoveryScan(){actor();shared=true;resetRecoveryScan();}
 public void resetRecoveryScan(){actor();scanned=false;pendingRead=null;cursor=Optional.empty();rows.clear();expectedSpecies.clear();starts.clear();ends.clear();heldCohorts.clear();holding.clear();arrivals.clear();spawnedIds.clear();routes.clear();prepares.clear();results.clear();proposedNavigation.clear();floor=0;clockFloor=0;offset=0;lastEvent=null;}
 public void acceptRecoveryRows(List<DurableRecord> records,boolean last){
  actor();
  for(var record:records){
   if(record instanceof DurableAggregate a){
    clockFloor=Math.max(clockFloor,a.state().header().updatedTick().value());
    if(a.state() instanceof EntityOwnershipAggregate entity){
     var value=entity.value();var old=rows.get(value.entityUuid());
     if(old!=null&&!old.header().id().equals(value.header().id()))throw new IllegalStateException("duplicate entity UUID aggregate");
     if(old==null||value.header().revision().compareTo(old.header().revision())>0)rows.put(value.entityUuid(),value);if(value.phase()==EntityPhase.REMOVED)expectedSpecies.remove(value.entityUuid());
    }
   }
   if(record instanceof DurablePrepare d){
    var p=d.value();floor=Math.max(floor,p.reservationGeneration().value());var operations=p.slice().operations();
    if(operations.size()==1&&Set.of(SPAWN,NAV,FaunaRemoveBinding.REMOVE).contains(operations.getFirst().primitive()))prepares.put(p.operationToken(),p);
   }
   if(record instanceof DurableResult d){
    results.add(d.value().operationToken());var p=prepares.get(d.value().operationToken());
    if(p!=null&&p.slice().operations().getFirst().primitive().equals(SPAWN)&&Set.of(Outcome.SUCCEEDED,Outcome.RECONCILED).contains(d.value().result().outcome()))for(var k:p.readSet().epochs().keySet())spawnedIds.add(UUID.fromString(k.key()));
   }
   if(record instanceof DurableFact d&&d.value().eventId().isPresent()){
    var f=d.value();var id=f.eventId().orElseThrow().value();
    if(f.factType().equals(START)){
     var species=recordedSpecies(f);var paths=((JsonValue.Array)f.payload().get("paths")).values();
     if(paths.size()!=24)throw new IllegalStateException("fauna species receipt slot bound");
     for(var encoded:paths){var savedPath=PATH.decode(Base64.getDecoder().decode(((JsonValue.Text)encoded).value()));expectedSpecies.put(savedPath.owner().entityUuid(),species);}
     starts.put(id,f);lastEvent=id;}
    else if(f.factType().equals(END)){ends.add(id);heldCohorts.remove(id);lastEvent=id;}
    else if(f.factType().equals(WAITING))heldCohorts.add(id);
    else if(f.factType().equals(ARRIVAL))arrivals.add(UUID.fromString(text(f,"entity")));
    else if(f.factType().equals(ROUTE))routes.put(UUID.fromString(text(f,"entity")),PLAN.decode(decode(f,"plan")));
    if(Set.of(START,ROUTE,ARRIVAL,WAITING).contains(f.factType())&&f.payload().get("tick") instanceof JsonValue.Text t)clockFloor=Math.max(clockFloor,Long.parseLong(t.value()));
   }
  }
  if(last){
   for(var id:ends)if(prepares.values().stream().noneMatch(p->p.reservation().instanceId().value().equals(id)&&!results.contains(p.operationToken())))starts.remove(id);
   prepares.values().removeIf(p->!starts.containsKey(p.reservation().instanceId().value()));
   routes.entrySet().removeIf(e->!starts.containsKey(e.getValue().intent().instanceId().value()));
   heldCohorts.retainAll(starts.keySet());
   offset=Math.max(0,clockFloor-server.overworld().getGameTime());scanned=true;
   System.out.println("DIRECTOR_LAB_FAUNA_RECOVERY_SCAN pending="+starts.size());
  }
 }
 private void scan(){
  if(pendingRead==null){var query=new StoreQuery(world,Set.of(),new Sequence(0),cursor,128);var resident=store.view(query);pendingRead=resident.status()==ReadStatus.NOT_RESIDENT?store.read(query).toCompletableFuture():CompletableFuture.completedFuture(resident);return;}
  if(!pendingRead.isDone())return;var read=pendingRead.getNow(null);pendingRead=null;
  if(read.status()==ReadStatus.STALE){resetRecoveryScan();return;}
  if(read.status()!=ReadStatus.AVAILABLE)throw new IllegalStateException("fauna recovery read "+read.status());
  var page=read.page().orElseThrow();acceptRecoveryRows(page.records(),!page.hasMore());cursor=page.nextCursor();
 }
 private void recover(){
  var entry=starts.entrySet().stream().filter(e->!heldCohorts.contains(e.getKey())).findFirst().orElse(null);
  if(entry==null)return;var saved=entry.getValue();var f=new Flight();
  f.id=entry.getKey();f.pinnedSpecies=recordedSpecies(saved);f.testOnly=saved.payload().get("testOnly") instanceof JsonValue.Text t&&Boolean.parseBoolean(t.value());f.witness=f.testOnly?null:UUID.fromString(text(saved,"witness"));f.cohort=new CohortId(UUID.fromString(text(saved,"cohort")));f.deadline=Long.parseLong(text(saved,"deadline"));f.recovery=true;f.admitted=true;
  var selectedPlan=saved.payload().get("selected_plan");
  if(selectedPlan!=null){
   f.selectedPlan=new ModelCodec<>(ExecutionPlan.class).decode(NativeCatalogueColdCompletion.decodedBytes(selectedPlan));
   f.selectedRegion=new RegionId(UUID.fromString(text(saved,"selected_region")));
   f.selectedDecision=new DecisionId(UUID.fromString(text(saved,"selected_decision")));
   if(saved.payload().get("parent_chain") instanceof JsonValue.Text parent)
    f.parentChain=Optional.of(new ChainId(UUID.fromString(parent.value())));
   f.coldIdentity=NativeCatalogueColdCompletion.restore(saved).orElse(null);
   if(f.coldIdentity!=null&&(!f.coldIdentity.plan().equals(f.selectedPlan)||!f.coldIdentity.decision().equals(f.selectedDecision)||!f.coldIdentity.parentChain().equals(f.parentChain)))
    throw new IllegalStateException("selected fauna cold pin mismatch");
   if(!f.selectedPlan.effects().getFirst().instanceId().value().equals(f.id)
       ||!f.selectedPlan.definition().id().equals(definition().id()))
    throw new IllegalStateException("selected fauna WAL lineage mismatch");
  }
  var encoded=((JsonValue.Array)saved.payload().get("paths")).values();
  if(encoded.size()!=24)throw new IllegalStateException("recovery requires original24slots");
  for(var value:encoded){
   var path=PATH.decode(Base64.getDecoder().decode(((JsonValue.Text)value).value()));var s=new Slot();s.initial=path.owner();s.route=path.cells();s.owner=Objects.requireNonNull(rows.get(s.initial.entityUuid()),"fauna WAL slot missing");
   if(!same(s.initial,s.owner)||!s.owner.instanceId().value().equals(f.id)||!s.owner.cohortId().equals(f.cohort))throw new IllegalStateException("fauna recovery identity mismatch");
   var p=s.route.getFirst();String identity="director-spawn-v1\0"+world.value()+"\0"+f.id+"\0"+migration(f).species().value()+"\0"+p.dimension().value()+"\0"+p.x()+","+p.y()+","+p.z();
   var operation=new OperationToken(UUID.nameUUIDFromBytes(identity.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
   s.spawn=new EffectIntent(operation,SPAWN,1,unit(migration(f)),footprint(List.of(p)),new EventInstanceId(f.id));s.phase=Phase.RECOVER;f.slots.add(s);
  }
  // A never-submitted original reservation is canceled before any recovered transfer begins.
  if(f.slots.stream().anyMatch(s->s.owner.phase()==EntityPhase.RESERVED&&!prepares.containsKey(s.spawn.operationToken())))f.stopping=true;
  var refs=new HashSet<BlobRef>();for(var p:prepares.values())if(p.reservation().instanceId().value().equals(f.id))refs.addAll(CommitPermit.Authority.references(p.durableBlobs()));
  if(refs.size()>256)throw new IllegalStateException("fauna recovery blob batch bound");
  f.blobs=refs.isEmpty()?CompletableFuture.completedFuture(new BlobReadResult(AckStatus.DURABLE,List.of(),List.of())):store.readBlobs(new BlobReadRequest(world,store.session().storeGeneration(),refs)).toCompletableFuture();
  flight=f;lastEvent=f.id;System.out.println("DIRECTOR_LAB_FAUNA_RECOVERY_NO_REPLACEMENT_SPAWNS "+f.id);
 }
 public LabStatus labStatus(){
  actor();var id=flight==null?lastEvent:flight.id;int spawned=0,arrived=0,settled=0,live=0,removed=0;
  if(id!=null)for(var row:rows.values())if(row.instanceId().value().equals(id)){
   if(spawnedIds.contains(row.entityUuid()))spawned++;if(arrivals.contains(row.entityUuid()))arrived++;if(row.phase()==EntityPhase.REMOVED)removed++;
   var entity=level(row.lastKnownChunk().dimension()).getEntity(row.entityUuid());
   if(entity instanceof Animal a&&expected(a,expectedSpecies.get(row.entityUuid()))&&a.isAlive()){live++;if(row.disposition()==OwnershipDisposition.DIRECTOR_DURABLE&&arrivals.contains(row.entityUuid())&&EntityDirectorAttachment.read(a).equals(Optional.of(row)))settled++;}
  }
  return new LabStatus(id,!scanned&&!failed,failed,flight!=null,spawned,arrived,settled,live,removed,id!=null&&ends.contains(id));
 }
 public boolean recovering(){actor();return !scanned&&!failed;}
 public boolean busy(){actor();return !scanned||failed||flight!=null||!availableStart();}
 public String status(){var s=labStatus();return s.failed()?"FAUNA_RECOVERY_REQUIRED":s.recovering()?"FAUNA_RECOVERING":"FAUNA "+(s.active()?"ACTIVE":"IDLE")+" spawned="+s.spawned()+" arrived="+s.arrived()+" settled="+s.settled()+" live="+s.live()+" ended="+s.ended()+" waiting_cohorts="+heldCohorts.size();}
 @Override public void close(){actor();if(flight!=null)releaseTransientControls(flight);closed=true;}
}