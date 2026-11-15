package fr.proutlost.director.adapter.neoforge.server.effects;

import fr.proutlost.director.adapter.neoforge.network.*;
import fr.proutlost.director.api.codec.ModelCodec;
import fr.proutlost.director.api.model.*;
import net.minecraft.server.MinecraftServer;
import java.util.*;

/** Real J06 effects through ServerEffectPort after its durable PREPARE permit. */
public final class NativePerceptionBinding implements ServerEffectPort.Binding {
 private record Selection(PerceptionKind kind,ResourceId asset,CapabilityId capability){}
 private final MinecraftServer server;private final PlayerId target;
 private final PrimitiveId primitive;private final PrimitiveArgs arguments;
 private final PerceptionTransform transform;private final int ttl;
 private final Selection selection;private final SessionIdentity session;
 private PerceptionHeader header;private boolean sendAttempted;
 public NativePerceptionBinding(MinecraftServer server,PlayerId target,PrimitiveId primitive,
   PrimitiveArgs arguments,PerceptionTransform transform,int ttl){
  this.server=server;this.target=target;this.primitive=primitive;this.arguments=arguments;
  this.transform=transform;this.ttl=ttl;
  if(ttl<1||ttl>1200)throw new IllegalArgumentException("perception TTL");
  selection=switch(primitive.value()){
   case "proutlost:target_sound"->{
    if(!(arguments instanceof TargetSound a)||!a.role().equals("witness")||a.repeats()!=1)throw new IllegalArgumentException("sound arguments");
    yield new Selection(PerceptionKind.SOUND,a.sound(),new CapabilityId("perception.sound_v1"));
   }
   case "proutlost:local_weather"->{
    if(!(arguments instanceof LocalWeather a)||!a.role().equals("witness"))throw new IllegalArgumentException("weather arguments");
    yield new Selection(PerceptionKind.LOCAL_WEATHER,a.profile(),new CapabilityId("perception.local_weather_v1"));
   }
   case "proutlost:apparition"->{
    if(!(arguments instanceof Apparition a)||!a.role().equals("witness"))throw new IllegalArgumentException("apparition arguments");
    yield new Selection(PerceptionKind.APPARITION,a.model(),new CapabilityId("perception.silhouette_v1"));
   }
   default->throw new IllegalArgumentException("unsupported perception primitive");
  };
  session=recipient().orElseThrow(()->new IllegalStateException("perception asset not negotiated"));
 }
 private void actor(){if(!server.isSameThread())throw new IllegalStateException("server actor required");}
 private Optional<SessionIdentity> recipient(){
  var asset=DirectorGameplayAssets.encoderAssets().get(selection.asset());
  if(asset==null||asset.kind()!=selection.kind()||asset.mode()!=ProviderMode.PRODUCTION)return Optional.empty();
  return DirectorNativeNetworkBootstrap.qualifiedRecipient(server,target,selection.kind(),selection.asset(),asset.hash());
 }
 public ProviderMode mode(){return ProviderMode.PRODUCTION;}
 public Set<PrimitiveId> supportedPrimitives(){return Set.of(primitive);}
 public Set<CapabilityId> capabilities(){return Set.of(selection.capability());}
 public boolean available(){actor();return recipient().filter(session::equals).isPresent();}
 private boolean live(EffectIntent intent){
  actor();
  if(!intent.primitive().equals(primitive)||intent.version()!=1||!intent.typedArgs().equals(arguments))return false;
  var player=server.getPlayerList().getPlayer(target.value());
  if(player==null||!player.isAlive()||
   !player.serverLevel().dimension().location().toString().equals(intent.expectedFootprint().dimension().value())||
   !available())return false;
  for(var chunk:intent.expectedFootprint().loadedChunks())
   if(!chunk.dimension().equals(intent.expectedFootprint().dimension())||
      player.serverLevel().getChunkSource().getChunkNow(chunk.x(),chunk.z())==null)return false;
  try{
   var h=new PerceptionHeader(1,session.worldId(),session.bootEpoch(),session.connectionEpoch(),
    session.dimension(),intent.instanceId(),new EffectId(intent.operationToken().value()),new Sequence(1),new DurationTicks(ttl));
   DirectorPerceptionPrimitiveEncoder.encode(intent,Map.of("witness",target),session,h,transform,DirectorGameplayAssets.encoderAssets());
   return true;
  }catch(IllegalArgumentException invalid){return false;}
 }
 private BlobContent blob(EffectIntent intent){
  return BlobContent.capture(new ResourceId("proutlost_director:perception_intent_v1"),1,new ModelCodec<>(EffectIntent.class).encode(intent));
 }
 public EffectPreparation capture(EffectIntent intent,PlanningSnapshot context){
  if(!live(intent))throw new IllegalArgumentException("perception target unavailable");
  var b=blob(intent);var read=new ReadSet(context.snapshot().scopeRevisions(),Map.of(),Map.of());
  var refs=new EffectBlobs(Set.of(),b.reference(),b.reference(),Map.of(),Map.of());
  return new EffectPreparation(intent,new ValidationResult(true,List.of(),read),
   Optional.of(new PreparedEffect(intent,context.snapshot(),List.of(),read,Map.of(),BudgetMath.zero(),
    List.of(),capabilities(),refs)),new BlobBundle(List.of(b)));
 }
 public boolean revalidate(PreparedEffect plan,BlobBundle blobs){
  return live(plan.intent())&&DirectorNativeNetworkBootstrap.hasPerceptionCapacity(server,target,1)&&plan.capabilities().equals(capabilities())&&
   blobs.contents().stream().anyMatch(b->b.reference().equals(blob(plan.intent()).reference()));
 }
 public SliceResult apply(PreparedEffect plan,BlobBundle blobs){
  if(!revalidate(plan,blobs))throw new IllegalStateException("stale perception");
  // Set before entering the network boundary: a throw may follow a queued packet.
  sendAttempted=true;
  header=DirectorNativeNetworkBootstrap.sendGameplayPerception(server,target,plan.intent(),transform,ttl);
  return new SliceResult(Outcome.SUCCEEDED,Set.of(plan.intent().operationToken()),Map.of(),0,Set.of(),Set.of(),List.of());
 }
 /** Valid only in this live binding; unlike a null header this proves no network call was attempted. */
 public boolean definitivelyNeverSent(){actor();return !sendAttempted;}
 public boolean received(){actor();return header!=null&&DirectorNativeNetworkBootstrap.warningReceived(server,target,header);}
 public Optional<PerceptionHeader> emittedHeader(){actor();return Optional.ofNullable(header);}
 public void stop(){actor();if(header!=null)DirectorNativeNetworkBootstrap.stopGameplayWarning(server,target,header);}
 public ReconcileResult reconcile(RecoveryProbe probe){actor();return new ReconcileResult(ReconcileStatus.UNAVAILABLE,probe.loadedProofs(),List.of());}
}
