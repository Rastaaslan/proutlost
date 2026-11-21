package fr.proutlost.quest.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSerializer;
import java.util.*;

/** Canonical server-owned state. Call only on the server thread. No Director or UI dependency. */
public final class QuestCore {
    public enum State { LOCKED, AVAILABLE, IN_PROGRESS, RESOLVED }
    public enum Knowledge { PERSONAL_OBSERVATION, SHARED_EVIDENCE, ESTABLISHED_KNOWLEDGE }
    public record Calibration(int minContributors, double activeRatio, long circulationMillis) {
        public Calibration {
            if (minContributors < 1 || !Double.isFinite(activeRatio) || activeRatio <= 0 || activeRatio > 1 || circulationMillis < 0)
                throw new IllegalArgumentException("Invalid calibration");
        }
    }
    public record Observation(String id, UUID witness, String type, String text, String origin, long timestamp) {}
    public record Evidence(Observation observation, Knowledge level) {}
    public record Page(String id, String title, State state, String context, String action,
                       List<Evidence> evidence, String summary) {}
    public record Established(String quest, String summary, long timestamp) {}
    public record Snapshot(int schema, Map<String,Observation> observations, Map<String,Set<UUID>> shares,
        Map<String,WorldFact> facts, Map<UUID,Long> activity, Map<String,Long> resolved,
        Map<String,Long> readySince, Map<UUID,Long> newsSeen, Set<String> establishedEvidence, Map<UUID,String> groups,
        Map<String,Map<String,Long>> scopedResolved, Map<String,Map<String,Long>> scopedReady,
        Map<String,Set<String>> scopedEvidence, Map<String,Integer> scopedNewsSeen) {}
    public static final long ACTIVE_WINDOW = 7L * 24 * 60 * 60 * 1000;
    private final Map<String,QuestDefinition> definitions = new LinkedHashMap<>();
    private final Calibration calibration;
    private java.util.function.Predicate<String> actionReady=id->true;
    public void actionReadiness(java.util.function.Predicate<String> readiness){actionReady=Objects.requireNonNull(readiness);}
    private final Set<String> establishedEvidence = new HashSet<>();
    private final Map<String,Observation> observations = new LinkedHashMap<>();
    private final Map<String,Set<UUID>> shares = new LinkedHashMap<>();
    private final Map<String,WorldFact> facts = new LinkedHashMap<>();
    private final Map<UUID,Long> activity = new HashMap<>(), newsSeen = new HashMap<>();
    private final Map<String,Long> resolved = new LinkedHashMap<>(), readySince = new HashMap<>();
    private final Map<String,Integer> scopedNewsSeen = new HashMap<>();
    private final Map<UUID,String> groups = new HashMap<>();
    private final Map<String,Map<String,Long>> scopedResolved = new LinkedHashMap<>(), scopedReady = new LinkedHashMap<>();
    private final Map<String,Set<String>> scopedEvidence = new HashMap<>();
    /** Membership is assigned by a trusted server team adapter, never by fact payloads. */
    public void assignGroup(UUID player, String group) {
        if (group == null || group.isBlank()) groups.remove(player); else groups.put(player, group);
    }
    private String scopeKey(QuestDefinition q, UUID player) {
        return switch (q.visibilityScope()) {
            case "SERVER" -> "SERVER";
            case "PERSONAL" -> "PERSONAL/" + player;
            case "GROUP" -> groups.containsKey(player) ? "GROUP/" + groups.get(player) : "";
            default -> throw new IllegalStateException();
        };
    }
    private boolean groupMember(UUID player, String group) { return Objects.equals(groups.get(player),group); }
    private boolean scopedObservation(QuestDefinition q, UUID player, Observation o) {
        if (q.visibilityScope().equals("PERSONAL"))
            return o.witness().equals(player) || shares.getOrDefault(o.id(),Set.of()).contains(player) || establishedEvidence.contains(o.id());
        String group=groups.get(player);
        return establishedEvidence.contains(o.id()) || (groupMember(o.witness(),group)
            && shares.getOrDefault(o.id(),Set.of()).stream().anyMatch(r -> groupMember(r,group)));
    }
    private Set<String> scopedTypes(QuestDefinition q, UUID player) {
        var types=new HashSet<String>();
        for (var f:facts.values()) {
            boolean publicFact=f.playerScope().isEmpty()&&f.groupScope().isEmpty();
            boolean addressed=q.visibilityScope().equals("PERSONAL") ? f.playerScope().contains(player)
                : !f.groupScope().isEmpty()&&f.groupScope().equals(groups.get(player));
            if(publicFact||addressed)types.add(f.factType());
        }
        observations.values().stream().filter(o->scopedObservation(q,player,o)).forEach(o->types.add(o.type()));
        return types;
    }
    public State state(String id, UUID player) {
        var q=Objects.requireNonNull(definitions.get(id));
        if(q.visibilityScope().equals("SERVER"))return state(id);
        String key=scopeKey(q,player);if(key.isEmpty())return State.LOCKED;
        if(scopedResolved.getOrDefault(key,Map.of()).containsKey(id))return State.RESOLVED;
        if(!actionReady.test(id))return State.LOCKED;
        if(q.prerequisites().stream().anyMatch(p->state(p,player)!=State.RESOLVED))return State.LOCKED;
        var types=scopedTypes(q,player);
        if(!types.containsAll(q.availabilityFacts()))return State.LOCKED;
        return q.observations().stream().anyMatch(types::contains)?State.IN_PROGRESS:State.AVAILABLE;
    }
    private Set<UUID> scopedPlayers() {
        var players=new HashSet<>(groups.keySet());players.addAll(activity.keySet());
        observations.values().forEach(o->players.add(o.witness()));shares.values().forEach(players::addAll);
        facts.values().forEach(f->players.addAll(f.playerScope()));
        return players;
    }
    private void evaluateScoped(long now) {
        var players=scopedPlayers();
        boolean changed;
        do {
            changed=false;
            for(var player:players)for(var q:definitions.values()) {
                if(q.visibilityScope().equals("SERVER"))continue;
                State state=state(q.questId(),player);
                if(state==State.LOCKED||state==State.RESOLVED)continue;
                if(q.resolutionConditions().stream().noneMatch(scopedTypes(q,player)::containsAll))continue;
                String key=scopeKey(q,player);
                long since=scopedReady.computeIfAbsent(key,k->new LinkedHashMap<>()).computeIfAbsent(q.questId(),k->now);
                if(q.majorConvergence()&&!scopedGate(q,player,now,since))continue;
                scopedResolved.computeIfAbsent(key,k->new LinkedHashMap<>()).put(q.questId(),now);
                if(q.visibilityScope().equals("GROUP"))observations.values().stream()
                    .filter(o->q.observations().contains(o.type())&&scopedObservation(q,player,o))
                    .forEach(o->scopedEvidence.computeIfAbsent(key,k->new HashSet<>()).add(o.id()));
                changed=true;
            }
        }while(changed);
    }
    private boolean scopedGate(QuestDefinition q,UUID player,long now,long since) {
        var eligible=new HashSet<>(activePlayers(now));
        eligible.removeIf(p->q.visibilityScope().equals("PERSONAL")?!p.equals(player):!groupMember(p,groups.get(player)));
        var contributors=new HashSet<UUID>();
        observations.values().stream().filter(o->q.observations().contains(o.type())&&scopedObservation(q,player,o)).forEach(o->contributors.add(o.witness()));
        for(var f:facts.values())if(q.observations().contains(f.factType())&&f.payload().containsKey("actor")) {
            boolean addressed=q.visibilityScope().equals("PERSONAL")?f.playerScope().contains(player):f.groupScope().equals(groups.get(player));
            if(addressed)try{contributors.add(UUID.fromString(f.payload().get("actor")));}catch(IllegalArgumentException ignored){}
        }
        contributors.retainAll(eligible);
        int minimum=q.visibilityScope().equals("PERSONAL")?1:calibration.minContributors();
        return contributors.size()>=Math.max(minimum,(int)Math.ceil(eligible.size()*calibration.activeRatio()))&&now-since>=calibration.circulationMillis();
    }
    public QuestCore(List<QuestDefinition> defs, Calibration calibration) {
        Campaign.validate(defs); defs.forEach(q -> definitions.put(q.questId(), q)); this.calibration = calibration;
    }
    public void active(UUID player, long now) { if (now < 0) throw new IllegalArgumentException(); activity.merge(player, now, Math::max); }
    public Set<UUID> activePlayers(long now) { return recentPlayers(now,ACTIVE_WINDOW); }
    public Set<UUID> recentPlayers(long now,long window) {
        if(window<0||window>ACTIVE_WINDOW)throw new IllegalArgumentException("Activity window");
        var result = new HashSet<UUID>(); activity.forEach((id,t) -> { if (t <= now && now-t <= window) result.add(id); }); return Set.copyOf(result);
    }
    public void observe(Observation observation) {
        Objects.requireNonNull(observation.witness()); Objects.requireNonNull(observation.type());
        Objects.requireNonNull(observation.text()); Objects.requireNonNull(observation.origin());
        if (observation.id() == null || observation.id().isBlank() || observation.timestamp() < 0) throw new IllegalArgumentException();
        var previous = observations.putIfAbsent(observation.id(), observation);
        if (previous != null && !previous.equals(observation)) throw new IllegalArgumentException("Evidence is immutable");
    }
    public void share(UUID witness, String id, Set<UUID> recipients) {
        var observation = Objects.requireNonNull(observations.get(id), "Unknown observation");
        if (!observation.witness().equals(witness)) throw new SecurityException("Only the witness may share");
        var targets = new HashSet<>(recipients); targets.remove(witness);
        shares.computeIfAbsent(id, k -> new HashSet<>()).addAll(targets);
    }
    public Optional<WorldFact> factById(String id) { return Optional.ofNullable(facts.get(Objects.requireNonNull(id))); }
    public void accept(WorldFact fact) {
        if (!fact.persistence() || fact.narrativeVisibility() == WorldFact.Category.AMBIENT) return;
        // HYBRID is consumed only when a currently accessible quest has an opportunity for this type.
        if (fact.narrativeVisibility() == WorldFact.Category.HYBRID && definitions.values().stream()
            .noneMatch(q -> q.observations().contains(fact.factType()) && (state(q.questId()) != State.LOCKED
                || fact.playerScope().stream().anyMatch(p -> state(q.questId(),p)!=State.LOCKED)
                || groups.entrySet().stream().anyMatch(e -> e.getValue().equals(fact.groupScope())&&state(q.questId(),e.getKey())!=State.LOCKED)
                || ((q.visibilityScope().equals("PERSONAL")||q.visibilityScope().equals("GROUP"))&&fact.playerScope().isEmpty()&&fact.groupScope().isEmpty()
                    &&scopedPlayers().stream().anyMatch(p->state(q.questId(),p)!=State.LOCKED))))) return;
        var old = facts.putIfAbsent(fact.factId(), fact);
        if (old != null && !old.equals(fact)) throw new IllegalArgumentException("Fact identity collision");
    }
    private Set<String> collectiveTypes() {
        var types = new HashSet<String>();
        facts.values().stream().filter(f -> f.playerScope().isEmpty() && f.groupScope().isEmpty()).forEach(f -> types.add(f.factType()));
        observations.values().stream().filter(o -> !shares.getOrDefault(o.id(), Set.of()).isEmpty()).forEach(o -> types.add(o.type()));
        return types;
    }
    public State state(String id) {
        var q = Objects.requireNonNull(definitions.get(id), "Unknown quest");
        if (!q.visibilityScope().equals("SERVER")) return State.LOCKED;
        if (resolved.containsKey(id)) return State.RESOLVED;
        if(!actionReady.test(id))return State.LOCKED;
        if (!resolved.keySet().containsAll(q.prerequisites()) || !collectiveTypes().containsAll(q.availabilityFacts())) return State.LOCKED;
        return q.observations().stream().anyMatch(collectiveTypes()::contains) ? State.IN_PROGRESS : State.AVAILABLE;
    }
    public void evaluate(long now) {
        var types = collectiveTypes();
        boolean changed;
        do {
            changed = false;
            for (var q : definitions.values()) {
                if (state(q.questId()) == State.LOCKED || resolved.containsKey(q.questId())) continue;
                if (q.resolutionConditions().stream().noneMatch(types::containsAll)) continue;
                long since = readySince.computeIfAbsent(q.questId(), k -> now);
                if (q.majorConvergence() && !gate(q, now, since)) continue;
                resolved.put(q.questId(), now);
                observations.values().stream().filter(o -> q.observations().contains(o.type()) && !shares.getOrDefault(o.id(), Set.of()).isEmpty())
                    .forEach(o -> establishedEvidence.add(o.id()));
                changed = true;
            }
        } while (changed);
        evaluateScoped(now);
    }
    private boolean gate(QuestDefinition q, long now, long since) {
        var eligible = activePlayers(now); var contributors = new HashSet<UUID>();
        var relevant = q.observations();

        observations.values().stream().filter(o -> relevant.contains(o.type()) && !shares.getOrDefault(o.id(), Set.of()).isEmpty())
            .forEach(o -> contributors.add(o.witness()));
        // Trusted integrations may identify an actor, but cannot manufacture an active player.
        for (var f : facts.values()) if (f.playerScope().isEmpty() && f.groupScope().isEmpty() && relevant.contains(f.factType()) && f.payload().containsKey("actor")) {
            try { contributors.add(UUID.fromString(f.payload().get("actor"))); } catch (IllegalArgumentException ignored) { }
        }
        contributors.retainAll(eligible);
        return contributors.size() >= Math.max(calibration.minContributors(), (int)Math.ceil(eligible.size()*calibration.activeRatio()))
            && now >= since && now-since >= calibration.circulationMillis();
    }
    /** Explicit Director collections do not belong to, unlock or reveal a quest page. */
    public List<Evidence> recordedPerceptions(UUID player){
        var result=new ArrayList<Evidence>();
        for(var observation:observations.values())if(observation.type().equals("DIRECTOR_PERCEPTION")){
            if(observation.witness().equals(player))result.add(new Evidence(observation,Knowledge.PERSONAL_OBSERVATION));
            else if(shares.getOrDefault(observation.id(),Set.of()).contains(player))result.add(new Evidence(observation,Knowledge.SHARED_EVIDENCE));
        }
        return List.copyOf(result);
    }
    public List<Page> journal(UUID player) {
        var pages = new ArrayList<Page>();
        for (var q : definitions.values()) {
            var state = state(q.questId(),player); if (state == State.LOCKED) continue;
            var evidence = new ArrayList<Evidence>();
            for (var o : observations.values()) if (q.observations().contains(o.type())) {
                if (o.witness().equals(player)) evidence.add(new Evidence(o, Knowledge.PERSONAL_OBSERVATION));
                else if (shares.getOrDefault(o.id(), Set.of()).contains(player)) evidence.add(new Evidence(o, Knowledge.SHARED_EVIDENCE));
                else if (establishedEvidence.contains(o.id()) || scopedEvidence.getOrDefault(scopeKey(q,player),Set.of()).contains(o.id())) evidence.add(new Evidence(o, Knowledge.ESTABLISHED_KNOWLEDGE));
                // Only previously shared evidence may become collective.
            }
            if (state == State.AVAILABLE && !evidence.isEmpty()) state = State.IN_PROGRESS;
            pages.add(new Page(q.questId(), q.playerTitle(), state, q.context(), q.action(), List.copyOf(evidence),
                state == State.RESOLVED ? q.resolvedSummary() : ""));
        }
        return List.copyOf(pages);
    }
    public List<Established> news(UUID player, long now, boolean acknowledge) {
        long last = newsSeen.getOrDefault(player, 0L);
        var result = new ArrayList<>(resolved.entrySet().stream().skip(last)
            .map(e -> new Established(e.getKey(), definitions.get(e.getKey()).resolvedSummary(), e.getValue())).toList());
        if(groups.containsKey(player)) {
            String scope="GROUP/"+groups.get(player), cursor=player+"/"+scope;
            var established=scopedResolved.getOrDefault(scope,Map.of());
            established.entrySet().stream().skip(scopedNewsSeen.getOrDefault(cursor,0)).forEach(e -> result.add(new Established(e.getKey(),definitions.get(e.getKey()).resolvedSummary(),e.getValue())));
            if(acknowledge)scopedNewsSeen.put(cursor,established.size());
        }
        if (acknowledge) newsSeen.put(player, (long)resolved.size()); return List.copyOf(result);
    }
    public List<Page> catchUp(UUID player) {
        return journal(player).stream().filter(p->p.state()==State.RESOLVED)
            .limit(3).map(p->new Page(p.id(),p.title(),p.state(),"Parcours facultatif : relire ce constat déjà établi.",
                "Consultez les preuves collectives conservées puis poursuivez le journal courant.",
                p.evidence().stream().filter(e->establishedEvidence.contains(e.observation().id())).toList(),p.summary())).toList();
    }
    public Set<UUID> sharedWitnesses(String type,String origin){
        var result=new HashSet<UUID>();
        observations.values().stream().filter(o->o.type().equals(type)&&o.origin().equals(origin)&&!shares.getOrDefault(o.id(),Set.of()).isEmpty()).forEach(o->result.add(o.witness()));
        return Set.copyOf(result);
    }
    public boolean convergenceReady(String id,long now){
        var q=Objects.requireNonNull(definitions.get(id));
        if(state(id)==State.LOCKED)return false;
        long since=readySince.computeIfAbsent(id,k->now);
        return !q.majorConvergence()||gate(q,now,since);
    }
    public boolean hasCollective(String type) { return collectiveTypes().contains(type); }
    public Optional<Observation> evidence(UUID player, String id) {
        var o = observations.get(id);
        return o != null && (o.witness().equals(player) || shares.getOrDefault(id, Set.of()).contains(player) || establishedEvidence.contains(id) || (groups.containsKey(player)&&scopedEvidence.getOrDefault("GROUP/"+groups.get(player),Set.of()).contains(id))) ? Optional.of(o) : Optional.empty();
    }
    // HashMap/HashSet capacity and Map.copyOf iteration are not durable ordering.
    // Only unordered state is sorted. Observation and resolution insertion order
    // remains authoritative: journal evidence and news cursors depend on it.
    private static final Gson VALUE_JSON = new Gson();
    private static final Gson SAVE_JSON = new GsonBuilder().registerTypeAdapter(WorldFact.class,
        (JsonSerializer<WorldFact>) (fact, type, context) -> {
            var object = VALUE_JSON.toJsonTree(fact).getAsJsonObject();
            object.add("playerScope", VALUE_JSON.toJsonTree(new TreeSet<>(fact.playerScope())));
            object.add("payload", VALUE_JSON.toJsonTree(new TreeMap<>(fact.payload())));
            return object;
        }).create();
    private static <K extends Comparable<? super K>, V extends Comparable<? super V>> Map<K,Set<V>> sortedSets(Map<K,Set<V>> values) {
        var result = new TreeMap<K,Set<V>>();
        values.forEach((key, value) -> result.put(key, new TreeSet<>(value)));
        return result;
    }
    private static Map<String,Map<String,Long>> sortedReadiness(Map<String,Map<String,Long>> values) {
        var result = new TreeMap<String,Map<String,Long>>();
        values.forEach((key, value) -> result.put(key, new TreeMap<>(value)));
        return result;
    }
    public String save() {
        return SAVE_JSON.toJson(new Snapshot(1, observations, sortedSets(shares), facts,
            new TreeMap<>(activity), resolved, new TreeMap<>(readySince), new TreeMap<>(newsSeen),
            new TreeSet<>(establishedEvidence), new TreeMap<>(groups),
            new TreeMap<>(scopedResolved), sortedReadiness(scopedReady), sortedSets(scopedEvidence),
            new TreeMap<>(scopedNewsSeen)));
    }
    public static QuestCore restore(List<QuestDefinition> definitions, Calibration calibration, String json) {
        var s = new Gson().fromJson(json, Snapshot.class);
        if (s == null || s.schema() != 1) throw new IllegalArgumentException("Unsupported quest schema");
        var core = new QuestCore(definitions, calibration);
        s.observations().values().forEach(core::observe);
        s.shares().forEach((id, targets) -> core.share(core.observations.get(id).witness(), id, targets));
        core.facts.putAll(s.facts()); core.activity.putAll(s.activity());
        for (var id : s.resolved().keySet()) if (!core.definitions.containsKey(id)) throw new IllegalArgumentException("Unknown saved quest");
        if (s.establishedEvidence() != null) core.establishedEvidence.addAll(s.establishedEvidence());
        core.resolved.putAll(s.resolved()); core.readySince.putAll(s.readySince()); core.newsSeen.putAll(s.newsSeen());
        if(s.groups()!=null)core.groups.putAll(s.groups());
        if(s.scopedResolved()!=null)core.scopedResolved.putAll(s.scopedResolved());
        if(s.scopedReady()!=null)core.scopedReady.putAll(s.scopedReady());
        if(s.scopedEvidence()!=null)core.scopedEvidence.putAll(s.scopedEvidence());
        if(s.scopedNewsSeen()!=null)core.scopedNewsSeen.putAll(s.scopedNewsSeen());
        // Legacy snapshots without a publication set can reconstruct shared evidence.
        // An explicit set (including an empty set) is authoritative: restart must
        // not widen the recipients of evidence shared after a prior resolution.
        if(s.establishedEvidence()==null)for(var id:core.resolved.keySet()) {
            var q=core.definitions.get(id);
            if(!q.visibilityScope().equals("SERVER"))continue;
            core.observations.values().stream().filter(o->q.observations().contains(o.type())&&!core.shares.getOrDefault(o.id(),Set.of()).isEmpty())
                .forEach(o->core.establishedEvidence.add(o.id()));
        }
        return core;
    }
}
