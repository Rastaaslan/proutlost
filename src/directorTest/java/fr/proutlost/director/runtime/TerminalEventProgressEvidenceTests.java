package fr.proutlost.director.runtime;
import fr.proutlost.director.api.model.*;
import java.nio.file.*;
import java.util.*;
import static fr.proutlost.director.runtime.CatalogueEventLedgerFixtures.*;

/** Pure original DTO evidence invalidation, then existing real J04 ledger tests.
 * These inputs are synthetic and never claim native effects or author approval. */
public final class TerminalEventProgressEvidenceTests {
    private static int checks;
    private static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    @SuppressWarnings("unchecked")
    private static <T extends Record>T changed(T value,String field,Object next)throws Exception{
        var components=value.getClass().getRecordComponents();var types=new Class<?>[components.length];var args=new Object[components.length];
        boolean found=false;for(int i=0;i<components.length;i++){
            types[i]=components[i].getType();args[i]=components[i].getName().equals(field)?next:components[i].getAccessor().invoke(value);
            found|=components[i].getName().equals(field);
        }if(!found)throw new AssertionError("missing fixture record field "+field);
        return (T)value.getClass().getDeclaredConstructor(types).newInstance(args);
    }
    private static TerminalEventProgressEvidence key(EventInstance h,EventDefinition d,ExecutionPlan p,
            Map<TransactionId,PrepareRecord> pre,Map<OperationToken,ResultRecord> results){
        return new TerminalEventProgressEvidence(h,d,p,pre,results,Set.of(),Set.of(),null,Set.of());
    }
    public static void main(String[] args)throws Exception{
        var loaded=load(Path.of(args[0]));var definition=loaded.event("controlled_landslide");
        var input=input(loaded,REGION,List.of(FIRST),new Tick(1000));var plan=plan(definition,input,List.of(FIRST));
        var context=BlobContent.capture(new ResourceId("fixture:terminal_context"),1,new byte[]{7}).reference();
        var head=new EventInstance(header(plan.effects().getFirst().instanceId().value(),new Tick(1000)),
            plan.effects().getFirst().instanceId(),plan.definition(),new Epoch(1),EventState.PARTIAL,"SYNTHETIC_TERMINAL",
            plan.roles(),Set.of(REGION),context,Map.of("warn",StepState.CONFIRMED,"impact",StepState.SKIPPED_SAFE),
            Set.of(),Set.of(),Set.of(),Optional.empty(),Set.of(),Optional.of(new Tick(900)),new DurationTicks(100),
            definition.timing().maxDurationTicks(),definition.recovery().mode(),Optional.of(Outcome.PARTIAL));
        var reservation=reserve(plan,new Tick(1000));var fixture=prepared(plan,reservation,List.of(),0,new Tick(1000));
        var prepare=fixture.prepare();var result=new ResultRecord(prepare.transactionId(),prepare.operationToken(),fixture.success(),Set.of(),List.of(),List.of());
        var prepares=new HashMap<TransactionId,PrepareRecord>();prepares.put(prepare.transactionId(),prepare);
        var results=new HashMap<OperationToken,ResultRecord>();results.put(prepare.operationToken(),result);
        var key=key(head,definition,plan,prepares,results);
        check(key.matches(head,definition,plan,prepares,results,Set.of(),Set.of(),null,Set.of()),"unchanged terminal evidence exactly matches");
        var h=changed(head,"header",new AggregateHeader(1,head.header().id(),new Revision(2),new Sequence(5),new Tick(1001)));
        check(!key.matches(h,definition,plan,prepares,results,Set.of(),Set.of(),null,Set.of()),"new committed EVENT revision invalidates");
        var d=changed(definition,"contentRevision",definition.contentRevision().plus(1));
        check(!key.matches(head,d,plan,prepares,results,Set.of(),Set.of(),null,Set.of()),"definition content change invalidates");
        var p=changed(plan,"readSet",new ReadSet(Map.of(),Map.of(),Map.of()));
        check(!key.matches(head,definition,p,prepares,results,Set.of(),Set.of(),null,Set.of()),"original selected plan/readset change invalidates");
        var altered=new HashMap<>(prepares);altered.put(prepare.transactionId(),changed(prepare,"expiryTick",new Tick(prepare.expiryTick().value()-1)));
        check(!key.matches(head,definition,plan,altered,results,Set.of(),Set.of(),null,Set.of()),"same-size same-key changed PREPARE is not skipped");
        var changedResults=new HashMap<>(results);changedResults.put(prepare.operationToken(),changed(result,"result",new SliceResult(Outcome.ABORTED_SAFE,Set.of(),Map.of(),0,Set.of(),Set.of(),List.of())));
        check(!key.matches(head,definition,plan,prepares,changedResults,Set.of(),Set.of(),null,Set.of()),"late/replaced actual RESULT invalidates even at same size");
        check(!key.matches(head,definition,plan,prepares,results,Set.of(new MutationId(UUID.randomUUID())),Set.of(),null,Set.of()),"mutation evidence invalidates");
        check(!key.matches(head,definition,plan,prepares,results,Set.of(),Set.of(new RequestId(UUID.randomUUID())),null,Set.of()),"requirement binding invalidates");
        check(!key.matches(head,definition,plan,prepares,results,Set.of(),Set.of(),null,Set.of(new ReservationId(UUID.randomUUID()))),"unique reservation evidence invalidates");
        var warning=new WarningEvidence(Set.of(FIRST),new Tick(1000),new DurationTicks(400),BlobContent.hash(new byte[]{8}),new Epoch(1),true,true);
        var disaster=new DisasterInstance(header(UUID.randomUUID(),new Tick(1000)),new DisasterId(UUID.randomUUID()),head.instanceId(),definition.id(),
            DisasterStage.RESOLVED,Map.of(),new UnitValue(1),plan.effects().getFirst().expectedFootprint(),warning,Set.of(FIRST),List.of(),context,Set.of(),"SYNTHETIC_RESOLVED",Set.of());
        check(!key.matches(head,definition,plan,prepares,results,Set.of(),Set.of(),disaster,Set.of()),"late disaster warning evidence invalidates");
        var withDisaster=new TerminalEventProgressEvidence(head,definition,plan,prepares,results,Set.of(),Set.of(),disaster,Set.of());
        check(!withDisaster.matches(head,definition,plan,prepares,results,Set.of(),Set.of(),changed(disaster,"riskScore",new UnitValue(.5)),Set.of()),"changed disaster body invalidates");
        prepares.clear();results.clear();
        check(key.prepares().size()==1&&key.results().size()==1,"memo owns immutable copies rather than mutable evidence aliases");
        check(!key.matches(head,definition,plan,prepares,results,Set.of(),Set.of(),null,Set.of()),"evidence removal invalidates");
        boolean rejected=false;try{key(changed(head,"state",EventState.RUNNING),definition,plan,prepares,results);}catch(IllegalArgumentException expected){rejected=true;}
        check(rejected,"active EVENT progress can never acquire a terminal memo key");
        System.out.println("PASS TerminalEventProgressEvidence assertions="+checks+" scope=EXACT_PURE_METADATA_ONLY_NOT_NATIVE");
        CatalogueEventLedgerTests.main(new String[]{args[1],args[0]});
    }
}
