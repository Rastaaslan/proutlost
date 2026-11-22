package fr.proutlost.director.bootstrap;

import fr.proutlost.director.api.codec.*;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.port.PlanningPort;
import fr.proutlost.director.engine.*;
import fr.proutlost.director.persistence.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Pure synthetic contract inputs; invokes actual shipped native-adapter method.
 * No Minecraft boot, author approval, world effect or human reception is claimed. */
public final class NativeReadyRequirementRankTests {
    @SuppressWarnings("unchecked")
    private static <T>T replace(T value,String name,Object replacement)throws Exception {
        var components=value.getClass().getRecordComponents();
        var types=new Class<?>[components.length];var values=new Object[components.length];
        boolean found=false;
        for(int i=0;i<components.length;i++){
            types[i]=components[i].getType();values[i]=components[i].getAccessor().invoke(value);
            if(components[i].getName().equals(name)){values[i]=replacement;found=true;}
        }
        if(!found)throw new AssertionError("Fixture record field absent "+name);
        return (T)value.getClass().getConstructor(types).newInstance(values);
    }
    @SuppressWarnings("unchecked")
    private static <T>T original(String method,Class<?>[] types,Object... args)throws Exception {
        var m=DecisionPipelineTest.class.getDeclaredMethod(method,types);m.setAccessible(true);
        return (T)m.invoke(null,args);
    }
    private static ConfigView config(Path path)throws Exception {
        var root=(JsonValue.ObjectValue)CanonicalJsonV1.decode(Files.readAllBytes(path));
        var values=(JsonValue.ObjectValue)root.values().get("values");
        var numbers=new HashMap<String,JsonValue.Number>();
        values.values().forEach((k,v)->numbers.put(k,(JsonValue.Number)v));
        return new ConfigView(new Revision(1),new Fingerprint("0".repeat(64)),Calibration.DEFAULT_FOR_TESTING,numbers);
    }
    private static Map<String,Object> actual(ConfigView config,Path root,String label,
            Priority definitionPriority,boolean requirement,double credit,boolean expected)throws Exception {
        DecisionInput input=original("fixture",new Class<?>[]{ConfigView.class,int.class},config,1);
        var definition=input.catalog().events().values().iterator().next();
        definition=replace(definition,"conflicts",replace(definition.conflicts(),"priority",definitionPriority));
        input=replace(input,"catalog",replace(input.catalog(),"events",Map.of(definition.id(),definition)));
        if(requirement){
            RequestView request=original("request",new Class<?>[]{DecisionInput.class,boolean.class},input,false);
            input=replace(input,"requests",List.of(request));
        }
        input=replace(input,"state",replace(input.state(),"world",replace(input.state().world(),"engineControl",
                replace(input.state().world().engineControl(),"requiredServiceCredit",new UnitValue(credit)))));
        CandidateGenerator.Generated generated=original("generated",new Class<?>[]{DecisionInput.class},input);
        var step=generated.definition().steps().getFirst();
        var footprint=new Footprint(new DimensionId("minecraft:overworld"),input.context().loadedChunks(),Set.of(),List.of());
        var event=new EventInstanceId(UUID.randomUUID());var operation=new OperationToken(UUID.randomUUID());
        var binding=new PlanningBindings(generated.id(),generated.reference(),event,operation,step.id(),
                generated.roles(),generated.scopes(),footprint,Set.copyOf(generated.roles().roles().values().stream().flatMap(List::stream).toList()),true);
        var snapshot=new PlanningSnapshot(input.context(),input.state().ownership(),input.state().budgets(),new BlobBundle(List.of()),Optional.of(binding));
        var blob=BlobContent.capture(new ResourceId("proutlost:rank_contract_plan"),1,new byte[]{1});
        var prepared=new PreparedEffect(new EffectIntent(operation,step.primitive(),1,step.args(),footprint,event),
                input.context(),List.of(),new ReadSet(input.context().scopeRevisions(),Map.of(),Map.of()),Map.of(),
                generated.definition().budget(),List.of(),Set.of(new CapabilityId("perception.sound_v1")),
                new EffectBlobs(Set.of(),blob.reference(),blob.reference(),Map.of(),Map.of()));
        PlanningPort planner=request->new PlannedPrimitive(request.snapshot().bindings().orElseThrow(),prepared);
        var proposal=new WorldDirector(ProviderMode.TEST_ONLY,new CandidatePlanner(planner,Map.of(generated.id(),List.of(snapshot)))).evaluate(input);
        if(proposal.selectedPlan().isEmpty())throw new AssertionError("Actual selector did not produce fixture plan "+proposal.candidates());
        var selected=new SelectedEventStart(input,proposal,proposal.selectedPlan().orElseThrow(),Optional.empty());
        var audience=Set.copyOf(selected.participants());
        var scheduled=proposal.stateChanges().stream().map(AggregateChange::next).filter(RequestAggregate.class::isInstance)
                .map(RequestAggregate.class::cast).filter(a->a.value().status()==RequestStatus.SCHEDULED)
                .map(a->a.value().requestId().value().toString()).toList();
        if(requirement&&scheduled.size()!=1)throw new AssertionError("Actual decision must schedule the exact matching request");
        var options=new StoreOptions(1_048_620,2_097_152,4_194_304,16_777_216,32,0,128);
        var store=JournalStore.open(root.resolve(label),input.state().world().worldId(),options).toCompletableFuture().get(20,TimeUnit.SECONDS);
        try{
            // Empty typed constructor context for the isolated method only.
            // No authored content/config is approved or installed.
            var constructorConfig=replace(config,"calibration",Calibration.APPROVED);
            var emptyCatalog=new CatalogSnapshot(new Epoch(1),new Fingerprint("0".repeat(64)),Map.of(),Map.of(),Map.of());
            var content=new DirectorRuntimeContent(ProviderMode.PRODUCTION,constructorConfig,emptyCatalog);
            var envelope=new DirectorNativeEventEnvelopes(store,content,()->new Tick(20000),new Epoch(0));
            var readyType=Class.forName(DirectorNativeEventEnvelopes.class.getName()+"$Ready");
            var constructor=readyType.getDeclaredConstructor(SelectedEventStart.class,List.class,Set.class,java.util.function.BooleanSupplier.class);
            constructor.setAccessible(true);
            var ready=constructor.newInstance(selected,List.of(prepared),audience,(java.util.function.BooleanSupplier)()->true);
            var method=DirectorNativeEventEnvelopes.class.getDeclaredMethod("proposal",readyType,DecisionInput.class,Tick.class);
            method.setAccessible(true);
            var result=(Optional<?>)method.invoke(envelope,ready,input,new Tick(20000));
            var internal=result.orElseThrow();var accessor=internal.getClass().getDeclaredMethod("candidate");accessor.setAccessible(true);
            var candidate=(fr.proutlost.director.runtime.ResourceArbiter.Candidate)accessor.invoke(internal);
            var row=new LinkedHashMap<String,Object>();
            row.put("case",label);row.put("definition_priority",definitionPriority.name());row.put("actual_matching_SCHEDULED_request_ids",scheduled);
            row.put("captured_credit",credit);row.put("required_service_share",config.values().get("scheduler.required_service_share").value());
            row.put("actual_native_readyRequirement",candidate.rank().readyRequirement());row.put("expected_native_readyRequirement",expected);
            row.put("contract_pass",candidate.rank().readyRequirement()==expected);
            row.put("native_adapter_loaded_from",DirectorNativeEventEnvelopes.class.getProtectionDomain().getCodeSource().getLocation().toString());
            return row;
        }finally{store.closeAsync().toCompletableFuture().get(20,TimeUnit.SECONDS);}
    }
    public static void main(String[] args)throws Exception {
        var config=config(Path.of(args[0]));var root=Path.of(args[1]);
        if(Files.exists(root))throw new AssertionError("New isolated pure-contract directory required");
        Files.createDirectory(root);var rows=new ArrayList<Map<String,Object>>();
        rows.add(actual(config,root,"audited_REQUIRE_lane_ambient_definition",Priority.AMBIENT,true,.9,true));
        rows.add(actual(config,root,"REQUIRE_definition_without_actual_request",Priority.REQUIRE,false,0,false));
        rows.add(actual(config,root,"ambient_lane_coincident_matching_request",Priority.AMBIENT,true,0,false));
        System.out.println("R53_ACTUAL_NATIVE_READY_RANK_CASES "+new com.google.gson.Gson().toJson(rows));
        long failures=rows.stream().filter(r->Boolean.FALSE.equals(r.get("contract_pass"))).count();
        System.out.println("R53_NATIVE_READY_RANK_EXPECTED_FAILURES "+failures);
        if(failures!=0)throw new AssertionError("Native opportunity priority contract failed: "+failures);
        System.out.println("PASS exact native adapter REQUIRE opportunity semantics; no Minecraft or authored approval claim");
    }
}
