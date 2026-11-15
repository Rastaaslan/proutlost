package fr.proutlost.director.adapter.neoforge.network;

import fr.proutlost.director.api.model.*;
import fr.proutlost.director.client.perception.PerceptionManager;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static fr.proutlost.director.adapter.neoforge.network.NetworkTests.*;

/** Review 2: tests renderer/audio entry calls, not native audio or GPU behavior. */
final class DelayedHeartbeatTests {
    private static PerceptionUpsert message(PerceptionKind kind,long sequence,long ttl,long id){
        var base=identified(effect(1,sequence,ttl),id);
        return new PerceptionUpsert(base.header(),kind,base.transform(),ASSET,HASH,
            kind==PerceptionKind.SOUND?base.parameters():new ApparitionParameters(new DurationTicks(80),24));
    }
    private static final class LifecycleSink implements PerceptionManager.Effects {
        final List<PerceptionUpsert> starts=new ArrayList<>();
        int updates,stops;
        public boolean available(PerceptionUpsert e){return true;}
        public void start(PerceptionUpsert e){starts.add(e);}
        public void update(PerceptionUpsert e){updates++;}
        public void stop(EffectId id){stops++;}
    }
    private static void expiredRenewal(PerceptionKind kind,boolean explicitTick){
        var clock=new AtomicLong();var sink=new LifecycleSink();var acks=new ArrayList<PerceptionAck>();
        var manager=new PerceptionManager(sink,new PerceptionManager.Limits(2,2,2,1200),clock::get,acks::add);
        manager.session(session(1),DIM);
        manager.upsert(message(kind,1,20,1));
        clock.set(10);manager.upsert(message(kind,2,20,1));
        check(sink.starts.size()==1&&sink.updates==1,"live heartbeat preserves original "+kind+" lifecycle");
        clock.set(30);if(explicitTick)manager.tick();
        // Both a ticked expiry and an expiry discovered by upsert must be terminal.
        manager.upsert(message(kind,3,1200,1));
        check(manager.activeCount()==0&&sink.stops==1,"delayed renewal leaves expired "+kind+" inactive");
        check(sink.starts.size()==1&&sink.updates==1,"no audio replay or new apparition follow initialization after expiry: "+kind);
        check(acks.getLast().header().sequence().value()==3&&acks.getLast().status()==PerceptionAckStatus.EXPIRED,"delayed renewal explicitly ACKed EXPIRED");
        clock.set(200);
        manager.upsert(message(kind,4,1200,1));manager.upsert(message(kind,3,1200,1));
        check(sink.starts.size()==1&&manager.activeCount()==0,"later and reordered heartbeats cannot restore follow allowance: "+kind);
        // A separate admitted effect can start. Historical expiry consumes only its original ID slot.
        manager.upsert(message(kind,5,20,2));
        check(sink.starts.size()==2&&sink.starts.getLast().header().effectId().equals(new EffectId(new UUID(5,2))),"new effect identity starts normally: "+kind);
        clock.set(220);manager.tick();manager.upsert(message(kind,6,20,1));
        check(sink.starts.size()==2&&manager.activeCount()==0,"retired history survives another effect expiry");
        manager.upsert(message(kind,7,20,3));
        check(manager.needsSessionReset()&&sink.starts.size()==2,"retired identities remain within shared rememberedIds capacity");
    }
    private static void endedByControl(){
        for(var scope:PerceptionResetScope.values()){
            var clock=new AtomicLong();var sink=new LifecycleSink();var manager=new PerceptionManager(sink,new PerceptionManager.Limits(2,2,8,1200),clock::get,a->{});
            manager.session(session(1),DIM);manager.upsert(message(PerceptionKind.SOUND,1,20,1));
            manager.reset(new PerceptionReset(identified(header(1,2,0),1),scope));
            manager.upsert(message(PerceptionKind.SOUND,3,20,1));
            check(sink.starts.size()==1&&manager.activeCount()==0,"higher sequence cannot restart effect ended by "+scope+" reset");
        }
        var clock=new AtomicLong();var sink=new LifecycleSink();var manager=new PerceptionManager(sink,new PerceptionManager.Limits(2,2,8,1200),clock::get,a->{});
        manager.session(session(1),DIM);manager.upsert(message(PerceptionKind.APPARITION,1,20,1));
        manager.stop(new PerceptionStop(identified(header(1,2,0),1),false));
        manager.upsert(message(PerceptionKind.APPARITION,3,20,1));
        check(sink.starts.size()==1&&manager.activeCount()==0,"higher sequence cannot restart stopped apparition follow");
        manager.session(session(2),DIM);manager.upsert(message(PerceptionKind.APPARITION,4,20,1));
        check(sink.starts.size()==1,"old epoch cannot bypass retirement through session reset");
    }
    static void run(){
        for(var kind:List.of(PerceptionKind.SOUND,PerceptionKind.APPARITION)){
            expiredRenewal(kind,true);expiredRenewal(kind,false);
        }
        endedByControl();
    }
}
