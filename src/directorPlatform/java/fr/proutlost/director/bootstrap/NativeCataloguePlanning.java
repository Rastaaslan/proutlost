package fr.proutlost.director.bootstrap;

import fr.proutlost.director.api.model.*;
import java.util.*;

/** Builds only role-bound G0 planning snapshots from actual native preparations. */
public final class NativeCataloguePlanning {
 private NativeCataloguePlanning(){}
 public static Set<PlayerId> players(Candidate candidate) {
  Set<PlayerId> ids=new HashSet<>();candidate.roles().roles().values().forEach(ids::addAll);return Set.copyOf(ids);
 }
 public static Footprint point(BlockPosition p) {
  return new Footprint(p.dimension(),Set.of(new ChunkRef(p.dimension(),p.x()>>4,p.z()>>4)),Set.of(p),List.of());
 }
 public static EffectIntent intent(Candidate candidate,EventDefinition definition,EventStep step,EventInstanceId event,Footprint footprint,DecisionInput input) {
  var primitive=input.catalog().primitives().get(step.primitive());
  if(primitive==null)throw new IllegalArgumentException("actual primitive absent");
  return new EffectIntent(DirectorNativeCatalogueExecution.operation(event,step.id()),step.primitive(),primitive.version(),step.args(),footprint,event);
 }
 public static PlanningSnapshot raw(DecisionInput input) {
  return new PlanningSnapshot(input.context(),input.state().ownership(),input.state().budgets(),new BlobBundle(List.of()));
 }
 public static DirectorNativeCatalogueExecution.Preview captured(Candidate candidate,DecisionInput input,EventInstanceId event,
   List<EffectPreparation> preparations,Set<PlayerId> actualIncidentalAudience) {
  var definition=Objects.requireNonNull(input.catalog().events().get(candidate.definition().id()));
  if(preparations.size()!=definition.steps().size())throw new IllegalArgumentException("all original steps required");
  var snapshots=new ArrayList<PlanningSnapshot>();var effects=new HashMap<OperationToken,PreparedEffect>();
  for(int index=0;index<preparations.size();index++) {
   var preparation=preparations.get(index);var step=definition.steps().get(index);
   var prepared=preparation.prepared().orElseThrow();var intent=prepared.intent();
   if(!preparation.validation().valid()||!prepared.snapshot().equals(input.context()) ||
      !intent.instanceId().equals(event)||!intent.primitive().equals(step.primitive())||!ObservedWeatherArguments.matches(step.args(),intent.typedArgs(),prepared.preconditions()) ||
      !intent.operationToken().equals(DirectorNativeCatalogueExecution.operation(event,step.id())))
    throw new IllegalArgumentException("native preparation changed selected step/snapshot");
   var bindings=new PlanningBindings(candidate.id(),candidate.definition(),event,intent.operationToken(),step.id(),
    candidate.roles(),candidate.scopes(),intent.expectedFootprint(),Set.copyOf(actualIncidentalAudience),true);
   snapshots.add(new PlanningSnapshot(input.context(),input.state().ownership(),input.state().budgets(),
    preparation.blobs(),Optional.of(bindings)));
   if(effects.putIfAbsent(intent.operationToken(),prepared)!=null)throw new IllegalArgumentException("duplicate native step");
  }
  return new DirectorNativeCatalogueExecution.Preview(snapshots,effects);
 }
 public static AnchorRecord anchor(Candidate candidate,DecisionInput input,TagId tag) {
  var regions=candidate.scopes().scopes().stream().filter(s->s.kind()==ScopeKind.REGION).map(s->new RegionId(s.id())).toList();
  var players=players(candidate);if(players.isEmpty())throw new IllegalArgumentException("real participant absent");
  var witness=input.context().context().players().get(players.stream().sorted().findFirst().orElseThrow());
  if(witness==null)throw new IllegalArgumentException("actual witness snapshot absent");
  var point=witness.position().position();
  var anchors=input.context().context().anchors().values().stream()
   .filter(a->a.active()&&a.tags().contains(tag)&&a.dimension().equals(point.dimension()))
   .filter(a->regions.isEmpty()||a.regionIds().containsAll(regions))
   .filter(a->input.context().loadedChunks().contains(new ChunkRef(a.dimension(),a.position().x()>>4,a.position().z()>>4)))
   .sorted(Comparator.comparingDouble((AnchorRecord a)->distance(point,a.position())).thenComparing(a->a.anchorId().value())).toList();
  if(anchors.isEmpty())throw new IllegalArgumentException("real tagged loaded anchor absent: "+tag.value());
  return anchors.getFirst();
 }
 private static double distance(BlockPosition a,BlockPosition b) {
  double x=(double)a.x()-b.x(),y=(double)a.y()-b.y(),z=(double)a.z()-b.z();return x*x+y*y+z*z;
 }
}
