package fr.proutlost.director.bootstrap;

import fr.proutlost.director.api.codec.*;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.engine.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Existing model codecs only. Technical archives never enter gameplay facts or outbox. */
public final class RecordedDecisionArchive {
    public static final ResourceId INPUT=new ResourceId("proutlost_director:decision_input");
    public static final ResourceId SNAPSHOT=new ResourceId("proutlost_director:planning_snapshot");
    public static final ResourceId EFFECT=new ResourceId("proutlost_director:prepared_effect");
    private RecordedDecisionArchive() {}
    private static <T> JsonValue json(Class<T> type,T value){return CanonicalJsonV1.decode(new ModelCodec<>(type).encode(value));}
    private static <T> T model(Class<T> type,JsonValue value){return new ModelCodec<>(type).decode(CanonicalJsonV1.encode(value));}
    private static String text(DirectorFact fact,String key){
        if(!(fact.payload().get(key) instanceof JsonValue.Text value))throw new IllegalArgumentException("decision archive text");return value.value();
    }
    private static void require(boolean value,String reason){if(!value)throw new IllegalArgumentException(reason);}
    private static <T> BlobContent capture(ResourceId codec,Class<T> type,T value){return BlobContent.capture(codec,1,new ModelCodec<>(type).encode(value));}
    /** Run on the existing bounded worker, with immutable actor-captured inputs. */
    public static Map.Entry<DirectorFact,List<BlobContent>> record(DecisionInput input,DecisionProposal proposal,
            Map<CandidateId,List<PlanningSnapshot>> planning,Map<OperationToken,PreparedEffect> effects,
            StoreSession source,ProviderMode mode) {
        validateInput(input,source,mode);
        var audit=proposal.audit();
        require(proposal.proposalId().equals(audit.decisionId())&&input.context().equals(audit.context())
                &&input.config().provenanceHash().equals(audit.configHash())&&input.catalog().hash().equals(audit.catalogHash())
                &&input.seed()==audit.seed(),"decision archive audit binding");
        var contents=new LinkedHashMap<BlobRef,BlobContent>();
        var captured=capture(INPUT,DecisionInput.class,input);contents.put(captured.reference(),captured);
        var pages=new ArrayList<JsonValue>();
        planning.entrySet().stream().sorted(Comparator.comparing(e->e.getKey().value().toString())).forEach(entry->{
            for(var original:entry.getValue()) {
                var snapshot=new PlanningSnapshot(original.snapshot(),original.ownership(),original.budgets(),new BlobBundle(List.of()),original.bindings());
                var blob=capture(SNAPSHOT,PlanningSnapshot.class,snapshot);contents.put(blob.reference(),blob);
                pages.add(new JsonValue.ObjectValue(Map.of("candidate",new JsonValue.Text(entry.getKey().value().toString()),"reference",json(BlobRef.class,blob.reference()))));
            }
        });
        var prepared=new ArrayList<JsonValue>();
        effects.entrySet().stream().sorted(Comparator.comparing(e->e.getKey().value().toString())).forEach(entry->{
            require(entry.getKey().equals(entry.getValue().intent().operationToken()),"decision archive effect identity");
            var blob=capture(EFFECT,PreparedEffect.class,entry.getValue());contents.put(blob.reference(),blob);prepared.add(json(BlobRef.class,blob.reference()));
        });
        require(contents.values().stream().mapToLong(b->b.reference().bytes()).sum()<=BlobContent.MAX_BATCH_BYTES,"decision archive batch bytes");
        var fact=new DirectorFact(new FactId(UUID.nameUUIDFromBytes((source.worldId().value()+"/recorded-decision/"+proposal.proposalId().value()).getBytes(StandardCharsets.UTF_8))),
                AdminContracts.RECORDED_DECISION_INPUT_FACT,List.of(new ScopeRef(ScopeKind.WORLD,source.worldId().value())),Optional.empty(),EvidenceStatus.CONFIRMED,
                Map.of("decision_id",new JsonValue.Text(proposal.proposalId().value().toString()),"input",json(BlobRef.class,captured.reference()),
                        "planning",new JsonValue.Array(pages),"effects",new JsonValue.Array(prepared),
                        "audit_hash",new JsonValue.Text(BlobContent.hash(new ModelCodec<>(AuditDecision.class).encode(audit)).value()),
                        "source_session",json(StoreSession.class,source),"provider_mode",new JsonValue.Text(mode.name())),true,Optional.empty());
        require(DurableBlobReferences.recordedDecision(fact).equals(contents.keySet()),"decision archive exact references");
        return Map.entry(fact,List.copyOf(contents.values()));
    }
    public static void validateInput(DecisionInput input,StoreSession source,ProviderMode mode) {
        require(input.context().scopes().worldId().equals(source.worldId())&&input.memory().scopes().worldId().equals(source.worldId())
                &&input.state().world().worldId().equals(source.worldId())&&input.state().world().storeGeneration().equals(source.storeGeneration())
                &&input.state().world().bootEpoch().equals(source.bootEpoch()),"decision archive world/session");
        require(input.state().world().configHash().equals(input.config().provenanceHash())&&input.state().world().catalogHash().equals(input.catalog().hash()),"decision archive configuration/catalogue hash");
        require(mode!=ProviderMode.PRODUCTION||input.config().calibration()==Calibration.APPROVED,"decision archive production calibration");
    }
    public static DecisionId decision(DirectorFact fact){return new DecisionId(UUID.fromString(text(fact,"decision_id")));}
    public static StoreSession source(DirectorFact fact){return model(StoreSession.class,fact.payload().get("source_session"));}
    public static ProviderMode mode(DirectorFact fact){return ProviderMode.valueOf(text(fact,"provider_mode"));}
    public static void validateIndex(DirectorFact fact,AuditDecision audit,StoreSession current,ProviderMode mode) {
        require(fact.factType().equals(AdminContracts.RECORDED_DECISION_INPUT_FACT)&&fact.durable()&&fact.evidenceStatus()==EvidenceStatus.CONFIRMED
                &&fact.eventId().isEmpty()&&fact.subjectRefs().equals(List.of(new ScopeRef(ScopeKind.WORLD,current.worldId().value()))),"decision archive evidence/scope");
        DurableBlobReferences.recordedDecision(fact);
        var source=model(StoreSession.class,fact.payload().get("source_session"));
        require(source.worldId().equals(current.worldId())&&source.storeGeneration().equals(current.storeGeneration())
                &&source.bootEpoch().compareTo(current.bootEpoch())<=0&&ProviderMode.valueOf(text(fact,"provider_mode"))==mode,"decision archive source session/mode");
        require(decision(fact).equals(audit.decisionId())&&text(fact,"audit_hash").equals(BlobContent.hash(new ModelCodec<>(AuditDecision.class).encode(audit)).value()),"decision archive audit hash");
    }
    /** Reconstructs the original pure planning result; no live provider is reachable. */
    public static DecisionProposal replay(DirectorFact fact,AuditDecision audit,Collection<BlobContent> hydrated,
            StoreSession current,ProviderMode mode,boolean withoutAi) {
        validateIndex(fact,audit,current,mode);
        var contents=new HashMap<BlobRef,BlobContent>();for(var content:hydrated)require(contents.put(content.reference(),content)==null,"duplicate archive blob");
        require(contents.keySet().equals(DurableBlobReferences.recordedDecision(fact)),"incomplete archive hydration");
        java.util.function.BiFunction<JsonValue,ResourceId,BlobContent> blob=(encoded,codec)->{
            var ref=model(BlobRef.class,encoded);require(ref.codecId().equals(codec)&&ref.codecVersion()==1,"decision archive codec");
            return Objects.requireNonNull(contents.get(ref),"missing decision archive blob");
        };
        var input=new ModelCodec<>(DecisionInput.class).decode(blob.apply(fact.payload().get("input"),INPUT).bytes());
        validateInput(input,model(StoreSession.class,fact.payload().get("source_session")),mode);
        require(input.context().equals(audit.context())&&input.config().provenanceHash().equals(audit.configHash())
                &&input.catalog().hash().equals(audit.catalogHash())&&input.seed()==audit.seed(),"recorded input differs from audit");
        var pages=new HashMap<CandidateId,List<PlanningSnapshot>>();
        for(var row:((JsonValue.Array)fact.payload().get("planning")).values()){
            var fields=((JsonValue.ObjectValue)row).values();var candidate=new CandidateId(UUID.fromString(((JsonValue.Text)fields.get("candidate")).value()));
            var snapshot=new ModelCodec<>(PlanningSnapshot.class).decode(blob.apply(fields.get("reference"),SNAPSHOT).bytes());
            require(snapshot.blobs().contents().isEmpty(),"archive contains ephemeral blob bytes");
            pages.computeIfAbsent(candidate,key->new ArrayList<>()).add(snapshot);
        }
        var effects=new HashMap<OperationToken,PreparedEffect>();
        for(var row:((JsonValue.Array)fact.payload().get("effects")).values()){
            var effect=new ModelCodec<>(PreparedEffect.class).decode(blob.apply(row,EFFECT).bytes());
            require(effects.put(effect.intent().operationToken(),effect)==null,"duplicate recorded effect token");
        }
        var planner=new CandidatePlanner(request->{
            var binding=request.snapshot().bindings().orElseThrow();var effect=effects.get(binding.operationToken());
            require(effect!=null&&effect.snapshot().equals(request.snapshot().snapshot())&&ObservedWeatherArguments.matches(request.step().args(),effect.intent().typedArgs(),effect.preconditions())
                    &&effect.intent().primitive().equals(request.step().primitive()),"recorded planning identity");
            return new PlannedPrimitive(binding,effect);
        },pages);
        var engine=new WorldDirector(mode,planner);var exact=engine.replay(input,audit);
        if(!withoutAi)return exact;
        return engine.evaluate(new DecisionInput(input.context(),input.memory(),input.catalog(),input.config(),input.requests(),input.chains(),input.seed(),input.state(),Optional.empty()));
    }
}
