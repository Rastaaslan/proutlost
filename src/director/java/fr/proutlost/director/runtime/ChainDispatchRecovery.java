package fr.proutlost.director.runtime;

import fr.proutlost.director.api.model.*;
import java.util.*;

/** Pure cold-recovery classification. A durable intent is never replayed.
 * The legacy API requires exact completion; the explicit abort-aware API also accepts caller-proven aborts.
 * Calling code must still obtain a qualified current session and ordinary J03 admission.
 * Laboratory callers additionally require explicit current operator consent.
 */
public final class ChainDispatchRecovery {
 private ChainDispatchRecovery(){}
 public record Intent(EventInstanceId event,DefinitionId definition,Tick tick,Optional<Revision> branchRevision){
  public Intent{Objects.requireNonNull(event);Objects.requireNonNull(definition);Objects.requireNonNull(tick);Objects.requireNonNull(branchRevision);}
 }
 public record Decision(boolean allowed,Optional<DefinitionId> next,String reason){}
 private static Decision waitFor(String why){return new Decision(false,Optional.empty(),why);}
 public static Decision classify(ChainDefinition definition,ChainInstance chain,Collection<Intent> intents,
      Set<EventInstanceId> completed,Optional<DefinitionId> entryEvent){
  if(!chain.recipe().id().equals(definition.id()))return waitFor("PINNED_DEFINITION_MISMATCH");
  var all=List.copyOf(intents);
  if(all.stream().map(Intent::event).distinct().count()!=all.size())return waitFor("DUPLICATE_DISPATCH_ID");
  if(all.stream().anyMatch(i->i.branchRevision().filter(r->r.compareTo(chain.header().revision())>0).isPresent()))return waitFor("DISPATCH_AHEAD_OF_CHAIN_REVISION");
  if(all.stream().anyMatch(i->!completed.contains(i.event())))return waitFor("WAIT_HISTORICAL_CHILD_COMPLETION_NO_REPLAY");
  if(chain.currentNodes().stream().allMatch(definition.terminalStates()::contains))
   return new Decision(true,Optional.empty(),"ALREADY_TERMINAL");
  if(chain.branchHistory().isEmpty()){
   if(!chain.plannedHorizon().isEmpty())return waitFor("ENTRY_HORIZON_WITHOUT_BRANCH");
   if(all.isEmpty())return new Decision(true,entryEvent,"ENTRY_WITHOUT_ANY_OLD_INTENT");
   return new Decision(true,Optional.empty(),"COMPLETED_ENTRY_CHILD_WAIT_SOURCE_FACT");
  }
  var branch=chain.branchHistory().getLast();
  var matches=definition.transitions().stream().filter(t->t.id().equals(branch.transitionId())).toList();
  if(matches.size()!=1)return waitFor("UNKNOWN_LAST_TRANSITION");
  var selected=matches.getFirst();
  if(!chain.currentNodes().equals(Set.of(selected.to())))return waitFor("LAST_TRANSITION_NODE_MISMATCH");
  if(selected.eventId().isEmpty()){
   if(!chain.plannedHorizon().isEmpty())return waitFor("HORIZON_WITHOUT_CHILD");
   return new Decision(true,Optional.empty(),"WAIT_FUTURE_SOURCE_FACT");
  }
  if(!chain.plannedHorizon().equals(List.of(selected.id())))return waitFor("LAST_BRANCH_HORIZON_MISMATCH");
  var candidates=all.stream().filter(i->i.branchRevision().isPresent()
    ?i.branchRevision().get().equals(chain.header().revision())
    :i.tick().compareTo(branch.tick())>=0).toList();
  if(candidates.size()>1)return waitFor("AMBIGUOUS_LAST_BRANCH_DISPATCH");
  if(candidates.isEmpty())return new Decision(true,selected.eventId(),"COMMITTED_BRANCH_WITHOUT_ANY_CHILD_INTENT");
  var intent=candidates.getFirst();
  if(!intent.definition().equals(selected.eventId().get())||intent.tick().compareTo(branch.tick())<0)
   return waitFor("LAST_BRANCH_DISPATCH_MISMATCH");
  return new Decision(true,Optional.empty(),"LAST_CHILD_ALREADY_COMPLETED_NO_REPLAY");
 }

 /** Proven abort is supplied only by the caller's exact durable source validation.
  * REFUSED/absence/timestamp alone never enters provenAborted. Any next definition below
  * is a FUTURE ordinary admission with a NEW UUID, never replay of a historical Intent.
  * The original overload above intentionally retains its exact legacy behavior.
  */
 public static Decision classify(ChainDefinition definition,ChainInstance chain,Collection<Intent> intents,
      Set<EventInstanceId> completed,Set<EventInstanceId> provenAborted,Optional<DefinitionId> entryEvent){
  Objects.requireNonNull(completed);Objects.requireNonNull(provenAborted);
  if(!Collections.disjoint(completed,provenAborted))return waitFor("CONFLICTING_CHILD_OUTCOMES");
  if(provenAborted.isEmpty())return classify(definition,chain,intents,completed,entryEvent);
  if(!chain.recipe().id().equals(definition.id()))return waitFor("PINNED_DEFINITION_MISMATCH");
  var all=List.copyOf(intents);
  if(all.stream().map(Intent::event).distinct().count()!=all.size())return waitFor("DUPLICATE_DISPATCH_ID");
  if(all.stream().anyMatch(i->i.branchRevision().filter(r->r.compareTo(chain.header().revision())>0).isPresent()))
   return waitFor("DISPATCH_AHEAD_OF_CHAIN_REVISION");
  if(all.stream().anyMatch(i->!completed.contains(i.event())&&!provenAborted.contains(i.event())))
   return waitFor("WAIT_HISTORICAL_CHILD_OUTCOME_NO_REPLAY");
  // Verify all explicit historical branch groups as well as the current group below.
  // Legacy tick groups normalize to revisions only for the exact rev=1+branch-count shape.
  var groups=new HashMap<String,List<Intent>>();
  for(var intent:all)groups.computeIfAbsent(attemptGroup(chain,intent),ignored->new ArrayList<>()).add(intent);
  for(var attempts:groups.values()){
   var invalid=attemptsInvalid(attempts,completed,provenAborted);
   if(invalid.isPresent())return waitFor(invalid.get());
  }
  if(chain.currentNodes().stream().allMatch(definition.terminalStates()::contains))
   return new Decision(true,Optional.empty(),"ALREADY_TERMINAL");
  if(chain.branchHistory().isEmpty()){
   if(!chain.plannedHorizon().isEmpty())return waitFor("ENTRY_HORIZON_WITHOUT_BRANCH");
   if(all.isEmpty())return new Decision(true,entryEvent,"ENTRY_WITHOUT_ANY_OLD_INTENT");
   var invalid=attemptsInvalid(all,completed,provenAborted);
   if(invalid.isPresent())return waitFor(invalid.get());
   if(entryEvent.isPresent()&&all.stream().anyMatch(i->!i.definition().equals(entryEvent.get())))
    return waitFor("ENTRY_DISPATCH_DEFINITION_MISMATCH");
   if(all.stream().anyMatch(i->completed.contains(i.event())))
    return new Decision(true,Optional.empty(),"COMPLETED_ENTRY_CHILD_WAIT_SOURCE_FACT");
   return new Decision(true,entryEvent,"ABORTED_ENTRY_CHILD_ALLOW_NEW_UUID");
  }
  var branch=chain.branchHistory().getLast();
  var matches=definition.transitions().stream().filter(t->t.id().equals(branch.transitionId())).toList();
  if(matches.size()!=1)return waitFor("UNKNOWN_LAST_TRANSITION");
  var selected=matches.getFirst();
  if(!chain.currentNodes().equals(Set.of(selected.to())))return waitFor("LAST_TRANSITION_NODE_MISMATCH");
  if(selected.eventId().isEmpty()){
   if(!chain.plannedHorizon().isEmpty())return waitFor("HORIZON_WITHOUT_CHILD");
   return new Decision(true,Optional.empty(),"WAIT_FUTURE_SOURCE_FACT");
  }
  if(!chain.plannedHorizon().equals(List.of(selected.id())))return waitFor("LAST_BRANCH_HORIZON_MISMATCH");
  var candidates=all.stream().filter(i->i.branchRevision().isPresent()
      ?i.branchRevision().get().equals(chain.header().revision())
      :i.tick().compareTo(branch.tick())>=0).toList();
  if(candidates.isEmpty())return new Decision(true,selected.eventId(),"COMMITTED_BRANCH_WITHOUT_ANY_CHILD_INTENT");
  if(candidates.stream().anyMatch(i->!i.definition().equals(selected.eventId().get())||i.tick().compareTo(branch.tick())<0))
   return waitFor("LAST_BRANCH_DISPATCH_MISMATCH");
  var invalid=attemptsInvalid(candidates,completed,provenAborted);
  if(invalid.isPresent())return waitFor(invalid.get());
  if(candidates.stream().anyMatch(i->completed.contains(i.event())))
   return new Decision(true,Optional.empty(),"LAST_CHILD_ALREADY_COMPLETED_NO_REPLAY");
  return new Decision(true,selected.eventId(),"ABORTED_LAST_CHILD_ALLOW_NEW_UUID");
 }
 private static Optional<String> attemptsInvalid(List<Intent> attempts,Set<EventInstanceId> completed,
      Set<EventInstanceId> provenAborted){
  if(attempts.stream().map(Intent::definition).distinct().count()>1)
   return Optional.of("BRANCH_ATTEMPT_DEFINITION_MISMATCH");
  var success=attempts.stream().filter(i->completed.contains(i.event())).toList();
  if(success.size()>1)return Optional.of("MULTIPLE_COMPLETED_BRANCH_ATTEMPTS");
  if(success.isEmpty())return Optional.empty();
  var completedAttempt=success.getFirst();
  for(var previous:attempts)if(!previous.event().equals(completedAttempt.event())){
   if(!provenAborted.contains(previous.event()))return Optional.of("UNPROVEN_PREVIOUS_BRANCH_ATTEMPT");
   // Equal ticks cannot establish which attempt came first with this DTO.
   if(previous.tick().compareTo(completedAttempt.tick())>=0)
    return Optional.of("ATTEMPT_NOT_BEFORE_COMPLETED_CHILD");
  }
  return Optional.empty();
 }
 private static String attemptGroup(ChainInstance chain,Intent intent){
  if(intent.branchRevision().isPresent())return "revision/"+intent.branchRevision().get().value();
  int index=-1;
  for(int i=0;i<chain.branchHistory().size();i++)
   if(intent.tick().compareTo(chain.branchHistory().get(i).tick())>=0)index=i;
  if(chain.header().revision().value()==chain.branchHistory().size()+1L)return "revision/"+(index+2L);
  return "legacy/"+index;
 }
}
