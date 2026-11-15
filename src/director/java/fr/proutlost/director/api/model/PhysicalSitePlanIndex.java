package fr.proutlost.director.api.model;

import java.util.*;

/** Historical ancillary plan pin; it never renews permission on recovery. */
public record PhysicalSitePlanIndex(int schemaVersion,PhysicalSitePin pin,BlobRef plan,
        BlobRef binding) implements PhysicalMetadataIndex {
    public PhysicalSitePlanIndex {
        Objects.requireNonNull(pin);Objects.requireNonNull(plan);Objects.requireNonNull(binding);
        if(schemaVersion!=1||!plan.hash().equals(pin.planHash())
                ||!plan.codecId().equals(PhysicalSitePin.PLAN_CODEC)||plan.codecVersion()!=1
                ||plan.bytes()<1||plan.bytes()>1_048_576
                ||!binding.codecId().equals(PhysicalCatalogFacts.BINDING_CODEC)||binding.codecVersion()!=1)
            throw new IllegalArgumentException("exact physical site plan index required");
    }
}
