package fr.proutlost.director.api.model;

import java.util.*;

/** Exact source, issued identity crosswalk and semantic-adapter inputs. */
public record PhysicalCatalogConfiguration(int schemaVersion,PhysicalCatalogModels.Key key,
        BlobRef source,BlobRef binding,BlobRef adapters,Fingerprint providerClassSha,
        String receiptRelative) implements PhysicalMetadataIndex {
    public PhysicalCatalogConfiguration {
        Objects.requireNonNull(key);Objects.requireNonNull(source);Objects.requireNonNull(binding);
        Objects.requireNonNull(adapters);Objects.requireNonNull(providerClassSha);
        if(schemaVersion!=1||!source.codecId().equals(PhysicalCatalogFacts.SOURCE_CODEC)
                ||!binding.codecId().equals(PhysicalCatalogFacts.BINDING_CODEC)
                ||!adapters.codecId().equals(PhysicalMetadataFacts.ADAPTERS_CODEC)
                ||source.codecVersion()!=1||binding.codecVersion()!=1||adapters.codecVersion()!=1
                ||source.bytes()<1||source.bytes()>1_048_576||binding.bytes()<1||binding.bytes()>1_048_576
                ||adapters.bytes()<1||adapters.bytes()>1_048_576
                ||!source.hash().equals(key.canonicalSourceSha())
                ||receiptRelative==null||!receiptRelative.matches("[a-z0-9][a-z0-9_./-]{0,190}\\.json")
                ||receiptRelative.contains("..")||receiptRelative.startsWith("/"))
            throw new IllegalArgumentException("bounded exact physical configuration required");
    }
}
