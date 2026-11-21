package fr.proutlost.director.bootstrap;

import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.port.PhysicalSiteResolverPort;
import fr.proutlost.director.api.model.PhysicalCatalogModels.Key;
import fr.proutlost.director.bootstrap.PhysicalCatalogJournalAdmission;
import java.util.*;
import java.util.function.Supplier;

/** Internal installed integration seam. Implementations must publish actual J04 readback. */
public interface PhysicalCatalogIntegrationProvider {
    record Context(Object platform,WorldId world,Map<DimensionId,ImportBounds> dimensions,
            Supplier<StoreSession> session,Supplier<OwnershipView> ownership,Runnable actor,
            Optional<Object> installedRecoveryAuthority,Supplier<Map<DefinitionId,DefinitionRef>> definitions,Supplier<List<PhysicalSitePin>> recordedSites){
        public Context {
            Objects.requireNonNull(platform);Objects.requireNonNull(world);dimensions=Map.copyOf(dimensions);
            Objects.requireNonNull(session);Objects.requireNonNull(ownership);Objects.requireNonNull(actor);
            Objects.requireNonNull(installedRecoveryAuthority);Objects.requireNonNull(definitions);Objects.requireNonNull(recordedSites);
        }
    }
    record Integration(PhysicalCatalogJournalAdmission.Compiler compiler,PhysicalSiteResolverPort resolver){
        public Integration{Objects.requireNonNull(compiler);Objects.requireNonNull(resolver);}
    }
    PhysicalCatalogSourceDescriptor describe(byte[] exactCanonicalSource);
    Integration create(Context ownerContext,Key source,byte[] exactAuthoredAdapters);
}
