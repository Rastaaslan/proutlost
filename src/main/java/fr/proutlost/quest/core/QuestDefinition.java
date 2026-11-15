package fr.proutlost.quest.core;

import java.util.*;

public record QuestDefinition(String questId, String authorTitle, String playerTitle,
        Set<String> prerequisites, Set<String> availabilityFacts, String visibilityScope, Set<String> observations,
        List<Set<String>> resolutionConditions, String context, String action,
        String resolvedSummary, Set<String> unlocks, String recoveryPolicy,
        boolean majorConvergence) {
    public QuestDefinition {
        Objects.requireNonNull(questId); Objects.requireNonNull(authorTitle);
        Objects.requireNonNull(playerTitle); Objects.requireNonNull(context);
        Objects.requireNonNull(action); Objects.requireNonNull(resolvedSummary);
        Objects.requireNonNull(recoveryPolicy);
        prerequisites = Set.copyOf(prerequisites); availabilityFacts = Set.copyOf(availabilityFacts); observations = Set.copyOf(observations);
        resolutionConditions = resolutionConditions.stream().map(Set::copyOf).toList();
        unlocks = Set.copyOf(unlocks);
        if (!Set.of("SERVER", "PERSONAL", "GROUP").contains(visibilityScope) || action.isBlank() || resolutionConditions.isEmpty()
                || resolutionConditions.stream().anyMatch(Set::isEmpty))
            throw new IllegalArgumentException("Unsupported scope or empty quest conditions: " + questId);
    }
}
