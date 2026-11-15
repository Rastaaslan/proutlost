package fr.proutlost.director.bootstrap;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.adapter.neoforge.server.ownership.ServerOwnershipAttachments;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

/** One actual eligible cell beside a real abandoned_camp; no aim/OP, invented flag or region. */
public final class NativeSelectedGrowthHabitat {
 private static final TagId ABANDONED=new TagId("proutlost:abandoned_camp");
 private static final ServerOwnershipAttachments OWNERS=new ServerOwnershipAttachments(64);
 private NativeSelectedGrowthHabitat(){}
 public record Survey(AnchorRecord camp,BlockPosition position,Footprint footprint){}
 private static ServerLevel level(MinecraftServer server,DimensionId dimension){
  for(var l:server.getAllLevels())if(l.dimension().location().toString().equals(dimension.value()))return l;
  throw new IllegalArgumentException("growth dimension unavailable");
 }
 public static boolean allowed(MinecraftServer server,BlockPosition p){
  var level=level(server,p.dimension());if(!server.isSameThread())throw new IllegalStateException("growth survey actor");
  if(level.getChunkSource().getChunkNow(p.x()>>4,p.z()>>4)==null)return false;
  var pos=new BlockPos(p.x(),p.y(),p.z());var state=level.getBlockState(pos);
  return level.getWorldBorder().isWithinBounds(pos)&&!state.hasBlockEntity()&&state.getFluidState().isEmpty()
      &&(state.is(Blocks.STONE)||state.is(Blocks.DIRT)||state.is(Blocks.COBBLESTONE))
      &&level.getBlockState(pos.above()).isAir()
      &&OWNERS.read(level,pos).map(o->o.disposition()==OwnershipDisposition.RELEASED).orElse(true);
 }
 public static AnchorRecord camp(ExecutionPlan plan,SnapshotBatch snapshot){
  if(plan.effects().size()!=1||plan.effects().getFirst().expectedFootprint().cells().size()!=1)
   throw new IllegalArgumentException("one real selected growth cell required");
  var p=plan.effects().getFirst().expectedFootprint().cells().iterator().next();
  return snapshot.context().anchors().values().stream().filter(a->a.active()&&a.evidence()==EvidenceStatus.CONFIRMED
      &&a.tags().contains(ABANDONED)&&a.dimension().equals(p.dimension())
      &&Math.abs(a.position().x()-p.x())<=3&&Math.abs(a.position().z()-p.z())<=3&&Math.abs(a.position().y()-p.y())<=1)
    .sorted(Comparator.comparing(AnchorRecord::anchorId)).findFirst()
    .orElseThrow(()->new IllegalArgumentException("real confirmed abandoned_camp anchor required"));
 }
 public static Survey preview(MinecraftServer server,Candidate candidate,DecisionInput input){
  if(!server.isSameThread()||!candidate.definition().id().equals(new DefinitionId("proutlost_director:candidate/camp_regrowth")))
   throw new IllegalArgumentException("actual growth candidate required");
  var regionScopes=candidate.scopes().scopes().stream().filter(s->s.kind()==ScopeKind.REGION).toList();
  if(regionScopes.size()!=1)throw new IllegalArgumentException("one actual growth region required");
  var id=new RegionId(regionScopes.getFirst().id());var region=input.memory().regionRecords().get(id);
  if(region==null||region.status()!=RegionStatus.ACTIVE)throw new IllegalArgumentException("known active growth region required");
  for(var anchor:input.context().context().anchors().values().stream()
      .filter(a->a.active()&&a.evidence()==EvidenceStatus.CONFIRMED&&a.tags().contains(ABANDONED)
         &&a.dimension().equals(region.dimension())&&(a.regionIds().contains(id)||region.anchorIds().contains(a.anchorId())))
      .sorted(Comparator.comparing(AnchorRecord::anchorId)).limit(8).toList()){
   for(int radius=0;radius<=3;radius++)for(int dy=-1;dy<=1;dy++)
    for(int dx=-radius;dx<=radius;dx++)for(int dz=-radius;dz<=radius;dz++){
     if(Math.max(Math.abs(dx),Math.abs(dz))!=radius)continue;
     var a=anchor.position();var p=new BlockPosition(a.dimension(),a.x()+dx,a.y()+dy,a.z()+dz);
     var chunk=new ChunkRef(p.dimension(),p.x()>>4,p.z()>>4);
     if(input.context().loadedChunks().contains(chunk)&&allowed(server,p))
      return new Survey(anchor,p,new Footprint(p.dimension(),Set.of(chunk),Set.of(p),List.of()));
    }
  }
  throw new IllegalArgumentException("no real mutable cell beside abandoned_camp");
 }
}
