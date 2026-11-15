package fr.proutlost.director.client.perception;

import fr.proutlost.director.api.model.*;
import java.util.*;
import java.util.function.*;

/** Main-thread state machine. Clock is local monotone elapsed ticks, never world/day time.
 * Sequence fences and terminal effect identities survive TTL/Stop until the session ends. Exhaustion fails closed, never evicts a fence. */
public final class PerceptionManager {
    public interface Effects {
        boolean available(PerceptionUpsert effect);
        void start(PerceptionUpsert effect);
        void update(PerceptionUpsert effect);
        void stop(EffectId id);
    }
    public record Limits(int effects, int silhouettes, int rememberedIds, long ttlTicks) {
        public Limits { Checks.require(effects>0 && silhouettes>0 && rememberedIds>=effects && ttlTicks>0, "client limits"); }
    }
    private record Active(PerceptionUpsert value, long deadline) {}
    private final Thread owner=Thread.currentThread();
    private final Effects effects;
    private final Limits limits;
    private final LongSupplier clock;
    private final Consumer<PerceptionAck> acknowledgements;
    private final Map<EffectId,Active> active=new HashMap<>();
    private final Map<EffectId,Long> sequences=new HashMap<>();
    // Subset of sequences: no additional identities beyond the rememberedIds bound.
    // A completed effect needs a new admitted identity, never a later heartbeat.
    private final Set<EffectId> retired=new HashSet<>();
    private final Map<EventInstanceId,Long> instanceStops=new HashMap<>();
    private SessionReset session;
    private DimensionId dimension;
    private long resetSequence=-1, lastTime;
    private boolean exhausted, rendererFaulted;
    public PerceptionManager(Effects effects, Limits limits, LongSupplier clock, Consumer<PerceptionAck> acknowledgements) {
        this.effects=Objects.requireNonNull(effects); this.limits=limits; this.clock=clock; this.acknowledgements=acknowledgements;
    }
    private long now() { Checks.require(Thread.currentThread()==owner,"client thread"); long n=clock.getAsLong(); Checks.require(n>=lastTime,"monotone clock"); return lastTime=n; }
    public boolean session(SessionReset next, DimensionId dimension) {
        now();
        if(session!=null) {
            if(!session.worldId().equals(next.worldId())) return false;
            int boot=next.bootEpoch().compareTo(session.bootEpoch());
            if(boot<0 || (boot==0 && next.connectionEpoch().compareTo(session.connectionEpoch())<=0)) return false;
        }
        clear(); session=next; this.dimension=dimension; resetSequence=-1;
        // If any physical renderer cleanup failed, changing the connection
        // epoch cannot make its potentially orphaned visual safe again.
        exhausted=rendererFaulted;
        sequences.clear(); retired.clear(); instanceStops.clear(); return !rendererFaulted;
    }
    public void disconnect() { now(); clear(); session=null; dimension=null; sequences.clear(); retired.clear(); instanceStops.clear(); exhausted=rendererFaulted; }
    /** Death/dimension/respawn suspends until a new server connection epoch is negotiated. */
    public void invalidate() { now(); clear(); exhausted=true; }
    private void clear() {
        var ids=List.copyOf(active.keySet());
        active.clear(); // Drop ALL logical effects before invoking potentially broken renderer code.
        for(var id:ids) stopSafely(id);
    }
    private void stopSafely(EffectId id) {
        try { effects.stop(id); }
        catch(RuntimeException failure) {
            if(!rendererFaulted) System.err.println("DIRECTOR_J06_RENDERER_STOP_FAILED_FAIL_CLOSED");
            rendererFaulted=true;
            exhausted=true; // No new effects until the manager itself is replaced.
        }
    }
    private boolean current(PerceptionHeader h) { return !exhausted && session!=null && NetworkContracts.current(h,session,dimension); }
    private boolean remember(EffectId id,long sequence) {
        if(!sequences.containsKey(id) && sequences.size()+instanceStops.size()>=limits.rememberedIds()) { clear(); exhausted=true; return false; }
        sequences.merge(id,sequence,Math::max); return true;
    }
    public void upsert(PerceptionUpsert value) {
        long time=now(); expire(time); var h=value.header();
        if(!current(h)) return;
        long seq=h.sequence().value();
        if(seq<=resetSequence || seq<=instanceStops.getOrDefault(h.instanceId(),-1L) || seq<sequences.getOrDefault(h.effectId(),-1L)) return;
        if(retired.contains(h.effectId())) {
            ack(h,PerceptionAckStatus.EXPIRED); return;
        }
        Active old=active.get(h.effectId());
        if(seq==sequences.getOrDefault(h.effectId(),-1L)) {
            if(old!=null && old.value().equals(value)) ack(h,PerceptionAckStatus.RECEIVED);
            return; // Retries never extend TTL or replay one-shot audio.
        }
        if(!remember(h.effectId(),seq)) { ack(h,PerceptionAckStatus.ERROR); return; }
        if(h.remainingTtlTicks().value()>limits.ttlTicks()) { ack(h,PerceptionAckStatus.ERROR); return; }
        if(!session.authorizedEffects().contains(value.kind()) || !value.assetHash().equals(session.authorizedAssets().get(value.assetId()))) {
            remove(h.effectId()); ack(h,PerceptionAckStatus.UNSUPPORTED); return;
        }
        final boolean available;
        try { available=effects.available(value); }
        catch(RuntimeException failure) {
            // Resource reloads may throw while checking the renderer. Do not
            // let an unchecked asset lookup crash a tick or leave an old effect.
            remove(h.effectId()); retired.add(h.effectId());
            ack(h,PerceptionAckStatus.ERROR); return;
        }
        if(!available) { remove(h.effectId()); ack(h,PerceptionAckStatus.UNSUPPORTED); return; }
        if(old!=null && (!old.value().header().instanceId().equals(h.instanceId()) || old.value().kind()!=value.kind() || !old.value().assetId().equals(value.assetId()) || !old.value().parameters().equals(value.parameters()) || !old.value().transform().equals(value.transform()))) {
            ack(h,PerceptionAckStatus.ERROR); return;
        }
        if(old==null && (active.size()>=limits.effects() || (value.kind()==PerceptionKind.APPARITION && active.values().stream().filter(a->a.value().kind()==PerceptionKind.APPARITION).count()>=limits.silhouettes()))) {
            ack(h,PerceptionAckStatus.ERROR); return;
        }
        try {
            if(old==null) effects.start(value); else effects.update(value);
            active.put(h.effectId(),new Active(value,Math.addExact(time,h.remainingTtlTicks().value())));
            ack(h,PerceptionAckStatus.RECEIVED);
        } catch(RuntimeException failure) {
            // A renderer may have partially started before throwing. Quarantine
            // its identity and stop it once: remove() already stops active
            // effects, whereas a failed first start still needs best-effort
            // cleanup. Never retry the same effect identity after an error.
            boolean wasActive=active.containsKey(h.effectId());
            if(wasActive) remove(h.effectId());
            else stopSafely(h.effectId());
            retired.add(h.effectId());
            ack(h,PerceptionAckStatus.ERROR);
        }
    }
    public void stop(PerceptionStop value) { now(); if(!current(value.header()))return; if(value.wholeInstance()) fenceInstance(value.header()); else {
        var h=value.header(); if(h.sequence().value()<sequences.getOrDefault(h.effectId(),-1L))return;
        if(remember(h.effectId(),h.sequence().value())) {
            remove(h.effectId());
            // STOP is terminal for this effect identity even when it arrives
            // before the first UPSERT (or after its TTL expired). A newer
            // heartbeat must never resurrect a stopped visual/sound.
            retired.add(h.effectId());
        }
    } }
    public void reset(PerceptionReset value) {
        now(); var h=value.header(); if(!current(h))return;
        if(value.scope()==PerceptionResetScope.INSTANCE) fenceInstance(h);
        else { resetSequence=Math.max(resetSequence,h.sequence().value()); for(var a:List.copyOf(active.values())) if(a.value().header().sequence().value()<=resetSequence)remove(a.value().header().effectId()); }
    }
    private void fenceInstance(PerceptionHeader h) {
        if(!instanceStops.containsKey(h.instanceId()) && sequences.size()+instanceStops.size()>=limits.rememberedIds()){ clear(); exhausted=true; return; }
        instanceStops.merge(h.instanceId(),h.sequence().value(),Math::max);
        for(var a:List.copyOf(active.values())) if(a.value().header().instanceId().equals(h.instanceId()) && a.value().header().sequence().value()<=h.sequence().value()) remove(a.value().header().effectId());
    }
    private void remove(EffectId id) { if(active.remove(id)!=null) { retired.add(id); stopSafely(id); } }
    private void ack(PerceptionHeader h,PerceptionAckStatus status) {
        try { acknowledgements.accept(new PerceptionAck(h,status)); }
        catch(RuntimeException failure) {
            // If the transport disappeared, rendering without a confirmed
            // server epoch is unsafe. Stop all cosmetic effects and suspend.
            if(!exhausted) System.err.println("DIRECTOR_J06_ACK_SEND_FAILED_FAIL_CLOSED");
            clear(); exhausted=true;
        }
    }
    private void expire(long time) { for(var a:List.copyOf(active.values())) if(time>=a.deadline()){remove(a.value().header().effectId());ack(a.value().header(),PerceptionAckStatus.EXPIRED);} }
    public void tick() { expire(now()); }
    public int activeCount() { return active.size(); }
    public boolean needsSessionReset() {
        // Rotate at the closed boundary, before the next identity can overflow.
        // No fence is forgotten here and active effects retain their advertised TTL.
        return exhausted || active.isEmpty()&&sequences.size()+instanceStops.size()>=limits.rememberedIds();
    }
    public boolean rendererFaulted() { return rendererFaulted; }
}
