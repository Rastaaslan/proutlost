package fr.proutlost.director.bootstrap;

import fr.proutlost.director.api.model.*;
import java.util.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/** Selected-plan physical survey; no OP check, aim, synthetic region tag, chunk load or world write. */
public final class NativeFaunaSelectedHabitat {
 private NativeFaunaSelectedHabitat(){}
 private static boolean includes(Footprint footprint,BlockPosition p){
  if(!p.dimension().equals(footprint.dimension())||!footprint.loadedChunks().contains(
      new ChunkRef(p.dimension(),p.x()>>4,p.z()>>4)))return false;
  if(footprint.cells().isEmpty()&&footprint.bounds().isEmpty())return true;
  return footprint.cells().contains(p)||footprint.bounds().stream().anyMatch(b->
    p.x()>=b.minX()&&p.x()<b.maxXExclusive()&&p.y()>=b.minY()&&p.y()<b.maxYExclusive()
      &&p.z()>=b.minZ()&&p.z()<b.maxZExclusive());
 }
 private static BlockPosition at(BlockPosition p,int x,int z,int dx,int dz){
  return new BlockPosition(p.dimension(),p.x()+dx*x-dz*z,p.y(),p.z()+dz*x+dx*z);
 }
 private static Optional<NativeFaunaHabitat.Layout> survey(ServerLevel level,Footprint footprint,
      BlockPosition origin,int dx,int dz,ResourceId species){
  var routes=new ArrayList<List<BlockPosition>>();var chunks=new HashSet<ChunkRef>();
  // Observe all 23x10 walking cells, including grazed soil and gaps.
  // Each actual start below still requires the complete native species spawn guard.
  for(int z=0;z<10;z++)for(int x=0;x<23;x++){
   var p=at(origin,x,z,dx,dz);
   if(!footprint.loadedChunks().contains(new ChunkRef(p.dimension(),p.x()>>4,p.z()>>4))
       ||!NativeFaunaHabitat.qualified(level,List.of(p),p,false,species))return Optional.empty();
   chunks.add(new ChunkRef(p.dimension(),p.x()>>4,p.z()>>4));
  }
  for(int row=0;row<4;row++)for(int column=0;column<6;column++){
   var route=List.of(at(origin,column*4,row*3,dx,dz),
       at(origin,column*4+1,row*3,dx,dz),at(origin,column*4+2,row*3,dx,dz));
   if(route.stream().anyMatch(p->!includes(footprint,p))
       ||!NativeFaunaHabitat.qualified(level,route,route.getFirst(),true,species))return Optional.empty();
   routes.add(route);
  }
  return Optional.of(new NativeFaunaHabitat.Layout(routes,chunks));
 }
 public static NativeFaunaHabitat.Layout plan(MinecraftServer server,ExecutionPlan selected,
      SnapshotBatch captured){
  if(!server.isSameThread()||selected.effects().size()!=1
      ||!selected.effects().getFirst().primitive().equals(new PrimitiveId("proutlost:fauna_migrate"))
      ||!(selected.effects().getFirst().typedArgs() instanceof FaunaMigrate args)
      ||args.cohortSize()!=24||!NativeFaunaHabitat.supports(args.species())
      ||!args.corridorTag().equals(NativeFaunaHabitat.CORRIDOR)
      ||args.arrivalPolicy()!=ArrivalPolicy.SETTLE_WORLD)
   throw new IllegalArgumentException("selected native24 registered fauna contract");
  return survey(server,selected.effects().getFirst().expectedFootprint(),selected.roles(),captured,args.species());
 }
 public static NativeFaunaHabitat.Layout survey(MinecraftServer server,Footprint footprint,
      RoleAssignments roles,SnapshotBatch captured){
  return survey(server,footprint,roles,captured,NativeFaunaHabitat.SHEEP);
 }
 public static NativeFaunaHabitat.Layout survey(MinecraftServer server,Footprint footprint,
      RoleAssignments roles,SnapshotBatch captured,ResourceId species){
  if(!NativeFaunaHabitat.supports(species))throw new IllegalArgumentException("unknown fauna profile");
  if(!server.isSameThread())throw new IllegalStateException("fauna survey actor");
  ServerLevel level=null;
  for(var current:server.getAllLevels())if(current.dimension().location().toString()
      .equals(footprint.dimension().value())){level=current;break;}
  if(level==null)throw new IllegalArgumentException("selected fauna dimension unavailable");
  var origins=new LinkedHashSet<BlockPosition>();
  footprint.cells().stream().sorted(Comparator.comparingInt(BlockPosition::x)
      .thenComparingInt(BlockPosition::z).thenComparingInt(BlockPosition::y)).limit(8).forEach(origins::add);
  for(var b:footprint.bounds().stream().limit(4).toList()){
   origins.add(new BlockPosition(b.dimension(),b.minX(),b.minY(),b.minZ()));
   if(b.minY()+1<b.maxYExclusive())origins.add(new BlockPosition(b.dimension(),b.minX(),b.minY()+1,b.minZ()));
  }
  if(origins.isEmpty())for(var id:roles.roles().getOrDefault("witness",List.of())){
   var player=captured.context().players().get(id);
   if(player!=null)origins.add(player.position().position());
  }
  int[][] axes={{1,0},{0,1},{-1,0},{0,-1}};
  for(var origin:origins)for(var axis:axes){
   var result=survey(level,footprint,origin,axis[0],axis[1],species);if(result.isPresent())return result.get();
  }
  throw new IllegalArgumentException("no real24animal habitat sealed inside selected footprint");
 }
 /** Planning before J03: only a real tagged Location attached to the real selected region.
  * Returned layout is the exact native survey; caller seals its72 route cells into the plan.
  */
 public static NativeFaunaHabitat.Layout preview(MinecraftServer server,Candidate candidate,
      DecisionInput input){
  if(!server.isSameThread()||!candidate.definition().id().equals(
      new DefinitionId("proutlost_director:candidate/animal_migration")))
   throw new IllegalArgumentException("native migration candidate required");
  var definition=input.catalog().events().get(candidate.definition().id());
  if(definition==null||definition.steps().size()!=1
      ||!(definition.steps().getFirst().args() instanceof FaunaMigrate migration)
      ||!NativeFaunaHabitat.supports(migration.species()))
   throw new IllegalArgumentException("selected registered fauna profile required");
  var regions=candidate.scopes().scopes().stream().filter(s->s.kind()==ScopeKind.REGION)
      .map(s->new RegionId(s.id())).toList();
  if(regions.size()!=1)throw new IllegalArgumentException("one real region required");
  var region=input.context().context().regions().get(regions.getFirst());
  if(region==null||region.region().status()!=RegionStatus.ACTIVE
      ||!region.tags().contains(NativeFaunaHabitat.CORRIDOR))
   throw new IllegalArgumentException("real fauna corridor region unavailable");
  var record=region.region();
  for(var location:input.memory().locations().values().stream()
      .filter(l->l.active()&&l.dimension().equals(record.dimension())
          &&l.tags().contains(NativeFaunaHabitat.CORRIDOR))
      .sorted(Comparator.comparing(LocationRecord::locationId)).limit(4).toList()){
   boolean associated=location.anchorIds().stream().anyMatch(record.anchorIds()::contains)
      ||record.anchorIds().stream().map(input.memory().anchors()::get).filter(Objects::nonNull)
        .anyMatch(a->a.active()&&a.locationId().equals(Optional.of(location.locationId())));
   if(!associated)continue;
   var b=location.bounds();var chunks=new HashSet<ChunkRef>();boolean available=true;
   int minX=b.minX()>>4,maxX=(b.maxXExclusive()-1)>>4;
   int minZ=b.minZ()>>4,maxZ=(b.maxZExclusive()-1)>>4;
   if((long)(maxX-minX+1)*(maxZ-minZ+1)>64)continue;
   for(int x=minX;x<=maxX;x++)for(int z=minZ;z<=maxZ;z++){
    var chunk=new ChunkRef(record.dimension(),x,z);
    if(!input.context().loadedChunks().contains(chunk))available=false;
    chunks.add(chunk);
   }
   if(!available)continue;
   try{return survey(server,new Footprint(record.dimension(),chunks,Set.of(),List.of(b)),
       candidate.roles(),input.context(),migration.species());}
   catch(IllegalArgumentException incompatible){/* Try the next real imported habitat. */}
  }
  throw new IllegalArgumentException("no associated real loaded native fauna Location");
 }
}