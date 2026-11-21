package fr.proutlost.director.bootstrap;

import fr.proutlost.director.catalog.*;
import fr.proutlost.director.api.model.*;
import java.util.*;

/**
 * Real bundled camp_regrowth definition, compiled against explicitly TEST_ONLY
 * J05 mutation contracts. Produces a typed catalog snapshot, never production
 * capability, and cannot authorize physical writes without J05 WAL/permit.
 */
public final class DirectorLabCampRegrowthCatalog implements DirectorBootstrap.TestOnlyBackend {
    private final CatalogSnapshot snapshot;
    public DirectorLabCampRegrowthCatalog(ConfigView config) {
        if(config.calibration()!=Calibration.DEFAULT_FOR_TESTING)
            throw new IllegalArgumentException("no approved camp calibration");
        var bundled=new DirectorNativeCandidateCatalog(config);
        var source=bundled.candidateDocuments().stream().filter(s->s.path().equals(
            "data/proutlost_director/director/events/candidate/camp_regrowth.json"))
            .findFirst().orElseThrow();
        var cap=new CapabilityId("mutation.plants_v1");
        var primitive=new PrimitiveId("proutlost:camp_growth");
        var codec=new ResourceId("proutlost:block_codec");
        var resolver=new ResourceId("proutlost:footprint");
        var recipeId=new ResourceId("proutlost:test_camp_plants");
        var descriptor=new PrimitiveDescriptor(primitive,1,
            new ResourceId("proutlost:codec_camp_growth"),Set.of(cap),
            BudgetMath.zero(),resolver,Set.of(codec),
            Set.of(OwnershipDomain.BLOCK),Set.of(),
            new DurationTicks(6000),PausePolicy.CLEANUP,RecoveryMode.RECOVER,
            new ResourceId("proutlost:cleanup"),Optional.empty(),Optional.empty(),
            ProviderMode.TEST_ONLY);
        var budget=new BudgetVector(100,100,100,0,0,1024,0,0,0,0);
        var mutation=new MutationProfile(recipeId,1,Set.of(primitive),
            Set.of(codec),resolver,new ReadSet(Map.of(),Map.of(),Map.of()),
            new ResourceId("proutlost:mask"),budget,1,List.of(),
            PlayerEditPolicy.YIELD,Optional.empty(),primitive,Set.of(),
            RecoveryMode.RECOVER,Set.of("TEST_ONLY:T-MUT"));
        var known=new EnumMap<ContentReferences.Kind,Set<ResourceId>>(
            ContentReferences.Kind.class);
        known.put(ContentReferences.Kind.FACT,
            Set.of(new ResourceId("proutlost:camp_regrowth_observed")));
        known.put(ContentReferences.Kind.TAG,
            Set.of(new ResourceId("proutlost:camp_abandoned"),
                   new ResourceId("proutlost:abandoned_camp"),
                   new ResourceId("proutlost:regrown_camp")));
        known.put(ContentReferences.Kind.CODEC,Set.of(codec,
            new ResourceId("proutlost:codec_camp_growth")));
        known.put(ContentReferences.Kind.RESOLVER,Set.of(resolver,
            new ResourceId("proutlost:mask")));
        known.put(ContentReferences.Kind.CLEANUP,Set.of(
            new ResourceId("proutlost:cleanup")));
        var refs=new ContentReferences(ProviderMode.TEST_ONLY,known,
            Map.of(recipeId,mutation),Map.of(),Map.of(),Set.of(cap),
            Set.of("BLOCKS:FOOTPRINT"));
        var loader=new DefinitionLoader(config,
            new PrimitiveRegistry(List.of(descriptor),ProviderMode.TEST_ONLY),
            refs,ProviderMode.TEST_ONLY,
            new DefinitionLoader.Limits(32,65536,4*1024*1024,128,128));
        var result=loader.reload(List.of(source));
        if(!result.installed() || !result.diagnostics().isEmpty() ||
           result.snapshot().events().size()!=1)
            throw new IllegalStateException(
                "J02 actual camp_regrowth cannot compile "+result.diagnostics());
        snapshot=result.snapshot();
        var value=snapshot.events().get(new DefinitionId(
            "proutlost_director:candidate/camp_regrowth"));
        if(value==null||value.calibration()!=Calibration.DEFAULT_FOR_TESTING||
           value.steps().size()!=1 ||
           !(value.steps().getFirst().args() instanceof CampGrowth))
            throw new IllegalStateException("invalid J02 camp_regrowth result");
    }
    public CatalogSnapshot snapshot(){return snapshot;}
}
