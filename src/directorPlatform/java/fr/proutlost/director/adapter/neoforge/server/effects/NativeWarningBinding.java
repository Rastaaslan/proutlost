package fr.proutlost.director.adapter.neoforge.server.effects;
import fr.proutlost.director.adapter.neoforge.network.*;
import fr.proutlost.director.api.codec.ModelCodec;
import fr.proutlost.director.api.model.*;
import net.minecraft.server.MinecraftServer;
import java.util.*;
import java.util.function.Supplier;

/** Real renderer exposure, reached only through ServerEffectPort's durable permit. */
public final class NativeWarningBinding implements ServerEffectPort.Binding {
    private static final PrimitiveId ID=new PrimitiveId("proutlost:warning");
    private final MinecraftServer server;
    private final PlayerId target;
    private final PerceptionTransform transform;
    private final int ttl;
    private final ResourceId profile;
    private final SessionIdentity session;
    private PerceptionHeader header;private boolean sendAttempted;
    private final Supplier<Tick> clock;
    private Tick sentTick;
    public NativeWarningBinding(MinecraftServer server,PlayerId target,PerceptionTransform transform,int ttl) {
        this(server,target,transform,ttl,DirectorGameplayAssets.WARNING);
    }
    public NativeWarningBinding(MinecraftServer server,PlayerId target,PerceptionTransform transform,int ttl,ResourceId profile) {
        this(server,target,transform,ttl,profile,()->new Tick(server.overworld().getGameTime()));
    }
    public NativeWarningBinding(MinecraftServer server,PlayerId target,PerceptionTransform transform,int ttl,ResourceId profile,Supplier<Tick> clock) {
        this.clock=Objects.requireNonNull(clock);
        this.server=server;this.target=target;this.transform=transform;this.ttl=ttl;this.profile=Objects.requireNonNull(profile);
        var asset=DirectorGameplayAssets.specs().get(profile);
        if(asset==null||asset.kind()!=PerceptionKind.SYMBOL)throw new IllegalArgumentException("warning profile unavailable");
        if(ttl<1||ttl>1200)throw new IllegalArgumentException("warning TTL");
        if(!DirectorNativeNetworkBootstrap.hasPerceptionCapacity(server,target,1))
            throw new IllegalStateException("warning session capacity/handshake pending");
        session=recipient().orElseThrow(()->new IllegalStateException("warning renderer not negotiated"));
    }
    private Optional<SessionIdentity> recipient() {
        return DirectorNativeNetworkBootstrap.qualifiedRecipient(server,target,PerceptionKind.SYMBOL,
            profile,DirectorGameplayAssets.specs().get(profile).hash());
    }
    public ProviderMode mode(){return ProviderMode.PRODUCTION;}
    public Set<PrimitiveId> supportedPrimitives(){return Set.of(ID);}
    public Set<CapabilityId> capabilities(){return Set.of(new CapabilityId("warning.readable_v1"));}
    private void actor(){if(!server.isSameThread())throw new IllegalStateException("server actor required");}
    private boolean live(EffectIntent intent) {
        actor();
        if(!intent.primitive().equals(ID)||intent.version()!=1||
           !(intent.typedArgs() instanceof Warning w)||!w.role().equals("witness")||
           !w.profile().equals(profile)||w.minimumLeadTicks().value()<1||
           w.minimumLeadTicks().value()>ttl)return false;
        var player=server.getPlayerList().getPlayer(target.value());
        return player!=null&&player.isAlive()&&
            player.serverLevel().dimension().location().toString().equals(intent.expectedFootprint().dimension().value())&&
            recipient().filter(session::equals).isPresent();
    }
    private BlobContent blob(EffectIntent intent) {
        return BlobContent.capture(new ResourceId("proutlost_director:warning_intent_v1"),1,
            new ModelCodec<>(EffectIntent.class).encode(intent));
    }
    public boolean available(){actor();return recipient().filter(session::equals).isPresent();}
    public boolean definitivelyNeverSent(){actor();return !sendAttempted;}
    public EffectPreparation capture(EffectIntent intent,PlanningSnapshot context) {
        if(!live(intent))
            throw new IllegalArgumentException("warning target unavailable");
        var b=blob(intent);var read=new ReadSet(context.snapshot().scopeRevisions(),Map.of(),Map.of());
        var refs=new EffectBlobs(Set.of(),b.reference(),b.reference(),Map.of(),Map.of());
        return new EffectPreparation(intent,new ValidationResult(true,List.of(),read),
            Optional.of(new PreparedEffect(intent,context.snapshot(),List.of(),read,Map.of(),
                BudgetMath.zero(),List.of(),capabilities(),refs)),new BlobBundle(List.of(b)));
    }
    public boolean revalidate(PreparedEffect plan,BlobBundle blobs) {
        return live(plan.intent())&&DirectorNativeNetworkBootstrap.hasPerceptionCapacity(server,target,1)&&plan.capabilities().equals(capabilities())&&
            blobs.contents().stream().anyMatch(b->b.reference().equals(blob(plan.intent()).reference()));
    }
    public SliceResult apply(PreparedEffect plan,BlobBundle blobs) {
        if(!revalidate(plan,blobs))throw new IllegalStateException("stale warning");
        sendAttempted=true; // A throw after queuing is ambiguous, even if no header was returned.
        header=DirectorNativeNetworkBootstrap.sendGameplayWarning(server,target,plan.intent(),transform,ttl);
        sentTick=clock.get();
        return new SliceResult(Outcome.SUCCEEDED,Set.of(plan.intent().operationToken()),
            Map.of(),0,Set.of(),Set.of(),List.of());
    }
    public Optional<PerceptionHeader> emittedHeader(){actor();return Optional.ofNullable(header);}
    public Optional<Tick> sentAt(){actor();return Optional.ofNullable(sentTick);}
    public boolean receivedAndLive(){actor();return header!=null&&DirectorNativeNetworkBootstrap.liveWarningReceived(server,target,header,profile,transform);}
    public boolean received(){actor();return header!=null&&DirectorNativeNetworkBootstrap.warningReceived(server,target,header);}
    public void stop(){actor();if(header!=null)DirectorNativeNetworkBootstrap.stopGameplayWarning(server,target,header);}
    public ReconcileResult reconcile(RecoveryProbe probe) {
        actor();return new ReconcileResult(ReconcileStatus.UNAVAILABLE,probe.loadedProofs(),List.of());
    }
}