package fr.proutlost.director.bootstrap;

import fr.proutlost.director.adapter.neoforge.server.effects.BlockTransformBinding;
import fr.proutlost.director.adapter.neoforge.server.ownership.ServerOwnershipAttachments;
import fr.proutlost.director.api.model.*;
import java.util.*;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BiomeTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/** Closed, observed grass-pasture LAB profile. Never creates blocks or region tags. */
public final class NativeFaunaHabitat {
 private NativeFaunaHabitat(){}
 public static final TagId CORRIDOR=new TagId("proutlost:fauna_corridor");
 public static final ResourceId SHEEP=new ResourceId("minecraft:sheep"),CAPYBARA=new ResourceId("reanimal:capybara");
 /** Explicit qualified registry profiles only. Unknown species stay unavailable. */
 public static boolean supports(ResourceId species){return SHEEP.equals(species)||CAPYBARA.equals(species);}
 public static boolean usesBrain(ResourceId species){return CAPYBARA.equals(species);}
 private static EntityType<?> type(ResourceId species){
  if(!supports(species))throw new IllegalArgumentException("unqualified fauna registry profile");
  return BuiltInRegistries.ENTITY_TYPE.getOptional(ResourceLocation.parse(species.value()))
    .orElseThrow(()->new IllegalArgumentException("missing required fauna species "+species.value()));
 }
 private static final ServerOwnershipAttachments LEDGER=new ServerOwnershipAttachments(64);
 private static final BudgetVector CAP=new BudgetVector(100,100,100,128,128,256,1048576,100,1048576,4096);
 public record Layout(List<List<BlockPosition>> routes,Set<ChunkRef> chunks){
  public Layout {routes=routes.stream().map(List::copyOf).toList();chunks=Set.copyOf(chunks);if(routes.size()!=24)throw new IllegalArgumentException("24 native sheep routes required");}
 }
 private static void actor(ServerLevel level){if(!level.getServer().isSameThread())throw new IllegalStateException("fauna habitat server actor");}
 private static BlockPos pos(BlockPosition p){return new BlockPos(p.x(),p.y(),p.z());}
 private static BlockPosition point(ServerLevel level,BlockPos p){return new BlockPosition(new DimensionId(level.dimension().location().toString()),p.getX(),p.getY(),p.getZ());}
 private static boolean known(BlockState state){return BuiltInRegistries.BLOCK.getKey(state.getBlock()).getNamespace().equals("minecraft")&&!state.hasBlockEntity()&&state.getFluidState().isEmpty();}
 private static boolean floor(BlockState state){return state.is(Blocks.GRASS_BLOCK)||state.is(Blocks.DIRT)||state.is(Blocks.COARSE_DIRT)||state.is(Blocks.ROOTED_DIRT)||state.is(Blocks.PODZOL);}
 private static boolean freeOwnership(ServerLevel level,BlockPos p){return LEDGER.read(level,p).map(o->o.disposition()==OwnershipDisposition.RELEASED).orElse(true);}
 private static boolean cell(ServerLevel level,BlockPosition p,boolean entities,ResourceId species){
  if(!p.dimension().value().equals(level.dimension().location().toString())||
     level.getChunkSource().getChunkNow(p.x()>>4,p.z()>>4)==null)return false;
  var b=pos(p);if(b.getY()<=level.getMinBuildHeight()||b.getY()>=level.getMaxBuildHeight()-2)return false;
  // Match the installed ReAnimal biome modifier; do not invent/replace physical biomes.
  if(CAPYBARA.equals(species)&&!level.getBiome(b).is(BiomeTags.IS_JUNGLE))return false;
  var feet=level.getBlockState(b);var head=level.getBlockState(b.above());var below=level.getBlockState(b.below());
  if(!known(feet)||!known(head)||!known(below)||!feet.isAir()||!head.isAir()||!floor(below)||
     !below.isSolidRender(level,b.below())||!freeOwnership(level,b)||!freeOwnership(level,b.above())||!freeOwnership(level,b.below()))return false;
  var box=type(species).getDimensions().makeBoundingBox(p.x()+.5,p.y(),p.z()+.5);
  if(!level.getWorldBorder().isWithinBounds(box)||!level.noBlockCollision(null,box))return false;
  return !entities||(level.noCollision(box)&&level.isUnobstructed(null,Shapes.create(box)));
 }
 /** naturalSpawn=true is for the RESERVED slot; false checks walking geometry only.
  * Native navigation subsequently checks entity collision while excluding its own animal. */
 public static boolean qualified(ServerLevel level,List<BlockPosition> route,BlockPosition spawn,boolean naturalSpawn){
  return qualified(level,route,spawn,naturalSpawn,SHEEP);
 }
 public static boolean qualified(ServerLevel level,List<BlockPosition> route,BlockPosition spawn,boolean naturalSpawn,ResourceId species){
  actor(level);if(route==null||route.isEmpty()||route.size()>64||spawn==null||!supports(species))return false;
  EntityType<?> nativeType;
  try{nativeType=type(species);}catch(IllegalArgumentException missing){return false;}
  var seen=new HashSet<BlockPosition>();BlockPosition previous=null;
  for(var p:route){
   if(p==null||!seen.add(p)||!cell(level,p,naturalSpawn,species))return false;
   if(previous!=null&&(Math.abs((long)p.x()-previous.x())+Math.abs((long)p.z()-previous.z())!=1||Math.abs((long)p.y()-previous.y())>1))return false;
   previous=p;
  }
  return !naturalSpawn||route.contains(spawn)&&SpawnPlacements.hasPlacement(nativeType)
    &&(!usesBrain(species)||SpawnPlacements.isSpawnPositionOk(nativeType,level,pos(spawn)))
    &&SpawnPlacements.checkSpawnRules(nativeType,level,MobSpawnType.EVENT,pos(spawn),level.getRandom());
 }
 public static Layout plan(ServerPlayer player){
  var level=player.serverLevel();actor(level);
  if(!player.hasPermissions(4)||!player.isAlive())throw new IllegalArgumentException("Living OP4 required for native migration LAB");
  if(!(player.pick(6,0,false) instanceof BlockHitResult hit)||hit.getType()!=HitResult.Type.BLOCK||!level.getBlockState(hit.getBlockPos()).is(Blocks.GRASS_BLOCK))
   throw new IllegalArgumentException("Vise l'herbe d'une parcelle plane chargee a moins de six blocs");
  var origin=hit.getBlockPos().above();var forward=player.getDirection();var right=forward.getClockWise();
  var chunks=new HashSet<ChunkRef>();
  // Observe every walking cell, including soil already grazed by settled sheep.
  // Natural sheep spawn rules still apply separately to each actual start.
  for(int z=0;z<=9;z++)for(int x=-10;x<=12;x++){
   var nativePos=origin.relative(right,x).relative(forward,z);var p=point(level,nativePos);
   if(!qualified(level,List.of(p),p,false))
    throw new IllegalArgumentException("Parcelle reelle de sol naturel plane23x10, deux blocs libres, chargee et sans provenance active requise");
   chunks.add(new ChunkRef(p.dimension(),p.x()>>4,p.z()>>4));
  }
  var routes=new ArrayList<List<BlockPosition>>();
  for(int row=0;row<4;row++)for(int column=0;column<6;column++){
   var start=origin.relative(right,column*4-10).relative(forward,row*3);
   var route=List.of(point(level,start),point(level,start.relative(right)),point(level,start.relative(right,2)));
   if(!qualified(level,route,route.getFirst(),true))throw new IllegalArgumentException("Habitat natif SHEEP ou chemin occupe/incompatible; verifier herbe et lumiere");
   routes.add(route);
  }
  return new Layout(routes,chunks);
 }
 public static PlanningSnapshot snapshot(MinecraftServer server,WorldId world,List<BlockPosition> route,Tick tick){
  return snapshot(server,world,route,tick,SHEEP);
 }
 public static PlanningSnapshot snapshot(MinecraftServer server,WorldId world,List<BlockPosition> route,Tick tick,ResourceId species){
  if(!server.isSameThread()||route==null||route.isEmpty())throw new IllegalArgumentException("Native route snapshot actor");
  ServerLevel level=null;var dimension=route.getFirst().dimension();
  for(var candidate:server.getAllLevels())if(candidate.dimension().location().toString().equals(dimension.value())){level=candidate;break;}
  if(level==null||!qualified(level,route,route.getFirst(),false,species))throw new IllegalArgumentException("Native corridor is no longer qualified");
  var cells=new LinkedHashMap<BlockPosition,TerrainCellSnapshot>();var chunks=new HashSet<ChunkRef>();
  for(var p:route){
   var nativePos=pos(p);var owner=LEDGER.read(level,nativePos);
   cells.put(p,new TerrainCellSnapshot(p,BlockTransformBinding.stateBlob(level.getBlockState(nativePos)).reference().hash(),BlockTransformBinding.STATE_CODEC,
    Set.of(CORRIDOR),Set.of(),false,true,owner.map(OwnershipRecord::epoch).orElse(new Epoch(0)),owner.map(OwnershipRecord::playerOverrideEpoch).orElse(new Epoch(0))));
   chunks.add(new ChunkRef(p.dimension(),p.x()>>4,p.z()>>4));
  }
  var footprint=new Footprint(dimension,chunks,cells.keySet(),List.of());
  var terrain=new TerrainSnapshot(footprint,cells,List.of(),Map.of(),new Revision(1));
  var context=new ObservationContext(Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),List.of(terrain),Map.of());
  var sample=new SnapshotBatch(new RequestId(UUID.randomUUID()),new ScopeSet(world,Set.of()),tick,Map.of(),context,Set.of(),chunks,HealthMode.NORMAL);
  return new PlanningSnapshot(sample,new OwnershipView(Map.of()),new BudgetView(CAP,BudgetMath.zero(),Map.of()),new BlobBundle(List.of()));
 }
}
