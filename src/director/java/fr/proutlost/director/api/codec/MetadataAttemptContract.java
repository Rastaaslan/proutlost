package fr.proutlost.director.api.codec;

import fr.proutlost.director.api.model.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Closed J04 technical attempt records carried by the existing durable fact
 * format. An attempt never changes the source revision or its generation ID. */
public final class MetadataAttemptContract {
    public static final FactType REPREPARE=new FactType("proutlost_director:metadata_reprepare_v1");
    public static final FactType STAGE=new FactType("proutlost_director:metadata_attempt_stage_v1");
    public static final FactType ACTIVATE=new FactType("proutlost_director:metadata_attempt_activate_v1");
    public record Reprepare(LocationTransferManifest transfer,Epoch expectedAttempt,Optional<Fingerprint> previousGeneration,Tick tick) {}
    public record Staged(Epoch attempt,DurableMetadataStage value) {}
    public record Activated(Epoch attempt,DurableMetadataActivation value) {}
    private MetadataAttemptContract(){}
    private static <T> JsonValue encode(Class<T> type,T value){return CanonicalJsonV1.decode(new ModelCodec<>(type).encode(value));}
    private static <T> T decode(Class<T> type,JsonValue value){return new ModelCodec<>(type).decode(CanonicalJsonV1.encode(value));}
    private static FactId id(FactType type,LocationTransferManifest transfer,Epoch attempt,String suffix){
        return new FactId(UUID.nameUUIDFromBytes((type.value()+"/"+transfer.worldId().value()+"/"+
            LocationTransferContract.identity(transfer).value()+"/"+attempt.value()+"/"+suffix).getBytes(StandardCharsets.UTF_8)));
    }
    private static DirectorFact fact(FactType type,LocationTransferManifest transfer,Epoch attempt,String suffix,Map<String,JsonValue> payload){
        return new DirectorFact(id(type,transfer,attempt,suffix),type,List.of(new ScopeRef(ScopeKind.WORLD,transfer.worldId().value())),
            Optional.empty(),EvidenceStatus.CONFIRMED,payload,true,Optional.empty());
    }
    public static DirectorFact reprepare(LocationTransferManifest transfer,Epoch expectedAttempt,Optional<Fingerprint> previousGeneration,Tick tick){
        Objects.requireNonNull(previousGeneration);
        return fact(REPREPARE,transfer,expectedAttempt.plus(1),"",Map.of("transfer",encode(LocationTransferManifest.class,transfer),
            "expected_attempt",encode(Epoch.class,expectedAttempt),"previous_generation",previousGeneration.<JsonValue>map(x->encode(Fingerprint.class,x)).orElse(JsonValue.Nil.INSTANCE),
            "tick",encode(Tick.class,tick)));
    }
    public static DirectorFact stage(Epoch attempt,DurableMetadataStage value){
        Checks.require(attempt.value()>0,"versioned metadata attempt required");
        return fact(STAGE,value.transfer(),attempt,Integer.toString(value.pageIndex()),Map.of("attempt",encode(Epoch.class,attempt),"stage",encode(DurableMetadataStage.class,value)));
    }
    public static DirectorFact activate(Epoch attempt,DurableMetadataActivation value){
        Checks.require(attempt.value()>0,"versioned metadata attempt required");
        return fact(ACTIVATE,value.transfer(),attempt,"",Map.of("attempt",encode(Epoch.class,attempt),"activation",encode(DurableMetadataActivation.class,value)));
    }
    private static void authority(DirectorFact value,WorldId world,Set<String> fields){
        Checks.require(value.durable()&&value.evidenceStatus()==EvidenceStatus.CONFIRMED&&value.eventId().isEmpty()
            &&value.supersedesFactId().isEmpty()&&value.subjectRefs().equals(List.of(new ScopeRef(ScopeKind.WORLD,world.value())))
            &&value.payload().keySet().equals(fields),"metadata attempt fact authority/fields");
    }
    public static Optional<Reprepare> reprepare(DirectorFact fact,WorldId world){
        if(!fact.factType().equals(REPREPARE))return Optional.empty();authority(fact,world,Set.of("transfer","expected_attempt","previous_generation","tick"));
        var transfer=decode(LocationTransferManifest.class,fact.payload().get("transfer"));var epoch=decode(Epoch.class,fact.payload().get("expected_attempt"));
        var previous=fact.payload().get("previous_generation");Optional<Fingerprint> generation=previous instanceof JsonValue.Nil?Optional.empty():Optional.of(decode(Fingerprint.class,previous));
        var tick=decode(Tick.class,fact.payload().get("tick"));
        Checks.require(transfer.worldId().equals(world)&&fact.equals(reprepare(transfer,epoch,generation,tick)),"metadata reprepare identity");
        return Optional.of(new Reprepare(transfer,epoch,generation,tick));
    }
    public static Optional<Staged> staged(DirectorFact fact,WorldId world){
        if(!fact.factType().equals(STAGE))return Optional.empty();authority(fact,world,Set.of("attempt","stage"));
        var epoch=decode(Epoch.class,fact.payload().get("attempt"));var value=decode(DurableMetadataStage.class,fact.payload().get("stage"));
        Checks.require(value.transfer().worldId().equals(world)&&fact.equals(stage(epoch,value)),"metadata attempt stage identity");return Optional.of(new Staged(epoch,value));
    }
    public static Optional<Activated> activated(DirectorFact fact,WorldId world){
        if(!fact.factType().equals(ACTIVATE))return Optional.empty();authority(fact,world,Set.of("attempt","activation"));
        var epoch=decode(Epoch.class,fact.payload().get("attempt"));var value=decode(DurableMetadataActivation.class,fact.payload().get("activation"));
        Checks.require(value.transfer().worldId().equals(world)&&fact.equals(activate(epoch,value)),"metadata attempt activation identity");return Optional.of(new Activated(epoch,value));
    }
    /** Typed reference extraction is shared by WAL, retention and ACK verification. */
    public static Set<BlobRef> references(DirectorFact fact){
        if(!fact.factType().equals(STAGE))return Set.of();
        Checks.require(fact.subjectRefs().size()==1&&fact.subjectRefs().getFirst().kind()==ScopeKind.WORLD,"metadata stage world");
        return Set.of(staged(fact,new WorldId(fact.subjectRefs().getFirst().id())).orElseThrow().value().page());
    }
}
