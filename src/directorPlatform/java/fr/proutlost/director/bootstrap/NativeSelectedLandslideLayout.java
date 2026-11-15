package fr.proutlost.director.bootstrap;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.runtime.DisasterRunner;
import fr.proutlost.director.neoforge.DirectorStandaloneMod;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.level.block.Blocks;

/** Actual bounded STONE/AIR pair and real per-player exits; no aim, OP or invented risk. */
public final class NativeSelectedLandslideLayout {
 private NativeSelectedLandslideLayout(){}
 private static ServerLevel level(MinecraftServer server,DimensionId dimension){
  for(var l:server.getAllLevels())if(l.dimension().location().toString().equals(dimension.value()))return l;
  throw new IllegalArgumentException("selected landslide dimension unavailable");
 }
 private static List<ServerPlayer> players(MinecraftServer server,RoleAssignments roles,DimensionId dimension){
  var ids=roles.roles().values().stream().flatMap(Collection::stream).distinct().sorted().toList();
  if(ids.isEmpty()||ids.size()>6)throw new IllegalArgumentException("one to six actual participants required");
  var actual=new ArrayList<ServerPlayer>();
  for(var id:ids){
   var player=server.getPlayerList().getPlayer(id.value());
   if(player==null||!player.isAlive()||!player.serverLevel().dimension().location().toString().equals(dimension.value()))
    throw new IllegalArgumentException("selected participant unavailable");
   actual.add(player);
  }
  return actual;
 }
 private static NativeTerrainDisasterLayout.Layout pair(MinecraftServer server,DimensionId dimension,
      BlockPosition source,BlockPosition destination,RoleAssignments roles){
  var l=level(server,dimension);var a=new BlockPos(source.x(),source.y(),source.z());
  var b=new BlockPos(destination.x(),destination.y(),destination.z());
  if(!NativeTerrainDisasterLayout.pair(l,a,b)||(source.y()-destination.y()!=1)
      ||Math.abs(source.x()-destination.x())+Math.abs(source.z()-destination.z())!=1)
   throw new IllegalArgumentException("actual bounded wall-side landslide pair required");
  var bounds=new Aabb(dimension,destination.x()-1,destination.y(),destination.z()-1,
      destination.x()+2,Math.max(destination.y()+3,source.y()+1),destination.z()+2);
  var chunks=new HashSet<ChunkRef>();
  for(int x=bounds.minX()>>4;x<=((bounds.maxXExclusive()-1)>>4);x++)
   for(int z=bounds.minZ()>>4;z<=((bounds.maxZExclusive()-1)>>4);z++){
    if(l.getChunkSource().getChunkNow(x,z)==null)throw new IllegalArgumentException("actual hazard chunk unavailable");
    chunks.add(new ChunkRef(dimension,x,z));
   }
  var exits=new ArrayList<RouteSegment>();
  for(var player:players(server,roles,dimension))exits.add(NativeTerrainDisasterLayout.exit(player,bounds));
  return new NativeTerrainDisasterLayout.Layout(DisasterRunner.Family.LANDSLIDE,List.of(source),List.of(destination),
      new Footprint(dimension,chunks,Set.of(source,destination),List.of(bounds)),exits);
 }
 public static NativeTerrainDisasterLayout.Layout selected(MinecraftServer server,ExecutionPlan selected){
  if(!server.isSameThread())throw new IllegalStateException("selected terrain actor");
  var physical=selected.effects().stream().filter(i->i.primitive().equals(new PrimitiveId("proutlost:landslide"))).toList();
  if(physical.size()!=1||physical.getFirst().expectedFootprint().cells().size()!=2)
   throw new IllegalArgumentException("one selected two-cell native landslide required");
  var fp=physical.getFirst().expectedFootprint();var l=level(server,fp.dimension());
  var source=fp.cells().stream().filter(p->l.getBlockState(new BlockPos(p.x(),p.y(),p.z())).is(Blocks.STONE)).toList();
  var dest=fp.cells().stream().filter(p->l.getBlockState(new BlockPos(p.x(),p.y(),p.z())).is(Blocks.AIR)).toList();
  if(source.size()!=1||dest.size()!=1)throw new IllegalArgumentException("selected source/destination changed");
  var observed=pair(server,fp.dimension(),source.getFirst(),dest.getFirst(),selected.roles());
  if(!observed.footprint().equals(fp))throw new IllegalArgumentException("selected hazard footprint changed");
  return observed;
 }
 /** Pure bounded candidate distribution; every retained position still passes the native checks below. */
 private static List<BlockPosition> sourcePoints(RegionRecord region,MemoryView memory,List<TerrainSnapshot> terrain){
  var points=new LinkedHashSet<BlockPosition>();var locations=new ArrayList<LocationRecord>();
  for(var location:memory.locations().values().stream().filter(LocationRecord::active)
      .filter(l->l.dimension().equals(region.dimension()))
      .sorted(Comparator.comparing(LocationRecord::locationId)).limit(4).toList()){
   boolean related=location.anchorIds().stream().anyMatch(region.anchorIds()::contains)
      ||region.anchorIds().stream().map(memory.anchors()::get).filter(Objects::nonNull)
        .anyMatch(a->a.locationId().equals(Optional.of(location.locationId())));
   if(!related)continue;var b=location.bounds();
   if((long)(b.maxXExclusive()-b.minX())*(b.maxYExclusive()-b.minY())*(b.maxZExclusive()-b.minZ())>4096)continue;
   locations.add(location);
   // At most one real linked active anchor per location: <=4 locations x3 vertical positions.
   var anchor=region.anchorIds().stream().map(memory.anchors()::get).filter(Objects::nonNull)
      .filter(a->a.active()&&a.regionIds().contains(region.regionId())&&a.dimension().equals(region.dimension()))
      .filter(a->location.anchorIds().contains(a.anchorId())||a.locationId().equals(Optional.of(location.locationId())))
      .sorted(Comparator.comparing(AnchorRecord::anchorId)).findFirst();
   if(anchor.isEmpty())continue;var p=anchor.orElseThrow().position();
   for(int dy:new int[]{1,0,-1}){
    var seed=new BlockPosition(p.dimension(),p.x(),p.y()+dy,p.z());
    if(seed.dimension().equals(b.dimension())&&seed.x()>=b.minX()&&seed.x()<b.maxXExclusive()
        &&seed.y()>=b.minY()&&seed.y()<b.maxYExclusive()&&seed.z()>=b.minZ()&&seed.z()<b.maxZExclusive())
     points.add(seed);
   }
  }
  for(var area:terrain)for(var p:area.cells().keySet().stream()
      .sorted(Comparator.comparingInt(BlockPosition::x).thenComparingInt(BlockPosition::y).thenComparingInt(BlockPosition::z))
      .limit(64).toList())if(points.size()<128)points.add(p);
  // Keep the existing volume prefix only after anchors and actual observed terrain; never exceed128 candidates.
  for(var location:locations){var b=location.bounds();
   for(int y=b.minY();y<b.maxYExclusive()&&points.size()<128;y++)
    for(int x=b.minX();x<b.maxXExclusive()&&points.size()<128;x++)
     for(int z=b.minZ();z<b.maxZExclusive()&&points.size()<128;z++)
      points.add(new BlockPosition(b.dimension(),x,y,z));
  }
  return List.copyOf(points);
 }
 public static NativeTerrainDisasterLayout.Layout preview(MinecraftServer server,Candidate candidate,DecisionInput input){
  if(!server.isSameThread()||!candidate.definition().id().equals(new DefinitionId("proutlost_director:candidate/controlled_landslide")))
   throw new IllegalArgumentException("selected landslide candidate required");
  var regions=candidate.scopes().scopes().stream().filter(s->s.kind()==ScopeKind.REGION).map(s->new RegionId(s.id())).toList();
  if(regions.size()!=1)throw new IllegalArgumentException("one real landslide region required");
  var region=input.context().context().regions().get(regions.getFirst());
  if(region==null||!fr.proutlost.director.engine.ConditionEvaluator.knownRegionalRisk(
      regions.getFirst(),new ResourceId("proutlost:landslide"),input))
   throw new IllegalArgumentException("natural landslide risk remains unknown");
  var points=sourcePoints(region.region(),input.memory(),input.context().context().terrain());
  for(var source:points.stream().limit(128).toList()){
   if(!source.dimension().equals(region.region().dimension())
       ||DirectorStandaloneMod.directorLifecycle().runtimePrimaryRegion(server,source).filter(regions.getFirst()::equals).isEmpty())continue;
   var chunk=new ChunkRef(source.dimension(),source.x()>>4,source.z()>>4);
   if(!input.context().loadedChunks().contains(chunk))continue;
   var l=level(server,source.dimension());if(l.getChunkSource().getChunkNow(chunk.x(),chunk.z())==null
       ||!l.getBlockState(new BlockPos(source.x(),source.y(),source.z())).is(Blocks.STONE))continue;
   for(var d:List.of(Direction.NORTH,Direction.SOUTH,Direction.EAST,Direction.WEST)){
    var dest=new BlockPosition(source.dimension(),source.x()+d.getStepX(),source.y()-1,source.z()+d.getStepZ());
    try{return pair(server,source.dimension(),source,dest,candidate.roles());}
    catch(IllegalArgumentException unavailable){/* Next real loaded wall pair. */}
   }
  }
  throw new IllegalArgumentException("no real bounded stone landslide and safe participant exits");
 }
}
