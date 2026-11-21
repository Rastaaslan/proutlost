package fr.proutlost.director.runtime;

import fr.proutlost.director.api.model.*;
import java.math.BigDecimal;
import java.util.*;

/** Synthetic immutable summary checks. Native index construction is qualified separately. */
public final class CatalogueBudgetIndexSummaryTests {
    private static int assertions;
    private static void check(boolean ok,String why){assertions++;if(!ok)throw new AssertionError(why);}
    private static void refuses(Runnable action){assertions++;try{action.run();throw new AssertionError("invalid summary accepted");}catch(IllegalArgumentException expected){}}
    private static ConfigView configuration(){
        Map<String,JsonValue.Number> values=new HashMap<>();
        Map.of("budget.tick_ms",5,"budget.global_intensity",8,"budget.region_intensity",3,
            "entity.director_world_cap",128,"event.disaster.mutation_units",1024,"io.queue_bytes",4194304,
            "client.max_effects",64,"network.s2c_payload_bytes",32768,"entity.max_path_nodes",512)
            .forEach((key,value)->values.put(key,new JsonValue.Number(BigDecimal.valueOf(value))));
        return new ConfigView(new Revision(1),new Fingerprint("1".repeat(64)),Calibration.DEFAULT_FOR_TESTING,values);
    }

    private static OwnershipRecord owner(int id){
        var key=new ResourceKey(OwnershipDomain.BLOCK,new DimensionId("minecraft:overworld"),id+",64,0");
        return new OwnershipRecord(new AggregateHeader(1,new UUID(0,id),new Revision(1),new Sequence(0),new Tick(0)),
          key,Set.of(new OwnerToken(new UUID(1,id))),new Epoch(1),OwnershipDisposition.DIRECTOR_ACTIVE,
          new MutationId(new UUID(2,id)),new Fingerprint("2".repeat(64)),new Epoch(0),Optional.empty());
    }
    private static OwnershipRecord changed(OwnershipRecord o,int kind){
        return new OwnershipRecord(kind==1?new AggregateHeader(1,o.header().id(),new Revision(2),new Sequence(0),new Tick(0)):o.header(),
          o.resourceKey(),kind==2?Set.of(new OwnerToken(new UUID(3,99))):o.ownerTokens(),
          kind==3?new Epoch(2):o.epoch(),kind==4?OwnershipDisposition.YIELDED_TO_PLAYER:o.disposition(),
          kind==5?new MutationId(new UUID(4,99)):o.creationMutationId(),
          kind==6?new Fingerprint("3".repeat(64)):o.expectedPostHash(),
          kind==7?new Epoch(1):o.playerOverrideEpoch(),o.exceptionContractId());
    }
    private static void ownedBlockTransfer(){
        var config=configuration();var a=owner(1);var b=owner(2);var foreign=owner(3);
        var owned=Map.of(a.resourceKey(),a,b.resourceKey(),b);
        List<AggregateState> heads=List.of(new OwnershipAggregate(a),new OwnershipAggregate(b));
        var original=CatalogueBudgetProjection.from(config,heads);
        var transferred=CatalogueBudgetProjection.withoutExactOwnedBlocks(config,heads,owned);
        var cost=new BudgetVector(0,0,0,0,0,1024,0,0,0,0);
        check(original.reserved().mutationsInFlight()==2,"real PRE physical debit remains visible before transfer");
        check(!(cost.mutationsInFlight()<=original.capacity().mutationsInFlight()-original.reserved().mutationsInFlight()),
          "counterexample: own two cells make unchanged full event cost impossible before transfer");
        check(transferred.capacity().equals(original.capacity()),"transfer never raises any configured capacity");
        check(transferred.reserved().mutationsInFlight()==0&&(cost.mutationsInFlight()<=transferred.capacity().mutationsInFlight()-transferred.reserved().mutationsInFlight()),
          "exact own PRE debit is contained in unchanged full event reservation");
        var withOther=new ArrayList<AggregateState>(heads);withOther.add(new OwnershipAggregate(foreign));
        var others=CatalogueBudgetProjection.withoutExactOwnedBlocks(config,withOther,owned);
        check(others.reserved().mutationsInFlight()==1&&!(cost.mutationsInFlight()<=others.capacity().mutationsInFlight()-others.reserved().mutationsInFlight()),
          "foreign debit remains charged and still blocks full capacity event");
        check(CatalogueBudgetProjection.from(config,heads).equals(original),"transfer never mutates committed input");
        refuses(()->CatalogueBudgetProjection.withoutExactOwnedBlocks(config,heads,Map.of()));
        refuses(()->CatalogueBudgetProjection.withoutExactOwnedBlocks(config,List.of(new OwnershipAggregate(a)),owned));
        refuses(()->CatalogueBudgetProjection.withoutExactOwnedBlocks(config,List.of(new OwnershipAggregate(a),new OwnershipAggregate(a),new OwnershipAggregate(b)),owned));
        for(int n=1;n<=7;n++){
          var changed=changed(a,n);
          refuses(()->CatalogueBudgetProjection.withoutExactOwnedBlocks(config,List.of(new OwnershipAggregate(changed),new OwnershipAggregate(b)),owned));
        }
        refuses(()->CatalogueBudgetProjection.withoutExactOwnedBlocks(config,heads,Map.of(a.resourceKey(),b,b.resourceKey(),a)));
        var invalid=changed(a,4);
        refuses(()->CatalogueBudgetProjection.withoutExactOwnedBlocks(config,List.of(new OwnershipAggregate(invalid)),Map.of(invalid.resourceKey(),invalid)));
    }

    public static void main(String[] args){
        var config=configuration();Set<ScopeRef> global=new HashSet<>();
        for(int n=0;n<700;n++)global.add(new ScopeRef(ScopeKind.REGION,new UUID(0,1000+n)));
        var budget=CatalogueBudgetProjection.fromSummary(config,global,37,12,19);
        check(budget.scopes().keySet().equals(global),"global cold region identities all charged above512 resident capacity");
        check(budget.scopes().size()==700,"selected context cannot truncate global budget region index");
        check(budget.reserved().liveEntities()==37&&budget.reserved().persistentEntities()==12
            &&budget.reserved().mutationsInFlight()==19,"all unloaded physical debits retained");
        check(budget.scopes().values().stream().allMatch(budget.reserved()::equals),"every region conservatively carries global physical debit");
        check(budget.capacity().tickMillis()==5&&budget.capacity().globalIntensity()==8&&budget.capacity().regionalIntensity()==3,"configured intensity capacity unchanged");
        check(budget.capacity().liveEntities()==128&&budget.capacity().persistentEntities()==128
            &&budget.capacity().mutationsInFlight()==1024,"configured physical capacity unchanged");
        check(budget.capacity().queuedIoBytes()==4194304&&budget.capacity().payloadBytes()==32768
            &&budget.capacity().clientUnits()==64&&budget.capacity().navigationNodes()==512,"configured IO/network/client/navigation capacities unchanged");
        check(budget.reserved().queuedIoBytes()==0&&budget.reserved().tickMillis()==0
            &&budget.reserved().globalIntensity()==0&&budget.reserved().regionalIntensity()==0,"live admission reservations not invented by committed index");
        var noRegions=CatalogueBudgetProjection.fromSummary(config,Set.of(),37,12,19);
        check(noRegions.scopes().isEmpty()&&noRegions.reserved().equals(budget.reserved()),"no active region does not erase global physical debit");
        global.clear();check(budget.scopes().size()==700,"published summary immune to caller index mutation");
        refuses(()->CatalogueBudgetProjection.fromSummary(config,Set.of(),-1,0,0));
        refuses(()->CatalogueBudgetProjection.fromSummary(config,Set.of(),1,2,0));
        refuses(()->CatalogueBudgetProjection.fromSummary(config,Set.of(),1,0,-1));
        refuses(()->CatalogueBudgetProjection.fromSummary(config,Set.of(new ScopeRef(ScopeKind.WORLD,new UUID(0,1))),1,0,0));
        ownedBlockTransfer();
        System.out.println("P008 global budget index summary: "+assertions+" assertions PASS; synthetic worldless scope only");
    }
}
