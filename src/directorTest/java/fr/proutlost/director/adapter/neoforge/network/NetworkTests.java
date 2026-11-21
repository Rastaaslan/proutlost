package fr.proutlost.director.adapter.neoforge.network;

import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.codec.PerceptionHeaderCodec;
import fr.proutlost.director.client.perception.PerceptionManager;
import fr.proutlost.director.client.perception.VerifiedAssetNegotiation;
import fr.proutlost.director.client.perception.PendingClientReset;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.core.RegistryAccess;
import io.netty.buffer.Unpooled;

/** Executable domain tests; sinks are test-only counters, never gameplay implementations. */
public final class NetworkTests {
    static int assertions;
    static final WorldId WORLD=new WorldId(new UUID(1,2));
    static final DimensionId DIM=new DimensionId("minecraft:overworld");
    static final EventInstanceId INSTANCE=new EventInstanceId(new UUID(3,4));
    static final EffectId EFFECT=new EffectId(new UUID(5,6));
    static final ResourceId ASSET=new ResourceId("minecraft:block.gravel.step");
    static final Fingerprint HASH=new Fingerprint("a".repeat(64));
    static PerceptionHeader header(long epoch,long seq,long ttl){return new PerceptionHeader(1,WORLD,new Epoch(1),new Epoch(epoch),DIM,INSTANCE,EFFECT,new Sequence(seq),new DurationTicks(ttl));}
    static PerceptionUpsert effect(long epoch,long seq,long ttl){return new PerceptionUpsert(header(epoch,seq,ttl),PerceptionKind.SOUND,new PerceptionTransform(1,64,2,0,0,1),ASSET,HASH,new SoundParameters(.4,1,12));}
    static PerceptionHeader identified(PerceptionHeader h,long id){return new PerceptionHeader(h.protocolVersion(),h.worldId(),h.bootEpoch(),h.connectionEpoch(),h.dimension(),h.instanceId(),new EffectId(new UUID(5,id)),h.sequence(),h.remainingTtlTicks());}
    static PerceptionUpsert identified(PerceptionUpsert e,long id){return new PerceptionUpsert(identified(e.header(),id),e.kind(),e.transform(),e.assetId(),e.assetHash(),e.parameters());}
    static SessionReset session(long epoch){return new SessionReset(1,WORLD,new Epoch(1),new Epoch(epoch),EnumSet.allOf(PerceptionKind.class),Map.of(ASSET,HASH));}
    static void check(boolean condition,String reason){assertions++;if(!condition)throw new AssertionError(reason);}
    static void reject(Runnable action,String reason){assertions++;try{action.run();}catch(IllegalArgumentException|ArithmeticException e){return;}throw new AssertionError("accepted: "+reason);}
    static <T> void codec(T value,Class<T> type,int maximum){
        var codec=new WireCodec<>(type,maximum);byte[] bytes=codec.encode(value);check(value.equals(codec.decode(bytes)),"roundtrip "+type);
        for(int n=0;n<bytes.length;n++){byte[] truncated=Arrays.copyOf(bytes,n);reject(()->codec.decode(truncated),"truncated "+type+" "+n);}
        reject(()->codec.decode(Arrays.copyOf(bytes,bytes.length+1)),"trailing "+type);
        reject(()->codec.decode(new byte[maximum+1]),"oversize");
    }
    static void codecs(){
        var h=header(1,1,20);
        codec(session(1),SessionReset.class,24576);
        codec(new Capabilities(1,new Epoch(1),Set.of(PerceptionKind.SOUND),Map.of(ASSET,HASH)),Capabilities.class,8192);
        var params=List.<PerceptionParameters>of(new SoundParameters(1,1,1),new ParticleParameters(32,1),new ApparitionParameters(new DurationTicks(80),24),new WeatherParameters(ASSET,new UnitValue(.5)),new GhostParameters(ASSET,new UnitValue(.5)),new SymbolParameters(ASSET,new UnitValue(.5)));
        for(int i=0;i<params.size();i++)codec(new PerceptionUpsert(h,PerceptionKind.values()[i],effect(1,1,20).transform(),ASSET,HASH,params.get(i)),PerceptionUpsert.class,24576);
        codec(new PerceptionStop(h,true),PerceptionStop.class,24576);
        for(var scope:PerceptionResetScope.values())codec(new PerceptionReset(h,scope),PerceptionReset.class,24576);
        for(var status:PerceptionAckStatus.values())codec(new PerceptionAck(h,status),PerceptionAck.class,8192);
        codec(new RecordObservation(h,new RecordObservationPayload(Optional.of(EFFECT),Optional.empty(),Optional.of("choisi é 🌍"))),RecordObservation.class,8192);
        codec(new AnchorInteraction(h,new AnchorId(new UUID(7,8)),ASSET),AnchorInteraction.class,8192);
        var sid=new SessionId(new UUID(1,1));var request=new RequestId(new UUID(2,2));
        codec(new AdminQuery(sid,request,AdminPanel.WORLD,new WorldFilter(),Optional.empty(),Optional.empty(),50),AdminQuery.class,8192);
        codec(new AdminCommand(sid,request,new Revision(1),AdminOperation.PAUSE,new ControlPayload(RunControl.PAUSED)),AdminCommand.class,8192);
        codec(new AdminSnapshot(request,new Revision(1),new Tick(1),new Sequence(1),List.of(),Optional.empty(),Set.of(AdminOperation.PAUSE)),AdminSnapshot.class,24576);
        codec(new AdminCommandResult(request,AdminStatus.PENDING,List.of(),Optional.empty(),Optional.empty()),AdminCommandResult.class,24576);
        codec(new AdminDelta(new UUID(9,9),new Revision(1),new Revision(2),List.of()),AdminDelta.class,24576);
        var mutationId=new MutationId(new UUID(7,11));
        var mutationResource=new ResourceKey(OwnershipDomain.BLOCK,DIM,"1,64,2");
        var mutationOwner=new OwnerToken(new UUID(7,12));
        var mutationBlob=BlobContent.capture(new ResourceId("test:mutation_state"),1,new byte[]{1,2});
        var inspected=new MutationRecord(new AggregateHeader(1,mutationId.value(),new Revision(1),new Sequence(1),new Tick(1)),
            mutationId,INSTANCE,new SliceId(new UUID(7,13)),MutationState.VERIFIED,new PrimitiveId("proutlost:trace"),1,
            new Footprint(DIM,Set.of(),Set.of(new BlockPosition(DIM,1,64,2)),List.of()),
            mutationBlob.reference(),mutationBlob.reference(),mutationOwner,new Epoch(1),
            new ReadSet(Map.of(),Map.of(mutationResource,new Epoch(1)),Map.of(mutationResource,mutationBlob.reference().hash())),
            Set.of(),List.of(),Optional.empty(),new Sequence(1));
        var currentOwner=new OwnershipRecord(new AggregateHeader(1,new UUID(7,14),new Revision(1),new Sequence(1),new Tick(1)),
            mutationResource,Set.of(mutationOwner),new Epoch(1),OwnershipDisposition.DIRECTOR_ACTIVE,
            mutationId,mutationBlob.reference().hash(),new Epoch(0),Optional.empty());
        codec(new AdminQuery(sid,request,AdminPanel.ACTIVE,new MutationFilter(mutationId),Optional.empty(),Optional.empty(),1),AdminQuery.class,8192);
        codec(new AdminCommand(sid,request,new Revision(1),AdminOperation.MUTATION_INSPECT,new MutationInspectionPayload(mutationId)),AdminCommand.class,8192);
        var mutationReply=new AdminSnapshot(request,new Revision(1),new Tick(1),new Sequence(1),
            List.of(new AdminRow(new ScopeRef(ScopeKind.EVENT,INSTANCE.value()),new Revision(1),new Tick(1),
                new MutationAdminData(inspected,new OwnershipView(Map.of(mutationResource,currentOwner)),Set.of(mutationResource)))),
            Optional.empty(),Set.of(AdminOperation.MUTATION_INSPECT));
        codec(mutationReply,AdminSnapshot.class,24576);
        var mutationChannel=new DirectorPayloads(24576,8192).channel(AdminSnapshot.class);
        var mutationBuffer=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);
        try{
            mutationChannel.codec().encode(mutationBuffer,new DirectorPayloads.Packet<>(mutationChannel.type(),mutationReply));
            check(mutationChannel.codec().decode(mutationBuffer).value().equals(mutationReply),"real StreamCodec roundtrip of explicit mutation/ownership references");
        }finally{mutationBuffer.release();}

        var wire=new WireCodec<>(PerceptionUpsert.class,24576);byte[] bytes=wire.encode(effect(1,1,20));
        byte[] goldenHeader=PerceptionHeaderCodec.encode(h);check(Arrays.equals(goldenHeader,Arrays.copyOf(bytes,goldenHeader.length)),"frozen binary header exact");
        byte[] overflow=bytes.clone();Arrays.fill(overflow,0,10,(byte)255);reject(()->wire.decode(overflow),"VarLong overflow");
        byte[] overlong=new byte[bytes.length+1];overlong[0]=(byte)129;System.arraycopy(bytes,1,overlong,2,bytes.length-1);reject(()->wire.decode(overlong),"noncanonical version");
        byte[] version=bytes.clone();version[0]=2;reject(()->wire.decode(version),"unknown version");
        var longDim=new PerceptionHeader(1,WORLD,new Epoch(1),new Epoch(1),new DimensionId("minecraft:"+"a".repeat(256)),INSTANCE,EFFECT,new Sequence(1),new DurationTicks(20));
        reject(()->wire.encode(new PerceptionUpsert(longDim,PerceptionKind.SOUND,effect(1,1,20).transform(),ASSET,HASH,new SoundParameters(1,1,1))),"normative dimension bytes");
        reject(()->new SoundParameters(Double.NaN,1,1),"nonfinite parameter");
        var random=new Random(208);int rejected=0;
        for(int n=0;n<2000;n++){byte[] fuzz=new byte[random.nextInt(1024)];random.nextBytes(fuzz);try{wire.decode(fuzz);}catch(IllegalArgumentException|ArithmeticException e){rejected++;}}
        check(rejected==2000,"seeded random malformed inputs rejected");
        var transport=new DirectorPayloads(24576,8192);var channel=transport.channel(PerceptionUpsert.class);
        var buffer=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);
        try{channel.codec().encode(buffer,new DirectorPayloads.Packet<>(channel.type(),effect(1,1,20)));check(channel.codec().decode(buffer).value().equals(effect(1,1,20)),"real StreamCodec roundtrip");}finally{buffer.release();}
        for(int length:new int[]{-1,24577,Integer.MAX_VALUE}){var bad=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);try{bad.writeInt(length);reject(()->channel.codec().decode(bad),"StreamCodec allocation bound");}finally{bad.release();}}
        var json=new WireCodec<>(AdminQuery.class,8192);byte[] query=json.encode(new AdminQuery(sid,request,AdminPanel.WORLD,new WorldFilter(),Optional.empty(),Optional.empty(),1));
        String unknown=new String(query,java.nio.charset.StandardCharsets.UTF_8).replaceFirst("\\{","{\"unknown\":true,");reject(()->json.decode(unknown.getBytes(java.nio.charset.StandardCharsets.UTF_8)),"closed admin fields");
    }
    static final class Sink implements PerceptionManager.Effects {
        int starts,updates,stops;boolean available=true;Set<EffectId> live=new HashSet<>();
        public boolean available(PerceptionUpsert e){return available;}
        public void start(PerceptionUpsert e){starts++;live.add(e.header().effectId());}
        public void update(PerceptionUpsert e){updates++;}
        public void stop(EffectId id){stops++;live.remove(id);}
    }
    static void deferredNativeLoginReset(){
        var inbox=new PendingClientReset();
        var first=new SessionReset(1,WORLD,new Epoch(1),new Epoch(1),
            Set.of(),Map.of());
        var next=new SessionReset(1,WORLD,new Epoch(1),new Epoch(2),
            Set.of(),Map.of());
        check(inbox.offer(first)&&inbox.hasPending(),
            "client must retain SessionReset sent before level spawn");
        check(inbox.takeIfReady(false,false).isEmpty()&&inbox.hasPending(),
            "missing ClientLevel must not consume reset");
        check(inbox.takeIfReady(true,false).isEmpty()&&inbox.hasPending(),
            "existing ClientLevel without LocalPlayer must not bind F8");
        check(!inbox.offer(first)&&inbox.offer(next),
            "newer pending epoch must replace older, not vice versa");
        check(!inbox.offer(first)&&inbox.takeIfReady(true,true)
            .orElseThrow().connectionEpoch().equals(new Epoch(2)),
            "older packet must not override genuine newest login");
        check(!inbox.hasPending()&&!inbox.offer(next),
            "consumed epoch is not allowed to reopen itself");
        var boot=new SessionReset(1,WORLD,new Epoch(2),new Epoch(1),
            Set.of(),Map.of());
        check(inbox.offer(boot)&&inbox.takeIfReady(true,true)
            .orElseThrow().bootEpoch().equals(new Epoch(2)),
            "legitimate J04 new boot permits resetting connection counter");
        check(!inbox.offer(next),
            "stale prior-boot reset may not revive client perception");
        inbox.offer(new SessionReset(1,new WorldId(UUID.randomUUID()),
            new Epoch(1),new Epoch(1),Set.of(),Map.of()));
        inbox.clear(); // Native LoggingOut clears packet from old socket.
        check(!inbox.hasPending()&&inbox.offer(first)&&
            inbox.takeIfReady(true,true).isPresent(),
            "disconnect must clear old pending/accepted world fences");
    }
    static void negotiatedVerifiedAssets(){
        var sound=new ResourceId("minecraft:block.campfire.crackle");
        var ghost=new ResourceId("proutlost_director:verified_ghost");
        var particle=new ResourceId("proutlost_director:unregistered_particle");
        var shaSound=new Fingerprint("a".repeat(64));
        var shaGhost=new Fingerprint("b".repeat(64));
        var shaWrong=new Fingerprint("f".repeat(64));
        var offer=new SessionReset(1,WORLD,new Epoch(1),new Epoch(7),
            Set.of(PerceptionKind.SOUND,PerceptionKind.GHOST),
            Map.of(sound,shaSound,ghost,shaGhost));
        var none=VerifiedAssetNegotiation.reply(offer,Map.of());
        check(none.supportedEffects().isEmpty()&&none.assetHashes().isEmpty()
            &&none.connectionEpoch().equals(new Epoch(7)),
            "empty local asset registry MUST advertise no effects");
        var onlyOne=VerifiedAssetNegotiation.reply(offer,Map.of(
            sound,new VerifiedAssetNegotiation.Local(PerceptionKind.SOUND,shaSound),
            ghost,new VerifiedAssetNegotiation.Local(PerceptionKind.GHOST,shaWrong),
            particle,new VerifiedAssetNegotiation.Local(PerceptionKind.PARTICLES,shaSound)));
        check(onlyOne.supportedEffects().equals(Set.of(PerceptionKind.SOUND))&&
            onlyOne.assetHashes().equals(Map.of(sound,shaSound)),
            "unmatched asset hash or unauthorized kind leaked capabilities");
        var onlyGhost=VerifiedAssetNegotiation.reply(offer,Map.of(
            ghost,new VerifiedAssetNegotiation.Local(PerceptionKind.GHOST,shaGhost)));
        check(onlyGhost.supportedEffects().equals(Set.of(PerceptionKind.GHOST))&&
            onlyGhost.assetHashes().equals(Map.of(ghost,shaGhost)),
            "a second client cannot be credited with the first client's assets");
        var disabled=new SessionReset(1,WORLD,new Epoch(1),new Epoch(7),
            Set.of(PerceptionKind.GHOST),Map.of(sound,shaSound,ghost,shaGhost));
        check(VerifiedAssetNegotiation.reply(disabled,Map.of(
            sound,new VerifiedAssetNegotiation.Local(PerceptionKind.SOUND,shaSound)))
            .assetHashes().isEmpty(),"server disabled sound but client announced it");
        var noManifest=new SessionReset(1,WORLD,new Epoch(1),new Epoch(7),
            Set.of(PerceptionKind.SOUND),Map.of());
        check(VerifiedAssetNegotiation.reply(noManifest,Map.of(
            sound,new VerifiedAssetNegotiation.Local(PerceptionKind.SOUND,shaSound)))
            .supportedEffects().isEmpty(),"asset not in server manifest accepted");
        var oversized=new HashMap<ResourceId,VerifiedAssetNegotiation.Local>();
        for(int i=0;i<129;i++)oversized.put(new ResourceId(
            "proutlost_director:asset_"+i),
            new VerifiedAssetNegotiation.Local(PerceptionKind.SOUND,shaSound));
        reject(()->VerifiedAssetNegotiation.reply(offer,oversized),
            "oversized local asset registry");
        var clock=new AtomicLong();
        var outputs=new ArrayList<Object>();
        var server=new PerceptionTransport(WORLD,new Epoch(1),
            Set.of(PerceptionKind.SOUND,PerceptionKind.GHOST),
            Map.of(sound,shaSound,ghost,shaGhost),
            new PerceptionTransport.Limits(2,32,10,1200,128,2,40),
            clock::get,(to,payload)->outputs.add(payload));
        var alice=new PlayerId(new UUID(10,11));
        var bob=new PlayerId(new UUID(12,13));
        var a=server.open(alice,DIM);
        var b=server.open(bob,DIM);
        var offerAlice=new SessionReset(1,WORLD,new Epoch(1),
            a.connectionEpoch(),offer.authorizedEffects(),offer.authorizedAssets());
        var offerBob=new SessionReset(1,WORLD,new Epoch(1),
            b.connectionEpoch(),offer.authorizedEffects(),offer.authorizedAssets());
        var capA=VerifiedAssetNegotiation.reply(offerAlice,Map.of(
            sound,new VerifiedAssetNegotiation.Local(PerceptionKind.SOUND,shaSound)));
        var capB=VerifiedAssetNegotiation.reply(offerBob,Map.of(
            ghost,new VerifiedAssetNegotiation.Local(PerceptionKind.GHOST,shaGhost)));
        check(server.capabilities(alice,capA)&&server.capabilities(bob,capB),
            "two independent negotiated client responses were rejected");
        var effect=new PerceptionUpsert(new PerceptionHeader(1,WORLD,
            new Epoch(1),a.connectionEpoch(),DIM,INSTANCE,EFFECT,
            new Sequence(1),new DurationTicks(20)),PerceptionKind.SOUND,
            new PerceptionTransform(1,64,2,0,0,1),sound,shaSound,
            new SoundParameters(.5,1,10));
        server.upsert(new PerceptionIntent(a,alice,effect));
        check(outputs.getLast().equals(effect),
            "sound was not delivered to the actual capable client");
        var forBob=new PerceptionUpsert(new PerceptionHeader(1,WORLD,
            new Epoch(1),b.connectionEpoch(),DIM,INSTANCE,EFFECT,
            new Sequence(1),new DurationTicks(20)),PerceptionKind.SOUND,
            effect.transform(),sound,shaSound,effect.parameters());
        reject(()->server.upsert(new PerceptionIntent(b,bob,forBob)),
            "sound delivered to client with only ghost asset verified");
        check(server.capabilities(alice).orElseThrow().supportedEffects()
                .equals(Set.of(PerceptionKind.SOUND))&&
            server.capabilities(bob).orElseThrow().supportedEffects()
                .equals(Set.of(PerceptionKind.GHOST)),
            "two-client asset capability isolation lost");
        server.close(alice);server.close(bob);
    }
    static void clients(){
        var clock=new AtomicLong();var a=new Sink();var b=new Sink();var ack=new ArrayList<PerceptionAck>();
        var limits=new PerceptionManager.Limits(32,2,128,1200);
        var one=new PerceptionManager(a,limits,clock::get,ack::add);var two=new PerceptionManager(b,limits,clock::get,x->{});
        check(one.session(session(1),DIM)&&two.session(session(2),DIM),"initial sessions");
        one.upsert(effect(1,1,20));two.upsert(effect(1,1,20));check(a.starts==1&&b.starts==0,"recipient epoch isolation (pure manager)");
        clock.set(10);one.upsert(effect(1,1,20));check(a.starts==1&&a.updates==0,"retry no replay");clock.set(20);one.tick();check(one.activeCount()==0&&a.stops==1,"TTL not extended by duplicate");
        one.upsert(effect(1,1,20));check(a.starts==1,"expired duplicate cannot resurrect");
        one.upsert(identified(effect(1,2,20),2));clock.set(30);one.upsert(identified(effect(1,3,20),2));clock.set(40);one.tick();check(one.activeCount()==1&&a.updates==1,"heartbeat extends live TTL");clock.set(50);one.tick();check(one.activeCount()==0,"lost stop TTL cleanup");
        one.stop(new PerceptionStop(identified(header(1,9,0),3),false));one.upsert(identified(effect(1,8,20),3));check(one.activeCount()==0,"stop before upsert tombstone");
        int beforeStoppedLate=a.starts;
        one.upsert(identified(effect(1,10,20),3));
        check(one.activeCount()==0&&a.starts==beforeStoppedLate&&ack.getLast().status()==PerceptionAckStatus.EXPIRED,
            "STOP for unseen effect must prevent later higher-sequence resurrection");
        one.upsert(identified(effect(1,10,20),4));check(one.activeCount()==1,"new admitted identity before instance reset");one.reset(new PerceptionReset(header(1,11,0),PerceptionResetScope.INSTANCE));one.upsert(identified(effect(1,10,20),4));check(one.activeCount()==0,"instance reset fence");
        one.upsert(identified(effect(1,12,20),5));check(one.activeCount()==1,"new admitted identity before player reset");one.reset(new PerceptionReset(header(1,13,0),PerceptionResetScope.PLAYER));one.upsert(identified(effect(1,12,20),5));check(one.activeCount()==0,"player reset fence");
        check(!one.session(session(1),DIM),"duplicate session reset rejected");one.invalidate();one.upsert(effect(1,14,20));check(one.activeCount()==0,"death/dimension suspension");
        check(one.session(session(3),DIM),"new epoch after respawn");one.upsert(effect(1,100,20));check(one.activeCount()==0,"old epoch");
        a.available=false;one.upsert(effect(3,1,20));check(ack.getLast().status()==PerceptionAckStatus.UNSUPPORTED&&one.activeCount()==0,"missing asset explicit");
        a.available=true;one.upsert(effect(3,2,1201));check(ack.getLast().status()==PerceptionAckStatus.ERROR,"oversized TTL");
        one.upsert(effect(3,3,20));one.disconnect();check(one.activeCount()==0&&a.live.isEmpty(),"disconnect releases");
        one.session(session(4),new DimensionId("minecraft:the_nether"));one.upsert(effect(4,1,20));check(one.activeCount()==0,"dimension isolation");
        var tiny=new PerceptionManager(new Sink(),new PerceptionManager.Limits(1,1,1,100),clock::get,x->{});tiny.session(session(1),DIM);tiny.upsert(effect(1,1,10));
        var other=new PerceptionHeader(1,WORLD,new Epoch(1),new Epoch(1),DIM,INSTANCE,new EffectId(new UUID(0,99)),new Sequence(2),new DurationTicks(10));
        tiny.upsert(new PerceptionUpsert(other,PerceptionKind.SOUND,effect(1,1,10).transform(),ASSET,HASH,new SoundParameters(1,1,1)));
        check(tiny.needsSessionReset()&&tiny.activeCount()==0,"fence saturation fails closed");
    }
    static void rendererFailureQuarantine(){
        var time=new AtomicLong();
        var received=new ArrayList<PerceptionAck>();
        final class Renderer implements PerceptionManager.Effects {
            boolean failStart,failUpdate;
            int starts,updates,stops;
            public boolean available(PerceptionUpsert effect){return true;}
            public void start(PerceptionUpsert effect){
                starts++;
                if(failStart)throw new IllegalStateException("partial render startup");
            }
            public void update(PerceptionUpsert effect){
                updates++;
                if(failUpdate)throw new IllegalStateException("partial update");
            }
            public void stop(EffectId id){stops++;}
        }
        var renderer=new Renderer();
        var manager=new PerceptionManager(renderer,
            new PerceptionManager.Limits(4,2,8,1200),
            time::get,received::add);
        check(manager.session(session(1),DIM),"renderer quarantine session");
        renderer.failStart=true;
        manager.upsert(identified(effect(1,1,20),21));
        check(manager.activeCount()==0&&renderer.starts==1&&renderer.stops==1
                &&received.getLast().status()==PerceptionAckStatus.ERROR,
            "partial startup stopped once and acknowledged ERROR");
        renderer.failStart=false;
        manager.upsert(identified(effect(1,2,20),21));
        check(manager.activeCount()==0&&renderer.starts==1
                &&received.getLast().status()==PerceptionAckStatus.EXPIRED,
            "failed startup identity cannot be resurrected by a newer heartbeat");
        manager.upsert(identified(effect(1,3,20),22));
        check(manager.activeCount()==1&&renderer.starts==2,"new identity may start");
        renderer.failUpdate=true;
        manager.upsert(identified(effect(1,4,20),22));
        check(manager.activeCount()==0&&renderer.updates==1&&renderer.stops==2
                &&received.getLast().status()==PerceptionAckStatus.ERROR,
            "failed update cleans live effect exactly once");
        renderer.failUpdate=false;
        manager.upsert(identified(effect(1,5,20),22));
        check(manager.activeCount()==0&&renderer.updates==1
                &&received.getLast().status()==PerceptionAckStatus.EXPIRED,
            "failed update identity remains terminal");
    }
    static void rendererStopFailureFailsClosed(){
        var time=new AtomicLong();
        class FaultyRenderer implements PerceptionManager.Effects {
            final Set<EffectId> live=new HashSet<>();
            final List<EffectId> stopAttempts=new ArrayList<>();
            EffectId throwsFor;
            boolean failAvailability;
            public boolean available(PerceptionUpsert value){
                if(failAvailability)throw new IllegalStateException("asset probe failed");
                return true;
            }
            public void start(PerceptionUpsert value){live.add(value.header().effectId());}
            public void update(PerceptionUpsert value){}
            public void stop(EffectId id){
                stopAttempts.add(id);
                if(id.equals(throwsFor))throw new IllegalStateException("renderer physical stop failed");
                live.remove(id);
            }
        }
        var renderer=new FaultyRenderer();
        var manager=new PerceptionManager(renderer,new PerceptionManager.Limits(4,2,8,1200),
            time::get,x->{});
        var first=identified(effect(1,1,20),51);
        var second=identified(effect(1,1,20),52);
        check(manager.session(session(1),DIM),"stop-fault fixture session");
        manager.upsert(first);manager.upsert(second);
        check(manager.activeCount()==2,"both renderers initially live");
        renderer.throwsFor=first.header().effectId();
        check(!manager.session(session(2),DIM),"session reset cannot clear broken physical renderer");
        check(manager.activeCount()==0 && manager.needsSessionReset()
                && renderer.stopAttempts.size()==2
                && renderer.stopAttempts.contains(first.header().effectId())
                && renderer.stopAttempts.contains(second.header().effectId()),
            "renderer stop fault must still drain all logical effects and attempt both stops");
        check(!renderer.live.contains(second.header().effectId()),
            "second renderer stop must be attempted even if first failed");
        manager.upsert(identified(effect(2,2,20),53));
        check(manager.activeCount()==0 && renderer.live.size()==1,
            "renderer fault must prevent any later effect from rendering");
        manager.disconnect();
        check(!manager.session(session(3),DIM) && manager.needsSessionReset(),
            "disconnect and newer epochs must not silently clear renderer fault");

        var another=new FaultyRenderer();
        var acks=new ArrayList<PerceptionAck>();
        var direct=new PerceptionManager(another,new PerceptionManager.Limits(4,2,8,1200),
            time::get,acks::add);
        check(direct.session(session(1),DIM),"direct stop-fault fixture");
        var directEffect=identified(effect(1,1,10),61);
        direct.upsert(directEffect);
        another.throwsFor=directEffect.header().effectId();
        direct.stop(new PerceptionStop(identified(header(1,2,0),61),false));
        check(direct.activeCount()==0 && direct.needsSessionReset()
                && another.stopAttempts.size()==1,
            "explicit STOP with throwing renderer must fail closed without bubbling exception");
        time.set(20);
        direct.tick();
        check(another.stopAttempts.size()==1 && !direct.session(session(2),DIM),
            "TTL and reconnect must not retry broken renderer stop");

        var startup=new FaultyRenderer(){
            @Override public void start(PerceptionUpsert value){
                super.start(value);
                throw new IllegalStateException("partial render start failed");
            }
        };
        var startupAcks=new ArrayList<PerceptionAck>();
        var startupManager=new PerceptionManager(startup,
            new PerceptionManager.Limits(4,2,8,1200),time::get,startupAcks::add);
        startupManager.session(session(1),DIM);
        startup.throwsFor=identified(effect(1,1,20),71).header().effectId();
        startupManager.upsert(identified(effect(1,1,20),71));
        check(startupManager.activeCount()==0 && startupManager.needsSessionReset()
                && startup.stopAttempts.size()==1
                && startupAcks.getLast().status()==PerceptionAckStatus.ERROR,
            "failed first render plus failed cleanup must still acknowledge ERROR and quarantine");

        var lookup=new FaultyRenderer();
        var lookupAcks=new ArrayList<PerceptionAck>();
        var lookupManager=new PerceptionManager(lookup,
            new PerceptionManager.Limits(4,2,8,1200),time::get,lookupAcks::add);
        lookupManager.session(session(1),DIM);
        lookupManager.upsert(identified(effect(1,1,20),81));
        lookup.failAvailability=true;
        lookupManager.upsert(identified(effect(1,2,20),81));
        check(lookupManager.activeCount()==0 && lookup.stopAttempts.size()==1
                && lookupAcks.getLast().status()==PerceptionAckStatus.ERROR,
            "asset lookup throwing must retire old effect and acknowledge ERROR");
        lookupManager.upsert(identified(effect(1,3,20),81));
        check(lookup.stopAttempts.size()==1
                && lookupAcks.getLast().status()==PerceptionAckStatus.EXPIRED,
            "failed asset lookup identity cannot be restarted");

        var ackRenderer=new FaultyRenderer();
        var managerWithBrokenTransport=new PerceptionManager(ackRenderer,
            new PerceptionManager.Limits(4,2,8,1200),time::get,
            acknowledgement->{throw new IllegalStateException("ack disconnected");});
        check(managerWithBrokenTransport.session(session(1),DIM),
            "broken-ACK fixture session");
        managerWithBrokenTransport.upsert(identified(effect(1,1,20),91));
        check(managerWithBrokenTransport.activeCount()==0
                && managerWithBrokenTransport.needsSessionReset()
                && ackRenderer.stopAttempts.size()==1 && ackRenderer.live.isEmpty(),
            "throwing ACK must clean visual and suspend session without crashing client");
        check(managerWithBrokenTransport.session(session(2),DIM),
            "healthy renderer may recover on genuine new session epoch");
    }
    static void server(){
        var time=new AtomicLong();var sent=new ArrayList<Object>();var recipients=new ArrayList<PlayerId>();
        var t=new PerceptionTransport(WORLD,new Epoch(1),Set.of(PerceptionKind.SOUND),Map.of(ASSET,HASH),new PerceptionTransport.Limits(2,32,10,1200,128,2,40),time::get,(p,d)->{recipients.add(p);sent.add(d);});
        var a=new PlayerId(new UUID(10,10));var b=new PlayerId(new UUID(11,11));var first=t.open(a,DIM);t.open(b,DIM);
        check(sent.size()==2 && sent.get(0) instanceof SessionReset
                && sent.get(1) instanceof SessionReset,
            "two independent physical-client reset DTOs dispatched");
        var resetA=(SessionReset)sent.get(0);
        var resetB=(SessionReset)sent.get(1);
        var reconstructedClientA=new SessionIdentity(resetA.worldId(),resetA.bootEpoch(),
            resetA.connectionEpoch(),DIM,a);
        var reconstructedClientB=new SessionIdentity(resetB.worldId(),resetB.bootEpoch(),
            resetB.connectionEpoch(),DIM,b);
        check(reconstructedClientA.equals(first)
                &&DirectorAdminSessionKey.of(reconstructedClientA).equals(
                    PerceptionTransport.adminSession(first)),
            "F8 client A reconstructs identical auth-correlation from received reset");
        check(DirectorAdminSessionKey.of(reconstructedClientB).equals(
                PerceptionTransport.adminSession(t.identity(b).orElseThrow()))
                &&!DirectorAdminSessionKey.of(reconstructedClientB).equals(
                    DirectorAdminSessionKey.of(reconstructedClientA)),
            "second F8 client cannot borrow first client session");
        check(!t.admin(a,PerceptionTransport.adminSession(first)) &&
            !t.adminQuery(a,PerceptionTransport.adminSession(first)),
            "pre-handshake F8 and WAL queries refused");
        reject(()->t.upsert(new PerceptionIntent(first,a,effect(1,1,20))),"no negotiation");
        check(!t.capabilities(a,new Capabilities(1,new Epoch(99),Set.of(PerceptionKind.SOUND),Map.of(ASSET,HASH))),"old negotiation");
        check(t.capabilities(a,new Capabilities(1,new Epoch(1),Set.of(PerceptionKind.SOUND),Map.of(ASSET,HASH))),"negotiation accepted");
        check(t.capabilities(a,new Capabilities(1,new Epoch(1),
                Set.of(PerceptionKind.SOUND),Map.of(ASSET,HASH))),
            "idempotent negotiation retry accepted");
        check(!t.capabilities(a,new Capabilities(1,new Epoch(1),Set.of(PerceptionKind.SOUND),Map.of()))
            && t.capabilities(a).orElseThrow().assetHashes().equals(Map.of(ASSET,HASH)),
            "client cannot replace nonempty asset permissions in place");
        check(!t.adminQuery(b,PerceptionTransport.adminSession(
                t.identity(b).orElseThrow())),
            "second client cannot use WAL read before its own negotiation");
        t.upsert(new PerceptionIntent(first,a,effect(1,1,20)));check(recipients.getLast().equals(a)&&sent.getLast().equals(effect(1,1,20)),"targeted transport");
        var ack=new PerceptionAck(header(1,1,20),PerceptionAckStatus.RECEIVED);
        check(!t.acknowledge(b,ack),"forged sender ACK rejected");check(t.acknowledge(a,ack),"matched receipt");
        check(t.received(a,ack.header())&&!t.received(b,ack.header()),"renderer receipt belongs only to actual recipient");
        check(!t.received(a,header(1,2,20)),"another sequence cannot borrow receipt");
        var journal=new ArrayList<RecordObservation>();check(journal.isEmpty(),"ACK is not opt-in");
        var observation=new RecordObservation(header(1,1,20),new RecordObservationPayload(Optional.of(EFFECT),Optional.empty(),Optional.of("my choice")));
        check(t.record(a,observation,f->false,journal::add)&&journal.size()==1,"explicit journal action");
        check(!t.record(b,observation,f->false,journal::add)&&journal.size()==1,"journal spoof rejected");
        var interactions=new ArrayList<AnchorInteraction>();var action=new AnchorInteraction(header(1,1,20),new AnchorId(new UUID(7,8)),ASSET);
        check(!t.interaction(a,action,x->false,interactions::add)&&interactions.isEmpty(),"live validator required");
        check(t.interaction(a,action,x->true,interactions::add)&&interactions.size()==1,"validated anchor");
        var actor=t.actor(a,4);
        // The physical F8 client derives its key from SessionReset. J06's
        // server actor and the J07 J04 read bridge MUST use the same key.
        // A mismatched algorithm silently rejected every real F8 query.
        var physicalF8Key=DirectorAdminSessionKey.of(first);
        check(physicalF8Key.equals(actor.session())
                &&physicalF8Key.equals(PerceptionTransport.adminSession(first)),
            "physical F8 / J06 / J07 admin session correlation");
        check(!physicalF8Key.equals(DirectorAdminSessionKey.of(t.identity(b).orElseThrow())),
            "admin session key must distinguish connected players");
        check(t.admin(a,actor.session()),"server authenticated admin session");check(!t.admin(b,actor.session()),"admin session spoof");
        check(t.admin(a,actor.session())&&!t.admin(a,actor.session()),"admin command limit");
        check(t.adminQuery(a,actor.session())&&!t.adminQuery(a,actor.session()),"admin refresh limit");
        check(!t.adminQuery(b,actor.session()),"cross-player admin WAL query rejected");
        check(actor.session().equals(PerceptionTransport.adminSession(first)),"reproducible public admin session");
        for(int i=2;i<=10;i++)t.upsert(new PerceptionIntent(first,a,effect(1,i,20)));
        reject(()->t.upsert(new PerceptionIntent(first,a,effect(1,11,20))),"outbound backpressure");
        t.stop(a,new PerceptionStop(header(1,11,0),false));check(sent.getLast() instanceof PerceptionStop,"cleanup bypasses cosmetic rate budget");
        reject(()->t.upsert(new PerceptionIntent(first,a,effect(1,12,20))),"stopped identity not renewed");
        time.set(39);
        check(!t.adminQuery(a,actor.session()),"admin WAL read cannot bypass 40-tick refresh");
        time.set(40);
        check(t.adminQuery(a,actor.session()),"admin WAL read admitted after 40 ticks");
        var second=t.open(a,DIM);
        check(!t.received(a,ack.header()),"reconnection revokes warning receipt");check(second.connectionEpoch().value()>first.connectionEpoch().value(),"reconnect epoch advances");
        check(!physicalF8Key.equals(DirectorAdminSessionKey.of(second))
                &&DirectorAdminSessionKey.of(second).equals(PerceptionTransport.adminSession(second)),
            "reconnect rotates the exact real F8/server/J07 key");
        check(!t.adminQuery(a,actor.session()) &&
            !t.adminQuery(a,PerceptionTransport.adminSession(second)),
            "reconnect rejects stale and unnegotiated J07 sessions");
        check(t.capabilities(a,new Capabilities(1,second.connectionEpoch(),
                Set.of(PerceptionKind.SOUND),Map.of(ASSET,HASH))) &&
            t.adminQuery(a,PerceptionTransport.adminSession(second)),
            "fresh J06 negotiation restores only rotated J07 authority");
        check(!t.acknowledge(a,ack),"late ACK after reset");reject(()->t.upsert(new PerceptionIntent(first,a,effect(1,12,20))),"old admission");
        t.close(a);check(t.identity(a).isEmpty(),"disconnect removes state");
    }
    /** Real sender failure can occur after a packet was queued on the server actor.
     * Never leave a usable J06/F8 session or a fabricated send/ACK provenance. */
    static void senderFailureQuarantine() {
        var clock=new AtomicLong();
        var failReset=new java.util.concurrent.atomic.AtomicBoolean(true);
        var failUpsert=new java.util.concurrent.atomic.AtomicBoolean();
        var failStop=new java.util.concurrent.atomic.AtomicBoolean();
        var failCleanup=new java.util.concurrent.atomic.AtomicBoolean();
        var alice=new PlayerId(new UUID(51,52));
        var t=new PerceptionTransport(WORLD,new Epoch(1),Set.of(PerceptionKind.SOUND),
            Map.of(ASSET,HASH),new PerceptionTransport.Limits(2,8,10,1200,16,2,40),
            clock::get,(recipient,payload)->{
                if(payload instanceof SessionReset && failReset.get()
                    || payload instanceof PerceptionUpsert && failUpsert.get()
                    || payload instanceof PerceptionStop && failStop.get()
                    || payload instanceof PerceptionReset && failCleanup.get())
                    throw new IllegalStateException("native packet sink unavailable");
            });
        try { t.open(alice,DIM); throw new AssertionError("failed SessionReset accepted"); }
        catch(IllegalStateException expected) { }
        check(t.identity(alice).isEmpty(),"failed SessionReset left authenticated F8 session");
        failReset.set(false);
        var a=t.open(alice,DIM);
        check(a.connectionEpoch().value()>1,"failed reset did not consume connection epoch");
        check(t.capabilities(alice,new Capabilities(1,a.connectionEpoch(),
            Set.of(PerceptionKind.SOUND),Map.of(ASSET,HASH))),"first negotiation");
        var effectA=effect(a.connectionEpoch().value(),1,20);
        failUpsert.set(true);
        try {t.upsert(new PerceptionIntent(a,alice,effectA));
            throw new AssertionError("failed upsert accepted");}
        catch(IllegalStateException expected) { }
        check(t.identity(alice).isEmpty(),"failed upsert retained authorizable client");
        check(!t.acknowledge(alice,new PerceptionAck(effectA.header(),PerceptionAckStatus.RECEIVED)),
            "failed packet could be falsely ACKed");
        failUpsert.set(false);
        var b=t.open(alice,DIM);
        check(b.connectionEpoch().compareTo(a.connectionEpoch())>0,"upsert failure reused session");
        check(t.capabilities(alice,new Capabilities(1,b.connectionEpoch(),
            Set.of(PerceptionKind.SOUND),Map.of(ASSET,HASH))),"second negotiation");
        var effectB=effect(b.connectionEpoch().value(),1,20);
        t.upsert(new PerceptionIntent(b,alice,effectB));
        failStop.set(true);
        try {t.stop(alice,new PerceptionStop(header(b.connectionEpoch().value(),2,0),false));
            throw new AssertionError("failed Stop accepted");}
        catch(IllegalStateException expected) { }
        check(t.identity(alice).isEmpty(),"failed stop left stale live client");
        failStop.set(false);
        var c=t.open(alice,DIM);
        check(t.capabilities(alice,new Capabilities(1,c.connectionEpoch(),
            Set.of(PerceptionKind.SOUND),Map.of(ASSET,HASH))),"third negotiation");
        t.upsert(new PerceptionIntent(c,alice,effect(c.connectionEpoch().value(),1,20)));
        failCleanup.set(true);
        try {t.reset(alice,new PerceptionReset(header(c.connectionEpoch().value(),2,0),
            PerceptionResetScope.PLAYER));throw new AssertionError("failed cleanup accepted");}
        catch(IllegalStateException expected) { }
        check(t.identity(alice).isEmpty(),"failed Reset left old F8/perception session");
    }
    /** Unit transport fixture only: no native renderer or human receipt is claimed. */
    static void liveWarningReceipts() {
        var time=new AtomicLong();
        var profile=new ResourceId("proutlost_director:warning_symbol");
        var otherProfile=new ResourceId("proutlost_director:another_symbol");
        var transform=new PerceptionTransform(1,64,2,0,0,1);
        var moved=new PerceptionTransform(2,64,2,0,0,1);
        var sent=new ArrayList<Object>();
        var t=new PerceptionTransport(WORLD,new Epoch(1),Set.of(PerceptionKind.SYMBOL),
            Map.of(profile,HASH,otherProfile,HASH),
            new PerceptionTransport.Limits(2,8,40,100,16,2,20),
            time::get,(player,payload)->sent.add(payload));
        var a=new PlayerId(new UUID(71,72));
        var b=new PlayerId(new UUID(73,74));
        var session=t.open(a,DIM);t.open(b,DIM);
        check(t.capabilities(a,new Capabilities(1,session.connectionEpoch(),
            Set.of(PerceptionKind.SYMBOL),Map.of(profile,HASH,otherProfile,HASH))),
            "live warning fixture negotiated SYMBOL");
        var original=new PerceptionUpsert(header(1,1,20),PerceptionKind.SYMBOL,
            transform,profile,HASH,new SymbolParameters(profile,new UnitValue(1)));
        t.upsert(new PerceptionIntent(session,a,original));
        check(sent.getLast().equals(original),"actual SYMBOL send recorded by fixture sink");
        check(!t.warningReceivedAndLive(a,original.header(),profile,transform),
            "sent warning alone is not received");
        check(!t.acknowledge(b,new PerceptionAck(original.header(),PerceptionAckStatus.RECEIVED)),
            "another recipient cannot acknowledge perimeter");
        check(t.acknowledge(a,new PerceptionAck(original.header(),PerceptionAckStatus.RECEIVED)),
            "actual matching fixture acknowledgement accepted");
        check(t.warningReceivedAndLive(a,original.header(),profile,transform),
            "exact current live received symbol authorizes perimeter");
        check(!t.warningReceivedAndLive(b,original.header(),profile,transform),
            "live warning is scoped to recipient");
        check(!t.warningReceivedAndLive(a,original.header(),otherProfile,transform),
            "another profile cannot borrow receipt");
        check(!t.warningReceivedAndLive(a,original.header(),profile,moved),
            "another perimeter position cannot borrow receipt");
        check(!t.warningReceivedAndLive(a,header(1,2,20),profile,transform),
            "another sequence cannot borrow receipt");
        time.set(1);
        var renewed=new PerceptionUpsert(header(1,2,20),PerceptionKind.SYMBOL,
            transform,profile,HASH,new SymbolParameters(profile,new UnitValue(1)));
        t.upsert(new PerceptionIntent(session,a,renewed));
        check(t.received(a,original.header())
            &&!t.warningReceivedAndLive(a,original.header(),profile,transform),
            "old renewal receipt remains historical but is not current live authority");
        check(!t.warningReceivedAndLive(a,renewed.header(),profile,transform),
            "new renewal requires its own exact receipt");
        check(t.acknowledge(a,new PerceptionAck(renewed.header(),PerceptionAckStatus.RECEIVED))
            &&t.warningReceivedAndLive(a,renewed.header(),profile,transform),
            "renewed symbol becomes live only after its own receipt");
        t.stop(a,new PerceptionStop(header(1,3,0),false));
        check(t.received(a,original.header())&&t.received(a,renewed.header()),
            "Stop preserves historical original and renewal receipts");
        check(!t.warningReceivedAndLive(a,renewed.header(),profile,transform),
            "Stop revokes live warning authority");
        var shortHeader=identified(header(1,4,5),91);
        var shortWarning=new PerceptionUpsert(shortHeader,PerceptionKind.SYMBOL,
            transform,profile,HASH,new SymbolParameters(profile,new UnitValue(1)));
        t.upsert(new PerceptionIntent(session,a,shortWarning));
        check(t.acknowledge(a,new PerceptionAck(shortHeader,PerceptionAckStatus.RECEIVED))
            &&t.warningReceivedAndLive(a,shortHeader,profile,transform),
            "short-lived warning initially received and live");
        time.set(6);
        check(t.received(a,shortHeader)
            &&!t.warningReceivedAndLive(a,shortHeader,profile,transform),
            "exact expiry tick revokes live authority while preserving receipt");
        var wrongSymbolHeader=identified(header(1,5,5),92);
        var wrongSymbol=new PerceptionUpsert(wrongSymbolHeader,PerceptionKind.SYMBOL,
            transform,profile,HASH,new SymbolParameters(otherProfile,new UnitValue(1)));
        t.upsert(new PerceptionIntent(session,a,wrongSymbol));
        check(t.acknowledge(a,new PerceptionAck(wrongSymbolHeader,PerceptionAckStatus.RECEIVED))
            &&!t.warningReceivedAndLive(a,wrongSymbolHeader,profile,transform),
            "symbol parameters must match the sealed warning profile");
        var currentHeader=identified(header(1,6,5),93);
        var currentWarning=new PerceptionUpsert(currentHeader,PerceptionKind.SYMBOL,
            transform,profile,HASH,new SymbolParameters(profile,new UnitValue(1)));
        t.upsert(new PerceptionIntent(session,a,currentWarning));
        check(t.acknowledge(a,new PerceptionAck(currentHeader,PerceptionAckStatus.RECEIVED))
            &&t.warningReceivedAndLive(a,currentHeader,profile,transform),
            "independent current marker is live before reconnect");
        var reconnected=t.open(a,DIM);
        check(reconnected.connectionEpoch().compareTo(session.connectionEpoch())>0
            &&!t.received(a,currentHeader)
            &&!t.warningReceivedAndLive(a,currentHeader,profile,transform),
            "reconnect revokes all old-session live and historical receipts");
    }
    /** Exercise the real bounded transport/session machines; no native rendering is inferred. */
    static void boundedSessionRecoveryAndResourceRevocation() {
        var time=new AtomicLong();var sent=new ArrayList<Object>();var recipients=new ArrayList<PlayerId>();
        var t=new PerceptionTransport(WORLD,new Epoch(1),Set.of(PerceptionKind.SOUND),Map.of(ASSET,HASH),
            new PerceptionTransport.Limits(2,32,10,1200,128,2,40),time::get,(p,d)->{recipients.add(p);sent.add(d);});
        var a=new PlayerId(new UUID(101,1));var b=new PlayerId(new UUID(101,2));
        var initial=t.open(a,DIM);var peer=t.open(b,DIM);
        check(t.capabilities(a,new Capabilities(1,initial.connectionEpoch(),Set.of(PerceptionKind.SOUND),Map.of(ASSET,HASH))),"quota fixture negotiation");
        PerceptionHeader last=null;
        for(int i=1;i<=128;i++) {
            time.set(((i-1)/10)*20L);
            var value=identified(effect(initial.connectionEpoch().value(),2L*i-1,5),1000+i);
            t.upsert(new PerceptionIntent(initial,a,value));last=value.header();
            check(t.acknowledge(a,new PerceptionAck(last,PerceptionAckStatus.RECEIVED)),"bounded send has its own recipient receipt");
            t.stop(a,new PerceptionStop(identified(header(initial.connectionEpoch().value(),2L*i,0),1000+i),false));
        }
        var historical=last;
        check(t.rememberedHeaders(a)==128&&t.rememberedHeaders(b)==0,"128 hard ceiling is local to its recipient");
        check(t.sessionResetRequested(a)&&t.readyForSessionReset(a),"completed effects permit an automatic epoch rollover");
        reject(()->t.upsert(new PerceptionIntent(initial,a,identified(effect(initial.connectionEpoch().value(),257,5),2000))),"129th header cannot evict fences");
        check(t.rememberedHeaders(a)==128&&t.received(a,historical),"capacity refusal retains bounded old provenance until real reset");
        var renewed=t.open(a,DIM);
        check(renewed.connectionEpoch().compareTo(initial.connectionEpoch())>0&&t.identity(b).orElseThrow().equals(peer),"new epoch belongs only to the exhausted recipient");
        check(!t.received(a,historical)&&!t.acknowledge(a,new PerceptionAck(historical,PerceptionAckStatus.RECEIVED)),"old-epoch delivery cannot be revived");
        var next=identified(effect(renewed.connectionEpoch().value(),1,1200),2001);
        reject(()->t.upsert(new PerceptionIntent(renewed,a,next)),"fresh epoch needs fresh capabilities");
        check(t.capabilities(a,new Capabilities(1,renewed.connectionEpoch(),Set.of(PerceptionKind.SOUND),Map.of(ASSET,HASH))),"fresh verified handshake");
        t.upsert(new PerceptionIntent(renewed,a,next));
        check(recipients.getLast().equals(a)&&sent.getLast().equals(next),"post-limit perception is delivered only to original target");
        check(t.acknowledge(a,new PerceptionAck(next.header(),PerceptionAckStatus.RECEIVED)),"new-epoch receipt");
        var durableConsumer=new ArrayList<RecordObservation>();
        var record=new RecordObservation(next.header(),new RecordObservationPayload(Optional.of(next.header().effectId()),Optional.empty(),Optional.of("neutral opt-in")));
        check(t.record(a,record,f->false,durableConsumer::add),"opt-in occurs before reload");
        check(!t.capabilities(a,new Capabilities(1,renewed.connectionEpoch(),Set.of(),Map.of())),"empty refresh is revocation, never an in-place grant");
        check(t.identity(a).isEmpty()&&t.capabilities(a).isEmpty()&&!t.received(a,next.header()),"old resource authority is revoked atomically");
        check(!t.record(a,record,f->false,durableConsumer::add)&&durableConsumer.size()==1,"revocation neither duplicates nor deletes accepted private data");
        check(!t.readyForSessionReset(a),"client refresh cannot create epochs faster than once per second");
        time.addAndGet(20);check(t.readyForSessionReset(a),"reload handshake becomes resumable within its bounded cooldown");
        var missingAssets=t.open(a,DIM);
        check(t.capabilities(a,new Capabilities(1,missingAssets.connectionEpoch(),Set.of(),Map.of())),"an initial unsupported-asset handshake grants no effects");
        reject(()->t.upsert(new PerceptionIntent(missingAssets,a,identified(effect(missingAssets.connectionEpoch().value(),1,5),2002))),"no effect with empty verified manifest");
        check(!t.capabilities(a,new Capabilities(1,missingAssets.connectionEpoch(),Set.of(),Map.of())),"an all-unavailable client can request a fresh epoch after restoring its pack");
        time.addAndGet(20);check(t.readyForSessionReset(a),"restored-resource renewal ready");
        var restored=t.open(a,DIM);
        check(t.capabilities(a,new Capabilities(1,restored.connectionEpoch(),Set.of(PerceptionKind.SOUND),Map.of(ASSET,HASH))),"restored bytes are approved in a new epoch only");
        t.upsert(new PerceptionIntent(restored,a,identified(effect(restored.connectionEpoch().value(),1,5),2003)));
        check(durableConsumer.size()==1&&t.identity(b).orElseThrow().equals(peer),"peer state and accepted private data survive both reloads");

        // A two-step event must wait before creating its dossier at 127, not fail on step two.
        var trancheClock=new AtomicLong();var trancheSent=new ArrayList<Object>();
        var tranche=new PerceptionTransport(WORLD,new Epoch(1),Set.of(PerceptionKind.SOUND),Map.of(ASSET,HASH),
            new PerceptionTransport.Limits(2,32,10,1200,128,2,40),trancheClock::get,(p,d)->trancheSent.add(d));
        var trancheIdentity=tranche.open(a,DIM);
        tranche.capabilities(a,new Capabilities(1,trancheIdentity.connectionEpoch(),Set.of(PerceptionKind.SOUND),Map.of(ASSET,HASH)));
        PerceptionHeader held=null;
        for(int i=1;i<=127;i++){
            trancheClock.set(((i-1)/10)*20L);
            var value=identified(effect(trancheIdentity.connectionEpoch().value(),2L*i-1,1200),3000+i);
            tranche.upsert(new PerceptionIntent(trancheIdentity,a,value));held=value.header();
            if(i<127)tranche.stop(a,new PerceptionStop(identified(header(trancheIdentity.connectionEpoch().value(),2L*i,0),3000+i),false));
        }
        int before=trancheSent.size();
        check(!tranche.canSendNew(a,2)&&tranche.sessionResetRequested(a),"whole two-step tranche requests rotation before its dossier at 127");
        check(!tranche.readyForSessionReset(a)&&trancheSent.size()==before,"live advertised TTL blocks quota rollover and emits no speculative effect");
        var heldStop=new PerceptionHeader(1,WORLD,new Epoch(1),trancheIdentity.connectionEpoch(),DIM,held.instanceId(),held.effectId(),new Sequence(254),new DurationTicks(0));
        tranche.stop(a,new PerceptionStop(heldStop,false));
        check(tranche.readyForSessionReset(a),"ordinary stop releases rollover backpressure");
        var trancheNext=tranche.open(a,DIM);
        check(!tranche.canSendNew(a,2),"new epoch does not skip the existing capability handshake");
        tranche.capabilities(a,new Capabilities(1,trancheNext.connectionEpoch(),Set.of(PerceptionKind.SOUND),Map.of(ASSET,HASH)));
        check(tranche.canSendNew(a,2),"two-step event can resume after actual new epoch negotiation");
        tranche.upsert(new PerceptionIntent(trancheNext,a,identified(effect(trancheNext.connectionEpoch().value(),1,5),3301)));
        tranche.upsert(new PerceptionIntent(trancheNext,a,identified(effect(trancheNext.connectionEpoch().value(),2,5),3302)));
        check(tranche.rememberedHeaders(a)==2,"resumed tranche retains only its two new provenance headers");

        // Real warning producers need four headers per recipient; STORM also sends one wind.
        for(int required:List.of(4,5)){
            var warningTime=new AtomicLong();var warningSent=new ArrayList<PlayerId>();
            var warnings=new PerceptionTransport(WORLD,new Epoch(1),Set.of(PerceptionKind.SOUND),Map.of(ASSET,HASH),
                new PerceptionTransport.Limits(2,32,10,1200,128,2,40),warningTime::get,(p,d)->warningSent.add(p));
            var warningA=warnings.open(a,DIM);var warningB=warnings.open(b,DIM);
            warnings.capabilities(a,new Capabilities(1,warningA.connectionEpoch(),Set.of(PerceptionKind.SOUND),Map.of(ASSET,HASH)));
            warnings.capabilities(b,new Capabilities(1,warningB.connectionEpoch(),Set.of(PerceptionKind.SOUND),Map.of(ASSET,HASH)));
            int retained=129-required;
            for(int i=1;i<=retained;i++){
                warningTime.set(((i-1)/10)*20L);
                var v=identified(effect(warningA.connectionEpoch().value(),2L*i-1,5),4000+i);
                warnings.upsert(new PerceptionIntent(warningA,a,v));
                warnings.stop(a,new PerceptionStop(identified(header(warningA.connectionEpoch().value(),2L*i,0),4000+i),false));
            }
            int observed=warningSent.size();
            check(!warnings.canSendNew(a,required)&&warningSent.size()==observed&&warnings.rememberedHeaders(a)==retained,
                "whole warning/wind tranche refuses before any speculative exposure");
            check(warnings.canSendNew(b,1)&&warnings.identity(b).orElseThrow().equals(warningB),"one exhausted participant cannot rotate another recipient");
            check(warnings.readyForSessionReset(a),"closed warning tranche is resumable after bounded rotation");
            var fresh=warnings.open(a,DIM);
            warnings.capabilities(a,new Capabilities(1,fresh.connectionEpoch(),Set.of(PerceptionKind.SOUND),Map.of(ASSET,HASH)));
            check(warnings.canSendNew(a,required),"whole warning/wind tranche fits the genuine renewed epoch");
            for(int i=1;i<=required;i++)warnings.upsert(new PerceptionIntent(fresh,a,identified(effect(fresh.connectionEpoch().value(),i,5),4500+i)));
            check(warnings.rememberedHeaders(a)==required&&warnings.rememberedHeaders(b)==0,"all resumed warning/wind headers remain targeted and bounded");
        }

        var renderer=new Sink();var acks=new ArrayList<PerceptionAck>();
        var manager=new PerceptionManager(renderer,new PerceptionManager.Limits(32,2,128,1200),time::get,acks::add);
        manager.session(session(10),DIM);
        for(int i=1;i<=64;i++) {
            var h=new PerceptionHeader(1,WORLD,new Epoch(1),new Epoch(10),DIM,new EventInstanceId(new UUID(201,i)),new EffectId(new UUID(202,i)),new Sequence(2L*i-1),new DurationTicks(5));
            manager.upsert(new PerceptionUpsert(h,PerceptionKind.SOUND,effect(10,1,5).transform(),ASSET,HASH,new SoundParameters(.4,1,12)));
            var stop=new PerceptionHeader(1,WORLD,new Epoch(1),new Epoch(10),DIM,h.instanceId(),h.effectId(),new Sequence(2L*i),new DurationTicks(0));
            manager.stop(new PerceptionStop(stop,true));
        }
        check(manager.needsSessionReset(),"closed client boundary requests rollover before a 129th remembered identity");
        manager.upsert(identified(effect(10,129,5),2100));
        check(manager.needsSessionReset()&&!manager.rendererFaulted()&&manager.activeCount()==0,"client fence exhaustion is recoverable and does not masquerade as renderer failure");
        check(manager.session(session(11),DIM),"genuine new epoch clears exhausted client fences");
        manager.upsert(identified(effect(11,1,5),2101));
        check(manager.activeCount()==1,"client resumes only after new epoch");
    }
    static void explicitRecordedPerceptionUsesOnlyReceivedPrivateSource(){
        var time=new AtomicLong();var sends=new ArrayList<Object>();
        var transport=new PerceptionTransport(WORLD,new Epoch(1),Set.of(PerceptionKind.SOUND),Map.of(ASSET,HASH),
            new PerceptionTransport.Limits(2,32,10,1200,128,2,40),time::get,(p,d)->sends.add(d));
        var a=new PlayerId(new UUID(501,1));var b=new PlayerId(new UUID(501,2));
        var identity=transport.open(a,DIM);transport.open(b,DIM);
        transport.capabilities(a,new Capabilities(1,identity.connectionEpoch(),Set.of(PerceptionKind.SOUND),Map.of(ASSET,HASH)));
        var actual=effect(identity.connectionEpoch().value(),1,20);transport.upsert(new PerceptionIntent(identity,a,actual));
        check(transport.receivedEffect(a,actual.header()).isEmpty(),"queued send does not qualify a collectable source");
        check(transport.acknowledge(a,new PerceptionAck(actual.header(),PerceptionAckStatus.RECEIVED)),"actual renderer receipt qualifies source");
        check(transport.receivedEffect(b,actual.header()).isEmpty(),"other client cannot retrieve private source");
        var received=transport.receivedEffect(a,actual.header()).orElseThrow();
        var request=new RecordObservation(actual.header(),new RecordObservationPayload(Optional.of(actual.header().effectId()),Optional.empty(),Optional.of("neutral annotation")));
        var stored=DirectorRecordedPerception.fact(a,request,received,123);
        check(DirectorRecordedPerception.belongsTo(stored,a)&&!DirectorRecordedPerception.belongsTo(stored,b),"collected fact has only its actual player scope");
        check(stored.subjectRefs().equals(List.of(new ScopeRef(ScopeKind.PLAYER,a.value()))),"collection never publishes WORLD scope");
        check(DirectorRecordedPerception.sameRequest(stored,a,request,received),"retry preserves first durable identity and timestamp");
        check(stored.factId().equals(DirectorRecordedPerception.fact(a,request,received,456).factId()),"stable source identity across retries");
        var conflicting=new RecordObservation(actual.header(),new RecordObservationPayload(Optional.of(actual.header().effectId()),Optional.empty(),Optional.of("different annotation")));
        check(!DirectorRecordedPerception.sameRequest(stored,a,conflicting,received),"payload conflict cannot overwrite prior observation");
        reject(()->DirectorRecordedPerception.fact(a,new RecordObservation(header(identity.connectionEpoch().value(),2,20),request.observation()),received,123),"source/header substitution refused");
        transport.upsert(new PerceptionIntent(identity,a,effect(identity.connectionEpoch().value(),2,20)));
        check(transport.receivedEffect(a,actual.header()).orElseThrow().equals(actual),"historical received heartbeat retains exact original source header");
        transport.open(a,DIM);
        check(transport.receivedEffect(a,actual.header()).isEmpty(),"new epoch cannot collect an unverified old source");
        check(DirectorRecordedPerception.belongsTo(stored,a),"prior durable data survives independently of transport expiry");
    }
    static void personalFactReferencesRequireActualEmissionAndShareBound(){
        var time=new AtomicLong();var wire=new ArrayList<Object>();var sequence=new java.util.concurrent.atomic.AtomicLong(10);
        var transport=new PerceptionTransport(WORLD,new Epoch(1),Set.of(PerceptionKind.SOUND),Map.of(ASSET,HASH),
            new PerceptionTransport.Limits(2,32,10,1200,128,2,40),time::get,(p,d)->wire.add(d));
        var a=new PlayerId(new UUID(601,1));var b=new PlayerId(new UUID(601,2));
        var identity=transport.open(a,DIM);var other=transport.open(b,DIM);
        transport.capabilities(a,new Capabilities(1,identity.connectionEpoch(),Set.of(PerceptionKind.SOUND),Map.of(ASSET,HASH)));
        transport.capabilities(b,new Capabilities(1,other.connectionEpoch(),Set.of(PerceptionKind.SOUND),Map.of(ASSET,HASH)));
        var actual=effect(identity.connectionEpoch().value(),1,20);
        transport.upsert(new PerceptionIntent(identity,a,actual));
        transport.acknowledge(a,new PerceptionAck(actual.header(),PerceptionAckStatus.RECEIVED));
        var initial=new RecordObservation(actual.header(),new RecordObservationPayload(Optional.of(actual.header().effectId()),Optional.empty(),Optional.of("neutral annotation")));
        var fact=DirectorRecordedPerception.fact(a,initial,actual,123);
        var retained=Map.of(fact.factId(),fact);var refs=new DirectorPersonalFactReceipts(2,128);
        var shown=new ArrayList<PerceptionHeader>();var accepted=new ArrayList<RecordObservation>();
        var candidate=new RecordObservation(actual.header(),new RecordObservationPayload(Optional.empty(),Optional.of(fact.factId()),Optional.empty()));
        check(!transport.record(a,candidate,f->true,accepted::add),"durable ID and renderer receipt alone do not prove fact emission");
        check(refs.emit(transport,identity,fact,sequence::incrementAndGet,shown::add),"actual targeted fact emission");
        check(shown.getLast().equals(actual.header())&&transport.rememberedHeaders(a)==1,"current RECEIVED header reused without a second authority");
        check(transport.record(a,candidate,id->refs.source(transport,identity,candidate,id2->Optional.ofNullable(retained.get(id2))).isPresent(),accepted::add),"exact emitted reference returned");
        check(DirectorRecordedPerception.sameReference(fact,candidate),"same reference preserves original annotation");
        var exactNote=new RecordObservation(candidate.header(),new RecordObservationPayload(Optional.empty(),Optional.of(fact.factId()),Optional.of("neutral annotation")));
        check(DirectorRecordedPerception.sameReference(fact,exactNote),"identical annotation remains idempotent");
        var conflict=new RecordObservation(candidate.header(),new RecordObservationPayload(Optional.empty(),Optional.of(fact.factId()),Optional.of("changed annotation")));
        check(!DirectorRecordedPerception.sameReference(fact,conflict),"reference never overwrites original note");
        check(refs.source(transport,other,candidate,id2->Optional.ofNullable(retained.get(id2))).isEmpty(),"other player cannot borrow emitted fact");
        check(!refs.emit(transport,other,fact,sequence::incrementAndGet,shown::add),"singleton PLAYER ownership required before emission");
        var corrupt=new HashMap<String,JsonValue>(fact.payload());corrupt.put("asset",new JsonValue.Text("fixture:tampered"));
        var bad=new DirectorFact(fact.factId(),fact.factType(),fact.subjectRefs(),fact.eventId(),fact.evidenceStatus(),corrupt,true,Optional.empty());
        check(DirectorRecordedPerception.source(bad,a).isEmpty(),"canonical source/payload substitution rejected");
        check(refs.source(transport,identity,candidate,id2->Optional.of(bad)).isEmpty(),"changed retained payload cannot borrow prior emission");
        reject(()->new DirectorFact(fact.factId(),fact.factType(),fact.subjectRefs(),fact.eventId(),EvidenceStatus.UNKNOWN,fact.payload(),true,Optional.empty()),"UNKNOWN evidence rejected by fact model");
        var unconfirmed=new DirectorFact(fact.factId(),fact.factType(),fact.subjectRefs(),fact.eventId(),EvidenceStatus.POSSIBLE,fact.payload(),true,Optional.empty());
        check(DirectorRecordedPerception.source(unconfirmed,a).isEmpty(),"non-CONFIRMED personal fact refused");
        var publicFact=new DirectorFact(fact.factId(),fact.factType(),List.of(new ScopeRef(ScopeKind.WORLD,WORLD.value())),fact.eventId(),fact.evidenceStatus(),fact.payload(),true,Optional.empty());
        check(!refs.emit(transport,identity,publicFact,sequence::incrementAndGet,shown::add),"WORLD scope never becomes personal record source");
        transport.stop(a,new PerceptionStop(header(identity.connectionEpoch().value(),2,0),false));
        time.set(40);var renewed=transport.open(a,DIM);refs.clear(a);
        transport.capabilities(a,new Capabilities(1,renewed.connectionEpoch(),Set.of(PerceptionKind.SOUND),Map.of(ASSET,HASH)));
        check(refs.source(transport,renewed,candidate,id2->Optional.ofNullable(retained.get(id2))).isEmpty(),"new connection purges prior envelopes");
        int nativePackets=wire.size();
        check(refs.emit(transport,renewed,fact,sequence::incrementAndGet,shown::add),"cold durable fact emitted in current session");
        var cold=shown.getLast();
        check(cold.remainingTtlTicks().value()==0&&cold.connectionEpoch().equals(renewed.connectionEpoch())&&!transport.received(a,cold),"RECORD-only authority is never renderer evidence");
        check(wire.size()==nativePackets&&transport.rememberedHeaders(a)==1,"cold reference consumes one existing quota slot without native effect packet");
        check(!transport.acknowledge(a,new PerceptionAck(cold,PerceptionAckStatus.RECEIVED)),"RECORD envelope cannot manufacture a renderer ACK");
        check(!transport.interaction(a,new AnchorInteraction(cold,new AnchorId(new UUID(602,1)),ASSET),x->true,x->{throw new AssertionError("record envelope interaction");}),"RECORD envelope grants no anchor interaction");
        var coldReturn=new RecordObservation(cold,new RecordObservationPayload(Optional.empty(),Optional.of(fact.factId()),Optional.empty()));
        check(transport.record(a,coldReturn,id->refs.source(transport,renewed,coldReturn,id2->Optional.ofNullable(retained.get(id2))).isPresent(),accepted::add),"current cold reference returns explicit intent");
        check(refs.emit(transport,renewed,fact,sequence::incrementAndGet,shown::add)&&shown.getLast().equals(cold)&&transport.rememberedHeaders(a)==1,"duplicate display reuses identical envelope/authority");
        var issued=DirectorPersonalFactReceipts.command(fact.factId(),cold);
        check(issued.length()<256,"normal clickable command fits Minecraft");
        var fields=issued.split(" ");
        var issuedWorld=new WorldId(UUID.fromString(fields[10]));
        var issuedBoot=new Epoch(Long.parseLong(fields[8]));
        var issuedConnection=new Epoch(Long.parseLong(fields[9]));
        check(DirectorPersonalFactReceipts.current(renewed,issuedWorld,issuedBoot,issuedConnection),"issued world/boot/connection are explicit command arguments");
        var largest=new PerceptionHeader(1,WORLD,new Epoch(Long.MAX_VALUE),new Epoch(Long.MAX_VALUE),DIM,
            cold.instanceId(),cold.effectId(),new Sequence(Long.MAX_VALUE),new DurationTicks(1200));
        check(DirectorPersonalFactReceipts.command(fact.factId(),largest).length()<256,"maximum valid counters still fit the normal chat command bound");
        var missing=new RecordObservation(cold,new RecordObservationPayload(Optional.empty(),Optional.of(new FactId(new UUID(602,9))),Optional.empty()));
        check(!transport.record(a,missing,id->true,accepted::add),"unemitted arbitrary fact ID refused");

        //64 genuine renderer headers+64 RECORD-only envelopes share one128 ceiling.
        for(int i=1;i<=64;i++){
            time.set(100+((i-1)/10)*20L);
            var v=identified(effect(renewed.connectionEpoch().value(),1000+i*2,5),6100+i);
            transport.upsert(new PerceptionIntent(renewed,a,v));
            transport.stop(a,new PerceptionStop(identified(header(renewed.connectionEpoch().value(),1001+i*2,0),6100+i),false));
        }
        for(int i=1;i<=63;i++){
            var id=new FactId(new UUID(603,i));var h=identified(header(renewed.connectionEpoch().value(),2000+i,0),6200+i);
            check(transport.emitRecordFact(a,id,h,()->{}),"additional actually emitted RECORD-only authority");
            check(!transport.received(a,h),"RECORD-only envelope does not fabricate RECEIVED");
        }
        check(transport.rememberedHeaders(a)==128&&transport.sessionResetRequested(a),"mixed provenance hits128, not256");
        check(!transport.emitRecordFact(a,new FactId(new UUID(604,1)),identified(header(renewed.connectionEpoch().value(),3000,0),6400),()->{throw new AssertionError("129th emission");}),"129th authority rejected before emission");
        check(!transport.canSendNew(a,1)&&transport.readyForSessionReset(a),"existing safe rotation resumes mixed authorities");
        var third=transport.open(a,DIM);refs.clear(a);
        check(!transport.record(a,coldReturn,id->true,accepted::add),"real epoch renewal rejects old RECORD authority");
        check(transport.identity(b).orElseThrow().equals(other),"mixed quota never rotates peer");
        transport.capabilities(a,new Capabilities(1,third.connectionEpoch(),Set.of(PerceptionKind.SOUND),Map.of(ASSET,HASH)));
        check(refs.emit(transport,third,fact,sequence::incrementAndGet,shown::add),"canonical personal data survives genuine rotation");
        check(!DirectorPersonalFactReceipts.current(third,issuedWorld,issuedBoot,issuedConnection),"old emitted command cannot rebase onto a new connection after same fact is reissued");
        check(!DirectorPersonalFactReceipts.current(third,issuedWorld,new Epoch(issuedBoot.value()+1),third.connectionEpoch()),"issued boot mismatch fails before C2S");
        check(!DirectorPersonalFactReceipts.current(third,new WorldId(new UUID(605,1)),third.bootEpoch(),third.connectionEpoch()),"issued world mismatch fails before C2S");
        check(!transport.capabilities(a,new Capabilities(1,third.connectionEpoch(),Set.of(),Map.of())),"empty capability remains revocation");
        refs.clear(a);
        check(refs.source(transport,third,coldReturn,id2->Optional.ofNullable(retained.get(id2))).isEmpty(),"reload revoke/purge invalidates references");
        var fourth=transport.open(a,DIM);
        transport.capabilities(a,new Capabilities(1,fourth.connectionEpoch(),Set.of(PerceptionKind.SOUND),Map.of(ASSET,HASH)));
        boolean failed=false;
        try{refs.emit(transport,fourth,fact,sequence::incrementAndGet,h->{throw new IllegalStateException("fixture send failure");});}
        catch(IllegalStateException uncertain){failed=true;}
        check(failed&&transport.identity(a).isEmpty()&&transport.rememberedHeaders(a)==0,"uncertain chat emission creates no authority and revokes session");
        check(DirectorRecordedPerception.sameReference(fact,candidate),"all references leave canonical fact unchanged");
    }
    public static void main(String[] args){personalFactReferencesRequireActualEmissionAndShareBound();explicitRecordedPerceptionUsesOnlyReceivedPrivateSource();boundedSessionRecoveryAndResourceRevocation();codecs();clients();rendererFailureQuarantine();rendererStopFailureFailsClosed();server();senderFailureQuarantine();liveWarningReceipts();negotiatedVerifiedAssets();deferredNativeLoginReset();AssetIntegrityTests.run();DirectorPerceptionPrimitiveEncoderTests.run();DirectorRecipientAuthorityTests.run();DirectorAdminReplyDispatchTests.run();ReviewRegressionTests.run();DelayedHeartbeatTests.run();ClientAdminReplyFenceTests.run();System.out.println("PASS J06 T-NET/client state/T-LOG: "+assertions+" assertions; native two-client rendering NOT_RUN");}
}
