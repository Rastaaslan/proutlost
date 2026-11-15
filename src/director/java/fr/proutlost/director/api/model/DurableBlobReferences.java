package fr.proutlost.director.api.model;

import java.lang.reflect.RecordComponent;
import java.util.*;

/**
 * Immutable J04 record-reference walker shared by the WAL writer and
 * actor-side ACK verifier. Only trusted API model records are traversed.
 * It performs no IO and grants no permit.
 */
public final class DurableBlobReferences {
    private DurableBlobReferences() {}

    public static Set<BlobRef> collect(Object value) {
        var refs = new HashSet<BlobRef>();
        visit(value, refs);
        return Set.copyOf(refs);
    }

    private static void visit(Object value, Set<BlobRef> refs) {
        if(value instanceof DirectorFact regional&&regional.factType().equals(RegionalWeatherJournal.TYPE))
            refs.addAll(RegionalWeatherJournal.references(regional));
        if(value instanceof DirectorFact metadata&&metadata.factType().equals(PhysicalMetadataFacts.TYPE))
            refs.addAll(PhysicalMetadataFacts.references(metadata));
        if(value instanceof DirectorFact physical&&physical.factType().equals(PhysicalCatalogFacts.TYPE))
            refs.addAll(PhysicalCatalogFacts.references(physical));
        if(value instanceof DirectorFact fact&&fact.factType().equals(AdminContracts.RECORDED_DECISION_INPUT_FACT))
            refs.addAll(recordedDecision(fact));
        if (value instanceof BlobRef ref) { refs.add(ref); return; }
        if (value instanceof Optional<?> optional) {
            optional.ifPresent(x -> visit(x, refs));
            return;
        }
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, item) -> { visit(key, refs); visit(item, refs); });
            return;
        }
        if (value instanceof Collection<?> items) {
            items.forEach(item -> visit(item, refs));
            return;
        }
        if (value != null && value.getClass().isRecord() &&
                value.getClass().getPackageName().equals("fr.proutlost.director.api.model")) {
            try {
                for (RecordComponent component : value.getClass().getRecordComponents()) {
                    visit(component.getAccessor().invoke(value), refs);
                }
            } catch (ReflectiveOperationException invalid) {
                throw new IllegalArgumentException("API record inaccessible", invalid);
            }
        }
    }
    /** Reserved technical index; decode actual BlobRef DTOs before WAL/ACK/retention. */
    public static Set<BlobRef> recordedDecision(DirectorFact fact) {
        Checks.require(fact.factType().equals(AdminContracts.RECORDED_DECISION_INPUT_FACT),"not a decision archive index");
        Checks.require(fact.payload().keySet().equals(Set.of("decision_id","input","planning","effects","audit_hash","source_session","provider_mode")),"decision archive fields");
        var codec=new fr.proutlost.director.api.codec.ModelCodec<>(BlobRef.class);
        var refs=new HashSet<BlobRef>();
        java.util.function.Function<JsonValue,BlobRef> decode=value->codec.decode(fr.proutlost.director.api.codec.CanonicalJsonV1.encode(value));
        refs.add(decode.apply(fact.payload().get("input")));
        Checks.require(fact.payload().get("planning") instanceof JsonValue.Array&&fact.payload().get("effects") instanceof JsonValue.Array,"decision archive lists");
        var planning=((JsonValue.Array)fact.payload().get("planning")).values();
        var effects=((JsonValue.Array)fact.payload().get("effects")).values();
        Checks.require(planning.size()<=1024&&effects.size()<=1024,"decision archive reference capacity");
        for(var row:planning){Checks.require(row instanceof JsonValue.ObjectValue,"decision planning reference");
            var fields=((JsonValue.ObjectValue)row).values();Checks.require(fields.keySet().equals(Set.of("candidate","reference")),"decision planning fields");
            refs.add(decode.apply(fields.get("reference")));}
        for(var row:effects)refs.add(decode.apply(row));
        return Set.copyOf(refs);
    }
}
