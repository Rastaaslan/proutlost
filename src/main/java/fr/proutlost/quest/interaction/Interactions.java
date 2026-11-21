package fr.proutlost.quest.interaction;

import java.util.*;
import java.util.function.Supplier;

/** Validation primitives: adapters supply freshly measured server-side values, never client claims. */
public final class Interactions {
    private Interactions() {}
    public enum Response { COMPLETE_RESPONSE, PARTIAL_RESPONSE }
    public record CampSignals(long activeTicks, int visits, boolean sleeping, boolean light, boolean supplies) {}
    public record CampRules(long minimumActiveTicks, int minimumVisits) {
        public CampRules { if (minimumActiveTicks < 1 || minimumVisits < 1) throw new IllegalArgumentException(); }
    }
    public record Camp(String location, UUID claimant, long timestamp, CampSignals signals) {}
    public static boolean campCandidate(CampSignals s, CampRules r) {
        return s.activeTicks() >= r.minimumActiveTicks() && s.visits() >= r.minimumVisits() && s.sleeping() && s.light() && s.supplies();
    }
    public static Optional<Camp> claimCamp(String location, UUID player, long now, CampRules rules, Supplier<CampSignals> rescan) {
        var fresh = rescan.get();
        return campCandidate(fresh, rules) ? Optional.of(new Camp(location, player, now, fresh)) : Optional.empty();
    }
    public record Signal(boolean preparedLocation, boolean openSky, int area, double contrast,
                         boolean coastal, boolean highPoint) {}
    public static boolean distressSignal(Supplier<Signal> rescan, int minimumArea, double minimumContrast) {
        if (minimumArea < 1 || minimumContrast <= 0 || minimumContrast > 1) throw new IllegalArgumentException();
        var s = rescan.get(); return s.preparedLocation() && s.openSky() && s.area() >= minimumArea
            && s.contrast() >= minimumContrast && (s.coastal() || s.highPoint());
    }
    public record Slot(int index, String itemId) {}
    public record Arrangement(String location, List<Slot> slots, Set<UUID> authors, long timestamp) {
        public Arrangement {
            slots = slots.stream().sorted(Comparator.comparingInt(Slot::index)).toList(); authors = Set.copyOf(authors);
            if (slots.size() < 3 || slots.size() > 5 || authors.isEmpty() || timestamp < 0 || location.isBlank()
                || slots.stream().map(Slot::index).distinct().count() != slots.size()
                || slots.stream().anyMatch(s -> s.index() < 0 || s.itemId().isBlank())) throw new IllegalArgumentException();
        }
    }
    public static Arrangement recordArrangement(String location, List<Slot> slots, Set<UUID> authors, long now, Set<String> admissibleItems) {
        if (slots.stream().anyMatch(s -> !admissibleItems.contains(s.itemId()))) throw new IllegalArgumentException("Unsupported ordinary item");
        return new Arrangement(location, slots, authors, now);
    }
    public static Response sequenceInput(List<String> expected, List<String> attempt, boolean complementaryEvidenceShared) {
        if (expected.isEmpty()) throw new IllegalArgumentException();
        return complementaryEvidenceShared && expected.equals(attempt) ? Response.COMPLETE_RESPONSE : Response.PARTIAL_RESPONSE;
    }
    public record Comparison(UUID author, String firstObservation, String secondObservation, String location, boolean tested) {}
    public static Comparison prepareCompare(UUID author, String first, String second, String requiredLocation) {
        if (first.equals(second)) throw new IllegalArgumentException("Two distinct observations required");
        return new Comparison(author, first, second, requiredLocation, false);
    }
    public static Comparison testCompare(Comparison prepared, String actualLocation, boolean exactCorrespondence) {
        return new Comparison(prepared.author(), prepared.firstObservation(), prepared.secondObservation(), prepared.location(),
            prepared.location().equals(actualLocation) && exactCorrespondence);
    }
}
