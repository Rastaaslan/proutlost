package fr.proutlost.director.engine;

import fr.proutlost.director.api.model.*;
import java.util.*;

/** Weighted top-K and weighted exploration; rejected candidates cannot enter either pool. */
public final class WeightedSelector {
    public record Selection(Optional<CandidateId> chosen, List<CandidateId> pool, boolean exploration) {
        public Selection { pool = List.copyOf(pool); }
    }

    private final EngineParameters parameters;
    public WeightedSelector(ConfigView config) { parameters = new EngineParameters(config); }

    public Selection select(List<Candidate> candidates, DecisionRandom random) {
        int k = Math.toIntExact(parameters.ticks("selection.top_k"));
        if (k < 1) throw new IllegalArgumentException("top_k must be positive");
        double relative = parameters.unit("selection.relative_floor");
        double floor = parameters.nonnegative("selection.absolute_floor");
        double temperature = parameters.positive("selection.temperature");
        double explorationProbability = parameters.unit("selection.exploration_probability");
        Set<CandidateId> ids = new HashSet<>();
        for (Candidate candidate : candidates) {
            if (!ids.add(candidate.id())) throw new IllegalArgumentException("Duplicate candidate identity");
            if (candidate.eligible() && candidate.score() <= 0)
                throw new IllegalArgumentException("Eligible score must be positive");
        }
        List<Candidate> sorted = candidates.stream().filter(Candidate::eligible)
            .sorted(Comparator.comparingDouble(Candidate::score).reversed()
                .thenComparing(c -> c.id().value().toString())).toList();
        if (sorted.isEmpty()) return new Selection(Optional.empty(), List.of(), false);
        double threshold = sorted.getFirst().score() * relative;
        List<Candidate> top = sorted.stream().limit(k).filter(c -> c.score() >= threshold).toList();
        Set<CandidateId> topIds = new HashSet<>();
        top.forEach(c -> topIds.add(c.id()));
        List<Candidate> rest = sorted.stream().filter(c -> !topIds.contains(c.id()) && c.score() >= floor).toList();
        boolean explore = random.next() < explorationProbability && !rest.isEmpty();
        List<Candidate> pool = explore ? rest : top;
        // Subtract the maximum log-weight BEFORE division to avoid overflow for small temperatures.
        double maxLog = StrictMath.log(pool.getFirst().score());
        double[] weights = new double[pool.size()];
        double total = 0;
        for (int i = 0; i < weights.length; i++) {
            weights[i] = StrictMath.exp((StrictMath.log(pool.get(i).score()) - maxLog) / temperature);
            total += weights[i];
        }
        double target = random.next() * total;
        double cumulative = 0;
        CandidateId selected = pool.getFirst().id();
        for (int i = 0; i < weights.length; i++) {
            if (weights[i] > 0) selected = pool.get(i).id();
            cumulative += weights[i];
            if (target < cumulative) break;
        }
        return new Selection(Optional.of(selected), pool.stream().map(Candidate::id).toList(), explore);
    }
}
