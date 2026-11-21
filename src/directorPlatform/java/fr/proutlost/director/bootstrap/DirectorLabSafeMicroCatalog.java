package fr.proutlost.director.bootstrap;

import fr.proutlost.director.catalog.*;
import fr.proutlost.director.api.model.*;
import java.util.*;

/** Resolve ONE genuine bundled safe_micro candidate through the real J02
 * DefinitionLoader using explicit TEST_ONLY capability/asset fixtures.
 * This qualifies JSON compilation, NOT a real client sound asset nor
 * production-permitted primitive. Its output must never be composed as a
 * ProviderMode.PRODUCTION catalog or exposed for gameplay execution.
 */
public final class DirectorLabSafeMicroCatalog implements DirectorBootstrap.TestOnlyBackend {
    private final CatalogSnapshot catalogue;

    public DirectorLabSafeMicroCatalog(ConfigView profile) {
        if(profile.calibration()!=Calibration.DEFAULT_FOR_TESTING)
            throw new IllegalArgumentException("J02 lab profile required");
        var verified=new DirectorNativeCandidateCatalog(profile);
        var source=verified.candidateDocuments().stream()
            .filter(s->s.path().equals(
                "data/proutlost_director/director/events/candidate/safe_micro.json"))
            .findFirst().orElseThrow();
        var cap=new CapabilityId("perception.sound_v1");
        var primitive=new PrimitiveDescriptor(new PrimitiveId("proutlost:target_sound"),
            1,new ResourceId("proutlost:codec_target_sound"),Set.of(cap),
            new BudgetVector(0,0,0,0,0,0,0,0,0,0),
            new ResourceId("proutlost:footprint"),Set.of(),
            Set.of(OwnershipDomain.PERCEPTION),
            Set.of(NoninvertibleEffect.SOUND_EXPOSURE),
            new DurationTicks(6000),PausePolicy.CLEANUP,RecoveryMode.RECOVER,
            new ResourceId("proutlost:cleanup"),Optional.empty(),Optional.empty(),
            ProviderMode.TEST_ONLY);
        var registry=new PrimitiveRegistry(List.of(primitive),ProviderMode.TEST_ONLY);
        var known=Map.of(
            ContentReferences.Kind.FACT,Set.of(
                new ResourceId("proutlost:safe_micro_observed")),
            ContentReferences.Kind.SOUND,Set.of(
                new ResourceId("minecraft:block.campfire.crackle")),
            ContentReferences.Kind.CODEC,Set.of(
                new ResourceId("proutlost:codec_target_sound")),
            ContentReferences.Kind.RESOLVER,Set.of(
                new ResourceId("proutlost:footprint")),
            ContentReferences.Kind.CLEANUP,Set.of(
                new ResourceId("proutlost:cleanup")));
        var references=new ContentReferences(ProviderMode.TEST_ONLY,known,
            Map.of(),Map.of(),Map.of(),Set.of(cap),
            Set.of("PERCEPTION:WITNESS"));
        var loader=new DefinitionLoader(profile,registry,references,
            ProviderMode.TEST_ONLY,
            new DefinitionLoader.Limits(32,65536,4*1024*1024,128,128));
        var result=loader.reload(List.of(source));
        if(!result.installed() || !result.diagnostics().isEmpty() ||
           result.snapshot().events().size()!=1 ||
           result.snapshot().chains().size()!=0)
            throw new IllegalStateException(
                "bundled J02 safe_micro cannot compile: "+result.diagnostics());
        catalogue=result.snapshot();
        EventDefinition micro=catalogue.events().get(new DefinitionId(
            "proutlost_director:candidate/safe_micro"));
        if(micro==null || micro.calibration()!=Calibration.DEFAULT_FOR_TESTING ||
           !micro.families().contains(Family.SAFE_MICRO_EVENT) ||
           micro.steps().size()!=1 ||
           !(micro.steps().getFirst().args() instanceof TargetSound))
            throw new IllegalStateException("J02 candidate mismatch");
    }
    public CatalogSnapshot snapshot(){return catalogue;}
}
