package fr.proutlost.director.api.model;

import fr.proutlost.director.api.codec.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Reserved ordinary J04 facts. Every referenced blob participates in force/readback. */
public final class PhysicalMetadataFacts {
    private PhysicalMetadataFacts() {}
    public static final FactType TYPE=new FactType("proutlost_director:physical_metadata_v1");
    public static final ResourceId ADAPTERS_CODEC=new ResourceId("proutlost_director:physical_native_adapters_v1");
    public static WorldId world(PhysicalMetadataIndex value){
        return value instanceof PhysicalCatalogConfiguration c?c.key().directorWorld():((PhysicalSitePlanIndex)value).pin().catalog().key().directorWorld();
    }
    public static FactId id(PhysicalMetadataIndex value){
        String identity=value instanceof PhysicalCatalogConfiguration c
            ?"configuration/"+BlobContent.hash(new ModelCodec<>(PhysicalCatalogModels.Key.class).encode(c.key())).value()
            :"site/"+((PhysicalSitePlanIndex)value).pin().event().value();
        return new FactId(UUID.nameUUIDFromBytes((world(value).value()+"/physical-metadata-v1/"+identity).getBytes(StandardCharsets.UTF_8)));
    }
    public static Set<BlobRef> references(PhysicalMetadataIndex value){
        if(value instanceof PhysicalCatalogConfiguration c)return Set.of(c.source(),c.binding(),c.adapters());
        var p=(PhysicalSitePlanIndex)value;
        return Set.of(p.pin().catalog().completeSource(),p.binding(),p.pin().need(),p.pin().selection(),p.plan());
    }
    public static Set<BlobRef> references(DirectorFact fact){return references(index(fact));}
    public static DirectorFact fact(PhysicalMetadataIndex index){
        boolean config=index instanceof PhysicalCatalogConfiguration;
        byte[] bytes=config?new ModelCodec<>(PhysicalCatalogConfiguration.class).encode((PhysicalCatalogConfiguration)index)
            :new ModelCodec<>(PhysicalSitePlanIndex.class).encode((PhysicalSitePlanIndex)index);
        return new DirectorFact(id(index),TYPE,List.of(new ScopeRef(ScopeKind.WORLD,world(index).value())),
            Optional.empty(),EvidenceStatus.CONFIRMED,Map.of("kind",new JsonValue.Text(config?"CONFIGURATION":"SITE_PLAN"),
            "value",CanonicalJsonV1.decode(bytes)),true,Optional.empty());
    }
    public static PhysicalMetadataIndex index(DirectorFact fact){
        if(!fact.factType().equals(TYPE)||!fact.durable()||fact.eventId().isPresent()
                ||fact.evidenceStatus()!=EvidenceStatus.CONFIRMED||!fact.payload().keySet().equals(Set.of("kind","value")))
            throw new IllegalArgumentException("physical metadata fact fields");
        var kind=((JsonValue.Text)fact.payload().get("kind")).value();
        var bytes=CanonicalJsonV1.encode(fact.payload().get("value"));
        PhysicalMetadataIndex value=switch(kind){
            case "CONFIGURATION"->new ModelCodec<>(PhysicalCatalogConfiguration.class).decode(bytes);
            case "SITE_PLAN"->new ModelCodec<>(PhysicalSitePlanIndex.class).decode(bytes);
            default->throw new IllegalArgumentException("physical metadata kind");
        };
        if(!fact.factId().equals(id(value))||!fact.subjectRefs().equals(List.of(new ScopeRef(ScopeKind.WORLD,world(value).value()))))
            throw new IllegalArgumentException("physical metadata fact identity");
        return value;
    }
}
