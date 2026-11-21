package fr.proutlost.integration.contra;

import fr.proutlost.contract.director.EventNeed;
import fr.proutlost.contract.director.StrictCodec;
import fr.proutlost.director.api.codec.*;
import fr.proutlost.director.api.model.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Pure decode of explicit owner-authored definition→D0 need bindings. No tags,
 * filenames or definition IDs imply a requirement. Caller reads its authorized
 * file off actor and pins these exact bytes as ordinary operator metadata. */
public final class D0PhysicalRequirements {
    private D0PhysicalRequirements() {}
    public static Map<DefinitionRef,EventNeed> decode(byte[] bytes,Map<DefinitionId,DefinitionRef> actualDefinitions){
        Objects.requireNonNull(bytes);Objects.requireNonNull(actualDefinitions);
        if(bytes.length<1||bytes.length>1_048_576)throw new IllegalArgumentException("physical requirements byte bound");
        var root=((JsonValue.ObjectValue)CanonicalJsonV1.decode(bytes)).values();
        if(!root.keySet().equals(Set.of("schema_version","bindings"))
                ||!root.get("schema_version").equals(new JsonValue.Number(java.math.BigDecimal.ONE))
                ||!(root.get("bindings") instanceof JsonValue.Array rows)||rows.values().isEmpty()||rows.values().size()>1024)
            throw new IllegalArgumentException("physical requirement schema/bindings");
        Map<DefinitionRef,EventNeed> result=new HashMap<>();Set<DefinitionId> used=new HashSet<>();
        for(var row:rows.values()){
            var fields=((JsonValue.ObjectValue)row).values();
            if(!fields.keySet().equals(Set.of("definition","need")))throw new IllegalArgumentException("physical requirement fields");
            var definition=new ModelCodec<>(DefinitionRef.class).decode(CanonicalJsonV1.encode(fields.get("definition")));
            if(!definition.equals(actualDefinitions.get(definition.id()))||!used.add(definition.id()))
                throw new IllegalArgumentException("physical requirement absent/duplicate/stale actual definition");
            var need=StrictCodec.decode(new String(CanonicalJsonV1.encode(fields.get("need")),StandardCharsets.UTF_8),EventNeed.class);
            if(need.evidence().isEmpty())throw new IllegalArgumentException("physical need evidence required");
            result.put(definition,need);
        }
        return Map.copyOf(result);
    }
}
