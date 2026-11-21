package fr.proutlost.director.runtime;
import fr.proutlost.director.api.model.*;
import java.nio.file.*;
import java.util.*;
import static fr.proutlost.director.runtime.CatalogueEventLedgerFixtures.*;

/** Synthetic pure reconciliation keys, not a native J04 ingestion/effect proof. */
public final class TerminalEventOwnerMemoTests {
 private static int checks;
 private static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
 private static final class CountedMap<K,V> extends HashMap<K,V>{
  long lookups;@Override public V get(Object key){lookups++;return super.get(key);}
 }
 public static void main(String[]args)throws Exception{
  var loaded=load(Path.of(args[0]));var def=loaded.event("controlled_landslide");
  var in=input(loaded,REGION,List.of(FIRST),new Tick(1000));var plan=plan(def,in,List.of(FIRST));
  var context=BlobContent.capture(new ResourceId("fixture:terminal_context"),1,new byte[]{7}).reference();
  var head=new EventInstance(header(plan.effects().getFirst().instanceId().value(),new Tick(1000)),
   plan.effects().getFirst().instanceId(),plan.definition(),new Epoch(1),EventState.PARTIAL,"SYNTHETIC_TERMINAL",
   plan.roles(),Set.of(REGION),context,Map.of("warn",StepState.CONFIRMED,"impact",StepState.SKIPPED_SAFE),
   Set.of(),Set.of(),Set.of(),Optional.empty(),Set.of(),Optional.of(new Tick(900)),new DurationTicks(100),
   def.timing().maxDurationTicks(),def.recovery().mode(),Optional.of(Outcome.PARTIAL));
  var reservation=reserve(plan,new Tick(1000));var fixture=prepared(plan,reservation,List.of(),0,new Tick(1000));
  var prepare=fixture.prepare();var result=new ResultRecord(prepare.transactionId(),prepare.operationToken(),fixture.success(),Set.of(),List.of(),List.of());
  var pre=new CountedMap<TransactionId,PrepareRecord>();pre.put(prepare.transactionId(),prepare);
  var results=new CountedMap<OperationToken,ResultRecord>();results.put(prepare.operationToken(),result);
  var legacy=new TerminalEventProgressEvidence(head,def,plan,pre,results,Set.of(),Set.of(),null,Set.of());
  pre.lookups=0;results.lookups=0;
  for(int i=0;i<1000;i++)if(!legacy.matches(head,def,plan,pre,results,Set.of(),Set.of(),null,Set.of()))throw new AssertionError();
  long oldReads=pre.lookups+results.lookups;check(oldReads>=2000,"real legacy immutable-key comparator walks unchanged maps on every check");
  var memo=new TerminalEventProgressMemo();check(!memo.unchanged(head,def,plan,null),"new owner has no reconciled proof");
  memo.reconciled(head,def,plan,null);pre.lookups=0;results.lookups=0;
  for(int i=0;i<1000;i++)if(!memo.unchanged(head,def,plan,null))throw new AssertionError();
  check(pre.lookups+results.lookups==0,"unchanged owner key needs no per-tick evidence map traversal");
  var equalHead=new fr.proutlost.director.api.codec.ModelCodec<EventInstance>(EventInstance.class)
   .decode(new fr.proutlost.director.api.codec.ModelCodec<EventInstance>(EventInstance.class).encode(head));
  check(equalHead.equals(head)&&!memo.unchanged(equalHead,def,plan,null),"equal replacement EVENT conservatively recomputes");
  for(var source:List.of("PREPARE","RESULT","MUTATION","REQUEST","UNIQUE","DISASTER","COMPLETION","ABORT","PERCEPTION_END")){
   memo.reconciled(head,def,plan,null);memo.invalidate();
   check(!memo.unchanged(head,def,plan,null),"owner "+source+" touch invalidates even unchanged cardinalities/head/cut");
  }
  memo.invalidate();check(!memo.unchanged(head,def,plan,null),"repeated invalidation never fabricates a key");
  System.out.println("PASS TerminalEventOwnerMemo assertions="+checks+" legacy_1000_checks_map_lookups="+oldReads+" owner_1000_checks_map_lookups=0 scope=PURE_OWNER_CONTRACT_NATIVE_INGESTION_PENDING");
  TerminalEventProgressEvidenceTests.main(new String[]{args[0],args[1]});
  DirectorTickWorkBudgetTests.main(new String[]{args[2]});
 }
}
