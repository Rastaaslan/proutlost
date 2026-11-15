package fr.proutlost.director.bootstrap;
import fr.proutlost.director.adapter.neoforge.server.effects.*;
import fr.proutlost.director.adapter.neoforge.server.ownership.*;
import fr.proutlost.director.api.codec.ModelCodec;
import fr.proutlost.director.api.error.ReasonCode;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.permission.CommitPermit;
import fr.proutlost.director.persistence.*;
import fr.proutlost.director.runtime.*;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.*;

/** Manual TEST_ONLY two-cell collapse. Reboot only cleans; damage is never replayed. */
public final class DirectorNativeLandslideGameplay implements AutoCloseable {
 private static final PrimitiveId LANDSLIDE=new PrimitiveId("proutlost:landslide");
 private static final CapabilityId TERRAIN=new CapabilityId("mutation.terrain_v1");
 private static final FactType START=new FactType("proutlost_director:lab_landslide_start");
 private static final FactType END=new FactType("proutlost_director:lab_landslide_end");
 private static final ModelCodec<PreparedEffect> PLAN=new ModelCodec<>(PreparedEffect.class);
 private static final ModelCodec<OwnershipRecord> OWNER=new ModelCodec<>(OwnershipRecord.class);
 private static final ModelCodec<BlockPosition> POSITION=new ModelCodec<>(BlockPosition.class);
 private static final BudgetVector CAP=new BudgetVector(100,100,100,100,100,2048,4096,100,4096,100);
 private final MinecraftServer server;private final WorldId world;private final JournalStore store;
 private final ServerOwnershipAttachments attached;private final Map<ResourceKey,OwnershipRecord> owners;
 private final Map<ResourceKey,OwnershipRecord> journalOwners=new HashMap<>();
 private final Map<UUID,DirectorFact> active=new LinkedHashMap<>();private final Set<UUID> ended=new HashSet<>();
 private CompletableFuture<StoreReadResult> pending;private Optional<String> cursor=Optional.empty();
 private final Map<OperationToken,PrepareRecord> unmatched=new LinkedHashMap<>();
 private final Set<OperationToken> resolved=new HashSet<>();
 private long floor;private boolean scanned,closed,failed;private Flight flight;
 private static final class Execution {
  NativeWarningBinding perception;PrepareRecord perceptionPrepare;CompletableFuture<CommitAck> noSendAck;boolean noSend;
  EventRunner runner;TransactionId tx;LiveValidationSnapshot live;ResourceArbiter arbiter;
  ReservationToken token;AdmissionService admission;DecisionId decision;boolean retain,released;
 }
 private static final class Flight {
  UUID id,player;List<BlockPosition> cells;Set<BlockPosition> selected;
  EffectPreparation captured;LandslideBinding preparedBinding;boolean stop;List<RouteSegment> exits;
  Map<ResourceKey,OwnershipRecord> initial=new HashMap<>();PreparedEffect forward;
  CatalogSnapshot catalog;NativeWarningBinding warning;EffectIntent warningIntent;
  CompletableFuture<CommitAck> ack;Execution work,impact;int phase;long warned=-1,sent,expires;
 }
 public DirectorNativeLandslideGameplay(MinecraftServer server,WorldId world,JournalStore store,
     ServerOwnershipAttachments attached,Map<ResourceKey,OwnershipRecord> owners){
  this.server=server;this.world=world;this.store=store;this.attached=attached;this.owners=owners;actor();
 }
 private void actor(){if(closed||!server.isSameThread())throw new IllegalStateException("landslide actor");}
 private Tick now(){return new Tick(server.overworld().getGameTime());}
 private static BlockPos pos(BlockPosition p){return new BlockPos(p.x(),p.y(),p.z());}
 private static ResourceKey key(BlockPosition p){return new ResourceKey(OwnershipDomain.BLOCK,p.dimension(),p.x()+","+p.y()+","+p.z());}
 private ServerLevel level(BlockPosition p){for(var l:server.getAllLevels())if(l.dimension().location().toString().equals(p.dimension().value()))return l;throw new IllegalStateException("dimension");}
 private boolean loaded(BlockPosition p){return level(p).getChunkSource().getChunkNow(p.x()>>4,p.z()>>4)!=null;}
 private Fingerprint hash(BlockPosition p){return BlockTransformBinding.stateBlob(level(p).getBlockState(pos(p))).reference().hash();}
 private void publish(BlockPosition p,OwnershipRecord o){owners.put(key(p),o);attached.write(level(p),pos(p),o);}
 private CompletableFuture<CommitAck> append(List<DurableRecord> rows){return store.append(ResultTransaction.batch(new TransactionId(UUID.randomUUID()),store.session().storeGeneration(),Map.of(),rows,List.of())).toCompletableFuture();}
 private boolean durable(CompletableFuture<CommitAck> ack){if(ack==null||!ack.isDone())return false;if(ack.isCompletedExceptionally()||ack.getNow(null)==null||ack.getNow(null).ackStatus()!=AckStatus.DURABLE)throw new IllegalStateException("landslide WAL ACK");return true;}
 private DirectorFact fact(FactType type,Flight f,Map<String,JsonValue> payload){return new DirectorFact(new FactId(UUID.randomUUID()),type,List.of(new ScopeRef(ScopeKind.WORLD,world.value())),Optional.of(new EventInstanceId(f.id)),EvidenceStatus.CONFIRMED,payload,true,Optional.empty());}
 private static String encode(byte[] b){return Base64.getEncoder().encodeToString(b);}
 private static byte[] decode(DirectorFact f,String k){return Base64.getDecoder().decode(text(f,k));}
 private static String text(DirectorFact f,String k){return ((JsonValue.Text)f.payload().get(k)).value();}
 private EventDefinition definition(Flight f){return Objects.requireNonNull(f.catalog.events().get(new DefinitionId("proutlost_director:candidate/controlled_landslide")));}
 private Landslide args(Flight f){return (Landslide)f.forward.intent().typedArgs();}
 private DirectorFact dossier(Flight f){return fact(START,f,Map.of(
  "plan",new JsonValue.Text(encode(PLAN.encode(f.forward))),"player",new JsonValue.Text(f.player.toString()),
  "source",new JsonValue.Text(encode(POSITION.encode(f.cells.get(0)))),"destination",new JsonValue.Text(encode(POSITION.encode(f.cells.get(1)))),
  "o0",new JsonValue.Text(encode(OWNER.encode(f.initial.get(key(f.cells.get(0)))))),
  "o1",new JsonValue.Text(encode(OWNER.encode(f.initial.get(key(f.cells.get(1))))))));}
 private List<RouteSegment> exit(ServerPlayer player,List<BlockPosition> hazard){
  var start=player.blockPosition();var source=pos(hazard.getFirst());
  int dx=Integer.compare(start.getX()-source.getX(),0),dz=Integer.compare(start.getZ()-source.getZ(),0);
  if(Math.abs(start.getX()-source.getX())>=Math.abs(start.getZ()-source.getZ()))dz=0;else dx=0;
  if(dx==0&&dz==0)throw new IllegalArgumentException("Aucune sortie laterale observable.");
  var l=player.serverLevel();var dim=hazard.getFirst().dimension();
  for(int i=0;i<=2;i++){
   var p=start.offset(dx*i,0,dz*i);var point=new BlockPosition(dim,p.getX(),p.getY(),p.getZ());
   if(!loaded(point)||!l.getWorldBorder().isWithinBounds(p)||!l.getFluidState(p).isEmpty()||
      !l.getBlockState(p.below()).isSolidRender(l,p.below())||
      !l.noCollision(player,player.getBoundingBox().move(p.getX()+.5-player.getX(),p.getY()-player.getY(),p.getZ()+.5-player.getZ())))
    throw new IllegalArgumentException("Sortie reelle de trois cellules requise.");
  }
  var end=start.offset(dx*2,0,dz*2);
  if(hazard.stream().anyMatch(p->pos(p).distSqr(end)<9))throw new IllegalArgumentException("Sortie encore dans le danger.");
  return List.of(new RouteSegment(new BlockPosition(dim,start.getX(),start.getY(),start.getZ()),new BlockPosition(dim,end.getX(),end.getY(),end.getZ()),.5));
 }
 public String begin(ServerPlayer player){
  actor();if(!scanned||failed||store.readOnlySafe())return "Eboulement LAB : recuperation/WAL en attente.";
  if(flight!=null||!active.isEmpty())return "Eboulement LAB deja actif.";
  if(!player.hasPermissions(4)||!player.isAlive())return "OP4 vivant requis.";
  if(!(player.pick(6,0,false) instanceof BlockHitResult hit)||hit.getType()!=HitResult.Type.BLOCK||hit.getDirection().getAxis()==Direction.Axis.Y)
   return "Vise une paroi laterale de pierre a moins de six blocs.";
  var l=player.serverLevel();var s=hit.getBlockPos();var d=s.relative(hit.getDirection()).below();
  var dim=new DimensionId(l.dimension().location().toString());
  var f=new Flight();f.id=UUID.randomUUID();f.player=player.getUUID();
  f.cells=List.of(new BlockPosition(dim,s.getX(),s.getY(),s.getZ()),new BlockPosition(dim,d.getX(),d.getY(),d.getZ()));f.selected=new HashSet<>(f.cells);
  if(f.cells.stream().anyMatch(p->!loaded(p))||!l.getBlockState(s).is(Blocks.STONE)||!l.getBlockState(d).isAir()||
      !l.getBlockState(d.below()).isSolidRender(l,d.below())||!l.getWorldBorder().isWithinBounds(d)||
      !l.getEntities(null,new AABB(d)).isEmpty())return "Pierre source et cellule de depot AIR sur sol libre requises.";
  f.exits=exit(player,f.cells);
  f.catalog=new DirectorLabLandslideCatalog(DirectorLabObservationProfile.load(),f.exits).snapshot();
  var warningArgs=(Warning)definition(f).steps().stream().filter(x->x.args() instanceof Warning).findFirst().orElseThrow().args();
  f.warning=new NativeWarningBinding(server,new PlayerId(f.player),new PerceptionTransform(s.getX()+.5,s.getY()+.8,s.getZ()+.5,net.minecraft.util.Mth.wrapDegrees(180-player.getYRot()),0,2),1200,warningArgs.profile());
  for(var p:f.cells){
   var previous=attached.read(l,pos(p));if(previous.isPresent()&&previous.get().disposition()!=OwnershipDisposition.RELEASED)return "Provenance active/cedee dans les deux cellules.";
   var old=previous.orElse(null);var k=key(p);
   f.initial.put(k,new OwnershipRecord(new AggregateHeader(1,old==null?UUID.randomUUID():old.header().id(),old==null?new Revision(1):old.header().revision().plus(1),new Sequence(0),now()),k,Set.of(new OwnerToken(UUID.randomUUID())),old==null?new Epoch(1):old.epoch().plus(1),OwnershipDisposition.DIRECTOR_ACTIVE,new MutationId(UUID.randomUUID()),hash(p),old==null?new Epoch(0):old.playerOverrideEpoch(),Optional.empty()));
  }
  var impact=(Landslide)definition(f).steps().stream().filter(x->x.args() instanceof Landslide).findFirst().orElseThrow().args();
  var context=snapshot(f);var intent=new EffectIntent(new OperationToken(UUID.randomUUID()),LANDSLIDE,1,impact,context.snapshot().context().terrain().getFirst().loadedFootprint(),new EventInstanceId(f.id));
  f.forward=provider(f,false,impact).capture(intent,context).prepared().orElseThrow();
  var start=dossier(f);var rows=new ArrayList<DurableRecord>();f.initial.values().forEach(o->rows.add(new DurableOwnership(o)));rows.add(new DurableFact(start));
  f.ack=append(rows);flight=f;active.put(f.id,start);return "WARN 20 s, deplacement de pierre, degats bornes, retour apres 20 s.";
 }
 private LandslideBinding provider(Flight f,boolean inverse,Landslide a){
  var replacements=inverse?Map.of(new ResourceId("minecraft:air"),new ResourceId("minecraft:stone"),new ResourceId("minecraft:cobblestone"),new ResourceId("minecraft:air")):
   Map.of(new ResourceId("minecraft:stone"),new ResourceId("minecraft:air"),new ResourceId("minecraft:air"),new ResourceId("minecraft:cobblestone"));
  var base=new BlockTransformBinding(d->level(f.cells.getFirst()),()->new OwnershipView(owners),this::publish,this::now,
   Map.of(a.recipe(),new BlockTransformBinding.Recipe(replacements,false)),2,LANDSLIDE,Set.of(TERRAIN));
  return new LandslideBinding(base,server,new PlayerId(f.player),!inverse);
 }
 private PlanningSnapshot snapshot(Flight f){
  var view=new HashMap<>(owners);if(f.phase==0)view.putAll(f.initial);
  var chunks=new HashSet<ChunkRef>();var cells=new HashMap<BlockPosition,TerrainCellSnapshot>();
  for(var p:f.selected){var o=Objects.requireNonNull(view.get(key(p)));chunks.add(new ChunkRef(p.dimension(),p.x()>>4,p.z()>>4));
   cells.put(p,new TerrainCellSnapshot(p,hash(p),BlockTransformBinding.STATE_CODEC,Set.of(),Set.of(),false,true,o.epoch(),o.playerOverrideEpoch()));}
  var bounds=envelope(f.cells);var l=level(f.cells.getFirst());
  for(int x=bounds.minX()>>4;x<=(bounds.maxXExclusive()-1)>>4;x++)
   for(int z=bounds.minZ()>>4;z<=(bounds.maxZExclusive()-1)>>4;z++){
    if(l.getChunkSource().getChunkNow(x,z)==null)throw new IllegalStateException("hazard chunk unavailable");
    chunks.add(new ChunkRef(bounds.dimension(),x,z));
   }
  var footprint=new Footprint(f.cells.getFirst().dimension(),chunks,f.selected,List.of(bounds));
  var terrain=new TerrainSnapshot(footprint,cells,List.of(),Map.of(),new Revision(1));
  var context=new ObservationContext(Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),List.of(terrain),Map.of());
  return new PlanningSnapshot(new SnapshotBatch(new RequestId(UUID.randomUUID()),new ScopeSet(world,Set.of()),now(),Map.of(),context,Set.of(),chunks,HealthMode.NORMAL),new OwnershipView(view),new BudgetView(CAP,BudgetMath.zero(),Map.of()),new BlobBundle(List.of()));
 }
 private EffectPreparation capture(Flight f,boolean inverse,LandslideBinding binding){
  var context=snapshot(f);return binding.capture(new EffectIntent(new OperationToken(UUID.randomUUID()),LANDSLIDE,1,args(f),context.snapshot().context().terrain().getFirst().loadedFootprint(),new EventInstanceId(f.id)),context);
 }
 private boolean exitReady(Flight f){
  var l=level(f.cells.getFirst());
  for(var route:f.exits){
   int dx=Integer.compare(route.to().x()-route.from().x(),0),dz=Integer.compare(route.to().z()-route.from().z(),0);
   for(int i=0;i<=2;i++){
    var p=new BlockPos(route.from().x()+dx*i,route.from().y(),route.from().z()+dz*i);
    if(l.getChunkSource().getChunkNow(p.getX()>>4,p.getZ()>>4)==null||
      !l.getBlockState(p.below()).isSolidRender(l,p.below())||!l.getFluidState(p).isEmpty()||!l.noCollision(new AABB(p.getX()+.2,p.getY(),p.getZ()+.2,p.getX()+.8,p.getY()+1.8,p.getZ()+.8)))return false;
   }
  }
  return true;
 }
 private boolean playerReady(Flight f){var p=server.getPlayerList().getPlayer(f.player);return p!=null&&p.hasPermissions(4)&&p.isAlive()&&p.serverLevel()==level(f.cells.getFirst())&&p.distanceToSqr(f.cells.getFirst().x()+.5,f.cells.getFirst().y()+.5,f.cells.getFirst().z()+.5)<=256;}
 private Execution run(Flight f,EffectPreparation capture,ServerEffectPort.Binding binding,boolean impact){
  var plan=capture.prepared().orElseThrow();var intent=plan.intent();var e=new Execution();if(binding instanceof NativeWarningBinding warning)e.perception=warning;e.retain=impact;
  e.arbiter=new ResourceArbiter(world,new Epoch(Math.max(floor,store.session().bootEpoch().value())),1000,4,new ResourceArbiter.Compatibility(){public boolean share(ResourceClaim a,ResourceClaim b){return false;}public boolean merge(ResourceId id,ResourceClaim a,ResourceClaim b){return false;}});
  var scope=new ScopeRef(ScopeKind.WORLD,world.value());var epochs=new HashMap<ResourceKey,Epoch>();plan.claims().forEach(c->epochs.put(c.resource(),c.expectedEpoch()));
  if(impact){
   var def=definition(f);var player=new PlayerId(f.player);var roles=new RoleAssignments(Map.of("witness",List.of(player)));
   var reference=new DefinitionRef(def.id(),def.contentRevision(),BlobContent.hash(new ModelCodec<>(EventDefinition.class).encode(def)));
   var execution=new ExecutionPlan(new CandidateId(UUID.randomUUID()),reference,List.of(f.warningIntent,intent),roles,new ScopeSet(world,Set.of(scope,new ScopeRef(ScopeKind.PLAYER,f.player))),plan.preconditions());
   var request=new AdmissionRequest(new DecisionId(UUID.randomUUID()),plan.preconditions(),plan.claims(),roles,execution);
   var validator=new AdmissionService.HardValidator(){
    public ProviderMode mode(){return ProviderMode.TEST_ONLY;}
    public AdmissionService.Assessment assess(AdmissionRequest proposed,Tick tick){
     boolean valid=request.equals(proposed)&&playerReady(f)&&exitReady(f)&&!store.readOnlySafe()&&f.warning.received()&&
      now().value()-f.warned>=400&&intent.primitive().equals(LANDSLIDE)&&
      intent.typedArgs().equals(def.steps().stream().filter(s->s.args() instanceof Landslide).findFirst().orElseThrow().args())&&
      intent.expectedFootprint().cells().equals(new HashSet<>(f.cells))&&binding.revalidate(plan,capture.blobs());
     return new AdmissionService.Assessment(new ValidationResult(valid,valid?List.of():List.of(ReasonCode.STALE_CONTEXT),plan.preconditions()),plan.claims(),new ResourceArbiter.Rank(false,false,false,Priority.DANGER,0,1,42),Map.of(scope,def.budget()));
    }
   };
   e.admission=new AdmissionService(()->f.catalog,validator,e.arbiter,this::now,new DurationTicks(1200),4,ProviderMode.TEST_ONLY);e.decision=request.proposalId();
   if(e.admission.admit(request).status()!=AdmissionStatus.DEFERRED)throw new IllegalStateException("landslide J03 queue rejected");
   var decision=e.admission.resolveWindow(epochs,Map.of(scope,CAP)).get(e.decision);
   if(decision==null||decision.status()!=AdmissionStatus.ACCEPTED)throw new IllegalStateException("landslide J03 denied "+decision);
   e.token=decision.token().orElseThrow();System.out.println("DIRECTOR_LAB_LANDSLIDE_J02_J03_MANUAL_ACCEPTED "+f.id+" automatic_eligibility=false");
  }else{
   var candidate=new ResourceArbiter.Candidate(new ReservationId(UUID.randomUUID()),intent.instanceId(),new ResourceArbiter.Rank(false,false,false,Priority.DANGER,0,1,42),now().plus(1200),plan.claims(),Map.of(scope,plan.budgetEstimate()));
   e.token=e.arbiter.arbitrate(List.of(candidate),now(),epochs,Map.of(scope,CAP)).getFirst().accepted().orElseThrow();
  }
  floor=Math.max(floor,e.token.generation().value());var slice=new PreparedSlice(new SliceId(UUID.randomUUID()),intent.operationToken(),intent.expectedFootprint(),plan.preconditions(),List.of(intent),plan.budgetEstimate());
  e.live=new LiveValidationSnapshot(plan.preconditions(),intent.expectedFootprint().loadedChunks(),HealthMode.NORMAL,now(),e.token.generation());
  var authority=new CommitPermit.Authority(store,4);var port=new ServerEffectPort(authority,i->{throw new IllegalStateException("already captured");},s->e.live,Map.of(intent.primitive(),binding),binding.capabilities(),4,s->new StabilizationLiveSnapshot(store.session(),e.live,new OwnershipView(owners)),m->{},(m,r)->{});port.hydrate(plan,capture.blobs());
  var prepare=new PrepareRecord(new TransactionId(UUID.randomUUID()),intent.operationToken(),e.token.generation(),plan.preconditions(),e.token.expiryTick(),slice,e.token,capture.validation(),List.of(),impact?Set.of(NoninvertibleEffect.DAMAGE):Set.of(),List.of(),plan.durableBlobs());
  e.perceptionPrepare=prepare;e.tx=prepare.transactionId();e.runner=new EventRunner(authority,store,port,e.arbiter,4);
  var batch=new DurableBatch(e.tx,store.session().storeGeneration(),prepare.readSet().revisions(),List.of(new DurablePrepare(prepare)),capture.blobs().contents(),Optional.empty(),CommitPermit.Authority.prepareHash(prepare));
  e.runner.submit(batch,plan.expectedPost(),e.live,actual->{
   var rows=new ArrayList<DurableRecord>();rows.add(new DurableResult(new ResultRecord(e.tx,intent.operationToken(),actual,Set.of(),List.of(),List.of())));
   for(var claim:plan.claims())if(claim.resource().domain()==OwnershipDomain.BLOCK)rows.add(new DurableOwnership(Objects.requireNonNull(owners.get(claim.resource()))));
   if(impact&&binding instanceof LandslideBinding debris){var players=new TreeMap<String,JsonValue>();debris.observedDamageFacts().forEach((id,value)->players.put(id.value().toString(),value));rows.add(new DurableFact(fact(new FactType("proutlost_director:lab_landslide_damage"),f,Map.of("players",new JsonValue.ObjectValue(players)))));}
   return ResultTransaction.batch(new TransactionId(UUID.randomUUID()),store.session().storeGeneration(),Map.of(),rows,List.of());
  });return e;
 }
 private void release(Execution e){if(e==null||e.released)return;e.arbiter.release(e.token);if(e.admission!=null)e.admission.released(e.decision,e.token);e.released=true;}
 private boolean applied(Execution e){
  if(e.noSend){if(!durable(e.noSendAck))return false;release(e);return true;}
  e.live=new LiveValidationSnapshot(e.live.readSet(),e.live.loadedChunks(),e.live.health(),now(),e.token.generation());var result=e.runner.poll(e.tx,e.live);if(result.stage()==EventRunner.Stage.RECOVERY_REQUIRED&&e.perception!=null&&e.perception.definitivelyNeverSent()){
   var safe=new SliceResult(Outcome.ABORTED_SAFE,Set.of(),Map.of(),0,Set.of(),Set.of(),List.of());
   e.noSendAck=append(List.of(new DurableResult(new ResultRecord(e.tx,e.perceptionPrepare.operationToken(),safe,Set.of(),List.of(),List.of()))));e.noSend=true;return false;
  }if(result.stage()==EventRunner.Stage.REJECTED||result.stage()==EventRunner.Stage.RECOVERY_REQUIRED)throw new IllegalStateException("landslide J04 "+result.stage()+" "+result.failure());if(result.stage()!=EventRunner.Stage.COMMITTED)return false;if(!e.retain)release(e);return true;}
 private boolean lineage(OwnershipRecord a,OwnershipRecord b){return a.header().id().equals(b.header().id())&&a.resourceKey().equals(b.resourceKey())&&a.ownerTokens().equals(b.ownerTokens())&&a.epoch().equals(b.epoch())&&a.playerOverrideEpoch().equals(b.playerOverrideEpoch())&&a.creationMutationId().equals(b.creationMutationId());}
 private OwnershipRecord released(OwnershipRecord o){var h=o.header();return new OwnershipRecord(new AggregateHeader(h.schemaVersion(),h.id(),h.revision().plus(1),h.lastSequence(),now()),o.resourceKey(),o.ownerTokens(),o.epoch(),OwnershipDisposition.RELEASED,o.creationMutationId(),o.expectedPostHash(),o.playerOverrideEpoch(),o.exceptionContractId());}
 private void finish(Flight f,String reason){
  if(f.warning!=null)f.warning.stop();var rows=new ArrayList<DurableRecord>();
  for(var p:f.cells){var current=attached.read(level(p),pos(p)).orElse(null);if(current==null)current=f.initial.get(key(p));
   if(lineage(current,f.initial.get(key(p)))&&current.disposition()==OwnershipDisposition.DIRECTOR_ACTIVE){
    var next=hash(p).equals(f.forward.preconditions().fingerprints().get(key(p)))?released(current):OwnershipRules.cede(current,OwnershipRules.ExternalChange.UNKNOWN,now());publish(p,next);rows.add(new DurableOwnership(next));
   }}
  closeUncertain(f,rows);
  rows.add(new DurableFact(fact(END,f,Map.of("reason",new JsonValue.Text(reason),"damage_replayed",new JsonValue.Bool(false)))));f.ack=append(rows);f.phase=7;
 }
 private static Aabb envelope(List<BlockPosition> cells){var d=cells.get(1);return new Aabb(d.dimension(),d.x()-1,d.y(),d.z()-1,d.x()+2,d.y()+3,d.z()+2);}
 private void closeUncertain(Flight f,List<DurableRecord> rows){
  for(var prepare:unmatched.values())if(prepare.reservation().instanceId().value().equals(f.id)){
   var uncertain=new SliceResult(Outcome.DELIVERY_UNCERTAIN,Set.of(),Map.of(),0,Set.of(),Set.of(),List.of());
   rows.add(new DurableResult(new ResultRecord(prepare.transactionId(),prepare.operationToken(),uncertain,Set.of(),List.of(),List.of())));
  }
 }
 private void cleanup(Flight f){
  f.selected=new HashSet<>();
  for(var p:f.cells){
   var current=attached.read(level(p),pos(p)).orElseThrow();owners.put(key(p),current);var initial=f.initial.get(key(p));
   if(OwnershipRules.rollback(current,initial.ownerTokens().iterator().next(),initial.epoch(),initial.playerOverrideEpoch(),f.forward.expectedPost().get(key(p)),Optional.of(hash(p)),true)==OwnershipRules.Rollback.ELIGIBLE)f.selected.add(p);
  }
  if(f.selected.isEmpty()){finish(f,"PRE_PRESENT_OR_YIELDED_NO_REPLAY");return;}
  // Restoring a solid source waits while an actual entity occupies its cell.
  var source=f.cells.getFirst();
  if(f.selected.contains(source)&&!level(source).getEntities(null,new AABB(pos(source))).isEmpty())return;
  var inverse=provider(f,true,args(f));f.work=run(f,capture(f,true,inverse),inverse,false);f.phase=6;
 }
 public String cancel(ServerPlayer operator){actor();if(!operator.hasPermissions(4))return "OP4 requis.";if(flight==null)return "Aucun eboulement LAB actif.";flight.stop=true;return "Arret demande : cleanup J04 des que possible.";}
 public void poll(){
  actor();if(failed)return;
  try{
   if(store.readOnlySafe())throw new IllegalStateException("landslide WAL unavailable");
   if(!scanned){if(!sharedRecovery)scan();return;}if(flight==null){recover();return;}var f=flight;
   if(f.cells.stream().anyMatch(p->!loaded(p)))return;
   switch(f.phase){
    case 0->{if(durable(f.ack)){
     for(var p:f.cells)publish(p,f.initial.get(key(p)));
     if(f.stop||!playerReady(f)){finish(f,"CANCELLED_BEFORE_WARN");return;}
     if(!f.warning.available()){finish(f,"SESSION_RENEWAL_BEFORE_WARNING");return;}
     var context=snapshot(f);var w=(Warning)definition(f).steps().stream().filter(s->s.args() instanceof Warning).findFirst().orElseThrow().args();
     f.warningIntent=new EffectIntent(new OperationToken(UUID.randomUUID()),new PrimitiveId("proutlost:warning"),1,w,context.snapshot().context().terrain().getFirst().loadedFootprint(),new EventInstanceId(f.id));
     f.work=run(f,f.warning.capture(f.warningIntent,context),f.warning,false);f.sent=now().value();f.phase=1;
    }}
    case 1->{if(applied(f.work)){
     if(f.work.noSend||!f.warning.available()){finish(f,"SESSION_RENEWAL_BEFORE_SEND_NO_REPLAY");return;}
     if(f.stop||!playerReady(f)){finish(f,"CANCELLED_DURING_WARN");return;}
     if(f.warning.received()){f.warned=now().value();f.phase=2;System.out.println("DIRECTOR_LAB_LANDSLIDE_WARN_RECEIVED "+f.id);}
     else if(now().value()-f.sent>=1200)finish(f,"WARN_TIMEOUT");
    }}
    case 2->{
     if(f.stop||!playerReady(f)){finish(f,"CANCELLED_BEFORE_IMPACT");return;}
     if(now().value()-f.warned>=400){
      for(var p:f.cells){var current=attached.read(level(p),pos(p)).orElseThrow();owners.put(key(p),current);
       if(!current.equals(f.initial.get(key(p)))||!hash(p).equals(f.forward.preconditions().fingerprints().get(key(p)))){finish(f,"PLAYER_EDIT_BEFORE_IMPACT");return;}}
      f.preparedBinding=provider(f,false,args(f));f.captured=capture(f,false,f.preparedBinding);f.forward=f.captured.prepared().orElseThrow();
      var sealed=dossier(f);f.ack=append(List.of(new DurableFact(sealed)));active.put(f.id,sealed);f.phase=3;
     }
    }
    case 3->{if(durable(f.ack)){
     if(f.stop||!playerReady(f)){finish(f,"CANCELLED_BEFORE_PREPARE");return;}
     f.work=run(f,f.captured,f.preparedBinding,true);f.impact=f.work;f.phase=4;
    }}
    case 4->{if(applied(f.work)){f.warning.stop();f.expires=now().value()+(f.stop?0:400);f.phase=5;System.out.println("DIRECTOR_LAB_LANDSLIDE_PHYSICAL_COMMITTED "+f.id);}}
    case 5->{if(f.stop||now().value()>=f.expires)cleanup(f);}
    case 6->{if(applied(f.work)){finish(f,"INVERSE_COMMITTED");System.out.println("DIRECTOR_LAB_LANDSLIDE_INVERSE_COMMITTED "+f.id);}}
    case 7->{if(durable(f.ack)){release(f.impact);ended.add(f.id);active.remove(f.id);unmatched.values().removeIf(p->p.reservation().instanceId().value().equals(f.id));flight=null;System.out.println("DIRECTOR_LAB_LANDSLIDE_CLEANUP_DURABLE "+f.id);}}
    case 8->{if(durable(f.ack)){f.expires=now().value();f.phase=5;}}
    default->throw new IllegalStateException("landslide phase");
   }
  }catch(RuntimeException failure){failed=true;System.out.println("DIRECTOR_LAB_LANDSLIDE_RECOVERY_REQUIRED "+failure);failure.printStackTrace();}
 }
 private void scan(){
  if(pending==null){pending=store.read(new StoreQuery(world,Set.of(),new Sequence(0),cursor,128)).toCompletableFuture();return;}
  if(!pending.isDone())return;var read=pending.getNow(null);pending=null;
  if(read.status()==ReadStatus.STALE){cursor=Optional.empty();journalOwners.clear();active.clear();ended.clear();unmatched.clear();resolved.clear();floor=0;return;}
  if(read.status()!=ReadStatus.AVAILABLE)throw new IllegalStateException("landslide recovery read "+read.status());
  var page=read.page().orElseThrow();
  acceptRecoveryRows(page.records(),!page.hasMore());
  cursor=page.nextCursor();
 }
 private boolean sharedRecovery;
 public void useSharedRecoveryScan(){actor();sharedRecovery=true;resetRecoveryScan();}
 public void resetRecoveryScan(){actor();scanned=false;pending=null;cursor=Optional.empty();journalOwners.clear();active.clear();ended.clear();unmatched.clear();resolved.clear();floor=0;}
 public void acceptRecoveryRows(List<DurableRecord> rows,boolean last){actor();
  for(var row:rows){
   if(row instanceof DurablePrepare p){var prepare=p.value();floor=Math.max(floor,prepare.reservationGeneration().value());var operations=prepare.slice().operations();
    if(operations.size()==1&&Set.of("proutlost:landslide","proutlost:warning").contains(operations.getFirst().primitive().value()))unmatched.put(prepare.operationToken(),prepare);}
   if(row instanceof DurableResult r)resolved.add(r.value().operationToken());
   if(row instanceof DurableOwnership r){var value=r.value();var old=journalOwners.get(value.resourceKey());
    if(old==null||value.epoch().value()>old.epoch().value()||value.epoch().equals(old.epoch())&&value.header().revision().value()>old.header().revision().value())journalOwners.put(value.resourceKey(),value);}
   if(row instanceof DurableFact r&&r.value().eventId().isPresent()){var fact=r.value();var id=fact.eventId().orElseThrow().value();if(fact.factType().equals(START))active.put(id,fact);else if(fact.factType().equals(END))ended.add(id);}
  }
  if(last){
   unmatched.entrySet().removeIf(e->resolved.contains(e.getKey())||!active.containsKey(e.getValue().reservation().instanceId().value()));
   for(var id:ended)if(unmatched.values().stream().noneMatch(p->p.reservation().instanceId().value().equals(id)))active.remove(id);
   scanned=true;System.out.println("DIRECTOR_LAB_LANDSLIDE_RECOVERY_SCAN pending="+active.size());
  }
 
 }

 private void recover(){
  if(active.isEmpty())return;var entry=active.entrySet().iterator().next();var saved=entry.getValue();var f=new Flight();
  f.id=entry.getKey();f.player=UUID.fromString(text(saved,"player"));f.forward=PLAN.decode(decode(saved,"plan"));
  f.cells=List.of(POSITION.decode(decode(saved,"source")),POSITION.decode(decode(saved,"destination")));f.selected=new HashSet<>(f.cells);
  f.initial.put(key(f.cells.get(0)),OWNER.decode(decode(saved,"o0")));f.initial.put(key(f.cells.get(1)),OWNER.decode(decode(saved,"o1")));
  if(!f.forward.intent().instanceId().value().equals(f.id)||!f.forward.intent().primitive().equals(LANDSLIDE)||!f.forward.intent().expectedFootprint().cells().equals(f.selected))throw new IllegalStateException("landslide recovery dossier identity");
  if(f.cells.stream().anyMatch(p->!loaded(p)))return;var repairs=new ArrayList<DurableRecord>();
  for(var p:f.cells){
   var k=key(p);var initial=f.initial.get(k);var persisted=Objects.requireNonNull(journalOwners.get(k),"landslide WAL owner missing");var observed=hash(p);
   var local=attached.read(level(p),pos(p)).orElse(null);
   if(local==null&&observed.equals(f.forward.preconditions().fingerprints().get(k))&&persisted.equals(initial)){local=initial;publish(p,local);}
   if(local==null)throw new IllegalStateException("landslide local owner missing");
   if(!local.equals(persisted)){
    boolean next=local.header().id().equals(persisted.header().id())&&local.resourceKey().equals(k)&&local.ownerTokens().equals(persisted.ownerTokens())&&local.header().revision().value()==persisted.header().revision().value()+1;
    boolean owned=next&&lineage(local,initial)&&local.expectedPostHash().equals(observed)&&
     (observed.equals(f.forward.preconditions().fingerprints().get(k))||observed.equals(f.forward.expectedPost().get(k)));
    boolean yielded=next&&Set.of(OwnershipDisposition.YIELDED_TO_PLAYER,OwnershipDisposition.CONFLICT_ISOLATED).contains(local.disposition())&&local.epoch().value()>=persisted.epoch().value()&&local.playerOverrideEpoch().value()>=persisted.playerOverrideEpoch().value();
    if(!owned&&!yielded)throw new IllegalStateException("landslide WAL/world owner mismatch "+k);
    repairs.add(new DurableOwnership(local));journalOwners.put(k,local);
   }
   owners.put(k,local);
  }
  flight=f;f.expires=now().value();if(repairs.isEmpty())f.phase=5;else{f.ack=append(repairs);f.phase=8;}
  System.out.println("DIRECTOR_LAB_LANDSLIDE_RECOVER_CLEANUP_NO_DAMAGE_REPLAY "+f.id);
 }
 public boolean recovering(){actor();return !scanned&&!failed;}
 public boolean busy(){actor();return !scanned||failed||flight!=null||!active.isEmpty();}
 public String status(){actor();return failed?"LANDSLIDE_RECOVERY_REQUIRED":!scanned?"LANDSLIDE_RECOVERING":flight==null?"LANDSLIDE_IDLE":"LANDSLIDE_PHASE_"+flight.phase+" "+flight.id;}
 @Override public void close(){actor();if(flight!=null&&flight.warning!=null)flight.warning.stop();closed=true;}
}
