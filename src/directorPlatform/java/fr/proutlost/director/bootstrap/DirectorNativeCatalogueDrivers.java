package fr.proutlost.director.bootstrap;

import fr.proutlost.director.adapter.neoforge.network.*;
import fr.proutlost.director.api.model.*;
import java.util.*;
import java.util.function.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.phys.AABB;

/** Exactly nine original composition drivers, backed by the actual native
 * coordinators. This creates no participant, eligible context or activation. */
public final class DirectorNativeCatalogueDrivers {
 private DirectorNativeCatalogueDrivers(){}
 @FunctionalInterface private interface Capture {
  DirectorNativeCatalogueExecution.Preview capture(Candidate candidate,DecisionInput input,EventInstanceId event);
 }
 private static DirectorNativeCatalogueExecution.Driver driver(BooleanSupplier ready,Capture preview,
   Function<SelectedEventStart,String> begin,Supplier<Optional<UUID>> active,Supplier<String> status,UnaryOperator<DecisionInput> enrich){
  Objects.requireNonNull(ready);Objects.requireNonNull(preview);Objects.requireNonNull(begin);Objects.requireNonNull(active);
  return new DirectorNativeCatalogueExecution.Driver(){
   public boolean available(){return ready.getAsBoolean();}
   public DecisionInput enrich(DecisionInput input){return enrich.apply(input);}
   public DirectorNativeCatalogueExecution.Preview preview(Candidate c,DecisionInput i,EventInstanceId e){return preview.capture(c,i,e);}
   public DirectorNativeCatalogueExecution.StartResult begin(SelectedEventStart selected){
    var message=begin.apply(selected);
    boolean accepted=active.get().filter(selected.eventId().value()::equals).isPresent();
    return new DirectorNativeCatalogueExecution.StartResult(selected.eventId(),accepted,message);
   }
   public Optional<DirectorNativeCatalogueExecution.EventProgress> progress(EventInstanceId event){
    if(active.get().filter(event.value()::equals).isEmpty())return Optional.empty();
    var text=status.get();var stage=(text.contains("RECOVERY_REQUIRED")||text.contains("FAILED_SAFE"))?
     DirectorNativeCatalogueExecution.EventStage.RECOVERING:DirectorNativeCatalogueExecution.EventStage.RUNNING;
    return Optional.of(new DirectorNativeCatalogueExecution.EventProgress(event,stage,text));
   }
  };
 }
 private static DefinitionId id(String name){return new DefinitionId("proutlost_director:candidate/"+name);}
 private static BudgetVector entities(int count,boolean persistent){return new BudgetVector(0,0,0,count,persistent?count:0,0,0,0,0,0);}
 private static BudgetVector blocks(int count){return new BudgetVector(0,0,0,0,0,count,0,0,0,0);}
 private static EventDefinition definition(Candidate candidate,DecisionInput input){
  return Objects.requireNonNull(input.catalog().events().get(candidate.definition().id()));
 }
 private static NativeCataloguePhysicalPlanning.Survey one(EventStep step,Footprint fp,List<ResourceKey> keys,BudgetVector cost){
  return new NativeCataloguePhysicalPlanning.Survey(step.id(),fp,keys,cost);
 }
 private static Footprint faunaFootprint(NativeFaunaHabitat.Layout layout){
  var cells=new HashSet<BlockPosition>();layout.routes().forEach(cells::addAll);
  if(cells.size()!=72)throw new IllegalArgumentException("actual disjoint24x3routes required");
  var dimension=cells.iterator().next().dimension();
  int x0=cells.stream().mapToInt(BlockPosition::x).min().orElseThrow(),x1=cells.stream().mapToInt(BlockPosition::x).max().orElseThrow()+1;
  int y0=cells.stream().mapToInt(BlockPosition::y).min().orElseThrow(),y1=cells.stream().mapToInt(BlockPosition::y).max().orElseThrow()+2;
  int z0=cells.stream().mapToInt(BlockPosition::z).min().orElseThrow(),z1=cells.stream().mapToInt(BlockPosition::z).max().orElseThrow()+1;
  return new Footprint(dimension,layout.chunks(),cells,List.of(new Aabb(dimension,x0,y0-1,z0,x1,y1,z1)));
 }
 private static Set<PlayerId> terrainAudience(MinecraftServer server,Candidate candidate,Footprint fp){
  var ids=new HashSet<>(NativeCataloguePlanning.players(candidate));
  for(var p:server.getPlayerList().getPlayers())if(p.isAlive()&&p.serverLevel().dimension().location().toString().equals(fp.dimension().value()))
   for(var b:fp.bounds())if(p.getBoundingBox().intersects(new AABB(b.minX(),b.minY(),b.minZ(),b.maxXExclusive(),b.maxYExclusive(),b.maxZExclusive())))
    ids.add(new PlayerId(p.getUUID()));
  var spec=Objects.requireNonNull(DirectorGameplayAssets.specs().get(DirectorGameplayAssets.LANDSLIDE_WARNING));
  for(var player:ids)if(DirectorNativeNetworkBootstrap.qualifiedRecipient(server,player,PerceptionKind.SYMBOL,DirectorGameplayAssets.LANDSLIDE_WARNING,spec.hash()).isEmpty())
   throw new IllegalStateException("actual affected audience lacks negotiated WARN renderer");
  return Set.copyOf(ids);
 }
 public static Map<DefinitionId,DirectorNativeCatalogueExecution.Driver> create(MinecraftServer server,
   DirectorNativeGameplay growth,DirectorNativePerceptionGameplay perception,DirectorNativeGoatGameplay goat,
   DirectorNativeFaunaMigrationGameplay fauna,DirectorNativeTerrainDisasterGameplay terrain,
   DirectorNativeTraceGameplay trace,DirectorNativeChoiceGameplay choice){
  Objects.requireNonNull(server);Objects.requireNonNull(growth);Objects.requireNonNull(perception);Objects.requireNonNull(goat);
  Objects.requireNonNull(fauna);Objects.requireNonNull(terrain);Objects.requireNonNull(trace);Objects.requireNonNull(choice);
  var result=new LinkedHashMap<DefinitionId,DirectorNativeCatalogueExecution.Driver>();
  var perceptionDriver=driver(perception::selectedAvailable,perception::preview,perception::beginSelected,
   perception::activeEvent,perception::status,UnaryOperator.identity());
  result.put(id("safe_micro"),perceptionDriver);result.put(id("local_mist"),perceptionDriver);result.put(id("parallel_steps"),perceptionDriver);
  result.put(id("required_signal"),driver(trace::selectedAvailable,trace::preview,trace::beginSelected,trace::activeEvent,
   ()->trace.status().phase()+" "+trace.status().outcome(),UnaryOperator.identity()));
  result.put(id("shared_choice"),driver(choice::selectedAvailable,choice::preview,choice::beginSelected,choice::activeEvent,choice::status,UnaryOperator.identity()));
  result.put(id("goat_guest"),driver(goat::selectedAvailable,(candidate,input,event)->{
   var survey=goat.preview(candidate,input);var uuid=NativeCataloguePhysicalPlanning.goatUuid(event);
   if(!goat.selectedEntityAvailable(uuid))throw new IllegalStateException("selected goat UUID already durably known");
   var step=definition(candidate,input).steps().getFirst();
   return NativeCataloguePhysicalPlanning.capture(server,candidate,input,event,List.of(one(step,survey.footprint(),
    List.of(NativeCataloguePhysicalPlanning.entity(survey.spawn().dimension(),uuid)),entities(1,false))),NativeCataloguePlanning.players(candidate));
  },goat::beginSelected,goat::activeEvent,goat::status,UnaryOperator.identity()));
  result.put(id("animal_migration"),driver(fauna::selectedAvailable,(candidate,input,event)->{
   var layout=fauna.preview(candidate,input);var fp=faunaFootprint(layout);var keys=new ArrayList<ResourceKey>();
   for(int index=0;index<24;index++){
    var uuid=NativeCataloguePhysicalPlanning.faunaUuid(event,index);
    if(!fauna.selectedEntityAvailable(uuid))throw new IllegalStateException("selected fauna UUID already durably known");
    keys.add(NativeCataloguePhysicalPlanning.entity(fp.dimension(),uuid));
   }
   var step=definition(candidate,input).steps().getFirst();
   return NativeCataloguePhysicalPlanning.capture(server,candidate,input,event,List.of(one(step,fp,keys,entities(24,true))),NativeCataloguePlanning.players(candidate));
  },fauna::beginSelected,fauna::activeEvent,fauna::status,UnaryOperator.identity()));
  result.put(id("camp_regrowth"),driver(growth::selectedGrowthAvailable,(candidate,input,event)->{
   var survey=growth.previewGrowth(candidate,input);var step=definition(candidate,input).steps().getFirst();
   return NativeCataloguePhysicalPlanning.capture(server,candidate,input,event,List.of(one(step,survey.footprint(),
    List.of(NativeCataloguePhysicalPlanning.block(survey.position())),blocks(1))),NativeCataloguePlanning.players(candidate));
  },growth::beginSelected,growth::activeGrowthEvent,growth::status,UnaryOperator.identity()));
  result.put(id("controlled_landslide"),driver(terrain::selectedAvailable,(candidate,input,event)->{
   var layout=terrain.preview(candidate,input);var fp=layout.footprint();var audience=terrainAudience(server,candidate,fp);
   var steps=definition(candidate,input).steps();var surveys=new ArrayList<NativeCataloguePhysicalPlanning.Survey>();
   for(var step:steps){
    if(step.args() instanceof Warning)surveys.add(one(step,fp,List.of(),BudgetMath.zero()));
    else if(step.args() instanceof Landslide)surveys.add(one(step,fp,layout.cells().stream().map(NativeCataloguePhysicalPlanning::block).toList(),blocks(layout.cells().size())));
    else throw new IllegalArgumentException("original WARN/landslide steps required");
   }
   return NativeCataloguePhysicalPlanning.capture(server,candidate,input,event,surveys,audience);
  },terrain::beginSelected,terrain::activeEvent,terrain::status,terrain::enrich));
  if(result.size()!=9)throw new IllegalStateException("nine actual native compositions required");return Map.copyOf(result);
 }
}
