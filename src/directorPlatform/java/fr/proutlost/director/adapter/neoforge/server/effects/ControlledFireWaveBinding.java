package fr.proutlost.director.adapter.neoforge.server.effects;

import fr.proutlost.director.api.codec.CanonicalJsonV1;
import fr.proutlost.director.api.codec.ModelCodec;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.runtime.ControlledFirePlanner;
import java.math.BigDecimal;
import java.util.*;
import java.util.function.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/** One actor-owned, maximum-two-cell controlled fire. Every wave must pass EventRunner/J04.
 * Capture does no native write. Tick/contact callbacks only enqueue observations.
 * Call committed() only after the exact EventRunner reports COMMITTED.
 */
public final class ControlledFireWaveBinding implements ServerEffectPort.Binding,ControlledFireBlock.Sink,AutoCloseable {
 public static final PrimitiveId FIRE=new PrimitiveId("proutlost:controlled_fire");
 public static final PrimitiveId CLEANUP=new PrimitiveId("proutlost:controlled_fire_cleanup");
 public static final ResourceId MASK_CODEC=new ResourceId("proutlost_director:fire_mask_v1");
 public static final ResourceId STATE_CODEC=new ResourceId("proutlost_director:fire_engine_state_v1");
 public static final ResourceId HIT_CODEC=new ResourceId("proutlost_director:fire_hit_ledger_v1");
 private static final ResourceId OWNER_PRE=new ResourceId("proutlost_director:fire_owner_pre_v1");
 private static final ResourceId OWNER_PRE_LEGACY=new ResourceId("proutlost_director:owner_v1");
 private static final ResourceId OWNER_POST=new ResourceId("proutlost_director:fire_owner_post_v1");
 private static final ResourceId WAVE_CODEC=new ResourceId("proutlost_director:fire_wave_v1");
 private static final ResourceId INTENT_CODEC=new ResourceId("proutlost_director:fire_intent_v1");
 private static final ResourceId IN_FIRE=new ResourceId("minecraft:in_fire");
 private static final ModelCodec<OwnershipRecord> OWNER=new ModelCodec<>(OwnershipRecord.class);
 private static final ModelCodec<Footprint> FOOTPRINT=new ModelCodec<>(Footprint.class);
 private static final Set<CapabilityId> CAPS=Set.of(
  new CapabilityId("mutation.controlled_fire_v1"),new CapabilityId("danger.controlled_fire_v1"));
 private record Hit(double reserved,long lastTick){}
 private record PlannedHit(PlayerId player,double amount){}
 private static final class Pending {
  PreparedEffect plan;BlobBundle blobs;OperationToken operation;
  BlobContent stateAfter,ledgerAfter;Map<BlockPosition,OwnershipRecord> before=new HashMap<>(),after=new HashMap<>();
  Map<BlockPosition,BlockState> targets=new HashMap<>(),observedStates=new HashMap<>();Map<BlockPosition,OwnershipRecord> observedOwners=new HashMap<>();Map<BlockPosition,Long> energyAfter;
  Map<PlayerId,Hit> hitsAfter;List<PlannedHit> damage;boolean cleanup,applied,nativeAttempted;
  long tick,cursor;List<DurableRecord> records=List.of();
 }
 private final MinecraftServer server;private final WorldId world;private final EventInstanceId event;
 private final ResourceId recipe;private final BlobContent maskBlob;private final Footprint mask;
 private final Supplier<ControlledFireBlock> block;private final Supplier<OwnershipView> ownership;
 private final BiConsumer<BlockPosition,OwnershipRecord> localLedger;private final Supplier<Tick> clock;
 private final BooleanSupplier liveSafety;private final DamageProfile profile;private final double probability;private final boolean nativeFixtureOnly;
 private final Map<BlockPosition,OwnershipRecord> lineage=new HashMap<>();
 private final Map<BlockPosition,Long> energy=new HashMap<>();private final Map<PlayerId,Hit> hits=new HashMap<>();
 private final Set<PlayerId> audience=new HashSet<>(),contacts=new HashSet<>();
 private final Set<BlockPosition> dirty=new HashSet<>();
 private long lastTick,burnCursor;private boolean started,stateReady,closed,unknownContact,recoveryCleanupOnly;private Pending pending;

 public ControlledFireWaveBinding(MinecraftServer server,WorldId world,EventInstanceId event,ResourceId recipe,
   BlobContent propagationMask,Map<BlockPosition,Long> initialEnergy,Supplier<ControlledFireBlock> block,
   Supplier<OwnershipView> ownership,BiConsumer<BlockPosition,OwnershipRecord> localLedger,
   Supplier<Tick> clock,DamageProfile profile,Set<PlayerId> actualAudience,
   BooleanSupplier liveSafety,double probability){
  this(server,world,event,recipe,propagationMask,initialEnergy,block,ownership,localLedger,clock,profile,actualAudience,liveSafety,probability,null);
 }
 /** Recovery supplies the ORIGINAL forced owner lineage; current yielded/released rows remain untouched. */
 public ControlledFireWaveBinding(MinecraftServer server,WorldId world,EventInstanceId event,ResourceId recipe,
   BlobContent propagationMask,Map<BlockPosition,Long> initialEnergy,Supplier<ControlledFireBlock> block,
   Supplier<OwnershipView> ownership,BiConsumer<BlockPosition,OwnershipRecord> localLedger,
   Supplier<Tick> clock,DamageProfile profile,Set<PlayerId> actualAudience,
   BooleanSupplier liveSafety,double probability,Map<BlockPosition,OwnershipRecord> originallyAdmitted){
  this(server,world,event,recipe,propagationMask,initialEnergy,block,ownership,localLedger,clock,profile,actualAudience,liveSafety,probability,originallyAdmitted,false);
 }
 /** Explicit server-only fixture, not a WARN/audience/human proof. It can never gain a damage audience. */
 public static ControlledFireWaveBinding nativeFixture(MinecraftServer server,WorldId world,EventInstanceId event,ResourceId recipe,
   BlobContent mask,Map<BlockPosition,Long> seed,Supplier<ControlledFireBlock> block,Supplier<OwnershipView> ownership,
   BiConsumer<BlockPosition,OwnershipRecord> changed,Supplier<Tick> clock,DamageProfile profile,BooleanSupplier nativeSafe,
   double probability,Map<BlockPosition,OwnershipRecord> originallyAdmitted){
  require(Boolean.getBoolean("proutlost.director.nativeGameTests")&&Boolean.getBoolean("proutlost.director.nativeFireProviderGameTest"),"native fire fixture disabled");
  return new ControlledFireWaveBinding(server,world,event,recipe,mask,seed,block,ownership,changed,clock,profile,Set.of(),nativeSafe,probability,originallyAdmitted,true);
 }
 private ControlledFireWaveBinding(MinecraftServer server,WorldId world,EventInstanceId event,ResourceId recipe,
   BlobContent propagationMask,Map<BlockPosition,Long> initialEnergy,Supplier<ControlledFireBlock> block,
   Supplier<OwnershipView> ownership,BiConsumer<BlockPosition,OwnershipRecord> localLedger,Supplier<Tick> clock,
   DamageProfile profile,Set<PlayerId> actualAudience,BooleanSupplier liveSafety,double probability,
   Map<BlockPosition,OwnershipRecord> originallyAdmitted,boolean nativeFixtureOnly){
  this.nativeFixtureOnly=nativeFixtureOnly;
  this.server=Objects.requireNonNull(server);this.world=Objects.requireNonNull(world);this.event=Objects.requireNonNull(event);
  this.recipe=Objects.requireNonNull(recipe);this.maskBlob=Objects.requireNonNull(propagationMask);
  this.block=Objects.requireNonNull(block);this.ownership=Objects.requireNonNull(ownership);
  this.localLedger=Objects.requireNonNull(localLedger);this.clock=Objects.requireNonNull(clock);
  this.profile=Objects.requireNonNull(profile);this.liveSafety=Objects.requireNonNull(liveSafety);actor();
  require(propagationMask.reference().codecId().equals(MASK_CODEC)&&propagationMask.reference().codecVersion()==1,"fire mask codec");
  mask=FOOTPRINT.decode(propagationMask.bytes());
  require(mask.cells().size()>=1&&mask.cells().size()<=2&&initialEnergy.keySet().equals(mask.cells()),"one or two admitted mask cells");
  require(Double.isFinite(probability)&&probability>=0&&probability<=1,"ignition probability");
  this.probability=probability;
  require(profile.damageTypes().contains(IN_FIRE)&&profile.perHit()>0&&profile.maxPerPlayer()>=profile.perHit()
   &&profile.hitGap().value()>0&&profile.hitLedgerCodec().equals(HIT_CODEC),"bounded actual fire damage profile");
  require((!actualAudience.isEmpty()||nativeFixtureOnly)&&actualAudience.size()<=16,"actual warning audience required");audience.addAll(actualAudience);
  lastTick=clock.get().value();stateReady=true;
  for(var p:mask.cells()){
   var amount=Objects.requireNonNull(initialEnergy.get(p));require(amount>=0,"negative energy");energy.put(p,amount);
   var o=Objects.requireNonNull(originallyAdmitted==null?ownership.get().records().get(key(p)):originallyAdmitted.get(p),"fire original ownership not admitted");
   require(o.disposition()==OwnershipDisposition.DIRECTOR_ACTIVE&&!o.ownerTokens().isEmpty(),"active admitted fire cell");
   lineage.put(p,o);var actual=level(p).getBlockState(pos(p));
   stateReady&=actual.is(Blocks.AIR)&&o.expectedPostHash().equals(state(actual).reference().hash());
  }
  require(initialEnergy.values().stream().reduce(0L,Math::addExact)>0,"positive admitted ignition energy");
  ControlledFireBlock.bind(server,mask.cells(),this);
 }
 private void actor(){if(closed||!server.isSameThread())throw new IllegalStateException("controlled fire actor");}
 private static void require(boolean ok,String why){if(!ok)throw new IllegalArgumentException(why);}
 private static BlockPos pos(BlockPosition p){return new BlockPos(p.x(),p.y(),p.z());}
 private static ResourceKey key(BlockPosition p){return new ResourceKey(OwnershipDomain.BLOCK,p.dimension(),p.x()+","+p.y()+","+p.z());}
 private ServerLevel level(BlockPosition p){
  for(var l:server.getAllLevels())if(l.dimension().location().toString().equals(p.dimension().value()))return l;
  throw new IllegalStateException("fire dimension unavailable");
 }
 private boolean loaded(BlockPosition p){var l=level(p);return mask.loadedChunks().contains(new ChunkRef(p.dimension(),p.x()>>4,p.z()>>4))&&l.getChunkSource().getChunkNow(p.x()>>4,p.z()>>4)!=null
  &&p.y()>=l.getMinBuildHeight()&&p.y()<l.getMaxBuildHeight()&&l.getWorldBorder().isWithinBounds(pos(p));}
 private static BlobContent state(BlockState state){return BlockTransformBinding.stateBlob(state);}
 private static BlobContent owner(ResourceId codec,OwnershipRecord row){
  byte[] raw=OWNER.encode(row);
  if(codec.equals(OWNER_PRE))raw=CanonicalJsonV1.encode(new JsonValue.ObjectValue(Map.of(
   "schema",new JsonValue.Text("1"),"kind",new JsonValue.Text("FIRE_OWNER_PRE"),
   "owner_base64",new JsonValue.Text(Base64.getEncoder().encodeToString(raw)))));
  return BlobContent.capture(codec,1,raw);
 }
 private static boolean ownerPre(BlobRef ref){return ref.codecVersion()==1&&(ref.codecId().equals(OWNER_PRE)||ref.codecId().equals(OWNER_PRE_LEGACY));}
 private static OwnershipRecord decodeOwnerPre(BlobContent content){
  var ref=content.reference();require(ownerPre(ref),"fire PRE ownership codec");
  if(ref.codecId().equals(OWNER_PRE_LEGACY))return OWNER.decode(content.bytes());
  var value=object(content.bytes());
  require(value.keySet().equals(Set.of("schema","kind","owner_base64"))&&text(value,"schema").equals("1")
   &&text(value,"kind").equals("FIRE_OWNER_PRE"),"fire PRE ownership envelope");
  return OWNER.decode(Base64.getDecoder().decode(text(value,"owner_base64")));
 }
 private static JsonValue.Text text(Object value){return new JsonValue.Text(value.toString());}
 private static JsonValue.Number number(double value){return new JsonValue.Number(BigDecimal.valueOf(value));}
 private static String text(Map<String,JsonValue> map,String key){return ((JsonValue.Text)map.get(key)).value();}
 private static long count(Map<String,JsonValue> map,String key){return Long.parseLong(text(map,key));}
 private static Map<String,JsonValue> object(byte[] bytes){return ((JsonValue.ObjectValue)CanonicalJsonV1.decode(bytes)).values();}
 private static String cellKey(BlockPosition p){return p.x()+","+p.y()+","+p.z();}
 private boolean ours(BlockPosition p,OwnershipRecord current){
  var original=lineage.get(p);
  return current!=null&&current.header().id().equals(original.header().id())&&current.resourceKey().equals(key(p))
   &&current.ownerTokens().equals(original.ownerTokens())&&current.epoch().equals(original.epoch())
   &&current.playerOverrideEpoch().equals(original.playerOverrideEpoch())&&current.creationMutationId().equals(original.creationMutationId());
 }
 private boolean usable(BlockPosition p,OwnershipRecord row){return ours(p,row)&&row.disposition()==OwnershipDisposition.DIRECTOR_ACTIVE;}
 private boolean nativeState(BlockState state){return state.is(Blocks.AIR)||state.is(block.get());}
 private boolean site(BlockPosition p){
  if(!loaded(p))return false;var l=level(p);var at=pos(p);
  if(!l.getBlockState(at.below()).is(Blocks.STONE))return false;
  for(var d:List.of(Direction.NORTH,Direction.SOUTH,Direction.EAST,Direction.WEST)){
   var near=at.relative(d);var point=new BlockPosition(p.dimension(),near.getX(),near.getY(),near.getZ());
   if(!loaded(point))return false;
   if(mask.cells().contains(point)){if(!nativeState(l.getBlockState(near)))return false;}
   else if(!l.getBlockState(near).is(Blocks.STONE))return false;
  }
  return true;
 }
 private boolean inside(ServerPlayer player,BlockPosition p){
  return player.serverLevel()==level(p)&&player.getBoundingBox().intersects(new AABB(pos(p)));
 }
 private boolean excluded(ServerPlayer player){return profile.excludedZones().stream().anyMatch(b->
  b.dimension().value().equals(player.serverLevel().dimension().location().toString())&&
   player.getBoundingBox().intersects(new AABB(b.minX(),b.minY(),b.minZ(),b.maxXExclusive(),b.maxYExclusive(),b.maxZExclusive())));}
 @Override public void observe(ServerLevel l,BlockPos p){
  if(closed)return;actor();var at=new BlockPosition(new DimensionId(l.dimension().location().toString()),p.getX(),p.getY(),p.getZ());
  if(mask.cells().contains(at))dirty.add(at);
 }
 @Override public void contact(ServerLevel l,BlockPos p,ServerPlayer player){
  if(closed)return;actor();var at=new BlockPosition(new DimensionId(l.dimension().location().toString()),p.getX(),p.getY(),p.getZ());
  if(!mask.cells().contains(at))return;var id=new PlayerId(player.getUUID());
  if(audience.contains(id))contacts.add(id);else unknownContact=true;
 }
 public void audienceAfterDurableWarning(Set<PlayerId> current){
  actor();require(!nativeFixtureOnly&&pending==null&&!current.isEmpty()&&current.size()<=16,"warning audience update while wave pending or fixture-only");
  audience.clear();audience.addAll(current);unknownContact=false;
 }
 public boolean requiresWarningRefresh(){actor();return unknownContact;}
 public boolean needsWave(){actor();return pending==null&&started&&clock.get().value()-lastTick>=20&&(!dirty.isEmpty()||!contacts.isEmpty()||energy.values().stream().anyMatch(v->v>0));}
 public boolean extinguished(){actor();return pending==null&&energy.values().stream().allMatch(v->v==0);}
 public Set<BlockPosition> unresolved(){
  actor();var found=new HashSet<BlockPosition>();
  for(var p:mask.cells()){if(!loaded(p)){found.add(p);continue;}var o=ownership.get().records().get(key(p));
   if(usable(p,o)&&!nativeState(level(p).getBlockState(pos(p))))found.add(p);}
  return Set.copyOf(found);
 }
 public ProviderMode mode(){return ProviderMode.PRODUCTION;}
 public Set<PrimitiveId> supportedPrimitives(){return Set.of(FIRE,CLEANUP);}
 public Set<CapabilityId> capabilities(){return CAPS;}
 public Set<NoninvertibleEffect> noninvertibleEffects(){
  actor();return pending!=null&&!pending.cleanup?Set.of(NoninvertibleEffect.DAMAGE):Set.of();
 }
 private ControlledFire arguments(EffectIntent intent){
  if(intent.version()!=1||!supportedPrimitives().contains(intent.primitive())||!(intent.typedArgs() instanceof ControlledFire a)
    ||!a.recipe().equals(recipe)||!a.propagationMask().equals(maskBlob.reference())||!a.damageProfile().equals(profile.id())
    ||a.energy().value()<1||!intent.instanceId().equals(event)||!intent.expectedFootprint().equals(mask))
   throw new IllegalArgumentException("exact typed controlled fire recipe/mask required");
  return a;
 }
 private BlobContent stateBlob(String stage,Map<BlockPosition,Long> values,long tick,long cursor,boolean active){
  var cells=new TreeMap<String,JsonValue>();values.forEach((p,v)->cells.put(cellKey(p),text(v)));
  return BlobContent.capture(STATE_CODEC,1,CanonicalJsonV1.encode(new JsonValue.ObjectValue(Map.of(
   "schema",text(1),"stage",text(stage),"event",text(event.value()),"mask",text(maskBlob.reference().hash().value()),
   "tick",text(tick),"cursor",text(cursor),"started",new JsonValue.Bool(active),"energy",new JsonValue.ObjectValue(cells)))));
 }
 private BlobContent ledgerBlob(String stage,Map<PlayerId,Hit> values){
  var players=new TreeMap<String,JsonValue>();values.forEach((p,h)->players.put(p.value().toString(),
   new JsonValue.ObjectValue(Map.of("reserved",number(h.reserved()),"last_tick",text(h.lastTick())))));
  return BlobContent.capture(HIT_CODEC,1,CanonicalJsonV1.encode(new JsonValue.ObjectValue(Map.of(
   "schema",text(1),"stage",text(stage),"event",text(event.value()),"profile",text(profile.id().value()),
   "mask",text(maskBlob.reference().hash().value()),"players",new JsonValue.ObjectValue(players)))));
 }
 private OwnershipRecord successor(OwnershipRecord before,Fingerprint observed,Tick now,boolean cleanup){
  var h=before.header();return new OwnershipRecord(new AggregateHeader(h.schemaVersion(),h.id(),h.revision().plus(1),h.lastSequence(),new Tick(server.overworld().getGameTime())),
   before.resourceKey(),before.ownerTokens(),before.epoch(),cleanup?OwnershipDisposition.RELEASED:before.disposition(),
   before.creationMutationId(),observed,before.playerOverrideEpoch(),before.exceptionContractId());
 }
 public EffectPreparation capture(EffectIntent intent,PlanningSnapshot context){
  actor();require(pending==null,"previous fire wave requires its durable RESULT");var a=arguments(intent);
  var cleanup=intent.primitive().equals(CLEANUP);require(cleanup||(!recoveryCleanupOnly&&stateReady),"recovery may clean only; no old damage continuation");
  require(cleanup||(!unknownContact&&liveSafety.getAsBoolean()&&context.snapshot().serverHealth()==HealthMode.NORMAL),"fire WARN/session/context unavailable");
  for(var chunk:mask.loadedChunks())require(level(mask.cells().iterator().next()).getChunkSource().getChunkNow(chunk.x(),chunk.z())!=null,"fire mask chunk unavailable");
  require(energy.values().stream().reduce(0L,Math::addExact)<=a.energy().value(),"declared total energy exceeded");
  var now=clock.get();require(now.value()>=lastTick,"fire clock regression");
  require(cleanup||!started||now.value()-lastTick>=20,"bounded 20-tick fire cadence");
  var beforeState=stateBlob("before",energy,lastTick,burnCursor,started);var beforeLedger=ledgerBlob("before",hits);
  var rows=ownership.get().records();var fuels=new HashMap<BlockPosition,ControlledFirePlanner.Fuel>();
  var q=new Pending();q.operation=intent.operationToken();q.cleanup=cleanup;q.tick=now.value();q.cursor=Math.addExact(burnCursor,1);
  for(var p:mask.cells()){
   require(loaded(p),"fire target unavailable");var o=rows.get(key(p));var actual=level(p).getBlockState(pos(p));
   q.observedStates.put(p,actual);if(o!=null)q.observedOwners.put(p,o);
   boolean admitted=usable(p,o)&&nativeState(actual);boolean observedGone=admitted&&actual.isAir()
    &&!o.expectedPostHash().equals(state(Blocks.AIR.defaultBlockState()).reference().hash());
   var value=cleanup||!admitted||observedGone?0:energy.get(p);
   fuels.put(p,new ControlledFirePlanner.Fuel(value,o==null?new Epoch(0):o.epoch(),admitted&&!observedGone&&site(p),!admitted));
   if(admitted)q.before.put(p,o);
  }
  require(!q.before.isEmpty(),"all fire cells yielded; retire without a physical wave");
  var elapsed=new DurationTicks(started?now.value()-lastTick:0);
  var proposal=ControlledFirePlanner.plan(fuels,mask.cells(),elapsed,probability,2,Math.min(20,a.energy().value()),
   event.value().getMostSignificantBits()^q.cursor,cleanup);
  q.energyAfter=new HashMap<>(proposal.remainingEnergy());
  if(cleanup)q.energyAfter.replaceAll((p,v)->0L);
  for(var p:mask.cells())if(!q.before.containsKey(p)||!site(p))q.energyAfter.put(p,0L);
  var afterHits=new HashMap<>(hits);var planned=new ArrayList<PlannedHit>();
  if(!cleanup)for(var id:new TreeSet<>(contacts)){
   var player=server.getPlayerList().getPlayer(id.value());var previous=hits.getOrDefault(id,new Hit(0,Long.MIN_VALUE/2));
   if(player==null||!player.isAlive()||excluded(player)||now.value()-previous.lastTick()<profile.hitGap().value())continue;
   boolean touches=mask.cells().stream().anyMatch(p->q.before.containsKey(p)&&q.energyAfter.get(p)>0
    &&level(p).getBlockState(pos(p)).is(block.get())&&inside(player,p));
   double amount=Math.min(profile.perHit(),profile.maxPerPlayer()-previous.reserved());
   amount=Math.min(amount,Math.max(0,player.getHealth()+player.getAbsorptionAmount()-1));
   if(touches&&amount>0){planned.add(new PlannedHit(id,amount));afterHits.put(id,new Hit(previous.reserved()+amount,now.value()));}
  }
  q.damage=List.copyOf(planned);q.hitsAfter=afterHits;
  q.stateAfter=stateBlob("after",q.energyAfter,q.tick,q.cursor,started||q.energyAfter.values().stream().anyMatch(v->v>0));
  q.ledgerAfter=ledgerBlob("after",afterHits);
  var blobs=new HashMap<BlobRef,BlobContent>();var snapshots=new HashSet<BlobRef>();
  for(var b:List.of(maskBlob,beforeState,q.stateAfter,beforeLedger,q.ledgerAfter)){blobs.put(b.reference(),b);snapshots.add(b.reference());}
  var pre=new HashMap<ResourceKey,BlobRef>();var post=new HashMap<ResourceKey,BlobRef>();
  var epochs=new HashMap<ResourceKey,Epoch>();var hashes=new HashMap<ResourceKey,Fingerprint>();var expected=new HashMap<ResourceKey,Fingerprint>();
  var claims=new ArrayList<ResourceClaim>();
  // Read-only yielded/foreign cells remain in the forced snapshot and read-set.
  for(var p:mask.cells()){
   var actual=state(q.observedStates.get(p));blobs.put(actual.reference(),actual);snapshots.add(actual.reference());hashes.put(key(p),actual.reference().hash());
   var o=q.observedOwners.get(p);if(o!=null){var b=owner(OWNER_PRE,o);blobs.put(b.reference(),b);snapshots.add(b.reference());epochs.put(key(p),o.epoch());}
  }
  for(var entry:q.before.entrySet()){
   var p=entry.getKey();var o=entry.getValue();var k=key(p);var actual=state(level(p).getBlockState(pos(p)));
   var target=q.energyAfter.get(p)>0?block.get().defaultBlockState():Blocks.AIR.defaultBlockState();
   var end=state(target);var next=successor(o,end.reference().hash(),now,cleanup);q.after.put(p,next);q.targets.put(p,target);
   for(var b:List.of(actual,end,owner(OWNER_PRE,o),owner(OWNER_POST,next))){blobs.put(b.reference(),b);}
   snapshots.add(owner(OWNER_PRE,o).reference());snapshots.add(owner(OWNER_POST,next).reference());
   pre.put(k,actual.reference());post.put(k,end.reference());epochs.put(k,o.epoch());hashes.put(k,actual.reference().hash());expected.put(k,end.reference().hash());
   claims.add(new ResourceClaim(k,ClaimPolicy.EXCLUSIVE,new BudgetVector(0,0,0,0,0,1,0,0,0,0),Optional.empty(),o.epoch()));
  }
  var damage=new ArrayList<JsonValue>();for(var h:planned)damage.add(new JsonValue.ObjectValue(Map.of("player",text(h.player().value()),"amount",number(h.amount()))));
  var ignitionFacts=new ArrayList<JsonValue>();for(var ignition:proposal.ignitions())ignitionFacts.add(
   new JsonValue.ObjectValue(Map.of("source",text(cellKey(ignition.source())),"target",text(cellKey(ignition.target())),
    "transferred_energy",text(ignition.transferredEnergy()),"target_owner_epoch",text(ignition.targetEpoch().value()))));
  var waveValues=new LinkedHashMap<String,JsonValue>();waveValues.putAll(Map.of(
   "event",text(event.value()),"operation",text(intent.operationToken().value()),"cursor",text(q.cursor),"tick",text(q.tick),
   "cleanup",new JsonValue.Bool(cleanup),"rng_seed",text(event.value().getMostSignificantBits()^q.cursor),
   "damage",new JsonValue.Array(damage),"after_state",text(q.stateAfter.reference().hash().value()),"after_ledger",text(q.ledgerAfter.reference().hash().value())));
  waveValues.put("ignition_probability",number(probability));waveValues.put("declared_energy",text(a.energy().value()));
  waveValues.put("maximum_ignitions",text(2));waveValues.put("maximum_ignition_energy",text(Math.min(20,a.energy().value())));
  waveValues.put("ignitions",new JsonValue.Array(ignitionFacts));
  var wave=BlobContent.capture(WAVE_CODEC,1,CanonicalJsonV1.encode(new JsonValue.ObjectValue(waveValues)));
  var authored=BlobContent.capture(INTENT_CODEC,1,new ModelCodec<>(EffectIntent.class).encode(intent));blobs.put(authored.reference(),authored);snapshots.add(authored.reference());
  blobs.put(wave.reference(),wave);var read=new ReadSet(context.snapshot().scopeRevisions(),epochs,hashes);
  var cost=new BudgetVector(0,0,0,0,0,claims.size(),0,0,0,0);
  var refs=new EffectBlobs(snapshots,wave.reference(),wave.reference(),pre,post);
  q.plan=new PreparedEffect(intent,context.snapshot(),claims,read,expected,cost,List.of(),capabilities(),refs);
  q.blobs=new BlobBundle(blobs.values().stream().sorted(Comparator.comparing(b->b.reference().hash().value())).toList());pending=q;
  return new EffectPreparation(intent,new ValidationResult(true,List.of(),read),Optional.of(q.plan),q.blobs);
 }
 private boolean sameBlobs(BlobBundle a,BlobBundle b){
  if(a.contents().size()!=b.contents().size())return false;
  return a.contents().stream().allMatch(x->b.contents().stream().anyMatch(y->x.reference().equals(y.reference())&&Arrays.equals(x.bytes(),y.bytes())));
 }
 public boolean revalidate(PreparedEffect plan,BlobBundle blobs){
  actor();var q=pending;if(q==null||q.nativeAttempted||q.applied||!q.plan.equals(plan)||!sameBlobs(q.blobs,blobs))return false;
  if(!q.cleanup&&(unknownContact||!liveSafety.getAsBoolean()))return false;
  var owners=ownership.get().records();
  for(var p:mask.cells()){
   if(!loaded(p)||!Objects.equals(q.observedOwners.get(p),owners.get(key(p))))return false;
   var actual=level(p).getBlockState(pos(p));
   if(!actual.equals(q.observedStates.get(p))&&!(q.cleanup&&q.before.containsKey(p)&&actual.is(Blocks.AIR)))return false;
  }
  for(var entry:q.before.entrySet()){
   var p=entry.getKey();if(!loaded(p)||!entry.getValue().equals(owners.get(key(p))))return false;
   var actual=level(p).getBlockState(pos(p));var pre=plan.preconditions().fingerprints().get(key(p));
   if(!state(actual).reference().hash().equals(pre)&&!(q.cleanup&&actual.is(Blocks.AIR)))return false;
   if(q.targets.get(p).is(block.get())&&!site(p))return false;
  }
  return true;
 }
 public SliceResult apply(PreparedEffect plan,BlobBundle blobs){
  actor();if(!revalidate(plan,blobs))throw new IllegalStateException("controlled fire changed before authorized wave");
  var q=pending;q.nativeAttempted=true;var observed=new HashMap<ResourceKey,Fingerprint>();var conflicts=new HashSet<BlockPosition>();int writes=0;
  for(var p:q.before.keySet().stream().sorted(Comparator.comparing(ControlledFireWaveBinding::cellKey)).toList()){
   var l=level(p);var target=q.targets.get(p);var actual=l.getBlockState(pos(p));
   if(!actual.equals(target)){
    if(!l.setBlock(pos(p),target,Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE|Block.UPDATE_SUPPRESS_DROPS)){conflicts.add(p);break;}
    writes++;
   }
   var hash=state(l.getBlockState(pos(p))).reference().hash();observed.put(key(p),hash);
   if(!hash.equals(plan.expectedPost().get(key(p)))){conflicts.add(p);break;}
   localLedger.accept(p,q.after.get(p));
  }
  if(!conflicts.isEmpty())return new SliceResult(Outcome.PARTIAL,Set.of(plan.intent().operationToken()),observed,Math.max(1,q.before.size()-observed.size()),Set.of(),conflicts,List.of());
  // All reserved damage is consumed before any hurt; an uncertain result is never replayed.
  energy.clear();energy.putAll(q.energyAfter);hits.clear();hits.putAll(q.hitsAfter);lastTick=q.tick;burnCursor=q.cursor;
  started=started||energy.values().stream().anyMatch(v->v>0);stateReady=true;var damageFacts=new TreeMap<String,JsonValue>();
  for(var h:q.damage){
   var player=server.getPlayerList().getPlayer(h.player().value());String reason="left_or_unavailable";boolean accepted=false;
   float health=player==null?0:player.getHealth(),absorption=player==null?0:player.getAbsorptionAmount();
   if(player!=null&&player.isAlive()&&audience.contains(h.player())&&!excluded(player)&&health+absorption>h.amount()
     &&mask.cells().stream().anyMatch(p->energy.get(p)>0&&level(p).getBlockState(pos(p)).is(block.get())&&inside(player,p))){
    accepted=player.hurt(player.damageSources().inFire(),(float)h.amount());reason=accepted?"native_hurt_returned_true":"native_refused";
   }
   float after=player==null?0:player.getHealth(),afterAbsorption=player==null?0:player.getAbsorptionAmount();
   damageFacts.put(h.player().value().toString(),new JsonValue.ObjectValue(Map.of("reserved",number(h.amount()),
    "accepted",new JsonValue.Bool(accepted),"reason",text(reason),"health_before",number(health),"health_after",number(after),
    "absorption_before",number(absorption),"absorption_after",number(afterAbsorption),
    "observed_loss",number(Math.max(0,(double)health+absorption-after-afterAbsorption)))));
  }
  var records=new ArrayList<DurableRecord>();q.after.values().forEach(o->records.add(new DurableOwnership(o)));
  var payload=new LinkedHashMap<String,JsonValue>();payload.put("operation",text(q.operation.value()));payload.put("tick",text(q.tick));
  payload.put("burn_cursor",text(q.cursor));payload.put("native_block_writes",text(writes));payload.put("cleanup",new JsonValue.Bool(q.cleanup));
  payload.put("state_base64",text(Base64.getEncoder().encodeToString(q.stateAfter.bytes())));
  payload.put("hit_ledger_base64",text(Base64.getEncoder().encodeToString(q.ledgerAfter.bytes())));
  payload.put("damage",new JsonValue.ObjectValue(damageFacts));payload.put("source",text(IN_FIRE.value()));
  records.add(new DurableFact(new DirectorFact(new FactId(UUID.randomUUID()),new FactType("proutlost_director:controlled_fire_wave"),
   List.of(new ScopeRef(ScopeKind.WORLD,world.value())),Optional.of(event),EvidenceStatus.CONFIRMED,payload,true,Optional.empty())));
  q.records=List.copyOf(records);q.applied=true;
  return new SliceResult(Outcome.SUCCEEDED,Set.of(q.operation),observed,0,Set.of(),Set.of(),List.of());
 }
 public List<DurableRecord> resultRecords(){actor();require(pending!=null&&pending.applied,"no applied fire wave");return pending.records;}
 public void committed(OperationToken operation){
  actor();require(pending!=null&&pending.applied&&pending.operation.equals(operation),"exact committed fire wave required");
  pending=null;dirty.clear();contacts.clear();
 }
 public void rejectedBeforeApply(OperationToken operation){
  actor();require(pending!=null&&!pending.applied&&!pending.nativeAttempted&&pending.operation.equals(operation),"only a rejected, unapplied wave may retire");
  // Conservatively consume reservations even when the current permit was rejected.
  mergeHits(pending.hitsAfter);pending=null;
 }
 private Map<PlayerId,Hit> decodeLedger(byte[] bytes){
  var value=object(bytes);require(text(value,"schema").equals("1")&&text(value,"event").equals(event.value().toString())
   &&text(value,"profile").equals(profile.id().value())&&text(value,"mask").equals(maskBlob.reference().hash().value()),"foreign fire hit ledger");
  var entries=((JsonValue.ObjectValue)value.get("players")).values();require(entries.size()<=16,"fire hit ledger bound");var result=new HashMap<PlayerId,Hit>();
  for(var entry:entries.entrySet()){
   var row=((JsonValue.ObjectValue)entry.getValue()).values();double reserved=((JsonValue.Number)row.get("reserved")).value().doubleValue();long tick=count(row,"last_tick");
   require(Double.isFinite(reserved)&&reserved>=0&&reserved<=profile.maxPerPlayer()&&tick>=0,"fire hit budget");
   result.put(new PlayerId(UUID.fromString(entry.getKey())),new Hit(reserved,tick));
  }return result;
 }
 private void mergeHits(Map<PlayerId,Hit> reserved){reserved.forEach((p,h)->{var old=hits.get(p);hits.put(p,old==null?h:new Hit(Math.max(old.reserved(),h.reserved()),Math.max(old.lastTick(),h.lastTick())));});}
 public void restoreCommittedState(byte[] stateBytes,byte[] hitBytes){
  actor();require(pending==null,"cannot restore over an in-flight wave");var value=object(stateBytes);
  require(text(value,"schema").equals("1")&&text(value,"event").equals(event.value().toString())
   &&text(value,"mask").equals(maskBlob.reference().hash().value()),"foreign fire state");
  var entries=((JsonValue.ObjectValue)value.get("energy")).values();require(entries.size()==mask.cells().size(),"fire energy mask");
  var restored=new HashMap<BlockPosition,Long>();for(var p:mask.cells()){
   long e=Long.parseLong(((JsonValue.Text)entries.get(cellKey(p))).value());require(e>=0,"negative recovered energy");restored.put(p,e);}
  energy.clear();energy.putAll(restored);lastTick=count(value,"tick");burnCursor=count(value,"cursor");
  started=((JsonValue.Bool)value.get("started")).value();mergeHits(decodeLedger(hitBytes));stateReady=true;
 }
 /** Every forced PREPARE reserves damage even if its RESULT is missing. Never reapply old damage. */
 public void consumePreparedDamage(PreparedEffect old,BlobBundle blobs){
  actor();require(pending==null,"damage recovery while a wave is pending");arguments(old.intent());
  for(var ref:old.durableBlobs().snapshots())if(ref.codecId().equals(HIT_CODEC)){
   var content=blobs.contents().stream().filter(b->b.reference().equals(ref)).findFirst().orElseThrow();
   if(text(object(content.bytes()),"stage").equals("after"))mergeHits(decodeLedger(content.bytes()));
  }
 }
 /** Forced old damage/energy reservations are conservative accounting, NEVER proof of application.
  * After this method the instance is permanently cleanup-only. It cannot ignite or damage. */
 public void recoverCleanupBudget(PrepareRecord old,BlobBundle blobs){
  actor();require(pending==null&&old.slice().operations().size()==1,"fire recovery plan bound");
  var intent=old.slice().operations().getFirst();arguments(intent);recoveryCleanupOnly=true;
  var forced=DurableBlobReferences.collect(old);
  var supplied=blobs.contents().stream().map(BlobContent::reference).collect(java.util.stream.Collectors.toSet());
  require(supplied.equals(forced),"exact original fire PREPARE hydration");
  BlobContent stateAfter=null,ledgerAfter=null;
  for(var ref:old.durableBlobs().snapshots()){
   if(!ref.codecId().equals(STATE_CODEC)&&!ref.codecId().equals(HIT_CODEC))continue;
   var content=blobs.contents().stream().filter(b->b.reference().equals(ref)).findFirst().orElseThrow();
   if(!text(object(content.bytes()),"stage").equals("after"))continue;
   if(ref.codecId().equals(STATE_CODEC))stateAfter=content;else ledgerAfter=content;
  }
  require(stateAfter!=null&&ledgerAfter!=null,"forced after-budget missing");
  mergeHits(decodeLedger(ledgerAfter.bytes()));
  if(count(object(stateAfter.bytes()),"cursor")>=burnCursor)restoreCommittedState(stateAfter.bytes(),ledgerAfter.bytes());
 }
 public ReconcileResult reconcile(RecoveryProbe probe){
  actor();arguments(probe.durableIntent().intent());boolean allPre=true,allPost=true;int checked=0;
  for(var p:mask.cells()){
   if(!loaded(p))return new ReconcileResult(ReconcileStatus.UNAVAILABLE,probe.loadedProofs(),List.of());
   var k=key(p);var pre=probe.durableIntent().durableBlobs().preStates().get(k);var post=probe.durableIntent().durableBlobs().expectedPostStates().get(k);
   if(pre==null||post==null)continue;checked++;
   var oldRows=probe.durableIntent().durableBlobs().snapshots().stream().filter(ControlledFireWaveBinding::ownerPre)
    .map(r->decodeOwnerPre(probe.blobs().contents().stream().filter(b->b.reference().equals(r)).findFirst().orElseThrow())).filter(o->o.resourceKey().equals(k)).toList();
   var newRows=probe.durableIntent().durableBlobs().snapshots().stream().filter(r->r.codecId().equals(OWNER_POST))
    .map(r->OWNER.decode(probe.blobs().contents().stream().filter(b->b.reference().equals(r)).findFirst().orElseThrow().bytes())).filter(o->o.resourceKey().equals(k)).toList();
   if(oldRows.size()!=1||newRows.size()!=1)return new ReconcileResult(ReconcileStatus.AMBIGUOUS,probe.loadedProofs(),List.of());
   var current=ownership.get().records().get(k);if(current==null)return new ReconcileResult(ReconcileStatus.AMBIGUOUS,probe.loadedProofs(),List.of());
   var before=oldRows.getFirst();var after=newRows.getFirst();
   boolean exactPre=current.equals(before),exactPost=current.equals(after);
   if(!exactPre&&!exactPost){
    boolean player=current.disposition()==OwnershipDisposition.YIELDED_TO_PLAYER
     ||current.playerOverrideEpoch().compareTo(before.playerOverrideEpoch())>0&&current.playerOverrideEpoch().compareTo(after.playerOverrideEpoch())>0;
    return new ReconcileResult(player?ReconcileStatus.PLAYER_OVERRIDE:ReconcileStatus.AMBIGUOUS,probe.loadedProofs(),List.of());
   }
   var actual=state(level(p).getBlockState(pos(p))).reference().hash();
   allPre&=exactPre&&actual.equals(pre.hash());allPost&=exactPost&&actual.equals(post.hash());
  }
  return new ReconcileResult(checked==0?ReconcileStatus.AMBIGUOUS:allPost?ReconcileStatus.SAME_POST:allPre?ReconcileStatus.SAME_PRE_PROVEN:ReconcileStatus.AMBIGUOUS,probe.loadedProofs(),List.of());
 }
 @Override public void close(){actor();ControlledFireBlock.unbind(server,this);closed=true;}
}
