package fr.proutlost.director.engine;

import fr.proutlost.director.api.model.*;
import java.util.*;

/** Pure interpretation of the latest durable administrative override in a decision snapshot. */
public final class AdminTensionOverride {
    public static final FactType TYPE=AdminContracts.TENSION_OVERRIDE_FACT;
    private record Override(GlobalTension tension,long created,long expires,long revision) {}
    private AdminTensionOverride() {}

    private static String text(DirectorFact fact,String key) {
        if(!(fact.payload().get(key) instanceof JsonValue.Text value))
            throw new IllegalArgumentException("invalid administrative tension fact");
        return value.value();
    }
    private static Override latest(DecisionInput input) {
        Override latest=null;
        var world=input.state().world();
        var scope=List.of(new ScopeRef(ScopeKind.WORLD,world.worldId().value()));
        for(var fact:input.state().facts()) {
            if(!fact.factType().equals(TYPE)||!fact.subjectRefs().equals(scope))continue;
            if(!fact.durable()||fact.evidenceStatus()!=EvidenceStatus.CONFIRMED||fact.eventId().isPresent()
                    ||!fact.payload().keySet().equals(Set.of("tension","created_tick","expires_tick","control_revision")))
                throw new IllegalArgumentException("unconfirmed administrative tension fact");
            var candidate=new Override(GlobalTension.valueOf(text(fact,"tension")),
                    Long.parseLong(text(fact,"created_tick")),Long.parseLong(text(fact,"expires_tick")),
                    Long.parseLong(text(fact,"control_revision")));
            if(candidate.created()<0||candidate.expires()<=candidate.created()||candidate.revision()<1
                    ||candidate.revision()>world.header().revision().value()||candidate.created()>world.simTick().value())
                throw new IllegalArgumentException("stale administrative tension snapshot");
            if(latest==null||candidate.revision()>latest.revision())latest=candidate;
            else if(candidate.revision()==latest.revision()&&!candidate.equals(latest))
                throw new IllegalArgumentException("conflicting administrative tension overrides");
        }
        return latest;
    }
    public static Optional<GlobalTension> active(DecisionInput input) {
        var latest=latest(input);
        return latest!=null&&input.state().world().simTick().value()<latest.expires()
                ?Optional.of(latest.tension()):Optional.empty();
    }
    /** Scoring sees the same bounded override as pacing. On expiry, release the
     * forced enum from the measured EMA; an expired forced value must not seed
     * a new hysteresis interval. No measured EMA or pacing deadline is replaced. */
    public static DecisionInput normalize(DecisionInput input) {
        Objects.requireNonNull(input);
        var latest=latest(input);if(latest==null)return input;
        var state=input.state();var world=state.world();
        GlobalTension effective=world.tension();
        if(world.simTick().value()<latest.expires())effective=latest.tension();
        else if(world.header().updatedTick().value()<latest.expires()) {
            var parameters=new EngineParameters(input.config());
            double measured=world.engineControl().tensionEma().value();
            effective=measured>=parameters.unit("pacing.tension_exceptional_enter")?GlobalTension.EXCEPTIONAL:
                    measured>=parameters.unit("pacing.tension_tense_enter")?GlobalTension.TENSE:
                    measured>=parameters.unit("pacing.tension_unusual_enter")?GlobalTension.UNUSUAL:GlobalTension.CALM;
        }
        if(effective==world.tension())return input;
        var normalized=new DirectorWorldState(world.header(),world.worldId(),world.storeGeneration(),world.simTick(),
                world.runControl(),world.healthMode(),world.pacingState(),effective,world.intensityProfileId(),
                world.catalogHash(),world.configHash(),world.bootEpoch(),world.lastCheckpointSeq(),world.engineControl());
        var normalizedState=new DecisionState(normalized,state.budgets(),state.ownership(),state.cooldowns(),
                state.uniqueness(),state.facts(),state.events(),state.chains(),state.disasters());
        return new DecisionInput(input.context(),input.memory(),input.catalog(),input.config(),input.requests(),
                input.chains(),input.seed(),normalizedState,input.advisorySnapshot());
    }
}
