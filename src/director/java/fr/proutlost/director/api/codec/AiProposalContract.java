package fr.proutlost.director.api.codec;
import fr.proutlost.director.api.model.*;
import java.util.*;
/** Pure protocol validation only. Arc admission and scoring remain the engine's responsibility. */
public final class AiProposalContract {
 private AiProposalContract() {}
 public static AiProposal decode(byte[] bytes,AiInput input,long nowMonotonic,boolean tokenActive,Fingerprint currentContextHash) {
  Checks.require(bytes.length<=input.outputLimits().maximumBytes(),"AI byte bound");
  Checks.require(tokenActive&&nowMonotonic-input.expiresMonotonicDeadline()<0,"AI late/inactive token");
  AiProposal proposal=new ModelCodec<>(AiProposal.class).decode(bytes);
  Checks.require(proposal.protocolVersion()==input.protocolVersion()&&proposal.requestId().equals(input.requestId())&&proposal.candidateSetHash().equals(input.candidateSetHash()),"AI response correlation");
  Checks.require(currentContextHash.equals(input.contextHash()),"AI stale context");
  var limits=input.outputLimits();Checks.require(proposal.adjustments().size()<=limits.maximumAdjustments()&&proposal.arcProposals().size()<=limits.maximumArcs(),"AI item bounds");
  Set<CandidateId> candidates=new HashSet<>();input.candidates().forEach(c->candidates.add(c.candidateId()));
  for(var adjustment:proposal.adjustments())Checks.require(candidates.contains(adjustment.candidateId())&&Math.abs(adjustment.deltaLogWeight())<=limits.maxAbsLogDelta(),"AI candidate/delta");
  for(var arc:proposal.arcProposals()) {
   Checks.require(arc.transitionIds().size()<=Math.min(input.planningHorizon(),limits.maximumTransitions()),"AI horizon");
   Map<ResourceId,AiAllowedTransition> allowed=new HashMap<>();for(var t:input.allowedChainTransitions())if(t.chainId().equals(arc.chainId()))Checks.require(allowed.putIfAbsent(t.transitionId(),t)==null,"duplicate allowed transition");
   Set<String> states=new HashSet<>();String previous=null;BudgetVector sum=BudgetMath.zero();
   for(var id:arc.transitionIds()) {var t=allowed.get(id);Checks.require(t!=null,"unknown transition");if(previous==null)states.add(t.from());else Checks.require(previous.equals(t.from()),"noncausal arc");Checks.require(states.add(t.to()),"cyclic arc");previous=t.to();sum=BudgetMath.add(sum,t.cost());}
   Checks.require(BudgetMath.fits(sum,limits.arcBudget()),"arc budget");
  }
  return proposal;
 }
}
