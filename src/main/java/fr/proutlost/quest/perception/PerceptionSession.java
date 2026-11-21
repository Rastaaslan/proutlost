package fr.proutlost.quest.perception;

import com.google.gson.Gson;
import fr.proutlost.quest.core.QuestCore;
import java.util.*;
import java.util.function.Predicate;

/** Targeted, non-physical scene. The transport sends only forPlayer(player), never this snapshot. */
public final class PerceptionSession {
    public enum Family { SOUND, SYMBOLS }
    public record Position(String dimension, double x, double y, double z) {
        public boolean movedFrom(Position other) {
            return dimension.equals(other.dimension) && Math.pow(x-other.x,2)+Math.pow(y-other.y,2)+Math.pow(z-other.z,2) >= 4;
        }
    }
    public record Fragment(String id, UUID recipient, Family family, List<String> sequence, String scene, long timestamp, int trial) {
        public Fragment { sequence = List.copyOf(sequence); }
    }
    private final String scene;
    private final List<String> sounds, symbols;
    private Map<UUID,Position> positions = new LinkedHashMap<>();
    private final Map<UUID,Family> families = new HashMap<>();
    private final Map<String,Fragment> fragments = new LinkedHashMap<>();
    private int trial;
    public PerceptionSession(String scene, List<String> sounds, List<String> symbols) {
        if (sounds.size()!=3 || symbols.size()!=3) throw new IllegalArgumentException("Three fragments per family");
        this.scene=scene; this.sounds=List.copyOf(sounds); this.symbols=List.copyOf(symbols);
    }
    public void play(Map<UUID,Position> present, long now) {
        if (present.size()<2) throw new IllegalArgumentException("Complementary witnesses required");
        if (trial>0 && (positions.size()!=present.size() || !positions.keySet().equals(present.keySet())
            || present.entrySet().stream().anyMatch(e -> !e.getValue().movedFrom(positions.get(e.getKey())))))
            throw new IllegalStateException("All witnesses must physically change position");
        var players = present.keySet().stream().sorted().toList();
        trial++;
        for (int i=0;i<players.size();i++) {
            UUID player=players.get(i);
            var family = families.containsKey(player) ? (families.get(player)==Family.SOUND ? Family.SYMBOLS : Family.SOUND)
                : ((i+Math.floorMod(scene.hashCode(),2))%2==0 ? Family.SOUND : Family.SYMBOLS);
            families.put(player,family);
            String id=scene+":"+trial+":"+player;
            fragments.put(id,new Fragment(id,player,family,family==Family.SOUND?sounds:symbols,scene,now,trial));
        }
        positions=new LinkedHashMap<>(present);
    }
    public List<Fragment> forPlayer(UUID player) { return fragments.values().stream().filter(f -> f.recipient().equals(player)).toList(); }
    public void record(UUID player, String fragmentId, QuestCore core) {
        var f=Objects.requireNonNull(fragments.get(fragmentId),"Unknown fragment");
        if (!f.recipient().equals(player)) throw new SecurityException("Private perception");
        core.observe(new QuestCore.Observation(f.id(),player,f.family()==Family.SOUND?"SOUND_RECORDED":"SYMBOLS_RECORDED",
            String.join(" · ",f.sequence()),f.scene(),f.timestamp()));
    }
    /** A new witness receives an additional immutable fragment, without modifying the original. */
    public Fragment recover(Family family, UUID newWitness, Set<UUID> active, long now, Predicate<Fragment> availableKnowledge) {
        if (hasAvailableWitness(family, active, availableKnowledge))
            throw new IllegalStateException("A witness remains available");
        String id=scene+":recovery:"+UUID.randomUUID();
        var f=new Fragment(id,newWitness,family,family==Family.SOUND?sounds:symbols,scene,now,trial); fragments.put(id,f); return f;
    }
    /** Native holders and recipients of shared/established knowledge are both holders.
     * The caller supplies the authoritative knowledge policy; this session never reads a journal snapshot. */
    private boolean hasAvailableWitness(Family family, Set<UUID> active, Predicate<Fragment> availableKnowledge) {
        Objects.requireNonNull(availableKnowledge);
        return fragments.values().stream().anyMatch(f -> f.family()==family
            && (active.contains(f.recipient()) || availableKnowledge.test(f)));
    }
    /** Substitute an unavailable participant before the next trial. A new physical baseline
     * is required; replacement does not count as a position change or a completed trial. */
    public Optional<Fragment> replaceWitness(UUID departed, UUID replacement, Position baseline,
                                   Set<UUID> active, long now, Predicate<Fragment> availableKnowledge) {
        if (!positions.containsKey(departed) || active.contains(departed)
                || !active.contains(replacement) || positions.containsKey(replacement))
            throw new IllegalStateException("Unavailable witness and active replacement required");
        if (!baseline.dimension().equals(positions.get(departed).dimension()))
            throw new IllegalArgumentException("Replacement must attend the same scene");
        Family family = families.get(departed);
        // Roster replacement must remain possible even while another holder can share.
        // Only grant an old exact fragment when every holder is durably unavailable.
        Optional<Fragment> fragment = Optional.empty();
        if (!hasAvailableWitness(family, active, availableKnowledge)) {
            String id = scene + ":replacement:" + UUID.randomUUID();
            fragment = Optional.of(new Fragment(id, replacement, family,
                family == Family.SOUND ? sounds : symbols, scene, now, trial));
        }
        positions.remove(departed);
        positions.put(replacement, baseline);
        families.put(replacement, family);
        fragment.ifPresent(f -> fragments.put(f.id(), f));
        return fragment;
    }
    public Set<UUID> participants() { return Set.copyOf(positions.keySet()); }
    public boolean comparesTrials(String firstId,String secondId) {
        var first=fragments.get(firstId);var second=fragments.get(secondId);
        return first!=null&&second!=null&&first.trial()>0&&second.trial()>0
            &&first.trial()!=second.trial()&&Math.max(first.trial(),second.trial())>=2
            &&Math.max(first.trial(),second.trial())<=trial;
    }
    public int trial() { return trial; }
    public String save() { return new Gson().toJson(this); }
    public static PerceptionSession restore(String json) {
        var session=new Gson().fromJson(json,PerceptionSession.class);
        // Legacy ordinary ids encode their trial. Never infer a recovery trial from timestamps.
        session.fragments.replaceAll((id,f)->{
            if(f.trial()>0)return f;
            String prefix=f.scene()+":";
            if(!id.startsWith(prefix))return f;
            try {
                int origin=Integer.parseInt(id.substring(prefix.length()).split(":",2)[0]);
                if(origin>0&&origin<=session.trial)return new Fragment(f.id(),f.recipient(),f.family(),f.sequence(),f.scene(),f.timestamp(),origin);
            } catch(NumberFormatException ignored) { }
            return f;
        });
        return session;
    }
}
