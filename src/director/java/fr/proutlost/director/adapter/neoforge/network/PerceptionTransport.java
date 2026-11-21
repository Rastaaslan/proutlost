package fr.proutlost.director.adapter.neoforge.network;

import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.port.PerceptionPort;
import java.util.*;
import java.util.function.*;

/** Actor-owned transport. Runtime must durably authorize irreversible exposure before calling upsert.
 * ACKs describe reception only. No world writes or persistence callbacks run here. */
public final class PerceptionTransport implements PerceptionPort {
    public record Limits(int sessions,int effects,int messagesPerSecond,long ttlTicks,int rememberedIds,int adminMessagesPerSecond,long adminRefreshTicks) {
        public Limits { Checks.require(sessions>0&&effects>0&&messagesPerSecond>0&&ttlTicks>0&&rememberedIds>=effects&&adminMessagesPerSecond>0&&adminRefreshTicks>0,"network limits"); }
    }
    private static final class Connection {
        final SessionIdentity identity; final SessionId adminSession;
        Capabilities capabilities;
        final long openedAt;
        boolean resetRequested, revoked;
        final Map<EffectId,PerceptionUpsert> sent=new HashMap<>();
        // Exact sent headers, retained for this session. Receipt evidence is monotone:
        // expiry, Stop and later ERROR/EXPIRED ACKs cannot undo an earlier receipt.
        final Map<PerceptionHeader,Boolean> receipts=new HashMap<>();
        // Renderer headers and RECORD-only envelopes share ONE rememberedIds ceiling.
        final Map<PerceptionHeader,FactId> recordFacts=new HashMap<>();
        int recordOnlyAuthorities;
        final Map<EffectId,Long> expires=new HashMap<>();
        long outboundWindow=-1,inboundWindow=-1,lastSequence=-1; int outbound,inbound,adminCount; long adminWindow=-1,lastQuery=-1;
        Connection(SessionIdentity identity,long openedAt){this.identity=identity;this.adminSession=adminSession(identity);this.openedAt=openedAt;}
    }
    private final Thread owner=Thread.currentThread();
    private final WorldId world; private final Epoch boot; private long nextConnection;
    private final Set<PerceptionKind> kinds; private final Map<ResourceId,Fingerprint> assets;
    private final Limits limits; private final LongSupplier clock; private long lastTick;
    private final BiConsumer<PlayerId,Object> sender;
    private final Map<PlayerId,Connection> connections=new HashMap<>();
    public PerceptionTransport(WorldId world,Epoch boot,Set<PerceptionKind> kinds,Map<ResourceId,Fingerprint> assets,Limits limits,LongSupplier clock,BiConsumer<PlayerId,Object> sender) {
        this.world=world;this.boot=boot;this.kinds=Set.copyOf(kinds);this.assets=Map.copyOf(assets);this.limits=limits;this.clock=clock;this.sender=sender;
        Checks.require(assets.size()<=128,"asset registry bound");
    }
    /** Single canonical correlation shared by client F8, J06 transport and J07 J04 bridge.
     * This key is public, never a permission grant: server still checks op level 4. */
    public static SessionId adminSession(SessionIdentity i){return DirectorAdminSessionKey.of(i);}
    private long tick(){Checks.require(Thread.currentThread()==owner,"actor thread");long t=clock.getAsLong();Checks.require(t>=lastTick,"clock rollback");return lastTick=t;}
    public SessionIdentity open(PlayerId player,DimensionId dimension) {
        tick();Checks.require(connections.containsKey(player)||connections.size()<limits.sessions(),"session capacity");
        var identity=new SessionIdentity(world,boot,new Epoch(Math.incrementExact(nextConnection)),dimension,player);nextConnection=identity.connectionEpoch().value();
        var connection=new Connection(identity,lastTick);connections.put(player,connection);
        deliver(player,connection,new SessionReset(1,world,boot,identity.connectionEpoch(),kinds,assets));return identity;
    }
    public void close(PlayerId player){tick();connections.remove(player);}
    public Optional<SessionIdentity> identity(PlayerId player){tick();var c=connections.get(player);return c==null||c.revoked?Optional.empty():Optional.of(c.identity);}
    /** Keep the old bounded fences until a genuine new connection epoch is sent.
     * Resource revocation also hides old authority immediately; quota rollover waits for live effects to end. */
    public boolean sessionResetRequested(PlayerId player){tick();var c=connections.get(player);return c!=null&&c.resetRequested;}
    private int remembered(Connection c){return c.receipts.size()+c.recordOnlyAuthorities;}
    public int rememberedHeaders(PlayerId player){tick();var c=connections.get(player);return c==null?0:remembered(c);}
    public boolean readyForSessionReset(PlayerId player){
        long now=tick();var c=connections.get(player);if(c==null)return false;expire(c,now);
        // A client cannot force unbounded resets by repeating a reload advertisement.
        return c.resetRequested&&c.expires.isEmpty()&&now-c.openedAt>=20;
    }
    /** Actor preflight only, not a reservation or exposure permission. Recheck before apply.
     * Refuse the whole requested tranche before its WAL dossier; keep all old fences until reset. */
    public boolean canSendNew(PlayerId player,int newHeaders){
        long now=tick();Checks.require(newHeaders>0&&newHeaders<=limits.rememberedIds(),"perception tranche bound");
        var c=connections.get(player);if(c==null||c.revoked||c.capabilities==null)return false;
        expire(c,now);
        if(c.sent.size()>limits.rememberedIds()-newHeaders||remembered(c)>limits.rememberedIds()-newHeaders){
            c.resetRequested=true;return false;
        }
        if(c.resetRequested||c.expires.size()>limits.effects()-newHeaders)return false;
        int outbound=c.outboundWindow==now/20?c.outbound:0;
        return newHeaders<=limits.messagesPerSecond()-outbound;
    }
    public ActorIdentity actor(PlayerId player,int permission){tick();var c=connection(player);return new ActorIdentity(c.adminSession,ActorKind.PLAYER,Optional.of(player),Optional.empty(),permission,true);}
    public boolean capabilities(PlayerId player,Capabilities value){tick();var c=connections.get(player);if(c==null||c.revoked||!c.identity.connectionEpoch().equals(value.connectionEpoch())||!inbound(c))return false;
        Set<PerceptionKind> accepted=new HashSet<>(value.supportedEffects());accepted.retainAll(kinds);
        Map<ResourceId,Fingerprint> hashes=new HashMap<>();value.assetHashes().forEach((k,v)->{if(v.equals(assets.get(k)))hashes.put(k,v);});
        var negotiated=new Capabilities(1,value.connectionEpoch(),accepted,hashes);
        // Empty capabilities after negotiation revoke authority; they never grant
        // an in-place replacement. The existing SessionReset handshake must issue
        // a newer epoch before any refreshed asset can be used.
        if(c.capabilities!=null&&negotiated.supportedEffects().isEmpty()&&negotiated.assetHashes().isEmpty()){
            c.capabilities=null;c.expires.clear();c.recordFacts.clear();c.recordOnlyAuthorities=0;c.revoked=true;c.resetRequested=true;return false;
        }
        if(c.resetRequested)return false;
        // Nonempty identical retries remain idempotent. Other changes are refused.
        if(c.capabilities!=null)return c.capabilities.equals(negotiated);
        c.capabilities=negotiated;return true;
    }
    public Optional<Capabilities> capabilities(PlayerId player){tick();var c=connections.get(player);return c==null?Optional.empty():Optional.ofNullable(c.capabilities);}
    /** If native sending fails after partially queuing a packet, its result is
     * ambiguous. Revoke the complete session: no ACK, interaction, F8 or
     * subsequent sequence can treat that payload as successfully delivered. */
    private void deliver(PlayerId player,Connection connection,Object payload){
        try {sender.accept(player,payload);}
        catch(RuntimeException failedSend){
            connections.remove(player,connection);
            throw new IllegalStateException("J06 delivery uncertain; client session revoked",failedSend);
        }
    }
    private Connection connection(PlayerId player){var c=connections.get(player);Checks.require(c!=null&&!c.revoked,"no current connection");return c;}
    private boolean current(Connection c,PerceptionHeader h){var i=c.identity;return !c.revoked&&h.worldId().equals(i.worldId())&&h.bootEpoch().equals(i.bootEpoch())&&h.connectionEpoch().equals(i.connectionEpoch())&&h.dimension().equals(i.dimension());}
    private boolean inbound(Connection c){long window=lastTick/20;if(c.inboundWindow!=window){c.inboundWindow=window;c.inbound=0;}return ++c.inbound<=limits.messagesPerSecond()*4;}
    private void sequence(Connection c,PerceptionHeader h){Checks.require(current(c,h)&&h.sequence().value()>c.lastSequence,"stale session/sequence");c.lastSequence=h.sequence().value();}
    @Override public void upsert(PerceptionIntent intent){long t=tick();var c=connection(intent.target());var e=intent.effect();var h=e.header();
        Checks.require(c.identity.equals(intent.session())&&current(c,h),"stale session");
        Checks.require(c.capabilities!=null&&c.capabilities.supportedEffects().contains(e.kind())&&e.assetHash().equals(c.capabilities.assetHashes().get(e.assetId())),"capability missing");
        Checks.require(h.remainingTtlTicks().value()<=limits.ttlTicks(),"TTL bound");
        expire(c,t);var previous=c.sent.get(h.effectId());
        if(previous==null&&c.sent.size()>=limits.rememberedIds())c.resetRequested=true;
        Checks.require(previous!=null||c.sent.size()<limits.rememberedIds(),"session fence capacity: renegotiate");
        if(remembered(c)>=limits.rememberedIds())c.resetRequested=true;
        Checks.require(remembered(c)<limits.rememberedIds(),"receipt provenance capacity: renegotiate");
        Checks.require(!c.recordFacts.containsKey(h)||c.receipts.containsKey(h),"RECORD-only envelope is not a renderer effect");
        Checks.require(c.expires.containsKey(h.effectId())||c.expires.size()<limits.effects(),"effect capacity");
        if(previous!=null) Checks.require(c.expires.containsKey(h.effectId())&&previous.kind()==e.kind()&&previous.assetId().equals(e.assetId())&&previous.parameters().equals(e.parameters())&&previous.transform().equals(e.transform())&&previous.header().instanceId().equals(h.instanceId()),"renewal requires same live effect");
        long w=t/20;if(c.outboundWindow!=w){c.outboundWindow=w;c.outbound=0;}Checks.require(c.outbound<limits.messagesPerSecond(),"slow client/backpressure");
        long expiry=Math.addExact(t,h.remainingTtlTicks().value());sequence(c,h);c.outbound++;
        c.sent.put(h.effectId(),e);c.receipts.put(h,false);c.expires.put(h.effectId(),expiry);
        if(c.sent.size()>=limits.rememberedIds()||remembered(c)>=limits.rememberedIds())c.resetRequested=true;
        deliver(intent.target(),c,e);
    }
    @Override public void stop(PlayerId target,PerceptionStop stop){tick();var c=connection(target);sequence(c,stop.header());
        if(stop.wholeInstance())c.expires.keySet().removeIf(id->c.sent.get(id).header().instanceId().equals(stop.header().instanceId()));else c.expires.remove(stop.header().effectId());deliver(target,c,stop);
    }
    @Override public void reset(PlayerId target,PerceptionReset reset){tick();var c=connection(target);sequence(c,reset.header());
        if(reset.scope()==PerceptionResetScope.INSTANCE)c.expires.keySet().removeIf(id->c.sent.get(id).header().instanceId().equals(reset.header().instanceId()));else c.expires.clear();deliver(target,c,reset);
    }
    private void expire(Connection c,long t){c.expires.values().removeIf(deadline->deadline<=t);}
    public boolean acknowledge(PlayerId sender,PerceptionAck ack){tick();var c=connections.get(sender);if(c==null||!inbound(c)||!current(c,ack.header()))return false;
        if(!c.receipts.containsKey(ack.header()))return false;
        if(ack.status()==PerceptionAckStatus.RECEIVED)c.receipts.put(ack.header(),true);
        return true;
    }
    /** Exact, current-session renderer receipt; never a claim that a human understood it. */
    public boolean received(PlayerId player,PerceptionHeader header) {
        tick();var c=connections.get(player);
        return c!=null && current(c,header) && Boolean.TRUE.equals(c.receipts.get(header));
    }
    /** Live perimeter authority is stronger than historical reception evidence.
     * A later renewal must itself be received; Stop, expiry and reconnect revoke
     * live authority without rewriting the historical received() receipt. */
    public boolean warningReceivedAndLive(PlayerId player,PerceptionHeader header,
            ResourceId profile,PerceptionTransform transform) {
        long now=tick();var c=connections.get(player);
        if(c==null||!current(c,header))return false;
        expire(c,now);
        var emitted=c.sent.get(header.effectId());
        return Boolean.TRUE.equals(c.receipts.get(header))
            &&c.expires.containsKey(header.effectId())&&emitted!=null
            &&emitted.header().equals(header)&&emitted.kind()==PerceptionKind.SYMBOL
            &&emitted.assetId().equals(profile)&&emitted.transform().equals(transform)
            &&emitted.parameters() instanceof SymbolParameters symbol
            &&symbol.symbol().equals(profile);
    }
    /** Called only for an explicit C2S record action; delivery never creates a journal entry. */
    public boolean record(PlayerId sender,RecordObservation record,Predicate<FactId> receivedFact,Consumer<RecordObservation> accepted){
        tick();var c=connections.get(sender);if(c==null||!inbound(c)||!current(c,record.header()))return false;
        var source=record.observation();boolean known=source.receivedEffectId().map(id->{return id.equals(record.header().effectId())&&Boolean.TRUE.equals(c.receipts.get(record.header()));}).orElseGet(()->source.receivedFactId().filter(id->id.equals(c.recordFacts.get(record.header()))).filter(receivedFact).isPresent());
        if(known)accepted.accept(record);return known;
    }
    /** Existing targeted ACK/journal emission, NOT an Upsert or renderer receipt.
     * A fact referencing a current RECEIVED header spends no second authority slot.
     * Cold RECORD-only envelopes use the same rememberedIds budget and genuine reset. */
    public boolean emitRecordFact(PlayerId player,FactId fact,PerceptionHeader header,Runnable sender){
        tick();Objects.requireNonNull(fact);Objects.requireNonNull(sender);
        var c=connections.get(player);
        if(c==null||!current(c,header)||c.capabilities==null)return false;
        var previous=c.recordFacts.get(header);
        if(previous!=null&&!previous.equals(fact))return false;
        boolean renderer=c.receipts.containsKey(header);
        if(renderer&&!Boolean.TRUE.equals(c.receipts.get(header)))return false;
        boolean additional=previous==null&&!renderer;
        if(additional&&(c.resetRequested||remembered(c)>=limits.rememberedIds())){
            c.resetRequested=true;return false;
        }
        try{sender.run();}
        catch(RuntimeException uncertain){
            connections.remove(player,c);
            throw new IllegalStateException("J06 personal RECORD delivery uncertain; session revoked",uncertain);
        }
        if(previous==null){c.recordFacts.put(header,fact);if(additional)c.recordOnlyAuthorities++;}
        if(remembered(c)>=limits.rememberedIds())c.resetRequested=true;
        return true;
    }
    /** Immutable source bytes for a receipt, including an earlier received heartbeat.
     * Renewal already fixes kind/asset/parameters/transform/instance for this effect. */
    public Optional<PerceptionUpsert> receivedEffect(PlayerId player,PerceptionHeader header){
        tick();var c=connections.get(player);
        if(c==null||!current(c,header)||!Boolean.TRUE.equals(c.receipts.get(header)))return Optional.empty();
        var actual=c.sent.get(header.effectId());if(actual==null)return Optional.empty();
        return Optional.of(new PerceptionUpsert(header,actual.kind(),actual.transform(),actual.assetId(),actual.assetHash(),actual.parameters()));
    }
    /** Validator is the server actor's live range/role/anchor-state check, never a client assertion. */
    public boolean interaction(PlayerId sender,AnchorInteraction interaction,Predicate<AnchorInteraction> liveValidator,Consumer<AnchorInteraction> accepted){
        long t=tick();var c=connections.get(sender);if(c==null||!inbound(c)||!current(c,interaction.header()))return false;expire(c,t);
        // An unchanged heartbeat may overtake a click already sent by the client.
        // Exact historical send provenance is necessary, but never replaces live authority.
        if(!c.receipts.containsKey(interaction.header())||!c.expires.containsKey(interaction.header().effectId())||!liveValidator.test(interaction))return false;
        accepted.accept(interaction);return true;
    }
    public boolean admin(PlayerId player,SessionId claimed){tick();var c=connections.get(player);if(c==null||c.capabilities==null||!c.adminSession.equals(claimed)||!inbound(c))return false;
        long window=lastTick/20;if(c.adminWindow!=window){c.adminWindow=window;c.adminCount=0;}return ++c.adminCount<=limits.adminMessagesPerSecond();}
    public boolean adminQuery(PlayerId player,SessionId claimed){tick();var c=connections.get(player);if(c==null||c.capabilities==null||!c.adminSession.equals(claimed)||!inbound(c))return false;
        if(c.lastQuery>=0&&lastTick-c.lastQuery<limits.adminRefreshTicks())return false;c.lastQuery=lastTick;return true;}
}
