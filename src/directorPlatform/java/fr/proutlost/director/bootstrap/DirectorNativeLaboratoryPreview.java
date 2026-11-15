package fr.proutlost.director.bootstrap;

import fr.proutlost.director.api.error.ReasonCode;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.engine.WorldDirector;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Native J07 score-only preview. The only selectable definitions are vetted
 * TEST_ONLY J02 candidate resources. Inputs are immutable J01/J04 observations,
 * never network-supplied player facts. It exposes no writer, J05 admission,
 * permit, effect port, world API, or catalog PRODUCTION provider.
 *
 * A result is a what-if model calculation, never an authorized event.
 */
public final class DirectorNativeLaboratoryPreview {
    public static final ResourceId SAFE_MICRO =
        new ResourceId("proutlost_director:candidate/safe_micro");
    public static final ResourceId CAMP_REGROWTH =
        new ResourceId("proutlost_director:candidate/camp_regrowth");
    private static final int MAX_PLAYERS = 64;
    private static final int MAX_CANDIDATES = 32;
    private final ConfigView config;
    private final DirectorNativeRegionalPlanningBridge regions;
    private final Map<ResourceId,CatalogSnapshot> catalogues;
    private final WorldDirector engine = new WorldDirector(ProviderMode.TEST_ONLY);

    public DirectorNativeLaboratoryPreview(ConfigView profile) {
        config=Objects.requireNonNull(profile);
        if(config.calibration()!=Calibration.DEFAULT_FOR_TESTING)
            throw new IllegalArgumentException("unapproved J07 preview profile");
        regions=new DirectorNativeRegionalPlanningBridge(config);
        catalogues=Map.of(
            SAFE_MICRO,new DirectorLabSafeMicroCatalog(config).snapshot(),
            CAMP_REGROWTH,new DirectorLabCampRegrowthCatalog(config).snapshot());
    }

    public Set<ResourceId> supportedFixtures(){return catalogues.keySet();}

    /** Caller must already have checked operator level 4, live network actor
     * identity, J04 StoreSession, boot epoch and projection completeness.
     */
    public Optional<AdminRow> calculate(ResourceId fixture,
            DirectorWorldState realWorld,SnapshotBatch observed,
            MemoryView memory,long durableSequence) {
        Objects.requireNonNull(fixture);
        Objects.requireNonNull(realWorld);
        Objects.requireNonNull(observed);
        Objects.requireNonNull(memory);
        var candidate=catalogues.get(fixture);
        if(candidate==null)return Optional.empty();
        if(realWorld.runControl()!=RunControl.PAUSED ||
           realWorld.healthMode()!=HealthMode.READ_ONLY_SAFE ||
           !realWorld.configHash().equals(config.provenanceHash()) ||
           !realWorld.worldId().equals(observed.scopes().worldId()) ||
           !realWorld.worldId().equals(memory.scopes().worldId()) ||
           observed.context().players().size()>MAX_PLAYERS ||
           observed.capturedTick().value()>realWorld.simTick().value()+2400)
            return Optional.empty();
        var projected=regions.project(observed,memory,durableSequence);
        if(projected.deferred())return Optional.empty();
        var snapshot=projected.snapshot();
        // This WORLD DTO is a *discarded what-if input*, never a J04 CAS.
        // Its catalog identity binds the chosen candidate definition, but
        // its run control stays PAUSED and its health remains READ_ONLY_SAFE.
        var ephemeral=new DirectorWorldState(realWorld.header(),
            realWorld.worldId(),realWorld.storeGeneration(),
            new Tick(Math.max(realWorld.simTick().value(),
                              snapshot.capturedTick().value())),
            RunControl.PAUSED,HealthMode.READ_ONLY_SAFE,
            realWorld.pacingState(),realWorld.tension(),
            realWorld.intensityProfileId(),candidate.hash(),
            config.provenanceHash(),realWorld.bootEpoch(),
            realWorld.lastCheckpointSeq(),realWorld.engineControl());
        var zero=new BudgetView(BudgetMath.zero(),BudgetMath.zero(),Map.of());
        var state=new DecisionState(ephemeral,zero,new OwnershipView(Map.of()),
            List.of(),List.of(),List.of(),List.of(),List.of(),List.of());
        long seed=UUID.nameUUIDFromBytes((
            realWorld.worldId().value()+"/j07-score-only-v1/"+
            realWorld.header().revision().value()+"/"+fixture.value()+"/"+
            snapshot.capturedTick().value()+"/"+durableSequence)
            .getBytes(StandardCharsets.UTF_8)).getMostSignificantBits();
        var input=new DecisionInput(snapshot,memory,candidate,config,
            List.of(),List.of(),seed,state,Optional.empty());
        var scored=engine.evaluate(input);
        if(scored.selectedPlan().isPresent() ||
           scored.candidates().size()>MAX_CANDIDATES ||
           scored.stateChanges().stream().anyMatch(c->!
               (c.next() instanceof WorldAggregate)) ||
           !engine.replay(input,scored.audit()).equals(scored))
            throw new IllegalStateException("unqualified score-only preview");
        return Optional.of(new AdminRow(
            new ScopeRef(ScopeKind.WORLD,realWorld.worldId().value()),
            realWorld.header().revision(),snapshot.capturedTick(),
            new LaboratoryAdminData(fixture,scored,
                List.of(ReasonCode.CAPABILITY_MISSING))));
    }
}
