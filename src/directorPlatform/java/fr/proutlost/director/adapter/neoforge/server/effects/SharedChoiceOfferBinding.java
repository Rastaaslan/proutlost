package fr.proutlost.director.adapter.neoforge.server.effects;

import fr.proutlost.director.adapter.neoforge.network.*;
import fr.proutlost.director.api.codec.*;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.bootstrap.DirectorNativeAnchorOwner;
import java.util.*;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.BlockTags;

/** A genuine shared anchor prompt. No ACK or timeout is a player selection. */
public final class SharedChoiceOfferBinding implements ServerEffectPort.Binding {
 public static final PrimitiveId ID=new PrimitiveId("proutlost:shared_choice");
 public static final CapabilityId INTERACTION=new CapabilityId("interaction.shared_anchor_v1");
 public static final CapabilityId SYMBOL_CAP=new CapabilityId("perception.symbol_v1");
 public static final ResourceId SYMBOL=new ResourceId("proutlost_director:lab_choice_symbol");
 public static final ResourceId LISTEN=new ResourceId("proutlost:listen");
 public static final ResourceId LEAVE_TRACE=new ResourceId("proutlost:leave_trace");
 public static final TagId RESOLVED=new TagId("proutlost_director:lab_choice_resolved");
 public static final SharedChoice LAB_ARGS=new SharedChoice(List.of("witness"),new TagId("proutlost:camp"),
  List.of(LISTEN,LEAVE_TRACE),TimeoutPolicy.KEEP_UNCHANGED);
 public static final ResourceId PLAN_CODEC=new ResourceId("proutlost_director:choice_offer_v1");
 private final MinecraftServer server;private final DirectorNativeAnchorOwner owner;
 private final AnchorRecord anchor;private final Map<PlayerId,SessionIdentity> recipients;
 private final Map<PlayerId,PerceptionHeader> headers=new LinkedHashMap<>();
 private final PerceptionTransform transform;private final int ttl;private final Supplier<Tick> clock;
 private Tick sentAt;private boolean sendAttempted;
 private final SharedChoice args;private final TagId resolvedTag;private final boolean requireOperator;
 public SharedChoiceOfferBinding(MinecraftServer server,DirectorNativeAnchorOwner owner,AnchorRecord anchor,
   List<PlayerId> players,PerceptionTransform transform,int ttl,Supplier<Tick> clock){
  this(server,owner,anchor,players,transform,ttl,clock,LAB_ARGS,RESOLVED,true);
 }
 public SharedChoiceOfferBinding(MinecraftServer server,DirectorNativeAnchorOwner owner,AnchorRecord anchor,
   List<PlayerId> players,PerceptionTransform transform,int ttl,Supplier<Tick> clock,
   SharedChoice args,TagId resolvedTag,boolean requireOperator){
  this.server=Objects.requireNonNull(server);this.owner=Objects.requireNonNull(owner);this.anchor=Objects.requireNonNull(anchor);
  this.args=Objects.requireNonNull(args);this.resolvedTag=Objects.requireNonNull(resolvedTag);this.requireOperator=requireOperator;
  if(!args.choiceIds().equals(List.of(LISTEN,LEAVE_TRACE))||args.timeoutPolicy()!=TimeoutPolicy.KEEP_UNCHANGED)
   throw new IllegalArgumentException("unsupported actual shared choice actions/timeout");
  this.transform=Objects.requireNonNull(transform);this.ttl=ttl;this.clock=Objects.requireNonNull(clock);actor();
  if(ttl<1||ttl>1200||players.isEmpty()||players.size()>6||new HashSet<>(players).size()!=players.size())
   throw new IllegalArgumentException("choice audience/TTL");
  // Validate every recipient before capturing any dossier; this does not reserve capacity.
  for(var player:players)if(!DirectorNativeNetworkBootstrap.hasPerceptionCapacity(server,player,1))
   throw new IllegalStateException("choice session capacity/handshake pending");
  var captured=new LinkedHashMap<PlayerId,SessionIdentity>();
  for(var player:players)captured.put(player,recipient(player).orElseThrow(()->new IllegalStateException("choice SYMBOL not negotiated")));
  recipients=Map.copyOf(captured);
 }
 private void actor(){if(!server.isSameThread())throw new IllegalStateException("choice server actor");}
 private Optional<SessionIdentity> recipient(PlayerId player){
  var asset=DirectorGameplayAssets.specs().get(SYMBOL);
  if(asset==null||asset.kind()!=PerceptionKind.SYMBOL)return Optional.empty();
  return DirectorNativeNetworkBootstrap.qualifiedRecipient(server,player,PerceptionKind.SYMBOL,SYMBOL,asset.hash());
 }
 public ProviderMode mode(){return ProviderMode.PRODUCTION;}
 public Set<PrimitiveId> supportedPrimitives(){return Set.of(ID);}
 public Set<CapabilityId> capabilities(){return Set.of(INTERACTION,SYMBOL_CAP);}
 public AnchorRecord anchor(){actor();return anchor;}
 public Set<PlayerId> players(){actor();return recipients.keySet();}
 public Optional<PerceptionHeader> header(PlayerId player){actor();return Optional.ofNullable(headers.get(player));}
 public boolean definitivelyNeverSent(){actor();return !sendAttempted;}
 public Tick sentAt(){actor();return Objects.requireNonNull(sentAt,"prompt not sent");}
 public boolean livePlayer(PlayerId id){
  actor();var player=server.getPlayerList().getPlayer(id.value());
  if(player==null||!player.isAlive()||(requireOperator&&!player.hasPermissions(4))||player.isSpectator()||
     !player.serverLevel().dimension().location().toString().equals(anchor.dimension().value()))return false;
  double dx=player.getX()-(anchor.position().x()+.5),dy=player.getY()-anchor.position().y(),dz=player.getZ()-(anchor.position().z()+.5);
  return dx*dx+dy*dy+dz*dz<=64&&recipient(id).filter(s->s.equals(recipients.get(id))).isPresent();
 }
 public boolean available(){
  actor();
  return owner.durablyCurrent(anchor)&&anchor.active()&&anchor.tags().contains(args.anchorTag())&&
   !anchor.tags().contains(resolvedTag)&&TraceBinding.actualAnchor(server,anchor)&&recipients.keySet().stream().allMatch(this::livePlayer);
 }
 private boolean arguments(EffectIntent intent){
  return intent.primitive().equals(ID)&&intent.version()==1&&intent.typedArgs().equals(args)&&
   intent.expectedFootprint().dimension().equals(anchor.dimension())&&intent.expectedFootprint().cells().equals(Set.of(anchor.position()))&&
   intent.expectedFootprint().loadedChunks().equals(Set.of(new ChunkRef(anchor.dimension(),anchor.position().x()>>4,anchor.position().z()>>4)));
 }
 private BlobContent plan(EffectIntent intent){
  var sessions=recipients.values().stream().sorted(Comparator.comparing(s->s.playerId().value())).map(s->
   CanonicalJsonV1.decode(new ModelCodec<>(SessionIdentity.class).encode(s))).toList();
  var bytes=CanonicalJsonV1.encode(new JsonValue.ObjectValue(Map.of(
   "intent",CanonicalJsonV1.decode(new ModelCodec<>(EffectIntent.class).encode(intent)),
   "anchor",CanonicalJsonV1.decode(new ModelCodec<>(AnchorRecord.class).encode(anchor)),
   "recipients",new JsonValue.Array(sessions),"ttl",new JsonValue.Number(java.math.BigDecimal.valueOf(ttl)),
   "transform",CanonicalJsonV1.decode(new ModelCodec<>(PerceptionTransform.class).encode(transform)))));
  return BlobContent.capture(PLAN_CODEC,1,bytes);
 }
 public EffectPreparation capture(EffectIntent intent,PlanningSnapshot context){
  actor();if(!arguments(intent)||!available())throw new IllegalArgumentException("real live shared camp audience required");
  var pre=TraceBinding.state(anchor);var bytes=plan(intent);var key=TraceBinding.key(anchor);
  var epoch=new Epoch(anchor.header().revision().value());
  var read=new ReadSet(context.snapshot().scopeRevisions(),Map.of(key,epoch),Map.of(key,pre.reference().hash()));
  var claim=new ResourceClaim(key,ClaimPolicy.EXCLUSIVE,BudgetMath.zero(),Optional.empty(),epoch);
  var refs=new EffectBlobs(Set.of(pre.reference()),bytes.reference(),bytes.reference(),Map.of(key,pre.reference()),Map.of());
  var prepared=new PreparedEffect(intent,context.snapshot(),List.of(claim),read,Map.of(),BudgetMath.zero(),List.of(),capabilities(),refs);
  return new EffectPreparation(intent,new ValidationResult(true,List.of(),read),Optional.of(prepared),new BlobBundle(List.of(pre,bytes)));
 }
 public boolean revalidate(PreparedEffect prepared,BlobBundle blobs){
  actor();return arguments(prepared.intent())&&available()&&
   recipients.keySet().stream().allMatch(p->DirectorNativeNetworkBootstrap.hasPerceptionCapacity(server,p,1))&&prepared.capabilities().equals(capabilities())&&
   prepared.durableBlobs().preparedPlan().equals(plan(prepared.intent()).reference())&&
   blobs.contents().stream().anyMatch(b->b.reference().equals(plan(prepared.intent()).reference()));
 }
 public SliceResult apply(PreparedEffect prepared,BlobBundle blobs){
  actor();if(!headers.isEmpty()||!revalidate(prepared,blobs))throw new IllegalStateException("stale or repeated choice prompt");
  sentAt=clock.get();sendAttempted=true;
  for(var player:recipients.keySet())headers.put(player,
   DirectorNativeNetworkBootstrap.sendGameplayPerception(server,player,prepared.intent(),transform,ttl));
  return new SliceResult(Outcome.SUCCEEDED,Set.of(prepared.intent().operationToken()),Map.of(),0,Set.of(),Set.of(),List.of());
 }
 public boolean allReceived(){
  actor();return headers.size()==recipients.size()&&headers.entrySet().stream().allMatch(e->
   DirectorNativeNetworkBootstrap.warningReceived(server,e.getKey(),e.getValue()));
 }
 public void stop(){
  actor();RuntimeException failure=null;
  for(var entry:headers.entrySet())try{DirectorNativeNetworkBootstrap.stopGameplayWarning(server,entry.getKey(),entry.getValue());}
   catch(RuntimeException unavailable){if(failure==null)failure=unavailable;}
  if(failure!=null)throw failure;
 }
 public ReconcileResult reconcile(RecoveryProbe probe){
  actor();return new ReconcileResult(ReconcileStatus.UNAVAILABLE,probe.loadedProofs(),List.of());
 }
}
