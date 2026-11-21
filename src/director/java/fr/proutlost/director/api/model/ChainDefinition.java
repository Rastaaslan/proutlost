package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record ChainDefinition(int schemaVersion, DefinitionId id, Revision contentRevision, Calibration calibration, ChainMode mode, ScopeKind scope, String entryState, Set<String> states, int planningHorizon, DurationTicks minimumStepGapTicks, UnitValue relevanceMinimum, List<ChainTransition> transitions, Set<String> terminalStates, RecoveryMode recovery) {
 public ChainDefinition { nonnegative(schemaVersion); Objects.requireNonNull(id); Objects.requireNonNull(contentRevision); require(contentRevision.value()>=1,"chain revision"); Objects.requireNonNull(calibration); Objects.requireNonNull(mode); require(scope==ScopeKind.REGION || scope==ScopeKind.WORLD,"chain scope"); text(entryState); states=set(states); states.forEach(Checks::text); nonnegative(planningHorizon); Objects.requireNonNull(minimumStepGapTicks); Objects.requireNonNull(relevanceMinimum); transitions=list(transitions); require(!transitions.isEmpty(),"chain transitions"); terminalStates=set(terminalStates); terminalStates.forEach(Checks::text); require(!terminalStates.isEmpty(),"chain terminals"); require(recovery==RecoveryMode.RECOVER || recovery==RecoveryMode.ABORT_SAFE,"chain recovery"); require(schemaVersion==1 && states.contains(entryState) && states.containsAll(terminalStates),"chain"); }
}
