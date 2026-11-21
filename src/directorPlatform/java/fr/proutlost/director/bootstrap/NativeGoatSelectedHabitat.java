package fr.proutlost.director.bootstrap;

import fr.proutlost.director.api.model.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;

/** Real CAMP anchor and actual native spawn space, without OP, aim, synthetic tags or chunk loads. */
public final class NativeGoatSelectedHabitat {
 private static final TagId CAMP=new TagId("proutlost:camp");
 private NativeGoatSelectedHabitat(){}
 public record Survey(AnchorRecord camp,BlockPosition spawn,Footprint footprint){}
 private static ServerLevel level(MinecraftServer server,DimensionId dimension){
  for(var level:server.getAllLevels())if(level.dimension().location().toString().equals(dimension.value()))return level;
  throw new IllegalArgumentException("goat dimension unavailable");
 }
 public static boolean safe(ServerLevel level,BlockPosition p){
  if(!level.getServer().isSameThread())throw new IllegalStateException("goat survey actor");
  if(!level.dimension().location().toString().equals(p.dimension().value())
      ||level.getChunkSource().getChunkNow(p.x()>>4,p.z()>>4)==null)return false;
  var b=new BlockPos(p.x(),p.y(),p.z());
  if(b.getY()<=level.getMinBuildHeight()||b.getY()>=level.getMaxBuildHeight()-2
      ||!level.getWorldBorder().isWithinBounds(b)
      ||!level.getBlockState(b.below()).isSolidRender(level,b.below())
      ||!level.getBlockState(b).getCollisionShape(level,b).isEmpty()
      ||!level.getBlockState(b.above()).getCollisionShape(level,b.above()).isEmpty())return false;
  var box=EntityType.GOAT.getDimensions().makeBoundingBox(p.x()+.5,p.y(),p.z()+.5);
  return level.getWorldBorder().isWithinBounds(box)&&level.noCollision(box)&&!level.containsAnyLiquid(box);
 }
 private static boolean camp(AnchorRecord anchor){
  return anchor.active()&&anchor.evidence()==EvidenceStatus.CONFIRMED&&anchor.tags().contains(CAMP);
 }
 private static long distance(BlockPosition a,BlockPosition b){
  if(!a.dimension().equals(b.dimension()))return Long.MAX_VALUE;
  long dx=(long)a.x()-b.x(),dy=(long)a.y()-b.y(),dz=(long)a.z()-b.z();
  return dx*dx+dy*dy+dz*dz;
 }
 public static AnchorRecord anchor(ExecutionPlan selected,SnapshotBatch captured){
  if(selected.effects().size()!=1||selected.effects().getFirst().expectedFootprint().cells().size()!=1)
   throw new IllegalArgumentException("one selected goat cell required");
  var point=selected.effects().getFirst().expectedFootprint().cells().iterator().next();
  return captured.context().anchors().values().stream().filter(NativeGoatSelectedHabitat::camp)
    .filter(a->distance(a.position(),point)<=36)
    .sorted(Comparator.comparingLong((AnchorRecord a)->distance(a.position(),point))
      .thenComparing(AnchorRecord::anchorId)).findFirst()
    .orElseThrow(()->new IllegalArgumentException("real confirmed CAMP anchor required"));
 }
 /** Before J03, seal this exact one-cell footprint in the real catalogue plan. */
 public static Survey preview(MinecraftServer server,Candidate candidate,DecisionInput input){
  if(!server.isSameThread()||!candidate.definition().id().equals(new DefinitionId("proutlost_director:candidate/goat_guest")))
   throw new IllegalArgumentException("selected goat candidate required");
  var ids=candidate.roles().roles().getOrDefault("witness",List.of());
  if(ids.size()!=1)throw new IllegalArgumentException("one actual goat witness required");
  var witness=input.context().context().players().get(ids.getFirst());
  if(witness==null||witness.eligibility()!=PlayerEligibility.ELIGIBLE)
   throw new IllegalArgumentException("eligible witness unavailable");
  var camps=input.context().context().anchors().values().stream().filter(NativeGoatSelectedHabitat::camp)
      .filter(a->distance(a.position(),witness.position().position())<=256)
      .sorted(Comparator.comparingLong((AnchorRecord a)->distance(a.position(),witness.position().position()))
        .thenComparing(AnchorRecord::anchorId)).limit(8).toList();
  for(var anchor:camps){
   var level=level(server,anchor.dimension());var center=anchor.position();
   for(int radius=1;radius<=3;radius++)for(int dy=0;dy<=1;dy++)
    for(int dx=-radius;dx<=radius;dx++)for(int dz=-radius;dz<=radius;dz++){
     if(Math.max(Math.abs(dx),Math.abs(dz))!=radius)continue;
     var p=new BlockPosition(center.dimension(),center.x()+dx,center.y()+dy,center.z()+dz);
     var chunk=new ChunkRef(p.dimension(),p.x()>>4,p.z()>>4);
     if(!input.context().loadedChunks().contains(chunk)||distance(p,witness.position().position())>256||!safe(level,p))continue;
     return new Survey(anchor,p,new Footprint(p.dimension(),Set.of(chunk),Set.of(p),List.of()));
    }
  }
  throw new IllegalArgumentException("no real loaded goat slot beside a confirmed CAMP");
 }
 public static void validate(MinecraftServer server,ExecutionPlan selected,SnapshotBatch captured){
  if(!server.isSameThread()||selected.effects().size()!=1
      ||!selected.effects().getFirst().primitive().equals(new PrimitiveId("proutlost:goat_visit"))
      ||!(selected.effects().getFirst().typedArgs() instanceof GoatVisit args)
      ||!args.role().equals("witness")||!args.anchorTag().equals(CAMP))
   throw new IllegalArgumentException("exact selected goat primitive required");
  var fp=selected.effects().getFirst().expectedFootprint();anchor(selected,captured);
  var p=fp.cells().iterator().next();
  if(!fp.loadedChunks().contains(new ChunkRef(p.dimension(),p.x()>>4,p.z()>>4))||!safe(level(server,p.dimension()),p))
   throw new IllegalArgumentException("selected goat slot is no longer available");
 }
}
