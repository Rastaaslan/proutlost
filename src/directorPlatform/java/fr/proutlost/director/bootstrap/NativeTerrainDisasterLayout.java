package fr.proutlost.director.bootstrap;

import fr.proutlost.director.api.model.*;
import fr.proutlost.director.runtime.DisasterRunner;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/** DRAFT: actual loaded native survey only. Does not admit, place or classify natural risk. */
public final class NativeTerrainDisasterLayout {
 private NativeTerrainDisasterLayout(){}
 public record Layout(DisasterRunner.Family family,List<BlockPosition> sources,
   List<BlockPosition> destinations,Footprint footprint,List<RouteSegment> exits){
  public Layout {sources=List.copyOf(sources);destinations=List.copyOf(destinations);exits=List.copyOf(exits);}
  public List<BlockPosition> cells(){var all=new ArrayList<>(sources);all.addAll(destinations);return List.copyOf(all);}
 }
 private static BlockPosition point(ServerLevel level,BlockPos p){
  return new BlockPosition(new DimensionId(level.dimension().location().toString()),p.getX(),p.getY(),p.getZ());
 }
 private static boolean loaded(ServerLevel level,BlockPos p){
  return p.getY()>=level.getMinBuildHeight()&&p.getY()<level.getMaxBuildHeight()&&
   level.getChunkSource().getChunkNow(p.getX()>>4,p.getZ()>>4)!=null&&level.getWorldBorder().isWithinBounds(p);
 }
 private static void require(boolean valid,String reason){if(!valid)throw new IllegalArgumentException(reason);}
 static boolean pair(ServerLevel level,BlockPos source,BlockPos destination){
  return loaded(level,source)&&loaded(level,destination)&&loaded(level,destination.below())&&
   level.getBlockState(source).is(Blocks.STONE)&&level.getBlockState(destination).is(Blocks.AIR)&&
   level.getBlockState(destination.below()).isSolidRender(level,destination.below())&&
   level.getFluidState(destination.below()).isEmpty()&&level.getEntities(null,new AABB(destination)).isEmpty();
 }
 public static Layout survey(ServerPlayer player,DisasterRunner.Family family){
  var level=player.serverLevel();require(level.getServer().isSameThread(),"terrain survey actor");
  require(family==DisasterRunner.Family.LANDSLIDE||family==DisasterRunner.Family.COLLAPSE,"unsupported terrain family");
  require(player.pick(6,0,false) instanceof BlockHitResult,"native block target required");
  var hit=(BlockHitResult)player.pick(6,0,false);require(hit.getType()==HitResult.Type.BLOCK,"native block target required");
  var source=hit.getBlockPos();var sources=new ArrayList<BlockPos>();var destinations=new ArrayList<BlockPos>();
  if(family==DisasterRunner.Family.LANDSLIDE){
   require(hit.getDirection().getAxis()!=Direction.Axis.Y,"aim at a stone wall side");
   var destination=source.relative(hit.getDirection()).below();
   require(pair(level,source,destination),"STONE wall source and empty supported destination required");
   sources.add(source);destinations.add(destination);
  }else{
   require(hit.getDirection()==Direction.DOWN,"aim at the underside of a two-block stone ceiling");
   var destination=source.below(2);require(pair(level,source,destination),"first ceiling/destination pair unavailable");
   var right=player.getDirection().getClockWise();BlockPos second=null;
   for(var direction:List.of(right,right.getOpposite())){
    var candidate=source.relative(direction);
    if(pair(level,candidate,candidate.below(2))){second=candidate;break;}
   }
   require(second!=null,"second adjacent stone ceiling and empty supported destination required");
   sources.add(source);sources.add(second);destinations.add(destination);destinations.add(second.below(2));
  }
  var dimension=new DimensionId(level.dimension().location().toString());
  int minX=destinations.stream().mapToInt(BlockPos::getX).min().orElseThrow()-1;
  int minY=destinations.stream().mapToInt(BlockPos::getY).min().orElseThrow();
  int minZ=destinations.stream().mapToInt(BlockPos::getZ).min().orElseThrow()-1;
  int maxX=destinations.stream().mapToInt(BlockPos::getX).max().orElseThrow()+2;
  int maxY=Math.max(minY+3,sources.stream().mapToInt(BlockPos::getY).max().orElseThrow()+1);
  int maxZ=destinations.stream().mapToInt(BlockPos::getZ).max().orElseThrow()+2;
  require(minY>=level.getMinBuildHeight()&&maxY<=level.getMaxBuildHeight(),"hazard height");
  var bounds=new Aabb(dimension,minX,minY,minZ,maxX,maxY,maxZ);var chunks=new HashSet<ChunkRef>();
  for(int x=Math.floorDiv(minX,16);x<=Math.floorDiv(maxX-1,16);x++)
   for(int z=Math.floorDiv(minZ,16);z<=Math.floorDiv(maxZ-1,16);z++){
    require(level.getChunkSource().getChunkNow(x,z)!=null,"hazard perimeter chunk unavailable");
    chunks.add(new ChunkRef(dimension,x,z));
   }
  var actualSources=sources.stream().map(p->point(level,p)).toList();
  var actualDestinations=destinations.stream().map(p->point(level,p)).toList();
  var cells=new HashSet<>(actualSources);cells.addAll(actualDestinations);
  require(cells.size()==sources.size()+destinations.size(),"overlapping collapse cells");
  var footprint=new Footprint(dimension,chunks,cells,List.of(bounds));
  var exit=exit(player,bounds);
  return new Layout(family,actualSources,actualDestinations,footprint,List.of(exit));
 }
 static RouteSegment exit(ServerPlayer player,Aabb envelope){
  var level=player.serverLevel();var start=player.blockPosition();
  var hazard=new AABB(envelope.minX(),envelope.minY(),envelope.minZ(),
   envelope.maxXExclusive(),envelope.maxYExclusive(),envelope.maxZExclusive());
  var directions=new ArrayList<>(List.of(Direction.NORTH,Direction.SOUTH,Direction.EAST,Direction.WEST));
  directions.sort(Comparator.comparingDouble((Direction d)->{
   var end=start.relative(d,2);
   double x=end.getX()+.5-(envelope.minX()+envelope.maxXExclusive())*.5;
   double z=end.getZ()+.5-(envelope.minZ()+envelope.maxZExclusive())*.5;
   return -(x*x+z*z);
  }));
  for(var direction:directions){
   boolean valid=true;
   for(int i=0;i<=2;i++){
    var p=start.relative(direction,i);
    var body=new AABB(p.getX()+.2,p.getY(),p.getZ()+.2,p.getX()+.8,p.getY()+1.8,p.getZ()+.8);
    if(!loaded(level,p)||!loaded(level,p.above())||!level.getFluidState(p).isEmpty()||
       !level.getBlockState(p.below()).isSolidRender(level,p.below())||!level.noCollision(player,body)){
     valid=false;break;
    }
    if(i==2&&body.intersects(hazard)){valid=false;break;}
   }
   if(valid){
    var end=start.relative(direction,2);return new RouteSegment(point(level,start),point(level,end),.3);
   }
  }
  throw new IllegalArgumentException("no observed three-cell escape ending outside the hazard");
 }
 public static BlockTransformBindingRecipe recipe(){
  return new BlockTransformBindingRecipe(Map.of(new ResourceId("minecraft:stone"),new ResourceId("minecraft:air"),
   new ResourceId("minecraft:air"),new ResourceId("minecraft:cobblestone")),
   Map.of(new ResourceId("minecraft:air"),new ResourceId("minecraft:stone"),
   new ResourceId("minecraft:cobblestone"),new ResourceId("minecraft:air")));
 }
 /** Plain data used to create existing BlockTransformBinding.Recipe values. */
 public record BlockTransformBindingRecipe(Map<ResourceId,ResourceId> forward,Map<ResourceId,ResourceId> inverse){
  public BlockTransformBindingRecipe {forward=Map.copyOf(forward);inverse=Map.copyOf(inverse);}
 }
}