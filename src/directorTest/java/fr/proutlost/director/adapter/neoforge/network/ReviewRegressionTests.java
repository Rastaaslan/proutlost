package fr.proutlost.director.adapter.neoforge.network;

import fr.proutlost.director.api.model.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import net.neoforged.bus.api.*;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import static fr.proutlost.director.adapter.neoforge.network.NetworkTests.*;

/** Review 1 regressions. Uses the real event bus; no Minecraft server or world is launched. */
final class ReviewRegressionTests {
    private static PerceptionTransport transport(AtomicLong clock,int capacity,List<Object> sent){
        return new PerceptionTransport(WORLD,new Epoch(1),Set.of(PerceptionKind.SOUND),Map.of(ASSET,HASH),
            new PerceptionTransport.Limits(2,1,10,1200,capacity,2,40),clock::get,(player,dto)->sent.add(dto));
    }
    private static void negotiate(PerceptionTransport t,SessionIdentity s){
        check(t.capabilities(s.playerId(),new Capabilities(1,s.connectionEpoch(),Set.of(PerceptionKind.SOUND),Map.of(ASSET,HASH))),"review negotiation");
    }
    private static RecordObservation observation(PerceptionHeader h){
        return new RecordObservation(h,new RecordObservationPayload(Optional.of(h.effectId()),Optional.empty(),Optional.of("explicit choice")));
    }
    static void receiptsAcrossRenewals(){
        var clock=new AtomicLong();var sent=new ArrayList<Object>();var journal=new ArrayList<RecordObservation>();
        var t=transport(clock,3,sent);var player=new PlayerId(new UUID(20,20));var other=new PlayerId(new UUID(21,21));
        var session=t.open(player,DIM);negotiate(t,session);t.open(other,DIM);
        t.upsert(new PerceptionIntent(session,player,effect(1,1,20)));
        t.upsert(new PerceptionIntent(session,player,effect(1,2,20)));
        check(!t.record(player,observation(header(1,1,20)),f->false,journal::add),"sent is not received evidence");
        check(t.acknowledge(player,new PerceptionAck(header(1,1,20),PerceptionAckStatus.RECEIVED)),"sequence 1 ACK after sequence 2 renewal");
        check(t.record(player,observation(header(1,1,20)),f->false,journal::add)&&journal.size()==1,"sequence 1 journal action after renewal");
        t.upsert(new PerceptionIntent(session,player,effect(1,3,20)));
        check(t.record(player,observation(header(1,1,20)),f->false,journal::add),"further heartbeat preserves existing receipt");
        check(!t.record(player,observation(header(1,2,20)),f->false,journal::add),"receipt is per exact header, not entire effect");
        check(t.acknowledge(player,new PerceptionAck(header(1,2,20),PerceptionAckStatus.RECEIVED)),"sequence 2 ACK after sequence 3 renewal");
        check(t.record(player,observation(header(1,2,20)),f->false,journal::add),"sequence 2 journal action after renewal");
        check(t.acknowledge(player,new PerceptionAck(header(1,1,20),PerceptionAckStatus.EXPIRED)),"later expiry is accepted technical status");
        check(t.record(player,observation(header(1,1,20)),f->false,journal::add),"later expiry cannot erase received evidence");
        check(t.acknowledge(player,new PerceptionAck(header(1,3,20),PerceptionAckStatus.ERROR)),"error status accepted for known transmission");
        check(!t.record(player,observation(header(1,3,20)),f->false,journal::add),"ERROR does not establish receipt");
        int before=sent.size();
        reject(()->t.upsert(new PerceptionIntent(session,player,effect(1,4,20))),"bounded provenance refuses renewal at capacity");
        check(sent.size()==before,"capacity refusal emits no effect");
        check(t.record(player,observation(header(1,1,20)),f->false,journal::add),"capacity refusal never evicts old receipt");
        check(!t.acknowledge(player,new PerceptionAck(header(1,4,20),PerceptionAckStatus.RECEIVED)),"unsent sequence rejected");
        check(!t.acknowledge(player,new PerceptionAck(header(1,1,19),PerceptionAckStatus.RECEIVED)),"altered historical TTL rejected");
        check(!t.acknowledge(other,new PerceptionAck(header(1,1,20),PerceptionAckStatus.RECEIVED)),"historical ACK sender spoof rejected");
        check(!t.record(other,observation(header(1,1,20)),f->false,journal::add),"historical journal sender spoof rejected");
        var spoof=new PerceptionHeader(1,WORLD,new Epoch(1),new Epoch(1),DIM,new EventInstanceId(new UUID(99,99)),EFFECT,new Sequence(1),new DurationTicks(20));
        check(!t.acknowledge(player,new PerceptionAck(spoof,PerceptionAckStatus.RECEIVED)),"historical instance spoof rejected");
        check(!t.record(player,observation(spoof),f->false,journal::add),"historical journal header spoof rejected");
        clock.set(40); // No live effect remains; delayed receipt still describes an actual prior send.
        check(t.acknowledge(player,new PerceptionAck(header(1,3,20),PerceptionAckStatus.RECEIVED)),"late receipt after TTL");
        t.stop(player,new PerceptionStop(header(1,4,0),false));
        check(t.record(player,observation(header(1,3,20)),f->false,journal::add),"journal provenance survives Stop");
        check(!t.interaction(player,new AnchorInteraction(header(1,3,20),new AnchorId(new UUID(1,3)),ASSET),a->true,a->{throw new AssertionError("stopped interaction");}),"historical receipt does not authorize live interaction");
        t.open(player,DIM);
        check(!t.acknowledge(player,new PerceptionAck(header(1,1,20),PerceptionAckStatus.RECEIVED)),"previous-session delayed ACK rejected");
        check(!t.record(player,observation(header(1,1,20)),f->false,journal::add),"previous-session journal rejected");
        t.close(player);
        check(!t.acknowledge(player,new PerceptionAck(header(1,1,20),PerceptionAckStatus.RECEIVED)),"disconnected receipt rejected");
    }
    static void finalDeathOutcome(){
        var clock=new AtomicLong();var sent=new ArrayList<Object>();var t=transport(clock,10,sent);
        var player=new PlayerId(new UUID(30,30));var session=t.open(player,DIM);negotiate(t,session);
        var cleanup=new DeferredDeathCleanup(t);var bus=BusBuilder.builder().build();var laterCancel=new AtomicBoolean(true);
        var dispatched=new AtomicInteger();
        bus.addListener(EventPriority.NORMAL,true,LivingDeathEvent.class,e->{
            check(!e.isCanceled(),"death initially uncancelled at normal priority");
            cleanup.observe(player,()->!e.isCanceled());dispatched.incrementAndGet();
        });
        bus.addListener(EventPriority.LOWEST,LivingDeathEvent.class,e->{if(laterCancel.get())e.setCanceled(true);});
        // Null entity/source are sufficient for the real cancellable event; no live entity is accessed.
        var cancelled=bus.post(new LivingDeathEvent(null,null));
        check(dispatched.get()==1&&cancelled.isCanceled(),"later real event listener cancelled death");
        cleanup.drain();
        check(t.identity(player).filter(session::equals).isPresent(),"cancelled death preserves original session");
        t.upsert(new PerceptionIntent(session,player,effect(1,1,20)));
        check(sent.getLast().equals(effect(1,1,20)),"survivor retains negotiated usable transport");
        laterCancel.set(false);bus.post(new LivingDeathEvent(null,null));
        check(t.identity(player).isPresent(),"no cleanup inside death dispatch");cleanup.drain();
        check(t.identity(player).isEmpty(),"final uncancelled death closes session at drain");
        var old=t.open(player,DIM);cleanup.observe(player,()->true);var respawn=t.open(player,DIM);cleanup.drain();
        check(!old.equals(respawn)&&t.identity(player).filter(respawn::equals).isPresent(),"old queued death cannot close respawn epoch");
        cleanup.observe(player,()->false);cleanup.drain();check(t.identity(player).isPresent(),"no completed death preserves session");
        cleanup.observe(player,()->true);cleanup.forget(player);cleanup.drain();check(t.identity(player).isPresent(),"logout invalidates queued check");
        cleanup.observe(player,()->true);cleanup.clear();cleanup.drain();check(t.identity(player).isPresent(),"shutdown releases queued outcome references");
    }
    static void delayedAnchor(){
        for(String ending:List.of("STOP","TTL","PLAYER","DIMENSION","INSTANCE","RECONNECT")){
            var clock=new AtomicLong();var t=transport(clock,8,new ArrayList<>());
            var player=new PlayerId(new UUID(41,41));var session=t.open(player,DIM);negotiate(t,session);
            t.upsert(new PerceptionIntent(session,player,effect(1,1,20)));
            var click=new AnchorInteraction(header(1,1,20),new AnchorId(new UUID(42,42)),ASSET);
            t.upsert(new PerceptionIntent(session,player,effect(1,2,40)));
            var validated=new AtomicInteger();var accepted=new AtomicInteger();
            check(t.interaction(player,click,a->{validated.incrementAndGet();return true;},a->accepted.incrementAndGet()),"earlier emitted click reaches current validator after heartbeat: "+ending);
            check(validated.get()==1&&accepted.get()==1,"exactly one validator and dispatch");
            check(!t.interaction(player,click,a->{validated.incrementAndGet();return false;},a->accepted.incrementAndGet()),"current role/range/state rejection retained");
            for(var forged:List.of(header(1,9,20),header(1,1,19))){
                check(!t.interaction(player,new AnchorInteraction(forged,click.anchorId(),ASSET),a->{throw new AssertionError("fabricated header reached validator");},a->{}),"nonemitted header rejected");
            }
            if(ending.equals("STOP"))t.stop(player,new PerceptionStop(header(1,3,0),false));
            else if(ending.equals("TTL"))clock.set(40);
            else if(ending.equals("RECONNECT"))t.open(player,DIM);
            else t.reset(player,new PerceptionReset(header(1,3,0),PerceptionResetScope.valueOf(ending)));
            check(!t.interaction(player,click,a->{throw new AssertionError("terminated identity reached validator");},a->{}),"delayed click rejected after "+ending);
            check(accepted.get()==1,"no authority granted after termination");
        }
    }
    static void run(){receiptsAcrossRenewals();finalDeathOutcome();delayedAnchor();}
}
