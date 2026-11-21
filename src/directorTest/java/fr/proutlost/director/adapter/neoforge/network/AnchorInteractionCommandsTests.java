package fr.proutlost.director.adapter.neoforge.network;

import fr.proutlost.director.api.model.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Declared synthetic input. Real wire codec and transport; no Minecraft or durability claim. */
public final class AnchorInteractionCommandsTests {
    private static int assertions;
    private static final WorldId WORLD=new WorldId(new UUID(1,2));
    private static final PlayerId PLAYER=new PlayerId(new UUID(3,4));
    private static final DimensionId DIM=new DimensionId("minecraft:overworld");
    private static final EventInstanceId EVENT=new EventInstanceId(new UUID(5,6));
    private static final EffectId EFFECT=new EffectId(new UUID(7,8));
    private static final AnchorId ANCHOR=new AnchorId(new UUID(9,10));
    private static final ResourceId ACTION=DirectorAnchorInteractionCommands.action("listen");
    private static final ResourceId ASSET=new ResourceId("minecraft:block.gravel.step");
    private static final Fingerprint HASH=new Fingerprint("a".repeat(64));
    private static void check(boolean condition,String name){assertions++;if(!condition)throw new AssertionError(name);}
    private static void reject(Runnable operation,String name){assertions++;try{operation.run();}catch(IllegalArgumentException|NullPointerException expected){return;}throw new AssertionError(name);}
    private static SessionIdentity session(){return new SessionIdentity(WORLD,new Epoch(1),new Epoch(1),DIM,PLAYER);}
    private static PerceptionHeader header(){return new PerceptionHeader(1,WORLD,new Epoch(1),new Epoch(1),DIM,EVENT,EFFECT,new Sequence(17),new DurationTicks(80));}
    private static DirectorAnchorInteractionCommands.Context context(){return new DirectorAnchorInteractionCommands.Context(EVENT,ANCHOR,EFFECT,new Sequence(17));}
    private static PerceptionHeader replace(WorldId world,Epoch boot,Epoch connection,DimensionId dim,EventInstanceId event,EffectId effect,Sequence sequence,DurationTicks ttl){
        return new PerceptionHeader(1,world,boot,connection,dim,event,effect,sequence,ttl);
    }
    private static void contextAndWire(){
        var h=header();var c=context();var request=DirectorAnchorInteractionCommands.interaction(session(),h,c,ACTION);
        check(request.header()==h,"exact received header object retained, no synthesized TTL");
        check(request.anchorId().equals(ANCHOR)&&request.actionId().equals(ACTION),"selected references retained");
        check(DirectorAnchorInteractionCommands.parse(EVENT.value().toString(),ANCHOR.value().toString(),EFFECT.value().toString(),17).equals(c),"canonical button reference parsed");
        for(boolean catalogue:List.of(false,true))for(String action:List.of("listen","leave_trace")){
            var line=DirectorAnchorInteractionCommands.button(catalogue,h,ANCHOR,DirectorAnchorInteractionCommands.action(action));
            String prefix=catalogue?"/proutlost choice select ":"/proutlost director lab choice select ";
            check(line.startsWith(prefix),"existing route family retained");
            var args=line.substring(prefix.length()).split(" ");
            check(args.length==5&&args[1].equals(action),"closed action and context suffix");
            check(DirectorAnchorInteractionCommands.parse(args[0],args[2],args[3],Long.parseLong(args[4])).equals(c),"button roundtrip context");
            check(line.length()<256,"button within vanilla command bound");
        }
        var codec=new WireCodec<>(AnchorInteraction.class,8188);var bytes=codec.encode(request);
        check(codec.decode(bytes).equals(request),"existing C2S schema roundtrip unchanged");
        check(bytes.length<8188,"existing C2S limit retained");
        reject(()->codec.decode(Arrays.copyOf(bytes,bytes.length-1)),"truncated payload refused");
        reject(()->codec.decode(Arrays.copyOf(bytes,bytes.length+1)),"trailing payload refused");
        reject(()->DirectorAnchorInteractionCommands.parse("1-1-1-1-1",ANCHOR.value().toString(),EFFECT.value().toString(),17),"abbreviated UUID refused");
        reject(()->DirectorAnchorInteractionCommands.parse(EVENT.value().toString(),ANCHOR.value().toString(),EFFECT.value().toString(),-1),"negative sequence refused");
        reject(()->DirectorAnchorInteractionCommands.action("arbitrary"),"unknown command action refused");
        reject(()->DirectorAnchorInteractionCommands.button(true,h,ANCHOR,new ResourceId("other:action")),"unknown action cannot enter button");
        reject(()->DirectorAnchorInteractionCommands.interaction(session(),null,c,ACTION),"no cached receipt cannot create DTO");
        reject(()->DirectorAnchorInteractionCommands.interaction(session(),h,c,new ResourceId("other:action")),"unknown outbound action refused");
        check(!DirectorAnchorInteractionCommands.matches(c,h,new AnchorId(new UUID(11,12))),"server compatibility checks actual anchor");
        var altered=List.of(
            replace(new WorldId(new UUID(20,1)),h.bootEpoch(),h.connectionEpoch(),DIM,EVENT,EFFECT,h.sequence(),h.remainingTtlTicks()),
            replace(WORLD,new Epoch(2),h.connectionEpoch(),DIM,EVENT,EFFECT,h.sequence(),h.remainingTtlTicks()),
            replace(WORLD,h.bootEpoch(),new Epoch(2),DIM,EVENT,EFFECT,h.sequence(),h.remainingTtlTicks()),
            replace(WORLD,h.bootEpoch(),h.connectionEpoch(),new DimensionId("minecraft:the_nether"),EVENT,EFFECT,h.sequence(),h.remainingTtlTicks()),
            replace(WORLD,h.bootEpoch(),h.connectionEpoch(),DIM,new EventInstanceId(new UUID(21,1)),EFFECT,h.sequence(),h.remainingTtlTicks()),
            replace(WORLD,h.bootEpoch(),h.connectionEpoch(),DIM,EVENT,new EffectId(new UUID(22,1)),h.sequence(),h.remainingTtlTicks()),
            replace(WORLD,h.bootEpoch(),h.connectionEpoch(),DIM,EVENT,EFFECT,new Sequence(18),h.remainingTtlTicks()),
            replace(WORLD,h.bootEpoch(),h.connectionEpoch(),DIM,EVENT,EFFECT,h.sequence(),new DurationTicks(0)));
        for(var changed:altered)reject(()->DirectorAnchorInteractionCommands.interaction(session(),changed,c,ACTION),"changed session/reference rejected: "+changed);
    }
    private static void realTransportGuards(){
        var time=new AtomicLong();var sent=new ArrayList<Object>();var accepted=new ArrayList<AnchorInteraction>();
        var transport=new PerceptionTransport(WORLD,new Epoch(1),Set.of(PerceptionKind.SOUND),Map.of(ASSET,HASH),
            new PerceptionTransport.Limits(2,2,100,1200,128,2,40),time::get,(p,dto)->sent.add(dto));
        var s=transport.open(PLAYER,DIM);
        check(transport.capabilities(PLAYER,new Capabilities(1,s.connectionEpoch(),Set.of(PerceptionKind.SOUND),Map.of(ASSET,HASH))),"real transport negotiated");
        var h=header();var effect=new PerceptionUpsert(h,PerceptionKind.SOUND,new PerceptionTransform(1,64,2,0,0,1),ASSET,HASH,new SoundParameters(.4,1,12));
        transport.upsert(new PerceptionIntent(s,PLAYER,effect));
        var request=DirectorAnchorInteractionCommands.interaction(s,h,context(),ACTION);
        check(!(transport.received(PLAYER,request.header())&&transport.interaction(PLAYER,request,v->true,accepted::add))&&accepted.isEmpty(),"native received prerequisite rejects SENT-only provenance");
        check(transport.acknowledge(PLAYER,new PerceptionAck(h,PerceptionAckStatus.RECEIVED)),"exact actual receive acknowledged");
        check(!transport.interaction(PLAYER,request,v->false,accepted::add)&&accepted.isEmpty(),"role/range/fingerprint validator denial retained");
        var codec=new WireCodec<>(AnchorInteraction.class,8188);var decoded=codec.decode(codec.encode(request));
        check(transport.interaction(PLAYER,decoded,v->v.equals(request),accepted::add)&&accepted.equals(List.of(request)),"decoded true C2S joins real live transport validation");
        var other=new PlayerId(new UUID(40,1));transport.open(other,DIM);
        check(!transport.interaction(other,request,v->{throw new AssertionError("wrong recipient reached live validator");},accepted::add),"other player cannot borrow header");
        time.set(81);
        check(transport.received(PLAYER,h),"historical receipt retained after visual expiry");
        check(!transport.interaction(PLAYER,request,v->{throw new AssertionError("expired choice reached validator");},accepted::add),"live interaction still refused after TTL");
        var newer=transport.open(PLAYER,DIM);
        check(!transport.interaction(PLAYER,request,v->{throw new AssertionError("old session reached validator");},accepted::add),"reconnect invalidates old header");
        reject(()->DirectorAnchorInteractionCommands.interaction(newer,h,context(),ACTION),"client also fences old connection");
        check(accepted.size()==1,"all denied paths leave one accepted input only");
    }
    private static void boundedClientReceipts(){
        reject(()->new DirectorAnchorInteractionReceipts(129),"no increase beyond128");
        reject(()->new DirectorAnchorInteractionReceipts(0),"positive cache bound");
        var cache=new DirectorAnchorInteractionReceipts(128);var h=header();
        for(int i=0;i<128;i++)cache.remember(replace(WORLD,h.bootEpoch(),h.connectionEpoch(),DIM,EVENT,
            new EffectId(new UUID(70,i)),h.sequence(),h.remainingTtlTicks()),0);
        check(cache.size()==128,"filled at fixed bound");
        var oldest=new DirectorAnchorInteractionCommands.Context(EVENT,ANCHOR,new EffectId(new UUID(70,0)),h.sequence());
        check(cache.received(oldest,0)!=null,"prior current receipt available");
        check(cache.remember(h,0)&&cache.size()==128,"new choice receipt admitted at saturation without raising bound");
        check(cache.received(context(),0)==h,"saturated historical cache cannot hide new choice header");
        check(cache.received(oldest,0)==null,"oldest transient hint evicted only");
        check(cache.remember(h,79),"identical duplicate received");
        check(cache.received(context(),79)==h,"unexpired exact header available");
        check(cache.received(context(),80)==null&&cache.size()==0,"duplicate ACK did not extend TTL");
        check(cache.remember(h,80),"new local cache entry only after real received callback");
        cache.clear();check(cache.size()==0&&cache.received(context(),80)==null,"session reset revokes cache");
        cache.remember(h,80);
        var wrongBoot=replace(WORLD,new Epoch(9),h.connectionEpoch(),DIM,EVENT,EFFECT,new Sequence(18),h.remainingTtlTicks());
        cache.stopped(new PerceptionStop(wrongBoot,false));
        check(cache.received(context(),80)==h,"old-session stop cannot revoke current hint");
        cache.stopped(new PerceptionStop(replace(WORLD,h.bootEpoch(),h.connectionEpoch(),DIM,EVENT,EFFECT,new Sequence(16),h.remainingTtlTicks()),false));
        check(cache.received(context(),80)==h,"older sequence stop cannot revoke newer hint");
        cache.stopped(new PerceptionStop(h,false));
        check(cache.received(context(),80)==null,"current effect stop revokes live hint");
        var olderReset=replace(WORLD,h.bootEpoch(),h.connectionEpoch(),DIM,EVENT,EFFECT,new Sequence(16),h.remainingTtlTicks());
        for(var scope:PerceptionResetScope.values()) {
            cache.remember(h,80);cache.reset(new PerceptionReset(olderReset,scope));
            check(cache.received(context(),80)==h,"older "+scope+" reset preserves newer received effect like renderer");
        }
        cache.remember(h,80);cache.reset(new PerceptionReset(h,PerceptionResetScope.INSTANCE));
        check(cache.size()==0,"instance reset revokes hint");
        cache.remember(h,80);cache.reset(new PerceptionReset(h,PerceptionResetScope.PLAYER));
        check(cache.size()==0,"player reset revokes hint");
        cache.remember(h,80);cache.reset(new PerceptionReset(h,PerceptionResetScope.DIMENSION));
        check(cache.size()==0,"dimension reset revokes hint");
        reject(()->cache.tick(79),"cache clock rollback refused");
        check(!cache.remember(replace(WORLD,h.bootEpoch(),h.connectionEpoch(),DIM,EVENT,EFFECT,h.sequence(),new DurationTicks(0)),80),"RECORD-only envelope never enters live cache");
        check(!cache.remember(replace(WORLD,h.bootEpoch(),h.connectionEpoch(),DIM,EVENT,EFFECT,h.sequence(),new DurationTicks(1201)),80),"live TTL bound retained");
        check(cache.size()==0,"invalid live headers allocate no entries");
    }
    public static void main(String[] args){contextAndWire();realTransportGuards();boundedClientReceipts();System.out.println("AnchorInteractionCommandsTests PASS assertions="+assertions+" fixture=SYNTHETIC scope=PURE_WIRE_AND_TRANSPORT_AND_BOUNDED_RECEIPTS native=NOT_RUN J04=NOT_RUN");}
}
