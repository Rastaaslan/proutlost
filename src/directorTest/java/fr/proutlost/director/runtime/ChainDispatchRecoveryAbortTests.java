package fr.proutlost.director.runtime;

import fr.proutlost.director.api.codec.ModelCodec;
import fr.proutlost.director.api.model.*;
import java.util.*;

/** Pure DTO tests only. These fixtures are not native/client/abort-source qualification. */
public final class ChainDispatchRecoveryAbortTests {
 private static final DefinitionId ENTRY=new DefinitionId("test:entry"),CHILD=new DefinitionId("test:child"),WRONG=new DefinitionId("test:wrong");
 private static final ResourceId STEP=new ResourceId("test:branch"),FINISH=new ResourceId("test:finish");
 private static final EventInstanceId A=event(1),B=event(2),C=event(3),OLD=event(4),EXTRA=event(5);
 private static int checked;
 private static EventInstanceId event(long id){return new EventInstanceId(new UUID(0,id));}
 private static ChainDefinition definition(){
  return new ChainDefinition(1,new DefinitionId("test:recoverable_chain"),new Revision(1),Calibration.DEFAULT_FOR_TESTING,
   ChainMode.SHORT_ARC,ScopeKind.REGION,"START",Set.of("START","ACTIVE","DONE"),4,new DurationTicks(1),new UnitValue(.2),
   List.of(new ChainTransition(STEP,"START","ACTIVE",new FactType("test:entry_done"),Optional.of(CHILD),new PlayersPresent(1),true,Optional.empty()),
     new ChainTransition(FINISH,"ACTIVE","DONE",new FactType("test:child_done"),Optional.empty(),new PlayersPresent(1),true,Optional.empty())),
   Set.of("DONE"),RecoveryMode.RECOVER);
 }
 private static ChainInstance chain(ChainDefinition def,int branchCount){
  var history=new ArrayList<BranchHistory>();var consumed=new HashSet<FactId>();
  if(branchCount>0){var fact=new FactId(new UUID(1,1));history.add(new BranchHistory(STEP,fact,new Tick(100)));consumed.add(fact);}
  if(branchCount>1){var fact=new FactId(new UUID(1,2));history.add(new BranchHistory(FINISH,fact,new Tick(200)));consumed.add(fact);}
  var id=new ChainId(new UUID(2,1));var ref=new DefinitionRef(def.id(),def.contentRevision(),
      BlobContent.hash(new ModelCodec<>(ChainDefinition.class).encode(def)));
  return new ChainInstance(new AggregateHeader(1,id.value(),new Revision(branchCount+1),new Sequence(0),new Tick(branchCount*100)),
   id,ref,def.mode(),branchCount>1?ChainState.COMPLETED:branchCount==1?ChainState.ACTIVE:ChainState.WAITING,
   Set.of(branchCount>1?"DONE":branchCount==1?"ACTIVE":"START"),consumed,Set.of(new RegionId(new UUID(3,1))),
   branchCount==1?List.of(STEP):List.of(),history,Optional.empty(),new UnitValue(1),new Tick(0));
 }
 private static ChainDispatchRecovery.Intent intent(EventInstanceId event,DefinitionId definition,long tick,long revision){
  return new ChainDispatchRecovery.Intent(event,definition,new Tick(tick),Optional.of(new Revision(revision)));
 }
 private static ChainDispatchRecovery.Decision classify(ChainDefinition def,ChainInstance chain,
      List<ChainDispatchRecovery.Intent> intents,Set<EventInstanceId> completed,Set<EventInstanceId> aborted){
  return ChainDispatchRecovery.classify(def,chain,intents,completed,aborted,Optional.of(ENTRY));
 }
 private static void check(boolean value,String message){checked++;if(!value)throw new AssertionError(message);}
 private static void next(ChainDispatchRecovery.Decision decision,DefinitionId expected,String message){
  check(decision.allowed()&&decision.next().equals(Optional.of(expected)),message+" "+decision);
 }
 private static void finished(ChainDispatchRecovery.Decision decision,String message){
  check(decision.allowed()&&decision.next().isEmpty(),message+" "+decision);
 }
 private static void blocked(ChainDispatchRecovery.Decision decision,String message){
  check(!decision.allowed()&&decision.next().isEmpty(),message+" "+decision);
 }
 public static int run(){
  checked=0;var def=definition();var entry=chain(def,0);var branch=chain(def,1);var terminal=chain(def,2);
  var entryA=intent(A,ENTRY,10,1);var entryB=intent(B,ENTRY,20,1);
  var childA=intent(A,CHILD,101,2);var childB=intent(B,CHILD,110,2);var childC=intent(C,CHILD,120,2);
  // The old overload never treats an abort, refusal or missing result as completion.
  blocked(ChainDispatchRecovery.classify(def,entry,List.of(entryA),Set.of(),Optional.of(ENTRY)),"legacy unresolved entry remains blocked");
  finished(ChainDispatchRecovery.classify(def,entry,List.of(entryA),Set.of(A),Optional.of(ENTRY)),"legacy completed entry still waits source fact");
  next(classify(def,entry,List.of(entryA),Set.of(),Set.of(A)),ENTRY,"proven aborted entry admits only a fresh future entry");
  next(classify(def,entry,List.of(entryA,entryB),Set.of(),Set.of(A,B)),ENTRY,"all aborted entry attempts can retry");
  finished(classify(def,entry,List.of(entryA,entryB),Set.of(B),Set.of(A)),"prior entry abort followed by one completion must not dispatch again");
  blocked(classify(def,entry,List.of(entryA,entryB),Set.of(A),Set.of(B)),"an attempt after a completed entry is inconsistent");
  blocked(classify(def,entry,List.of(intent(A,WRONG,10,1)),Set.of(),Set.of(A)),"aborted entry cannot change its definition");
  next(classify(def,branch,List.of(childA),Set.of(),Set.of(A)),CHILD,"last branch abort can dispatch same definition with new UUID");
  next(classify(def,branch,List.of(childA,childB),Set.of(),Set.of(A,B)),CHILD,"multiple proven branch aborts are resumable");
  finished(classify(def,branch,List.of(childA,childB),Set.of(B),Set.of(A)),"one completed retry suppresses every future duplicate");
  blocked(classify(def,branch,List.of(childA,childB),Set.of(A,B),Set.of(EXTRA)),"two completed attempts on one branch are a conflict");
  blocked(classify(def,branch,List.of(childA,childB),Set.of(A),Set.of(B)),"aborted attempt after success cannot authorize a replay");
  blocked(classify(def,branch,List.of(childA,childB),Set.of(B),Set.of(B,A)),"success and abort overlap is a conflict");
  blocked(classify(def,branch,List.of(childA),Set.of(EXTRA),Set.of(EXTRA,A)),"contradictory proof sets conflict even outside tracked intents");
  blocked(classify(def,branch,List.of(childA),Set.of(),Set.of(EXTRA)),"REFUSED or absence without proven abort remains unresolved");
  blocked(classify(def,branch,List.of(intent(OLD,ENTRY,10,1),childA),Set.of(),Set.of(A)),"older unresolved intent blocks the whole future chain");
  next(classify(def,branch,List.of(intent(OLD,ENTRY,10,1),childA),Set.of(OLD),Set.of(A)),CHILD,"completed prior branch plus aborted current branch is valid");
  blocked(classify(def,branch,List.of(intent(A,WRONG,101,2)),Set.of(),Set.of(A)),"aborted current child cannot change selected definition");
  blocked(classify(def,branch,List.of(childA,intent(B,WRONG,110,2)),Set.of(),Set.of(A,B)),"one branch cannot mix attempt definitions");
  blocked(classify(def,branch,List.of(intent(A,CHILD,99,2)),Set.of(),Set.of(A)),"current-revision child cannot predate branch commit");
  blocked(classify(def,branch,List.of(intent(A,CHILD,101,3)),Set.of(),Set.of(A)),"future chain revision is not an admitted branch");
  blocked(classify(def,branch,List.of(childA,childA),Set.of(),Set.of(A)),"duplicate historical event UUID is rejected");
  var legacyA=new ChainDispatchRecovery.Intent(A,CHILD,new Tick(101),Optional.empty());
  var legacyB=new ChainDispatchRecovery.Intent(B,CHILD,new Tick(110),Optional.empty());
  next(classify(def,branch,List.of(legacyA,legacyB),Set.of(),Set.of(A,B)),CHILD,"legacy aborted attempts retain their current branch");
  finished(classify(def,branch,List.of(legacyA,childB),Set.of(B),Set.of(A)),"legacy and explicit attempts combine before one completion");
  blocked(classify(def,branch,List.of(legacyA,intent(B,CHILD,101,2)),Set.of(B),Set.of(A)),"equal ticks cannot prove abort attempt preceded completion");
  blocked(classify(def,terminal,List.of(childA),Set.of(),Set.of(EXTRA)),"terminal state does not resolve an unknown old intent");
  finished(classify(def,terminal,List.of(childA),Set.of(),Set.of(A)),"terminal chain never dispatches again after resolved abort proof");
  // No aborted evidence preserves the exact old-overload answer and reason.
  var original=ChainDispatchRecovery.classify(def,branch,List.of(childA),Set.of(A),Optional.of(ENTRY));
  check(original.equals(classify(def,branch,List.of(childA),Set.of(A),Set.of())),"empty new abort set preserves exact legacy decision");
  next(classify(def,branch,List.of(),Set.of(),Set.of(EXTRA)),CHILD,"an unrelated abort does not invent a child intent");
  // All DTO and caller-owned collections remain unmodified by classification.
  var history=branch.branchHistory();var intents=new ArrayList<>(List.of(childA,childB,childC));var aborts=new HashSet<>(Set.of(A,B,C));
  next(classify(def,branch,intents,Set.of(),aborts),CHILD,"three actual abort proofs permit a future new attempt");
  check(intents.equals(List.of(childA,childB,childC))&&aborts.equals(Set.of(A,B,C))&&branch.branchHistory().equals(history),"classification mutates no history/proof collection");
  return checked;
 }
 public static void main(String[] args){System.out.println("ChainDispatchRecoveryAbortTests passed="+run()+"; pure DTO classification only");}
}
