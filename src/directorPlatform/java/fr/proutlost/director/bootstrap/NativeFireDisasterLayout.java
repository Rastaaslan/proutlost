package fr.proutlost.director.bootstrap;

import fr.proutlost.director.api.model.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.*;

/** Native survey only. A contained stone fire pit is not evidence of a natural wildfire risk. */
public final class NativeFireDisasterLayout {
 private NativeFireDisasterLayout(){}
 public record Layout(List<BlockPosition> cells,Footprint footprint,List<RouteSegment> exits){
  public Layout {cells=List.copyOf(cells);exits=List.copyOf(exits);}
 }
 private static BlockPosition point(ServerLevel l,BlockPos p){return new BlockPosition(new DimensionId(l.dimension().location().toString()),p.getX(),p.getY(),p.getZ());}
 private static boolean loaded(ServerLevel l,BlockPos p){return p.getY()>=l.getMinBuildHeight()&&p.getY()<l.getMaxBuildHeight()
  &&l.getChunkSource().getChunkNow(p.getX()>>4,p.getZ()>>4)!=null&&l.getWorldBorder().isWithinBounds(p);}
 private static void require(boolean ok,String reason){if(!ok)throw new IllegalArgumentException(reason);}
 private static boolean cell(ServerLevel l,BlockPos p){return loaded(l,p)&&loaded(l,p.below())&&loaded(l,p.above())
  &&l.getBlockState(p).is(Blocks.AIR)&&l.getBlockState(p.below()).is(Blocks.STONE)&&l.getFluidState(p).isEmpty()
  &&l.getEntities(null,new AABB(p)).isEmpty();}
 private static boolean contained(ServerLevel l,Set<BlockPos> cells){
  for(var p:cells){
   if(!cell(l,p))return false;
   for(var d:List.of(Direction.NORTH,Direction.SOUTH,Direction.EAST,Direction.WEST)){
    var near=p.relative(d);if(cells.contains(near))continue;
    if(!loaded(l,near)||!l.getBlockState(near).is(Blocks.STONE))return false;
   }
  }return true;
 }
 public static Layout survey(ServerPlayer player){
  var l=player.serverLevel();require(l.getServer().isSameThread(),"fire survey actor");
  var target=player.pick(6,0,false);
  require(target instanceof BlockHitResult&&target.getType()==HitResult.Type.BLOCK,"aim at the stone floor of a contained fire pit");
  var hit=(BlockHitResult)target;require(hit.getDirection()==Direction.UP&&l.getBlockState(hit.getBlockPos()).is(Blocks.STONE),"aim at the TOP of the stone floor");
  var first=hit.getBlockPos().above();require(cell(l,first),"empty supported fire cell required");
  Set<BlockPos> chosen=null;
  if(contained(l,Set.of(first)))chosen=Set.of(first);
  else for(var d:List.of(Direction.NORTH,Direction.SOUTH,Direction.EAST,Direction.WEST)){
   var pair=Set.of(first,first.relative(d));if(contained(l,pair)){chosen=pair;break;}
  }
  require(chosen!=null,"one or two empty cells on STONE, enclosed horizontally by STONE required");
  var cells=chosen.stream().sorted(Comparator.comparingInt((BlockPos p)->p.getX()).thenComparingInt(p->p.getZ())).map(p->point(l,p)).toList();
  int minX=cells.stream().mapToInt(BlockPosition::x).min().orElseThrow()-1,maxX=cells.stream().mapToInt(BlockPosition::x).max().orElseThrow()+2;
  int minZ=cells.stream().mapToInt(BlockPosition::z).min().orElseThrow()-1,maxZ=cells.stream().mapToInt(BlockPosition::z).max().orElseThrow()+2;
  int y=first.getY();require(y+3<=l.getMaxBuildHeight(),"fire hazard height");
  var dimension=new DimensionId(l.dimension().location().toString());var bounds=new Aabb(dimension,minX,y,minZ,maxX,y+3,maxZ);
  var chunks=new HashSet<ChunkRef>();
  for(int x=Math.floorDiv(minX,16);x<=Math.floorDiv(maxX-1,16);x++)for(int z=Math.floorDiv(minZ,16);z<=Math.floorDiv(maxZ-1,16);z++){
   require(l.getChunkSource().getChunkNow(x,z)!=null,"fire perimeter chunk unavailable");chunks.add(new ChunkRef(dimension,x,z));
  }
  var footprint=new Footprint(dimension,chunks,Set.copyOf(cells),List.of(bounds));
  return new Layout(cells,footprint,List.of(exit(player,bounds)));
 }
 private static RouteSegment exit(ServerPlayer player,Aabb envelope){
  var l=player.serverLevel();var start=player.blockPosition();
  var hazard=new AABB(envelope.minX(),envelope.minY(),envelope.minZ(),envelope.maxXExclusive(),envelope.maxYExclusive(),envelope.maxZExclusive());
  var directions=new ArrayList<>(List.of(Direction.NORTH,Direction.SOUTH,Direction.EAST,Direction.WEST));
  directions.sort(Comparator.comparingDouble((Direction d)->{
   var end=start.relative(d,2);double x=end.getX()+.5-(envelope.minX()+envelope.maxXExclusive())*.5,z=end.getZ()+.5-(envelope.minZ()+envelope.maxZExclusive())*.5;return -(x*x+z*z);
  }));
  for(var d:directions){
   boolean valid=true;
   for(int i=0;i<=2;i++){
    var p=start.relative(d,i);var body=new AABB(p.getX()+.2,p.getY(),p.getZ()+.2,p.getX()+.8,p.getY()+1.8,p.getZ()+.8);
    if(!loaded(l,p)||!loaded(l,p.above())||!l.getFluidState(p).isEmpty()||!l.getBlockState(p.below()).isSolidRender(l,p.below())
      ||!l.noCollision(body)||(i==2&&body.intersects(hazard))){valid=false;break;}
   }
   if(valid)return new RouteSegment(point(l,start),point(l,start.relative(d,2)),.3);
  }
  throw new IllegalArgumentException("no observed three-cell escape ending outside fire envelope");
 }
}
