package fr.proutlost.director.api.model;

import fr.proutlost.director.api.codec.*;
import fr.proutlost.director.api.model.PhysicalCatalogModels.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Reserved technical D0 index. Both source AND explicit UUID binding are forced J04 blobs. */
public final class PhysicalCatalogFacts {
    private PhysicalCatalogFacts() {}
    public static final FactType TYPE=new FactType("proutlost_director:physical_catalog_admitted");
    public static final ResourceId SOURCE_CODEC=new ResourceId("proutlost_director:physical_catalog_d0");
    public static final ResourceId BINDING_CODEC=new ResourceId("proutlost_director:physical_catalog_binding");
    public record Manifest(int schemaVersion,Key key,BlobRef source,BlobRef binding,Fingerprint projectionDigest,
            long sourceOrdinal,Optional<FactId> previousImport) {
        public Manifest {
            Objects.requireNonNull(key);Objects.requireNonNull(source);Objects.requireNonNull(binding);Objects.requireNonNull(projectionDigest);Objects.requireNonNull(previousImport);
            Checks.require(sourceOrdinal>=1&&(sourceOrdinal==1)==previousImport.isEmpty(),"physical source history link");
            Checks.require(schemaVersion==1&&source.codecId().equals(SOURCE_CODEC)&&source.codecVersion()==1
                &&binding.codecId().equals(BINDING_CODEC)&&binding.codecVersion()==1
                &&source.hash().equals(key.canonicalSourceSha())&&source.bytes()>0&&source.bytes()<=1_048_576,
                "physical manifest schema/reference");
        }
    }
    public static Fingerprint bindingDigest(Binding binding) {
        var fields=new TreeMap<>(((JsonValue.ObjectValue)CanonicalJsonV1.decode(new ModelCodec<>(Binding.class).encode(binding))).values());
        fields.remove("binding_sha");return BlobContent.hash(CanonicalJsonV1.encode(new JsonValue.ObjectValue(fields)));
    }
    public static Fingerprint projectionDigest(Key key,BlobRef source,BlobRef binding) {
        // Length-safe canonical DTO binding; does not reinterpret D0 revision or identifiers.
        return BlobContent.hash(CanonicalJsonV1.encode(new JsonValue.ObjectValue(Map.of(
            "key",CanonicalJsonV1.decode(new ModelCodec<>(Key.class).encode(key)),
            "source",CanonicalJsonV1.decode(new ModelCodec<>(BlobRef.class).encode(source)),
            "binding",CanonicalJsonV1.decode(new ModelCodec<>(BlobRef.class).encode(binding))))));
    }
    public static FactId factId(Manifest manifest) {
        // Stable technical record identity, NEVER a Director region/location/anchor binding.
        return new FactId(UUID.nameUUIDFromBytes(("physical-catalog-fact-v1/"+
            BlobContent.hash(new ModelCodec<>(Key.class).encode(manifest.key())).value()).getBytes(StandardCharsets.UTF_8)));
    }
    public static DirectorFact fact(Manifest manifest) {
        return new DirectorFact(factId(manifest),TYPE,List.of(new ScopeRef(ScopeKind.WORLD,manifest.key().directorWorld().value())),
            Optional.empty(),EvidenceStatus.CONFIRMED,Map.of("manifest",CanonicalJsonV1.decode(new ModelCodec<>(Manifest.class).encode(manifest))),
            true,Optional.empty());
    }
    public static Manifest manifest(DirectorFact fact) {
        Checks.require(fact.factType().equals(TYPE)&&fact.durable()&&fact.eventId().isEmpty()
            &&fact.evidenceStatus()==EvidenceStatus.CONFIRMED&&fact.payload().keySet().equals(Set.of("manifest")),"physical index fields");
        var value=new ModelCodec<>(Manifest.class).decode(CanonicalJsonV1.encode(fact.payload().get("manifest")));
        Checks.require(fact.factId().equals(factId(value))&&fact.subjectRefs().equals(List.of(
            new ScopeRef(ScopeKind.WORLD,value.key().directorWorld().value()))),"physical index identity");
        Checks.require(value.projectionDigest().equals(projectionDigest(value.key(),value.source(),value.binding())),"physical projection digest");
        return value;
    }
    public static Set<BlobRef> references(DirectorFact fact) {
        var m=manifest(fact);return Set.of(m.source(),m.binding());
    }
}
