package fr.proutlost.director.engine;
import fr.proutlost.director.api.model.*;

/** Typed selector and scope matching; unknown applicability never grants admission. */
public final class CooldownPolicy {
    public enum Match { YES, NO, UNKNOWN }
    public Match applies(CooldownRecord record, CandidateGenerator.Generated candidate, DecisionInput input) {
        if (record.binding().isEmpty()) return Match.UNKNOWN;
        var binding = record.binding().orElseThrow();
        if (!binding.affectedScopes().worldId().equals(candidate.scopes().worldId())) return Match.NO;
        if (binding.selector() instanceof EventCooldownSelector event && !event.definitionId().equals(candidate.definition().id())) return Match.NO;
        if (binding.selector() instanceof FamilyCooldownSelector family && !candidate.definition().families().contains(family.family())) return Match.NO;
        if (binding.affectedScopes().scopes().stream().anyMatch(s -> s.kind() == ScopeKind.WORLD || candidate.scopes().scopes().contains(s))) return Match.YES;
        if (candidate.region().filter(binding.inheritedRegions()::contains).isPresent()) return Match.YES;
        return binding.lineageComplete() ? Match.NO : Match.UNKNOWN;
    }
    public boolean blocks(CooldownRecord record, CandidateGenerator.Generated candidate, DecisionInput input) {
        var match = applies(record, candidate, input);
        if (match == Match.NO) return false;
        if (record.notBeforeTick().compareTo(input.state().world().simTick()) > 0) return true;
        if (record.binding().isEmpty()) return record.releaseCondition().isPresent();
        var binding = record.binding().orElseThrow();
        if (binding.releaseMode() == CooldownReleaseMode.AFTER_TICK) return false;
        return match == Match.UNKNOWN || new ConditionEvaluator().evaluate(binding.releasePredicate().orElseThrow(), candidate, input) != ConditionEvaluator.Truth.TRUE;
    }
}
