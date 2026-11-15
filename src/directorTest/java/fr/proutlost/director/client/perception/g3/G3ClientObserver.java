package fr.proutlost.director.client.perception.g3;

import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.codec.ModelCodec;
import fr.proutlost.director.bootstrap.DirectorBootstrap;
import fr.proutlost.director.client.perception.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.*;
import net.neoforged.neoforge.client.event.*;
import com.mojang.blaze3d.systems.RenderSystem;
import com.sun.jna.*;
import com.sun.jna.ptr.IntByReference;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** TEST_ONLY hooks for Work's separate qualification mod, never a gameplay provider.
 * No verdicts, desktop capture, external process invocation or world mutation. */
public final class G3ClientObserver implements DirectorBootstrap.TestOnlyBackend,PerceptionManager.Effects {
    public enum Slot { A,B }
    public record Admission(UUID nonce,PerceptionHeader header,UUID targetClient,ResourceId assetId,Fingerprint assetHash,Optional<ResourceId> soundEvent){
        public Admission{Objects.requireNonNull(nonce);Objects.requireNonNull(header);Objects.requireNonNull(targetClient);Objects.requireNonNull(assetId);Objects.requireNonNull(assetHash);Objects.requireNonNull(soundEvent);}
    }
    /** Supplied by server-side qualification hook AFTER actual admission, independently to BOTH clients. */
    private final Map<PerceptionHeader,Admission> admissions=new HashMap<>();
    private final Minecraft mc;
    private final MinecraftPerceptionEffects delegate;
    private final G3EvidenceWriter writer;
    private final UUID runId,expectedClient,peerClient;
    private final Slot slot;
    private final Map<String,Object> process;
    private final Field visuals,engineField,channelsField;
    private final AtomicInteger capturesInFlight=new AtomicInteger();
    private final AtomicInteger audioInFlight=new AtomicInteger();
    private final ArrayDeque<CaptureRequest> captures=new ArrayDeque<>();
    private record CaptureRequest(UUID id,Admission admission,String phase){}
    private long frame,renderPass,starts,updates,stops;
    private boolean renderedSinceFrame;
    private UUID lastClient;
    private SessionReset currentSession;
    private boolean closed;

    public interface WindowsKernel extends Library {boolean ProcessIdToSessionId(int pid,IntByReference session);}
    private static Map<String,Object> processIdentity(){
        long pid=ProcessHandle.current().pid();String os=System.getProperty("os.name");
        var p=new LinkedHashMap<String,Object>();p.put("java_pid",Long.toString(pid));p.put("os",os);
        p.put("process_started_utc",ProcessHandle.current().info().startInstant().orElseThrow().toString());
        if(os.toLowerCase(Locale.ROOT).startsWith("windows")){
            var session=new IntByReference();if(!Native.load("kernel32",WindowsKernel.class).ProcessIdToSessionId(Math.toIntExact(pid),session))throw new IllegalStateException("Windows process session unavailable");
            p.put("windows_session_id",Integer.toUnsignedString(session.getValue()));p.put("windows_session_name",Objects.toString(System.getenv("SESSIONNAME"),"UNAVAILABLE"));
        }else{p.put("windows_session_id","NOT_WINDOWS");p.put("windows_session_name","NOT_WINDOWS");}
        return Map.copyOf(p);
    }
    public G3ClientObserver(ProviderMode mode,Minecraft mc,MinecraftPerceptionEffects delegate,G3EvidenceWriter writer,UUID runId,Slot slot,UUID expectedClient,UUID peerClient){
        Checks.require(mode==ProviderMode.TEST_ONLY,"G3 observer is TEST_ONLY");
        Checks.require(!expectedClient.equals(peerClient),"two distinct fixed clients required");
        this.mc=mc;this.delegate=delegate;this.writer=writer;this.runId=runId;this.slot=slot;this.expectedClient=expectedClient;this.peerClient=peerClient;
        process=processIdentity();visuals=field(MinecraftPerceptionEffects.class,"visuals");
        engineField=field(SoundManager.class,"soundEngine");channelsField=field(SoundEngine.class,"instanceToChannel");
    }
    private static Field field(Class<?> type,String name){try{var f=type.getDeclaredField(name);f.setAccessible(true);return f;}catch(ReflectiveOperationException e){throw new IllegalStateException("Pinned G3 instrumentation incompatible",e);}}
    private static Object read(Field f,Object target){try{return f.get(target);}catch(IllegalAccessException e){throw new IllegalStateException("G3 reflection failed",e);}}
    private void main(){Checks.require(mc.isSameThread()&&!closed,"client main thread / observer open");if(mc.player!=null){lastClient=mc.player.getUUID();Checks.require(lastClient.equals(expectedClient),"fixed client UUID mismatch");}}
    private Admission admission(PerceptionHeader header){var a=admissions.get(header);Checks.require(a!=null,"missing independent server admission witness");return a;}
    private Map<String,Object> row(String kind,Admission a,String phase){
        Checks.require(a.equals(admission(a.header())),"unarmed witness");
        Checks.require(phase.length()<=128,"phase bound");
        var m=new LinkedHashMap<String,Object>();m.put("schema_version",1);m.put("provider_mode","TEST_ONLY");m.put("run_id",runId.toString());m.put("client_slot",slot.name());m.put("process",process);
        m.put("client_uuid",lastClient==null?"UNCONNECTED":lastClient.toString());m.put("expected_client_uuid",expectedClient.toString());m.put("peer_client_uuid",peerClient.toString());
        m.put("player_present",mc.player!=null);m.put("dimension",mc.level==null?"DISCONNECTED":mc.level.dimension().location().toString());
        m.put("frame",Long.toString(frame));m.put("render_pass",Long.toString(renderPass));m.put("observed_nanos",Long.toString(System.nanoTime()));m.put("kind",kind);m.put("phase",phase);
        m.put("admission_nonce",a.nonce().toString());m.put("target_client_uuid",a.targetClient().toString());
        m.put("header",com.google.gson.JsonParser.parseString(new String(new ModelCodec<>(PerceptionHeader.class).encode(a.header()),StandardCharsets.UTF_8)));
        m.put("asset_id",a.assetId().value());m.put("asset_hash",a.assetHash().value());
        m.put("current_session",currentSession==null?"NONE":com.google.gson.JsonParser.parseString(new String(new ModelCodec<>(SessionReset.class).encode(currentSession),StandardCharsets.UTF_8)));
        return m;
    }
    private void emit(String kind,Admission a,String phase,Map<String,Object> data){var r=row(kind,a,phase);r.put("data",Map.copyOf(data));writer.event(r);}
    public void arm(Admission witness){main();Checks.require(witness.targetClient().equals(expectedClient)||witness.targetClient().equals(peerClient),"target outside fixed pair");Checks.require(admissions.size()<128||admissions.containsKey(witness.header()),"admission observer bound");var previous=admissions.putIfAbsent(witness.header(),witness);Checks.require(previous==null||previous.equals(witness),"admission witness conflict");emit("ARM",witness,"SERVER_ADMITTED",Map.of());}
    /** Reset hook is invoked with the genuine S2C DTO, even across disconnect/reconnect. */
    public void session(SessionReset reset){main();currentSession=reset;}
    public void receive(PerceptionUpsert payload,Consumer<PerceptionUpsert> realDispatch){
        main();var a=admission(payload.header());Checks.require(payload.assetId().equals(a.assetId())&&payload.assetHash().equals(a.assetHash()),"admitted asset mismatch");emit("RECEIVE",a,"PAYLOAD",Map.of());
        try{realDispatch.accept(payload);emit("DISPATCH",a,"RETURNED",snapshot(a));}catch(RuntimeException e){emit("DISPATCH",a,"THREW",Map.of("exception",e.getClass().getName()));throw e;}
    }
    /** Forward this actual manager ACK to the real network after observation. */
    public void acknowledgement(PerceptionAck ack){main();emit("PERCEPTION_ACK",admission(ack.header()),ack.status().name(),Map.of());}
    @Override public boolean available(PerceptionUpsert e){main();return delegate.available(e);}
    @Override public void start(PerceptionUpsert e){main();delegate.start(e);starts++;emit("EFFECT",admission(e.header()),"START_RETURNED",snapshot(admission(e.header())));}
    @Override public void update(PerceptionUpsert e){main();delegate.update(e);updates++;emit("EFFECT",admission(e.header()),"UPDATE_RETURNED",snapshot(admission(e.header())));}
    @Override public void stop(EffectId id){main();delegate.stop(id);stops++;}
    @SuppressWarnings("unchecked") private Map<EffectId,Object> live(){return (Map<EffectId,Object>)read(visuals,delegate);}
    private Map<String,Object> snapshot(Admission a){
        Object visual=live().get(a.header().effectId());int particles=0;boolean soundReference=false;
        if(visual!=null){for(Object p:(List<?>)read(field(visual.getClass(),"particles"),visual))if(((Particle)p).isAlive())particles++;soundReference=read(field(visual.getClass(),"sound"),visual)!=null;}
        return Map.of("live_visuals",live().size(),"effect_proxy_present",visual!=null,"live_effect_particles",particles,"sound_reference_present",soundReference,
            "starts",Long.toString(starts),"updates",Long.toString(updates),"cleanup_calls",Long.toString(stops));
    }
    /** Runner calls for genuine dimension/death/disconnect/reconnect/Stop/Reset callbacks AFTER cleanup. */
    public void cleanup(Admission witness,String reason){main();emit("CLEANUP",witness,reason,snapshot(witness));sampleAudio(witness);}
    /** Replace the original render listener with this wrapper; do not register both. */
    public void render(RenderLevelStageEvent event){main();delegate.render(event);if(event.getStage()==RenderLevelStageEvent.Stage.AFTER_PARTICLES){renderPass++;renderedSinceFrame=true;}}
    public UUID requestCapture(Admission witness,String phase){main();Checks.require(captures.size()<2,"capture request bound");var id=UUID.randomUUID();captures.add(new CaptureRequest(id,witness,phase));return id;}
    /** Register on actual RenderFrameEvent.Post. Reads only Minecraft's render target, never desktop. */
    public void frame(RenderFrameEvent.Post event){
        main();RenderSystem.assertOnRenderThread();frame++;
        boolean rendered=renderedSinceFrame;renderedSinceFrame=false;
        if(captures.isEmpty()||capturesInFlight.get()>=2)return;
        var request=captures.remove();var target=mc.getMainRenderTarget();
        Checks.require((long)target.width*target.height<=G3EvidenceWriter.MAX_PIXELS,"framebuffer bound");
        try(var image=Screenshot.takeScreenshot(target)){
            int[] pixels=image.getPixelsRGBA();var row=row("FRAMEBUFFER",request.admission(),request.phase());row.put("data",snapshot(request.admission()));row.put("capture_id",request.id().toString());row.put("world_rendered_this_frame",rendered);
            capturesInFlight.incrementAndGet();writer.capture(row,request.id(),image.getWidth(),image.getHeight(),pixels).whenComplete((ignored,error)->capturesInFlight.decrementAndGet());
        }
    }
    /** Actual SoundEngine membership plus OpenAL channel.playing()/stopped() on the audio executor.
     * Missing asynchronous completions are UNKNOWN, never interpreted as stopped or silence. */
    @SuppressWarnings("unchecked") public void sampleAudio(Admission a){
        main();var soundManager=mc.getSoundManager();var engine=(SoundEngine)read(engineField,soundManager);
        var channels=(Map<SoundInstance,ChannelAccess.ChannelHandle>)read(channelsField,engine);
        Object visual=live().get(a.header().effectId());SoundInstance exact=visual==null?null:(SoundInstance)read(field(visual.getClass(),"sound"),visual);
        int matched=0;
        for(var entry:List.copyOf(channels.entrySet())){
            var sound=entry.getKey();boolean byAsset=a.soundEvent().map(id->id.value().equals(sound.getLocation().toString())).orElse(false);
            if(sound!=exact&&!byAsset)continue;
            Checks.require(audioInFlight.get()<128,"audio probe bound");matched++;
            var sample=UUID.randomUUID();boolean active=soundManager.isActive(sound);boolean exactMatch=sound==exact;
            var base=row("AUDIO_CHANNEL",a,"REQUESTED");base.put("sample_id",sample.toString());base.put("data",Map.of("engine_is_active",active,"sound_event",sound.getLocation().toString(),"exact_effect_reference",exactMatch));writer.event(base);
            // Freeze all MC-derived values before callback: audio callback reads only its own Channel.
            var completed=new LinkedHashMap<>(base);completed.put("phase","SAMPLED");audioInFlight.incrementAndGet();
            entry.getValue().execute(channel->{try{var result=new LinkedHashMap<>(completed);result.put("data",Map.of("engine_is_active_at_request",active,"openal_playing",channel.playing(),"openal_stopped",channel.stopped(),"exact_effect_reference",exactMatch));writer.event(result);}finally{audioInFlight.decrementAndGet();}});
        }
        emit("AUDIO_SCAN",a,"ENGINE_SNAPSHOT",Map.of("matching_channels",matched,"pending_samples",audioInFlight.get()));
    }
    /** Stop hooks first; completion is awaited off MC threads by the runner. Pending samples invalidate proof. */
    public CompletionStage<Void> finish(){main();Checks.require(captures.isEmpty()&&capturesInFlight.get()==0&&audioInFlight.get()==0,"unfinished G3 observations");closed=true;return writer.finish();}
    public Optional<Throwable> failure(){return writer.failure();}
}
