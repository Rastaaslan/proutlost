package fr.proutlost.quest.choice;

import com.google.gson.Gson;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Discussion, frozen electorate, absolute majority, limited runoff, then auditable tie draw. */
public final class CollectiveVote {
    public enum Phase { DISCUSSION, FIRST, RUNOFF, RECONSULT, DECIDED }
    public record Rules(long discussionMillis, long ballotMillis, double minimumParticipation) {
        public Rules {
            if (discussionMillis < 0 || ballotMillis < 1 || !Double.isFinite(minimumParticipation)
                || minimumParticipation <= 0 || minimumParticipation > 1) throw new IllegalArgumentException();
        }
    }
    public record Dossier(List<String> options, String knownConsequences, String establishedFacts, String warning) {
        public Dossier { options = List.copyOf(options); if (new HashSet<>(options).size() < 2 || options.size() != new HashSet<>(options).size()
            || knownConsequences.isBlank() || establishedFacts.isBlank() || warning.isBlank()) throw new IllegalArgumentException(); }
    }
    private final String id;
    private final Rules rules;
    private final Dossier dossier;
    private final Set<UUID> consulted = new HashSet<>();
    private Set<UUID> eligible = new HashSet<>();
    private Map<UUID,String> ballots = new HashMap<>();
    private List<String> options;
    private Phase phase = Phase.DISCUSSION;
    private long deadline;
    private final long seed = new SecureRandom().nextLong();
    private String result = "";
    private boolean drawn;
    public CollectiveVote(String id, Rules rules, Dossier dossier, long now) {
        this.id = Objects.requireNonNull(id); this.rules = rules; this.dossier = dossier;
        this.options = dossier.options(); deadline = Math.addExact(now, rules.discussionMillis());
    }
    public Dossier consult(UUID player) { consulted.add(player); return dossier; }
    public void open(Set<UUID> active, long now) {
        if (phase != Phase.DISCUSSION && phase != Phase.RECONSULT) throw new IllegalStateException("Session already open");
        if (now < deadline) throw new IllegalStateException("Discussion still open");
        eligible = new HashSet<>(active); eligible.retainAll(consulted);
        if (eligible.isEmpty()) throw new IllegalStateException("No eligible voters");
        phase = Phase.FIRST; options = dossier.options(); ballots.clear(); deadline = Math.addExact(now, rules.ballotMillis());
    }
    public void confirm(UUID player, String option, long now) {
        if ((phase != Phase.FIRST && phase != Phase.RUNOFF) || now >= deadline) throw new IllegalStateException("No ballot session");
        if (!eligible.contains(player)) throw new SecurityException("Active and dossier consultation required");
        if (!options.contains(option)) throw new IllegalArgumentException("Unknown option");
        if (ballots.putIfAbsent(player, option) != null) throw new IllegalStateException("Already confirmed");
    }
    public Phase close(long now) {
        if ((phase != Phase.FIRST && phase != Phase.RUNOFF) || now < deadline) throw new IllegalStateException("Session not finished");
        if (ballots.size() < Math.ceil(eligible.size()*rules.minimumParticipation())) return reconsult(now);
        var counts = new TreeMap<String,Integer>(); options.forEach(o -> counts.put(o, 0));
        ballots.values().forEach(o -> counts.merge(o, 1, Integer::sum));
        int max = Collections.max(counts.values());
        var tied = counts.entrySet().stream().filter(e -> e.getValue() == max).map(Map.Entry::getKey).toList();
        if (max > eligible.size()/2) { result = tied.getFirst(); phase = Phase.DECIDED; }
        else if (tied.size() > 1 && max > 0) {
            if (phase == Phase.FIRST) { options = tied; ballots.clear(); phase = Phase.RUNOFF; deadline = Math.addExact(now, rules.ballotMillis()); }
            else { result = tied.get(new SplittableRandom(seed).nextInt(tied.size())); drawn = true; phase = Phase.DECIDED; }
        } else return reconsult(now);
        return phase;
    }
    private Phase reconsult(long now) { phase = Phase.RECONSULT; deadline = Math.addExact(now, rules.discussionMillis()); ballots.clear(); return phase; }
    public String commitment() {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((id+":"+seed).getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    public String audit() { return phase == Phase.DECIDED ? "id="+id+"; seed="+seed+"; options="+options+"; drawn="+drawn+"; result="+result : "commitment="+commitment(); }
    public Phase phase() { return phase; }
    public String result() { return result; }
    public boolean hasConsulted(UUID player) { return consulted.contains(player); }
    public Set<UUID> confirmedVoters() { return Set.copyOf(ballots.keySet()); }
    public Set<UUID> eligible() { return Set.copyOf(eligible); }
    public String save() { return new Gson().toJson(this); }
    public static CollectiveVote restore(String json) {
        var vote = new Gson().fromJson(json, CollectiveVote.class);
        if (vote == null || vote.rules == null || vote.dossier == null || vote.phase == null) throw new IllegalArgumentException("Invalid vote");
        return vote;
    }
}
