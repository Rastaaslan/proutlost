package fr.proutlost.director.api.model;
import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;
/** Immutable eligibility evidence captured by the actor. Pure evaluate requires no store/cache lookup. */
public record DecisionState(DirectorWorldState world, BudgetView budgets, OwnershipView ownership, List<CooldownRecord> cooldowns, List<UniqueUseRecord> uniqueness, List<DirectorFact> facts, List<EventInstance> events, List<ChainInstance> chains, List<DisasterInstance> disasters) {
 public DecisionState { Objects.requireNonNull(world); Objects.requireNonNull(budgets); Objects.requireNonNull(ownership); cooldowns=list(cooldowns); uniqueness=list(uniqueness); facts=list(facts); events=list(events); chains=list(chains); disasters=list(disasters); }
}
