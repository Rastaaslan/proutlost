package fr.proutlost.director.adapter.neoforge.server.effects;

import fr.proutlost.director.adapter.neoforge.server.ownership.ServerOwnershipAttachments;
import fr.proutlost.director.api.codec.*;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.runtime.ControlledWaterPlanner;
import fr.proutlost.director.runtime.ControlledWaterPulsePlanner;
import java.util.*;
import java.util.function.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluids;

/** DRAFT: real controlled fluid, no native propagation. Every changed cell needs J04 authority.
 * IMPORT converts real observed non-falling vanilla water in place; it never mints initial volume.
 * TRANSFER is exclusively the conservative planner. DRAIN is an explicit owned-volume sink.
 * RESTORE returns exact original native states only while the entire imported credit remains owned.
 * The initial basin is real/pre-existing; its stone structure is observed, never constructed here. */
public final class ControlledWaterBinding implements ServerEffectPort.Binding {
 public static final PrimitiveId ID=new PrimitiveId("proutlost:controlled_water");
 public static final CapabilityId CAP=new CapabilityId("fluid.controlled_v1");
 public static final ResourceId IMPORT=new ResourceId("proutlost_director:water_import");
 public static final ResourceId TRANSFER=new ResourceId("proutlost_director:water_transfer");
 public static final ResourceId PULSE=new ResourceId("proutlost_director:water_pulse");
 public static final ResourceId DRAIN=new ResourceId("proutlost_director:water_drain");
 public static final ResourceId RESTORE=new ResourceId("proutlost_director:water_restore");
 public static final ResourceId RESTORE_CODEC=new ResourceId("proutlost_director:controlled_water_restore_v1");
 public static final ResourceId MASK_CODEC=new ResourceId("proutlost_director:controlled_water_mask_v1");
 private static final ResourceId OWNER_CODEC=new ResourceId("proutlost_director:owner_v1");
 private static final ResourceId INTENT_CODEC=new ResourceId("proutlost_director:controlled_water_intent_v1");
 private static final ModelCodec<Footprint> MASK=new ModelCodec<>(Footprint.class);
 private static final ModelCodec<OwnershipRecord> OWNER=new ModelCodec<>(OwnershipRecord.class);
 private static final ModelCodec<EffectIntent> INTENT=new ModelCodec<>(EffectIntent.class);
 private static final ModelCodec<PreparedEffect> PREPARED=new ModelCodec<>(PreparedEffect.class);
 private static final ModelCodec<BlobBundle> BUNDLE=new ModelCodec<>(BlobBundle.class);
 private final Function<DimensionId,ServerLevel> levels;private final Supplier<OwnershipView> ownership;
 private final BiConsumer<BlockPosition,OwnershipRecord> localLedger;private final Supplier<Tick> clock;
 private final ServerOwnershipAttachments attached;
 private final Map<ResourceKey,OwnershipRecord> updates=new LinkedHashMap<>();
 private final Map<OperationToken,BlobContent> restoreTargets=new HashMap<>();
 private Map<String,JsonValue> lastEvidence=Map.of();
 public static final class NoPhysicalChange extends IllegalStateException {
  private final ResourceId recipe;
  public NoPhysicalChange(ResourceId recipe){super("No native water change; do not submit a fabricated physical success");this.recipe=recipe;}
  public ResourceId recipe(){return recipe;}
 }
 public static final class RestoreUnavailable extends IllegalStateException {
  public RestoreUnavailable(String message){super(message);}
 }
 private record Credit(PreparedEffect original,Wave wave){}
 private record Shape(List<BlockPosition> cells,Map<BlockPosition,BlockState> guards,Footprint footprint){}
 private record Node(BlockPosition position,OwnershipRecord owner,BlobContent before,int volume){}
 private record Wave(Shape shape,ControlledWater args,List<Node> nodes,Map<ResourceKey,BlobContent> targets,
                     Map<ResourceKey,Fingerprint> reads,Map<ResourceKey,Epoch> epochs,long before,long after,long moved){}
 public ControlledWaterBinding(Function<DimensionId,ServerLevel> levels,Supplier<OwnershipView> ownership,
   BiConsumer<BlockPosition,OwnershipRecord> localLedger,Supplier<Tick> clock,ServerOwnershipAttachments attached){
  this.levels=Objects.requireNonNull(levels);this.ownership=Objects.requireNonNull(ownership);
  this.localLedger=Objects.requireNonNull(localLedger);this.clock=Objects.requireNonNull(clock);this.attached=Objects.requireNonNull(attached);
 }
 public ProviderMode mode(){return ProviderMode.PRODUCTION;}
 public Set<PrimitiveId> supportedPrimitives(){return Set.of(ID);}
 public Set<CapabilityId> capabilities(){return Set.of(CAP);}
 private static void require(boolean ok,String message){if(!ok)throw new IllegalArgumentException(message);}
 private ServerLevel level(DimensionId dimension){
  var level=Objects.requireNonNull(levels.apply(dimension));require(level.getServer().isSameThread(),"water server actor");
  require(level.dimension().location().toString().equals(dimension.value()),"water dimension");return level;
 }
 private static BlockPos pos(BlockPosition p){return new BlockPos(p.x(),p.y(),p.z());}
 private static ResourceKey key(BlockPosition p){return new ResourceKey(OwnershipDomain.BLOCK,p.dimension(),p.x()+","+p.y()+","+p.z());}
 private static boolean loaded(ServerLevel level,BlockPosition p){return level.getChunkSource().getChunkNow(p.x()>>4,p.z()>>4)!=null
   &&p.y()>level.getMinBuildHeight()&&p.y()<level.getMaxBuildHeight()-2&&level.getWorldBorder().isWithinBounds(pos(p));}
 private static List<BlockPosition> ordered(Collection<BlockPosition> cells){return cells.stream().sorted(Comparator.comparingInt(BlockPosition::x).thenComparingInt(BlockPosition::y).thenComparingInt(BlockPosition::z)).toList();}
 private static Shape shape(Collection<BlockPosition> supplied){
  var cells=ordered(new HashSet<>(supplied));require(cells.size()>=2&&cells.size()<=4,"basin mask2..4 cells");
  var d=cells.getFirst().dimension();
  require(cells.stream().allMatch(p->p.dimension().equals(d)),"one basin dimension");
  int minX=cells.stream().mapToInt(BlockPosition::x).min().orElseThrow(),maxX=cells.stream().mapToInt(BlockPosition::x).max().orElseThrow();
  int minY=cells.stream().mapToInt(BlockPosition::y).min().orElseThrow(),maxY=cells.stream().mapToInt(BlockPosition::y).max().orElseThrow();
  int minZ=cells.stream().mapToInt(BlockPosition::z).min().orElseThrow(),maxZ=cells.stream().mapToInt(BlockPosition::z).max().orElseThrow();
  long columns=(long)(maxX-minX+1)*(maxZ-minZ+1);
  require(columns*(maxY-minY+1)==cells.size(),"complete rectangular interior required");
  require(maxY==minY||(maxY==minY+1&&cells.size()==4&&columns==2),
   "flat2..4 or closed two-column two-layer basin required");
  var guards=new LinkedHashMap<BlockPosition,BlockState>();
  for(int x=minX-1;x<=maxX+1;x++)for(int z=minZ-1;z<=maxZ+1;z++){
   guards.put(new BlockPosition(d,x,minY-1,z),Blocks.STONE.defaultBlockState());
   if(x==minX-1||x==maxX+1||z==minZ-1||z==maxZ+1)
    for(int y=minY;y<=maxY;y++)guards.put(new BlockPosition(d,x,y,z),Blocks.STONE.defaultBlockState());
  }
  for(int x=minX;x<=maxX;x++)for(int z=minZ;z<=maxZ;z++)
   guards.put(new BlockPosition(d,x,maxY+1,z),Blocks.AIR.defaultBlockState());
  var chunks=new HashSet<ChunkRef>();for(var p:cells)chunks.add(new ChunkRef(d,p.x()>>4,p.z()>>4));
  for(var p:guards.keySet())chunks.add(new ChunkRef(d,p.x()>>4,p.z()>>4));
  return new Shape(cells,Map.copyOf(guards),new Footprint(d,chunks,new HashSet<>(cells),
   List.of(new Aabb(d,minX-1,minY,minZ-1,maxX+2,maxY+2,maxZ+2))));
 }
 private static boolean free(ServerOwnershipAttachments attached,ServerLevel level,BlockPosition p){
  return attached.read(level,pos(p)).map(o->o.disposition()==OwnershipDisposition.RELEASED).orElse(true);
 }
 private static boolean contained(ServerLevel level,Shape shape,ServerOwnershipAttachments attached){
  for(var entry:shape.guards().entrySet()){
   var p=entry.getKey();if(!loaded(level,p)||!level.getBlockState(pos(p)).equals(entry.getValue())
    ||!free(attached,level,p))return false;
  }return true;
 }
 /** Exact horizontal vanilla water only: LEVEL0 is eight units, LEVEL1..7 are seven..one.
  * Falling and waterlogged states never become native import credit. */
 public static int nativeVolume(BlockState state){
  if(!state.is(Blocks.WATER)||state.hasBlockEntity()||!state.hasProperty(LiquidBlock.LEVEL))return -1;
  int level=state.getValue(LiquidBlock.LEVEL);if(level<0||level>7)return -1;
  var fluid=state.getFluidState();int volume=8-level;
  if(fluid.isEmpty()||(fluid.getType()!=Fluids.WATER&&fluid.getType()!=Fluids.FLOWING_WATER)
   ||fluid.getAmount()!=volume||fluid.isSource()!=(level==0)
   ||!fluid.hasProperty(FlowingFluid.FALLING)||fluid.getValue(FlowingFluid.FALLING))return -1;
  return volume;
 }
 /** Creates only a real observed mask blob. It does not place water, stone or ownership. */
 public static BlobContent captureMask(ServerLevel level,Collection<BlockPosition> cells,ServerOwnershipAttachments attached){
  require(level.getServer().isSameThread(),"basin mask actor");var shape=shape(cells);
  require(contained(level,shape,attached),"pre-existing unowned STONE basin and free head space required");
  long sources=0,nativeCells=0;
  for(var p:shape.cells()){
   require(loaded(level,p)&&free(attached,level,p),"basin cell loaded/unowned");
   var state=level.getBlockState(pos(p));int nativeUnits=nativeVolume(state);
   require(DirectorControlledWater.volume(state)>=0||nativeUnits>0,"mask permits AIR, controlled water or horizontal native water only");
   if(nativeUnits>0){nativeCells++;if(nativeUnits==8)sources++;}
  }
  if(nativeCells>0)require(nativeCells==shape.cells().size()&&sources>=1,
   "native import mask requires water in every cell and at least one real source; no AIR or controlled mix");
  return BlobContent.capture(MASK_CODEC,1,MASK.encode(shape.footprint()));
 }
 private static BlobContent find(BlobBundle blobs,BlobRef ref){return blobs.contents().stream().filter(b->b.reference().equals(ref)).findFirst().orElseThrow();}
 private static BlobContent findState(BlobBundle blobs,Fingerprint hash){return blobs.contents().stream().filter(b->b.reference().codecId().equals(BlockTransformBinding.STATE_CODEC)&&b.reference().hash().equals(hash)).findFirst().orElseThrow();}
 private static Shape decodeMask(BlobBundle blobs,BlobRef ref){
  require(ref.codecId().equals(MASK_CODEC)&&ref.codecVersion()==1,"mask codec");var footprint=MASK.decode(find(blobs,ref).bytes());
  var shape=shape(footprint.cells());require(footprint.equals(shape.footprint()),"mask shape/chunk binding");return shape;
 }
 private static Map<String,JsonValue> object(JsonValue v){return ((JsonValue.ObjectValue)v).values();}
 private static int volume(BlobContent blob,boolean allowVanilla){
  require(blob.reference().codecId().equals(BlockTransformBinding.STATE_CODEC)&&blob.reference().codecVersion()==1,"water state codec");
  var values=object(CanonicalJsonV1.decode(blob.bytes()));var id=((JsonValue.Text)values.get("block")).value();
  var properties=object(values.get("properties"));
  if(id.equals("minecraft:air")||id.equals("minecraft:cave_air")||id.equals("minecraft:void_air"))return 0;
  var controlled=BuiltInRegistries.BLOCK.getKey(DirectorControlledWater.block()).toString();
  if(!id.equals(controlled)&&!(allowVanilla&&id.equals("minecraft:water")))return -1;
  require(properties.keySet().equals(Set.of("level")),"water state properties");
  int level=Integer.parseInt(((JsonValue.Text)properties.get("level")).value());
  if(id.equals("minecraft:water"))return level>=0&&level<=7?
   nativeVolume(Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL,level)):-1;
  return level>=0&&level<=7?8-level:-1;
 }
 private static boolean nativeState(BlobContent blob){
  return ((JsonValue.Text)object(CanonicalJsonV1.decode(blob.bytes())).get("block")).value().equals("minecraft:water");
 }
 private static BlockState targetState(BlobContent blob,boolean allowVanilla){
  int v=volume(blob,allowVanilla);require(v>=0,"foreign target fluid");
  var id=((JsonValue.Text)object(CanonicalJsonV1.decode(blob.bytes())).get("block")).value();
  BlockState state;
  if(id.equals("minecraft:water"))state=Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL,8-v);
  else if(id.equals("minecraft:cave_air"))state=Blocks.CAVE_AIR.defaultBlockState();
  else if(id.equals("minecraft:void_air"))state=Blocks.VOID_AIR.defaultBlockState();
  else if(id.equals("minecraft:air"))state=Blocks.AIR.defaultBlockState();
  else state=DirectorControlledWater.stateForVolume(v);
  require(BlockTransformBinding.stateBlob(state).reference().equals(blob.reference()),"target native state must preserve exact sealed bytes");
  return state;
 }
 private static boolean active(OwnershipRecord owner){return owner.disposition()==OwnershipDisposition.DIRECTOR_ACTIVE&&!owner.ownerTokens().isEmpty();}
 private Wave compute(EffectIntent intent,Shape shape,List<Node> nodes,Credit credit){
  require(intent.primitive().equals(ID)&&intent.version()==1&&intent.typedArgs() instanceof ControlledWater,"typed controlled water required");
  var a=(ControlledWater)intent.typedArgs();require(a.maxLevel()==8&&a.transferUnits()<=Integer.MAX_VALUE,"water level/budget");
  boolean importing=a.recipe().equals(IMPORT),draining=a.recipe().equals(DRAIN),restoring=a.recipe().equals(RESTORE),pulsing=a.recipe().equals(PULSE);
  require(importing||draining||restoring||pulsing||a.recipe().equals(TRANSFER),"registered water recipe");
  if(shape.cells().stream().mapToInt(BlockPosition::y).distinct().count()>1)
   require(importing||draining||restoring,"two-layer basin supports import and exact cleanup only");
  if(draining||restoring)require(a.transferUnits()==0,"cleanup has no new incoming transfer budget");
  if(restoring)return restoreWave(intent,shape,nodes,Objects.requireNonNull(credit,"restore credit required"));
  if(pulsing)validatePulseCredit(intent,shape,nodes,Objects.requireNonNull(credit,"pulse credit required"));
  else require(credit==null,"unexpected original import credit");
  var cells=new HashMap<BlockPosition,ControlledWaterPlanner.Cell>();long before=0,importedUnits=0;int imported=0;
  for(var n:nodes){
   if(n.volume()>=0){cells.put(n.position(),new ControlledWaterPlanner.Cell(n.volume(),n.owner().epoch(),!active(n.owner())));before+=n.volume();}
   if(importing){
    int nativeUnits=nativeState(n.before())?volume(n.before(),true):-1;
    require(active(n.owner())&&nativeUnits>0&&n.volume()==nativeUnits
     &&n.owner().expectedPostHash().equals(n.before().reference().hash()),"import needs owned actual horizontal native water in every cell; no AIR/controlled mix");
    importedUnits+=nativeUnits;if(nativeUnits==8)imported++;
   }else if(!draining&&active(n.owner()))require(n.volume()>=0&&n.owner().expectedPostHash().equals(n.before().reference().hash()),"changed/foreign owned water cell");
  }
  Map<BlockPosition,ControlledWaterPlanner.Cell> after=cells;long moved=0;
  if(importing)require(imported>=1&&imported<=4&&before==importedUnits&&a.transferUnits()>=importedUnits,"actual observed reservoir volume missing or over budget");
  else if(pulsing){
   var plan=ControlledWaterPulsePlanner.plan(cells,new HashSet<>(shape.cells()),8,Math.toIntExact(a.transferUnits()));
   after=plan.after();moved=plan.transfers().stream().mapToLong(ControlledWaterPlanner.Transfer::units).sum();
  }else if(!draining){
   long seed=intent.operationToken().value().getMostSignificantBits()^intent.operationToken().value().getLeastSignificantBits();
   var plan=ControlledWaterPlanner.plan(cells,new HashSet<>(shape.cells()),8,Math.toIntExact(a.transferUnits()),seed);
   after=plan.after();moved=plan.transfers().stream().mapToLong(ControlledWaterPlanner.Transfer::units).sum();
  }
  var targets=new LinkedHashMap<ResourceKey,BlobContent>();var reads=new HashMap<ResourceKey,Fingerprint>();var epochs=new HashMap<ResourceKey,Epoch>();long totalAfter=0;
  for(var n:nodes){
   int v=n.volume();var target=n.before();
   if(active(n.owner())&&v>=0&&n.owner().expectedPostHash().equals(n.before().reference().hash())){
    if(draining)v=0;else if(after.containsKey(n.position()))v=after.get(n.position()).volume();
    target=BlockTransformBinding.stateBlob(DirectorControlledWater.stateForVolume(v));
   }
   if(v>=0)totalAfter+=v;var k=key(n.position());reads.put(k,n.before().reference().hash());epochs.put(k,n.owner().epoch());
   if(!target.reference().hash().equals(n.before().reference().hash()))targets.put(k,target);
  }
  if(!draining)require(before==totalAfter,"nonconservative import/transfer");
  return new Wave(shape,a,List.copyOf(nodes),Map.copyOf(targets),Map.copyOf(reads),Map.copyOf(epochs),before,totalAfter,moved);
 }
 /** A root actor registers an inverse only from the original durable import PREPARE and its hydrated bytes.
  * The sealed original is included in the inverse PREPARE, so reboot never relies on this volatile map. */
 public void registerRestore(EffectIntent inverse,PreparedEffect originalImport,BlobBundle originalBlobs){
  require(inverse.typedArgs() instanceof ControlledWater a&&a.recipe().equals(RESTORE),"restore intent");
  registerCredit(inverse,originalImport,originalBlobs);
 }
 /** Stable token is mandatory: J04 operation tombstones enforce single pulse even after stale world persistence. */
 public static OperationToken pulseToken(EventInstanceId instance){
  return new OperationToken(UUID.nameUUIDFromBytes(("proutlost_director:water_pulse/"+instance.value())
   .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
 }
 public void registerPulse(EffectIntent pulse,PreparedEffect originalImport,BlobBundle originalBlobs){
  require(pulse.typedArgs() instanceof ControlledWater a&&a.recipe().equals(PULSE)&&a.transferUnits()==1
   &&pulse.operationToken().equals(pulseToken(pulse.instanceId())),"one durable pulse token/one unit required");
  registerCredit(pulse,originalImport,originalBlobs);
 }
 private void registerCredit(EffectIntent inverse,PreparedEffect originalImport,BlobBundle originalBlobs){
  var original=durable(originalImport,originalBlobs);
  require(original.args().recipe().equals(IMPORT)&&inverse.instanceId().equals(originalImport.intent().instanceId())
   &&inverse.expectedFootprint().equals(originalImport.intent().expectedFootprint())
   &&((ControlledWater)inverse.typedArgs()).propagationMask().equals(original.args().propagationMask()),"original import lineage");
  var body=Map.<String,JsonValue>of("original_plan",new JsonValue.Text(Base64.getEncoder().encodeToString(PREPARED.encode(originalImport))),
   "original_blobs",new JsonValue.Text(Base64.getEncoder().encodeToString(BUNDLE.encode(originalBlobs))));
  var blob=BlobContent.capture(RESTORE_CODEC,1,CanonicalJsonV1.encode(new JsonValue.ObjectValue(body)));
  require(restoreTargets.size()<128&&restoreTargets.putIfAbsent(inverse.operationToken(),blob)==null,"restore registration capacity/duplicate");
 }
 private Credit credit(BlobContent blob){
  require(blob.reference().codecId().equals(RESTORE_CODEC)&&blob.reference().codecVersion()==1,"restore credit codec");
  var values=object(CanonicalJsonV1.decode(blob.bytes()));require(values.keySet().equals(Set.of("original_plan","original_blobs")),"restore credit fields");
  var plan=PREPARED.decode(Base64.getDecoder().decode(((JsonValue.Text)values.get("original_plan")).value()));
  require(plan.intent().typedArgs() instanceof ControlledWater a&&a.recipe().equals(IMPORT),"restore credit must derive from import");
  var bundle=BUNDLE.decode(Base64.getDecoder().decode(((JsonValue.Text)values.get("original_blobs")).value()));
  return new Credit(plan,durable(plan,bundle));
 }
 private static boolean lineage(OwnershipRecord original,OwnershipRecord current){
  return active(current)&&original.header().id().equals(current.header().id())
   &&original.resourceKey().equals(current.resourceKey())&&original.ownerTokens().equals(current.ownerTokens())
   &&original.epoch().equals(current.epoch())&&original.creationMutationId().equals(current.creationMutationId())
   &&original.playerOverrideEpoch().equals(current.playerOverrideEpoch())&&original.exceptionContractId().equals(current.exceptionContractId())
   &&current.header().revision().value()>=original.header().revision().value();
 }
 private void validatePulseCredit(EffectIntent intent,Shape shape,List<Node> nodes,Credit credit){
  require(intent.operationToken().equals(pulseToken(intent.instanceId()))
   &&((ControlledWater)intent.typedArgs()).transferUnits()==1,"single one-unit manual pulse required");
  var original=credit.wave();var originalIntent=credit.original().intent();
  require(intent.instanceId().equals(originalIntent.instanceId())&&shape.footprint().equals(original.shape().footprint())
   &&((ControlledWater)intent.typedArgs()).propagationMask().equals(original.args().propagationMask()),"pulse original instance/mask");
  var originals=new HashMap<BlockPosition,Node>();original.nodes().forEach(n->originals.put(n.position(),n));long total=0;
  for(var n:nodes){
   var old=Objects.requireNonNull(originals.get(n.position()));
   require(n.volume()>=0&&lineage(old.owner(),n.owner())
    &&n.owner().header().revision().value()==old.owner().header().revision().value()+1
    &&n.owner().expectedPostHash().equals(n.before().reference().hash())
    &&n.before().reference().hash().equals(credit.original().expectedPost().get(key(n.position()))),
    "pulse only immediately after exact imported POST; never repeat or reacquire");
   total+=n.volume();
  }
  require(total==original.before(),"pulse original volume credit changed");
 }
 private Wave restoreWave(EffectIntent intent,Shape shape,List<Node> nodes,Credit credit){
  var original=credit.wave();var originalIntent=credit.original().intent();
  require(intent.instanceId().equals(originalIntent.instanceId())&&shape.footprint().equals(original.shape().footprint())
   &&((ControlledWater)intent.typedArgs()).propagationMask().equals(original.args().propagationMask()),"restore original instance/mask");
  var originals=new HashMap<BlockPosition,Node>();original.nodes().forEach(n->originals.put(n.position(),n));
  var targets=new LinkedHashMap<ResourceKey,BlobContent>();var reads=new HashMap<ResourceKey,Fingerprint>();var epochs=new HashMap<ResourceKey,Epoch>();long before=0,after=0;
  for(var n:nodes){
   var old=Objects.requireNonNull(originals.get(n.position()));
   if(n.volume()<0||!lineage(old.owner(),n.owner())||!n.owner().expectedPostHash().equals(n.before().reference().hash()))
    throw new RestoreUnavailable("Import credit no longer fully owned; use explicit owned-volume drain");
   before+=n.volume();after+=old.volume();var k=key(n.position());
   reads.put(k,n.before().reference().hash());epochs.put(k,n.owner().epoch());
   if(!n.before().reference().hash().equals(old.before().reference().hash()))targets.put(k,old.before());
  }
  if(before!=original.before()||after!=original.before())throw new RestoreUnavailable("Imported volume was withdrawn or lost; never recreate native sources");
  return new Wave(shape,(ControlledWater)intent.typedArgs(),List.copyOf(nodes),Map.copyOf(targets),Map.copyOf(reads),Map.copyOf(epochs),before,after,0);
 }
 private static OwnershipRecord capturedOwner(PreparedEffect plan,BlobBundle blobs,ResourceKey key){
  var found=plan.durableBlobs().snapshots().stream().filter(r->r.codecId().equals(OWNER_CODEC)&&r.codecVersion()==1)
   .map(r->OWNER.decode(find(blobs,r).bytes())).filter(o->o.resourceKey().equals(key)).toList();
  require(found.size()==1,"water owner missing/ambiguous");return found.getFirst();
 }
 public EffectPreparation capture(EffectIntent intent,PlanningSnapshot context){
  require(intent.typedArgs() instanceof ControlledWater,"ControlledWater args");var a=(ControlledWater)intent.typedArgs();
  var shape=decodeMask(context.blobs(),a.propagationMask());var level=level(shape.footprint().dimension());
  require(intent.expectedFootprint().equals(shape.footprint()),"whole basin mask footprint required");
  if(!a.recipe().equals(DRAIN))require(contained(level,shape,attached),"basin containment changed");
  var nativeCells=new HashMap<BlockPosition,TerrainCellSnapshot>();context.snapshot().context().terrain().forEach(t->nativeCells.putAll(t.cells()));
  var bytes=new HashMap<BlobRef,BlobContent>();var snapshots=new HashSet<BlobRef>();var nodes=new ArrayList<Node>();
  var mask=find(context.blobs(),a.propagationMask());bytes.put(mask.reference(),mask);snapshots.add(mask.reference());
  for(var p:shape.cells()){
   require(loaded(level,p),"water cell unloaded");var physical=level.getBlockState(pos(p));var state=BlockTransformBinding.stateBlob(physical);
   var owner=Objects.requireNonNull(context.ownership().records().get(key(p)),"mask ownership missing");
   var ownerBlob=BlobContent.capture(OWNER_CODEC,1,OWNER.encode(owner));var observed=Objects.requireNonNull(nativeCells.get(p),"water cell unobserved");
   require(observed.stateFingerprint().equals(state.reference().hash()),"water observation stale");
   int v=volume(state,a.recipe().equals(IMPORT));
   if(a.recipe().equals(IMPORT))require(v>0&&v==nativeVolume(physical),"native import BlockState/FluidState amount mismatch, falling or foreign input");
   if(active(owner)&&!a.recipe().equals(DRAIN))require(observed.knownContent()&&!observed.protectedContent(),"unknown/protected water input");
   nodes.add(new Node(p,owner,state,v));for(var b:List.of(state,ownerBlob)){bytes.put(b.reference(),b);snapshots.add(b.reference());}
  }
  BlobContent restore=null;Credit credit=null;
  if(a.recipe().equals(RESTORE)||a.recipe().equals(PULSE)){restore=Objects.requireNonNull(restoreTargets.get(intent.operationToken()),"register original import before restore/pulse");
   credit=credit(restore);bytes.put(restore.reference(),restore);snapshots.add(restore.reference());}
  var wave=compute(intent,shape,nodes,credit);if(wave.targets().isEmpty())throw new NoPhysicalChange(a.recipe());
  var reads=new HashMap<>(wave.reads());
  if(!a.recipe().equals(DRAIN))for(var e:shape.guards().entrySet()){
   var b=BlockTransformBinding.stateBlob(e.getValue());bytes.put(b.reference(),b);snapshots.add(b.reference());reads.put(key(e.getKey()),b.reference().hash());
  }
  var pre=new HashMap<ResourceKey,BlobRef>();var post=new HashMap<ResourceKey,BlobRef>();var expected=new HashMap<ResourceKey,Fingerprint>();var claims=new ArrayList<ResourceClaim>();
  for(var n:nodes)if(wave.targets().containsKey(key(n.position()))){
   var k=key(n.position());var target=wave.targets().get(k);require(active(n.owner()),"yielded water cannot change");
   pre.put(k,n.before().reference());post.put(k,target.reference());expected.put(k,target.reference().hash());bytes.put(target.reference(),target);
   claims.add(new ResourceClaim(k,ClaimPolicy.EXCLUSIVE,new BudgetVector(0,0,0,0,0,1,0,0,0,0),Optional.empty(),n.owner().epoch()));
  }
  var intentBlob=BlobContent.capture(INTENT_CODEC,1,INTENT.encode(intent));bytes.put(intentBlob.reference(),intentBlob);
  var read=new ReadSet(context.snapshot().scopeRevisions(),wave.epochs(),reads);var cost=new BudgetVector(0,0,0,0,0,claims.size(),0,0,0,0);
  var refs=new EffectBlobs(snapshots,intentBlob.reference(),intentBlob.reference(),pre,post);
  var plan=new PreparedEffect(intent,context.snapshot(),claims,read,expected,cost,List.of(),capabilities(),refs);
  restoreTargets.remove(intent.operationToken());
  return new EffectPreparation(intent,new ValidationResult(true,List.of(),read),Optional.of(plan),new BlobBundle(List.copyOf(bytes.values())));
 }
 private Wave durable(PreparedEffect plan,BlobBundle blobs){
  require(plan.capabilities().equals(capabilities()),"water capabilities");
  require(plan.durableBlobs().preparedPlan().codecId().equals(INTENT_CODEC)&&plan.durableBlobs().preparedPlan().codecVersion()==1
   &&plan.durableBlobs().stopPlan().equals(plan.durableBlobs().preparedPlan())&&INTENT.decode(find(blobs,plan.durableBlobs().preparedPlan()).bytes()).equals(plan.intent()),"water intent blob");
  var a=(ControlledWater)plan.intent().typedArgs();var shape=decodeMask(blobs,a.propagationMask());
  require(plan.intent().expectedFootprint().equals(shape.footprint()),"water footprint expanded");
  var nodes=new ArrayList<Node>();for(var p:shape.cells()){
   var owner=capturedOwner(plan,blobs,key(p));var b=findState(blobs,Objects.requireNonNull(plan.preconditions().fingerprints().get(key(p))));
   nodes.add(new Node(p,owner,b,volume(b,a.recipe().equals(IMPORT))));
  }
  var creditRefs=plan.durableBlobs().snapshots().stream().filter(r->r.codecId().equals(RESTORE_CODEC)).toList();
  require(creditRefs.size()==(a.recipe().equals(RESTORE)||a.recipe().equals(PULSE)?1:0),"original import credit missing/unexpected");
  var wave=compute(plan.intent(),shape,nodes,creditRefs.isEmpty()?null:credit(find(blobs,creditRefs.getFirst())));var expected=new HashMap<ResourceKey,Fingerprint>();
  wave.targets().forEach((k,v)->expected.put(k,v.reference().hash()));
  require(plan.expectedPost().equals(expected)&&plan.preconditions().epochs().equals(wave.epochs()),"water PRE/POST epochs");
  require(plan.claims().size()==expected.size()&&plan.claims().stream().map(ResourceClaim::resource).collect(java.util.stream.Collectors.toSet()).equals(expected.keySet()),"water claims exact changed cells");
  for(var n:nodes)if(expected.containsKey(key(n.position()))){
   var k=key(n.position());var claim=plan.claims().stream().filter(c->c.resource().equals(k)).findFirst().orElseThrow();
   require(claim.policy()==ClaimPolicy.EXCLUSIVE&&claim.mergeFunction().isEmpty()&&claim.cost().equals(new BudgetVector(0,0,0,0,0,1,0,0,0,0))&&claim.expectedEpoch().equals(n.owner().epoch())
    &&plan.durableBlobs().preStates().get(k).equals(n.before().reference())
    &&plan.durableBlobs().expectedPostStates().get(k).equals(wave.targets().get(k).reference()),"water claim/blob binding");
  }
  var reads=new HashMap<>(wave.reads());if(!a.recipe().equals(DRAIN))shape.guards().forEach((p,s)->reads.put(key(p),BlockTransformBinding.stateBlob(s).reference().hash()));
  require(plan.preconditions().fingerprints().equals(reads),"water input/guard hashes");
  var snapshots=new HashSet<BlobRef>();snapshots.add(a.propagationMask());snapshots.addAll(creditRefs);
  for(var n:nodes){snapshots.add(n.before().reference());snapshots.add(BlobContent.capture(OWNER_CODEC,1,OWNER.encode(n.owner())).reference());}
  if(!a.recipe().equals(DRAIN))shape.guards().values().forEach(s->snapshots.add(BlockTransformBinding.stateBlob(s).reference()));
  require(plan.durableBlobs().snapshots().equals(snapshots)&&plan.budgetEstimate().equals(new BudgetVector(0,0,0,0,0,expected.size(),0,0,0,0)),"water snapshots/budget");
  require(plan.durableBlobs().preStates().keySet().equals(expected.keySet())&&plan.durableBlobs().expectedPostStates().keySet().equals(expected.keySet()),"water changed refs");
  return wave;
 }
 public boolean revalidate(PreparedEffect plan,BlobBundle blobs){
  try{var wave=durable(plan,blobs);var level=level(wave.shape().footprint().dimension());
   if(!wave.args().recipe().equals(DRAIN)&&!contained(level,wave.shape(),attached))return false;
   for(var n:wave.nodes())if(!loaded(level,n.position())||!n.owner().equals(ownership.get().records().get(key(n.position())))
    ||!attached.read(level,pos(n.position())).equals(Optional.of(n.owner()))
    ||!BlockTransformBinding.stateBlob(level.getBlockState(pos(n.position()))).reference().hash().equals(n.before().reference().hash()))return false;
   return true;
  }catch(RuntimeException invalid){return false;}
 }
 public SliceResult apply(PreparedEffect plan,BlobBundle blobs){
  var wave=durable(plan,blobs);require(revalidate(plan,blobs),"water changed since PREPARE");
  updates.clear();var observed=new LinkedHashMap<ResourceKey,Fingerprint>();var conflicts=new HashSet<BlockPosition>();int count=0;
  var level=level(wave.shape().footprint().dimension());
  // Debit before credit. A partial wave can lose attributable volume but must never credit it first.
  var applyOrder=wave.nodes().stream().filter(n->wave.targets().containsKey(key(n.position())))
   .sorted(Comparator.comparingInt((Node n)->volume(wave.targets().get(key(n.position())),wave.args().recipe().equals(RESTORE))-n.volume())).toList();
  for(var n:applyOrder){
   var k=key(n.position());var target=wave.targets().get(k);
   BlockState desired=targetState(target,wave.args().recipe().equals(RESTORE));
   if(!level.setBlock(pos(n.position()),desired,Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE|Block.UPDATE_SUPPRESS_DROPS)){conflicts.add(n.position());break;}
   count++;var actual=BlockTransformBinding.stateBlob(level.getBlockState(pos(n.position()))).reference().hash();observed.put(k,actual);
   if(!actual.equals(target.reference().hash())||!n.owner().equals(ownership.get().records().get(k))){conflicts.add(n.position());break;}
   var old=n.owner();var h=old.header();var next=new OwnershipRecord(new AggregateHeader(h.schemaVersion(),h.id(),h.revision().plus(1),h.lastSequence(),clock.get()),
    k,old.ownerTokens(),old.epoch(),old.disposition(),old.creationMutationId(),actual,old.playerOverrideEpoch(),old.exceptionContractId());
   localLedger.accept(n.position(),next);updates.put(k,next);
  }
  boolean complete=conflicts.isEmpty()&&count==wave.targets().size();
  lastEvidence=Map.of("recipe",new JsonValue.Text(wave.args().recipe().value()),"accounted_units_before",new JsonValue.Text(Long.toString(wave.before())),
   "accounted_units_planned_after",new JsonValue.Text(Long.toString(wave.after())),"transferred_units",new JsonValue.Text(Long.toString(wave.moved())),
   "complete",new JsonValue.Bool(complete),"mask_hash",new JsonValue.Text(wave.args().propagationMask().hash().value()));
  return new SliceResult(complete?Outcome.SUCCEEDED:Outcome.PARTIAL,count>0?Set.of(plan.intent().operationToken()):Set.of(),observed,
   wave.targets().size()-count,Set.of(),conflicts,List.of());
 }
 /** Persist ONLY these newer rows in RESULT. Unchanged snapshot owners must not be appended again. */
 public Map<ResourceKey,OwnershipRecord> updatedOwners(){return Map.copyOf(updates);}
 public Map<String,JsonValue> volumeEvidence(){return Map.copyOf(lastEvidence);}
 public ReconcileResult reconcile(RecoveryProbe probe){
  try{var plan=probe.durableIntent();var wave=durable(plan,probe.blobs());var level=level(wave.shape().footprint().dimension());boolean pre=true,post=true;
   for(var n:wave.nodes())if(wave.targets().containsKey(key(n.position()))){
    if(!loaded(level,n.position()))return new ReconcileResult(ReconcileStatus.UNAVAILABLE,probe.loadedProofs(),List.of());
    var k=key(n.position());var current=ownership.get().records().get(k);
    if(current==null)return new ReconcileResult(ReconcileStatus.AMBIGUOUS,probe.loadedProofs(),List.of());
    if(current.disposition()==OwnershipDisposition.YIELDED_TO_PLAYER||!current.epoch().equals(n.owner().epoch())||!current.playerOverrideEpoch().equals(n.owner().playerOverrideEpoch()))
     return new ReconcileResult(ReconcileStatus.PLAYER_OVERRIDE,probe.loadedProofs(),List.of());
    var actual=BlockTransformBinding.stateBlob(level.getBlockState(pos(n.position()))).reference().hash();
    var proofs=probe.loadedProofs().stream().filter(p->p.resource().equals(k)).toList();
    if(proofs.size()!=1||!BlockOwnershipFence.confirmedAtCurrentOwner(proofs.getFirst(),n.owner(),current,actual))
     return new ReconcileResult(ReconcileStatus.AMBIGUOUS,probe.loadedProofs(),List.of());
    pre&=BlockOwnershipFence.exactPre(n.owner(),current)&&actual.equals(n.before().reference().hash())&&proofs.getFirst().localAbsenceProven();
    post&=BlockOwnershipFence.exactPost(n.owner(),current,plan.expectedPost().get(k))&&actual.equals(plan.expectedPost().get(k));
   }
   return new ReconcileResult(post?ReconcileStatus.SAME_POST:pre?ReconcileStatus.SAME_PRE_PROVEN:ReconcileStatus.AMBIGUOUS,probe.loadedProofs(),List.of());
  }catch(RuntimeException invalid){return new ReconcileResult(ReconcileStatus.AMBIGUOUS,probe.loadedProofs(),List.of());}
 }
}
