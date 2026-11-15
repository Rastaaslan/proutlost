package fr.proutlost.director.adapter.neoforge.server.commands;

import com.mojang.brigadier.CommandDispatcher;
import fr.proutlost.director.api.model.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

public final class AdminCommandTreeTests {
    private static int checks;
    public static void main(String[] args) throws Exception {
        resultOutput();
        String id="00000000-0000-0000-0000-000000000001";
        var world=new WorldId(UUID.fromString(id));
        var context=new SnapshotBatch(new RequestId(UUID.fromString(id)),new ScopeSet(world,Set.of()),new Tick(0),Map.of(),
                new ObservationContext(Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),List.of(),Map.of()),Set.of(),Set.of(),HealthMode.NORMAL);
        sourceScopedParsing(context);
        var output=new AtomicReference<Map.Entry<AdminOperation,AdminPayload>>();
        var tree=new AdminCommandTree<Integer>(level->level,(level,name)->Set.of(new PlayerId(UUID.fromString(id))),fixture->context,()->100,
                (level,request)->{output.set(request);return 1;},(level,cursor)->{output.set(Map.entry(AdminOperation.EVENT_LIST,new EmptyAdminPayload()));return 1;});
        var dispatcher=new CommandDispatcher<Integer>();tree.register(dispatcher);
        Map<String,AdminOperation> cases=new LinkedHashMap<>();
        cases.put("proutlost status",AdminOperation.STATUS);cases.put("proutlost director status",AdminOperation.STATUS);
        cases.put("proutlost director ui",AdminOperation.UI);cases.put("proutlostdirectorui",AdminOperation.UI);
        cases.put("proutlost director pause",AdminOperation.PAUSE);cases.put("proutlost director resume",AdminOperation.RESUME);cases.put("proutlost director drain",AdminOperation.DRAIN);
        cases.put("proutlost director tension TENSE 40",AdminOperation.TENSION);cases.put("proutlost director intensity test:profile",AdminOperation.INTENSITY);
        cases.put("proutlost director event list",AdminOperation.EVENT_LIST);cases.put("proutlost director event inspect "+id,AdminOperation.EVENT_INSPECT);
        cases.put("proutlost stop all",AdminOperation.STOP);cases.put("proutlost stop "+id,AdminOperation.STOP);
        cases.put("proutlost rescue self",AdminOperation.RESCUE);cases.put("proutlost rescue Alex",AdminOperation.RESCUE);
        cases.put("proutlost report message with spaces",AdminOperation.REPORT);
        cases.put("proutlost trigger test:event region "+id,AdminOperation.TRIGGER);
        cases.put("proutlost trigger test:event region "+id+" targets @a",AdminOperation.TRIGGER);
        cases.put("proutlost director simulate test:fixture",AdminOperation.SIMULATE);
        cases.put("proutlost director replay "+id,AdminOperation.REPLAY);cases.put("proutlost director replay "+id+" without_ai",AdminOperation.REPLAY);
        cases.put("proutlost director region "+id,AdminOperation.REGION);cases.put("proutlost director player "+id,AdminOperation.PLAYER);
        cases.put("proutlost director chain "+id,AdminOperation.CHAIN);cases.put("proutlost director disaster "+id+" stabilize",AdminOperation.STABILIZE);
        cases.put("proutlost director audit export "+id,AdminOperation.AUDIT_EXPORT);cases.put("proutlost debug",AdminOperation.DEBUG);
        for(var entry:cases.entrySet()) {
            check(dispatcher.execute(entry.getKey(),4)==1,"command dispatched");
            check(output.get().getKey()==entry.getValue()&&AdminContracts.matches(entry.getValue(),output.get().getValue()),"typed payload "+entry.getKey());
        }
        check(dispatcher.execute("proutlost director event list opaque-cursor",4)==1,"paged list grammar");
        check(dispatcher.execute("proutlost rescue self",0)==1,"player self rescue");
        check(dispatcher.execute("proutlost report help",0)==1,"player report");
        check(dispatcher.execute("proutlost status",0)==1,"public status");
        check(dispatcher.execute("proutlost rescue Alex",2)==1,"moderator rescue");
        for(String bad:List.of("proutlost director pause","proutlost rescue Alex","proutlost debug","proutlost trigger test:event region "+id)) reject(dispatcher,bad,0);
        for(String bad:List.of("proutlost director tension TENSE 9223372036854775807","proutlost director tension TENSE -1",
                "proutlost trigger test:event region "+id+" bypass","proutlost trigger test:event region "+id+" force true",
                "proutlost director replay "+id+" execute","proutlost director audit export ../../file",
                "proutlost director disaster "+id+" destroy","proutlost stop all extra","proutlost director player not-a-uuid")) reject(dispatcher,bad,4);
        dispatcher.execute("proutlost director tension CALM 20",4);
        check(((TensionPayload)output.get().getValue()).expiresTick().value()==120,"expiry from server clock");
        dispatcher.execute("proutlost director simulate test:fixture",4);
        check(((SimulationPayload)output.get().getValue()).scoreOnly(),"simulation cannot execute");
        System.out.println("PASS AdminCommandTreeTests: "+checks+" assertions; native source/selector/UI execution NOT_RUN");
    }
    private static void sourceScopedParsing(SnapshotBatch first) throws Exception {
        var second=new SnapshotBatch(first.requestId(),new ScopeSet(new WorldId(new UUID(0,9)),Set.of()),first.capturedTick(),first.scopeRevisions(),
                first.context(),first.missingData(),first.loadedChunks(),first.serverHealth());
        var contexts=new HashMap<Integer,SnapshotBatch>();contexts.put(4,first);contexts.put(5,second);
        var clocks=new HashMap<Integer,Long>();clocks.put(4,100L);clocks.put(5,700L);
        var output=new AtomicReference<AdminPayload>();
        var tree=new AdminCommandTree<Integer>(level->level,(level,name)->Set.of(),
                (source,id)->contexts.get(source),source->{var tick=clocks.get(source);if(tick==null)throw new IllegalArgumentException("CAPABILITY_MISSING");return tick;},
                (source,request)->{output.set(request.getValue());return 1;},(source,cursor)->1);
        var dispatcher=new CommandDispatcher<Integer>();tree.register(dispatcher);
        dispatcher.execute("proutlost director tension CALM 20",4);
        check(((TensionPayload)output.get()).expiresTick().value()==120,"first server clock");
        dispatcher.execute("proutlost director tension CALM 20",5);
        check(((TensionPayload)output.get()).expiresTick().value()==720,"second server clock isolated");
        clocks.put(5,900L);dispatcher.execute("proutlost director tension CALM 20",5);
        check(((TensionPayload)output.get()).expiresTick().value()==920,"runtime clock looked up after registration");
        dispatcher.execute("proutlost director simulate test:fixture",4);
        check(((SimulationPayload)output.get()).injectedContext().equals(first),"first server fixture");
        dispatcher.execute("proutlost director simulate test:fixture",5);
        check(((SimulationPayload)output.get()).injectedContext().equals(second),"second server fixture isolated");
        reject(dispatcher,"proutlost director tension CALM 20",6);
        reject(dispatcher,"proutlost director simulate test:fixture",6);
    }
    private static void resultOutput() {
        UUID id=new UUID(0,42);var session=new SessionId(id);var request=new RequestId(id);
        var player=new ActorIdentity(session,ActorKind.PLAYER,Optional.of(new PlayerId(id)),Optional.empty(),0,true);
        var admin=new ActorIdentity(session,ActorKind.PLAYER,player.playerId(),Optional.empty(),4,true);
        var command=new AdminCommand(session,request,new Revision(1),AdminOperation.STATUS,new EmptyAdminPayload());
        var row=new AdminRow(new ScopeRef(ScopeKind.PLAYER,id),new Revision(7),new Tick(9),new PublicStatusData(HealthMode.DEGRADED,true,Set.of(fr.proutlost.director.api.error.ReasonCode.BUDGET_EXHAUSTED)));
        List<String> lines=new ArrayList<>();
        CommandResultOutput.deliver(command,new AdminResult(AdminStatus.SUCCEEDED,request,new Revision(7),Optional.of(row),Optional.empty()),player,lines::add);
        check(lines.stream().anyMatch(l->l.contains("DEGRADED"))&&lines.stream().anyMatch(l->l.contains("localAnomaly : true")),"public status data displayed");
        check(lines.stream().anyMatch(l->l.contains("BUDGET_EXHAUSTED")),"public status reasons displayed");
        var header=new AggregateHeader(1,id,new Revision(7),new Sequence(1),new Tick(9));
        var memory=new PlayerOperationalMemory(header,new PlayerId(id),new DurationTicks(123),Optional.empty(),Optional.empty(),List.of(),List.of(),Set.of(),new UnitValue(0.5),Map.of(),Optional.empty(),new Epoch(1));
        var privateRow=new AdminRow(row.scope(),row.revision(),row.capturedTick(),new PlayerAdminData(memory,List.of()));
        var result=new AdminResult(AdminStatus.SUCCEEDED,request,new Revision(7),Optional.of(privateRow),Optional.empty());
        var inspect=new AdminCommand(session,request,new Revision(1),AdminOperation.PLAYER,new PlayerInspectionPayload(new PlayerId(id)));
        lines.clear();CommandResultOutput.deliver(inspect,result,admin,lines::add);
        check(lines.stream().anyMatch(l->l.contains("eligibleOnlineTicks / value : 123")),"player inspection data displayed");
        check(lines.stream().anyMatch(l->l.contains("Révision 7")),"inspection revision displayed");
        lines.clear();CommandResultOutput.deliver(command,result,player,lines::add);
        check(lines.size()==1&&!lines.getFirst().contains("eligibleOnlineTicks"),"private result hidden from public status");
        lines.clear();CommandResultOutput.deliver(inspect,result,player,lines::add);check(lines.isEmpty(),"revoked inspection emits no data");
        var validation=new ValidationResult(true,List.of(),new ReadSet(Map.of(),Map.of(),Map.of()));
        var details=new AdminRow(row.scope(),row.revision(),row.capturedTick(),new DecisionAdminData(new DecisionId(id),new Tick(9),List.of(),Optional.empty(),Optional.empty(),Collections.nCopies(100,validation),Optional.empty()));
        lines.clear();CommandResultOutput.deliver(command,new AdminResult(AdminStatus.SUCCEEDED,request,new Revision(7),Optional.of(details),Optional.empty()),admin,lines::add);
        check(lines.size()==CommandResultOutput.MAX_LINES&&lines.stream().allMatch(l->l.length()<=CommandResultOutput.MAX_CHARS),"output bounded");
        check(lines.getLast().contains("Détail limité"),"truncation visible");
    }
    private static void reject(CommandDispatcher<Integer> dispatcher,String text,int level) throws Exception {
        try {dispatcher.execute(text,level);throw new AssertionError("Accepted invalid command: "+text);}
        catch(com.mojang.brigadier.exceptions.CommandSyntaxException expected){checks++;}
    }
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
}
