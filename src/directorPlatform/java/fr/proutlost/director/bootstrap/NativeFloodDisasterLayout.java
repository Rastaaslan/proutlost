package fr.proutlost.director.bootstrap;

import fr.proutlost.director.adapter.neoforge.server.effects.ControlledWaterBinding;
import fr.proutlost.director.adapter.neoforge.server.ownership.ServerOwnershipAttachments;
import fr.proutlost.director.api.codec.ModelCodec;
import fr.proutlost.director.api.model.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/** Actual loaded native reservoir/exit survey. No source credit, blocks,
 * ownership, artificial player, client receipt or natural eligibility is created. */
public final class NativeFloodDisasterLayout {
 private NativeFloodDisasterLayout(){}
 public record Layout(List<BlockPosition> cells,BlobContent mask,Footprint footprint,Footprint warningEnvelope,
   List<RouteSegment> exits,long observedUnits){
  public Layout {
   cells=List.copyOf(cells);Objects.requireNonNull(mask);Objects.requireNonNull(footprint);Objects.requireNonNull(warningEnvelope);
   exits=List.copyOf(exits);
   long layers=cells.stream().mapToInt(BlockPosition::y).distinct().count();
   if(cells.size()!=4||!((layers==1&&observedUnits==26)||(layers==2&&observedUnits==32))||exits.isEmpty())
    throw new IllegalArgumentException("actual four-cell reservoir and observed exit required");
  }
 }
 private static void require(boolean valid,String reason){if(!valid)throw new IllegalArgumentException(reason);}
 private static BlockPosition point(ServerLevel level,BlockPos p){
  return new BlockPosition(new DimensionId(level.dimension().location().toString()),p.getX(),p.getY(),p.getZ());
 }
 private static boolean loaded(ServerLevel level,BlockPos p){
  return p.getY()>=level.getMinBuildHeight()&&p.getY()<level.getMaxBuildHeight()&&
   level.getChunkSource().getChunkNow(p.getX()>>4,p.getZ()>>4)!=null&&level.getWorldBorder().isWithinBounds(p);
 }
 public static Layout survey(ServerPlayer player){
  Objects.requireNonNull(player);var level=player.serverLevel();
  require(level.getServer().isSameThread(),"flood survey actor");
  require(player.hasPermissions(4)&&player.isAlive(),"living OP4 required");
  var hit=player.pick(6,0,true);
  require(hit instanceof BlockHitResult&&hit.getType()==HitResult.Type.BLOCK,"aim at an actual vanilla water source");
  var source=((BlockHitResult)hit).getBlockPos();
  require(loaded(level,source)&&ControlledWaterBinding.nativeVolume(level.getBlockState(source))==8,
   "target must be loaded horizontal vanilla source8");
  var ownership=new ServerOwnershipAttachments(64);
  for(var direction:List.of(Direction.NORTH,Direction.SOUTH,Direction.EAST,Direction.WEST)){
   var cells=new ArrayList<BlockPosition>();long units=0;boolean valid=true;
   for(int step=0;step<4;step++){
    var p=source.relative(direction,step);
    if(!loaded(level,p)){valid=false;break;}
    int observed=ControlledWaterBinding.nativeVolume(level.getBlockState(p));
    if(observed!=8-step){valid=false;break;}
    cells.add(point(level,p));units+=observed;
   }
   if(!valid||units!=26)continue;
   try{
    // Existing provider checks the real rectangular STONE floor/side ring,
    // AIR headroom, loaded chunks and absence of active/player ownership.
    var mask=ControlledWaterBinding.captureMask(level,cells,ownership);
    var footprint=new ModelCodec<>(Footprint.class).decode(mask.bytes());
    require(footprint.cells().equals(Set.copyOf(cells))&&footprint.bounds().size()==1,"captured native mask mismatch");
    var envelope=warningEnvelope(level,footprint,ownership);
    var actualExit=exit(player,envelope.bounds().getFirst());
    return new Layout(cells,mask,footprint,envelope,List.of(actualExit),units);
   }catch(IllegalArgumentException changedOrUnsuitable){continue;}
  }
  // A second bounded layout qualifies real eye immersion without increasing
  // the four-cell kernel or creating any native source credit.
  for(var direction:List.of(Direction.NORTH,Direction.SOUTH,Direction.EAST,Direction.WEST)){
   var cells=new ArrayList<BlockPosition>();boolean valid=true;
   for(int depth=0;depth<2;depth++)for(int step=0;step<2;step++){
    var p=source.relative(direction,step).below(depth);
    if(!loaded(level,p)||ControlledWaterBinding.nativeVolume(level.getBlockState(p))!=8){valid=false;break;}
    cells.add(point(level,p));
   }
   if(!valid||cells.size()!=4)continue;
   try{
    var mask=ControlledWaterBinding.captureMask(level,cells,ownership);
    var footprint=new ModelCodec<>(Footprint.class).decode(mask.bytes());
    require(footprint.cells().equals(Set.copyOf(cells))&&footprint.bounds().size()==1,"captured deep native mask mismatch");
    var envelope=warningEnvelope(level,footprint,ownership);
    return new Layout(cells,mask,footprint,envelope,List.of(exit(player,envelope.bounds().getFirst())),32);
   }catch(IllegalArgumentException changedOrUnsuitable){continue;}
  }
  throw new IllegalArgumentException("no observed closed STONE four-cell basin: flat8/7/6/5 or two-layer four-source32, AIR headroom and real escape required");
 }
 private static Footprint warningEnvelope(ServerLevel level,Footprint mask,ServerOwnershipAttachments ownership){
  var b=mask.bounds().getFirst();
  var bounds=new Aabb(b.dimension(),b.minX()-1,b.minY(),b.minZ()-1,b.maxXExclusive()+1,b.maxYExclusive(),b.maxZExclusive()+1);
  var chunks=new HashSet<ChunkRef>();
  for(int x=Math.floorDiv(bounds.minX(),16);x<=Math.floorDiv(bounds.maxXExclusive()-1,16);x++)
   for(int z=Math.floorDiv(bounds.minZ(),16);z<=Math.floorDiv(bounds.maxZExclusive()-1,16);z++){
    require(level.getChunkSource().getChunkNow(x,z)!=null,"actual warning perimeter chunk unavailable");chunks.add(new ChunkRef(bounds.dimension(),x,z));
   }
  for(int x:new int[]{bounds.minX(),bounds.maxXExclusive()-1})for(int z:new int[]{bounds.minZ(),bounds.maxZExclusive()-1}){
   var p=new BlockPos(x,bounds.minY(),z);
   require(loaded(level,p)&&loaded(level,p.above())&&level.getBlockState(p).isAir()&&level.getBlockState(p.above()).isAir()
    &&ownership.read(level,p).map(o->o.disposition()==OwnershipDisposition.RELEASED).orElse(true),"warning corner is obstructed or has active/player provenance");
  }
  return new Footprint(mask.dimension(),chunks,mask.cells(),List.of(bounds));
 }
 /** Same actual three-cell exit algorithm as NativeTerrainDisasterLayout. */
 private static RouteSegment exit(ServerPlayer player,Aabb envelope){
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
}