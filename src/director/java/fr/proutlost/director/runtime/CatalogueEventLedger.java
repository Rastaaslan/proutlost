package fr.proutlost.director.runtime;

import fr.proutlost.director.api.codec.ModelCodec;
import fr.proutlost.director.api.model.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Pure records for the existing PREPARE/RESULT/END transactions. No append,
 * invented ACK/sequence, client receipt, effect, eligibility or temporal cache.
 * Caller supplies the latest actual one-cut DecisionInput and includes returned
 * blobs AND records in the same real provider transaction. PREPARE.uniqueness
 * owns reservations; a physical/player-edited effect never releases spent use.
 * Completion is called only on the native driver's actual receipt/outcome gate. */
public final class CatalogueEventLedger {
 private CatalogueEventLedger(){}
 public record Selection(EventDefinition definition,ExecutionPlan plan){
  public Selection {
   Objects.requireNonNull(definition);Objects.requireNonNull(plan);
   var ref=new DefinitionRef(definition.id(),definition.contentRevision(),BlobContent.hash(new ModelCodec<>(EventDefinition.class).encode(definition)));
   if(!plan.definition().equals(ref)||plan.effects().size()!=definition.steps().size()||plan.effects().isEmpty())
    throw new IllegalArgumentException("exact selected original definition/steps required");
   var event=plan.effects().getFirst().instanceId();var operations=new HashSet<OperationToken>();
   for(int i=0;i<plan.effects().size();i++){
    var effect=plan.effects().get(i);var step=definition.steps().get(i);
    if(!effect.instanceId().equals(event)||!effect.primitive().equals(step.primitive())||!ObservedWeatherArguments.matches(step.args(),effect.typedArgs(),plan.readSet())||!operations.add(effect.operationToken()))
     throw new IllegalArgumentException("exact selected original operation required");
   }
  }
  public EventInstanceId event(){return plan.effects().getFirst().instanceId();}
 }
 /** A current one-cut durable ledger view. It is NOT a decision, observation, eligibility,
  * live-world, reservation, or execution authority carrier. Cold cleanup/completion needs no J01.
  * The native reader proves WORLD session/generation/boot and each positive durable row.
  */
 public record Metadata(DirectorWorldState world,Map<RegionId,RegionRecord> regions,
      List<CooldownRecord> cooldowns,List<UniqueUseRecord> uniqueness){
  public Metadata{Objects.requireNonNull(world);regions=Map.copyOf(regions);cooldowns=List.copyOf(cooldowns);uniqueness=List.copyOf(uniqueness);}
 }
 private static Metadata metadata(Selection selection,DecisionInput current){
  Objects.requireNonNull(current);
  if(!selection.plan().scopes().worldId().equals(current.state().world().worldId())
      ||!Objects.equals(current.catalog().events().get(selection.definition().id()),selection.definition()))
   throw new IllegalArgumentException("current actual world/catalogue mismatch");
  return new Metadata(current.state().world(),current.memory().regionRecords(),current.state().cooldowns(),current.state().uniqueness());
 }
 // The established live DecisionInput API keeps its catalogue-identity check. Cold callers use
 // the Metadata overloads below, with the original pinned Selection and actual current WAL rows.
 public static List<UniqueUseRecord> reserve(Selection s,DecisionInput current,ReservationToken token,Tick now){
  return reserve(s,metadata(s,current),token,now);
 }
 public static Contribution effect(Selection s,DecisionInput current,PrepareRecord prepare,SliceResult actual,Tick now){
  return effect(s,metadata(s,current),prepare,actual,now);
 }
 public static Contribution completed(Selection s,DecisionInput current,DirectorFact fact,Tick now){
  return completed(s,metadata(s,current),fact,now);
 }
 public static Contribution completedComposed(Selection s,DecisionInput current,DirectorFact fact,Tick now){
  return completedComposed(s,metadata(s,current),fact,now);
 }
 public static Contribution outcomeUnchanged(Selection s,DecisionInput current,PrepareRecord offer,DirectorFact closed,
      AnchorRecord before,AnchorRecord after,Tick now){
  return outcomeUnchanged(s,metadata(s,current),offer,closed,before,after,now);
 }
 public static Contribution completed(Selection s,DecisionInput current,PrepareRecord prepare,DirectorFact fact,Tick now){
  return completed(s,metadata(s,current),prepare,fact,now);
 }
 public static DecisionInput afterPrepare(Selection s,DecisionInput current,PrepareRecord prepare){
  var next=afterPrepare(s,metadata(s,current),prepare);var old=current.state();
  var state=new DecisionState(old.world(),old.budgets(),old.ownership(),old.cooldowns(),next.uniqueness(),old.facts(),old.events(),old.chains(),old.disasters());
  return new DecisionInput(current.context(),current.memory(),current.catalog(),current.config(),current.requests(),current.chains(),current.seed(),state,current.advisorySnapshot());
 }
 public static Contribution aborted(Selection s,DecisionInput current,PrepareRecord prepare,SliceResult actual,Tick now){
  return aborted(s,metadata(s,current),prepare,actual,now);
 }
 public static Set<AggregateKey> keys(Selection s,DecisionInput current){return keys(s,metadata(s,current));}
 public record Contribution(List<DurableRecord> records,List<BlobContent> blobs){
  public Contribution {records=List.copyOf(records);blobs=List.copyOf(blobs);}
 }
 private record Scope(UUID canonical,Set<UUID> origins){}
 private record Lineage(Set<UUID> ids,boolean complete){}
 private static final Contribution EMPTY=new Contribution(List.of(),List.of());
 private static void identity(Selection s,Metadata current){
  Objects.requireNonNull(current);
  if(!s.plan().scopes().worldId().equals(current.world().worldId()))
   throw new IllegalArgumentException("current actual world/catalogue mismatch");
 }
 private static Set<PlayerId> players(Selection s){
  var result=new HashSet<PlayerId>();s.plan().roles().roles().values().forEach(result::addAll);return Set.copyOf(result);
 }
 private static Set<RegionId> regions(Selection s){
  var result=new HashSet<RegionId>();for(var scope:s.plan().scopes().scopes())if(scope.kind()==ScopeKind.REGION)result.add(new RegionId(scope.id()));return Set.copyOf(result);
 }
 /** Same parent/alias closure as EligibilityEngine; unavailable parents remain unknown. */
 private static Lineage lineage(RegionId region,Metadata current){
  var ids=new HashSet<UUID>();var pending=new ArrayDeque<RegionId>();pending.add(region);boolean complete=true;
  while(!pending.isEmpty()){
   var id=pending.removeFirst();if(!ids.add(id.value()))continue;
   var row=current.regions().get(id);if(row==null){complete=false;continue;}
   pending.addAll(row.parentIds());row.aliases().forEach(alias->ids.add(alias.value()));
  }
  return new Lineage(Set.copyOf(ids),complete);
 }
 private static List<Scope> scopes(Selection s,Metadata current){return scopes(s,current,true);}
 private static List<Scope> scopes(Selection s,Metadata current,boolean requireCompleteLineage){
  return switch(s.definition().uniqueness().scope()){
   case REPEATABLE->List.of();
   case ONCE_PER_WORLD->List.of(new Scope(s.plan().scopes().worldId().value(),Set.of(s.plan().scopes().worldId().value())));
   case ONCE_PER_PLAYER->players(s).stream().sorted(Comparator.comparing(p->p.value().toString())).map(p->new Scope(p.value(),Set.of(p.value()))).toList();
   case ONCE_PER_REGION->{
    var regions=regions(s);if(regions.size()!=1)throw new IllegalStateException("one actual selected region required for unique use");
    var region=regions.iterator().next();var closure=lineage(region,current);
    if(requireCompleteLineage&&!closure.complete())throw new IllegalStateException("actual region lineage unavailable for unique use");
    yield List.of(new Scope(region.value(),closure.ids()));
   }
  };
 }
 private static Optional<UniqueUseRecord> prior(Selection s,Metadata current,Scope scope){
  var matches=current.uniqueness().stream().filter(u->u.definitionId().equals(s.definition().id())&&
   u.scopeKind()==s.definition().uniqueness().scope()&&u.canonicalScopeId().equals(scope.canonical())).toList();
  if(matches.size()>1)throw new IllegalArgumentException("duplicate actual unique projection");return matches.stream().findFirst();
 }
 private static UUID uniqueId(Selection s,Scope scope){return named("catalogue-unique-v1",s.plan().scopes().worldId().value(),s.definition().id().value(),s.definition().uniqueness().scope(),scope.canonical());}
 /** Put these rows ONLY in PrepareRecord.uniqueness, not a second top-level copy.
  * A later original step reuses this event's already durable reservation/spend. */
 public static List<UniqueUseRecord> reserve(Selection s,Metadata current,ReservationToken token,Tick now){
  identity(s,current);if(!token.instanceId().equals(s.event()))throw new IllegalArgumentException("actual selected event reservation required");
  var rows=new ArrayList<UniqueUseRecord>();
  for(var scope:scopes(s,current)){
   for(var use:current.uniqueness())if(use.definitionId().equals(s.definition().id())&&use.scopeKind()==s.definition().uniqueness().scope()&&
     use.status()!=UniqueStatus.RELEASED_BEFORE_EFFECT&&!use.instanceId().equals(s.event())&&
     (scope.origins().contains(use.canonicalScopeId())||!Collections.disjoint(scope.origins(),use.originScopeIds())))
    throw new IllegalStateException("selected uniqueness already reserved or spent");
   var old=prior(s,current,scope).orElse(null);
   if(old!=null&&old.status()!=UniqueStatus.RELEASED_BEFORE_EFFECT){
    if(!old.instanceId().equals(s.event()))throw new IllegalStateException("selected uniqueness already reserved or spent");
    continue;
   }
   rows.add(new UniqueUseRecord(next(old==null?null:old.header(),old==null?uniqueId(s,scope):old.header().id(),now),
    s.definition().id(),s.definition().uniqueness().scope(),scope.canonical(),UniqueStatus.RESERVED,Optional.of(token),s.event(),Optional.empty(),scope.origins()));
  }
  return List.copyOf(rows);
 }
 /** Called from the actual EventRunner RESULT builder; never predicts an apply.
  * S2C success alone is POSSIBLE perception; the later native receipt-gated
  * original completion can upgrade it. OUTCOME_CONFIRMED is not consumed here. */
 public static Contribution effect(Selection s,Metadata current,PrepareRecord prepare,SliceResult actual,Tick now){
  identity(s,current);Objects.requireNonNull(prepare);Objects.requireNonNull(actual);
  var intent=s.plan().effects().stream().filter(i->i.operationToken().equals(prepare.operationToken())).findFirst()
   .orElseThrow(()->new IllegalArgumentException("result is not an original selected operation"));
  if(!prepare.slice().operations().equals(List.of(intent))||!prepare.reservation().instanceId().equals(s.event()))
   throw new IllegalArgumentException("actual selected PREPARE binding required");
  if(!Set.of(intent.operationToken()).containsAll(actual.appliedOperationIds()))throw new IllegalArgumentException("foreign actual result operation");
  boolean applied=actual.appliedOperationIds().contains(intent.operationToken());
  boolean possible=applied||actual.outcome()==Outcome.DELIVERY_UNCERTAIN||!actual.observedHashes().isEmpty();
  if(!possible)return EMPTY;
  var expected=new HashMap<ResourceKey,Fingerprint>();prepare.durableBlobs().expectedPostStates().forEach((k,v)->expected.put(k,v.hash()));
  boolean perception=intent.typedArgs() instanceof TargetSound||intent.typedArgs() instanceof Apparition||intent.typedArgs() instanceof LocalWeather||intent.typedArgs() instanceof Warning;
  boolean confirmed=applied&&!perception&&(actual.outcome()==Outcome.SUCCEEDED||actual.outcome()==Outcome.RECONCILED)&&
   actual.remainingWork()==0&&actual.observedHashes().equals(expected);
  current=afterPrepare(s,current,prepare);
  var rows=new ArrayList<DurableRecord>();var blobs=new ArrayList<BlobContent>();
  if(s.definition().uniqueness().consumeOn()==ConsumeOn.EFFECT_CONFIRMED){
   var result=new ResultRecord(prepare.transactionId(),prepare.operationToken(),actual,Set.of(),List.of(),List.of());
   var evidence=BlobContent.capture(new ResourceId("proutlost_director:catalogue_effect_evidence_v1"),1,new ModelCodec<>(ResultRecord.class).encode(result));
   rows.addAll(spend(s,current,confirmed?UniqueStatus.SPENT_CONFIRMED:UniqueStatus.SPENT_POSSIBLE,evidence.reference(),now));
   if(!rows.isEmpty())blobs.add(evidence);
  }
  rows.addAll(cooldowns(s,current,now));return new Contribution(rows,blobs);
 }
 /** Must be included with the actual original completion fact after the driver's
  * real outcome/receipt gate, never on timeout/cleanup/yield/offer publication. */
 public static Contribution completed(Selection s,Metadata current,DirectorFact fact,Tick now){
  identity(s,current);Objects.requireNonNull(fact);
  var expectedId=new FactId(named("catalogue-completion-v1",s.event().value()));
  if(!fact.factId().equals(expectedId)||!fact.factType().equals(s.definition().completion().factType())||
    !fact.eventId().equals(Optional.of(s.event()))||!fact.durable()||fact.evidenceStatus()!=EvidenceStatus.CONFIRMED||
    !new HashSet<>(fact.subjectRefs()).equals(s.plan().scopes().scopes())||
    !Objects.equals(fact.payload().get("definition"),new JsonValue.Text(s.definition().id().value()))||
    !Objects.equals(fact.payload().get("source"),new JsonValue.Text("CATALOGUE_SELECTED")))
   throw new IllegalArgumentException("actual original selected completion fact required");
  var evidence=BlobContent.capture(new ResourceId("proutlost_director:catalogue_outcome_evidence_v1"),1,new ModelCodec<>(DirectorFact.class).encode(fact));
  var unique=spend(s,current,UniqueStatus.SPENT_CONFIRMED,evidence.reference(),now);
  // effect() owns cooldowns; do not duplicate the same aggregate revision when
  // a one-step OUTCOME completion shares the physical RESULT transaction.
  return new Contribution(unique,unique.isEmpty()?List.of():List.of(evidence));
 }
 /** A real composed driver (for example 24 spawn/navigation/arrival operations)
  * has no fabricated original-cohort PREPARE. Its genuine original completion
  * fact can start cooldowns at END; no synthetic SliceResult is introduced. */
 public static Contribution completedComposed(Selection s,Metadata current,DirectorFact fact,Tick now){
  var completion=completed(s,current,fact,now);var rows=new ArrayList<>(completion.records());
  rows.addAll(cooldowns(s,current,now));return new Contribution(rows,completion.blobs());
 }
 /** KEEP_UNCHANGED is an outcome absence, not a failed offer send. Called with
  * the real CLOSED row and exact original/current anchor records, in that same
  * END batch. Equality includes revision and tags, so an applied then edited
  * branch cannot pretend its outcome never happened. */
 public static Contribution outcomeUnchanged(Selection s,Metadata current,PrepareRecord offer,DirectorFact closed,
   AnchorRecord before,AnchorRecord after,Tick now){
  identity(s,current);Objects.requireNonNull(before);Objects.requireNonNull(after);Objects.requireNonNull(closed);
  if(s.definition().uniqueness().consumeOn()!=ConsumeOn.OUTCOME_CONFIRMED||!before.equals(after)||!before.active()||
    !closed.factType().equals(new FactType("proutlost_director:lab_choice_closed"))||!closed.eventId().equals(Optional.of(s.event()))||
    !closed.durable()||closed.evidenceStatus()!=EvidenceStatus.CONFIRMED||
    !(closed.payload().get("reason") instanceof JsonValue.Text reason)||!reason.value().contains("KEEP_UNCHANGED"))
   throw new IllegalArgumentException("actual unchanged choice outcome/closure required");
  var original=s.plan().effects().stream().filter(i->i.operationToken().equals(offer.operationToken())).findFirst().orElseThrow();
  if(!(original.typedArgs() instanceof SharedChoice choice)||choice.timeoutPolicy()!=TimeoutPolicy.KEEP_UNCHANGED||
    !before.tags().contains(choice.anchorTag())||!offer.slice().operations().equals(List.of(original))||
    !offer.durableBlobs().expectedPostStates().isEmpty()||!offer.reservation().instanceId().equals(s.event()))
   throw new IllegalArgumentException("original unchanged choice offer PREPARE required");
  var key=new ResourceKey(OwnershipDomain.ANCHOR,before.dimension(),before.anchorId().value().toString());
  var anchor=BlobContent.capture(new ResourceId("proutlost_director:anchor_state_v1"),1,new ModelCodec<>(AnchorRecord.class).encode(before));
  if(offer.durableBlobs().preStates().size()!=1||!anchor.reference().equals(offer.durableBlobs().preStates().get(key))||
    !anchor.reference().hash().equals(offer.readSet().fingerprints().get(key)))
   throw new IllegalArgumentException("unchanged anchor must equal the exact sealed offer PRE");
  current=afterPrepare(s,current,offer);
  var fact=BlobContent.capture(new ResourceId("proutlost_director:catalogue_unchanged_outcome_v1"),1,new ModelCodec<>(DirectorFact.class).encode(closed));
  var rows=new ArrayList<DurableRecord>();
  for(var scope:scopes(s,current,false)){
   var old=prior(s,current,scope).orElse(null);
   if(old==null||old.status()!=UniqueStatus.RESERVED||!old.instanceId().equals(s.event())||
     !old.reservationToken().equals(Optional.of(offer.reservation())))continue;
   if(offer.uniqueness().stream().noneMatch(old::equals))continue;
   rows.add(new DurableUnique(new UniqueUseRecord(next(old.header(),old.header().id(),now),old.definitionId(),old.scopeKind(),old.canonicalScopeId(),
    UniqueStatus.RELEASED_BEFORE_EFFECT,Optional.empty(),old.instanceId(),Optional.of(fact.reference()),old.originScopeIds())));
  }
  return new Contribution(rows,rows.isEmpty()?List.of():List.of(fact));
 }
 /** One-step completion in the actual RESULT builder can consume the reservation
  * whose ACK EventRunner just proved, even before an observer refreshes its index. */
 public static Contribution completed(Selection s,Metadata current,PrepareRecord actualPrepare,DirectorFact fact,Tick now){
  return completed(s,afterPrepare(s,current,actualPrepare),fact,now);
 }
 /** Data-only incorporation of rows known durable by the enclosing EventRunner
  * RESULT callback. Never call before its actual PREPARE ACK. Newer real heads win. */
 public static Metadata afterPrepare(Selection s,Metadata current,PrepareRecord prepare){
  identity(s,current);
  if(!prepare.reservation().instanceId().equals(s.event()))throw new IllegalArgumentException("foreign actual PREPARE reservation");
  var unique=new ArrayList<>(current.uniqueness());
  for(var row:prepare.uniqueness()){
   if(!row.instanceId().equals(s.event())||!row.definitionId().equals(s.definition().id())||row.status()!=UniqueStatus.RESERVED||
     !row.reservationToken().equals(Optional.of(prepare.reservation())))throw new IllegalArgumentException("actual PREPARE unique binding");
   var old=unique.stream().filter(u->u.definitionId().equals(row.definitionId())&&u.scopeKind()==row.scopeKind()&&u.canonicalScopeId().equals(row.canonicalScopeId())).toList();
   if(old.size()>1)throw new IllegalArgumentException("duplicate actual unique head");
   if(!old.isEmpty()){
    var before=old.getFirst();if(!before.header().id().equals(row.header().id()))throw new IllegalArgumentException("unique identity changed");
    int order=before.header().revision().compareTo(row.header().revision());
    if(order>0)continue;
    if(order==0){if(!before.equals(row))throw new IllegalArgumentException("unique revision collision");continue;}
    unique.remove(before);
   }
   unique.add(row);
  }
  return new Metadata(current.world(),current.regions(),current.cooldowns(),unique);
 }
 /** Only a real ABORTED_SAFE/no-applied-operation RESULT can release this
  * exact PREPARE's reserved use. A later branch abort cannot release an earlier
  * offer/effect reservation, and spent/possible evidence is never reset. */
 public static Contribution aborted(Selection s,Metadata current,PrepareRecord prepare,SliceResult actual,Tick now){
  identity(s,current);Objects.requireNonNull(actual);
  if(actual.outcome()!=Outcome.ABORTED_SAFE||!actual.appliedOperationIds().isEmpty()||
    !actual.observedHashes().isEmpty()||actual.remainingWork()!=0)
   throw new IllegalArgumentException("actual pre-apply ABORTED_SAFE/no-effect RESULT required");
  current=afterPrepare(s,current,prepare);
  var evidence=BlobContent.capture(new ResourceId("proutlost_director:catalogue_absence_evidence_v1"),1,
   new ModelCodec<>(ResultRecord.class).encode(new ResultRecord(prepare.transactionId(),prepare.operationToken(),actual,Set.of(),List.of(),List.of())));
  var rows=new ArrayList<DurableRecord>();
  for(var scope:scopes(s,current,false)){
   var old=prior(s,current,scope).orElse(null);
   if(old==null||old.status()!=UniqueStatus.RESERVED||!old.instanceId().equals(s.event())||
     !old.reservationToken().equals(Optional.of(prepare.reservation())))continue;
   // The durable row must originate in THIS PREPARE, not an earlier applied step.
   if(prepare.uniqueness().stream().noneMatch(old::equals))continue;
   if(!StateTransitions.allows(old.status(),UniqueStatus.RELEASED_BEFORE_EFFECT,true,true))
    throw new IllegalStateException("invalid uniqueness absence transition");
   rows.add(new DurableUnique(new UniqueUseRecord(next(old.header(),old.header().id(),now),old.definitionId(),old.scopeKind(),old.canonicalScopeId(),
    UniqueStatus.RELEASED_BEFORE_EFFECT,Optional.empty(),old.instanceId(),Optional.of(evidence.reference()),old.originScopeIds())));
  }
  return new Contribution(rows,rows.isEmpty()?List.of():List.of(evidence));
 }
 private static List<DurableRecord> spend(Selection s,Metadata current,UniqueStatus target,BlobRef evidence,Tick now){
  var rows=new ArrayList<DurableRecord>();
  for(var scope:scopes(s,current,false)){
   var old=prior(s,current,scope).orElseThrow(()->new IllegalStateException("actual durable unique reservation missing"));
   if(!old.instanceId().equals(s.event())||old.status()==UniqueStatus.RELEASED_BEFORE_EFFECT)
    throw new IllegalStateException("actual unique reservation belongs to another/released event");
   if(old.status()==UniqueStatus.SPENT_CONFIRMED||old.status()==target)continue;
   if(old.status()==UniqueStatus.SPENT_POSSIBLE&&target!=UniqueStatus.SPENT_CONFIRMED)continue;
   if(old.status()!=UniqueStatus.RESERVED&&old.status()!=UniqueStatus.SPENT_POSSIBLE)
    throw new IllegalStateException("unique cannot be consumed from current status");
   var origins=new HashSet<>(old.originScopeIds());origins.addAll(scope.origins());
   rows.add(new DurableUnique(new UniqueUseRecord(next(old.header(),old.header().id(),now),old.definitionId(),old.scopeKind(),old.canonicalScopeId(),
    target,Optional.empty(),old.instanceId(),Optional.of(evidence),origins)));
  }
  return List.copyOf(rows);
 }
 /** All timing comes from the unchanged definition. Bind WORLD/REGION/PLAYER
  * separately: adding WORLD to a player's binding would suppress unrelated scopes. */
 private static List<DurableRecord> cooldowns(Selection s,Metadata current,Tick now){
  var rows=new ArrayList<DurableRecord>();var timing=s.definition().timing().cooldowns();
  addCooldown(s,current,now,ScopeKind.WORLD,s.plan().scopes().worldId().value(),timing.worldTicks(),Set.of(),true,rows);
  for(var region:regions(s)){
   var closure=lineage(region,current);var inherited=new HashSet<RegionId>();closure.ids().forEach(id->inherited.add(new RegionId(id)));
   addCooldown(s,current,now,ScopeKind.REGION,region.value(),timing.regionTicks(),inherited,closure.complete(),rows);
  }
  for(var player:players(s))addCooldown(s,current,now,ScopeKind.PLAYER,player.value(),timing.playerTicks(),Set.of(),true,rows);
  return List.copyOf(rows);
 }
 private static void addCooldown(Selection s,Metadata current,Tick now,ScopeKind kind,UUID scopeId,DurationTicks duration,
   Set<RegionId> inherited,boolean complete,List<DurableRecord> rows){
  if(duration.value()==0)return;
  UUID id=named("catalogue-cooldown-v1",s.event().value(),kind,scopeId);
  var old=current.cooldowns().stream().filter(c->c.header().id().equals(id)).toList();
  if(old.size()>1)throw new IllegalArgumentException("duplicate actual cooldown projection");
  if(!old.isEmpty()){
   var c=old.getFirst();if(!c.sourceInstanceId().equals(s.event())||c.binding().isEmpty()||
     !c.binding().orElseThrow().selector().equals(new EventCooldownSelector(s.definition().id())))
    throw new IllegalArgumentException("cooldown identity collision");
   return; // First actual effect starts the interval once; later steps do not prolong it.
  }
  var scope=new ScopeRef(kind,scopeId);var binding=new CooldownBinding(new EventCooldownSelector(s.definition().id()),
   new ScopeSet(s.plan().scopes().worldId(),Set.of(scope)),inherited,complete,CooldownReleaseMode.AFTER_TICK,Optional.empty());
  var c=new CooldownRecord(new AggregateHeader(1,id,new Revision(1),new Sequence(0),now),
   new ResourceId("proutlost_director:catalogue_cooldown/"+kind.name().toLowerCase(Locale.ROOT)),"EVENT_"+kind,"ACTUAL_SELECTED_EFFECT",now.plus(duration.value()),s.event(),
   s.definition().safety().risk(),Optional.empty(),Optional.of(binding));
  rows.add(new DurableAggregate(new CooldownAggregate(c)));
 }
 /** Exact stable keys for THIS selected event's ledger. Existing logical
  * uniqueness keeps its original aggregate UUID, including lineage inheritance.
  * Used only for fresh metadata point reads, never as a substitute J03 snapshot. */
 public static Set<AggregateKey> keys(Selection s,Metadata current){
  identity(s,current);var keys=new HashSet<AggregateKey>();
  for(var scope:scopes(s,current,false))keys.add(new AggregateKey(AggregateKind.UNIQUE,
   prior(s,current,scope).map(u->u.header().id()).orElseGet(()->uniqueId(s,scope))));
  var timing=s.definition().timing().cooldowns();
  if(timing.worldTicks().value()>0)keys.add(new AggregateKey(AggregateKind.COOLDOWN,named("catalogue-cooldown-v1",s.event().value(),ScopeKind.WORLD,s.plan().scopes().worldId().value())));
  if(timing.regionTicks().value()>0)for(var region:regions(s))keys.add(new AggregateKey(AggregateKind.COOLDOWN,named("catalogue-cooldown-v1",s.event().value(),ScopeKind.REGION,region.value())));
  if(timing.playerTicks().value()>0)for(var player:players(s))keys.add(new AggregateKey(AggregateKind.COOLDOWN,named("catalogue-cooldown-v1",s.event().value(),ScopeKind.PLAYER,player.value())));
  if(keys.size()>16)throw new IllegalArgumentException("selected original ledger key bound exceeded");return Set.copyOf(keys);
 }
 private static AggregateHeader next(AggregateHeader old,UUID id,Tick now){
  if(old!=null&&now.compareTo(old.updatedTick())<0)throw new IllegalArgumentException("ledger time regression");
  return new AggregateHeader(1,id,old==null?new Revision(1):old.revision().plus(1),old==null?new Sequence(0):old.lastSequence(),now);
 }
 private static UUID named(Object... values){
  return UUID.nameUUIDFromBytes(String.join("\0",Arrays.stream(values).map(Object::toString).toList()).getBytes(StandardCharsets.UTF_8));
 }
}
