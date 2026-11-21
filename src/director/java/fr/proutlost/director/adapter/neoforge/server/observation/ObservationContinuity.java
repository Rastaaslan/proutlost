package fr.proutlost.director.adapter.neoforge.server.observation;

import fr.proutlost.director.api.model.*;
import java.util.*;

/** Ephemeral actor sampling state. No region membership or persisted player trajectories. */
public final class ObservationContinuity {
    private record Camp(AnchorId anchor, Tick tick) {}
    private record Isolation(Tick start, Tick last) {}
    private final Map<PlayerId,Camp> camps=new HashMap<>();
    private final Map<PlayerId,Isolation> isolation=new HashMap<>();
    private final long interval,campInterval;
    public ObservationContinuity(long interval) {this(interval,interval);}
    public ObservationContinuity(long interval,long campInterval) {
        if(interval<=0||campInterval<=0)throw new IllegalArgumentException("sampling interval");
        this.interval=interval;this.campInterval=campInterval;
    }
    /** Called for each regular camp observation, including absent/ineligible observations. */
    public boolean camp(PlayerId player, Optional<AnchorId> anchor, PlayerEligibility eligibility, Tick now) {
        Camp previous=camps.remove(player);
        if(eligibility!=PlayerEligibility.ELIGIBLE||anchor.isEmpty())return false;
        camps.put(player,new Camp(anchor.get(),now));
        return previous!=null&&previous.anchor().equals(anchor.get())&&now.value()-previous.tick().value()==campInterval;
    }
    /** Only a complete proximity census can establish absence of companions. */
    public void proximity(Map<PlayerId,PlayerSnapshot> players, ProximityGroups.Result groups, boolean complete, Tick now) {
        if(!complete||!groups.complete()){isolation.clear();return;}
        isolation.keySet().retainAll(players.keySet());
        for(var p:players.values()) {
            PlayerId id=p.playerId();
            if(p.eligibility()!=PlayerEligibility.ELIGIBLE||!groups.memberships().getOrDefault(id,Set.of()).isEmpty()) {
                isolation.remove(id);continue;
            }
            Isolation old=isolation.get(id);
            Tick start=old!=null&&now.value()-old.last().value()==interval?old.start():now;
            isolation.put(id,new Isolation(start,now));
        }
    }
    /** A snapshot never credits time beyond the most recent complete regular observation. */
    public long isolatedTicks(PlayerId player,Tick now) {
        Isolation value=isolation.get(player);
        if(value==null||now.value()<value.last().value()||now.value()-value.last().value()>=interval)return 0;
        return value.last().value()-value.start().value();
    }
    public void resetIsolation(PlayerId player){isolation.remove(player);}
    public void reset(PlayerId player){camps.remove(player);isolation.remove(player);}
    public void clear(){camps.clear();isolation.clear();}
}
