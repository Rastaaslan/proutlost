package fr.proutlost.director.api.model;
import java.util.*;
/** Real mutation plus current owners at the same WAL cut. No blob payloads or authority.
 * An absent owner and an owner created by a later mutation remain distinguishable. */
public record MutationAdminData(MutationRecord mutation,OwnershipView ownership,
        Set<ResourceKey> inspectedResources) implements AdminData {
    public MutationAdminData {
        Objects.requireNonNull(mutation);Objects.requireNonNull(ownership);
        inspectedResources=Set.copyOf(inspectedResources);
        if(inspectedResources.size()>16 ||
           !inspectedResources.containsAll(ownership.records().keySet()))
            throw new IllegalArgumentException("mutation ownership inspection bound");
        for(var entry:ownership.records().entrySet())
            if(!entry.getKey().equals(entry.getValue().resourceKey()))
                throw new IllegalArgumentException("owner resource identity");
    }
}
