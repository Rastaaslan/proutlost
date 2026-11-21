package fr.proutlost.director.admin.server;

import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.port.DecisionPort;
import java.util.*;
import java.util.function.Function;
import java.util.function.BiFunction;

/** Run on a bounded CPU worker with immutable inputs. This component has no admission,
 * store writer or world port. Proposed state changes are deliberately returned as data. */
public final class AdminReadOnlyCalculations {
    private final DecisionPort decisions;
    private final Function<ResourceId,DecisionInput> fixtures;
    private final Function<DecisionId,DecisionInput> recordedInputs;
    private final BiFunction<DecisionId,Boolean,DecisionProposal> recordedReplay;
    public AdminReadOnlyCalculations(DecisionPort decisions,Function<ResourceId,DecisionInput> fixtures,
            Function<DecisionId,DecisionInput> recordedInputs) {
        this.decisions=Objects.requireNonNull(decisions);this.fixtures=Objects.requireNonNull(fixtures);this.recordedInputs=Objects.requireNonNull(recordedInputs);
        this.recordedReplay=null;
    }
    /** Production archive resolver retains the exact captured pure planner as well as input. */
    public AdminReadOnlyCalculations(DecisionPort decisions,Function<ResourceId,DecisionInput> fixtures,
            BiFunction<DecisionId,Boolean,DecisionProposal> recordedReplay) {
        this.decisions=Objects.requireNonNull(decisions);this.fixtures=Objects.requireNonNull(fixtures);
        this.recordedInputs=null;this.recordedReplay=Objects.requireNonNull(recordedReplay);
    }
    public DecisionProposal simulate(ResourceId fixture) {
        return decisions.evaluate(Objects.requireNonNull(fixtures.apply(fixture),"Unknown registered fixture"));
    }
    public DecisionProposal simulate(ResourceId fixture,SnapshotBatch injected) {
        var original=Objects.requireNonNull(fixtures.apply(fixture),"Unknown registered fixture");
        if(!original.context().scopes().equals(injected.scopes())||!original.context().scopeRevisions().equals(injected.scopeRevisions())) throw new IllegalArgumentException("fixture scopes/revisions");
        return decisions.evaluate(new DecisionInput(injected,original.memory(),original.catalog(),original.config(),
                original.requests(),original.chains(),original.seed(),original.state(),original.advisorySnapshot()));
    }
    public DecisionProposal replay(DecisionId decision,boolean withoutAi) {
        if(recordedReplay!=null)return Objects.requireNonNull(recordedReplay.apply(decision,withoutAi));
        var original=Objects.requireNonNull(recordedInputs.apply(decision),"Missing exact recorded input");
        var input=withoutAi?new DecisionInput(original.context(),original.memory(),original.catalog(),original.config(),
                original.requests(),original.chains(),original.seed(),original.state(),Optional.empty()):original;
        return decisions.evaluate(input);
    }
}
