package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record PrepareRecord(TransactionId transactionId, OperationToken operationToken, Epoch reservationGeneration, ReadSet readSet, Tick expiryTick, PreparedSlice slice, ReservationToken reservation, ValidationResult hardValidation, List<UniqueUseRecord> uniqueness, Set<NoninvertibleEffect> noninvertibleEffects, List<EffectIntent> authorizedStabilization, EffectBlobs durableBlobs, Optional<StabilizationPlan> stabilizationPlan) {
 public PrepareRecord(TransactionId transactionId,OperationToken operationToken,Epoch reservationGeneration,ReadSet readSet,Tick expiryTick,PreparedSlice slice,ReservationToken reservation,ValidationResult hardValidation,List<UniqueUseRecord> uniqueness,Set<NoninvertibleEffect> noninvertibleEffects,List<EffectIntent> authorizedStabilization,EffectBlobs durableBlobs){this(transactionId,operationToken,reservationGeneration,readSet,expiryTick,slice,reservation,hardValidation,uniqueness,noninvertibleEffects,authorizedStabilization,durableBlobs,Optional.empty());}
 public PrepareRecord { Objects.requireNonNull(stabilizationPlan); Objects.requireNonNull(transactionId); Objects.requireNonNull(operationToken); Objects.requireNonNull(reservationGeneration); Objects.requireNonNull(readSet); Objects.requireNonNull(expiryTick); Objects.requireNonNull(slice); Objects.requireNonNull(reservation); Objects.requireNonNull(hardValidation); uniqueness=list(uniqueness); noninvertibleEffects=set(noninvertibleEffects); authorizedStabilization=list(authorizedStabilization); Objects.requireNonNull(durableBlobs); if(stabilizationPlan.isPresent()){
 var plan=stabilizationPlan.orElseThrow();require(plan.blob().reference().equals(durableBlobs.stopPlan()),"forced stop blob binding");
 require(plan.slices().stream().flatMap(s->s.slice().operations().stream()).toList().equals(authorizedStabilization),"stop intent binding");
 BudgetVector stopWork=BudgetMath.zero();
 for(var stop:plan.slices()){
  stopWork=BudgetMath.add(stopWork,stop.slice().maximumWork());
  require(reservation.claims().stream().filter(c->c.policy()==ClaimPolicy.EXCLUSIVE).map(ResourceClaim::resource).collect(java.util.stream.Collectors.toSet()).containsAll(stop.expectedOwnership().keySet()),"unreserved stop resource");
  var f=stop.slice().footprint();require(slice.operations().stream().noneMatch(o->o.operationToken().equals(stop.slice().operationToken())||stop.slice().operations().stream().anyMatch(t->t.operationToken().equals(o.operationToken()))),"impact and stop tokens overlap");
  require(!stop.slice().operationToken().equals(operationToken),"impact token is not stop token");
  require(f.dimension().equals(slice.footprint().dimension())&&slice.footprint().loadedChunks().containsAll(f.loadedChunks())&&slice.footprint().cells().containsAll(f.cells())&&slice.footprint().bounds().containsAll(f.bounds()),"stop footprint expansion");
  require(f.cells().stream().allMatch(c->f.loadedChunks().contains(new ChunkRef(c.dimension(),Math.floorDiv(c.x(),16),Math.floorDiv(c.z(),16)))),"stop cell chunk proof");
  for(var box:f.bounds()){
   require(box.dimension().equals(f.dimension()),"stop bound dimension");
   int minX=Math.floorDiv(box.minX(),16),maxX=Math.floorDiv(box.maxXExclusive()-1,16),minZ=Math.floorDiv(box.minZ(),16),maxZ=Math.floorDiv(box.maxZExclusive()-1,16);
   require(((long)maxX-minX+1)*((long)maxZ-minZ+1)<=4096,"stop chunk bound");
   for(int x=minX;x<=maxX;x++)for(int z=minZ;z<=maxZ;z++)require(f.loadedChunks().contains(new ChunkRef(f.dimension(),x,z)),"stop bounds chunk proof");
  }
  require(!f.loadedChunks().isEmpty()&&BudgetMath.fits(stop.slice().maximumWork(),slice.maximumWork()),"stop work bound");
  for(var intent:stop.slice().operations())require(intent.expectedFootprint().equals(f)&&intent.instanceId().equals(reservation.instanceId())&&!intent.operationToken().equals(operationToken),"stop intent scope");
 }
 require(BudgetMath.fits(stopWork,slice.maximumWork()),"total stop work bound");
 }
 require(operationToken.equals(slice.operationToken()),"operation mismatch"); require(reservation.generation().equals(reservationGeneration)&&reservation.expiryTick().compareTo(expiryTick)>=0,"reservation binding"); require(hardValidation.valid()&&hardValidation.checkedVersions().equals(readSet),"validated PREPARE required"); }
}
