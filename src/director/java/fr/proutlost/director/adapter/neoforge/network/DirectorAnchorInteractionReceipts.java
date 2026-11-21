package fr.proutlost.director.adapter.neoforge.network;

import fr.proutlost.director.api.model.*;
import java.util.*;

/** Bounded transient client hints, independent from historical voluntary-record references. */
public final class DirectorAnchorInteractionReceipts {
    private record Key(EventInstanceId instance,EffectId effect,Sequence sequence) {}
    private record Entry(PerceptionHeader header,long expires) {}
    private final int maximum;
    private final LinkedHashMap<Key,Entry> entries=new LinkedHashMap<>();
    private long lastTick;
    public DirectorAnchorInteractionReceipts(int maximum) {
        if(maximum<1||maximum>128)throw new IllegalArgumentException("interaction receipt bound");
        this.maximum=maximum;
    }
    private void expire(long tick) {
        if(tick<lastTick)throw new IllegalArgumentException("client clock rollback");
        lastTick=tick;entries.values().removeIf(entry->entry.expires()<=tick);
    }
    private static Key key(PerceptionHeader header){return new Key(header.instanceId(),header.effectId(),header.sequence());}
    public boolean remember(PerceptionHeader header,long tick) {
        Objects.requireNonNull(header);expire(tick);
        long ttl=header.remainingTtlTicks().value();
        if(ttl==0||ttl>1200||tick>Long.MAX_VALUE-ttl)return false;
        var key=key(header);var prior=entries.get(key);
        if(prior!=null)return prior.header().equals(header); // Duplicate ACK never extends expiry.
        if(entries.size()==maximum)entries.remove(entries.keySet().iterator().next());
        entries.put(key,new Entry(header,tick+ttl));return true;
    }
    public PerceptionHeader received(DirectorAnchorInteractionCommands.Context reference,long tick) {
        Objects.requireNonNull(reference);expire(tick);
        var entry=entries.get(new Key(reference.instance(),reference.effect(),reference.sequence()));
        return entry==null?null:entry.header();
    }
    private static boolean sameSession(PerceptionHeader a,PerceptionHeader b) {
        return a.worldId().equals(b.worldId())&&a.bootEpoch().equals(b.bootEpoch())
            &&a.connectionEpoch().equals(b.connectionEpoch())&&a.dimension().equals(b.dimension());
    }
    public void stopped(PerceptionStop stop) {
        var header=stop.header();
        entries.values().removeIf(entry->sameSession(entry.header(),header)
            &&entry.header().sequence().compareTo(header.sequence())<=0
            &&(stop.wholeInstance()?entry.header().instanceId().equals(header.instanceId()):entry.header().effectId().equals(header.effectId())));
    }
    public void reset(PerceptionReset reset) {
        var header=reset.header();
        entries.values().removeIf(entry->sameSession(entry.header(),header)
            &&entry.header().sequence().compareTo(header.sequence())<=0
            &&(reset.scope()!=PerceptionResetScope.INSTANCE||entry.header().instanceId().equals(header.instanceId())));
    }
    public void tick(long tick){expire(tick);}
    public void clear(){entries.clear();}
    public int size(){return entries.size();}
}
