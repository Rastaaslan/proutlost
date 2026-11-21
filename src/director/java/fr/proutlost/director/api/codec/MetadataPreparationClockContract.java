package fr.proutlost.director.api.codec;

import fr.proutlost.director.api.model.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Shared closed fact/clock contract, with no writer or registry implementation.
 * A durable proposal clock is not a metadata activation or a
 * physical receipt. The closed fact reuses the existing durable-record format.
 */
public final class MetadataPreparationClockContract {
    public static final FactType TYPE=new FactType("proutlost_director:metadata_preparation_clock");
    private static final ModelCodec<LocationTransferManifest> CODEC=new ModelCodec<>(LocationTransferManifest.class);
    public record Prepared(LocationTransferManifest transfer,Tick tick) {}
    private MetadataPreparationClockContract(){}
    private static FactId id(LocationTransferManifest transfer){
        return new FactId(UUID.nameUUIDFromBytes(("director-metadata-preparation-clock-v1/"+transfer.worldId().value()+"/"
            +LocationTransferContract.identity(transfer).value()).getBytes(StandardCharsets.UTF_8)));
    }
    /** Recovery may reuse a J04 time, but a private clock alone is never proof. */
    public static Tick proposalTick(Optional<Tick> durable,Optional<Tick> local,Tick now){
        Objects.requireNonNull(durable);Objects.requireNonNull(local);Objects.requireNonNull(now);
        Checks.require(durable.isPresent()||local.isEmpty(),
            "orphan prepared spool has no durable J04 clock; preserve evidence and cancel/reissue generation");
        if(durable.isPresent()&&local.isPresent())Checks.require(durable.equals(local),"prepared spool clock differs from durable J04 clock");
        var selected=durable.orElse(now);
        Checks.require(selected.compareTo(now)<=0,"prepared generation clock ahead of native authority");return selected;
    }
    public static DirectorFact fact(LocationTransferManifest transfer,Tick tick){
        Objects.requireNonNull(transfer);Objects.requireNonNull(tick);
        return new DirectorFact(id(transfer),TYPE,List.of(new ScopeRef(ScopeKind.WORLD,transfer.worldId().value())),
            Optional.empty(),EvidenceStatus.CONFIRMED,Map.of("transfer",CanonicalJsonV1.decode(CODEC.encode(transfer)),
                "tick",new JsonValue.Text(Long.toString(tick.value()))),true,Optional.empty());
    }
    public static Optional<Prepared> decode(DirectorFact fact,WorldId world){
        if(!fact.factType().equals(TYPE))return Optional.empty();
        Checks.require(fact.durable()&&fact.evidenceStatus()==EvidenceStatus.CONFIRMED&&fact.eventId().isEmpty()
            &&fact.subjectRefs().equals(List.of(new ScopeRef(ScopeKind.WORLD,world.value())))
            &&fact.payload().keySet().equals(Set.of("transfer","tick")),"metadata preparation clock authority/fields");
        var transfer=CODEC.decode(CanonicalJsonV1.encode(fact.payload().get("transfer")));
        var tick=new ModelCodec<>(Tick.class).decode(CanonicalJsonV1.encode(fact.payload().get("tick")));
        Checks.require(transfer.worldId().equals(world)&&fact.factId().equals(id(transfer))
            &&fact.equals(fact(transfer,tick)),"metadata preparation clock identity");
        return Optional.of(new Prepared(transfer,tick));
    }
}
