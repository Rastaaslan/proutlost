package fr.proutlost.director.bootstrap;

import fr.proutlost.director.adapter.neoforge.server.effects.*;
import fr.proutlost.director.api.model.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

/** DRAFT native input snapshot only; no blocks, items or source credits are created. */
public final class ControlledWaterPlanning {
 private ControlledWaterPlanning(){}
 private static final BudgetVector CAP=new BudgetVector(100,100,100,128,128,256,1048576,100,1048576,4096);
 private static final TagId MASK=new TagId("proutlost_director:controlled_water_mask");
 public static Footprint footprint(BlobContent mask){
  if(!mask.reference().codecId().equals(ControlledWaterBinding.MASK_CODEC)||mask.reference().codecVersion()!=1)
   throw new IllegalArgumentException("controlled water mask codec");
  return new fr.proutlost.director.api.codec.ModelCodec<>(Footprint.class).decode(mask.bytes());
 }
 public static PlanningSnapshot snapshot(MinecraftServer server,WorldId world,BlobContent mask,
   Map<ResourceKey,OwnershipRecord> ownerRecords,Tick tick){
  if(!server.isSameThread())throw new IllegalStateException("water snapshot actor");
  var footprint=footprint(mask);ServerLevel level=null;
  for(var candidate:server.getAllLevels())if(candidate.dimension().location().toString().equals(footprint.dimension().value())){level=candidate;break;}
  if(level==null)throw new IllegalStateException("water mask dimension unavailable");
  for(var chunk:footprint.loadedChunks())if(level.getChunkSource().getChunkNow(chunk.x(),chunk.z())==null)
   throw new IllegalStateException("water mask chunk unavailable; never force-load");
  var cells=new HashMap<BlockPosition,TerrainCellSnapshot>();
  for(var p:footprint.cells()){
   var pos=new BlockPos(p.x(),p.y(),p.z());var state=level.getBlockState(pos);
   var key=new ResourceKey(OwnershipDomain.BLOCK,p.dimension(),p.x()+","+p.y()+","+p.z());
   var owner=Objects.requireNonNull(ownerRecords.get(key),"mask ownership record missing");
   int nativeUnits=ControlledWaterBinding.nativeVolume(state);
   boolean known=DirectorControlledWater.volume(state)>=0||nativeUnits>0;
   boolean protectedContent=owner.disposition()!=OwnershipDisposition.DIRECTOR_ACTIVE||!known;
   cells.put(p,new TerrainCellSnapshot(p,BlockTransformBinding.stateBlob(state).reference().hash(),
    BlockTransformBinding.STATE_CODEC,Set.of(MASK),Set.of(),protectedContent,known,owner.epoch(),owner.playerOverrideEpoch()));
  }
  var terrain=new TerrainSnapshot(footprint,cells,List.of(),Map.of(),new Revision(1));
  var context=new ObservationContext(Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),List.of(terrain),Map.of());
  var batch=new SnapshotBatch(new RequestId(UUID.randomUUID()),new ScopeSet(world,Set.of()),tick,Map.of(),
   context,Set.of(),footprint.loadedChunks(),HealthMode.NORMAL);
  return new PlanningSnapshot(batch,new OwnershipView(ownerRecords),new BudgetView(CAP,BudgetMath.zero(),Map.of()),
   new BlobBundle(List.of(mask)));
 }
}
