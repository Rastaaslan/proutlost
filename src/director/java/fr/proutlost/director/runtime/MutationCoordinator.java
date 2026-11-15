package fr.proutlost.director.runtime;

import fr.proutlost.director.api.codec.ModelCodec;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.permission.CommitPermit;
import java.util.*;
import java.util.function.Function;

/** Builds the exact forced PREPARE from captured immutable plans and submits a slice saga.
 * Capture/hydration remain actor-adapter responsibilities; this class performs no world IO.
 */
public final class MutationCoordinator {
 private final EventRunner events;
 public MutationCoordinator(EventRunner events){this.events=Objects.requireNonNull(events);}
 public DurableBatch assemble(TransactionId transaction,SliceId sliceId,OperationToken sliceOperation,
                              ReservationToken reservation,Epoch storeGeneration,Tick expiry,
                              List<EffectPreparation> captures,Optional<StabilizationPlan> stop,
                              Set<NoninvertibleEffect> noninvertible,List<UniqueUseRecord> uniqueness) {
  captures=List.copyOf(captures);if(captures.isEmpty()||captures.size()>64)throw new IllegalArgumentException("mutation tranche bound");
  var plans=captures.stream().map(c->{if(!c.validation().valid())throw new IllegalArgumentException("failed preparation");var p=c.prepared().orElseThrow();if(!p.intent().equals(c.intent())||!p.preconditions().equals(c.validation().checkedVersions()))throw new IllegalArgumentException("capture binding");return p;}).toList();
  var first=plans.getFirst();var foot=first.intent().expectedFootprint();var read=first.preconditions();
  var operations=new HashSet<OperationToken>();var expected=new HashMap<ResourceKey,Fingerprint>();var preStates=new HashMap<ResourceKey,BlobRef>();var postStates=new HashMap<ResourceKey,BlobRef>();var snapshots=new HashSet<BlobRef>();var blobs=new LinkedHashMap<BlobRef,BlobContent>();var budget=BudgetMath.zero();
  for(int n=0;n<plans.size();n++){
   var p=plans.get(n);
   if(!operations.add(p.intent().operationToken())||!p.intent().instanceId().equals(reservation.instanceId())||!p.intent().expectedFootprint().equals(foot)||!p.preconditions().equals(read))throw new IllegalArgumentException("slice identity or proof mismatch");
   if(!reservation.claims().containsAll(p.claims()))throw new IllegalArgumentException("mutation claims not held");
   for(var entry:p.expectedPost().entrySet())if(expected.putIfAbsent(entry.getKey(),entry.getValue())!=null)throw new IllegalArgumentException("overlapping writes require prior merge plan");
   merge(preStates,p.durableBlobs().preStates());merge(postStates,p.durableBlobs().expectedPostStates());snapshots.addAll(CommitPermit.Authority.references(p.durableBlobs()));
   budget=BudgetMath.add(budget,p.budgetEstimate());
   for(var b:captures.get(n).blobs().contents())blobs.put(b.reference(),b);
  }
  if(!blobs.keySet().containsAll(snapshots))throw new IllegalArgumentException("unhydrated capture");
  var slice=new PreparedSlice(sliceId,sliceOperation,foot,read,plans.stream().map(PreparedEffect::intent).toList(),budget);
  var planBlob=BlobContent.capture(new ResourceId("proutlost_director:prepared_slice"),1,new ModelCodec<>(PreparedSlice.class).encode(slice));blobs.put(planBlob.reference(),planBlob);
  // An explicitly empty stop list is data, not a silent authorization or fake stop effect.
  var stopBlob=stop.map(StabilizationPlan::blob).orElseGet(()->BlobContent.capture(new ResourceId("proutlost_director:no_stop"),1,new byte[]{0}));blobs.put(stopBlob.reference(),stopBlob);
  var refs=new EffectBlobs(snapshots,planBlob.reference(),stopBlob.reference(),preStates,postStates);
  var prepare=new PrepareRecord(transaction,sliceOperation,reservation.generation(),read,expiry,slice,reservation,new ValidationResult(true,List.of(),read),uniqueness,noninvertible,stop.map(p->p.slices().stream().flatMap(s->s.slice().operations().stream()).toList()).orElse(List.of()),refs,stop);
  return new DurableBatch(transaction,storeGeneration,read.revisions(),List.of(new DurablePrepare(prepare)),List.copyOf(blobs.values()),Optional.empty(),CommitPermit.Authority.prepareHash(prepare));
 }
 private static <K,V> void merge(Map<K,V> into,Map<K,V> source){source.forEach((key,value)->{var previous=into.putIfAbsent(key,value);if(previous!=null&&!previous.equals(value))throw new IllegalArgumentException("conflicting captured state");});}
 public void submit(DurableBatch prepare,List<EffectPreparation> captures,LiveValidationSnapshot live,Function<SliceResult,DurableBatch> confirmedResult){
  var expected=new HashMap<ResourceKey,Fingerprint>();for(var capture:captures)merge(expected,capture.prepared().orElseThrow().expectedPost());
  var record=((DurablePrepare)prepare.records().getFirst()).value();
  var pre=new HashMap<ResourceKey,BlobRef>();var post=new HashMap<ResourceKey,BlobRef>();
  for(var capture:captures){var plan=capture.prepared().orElseThrow();
   if(!capture.validation().valid()||!plan.preconditions().equals(record.readSet()))throw new IllegalArgumentException("capture validation substitution");
   merge(pre,plan.durableBlobs().preStates());merge(post,plan.durableBlobs().expectedPostStates());
  }
  if(!pre.equals(record.durableBlobs().preStates())||!post.equals(record.durableBlobs().expectedPostStates()))throw new IllegalArgumentException("captured hashes substituted");
  if(!record.slice().operations().equals(captures.stream().map(EffectPreparation::intent).toList()))throw new IllegalArgumentException("prepared operation substitution");
  events.submit(prepare,expected,live,confirmedResult);
 }
}
