package fr.proutlost.director.memory;

import fr.proutlost.director.adapter.neoforge.server.observation.*;
import fr.proutlost.director.api.codec.ModelCodec;
import fr.proutlost.director.api.model.*;
import java.time.Instant;
import java.util.*;
import static fr.proutlost.director.memory.MemoryIntegrationTest.*;

/** Pure integration regression for genuine sampled camp episodes in an ALREADY
 * authored region. No client, physical player interaction or J04 fsync claim. */
public final class MemoryCampRoutineConfirmationTests {
 private MemoryCampRoutineConfirmationTests(){}
 private record Evidence(MemoryView state,AnchorRecord possible,RegionRecord authored,long end,CampEpisodeObservation last){}
 private static List<AggregateChange> anchors(Reduction value){return value.aggregateChanges().stream().filter(c->c.next() instanceof AnchorAggregate).toList();}
 private static void stableRegion(RegionRecord before,RegionRecord after){
  check(before.regionId().equals(after.regionId())&&before.dimension().equals(after.dimension())&&before.status()==after.status()
   &&before.supportShape().equals(after.supportShape())&&before.anchorIds().equals(after.anchorIds())
   &&before.parentIds().equals(after.parentIds())&&before.aliases().equals(after.aliases())
   &&before.membershipRevision().equals(after.membershipRevision())&&before.memoryRefs().equals(after.memoryRefs()),
   "camp confirmation must not invent authored region/support/membership/lineage");
 }
 private static AnchorRecord anchorState(AnchorRecord old,AnchorOrigin origin,EvidenceStatus evidence,boolean active,long at){
  return new AnchorRecord(new AggregateHeader(1,old.header().id(),old.header().revision(),old.header().lastSequence(),tick(at)),
   old.anchorId(),old.locationId(),old.regionIds(),old.dimension(),old.position(),old.radiusProfile(),old.tags(),origin,evidence,active);
 }
 private static MemoryView seed(RegionRecord region,RegionMemory memory,AnchorRecord anchor){
  return MemoryProjection.of(all,List.of(new RegionAggregate(region),new RegionMemoryAggregate(memory),new AnchorAggregate(anchor)));
 }
 private static Evidence sampledAssignedCamp(){
  var aid=new AnchorId(id(66010));var authored=region(66011,Set.of(aid),700);
  var possible=new AnchorRecord(header(id(66012),1,0),aid,Optional.of(new LocationId(id(66013))),Set.of(authored.regionId()),
   dimension,new BlockPosition(dimension,700,64,0),"region.camp_radius_blocks",
   Set.of(new TagId("proutlost:camp"),new TagId("proutlost:tree_of_life"),new TagId("fixture:retain_me")),
   AnchorOrigin.CAMP,EvidenceStatus.POSSIBLE,true);
  var before=seed(authored,memory(authored,0,Set.of(new FactId(id(66014)))),possible);
  var sampler=new CampSampler(32,12,200,1200);var pipeline=new ObservationPipeline(new ObservationBuffer(world,200,32),sampler);
  var continuity=new ObservationContinuity(40);var player=new PlayerId(id(66015));var reducer=new MemoryReducer(settings);
  CampEpisodeObservation last=null;long end=0;
  for(int visit=0;visit<7;visit++){
   long start=40+visit*1600L;end=200+visit*1600L;
   for(long now=start;now<=start+80;now+=40)if(continuity.camp(player,Optional.of(aid),PlayerEligibility.ELIGIBLE,tick(now)))
    check(sampler.observe(before.anchors().get(aid),authored.regionId(),List.of(new PositionSnapshot(possible.position(),tick(now))),
     tick(now),visit*2000L,40,32),"real continuity-qualified position reaches CampSampler");
   var input=pipeline.seal(snapshot(end,before),before);check(input.campEpisodes().size()==1,"one real sealed camp window per visit");
   last=input.campEpisodes().getFirst();var prior=before;
   var reduced=reducer.reduce(prior,input,new DirectorClock(tick(end),0,Instant.EPOCH));
   var codec=new ModelCodec<>(Reduction.class);check(codec.decode(codec.encode(reduced)).equals(reduced),"promotion shares the exact serializable Reduction");
   check(new HashSet<>(reduced.consumedInputs()).equals(new HashSet<>(input.inputIds())),"same batch consumes the actual camp observation");
   var changes=anchors(reduced);var projected=apply(prior,reduced); // Existing helper verifies every AggregateKey CAS.
   var camp=projected.regions().getFirst().activity().camps().get(aid);
   if(visit<6){
    check(changes.isEmpty()&&projected.anchors().get(aid).evidence()==EvidenceStatus.POSSIBLE,
     "visits below actual confidence threshold cannot confirm an anchor");
   }else{
    check(camp.independentVisits()==7&&camp.independentVisits()>=settings.count("pattern.camp_min_visits")
     &&camp.confidence().value()>=settings.number("pattern.camp_confidence_min"),"seven independent sampled episodes cross the actual configured gate");
    check(prior.anchors().get(aid).evidence()==EvidenceStatus.POSSIBLE&&changes.size()==1,"one atomic promotion, never optimistic before reduction apply");
    var promoted=((AnchorAggregate)changes.getFirst().next()).value();
    check(changes.getFirst().expectedRevision().equals(Optional.of(possible.header().revision()))
     &&promoted.header().revision().equals(possible.header().revision().plus(1))&&promoted.header().id().equals(possible.header().id())
     &&promoted.header().lastSequence().equals(possible.header().lastSequence()),"promotion retains independent anchor CAS/header identity");
    var expected=new AnchorRecord(promoted.header(),possible.anchorId(),possible.locationId(),possible.regionIds(),possible.dimension(),
     possible.position(),possible.radiusProfile(),possible.tags(),possible.origin(),EvidenceStatus.CONFIRMED,true);
    check(promoted.equals(expected),"only evidence/header advance; location/tags/origin/position remain exact");
    check(reduced.aggregateChanges().stream().anyMatch(c->c.next() instanceof RegionMemoryAggregate)
     &&reduced.aggregateChanges().stream().anyMatch(c->c.next() instanceof RegionAggregate),"anchor promotion is in the normal memory/region batch");
    rejects(()->reducer.reduce(projected,input,new DirectorClock(input.context().capturedTick(),0,Instant.EPOCH)),"old observation CAS cannot apply promotion twice");
   }
   check(projected.regionRecords().keySet().equals(Set.of(authored.regionId())),"existing region identity remains the only region");
   stableRegion(authored,projected.regionRecords().get(authored.regionId()));
   check(projected.regions().getFirst().durableFactRefs().contains(new FactId(id(66014))),"existing durable memory reference is preserved");
   before=projected;pipeline.acknowledge(input.inputIds(),tick(end));
  }
  return new Evidence(before,possible,authored,end,Objects.requireNonNull(last));
 }
 private static void exclusionsAndReplay(Evidence e){
  var reducer=new MemoryReducer(settings);var aid=e.possible().anchorId();var id=e.authored().regionId();var now=e.end()+1;
  var stored=e.state().regions().getFirst();var record=e.state().regionRecords().get(id);
  // These cold fixtures model the already-observed pre-fix world: qualifying
  // historical routine exists but evidence is still POSSIBLE. They are not new observations.
  for(var origin:List.of(AnchorOrigin.IMPORTED,AnchorOrigin.CAMP)){
   boolean active=origin==AnchorOrigin.IMPORTED;
   var excluded=anchorState(e.possible(),origin,EvidenceStatus.POSSIBLE,active,now);
   var before=seed(record,stored,excluded);
   var result=reducer.reduce(before,batch(now,before,List.of(),List.of(),List.of(),Map.of()),new DirectorClock(tick(now),0,Instant.EPOCH));
   check(anchors(result).isEmpty()&&apply(before,result).anchors().get(aid).equals(excluded),
    active?"camp tag/routine cannot promote an imported anchor":"historical camp routine cannot reactivate/promote a player-broken camp");
   if(!active){
    var old=e.last();var raw=new CampEpisodeObservation(new ObservationId(id(66016)),id,aid,old.windowId(),old.episodeId(),old.windowStart(),old.windowEnd(),old.occupiedTicks(),old.histogram());
    rejects(()->reducer.reduce(before,batch(now,before,List.of(),List.of(raw),List.of(),Map.of()),new DirectorClock(tick(now),0,Instant.EPOCH)),
     "new camp input for a broken anchor remains rejected by the existing ingress contract");
   }
  }
  var old=e.last();var duplicate=new CampEpisodeObservation(new ObservationId(id(66017)),id,aid,old.windowId(),old.episodeId(),old.windowStart(),old.windowEnd(),old.occupiedTicks(),old.histogram());
  var replay=reducer.reduce(e.state(),batch(now,e.state(),List.of(),List.of(duplicate),List.of(),Map.of()),new DirectorClock(tick(now),0,Instant.EPOCH));
  var after=apply(e.state(),replay);check(anchors(replay).isEmpty()&&after.anchors().get(aid).equals(e.state().anchors().get(aid))
   &&after.regions().getFirst().activity().camps().get(aid).independentVisits()==7,"duplicate sealed window cannot add visits or re-promote the confirmed anchor");
 }
 private static void sharedAnchorIsWrittenOnce(Evidence e){
  var first=e.state().regionRecords().get(e.authored().regionId());var second=region(66020,Set.of(e.possible().anchorId()),700);
  var a=e.possible();var shared=new AnchorRecord(a.header(),a.anchorId(),a.locationId(),Set.of(first.regionId(),second.regionId()),a.dimension(),a.position(),a.radiusProfile(),a.tags(),a.origin(),a.evidence(),true);
  var old=e.state().regions().getFirst();var copy=new RegionMemory(header(id(66021),1,e.end()),second.regionId(),old.components(),old.lastVisitTick(),old.lastEventTick(),old.persistentStateRefs(),old.durableFactRefs(),old.activity());
  var before=MemoryProjection.of(all,List.of(new RegionAggregate(first),new RegionAggregate(second),new RegionMemoryAggregate(old),new RegionMemoryAggregate(copy),new AnchorAggregate(shared)));
  var now=e.end()+1;var reduced=new MemoryReducer(settings).reduce(before,batch(now,before,List.of(),List.of(),List.of(),Map.of()),new DirectorClock(tick(now),0,Instant.EPOCH));
  check(anchors(reduced).size()==1&&reduced.aggregateChanges().stream().map(AggregateChange::key).distinct().count()==reduced.aggregateChanges().size(),
   "same qualifying CAMP in two existing regions produces only one anchor aggregate write");
  var after=apply(before,reduced);check(after.anchors().get(a.anchorId()).evidence()==EvidenceStatus.CONFIRMED
   &&after.anchors().get(a.anchorId()).regionIds().equals(shared.regionIds())&&after.regionRecords().keySet().equals(before.regionRecords().keySet()),
   "multi-region promotion preserves memberships and creates no new region");
 }
 public static void run(){
  int start=checks;var sampled=sampledAssignedCamp();exclusionsAndReplay(sampled);sharedAnchorIsWrittenOnce(sampled);
  System.out.println("PASS existing authored-region CAMP confirmation integration assertions="+(checks-start)+" pure TEST_ONLY no GUI/fsync claim");
 }
}
