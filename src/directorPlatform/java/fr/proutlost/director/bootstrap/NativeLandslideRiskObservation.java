package fr.proutlost.director.bootstrap;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.adapter.neoforge.server.effects.BlockTransformBinding;
import fr.proutlost.director.adapter.neoforge.server.ownership.ServerOwnershipAttachments;
import java.util.*;
import java.util.function.Consumer;
import fr.proutlost.director.memory.MemoryMath;
import fr.proutlost.director.memory.SupportGeometry;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.FarmBlock;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;

/** Read-only loaded relief evidence, independent of event eligibility.
 * The legacy TEST_ONLY risk proxy is min(1,max adjacent surface drop/8).
 * Production uses the separately approved four-factor native measurement below,
 * never the legacy proxy and never an invented weather/moisture history.
 * Unknown/cold or oversized regional geometry remains unknown; no chunk loads.
 */
public final class NativeLandslideRiskObservation {
 private static final ResourceId RISK=new ResourceId("proutlost:landslide");
 private static final ResourceId RAIN=new ResourceId("proutlost_director:landslide/rain"),
  SATURATION=new ResourceId("proutlost_director:landslide/saturation"),
  SLOPE=new ResourceId("proutlost_director:landslide/slope"),
  ACTIVITY=new ResourceId("proutlost_director:landslide/recent_activity");
 private static final Set<ResourceId> FACTORS=Set.of(RISK,RAIN,SATURATION,SLOPE,ACTIVITY);
 private static final ServerOwnershipAttachments OWNERS=new ServerOwnershipAttachments(64);
 private static final Map<MinecraftServer,Watch> WATCHES=new WeakHashMap<>();
 private NativeLandslideRiskObservation(){}
 private record Surface(int x,int z,int y){}
 private record Measured(RegionSnapshot region,TerrainSnapshot terrain){}
 private record Geometry(DimensionId dimension,List<Aabb> boxes,List<SupportDisc> discs){
  private Geometry{boxes=List.copyOf(boxes);discs=List.copyOf(discs);}
  boolean contains(int x,int z){
   for(var b:boxes)if(x>=b.minX()&&x<b.maxXExclusive()&&z>=b.minZ()&&z<b.maxZExclusive())return true;
   for(var d:discs)if(StrictMath.hypot((double)x-d.center().x(),(double)z-d.center().z())<=d.radius())return true;
   return false;
  }
 }
 private record Stamp(WorldId world,Epoch boot,Fingerprint config,Revision region,Geometry geometry,Optional<DecayValue> activity){}
 private record Physical(TerrainSnapshot terrain,Set<ChunkRef> chunks,double rain,double saturation,double slope,boolean normalizedKnown,int samples,int spacing,long areaUpperBound){}
 private record Cached(Stamp stamp,long tick,long editEpoch,int rainBits,boolean raining,Physical physical){}
 /** Actor-owned cache only; no historical rain, inferred dry period or persistent actor data. */
 private static final class Watch {
  final MinecraftServer server;final Map<RegionId,Cached> values=new LinkedHashMap<>();
  final Map<DimensionId,Long> edits=new HashMap<>();
  // BlockEvent is abstract and NeoForge rejects registering it. Each method reference has a concrete event signature.
  final Consumer<BlockEvent.BreakEvent> broken=this::broken;
  final Consumer<BlockEvent.EntityPlaceEvent> placed=this::placed; // includes EntityMultiPlaceEvent
  final Consumer<BlockEvent.NeighborNotifyEvent> neighbors=this::neighbors;
  final Consumer<BlockEvent.FluidPlaceBlockEvent> fluid=this::fluid;
  final Consumer<BlockEvent.FarmlandTrampleEvent> trampled=this::trampled;
  final Consumer<BlockEvent.PortalSpawnEvent> portal=this::portal;
  final Consumer<BlockEvent.BlockToolModificationEvent> tool=this::tool;
  final Consumer<ServerStoppedEvent> stopped=this::stopped;
  volatile boolean invalid;boolean closed;
  Watch(MinecraftServer server){
   this.server=server;
   try{
    NeoForge.EVENT_BUS.addListener(broken);NeoForge.EVENT_BUS.addListener(placed);
    NeoForge.EVENT_BUS.addListener(neighbors);NeoForge.EVENT_BUS.addListener(fluid);
    NeoForge.EVENT_BUS.addListener(trampled);NeoForge.EVENT_BUS.addListener(portal);
    NeoForge.EVENT_BUS.addListener(tool);NeoForge.EVENT_BUS.addListener(stopped);
   }catch(RuntimeException|Error failure){
    // computeIfAbsent has not published this Watch; remove every potentially registered callback.
    try{close();}catch(RuntimeException|Error cleanup){if(failure!=cleanup)failure.addSuppressed(cleanup);}
    throw failure;
   }
  }
  void broken(BlockEvent.BreakEvent event){changed(event);}
  void placed(BlockEvent.EntityPlaceEvent event){changed(event);}
  void neighbors(BlockEvent.NeighborNotifyEvent event){changed(event);}
  void fluid(BlockEvent.FluidPlaceBlockEvent event){changed(event);}
  void trampled(BlockEvent.FarmlandTrampleEvent event){changed(event);}
  void portal(BlockEvent.PortalSpawnEvent event){changed(event);}
  void tool(BlockEvent.BlockToolModificationEvent event){changed(event);}
  void changed(BlockEvent event){if(!closed&&event.getLevel() instanceof ServerLevel l&&l.getServer()==server){
   if(!server.isSameThread()){invalid=true;return;}
   edits.merge(new DimensionId(l.dimension().location().toString()),1L,Math::addExact);
  }}
  void stopped(ServerStoppedEvent event){if(event.getServer()==server){WATCHES.remove(server);close();}}
  private void close(){
   closed=true;values.clear();edits.clear();Throwable failure=null;
   for(Object listener:List.of(broken,placed,neighbors,fluid,trampled,portal,tool,stopped)){
    try{NeoForge.EVENT_BUS.unregister(listener);}
    catch(RuntimeException|Error cleanup){if(failure==null)failure=cleanup;else if(failure!=cleanup)failure.addSuppressed(cleanup);}
   }
   if(failure instanceof RuntimeException rejected)throw rejected;
   if(failure instanceof Error rejected)throw rejected;
  }
  long epoch(DimensionId dimension){return edits.getOrDefault(dimension,0L);}
  void put(RegionId id,Cached next,int cellLimit){
   values.remove(id);while(!values.isEmpty()&&(values.size()>=64||values.values().stream().mapToInt(v->v.physical().terrain().cells().size()).sum()+next.physical().terrain().cells().size()>cellLimit))values.remove(values.keySet().iterator().next());
   if(next.physical().terrain().cells().size()<=cellLimit)values.put(id,next);
  }
 }
 private static Geometry geometry(RegionSnapshot region,MemoryView memory){
  var record=region.region();var boxes=new ArrayList<>(record.supportShape().boxes());
  for(var location:memory.locations().values().stream().sorted(Comparator.comparing(LocationRecord::locationId)).toList()){
   if(!location.active()||!location.dimension().equals(record.dimension()))continue;
   boolean associated=location.anchorIds().stream().anyMatch(record.anchorIds()::contains)
    ||memory.anchors().values().stream().anyMatch(a->a.active()&&a.regionIds().contains(record.regionId())&&a.locationId().equals(Optional.of(location.locationId())));
   if(associated)boxes.add(location.bounds());
  }
  for(var b:boxes)if(!b.dimension().equals(record.dimension()))throw new IllegalArgumentException("foreign risk support");
  for(var d:record.supportShape().discs())if(!d.center().dimension().equals(record.dimension()))throw new IllegalArgumentException("foreign risk support");
  return new Geometry(record.dimension(),boxes,record.supportShape().discs());
 }
 private record Sampling(Set<Long> points,int spacing,long areaUpperBound){}
 private static boolean neighbourhood(Set<Long> points,Geometry geometry,int x,int z,int limit){
  if(!geometry.contains(x,z))return true;
  for(int[] offset:new int[][]{{0,0},{1,0},{0,1}}){long px=(long)x+offset[0],pz=(long)z+offset[1];if(px>Integer.MAX_VALUE||pz>Integer.MAX_VALUE)return false;
   if(geometry.contains((int)px,(int)pz)&&!add(points,(int)px,(int)pz,limit))return false;
  }return true;
 }
 /** Coarse deterministic survey, not an assertion about unsampled columns.
  * Each sampled centre includes immediate neighbours, so observed slope uses a real one-block run. */
 private static Optional<Sampling> sampling(Geometry geometry,RegionSnapshot region,MemoryView memory,int limit){
  long minX=Long.MAX_VALUE,minZ=Long.MAX_VALUE,maxX=Long.MIN_VALUE,maxZ=Long.MIN_VALUE;
  for(var b:geometry.boxes()){minX=Math.min(minX,b.minX());minZ=Math.min(minZ,b.minZ());maxX=Math.max(maxX,(long)b.maxXExclusive()-1);maxZ=Math.max(maxZ,(long)b.maxZExclusive()-1);}
  for(var d:geometry.discs()){
   if(!Double.isFinite(d.radius())||d.radius()>29999984)return Optional.empty();long radius=(long)Math.ceil(d.radius());
   minX=Math.min(minX,(long)d.center().x()-radius);minZ=Math.min(minZ,(long)d.center().z()-radius);maxX=Math.max(maxX,(long)d.center().x()+radius);maxZ=Math.max(maxZ,(long)d.center().z()+radius);
  }
  if(minX>maxX||minZ>maxZ||minX<Integer.MIN_VALUE||minZ<Integer.MIN_VALUE||maxX>=Integer.MAX_VALUE||maxZ>=Integer.MAX_VALUE||limit<3)return Optional.empty();
  long width=maxX-minX+1,depth=maxZ-minZ+1,area=Math.multiplyExact(width,depth);var points=new TreeSet<Long>();
  for(var anchor:memory.anchors().values().stream().filter(a->a.active()&&a.dimension().equals(geometry.dimension())&&(region.region().anchorIds().contains(a.anchorId())||a.regionIds().contains(region.region().regionId()))).sorted(Comparator.comparing(AnchorRecord::anchorId)).toList())
   if(!neighbourhood(points,geometry,anchor.position().x(),anchor.position().z(),Math.min(limit,64)))return Optional.empty();
  int centres=Math.max(1,(limit-points.size())/3);long lo=1,hi=Math.max(width,depth);
  while(lo<hi){long mid=(lo+hi)>>>1,nx=(width+mid-1)/mid,nz=(depth+mid-1)/mid;if(nx>centres||nz>centres||nx*nz>centres)lo=mid+1;else hi=mid;}
  int spacing=Math.toIntExact(lo);
  for(long x=minX;x<=maxX;x+=spacing)for(long z=minZ;z<=maxZ;z+=spacing)if(!neighbourhood(points,geometry,(int)x,(int)z,limit))return Optional.empty();
  if(points.size()<2&&!neighbourhood(points,geometry,(int)(minX+(width-1)/2),(int)(minZ+(depth-1)/2),limit))return Optional.empty();
  return points.size()<2?Optional.empty():Optional.of(new Sampling(Set.copyOf(points),spacing,area));
 }
 private static Optional<Double> recentActivity(RegionId id,DecisionInput input){
  var rows=input.memory().regions().stream().filter(m->m.regionId().equals(id)).toList();if(rows.size()!=1)return Optional.empty();
  var value=rows.getFirst().components().get(MemoryComponent.ACTIVITY);var now=input.context().capturedTick();
  if(value==null||value.evidenceCount()==0||value.confidence().value()<=0||value.observedTick().compareTo(now)>0||value.value()<0)return Optional.empty();
  var calibration=input.config().values().get(value.halfLifeProfile());if(calibration==null)return Optional.empty();long halfLife;try{halfLife=calibration.value().longValueExact();}catch(ArithmeticException invalid){return Optional.empty();}if(halfLife<1)return Optional.empty();
  double decayed=MemoryMath.decay(value.value(),value.observedTick(),now,halfLife);return Optional.of(decayed/(1+decayed));
 }
 /** Convention approved for this native adapter: saturation is instantaneous
  * represented surface water/moisture, not inferred hydraulic or rainfall history.
  * Water fluid is1, native MOISTURE is0..7, other observed vanilla dry states are0.
  * Opaque custom blocks/BlockEntities never supply an invented zero. */
 private static Optional<Physical> physical(MinecraftServer server,RegionSnapshot region,Geometry geometry,MemoryView memory,int[] budget,Set<ChunkRef> globalChunks){
  var selected=sampling(geometry,region,memory,Math.min(256,budget[0]));if(selected.isEmpty())return Optional.empty();
  var sample=selected.get();var support=new SupportShape(geometry.boxes(),geometry.discs(),List.of());var l=level(server,geometry.dimension());var columns=new TreeMap<Long,Surface>();var chunks=new HashSet<ChunkRef>();double rain=0,saturation=0;boolean normalizedKnown=true;
  for(var point:sample.points().stream().sorted().toList()){
   int x=(int)(point>>32),z=(int)(long)point;var chunk=l.getChunkSource().getChunkNow(x>>4,z>>4);if(chunk==null||budget[0]--<=0)return Optional.empty();
   var ref=new ChunkRef(geometry.dimension(),x>>4,z>>4);if(!globalChunks.contains(ref)&&globalChunks.size()>=64)return Optional.empty();globalChunks.add(ref);chunks.add(ref);
   int y=chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x&15,z&15);if(y<l.getMinBuildHeight()||y>=l.getMaxBuildHeight())return Optional.empty();
   var pos=new BlockPos(x,y,z);var state=chunk.getBlockState(pos);
   if(!SupportGeometry.contains(support,new BlockPosition(geometry.dimension(),x,y,z))||!BuiltInRegistries.BLOCK.getKey(state.getBlock()).getNamespace().equals("minecraft")||state.hasBlockEntity())normalizedKnown=false;
   else saturation+=state.getFluidState().is(FluidTags.WATER)?1:state.hasProperty(FarmBlock.MOISTURE)?state.getValue(FarmBlock.MOISTURE)/7.0:0;
   rain+=l.isRainingAt(pos.above())?l.getRainLevel(1):0;columns.put(point,new Surface(x,z,y));
  }
  int drop=-1;Surface high=null,low=null;for(var a:columns.values())for(int[] d:new int[][]{{1,0},{0,1}}){var b=columns.get(key(a.x()+d[0],a.z()+d[1]));if(b==null)continue;int difference=Math.abs(a.y()-b.y());if(difference>drop){drop=difference;high=a.y()>=b.y()?a:b;low=a.y()>=b.y()?b:a;}}
  if(drop<0)return Optional.empty();var positions=new LinkedHashSet<BlockPosition>();positions.add(new BlockPosition(geometry.dimension(),high.x(),high.y(),high.z()));positions.add(new BlockPosition(geometry.dimension(),low.x(),low.y(),low.z()));
  if(drop>=2){positions.add(new BlockPosition(geometry.dimension(),high.x(),low.y()+2,high.z()));positions.add(new BlockPosition(geometry.dimension(),low.x(),low.y()+1,low.z()));}
  var cells=new HashMap<BlockPosition,TerrainCellSnapshot>();for(var p:positions){var pos=new BlockPos(p.x(),p.y(),p.z());var state=l.getBlockState(pos);var owner=OWNERS.read(l,pos);boolean known=BuiltInRegistries.BLOCK.getKey(state.getBlock()).getNamespace().equals("minecraft")&&!state.hasBlockEntity();
   cells.put(p,new TerrainCellSnapshot(p,BlockTransformBinding.stateBlob(state).reference().hash(),BlockTransformBinding.STATE_CODEC,Set.of(),Set.of(),!known||owner.map(o->o.disposition()!=OwnershipDisposition.RELEASED).orElse(false),known,owner.map(OwnershipRecord::epoch).orElse(new Epoch(0)),owner.map(OwnershipRecord::playerOverrideEpoch).orElse(new Epoch(0))));}
  var terrain=new TerrainSnapshot(new Footprint(geometry.dimension(),chunks,cells.keySet(),List.of()),cells,List.of(),Map.of(),region.region().header().revision());
  return Optional.of(new Physical(terrain,Set.copyOf(chunks),rain/columns.size(),saturation/columns.size(),StrictMath.atan(drop)/(StrictMath.PI/2),normalizedKnown,columns.size(),sample.spacing(),sample.areaUpperBound()));
 }
 private static boolean cacheReadable(MinecraftServer server,Physical physical,Set<ChunkRef> observed){
  for(var chunk:physical.chunks())if(!observed.contains(chunk)){if(observed.size()>=64)return false;if(level(server,chunk.dimension()).getChunkSource().getChunkNow(chunk.x(),chunk.z())==null)return false;observed.add(chunk);}
  for(var entry:physical.terrain().cells().entrySet()){
   var p=entry.getKey();var l=level(server,p.dimension());var pos=new BlockPos(p.x(),p.y(),p.z());var old=entry.getValue();var state=l.getBlockState(pos);var owner=OWNERS.read(l,pos);
   if(!old.stateFingerprint().equals(BlockTransformBinding.stateBlob(state).reference().hash())||!old.ownershipEpoch().equals(owner.map(OwnershipRecord::epoch).orElse(new Epoch(0)))||!old.playerOverrideEpoch().equals(owner.map(OwnershipRecord::playerOverrideEpoch).orElse(new Epoch(0))))return false;
  }return true;
 }
 private static DecisionInput unknown(DecisionInput input){
  var old=input.context();var c=old.context();var regions=new HashMap<RegionId,RegionSnapshot>();var scopes=new HashMap<>(c.scopes());
  c.regions().forEach((id,r)->{var risks=new HashMap<>(r.risks());FACTORS.forEach(risks::remove);regions.put(id,new RegionSnapshot(r.region(),r.tags(),r.presentPlayers(),r.camps(),risks));
   var ref=new ScopeRef(ScopeKind.REGION,id.value());var scope=scopes.get(ref);if(scope!=null){var available=new HashSet<>(scope.availableSensors());available.remove(SensorKind.RISK);var missing=new HashSet<>(scope.missingSensors());missing.add(SensorKind.RISK);scopes.put(ref,new ScopeSnapshot(ref,scope.revision(),available,missing,scope.scoringFeatures()));}});
  var terrain=new ArrayList<TerrainSnapshot>();for(var t:c.terrain()){var risks=new HashMap<>(t.risks());FACTORS.forEach(risks::remove);terrain.add(new TerrainSnapshot(t.loadedFootprint(),t.cells(),t.safeExits(),risks,t.revision()));}
  var missing=new HashSet<>(old.missingData());missing.add(SensorKind.RISK);var snapshot=new SnapshotBatch(old.requestId(),old.scopes(),old.capturedTick(),old.scopeRevisions(),new ObservationContext(scopes,c.players(),c.groups(),regions,c.anchors(),c.locations(),terrain,c.capabilities()),missing,old.loadedChunks(),old.serverHealth());
  return new DecisionInput(snapshot,input.memory(),input.catalog(),input.config(),input.requests(),input.chains(),input.seed(),input.state(),input.advisorySnapshot());
 }
 private static ResourceId regionMarker(RegionId region){return new ResourceId("proutlost_director:landslide/region/"+region.value());}
 /** Exact captured normalized factors for the existing DisasterInstance DTO.
  * Component values stay out of RegionSnapshot.risks: pacing must see only final risk scores. */
 public static Optional<Map<ResourceId,UnitValue>> capturedFactors(DecisionInput input,RegionId id){
  var region=input.context().context().regions().get(id);if(region==null||!region.risks().containsKey(RISK))return Optional.empty();
  var found=input.context().context().terrain().stream().filter(t->t.loadedFootprint().dimension().equals(region.region().dimension())&&t.revision().equals(region.region().header().revision())&&t.risks().containsKey(regionMarker(id))&&t.risks().keySet().containsAll(FACTORS)&&region.risks().get(RISK).equals(t.risks().get(RISK))).toList();
  if(found.size()!=1)return Optional.empty();var result=new HashMap<ResourceId,UnitValue>();for(var key:List.of(RAIN,SATURATION,SLOPE,ACTIVITY))result.put(key,found.getFirst().risks().get(key));return Optional.of(Map.copyOf(result));
 }
 private static DecisionInput approved(MinecraftServer server,DecisionInput input){
  var original=input.context();var context=original.context();var watch=WATCHES.computeIfAbsent(server,Watch::new);
  if(watch.closed||watch.invalid)return unknown(input);
  long now=original.capturedTick().value(),cadence=input.config().values().get("sensor.risk_ticks").value().longValueExact(),ttl=input.config().values().get("cache.terrain_ttl_ticks").value().longValueExact();
  int capacity=Math.toIntExact(Math.min(2048,input.config().values().get("cache.terrain_cells").value().longValueExact()));
  if(cadence<1||ttl<1||capacity<2)return unknown(input);long maximumAge=Math.min(cadence,ttl);
  var regions=new HashMap<>(context.regions());var scopes=new HashMap<>(context.scopes());var terrains=new ArrayList<>(context.terrain());var riskKnown=new HashSet<RegionId>();var terrainKnown=new HashSet<RegionId>();var chunks=new HashSet<ChunkRef>();int[] budget={Math.min(1024,capacity)};
  for(var region:context.regions().values().stream().sorted(Comparator.comparing(r->r.region().regionId())).toList()){
   if(region.region().status()!=RegionStatus.ACTIVE)continue;var id=region.region().regionId();var risks=new HashMap<>(region.risks());FACTORS.forEach(risks::remove);var factors=new HashMap<ResourceId,UnitValue>();
   var geometry=geometry(region,input.memory());var memory=input.memory().regions().stream().filter(m->m.regionId().equals(id)).toList();
   Optional<DecayValue> activity=memory.size()==1?Optional.ofNullable(memory.getFirst().components().get(MemoryComponent.ACTIVITY)):Optional.empty();
   var stamp=new Stamp(original.scopes().worldId(),input.state().world().bootEpoch(),input.config().provenanceHash(),region.region().header().revision(),geometry,activity);ServerLevel l=null;for(var candidate:server.getAllLevels())if(candidate.dimension().location().toString().equals(region.region().dimension().value())){l=candidate;break;}
   var cached=watch.values.get(id);Physical observed=null;
   if(l!=null&&cached!=null&&cached.stamp().equals(stamp)&&now>=cached.tick()&&now-cached.tick()<maximumAge&&cached.editEpoch()==watch.epoch(region.region().dimension())&&cached.rainBits()==Float.floatToIntBits(l.getRainLevel(1))&&cached.raining()==l.isRaining()&&cacheReadable(server,cached.physical(),chunks))observed=cached.physical();
   else{watch.values.remove(id);if(l!=null&&budget[0]>=3){var measured=physical(server,region,geometry,input.memory(),budget,chunks);if(measured.isPresent()){observed=measured.get();watch.put(id,new Cached(stamp,now,watch.epoch(region.region().dimension()),Float.floatToIntBits(l.getRainLevel(1)),l.isRaining(),observed),capacity);
    System.out.println("DIRECTOR_NATIVE_LANDSLIDE_WATCH region="+id.value()+" tick="+now+" sampled_columns="+observed.samples()+" spacing="+observed.spacing()+" area_upper_bound="+observed.areaUpperBound()+" geometry_coarse=true history_inferred=false");}}}
   if(observed!=null){terrainKnown.add(id);var active=recentActivity(id,input);if(observed.normalizedKnown()&&active.isPresent()){
    double score=0;double[] values={observed.rain(),observed.saturation(),observed.slope(),active.get()};String[] names={"rain","saturation","slope","activity"};
    for(int i=0;i<4;i++){double weight=input.config().values().get("risk.landslide."+names[i]+"_weight").value().doubleValue();if(!Double.isFinite(weight)||weight<0)throw new IllegalArgumentException("invalid approved landslide weight");score+=weight*values[i];}
    factors.put(RAIN,new UnitValue(values[0]));factors.put(SATURATION,new UnitValue(values[1]));factors.put(SLOPE,new UnitValue(values[2]));factors.put(ACTIVITY,new UnitValue(values[3]));factors.put(RISK,new UnitValue(Math.clamp(score,0,1)));factors.put(regionMarker(id),new UnitValue(1));risks.put(RISK,factors.get(RISK));riskKnown.add(id);
   }var t=observed.terrain();terrains.add(new TerrainSnapshot(t.loadedFootprint(),t.cells(),t.safeExits(),riskKnown.contains(id)?Map.copyOf(factors):Map.of(),t.revision()));}
   regions.put(id,new RegionSnapshot(region.region(),region.tags(),region.presentPlayers(),region.camps(),risks));var ref=new ScopeRef(ScopeKind.REGION,id.value());var old=scopes.get(ref);
   if(old!=null){var available=new HashSet<>(old.availableSensors());var missing=new HashSet<>(old.missingSensors());
    if(terrainKnown.contains(id)){available.add(SensorKind.TERRAIN);missing.remove(SensorKind.TERRAIN);}else{available.remove(SensorKind.TERRAIN);missing.add(SensorKind.TERRAIN);}
    if(riskKnown.contains(id)){available.add(SensorKind.RISK);missing.remove(SensorKind.RISK);}else{available.remove(SensorKind.RISK);missing.add(SensorKind.RISK);}
    scopes.put(ref,new ScopeSnapshot(ref,old.revision(),available,missing,old.scoringFeatures()));}
  }
  watch.values.keySet().retainAll(context.regions().keySet());var required=context.regions().values().stream().filter(r->r.region().status()==RegionStatus.ACTIVE).map(r->r.region().regionId()).collect(java.util.stream.Collectors.toSet());var missing=new HashSet<>(original.missingData());
  if(!required.isEmpty()&&terrainKnown.containsAll(required))missing.remove(SensorKind.TERRAIN);else missing.add(SensorKind.TERRAIN);
  if(!required.isEmpty()&&riskKnown.containsAll(required))missing.remove(SensorKind.RISK);else missing.add(SensorKind.RISK);
  var loaded=new HashSet<>(original.loadedChunks());loaded.addAll(chunks);var snapshot=new SnapshotBatch(original.requestId(),original.scopes(),original.capturedTick(),original.scopeRevisions(),new ObservationContext(scopes,context.players(),context.groups(),regions,context.anchors(),context.locations(),terrains,context.capabilities()),missing,loaded,original.serverHealth());
  return new DecisionInput(snapshot,input.memory(),input.catalog(),input.config(),input.requests(),input.chains(),input.seed(),input.state(),input.advisorySnapshot());
 }
 private static long key(int x,int z){return ((long)x<<32)^(z&0xffffffffL);}
 private static boolean add(Set<Long> points,int x,int z,int limit){
  points.add(key(x,z));return points.size()<=limit;
 }
 private static ServerLevel level(MinecraftServer server,DimensionId dimension){
  for(var l:server.getAllLevels())if(l.dimension().location().toString().equals(dimension.value()))return l;
  throw new IllegalArgumentException("risk dimension unavailable");
 }
 private static Optional<Measured> measure(MinecraftServer server,RegionSnapshot region,MemoryView memory,int[] budget,Set<ChunkRef> globalChunks,ProviderMode mode){
  int limit=budget[0];
  var record=region.region();var points=new LinkedHashSet<Long>();
  for(var b:record.supportShape().boxes()){
   if(!b.dimension().equals(record.dimension()))return Optional.empty();
   long area=(long)(b.maxXExclusive()-b.minX())*(b.maxZExclusive()-b.minZ());
   if(area>limit)return Optional.empty();
   for(int x=b.minX();x<b.maxXExclusive();x++)for(int z=b.minZ();z<b.maxZExclusive();z++)
    if(!add(points,x,z,limit))return Optional.empty();
  }
  for(var disc:record.supportShape().discs()){
   if(!disc.center().dimension().equals(record.dimension())||disc.radius()>32)return Optional.empty();
   int radius=(int)Math.ceil(disc.radius());var center=disc.center();
   for(int x=center.x()-radius;x<=center.x()+radius;x++)for(int z=center.z()-radius;z<=center.z()+radius;z++){
    long dx=(long)x-center.x(),dz=(long)z-center.z();
    if(dx*dx+dz*dz<=disc.radius()*disc.radius()&&!add(points,x,z,limit))return Optional.empty();
   }
  }
  for(var location:memory.locations().values()){
   if(!location.active()||!location.dimension().equals(record.dimension()))continue;
   boolean associated=location.anchorIds().stream().anyMatch(record.anchorIds()::contains)
       ||record.anchorIds().stream().map(memory.anchors()::get).filter(Objects::nonNull)
         .anyMatch(a->a.active()&&a.locationId().equals(Optional.of(location.locationId())));
   if(!associated)continue;var b=location.bounds();
   if((long)(b.maxXExclusive()-b.minX())*(b.maxZExclusive()-b.minZ())>limit)return Optional.empty();
   for(int x=b.minX();x<b.maxXExclusive();x++)for(int z=b.minZ();z<b.maxZExclusive();z++)
    if(!add(points,x,z,limit))return Optional.empty();
  }
  if(points.size()<2)return Optional.empty();var l=level(server,record.dimension());
  var columns=new HashMap<Long,Surface>();var observedChunks=new HashSet<ChunkRef>();
  for(long point:points){
   int x=(int)(point>>32),z=(int)point;
   var ref=new ChunkRef(record.dimension(),x>>4,z>>4);
   if(!globalChunks.contains(ref)&&globalChunks.size()>=64)return Optional.empty();
   var chunk=l.getChunkSource().getChunkNow(x>>4,z>>4);if(chunk==null)return Optional.empty();
   globalChunks.add(ref);if(budget[0]--<=0)return Optional.empty();observedChunks.add(ref);
   if(observedChunks.size()>64)return Optional.empty();
   // LevelChunk heightmap access reads the already loaded native column.
   int y=chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x&15,z&15);
   if(y<l.getMinBuildHeight()||y>=l.getMaxBuildHeight())return Optional.empty();
   columns.put(point,new Surface(x,z,y));
  }
  int drop=-1;Surface high=null,low=null;
  for(var a:columns.values())for(int[] delta:new int[][]{{1,0},{0,1}}){
   var b=columns.get(key(a.x()+delta[0],a.z()+delta[1]));if(b==null)continue;
   int difference=Math.abs(a.y()-b.y());
   if(difference>drop){drop=difference;high=a.y()>=b.y()?a:b;low=a.y()>=b.y()?b:a;}
  }
  if(drop<0)return Optional.empty();
  var proofPositions=new LinkedHashSet<BlockPosition>();
  proofPositions.add(new BlockPosition(record.dimension(),high.x(),high.y(),high.z()));
  proofPositions.add(new BlockPosition(record.dimension(),low.x(),low.y(),low.z()));
  // Native wall-base pair is useful to the later bounded physical planner. It is observed here.
  if(drop>=2){
   proofPositions.add(new BlockPosition(record.dimension(),high.x(),low.y()+2,high.z()));
   proofPositions.add(new BlockPosition(record.dimension(),low.x(),low.y()+1,low.z()));
  }
  var cells=new HashMap<BlockPosition,TerrainCellSnapshot>();var proofChunks=new HashSet<ChunkRef>();
  for(var p:proofPositions){
   var pos=new BlockPos(p.x(),p.y(),p.z());var state=l.getBlockState(pos);var owner=OWNERS.read(l,pos);
   boolean known=BuiltInRegistries.BLOCK.getKey(state.getBlock()).getNamespace().equals("minecraft")&&!state.hasBlockEntity();
   boolean protectedContent=!known||owner.map(o->o.disposition()!=OwnershipDisposition.RELEASED).orElse(false);
   cells.put(p,new TerrainCellSnapshot(p,BlockTransformBinding.stateBlob(state).reference().hash(),
       BlockTransformBinding.STATE_CODEC,Set.of(),Set.of(),protectedContent,known,
       owner.map(OwnershipRecord::epoch).orElse(new Epoch(0)),owner.map(OwnershipRecord::playerOverrideEpoch).orElse(new Epoch(0))));
   proofChunks.add(new ChunkRef(p.dimension(),p.x()>>4,p.z()>>4));
  }
  var risks=new HashMap<>(region.risks());if(mode==ProviderMode.TEST_ONLY)risks.put(RISK,new UnitValue(Math.min(1,drop/8.0)));
  var updated=new RegionSnapshot(record,region.tags(),region.presentPlayers(),region.camps(),risks);
  var terrain=new TerrainSnapshot(new Footprint(record.dimension(),proofChunks,cells.keySet(),List.of()),cells,List.of(),
      mode==ProviderMode.TEST_ONLY?Map.of(RISK,risks.get(RISK)):Map.of(),record.header().revision());
  return Optional.of(new Measured(updated,terrain));
 }
 public static DecisionInput enrich(MinecraftServer server,DecisionInput input){return enrich(server,input,ProviderMode.TEST_ONLY);}
 public static DecisionInput enrich(MinecraftServer server,DecisionInput input,ProviderMode mode){
  Objects.requireNonNull(mode);
  if(!server.isSameThread()||input.config().calibration()!=(mode==ProviderMode.PRODUCTION?Calibration.APPROVED:Calibration.DEFAULT_FOR_TESTING))
   throw new IllegalArgumentException("native relief actor/calibration mode mismatch");
  if(mode==ProviderMode.PRODUCTION)return approved(server,input);
  var original=input.context();var c=original.context();
  var regions=new HashMap<>(c.regions());var scopes=new HashMap<>(c.scopes());var terrain=new ArrayList<>(c.terrain());
  var measured=new HashSet<RegionId>();int[] budget={1024};var observedChunks=new HashSet<ChunkRef>();
  // Deterministic ordering keeps the bounded sensor reproducible at the same live scene.
  for(var region:c.regions().values().stream().sorted(Comparator.comparing(r->r.region().regionId())).toList()){
   if(region.region().status()!=RegionStatus.ACTIVE||budget[0]<2)continue;
   var result=measure(server,region,input.memory(),budget,observedChunks,mode);if(result.isEmpty())continue;
   var next=result.get();var id=region.region().regionId();regions.put(id,next.region());terrain.add(next.terrain());
   measured.add(id);
   var ref=new ScopeRef(ScopeKind.REGION,id.value());var old=scopes.get(ref);
   if(old!=null){
    var available=new HashSet<>(old.availableSensors());available.add(SensorKind.TERRAIN);if(mode==ProviderMode.TEST_ONLY)available.add(SensorKind.RISK);
    var missing=new HashSet<>(old.missingSensors());missing.remove(SensorKind.TERRAIN);if(mode==ProviderMode.TEST_ONLY)missing.remove(SensorKind.RISK);
    scopes.put(ref,new ScopeSnapshot(ref,old.revision(),available,missing,old.scoringFeatures()));
   }
  }
  var missing=new HashSet<>(original.missingData());
  var required=c.regions().values().stream().filter(r->r.region().status()==RegionStatus.ACTIVE)
      .map(r->r.region().regionId()).collect(java.util.stream.Collectors.toSet());
  if(!required.isEmpty()&&measured.containsAll(required)){missing.remove(SensorKind.TERRAIN);if(mode==ProviderMode.TEST_ONLY)missing.remove(SensorKind.RISK);}
  var loadedChunks=new HashSet<>(original.loadedChunks());loadedChunks.addAll(observedChunks);
  var snapshot=new SnapshotBatch(original.requestId(),original.scopes(),original.capturedTick(),original.scopeRevisions(),
      new ObservationContext(scopes,c.players(),c.groups(),regions,c.anchors(),c.locations(),terrain,c.capabilities()),
      missing,loadedChunks,original.serverHealth());
  return new DecisionInput(snapshot,input.memory(),input.catalog(),input.config(),input.requests(),input.chains(),
      input.seed(),input.state(),input.advisorySnapshot());
 }
}
