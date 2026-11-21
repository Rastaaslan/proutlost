package fr.proutlost.director.bootstrap;

import fr.proutlost.director.adapter.neoforge.server.effects.GoatVisitBinding;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.catalog.*;
import java.util.*;

/** The actual bundled goat_guest J02 definition, but manual LAB only. */
public final class DirectorLabGoatCatalog implements DirectorBootstrap.TestOnlyBackend {
    private final CatalogSnapshot snapshot;
    public DirectorLabGoatCatalog(ConfigView config){
        if(config.calibration()!=Calibration.DEFAULT_FOR_TESTING)
            throw new IllegalArgumentException("goat season calibration not approved");
        var bundled=new DirectorNativeCandidateCatalog(config);
        var source=bundled.candidateDocuments().stream().filter(d->d.path().equals(
            "data/proutlost_director/director/events/candidate/goat_guest.json"))
            .findFirst().orElseThrow();
        var primitive=GoatVisitBinding.VISIT;
        var capability=GoatVisitBinding.GOAT;
        var codec=new ResourceId("proutlost_director:goat_slot_v1");
        var resolver=new ResourceId("proutlost:goat_slot");
        var descriptor=new PrimitiveDescriptor(primitive,1,
            new ResourceId("proutlost:codec_goat_visit"),Set.of(capability),
            BudgetMath.zero(),resolver,Set.of(codec),Set.of(OwnershipDomain.ENTITY),
            Set.of(),new DurationTicks(6000),PausePolicy.CLEANUP,
            RecoveryMode.RECOVER,new ResourceId("proutlost:cleanup"),
            Optional.empty(),Optional.empty(),ProviderMode.TEST_ONLY);
        var known=new EnumMap<ContentReferences.Kind,Set<ResourceId>>(ContentReferences.Kind.class);
        known.put(ContentReferences.Kind.FACT,Set.of(new ResourceId("proutlost:goat_guest_observed")));
        known.put(ContentReferences.Kind.TAG,Set.of(new ResourceId("proutlost:camp")));
        known.put(ContentReferences.Kind.CODEC,Set.of(codec,new ResourceId("proutlost:codec_goat_visit")));
        known.put(ContentReferences.Kind.RESOLVER,Set.of(resolver));
        known.put(ContentReferences.Kind.CLEANUP,Set.of(new ResourceId("proutlost:cleanup")));
        var references=new ContentReferences(ProviderMode.TEST_ONLY,known,
            Map.of(),Map.of(),Map.of(),Set.of(capability),Set.of("ENTITY:GOAT_SLOT"));
        var loader=new DefinitionLoader(config,
            new PrimitiveRegistry(List.of(descriptor),ProviderMode.TEST_ONLY),
            references,ProviderMode.TEST_ONLY,
            new DefinitionLoader.Limits(32,65536,4*1024*1024,128,128));
        var result=loader.reload(List.of(source));
        if(!result.installed()||!result.diagnostics().isEmpty()||
            result.snapshot().events().size()!=1)
            throw new IllegalStateException("J02 bundled goat_guest cannot compile "+result.diagnostics());
        snapshot=result.snapshot();
        var definition=snapshot.events().get(new DefinitionId("proutlost_director:candidate/goat_guest"));
        if(definition==null||definition.calibration()!=Calibration.DEFAULT_FOR_TESTING||
            definition.steps().size()!=1||
            !definition.steps().getFirst().primitive().equals(primitive)||
            !(definition.steps().getFirst().args() instanceof GoatVisit))
            throw new IllegalStateException("J02 goat_guest invalid");
    }
    public CatalogSnapshot snapshot(){return snapshot;}
}
