package fr.proutlost.director.memory;

import fr.proutlost.director.api.model.*;
import java.util.*;

/** Pure regional consequences from real durable source records.
 * Caller serializes against the observation-memory writer and persists returned changes.
 * No region, anchor tag, natural risk, synthetic fact, or execution authority is created.
 */
public final class ChainStateMemoryProjection {
 private static final ResourceId SCAR=new ResourceId("proutlost:scar_still_relevant");
 private static final FactType LANDSLIDE=new FactType("proutlost:controlled_landslide_observed");
 private static final PrimitiveId TERRAIN=new PrimitiveId("proutlost:landslide");
 private ChainStateMemoryProjection(){}

 /** This pair can be appended atomically with a proposed ChainAggregate/source fact.
  * Alternatively a consequence owner may append it after the source ACK, before exposing completion.
  * RegionMemory has its own AggregateKey revision; do not CAS RegionRecord's ScopeRef revision.
  */
 public record Change(RegionMemory before,RegionMemory after) {
  public DurableRecord record(){return new DurableAggregate(new RegionMemoryAggregate(after));}
 }

 private static Optional<RegionMemory> unique(MemoryView memory,RegionId region){
  var record=memory.regionRecords().get(region);
  if(record==null||!Set.of(RegionStatus.ACTIVE,RegionStatus.DORMANT).contains(record.status()))
   return Optional.empty();
  var matches=memory.regions().stream().filter(m->m.regionId().equals(region)).toList();
  return matches.size()==1?Optional.of(matches.getFirst()):Optional.empty();
 }
 private static Change change(RegionMemory before,Set<ResourceId> states,Set<FactId> facts,Tick eventTick){
  if(eventTick.compareTo(before.header().updatedTick())<0)eventTick=before.header().updatedTick();
  var h=before.header();
  var after=new RegionMemory(new AggregateHeader(h.schemaVersion(),h.id(),h.revision().plus(1),
    h.lastSequence(),eventTick),before.regionId(),before.components(),before.lastVisitTick(),
    before.lastEventTick().compareTo(eventTick)>=0?before.lastEventTick():eventTick,
    states,facts,before.activity());
  return new Change(before,after);
 }

 /** The regional state comes ONLY from the actual pinned chain's terminal branch.
  * lineageRegion must be the real region pinned at chain creation, not a region inferred from UUIDs.
  */
 public static Optional<Change> settlement(MemoryView memory,RegionId lineageRegion,
      ChainInstance chain,Tick now){
  if(chain.state()!=ChainState.SETTLED_AS_WORLD_STATE||chain.regionalStateRef().isEmpty()
      ||!chain.lineageRefs().contains(lineageRegion)||chain.consumedFactIds().isEmpty())
   return Optional.empty();
  var known=unique(memory,lineageRegion);if(known.isEmpty())return Optional.empty();
  var before=known.get();var states=new HashSet<>(before.persistentStateRefs());
  var facts=new HashSet<>(before.durableFactRefs());
  boolean changed=states.add(chain.regionalStateRef().orElseThrow());
  changed|=facts.addAll(chain.consumedFactIds());
  return changed?Optional.of(change(before,states,facts,now)):Optional.empty();
 }

 /** Sources must be the COMPLETE known regional landslide source set at the same J04 cut.
  * payload.mutation_refs seals each source's exact complete actual impact mutation UUIDs.
  * Never remove a regional flag using only one source while another scar remains active.
  * VERIFIED/COMMITTED means a remaining scar; ALL source mutations ROLLED_BACK means restored.
  * Empty, incomplete, YIELDED/CONFLICT/uncertain evidence never authorizes a false flag.
  */
 private record ScarEvidence(boolean active,Set<FactId> sourceFacts){}
 private static Optional<ScarEvidence> evidence(RegionId region,
      Collection<DirectorFact> regionalSources,Collection<MutationRecord> latest){
  if(regionalSources.isEmpty())return Optional.empty();
  var expected=new HashMap<MutationId,EventInstanceId>();var sourceFacts=new HashSet<FactId>();
  for(var source:regionalSources){
   if(!source.factType().equals(LANDSLIDE)||source.evidenceStatus()!=EvidenceStatus.CONFIRMED
       ||!source.durable()||source.eventId().isEmpty()
       ||!source.subjectRefs().contains(new ScopeRef(ScopeKind.REGION,region.value()))
       ||!sourceFacts.add(source.factId()))return Optional.empty();
   if(!(source.payload().get("mutation_refs") instanceof JsonValue.Array refs)||refs.values().isEmpty())
    return Optional.empty();
   var perSource=new HashSet<MutationId>();
   try {
    for(var value:refs.values()){
     if(!(value instanceof JsonValue.Text text))return Optional.empty();
     var id=new MutationId(UUID.fromString(text.value()));
     if(!perSource.add(id))return Optional.empty();
     var prior=expected.putIfAbsent(id,source.eventId().orElseThrow());
     if(prior!=null&&!prior.equals(source.eventId().orElseThrow()))return Optional.empty();
    }
   }catch(IllegalArgumentException malformed){return Optional.empty();}
  }
  var selected=new HashMap<MutationId,MutationRecord>();
  for(var row:latest)if(expected.containsKey(row.mutationId())){
   if(selected.putIfAbsent(row.mutationId(),row)!=null)return Optional.empty();
  }
  if(!selected.keySet().equals(expected.keySet()))return Optional.empty();
  boolean active=false,allRestored=true;
  for(var row:selected.values()){
   if(!row.instanceId().equals(expected.get(row.mutationId()))||!row.primitiveId().equals(TERRAIN)
       ||row.durableSeq().value()<=0)return Optional.empty();
   switch(row.state()){
    case VERIFIED,COMMITTED->{active=true;allRestored=false;}
    case ROLLED_BACK->{}
    default->{allRestored=false;}
   }
  }
  if(!active&&!allRestored)return Optional.empty();
  return Optional.of(new ScarEvidence(active,Set.copyOf(sourceFacts)));
 }
 /** UNKNOWN is empty; false requires complete proven restoration of all regional scars. */
 public static Optional<Boolean> scarValue(RegionId region,Collection<DirectorFact> sources,
      Collection<MutationRecord> latest){return evidence(region,sources,latest).map(ScarEvidence::active);}
 public static Optional<Change> scar(MemoryView memory,RegionId region,
      Collection<DirectorFact> sources,Collection<MutationRecord> latest,Tick now){
  var proven=evidence(region,sources,latest);var known=unique(memory,region);
  if(proven.isEmpty()||known.isEmpty())return Optional.empty();
  var before=known.get();var states=new HashSet<>(before.persistentStateRefs());
  var facts=new HashSet<>(before.durableFactRefs());boolean changed;
  if(proven.get().active())changed=states.add(SCAR);else changed=states.remove(SCAR);
  changed|=facts.addAll(proven.get().sourceFacts());
  return changed?Optional.of(change(before,states,facts,now)):Optional.empty();
 }
}