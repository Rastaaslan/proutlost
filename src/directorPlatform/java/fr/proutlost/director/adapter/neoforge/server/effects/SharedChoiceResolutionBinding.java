package fr.proutlost.director.adapter.neoforge.server.effects;

import fr.proutlost.director.adapter.neoforge.network.*;
import fr.proutlost.director.api.codec.ModelCodec;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.bootstrap.DirectorNativeAnchorOwner;
import java.util.*;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.BlockTags;

/** A real accepted command, sealed before the shared anchor state is changed. */
public final class SharedChoiceResolutionBinding implements AnchorMutationBinding {
 public static final FactType SELECTED=new FactType("proutlost_director:lab_choice_selected");
 public static final ResourceId PLAN_CODEC=new ResourceId("proutlost_director:choice_resolution_v1");
 private final MinecraftServer server;private final DirectorNativeAnchorOwner owner;
 private final DirectorNativeAnchorOwner.Lease lease;private final Supplier<Tick> clock;
 private final PlayerId actor;private final PerceptionHeader receipt;private final DirectorFact decision;
 private AnchorRecord after;private boolean applied;private final boolean nativeFixture;
 private final SharedChoice args;private final TagId resolvedTag;private final boolean requireOperator;
 public SharedChoiceResolutionBinding(MinecraftServer server,DirectorNativeAnchorOwner owner,
   DirectorNativeAnchorOwner.Lease lease,Supplier<Tick> clock,PlayerId actor,PerceptionHeader receipt,DirectorFact decision){
  this(server,owner,lease,clock,actor,receipt,decision,false,SharedChoiceOfferBinding.LAB_ARGS,SharedChoiceOfferBinding.RESOLVED,true);
 }
 public SharedChoiceResolutionBinding(MinecraftServer server,DirectorNativeAnchorOwner owner,
   DirectorNativeAnchorOwner.Lease lease,Supplier<Tick> clock,PlayerId actor,PerceptionHeader receipt,DirectorFact decision,
   SharedChoice args,TagId resolvedTag,boolean requireOperator){
  this(server,owner,lease,clock,actor,receipt,decision,false,args,resolvedTag,requireOperator);
 }
 private SharedChoiceResolutionBinding(MinecraftServer server,DirectorNativeAnchorOwner owner,
   DirectorNativeAnchorOwner.Lease lease,Supplier<Tick> clock,PlayerId actor,PerceptionHeader receipt,
   DirectorFact decision,boolean nativeFixture,SharedChoice args,TagId resolvedTag,boolean requireOperator){
  this.server=Objects.requireNonNull(server);this.owner=Objects.requireNonNull(owner);
  this.lease=Objects.requireNonNull(lease);this.clock=Objects.requireNonNull(clock);
  this.actor=actor;this.receipt=receipt;this.decision=Objects.requireNonNull(decision);this.nativeFixture=nativeFixture;
  this.args=Objects.requireNonNull(args);this.resolvedTag=Objects.requireNonNull(resolvedTag);this.requireOperator=requireOperator;
  if(nativeFixture&&!Boolean.getBoolean("proutlost.director.nativeGameTests"))
   throw new IllegalStateException("native choice fixture disabled");
  boolean source=nativeFixture?
   decision.factType().equals(new FactType("proutlost_director:lab_choice_native_resolution_fixture"))&&
    decision.payload().get("nativeFixture").equals(new JsonValue.Bool(true))&&
    decision.payload().get("humanInteraction").equals(new JsonValue.Bool(false)):
   decision.factType().equals(SELECTED)&&Objects.requireNonNull(receipt)!=null&&
    decision.payload().getOrDefault("actor",JsonValue.Nil.INSTANCE).equals(new JsonValue.Text(Objects.requireNonNull(actor).value().toString()));
  if(!source||decision.evidenceStatus()!=EvidenceStatus.CONFIRMED||!decision.durable()||
     !decision.payload().getOrDefault("anchor",JsonValue.Nil.INSTANCE).equals(new JsonValue.Text(lease.before().anchorId().value().toString()))||
     !Set.of(new JsonValue.Text(SharedChoiceOfferBinding.LISTEN.value()),new JsonValue.Text(SharedChoiceOfferBinding.LEAVE_TRACE.value()))
      .contains(decision.payload().get("action")))throw new IllegalArgumentException("unbound real choice decision");
 }
 /** Explicit laboratory input into this SAME real provider; no synthetic ACK/header/player. */
 public static SharedChoiceResolutionBinding nativeFixture(MinecraftServer server,DirectorNativeAnchorOwner owner,
   DirectorNativeAnchorOwner.Lease lease,Supplier<Tick> clock,UUID event,ResourceId branch){
  if(!Boolean.getBoolean("proutlost.director.nativeGameTests"))throw new IllegalStateException("native choice fixture disabled");
  if(!SharedChoiceOfferBinding.LAB_ARGS.choiceIds().contains(branch))throw new IllegalArgumentException("fixture branch");
  var decision=new DirectorFact(new FactId(UUID.nameUUIDFromBytes(("lab-choice-native-fixture/"+event).getBytes(java.nio.charset.StandardCharsets.UTF_8))),
   new FactType("proutlost_director:lab_choice_native_resolution_fixture"),List.of(new ScopeRef(ScopeKind.EVENT,event)),
   Optional.of(new EventInstanceId(event)),EvidenceStatus.CONFIRMED,Map.of("nativeFixture",new JsonValue.Bool(true),
    "humanInteraction",new JsonValue.Bool(false),"anchor",new JsonValue.Text(lease.before().anchorId().value().toString()),
    "action",new JsonValue.Text(branch.value())),true,Optional.empty());
  return new SharedChoiceResolutionBinding(server,owner,lease,clock,null,null,decision,true,SharedChoiceOfferBinding.LAB_ARGS,SharedChoiceOfferBinding.RESOLVED,true);
 }
 private boolean physicalCamp(){
  return TraceBinding.actualAnchor(server,lease.before());
 }
 private void actorThread(){if(!server.isSameThread())throw new IllegalStateException("choice resolution actor");}
 public ProviderMode mode(){return ProviderMode.PRODUCTION;}
 public Set<PrimitiveId> supportedPrimitives(){return Set.of(SharedChoiceOfferBinding.ID);}
 public Set<CapabilityId> capabilities(){return Set.of(SharedChoiceOfferBinding.INTERACTION,SharedChoiceOfferBinding.SYMBOL_CAP);}
 private boolean live(){
  if(nativeFixture)return Boolean.getBoolean("proutlost.director.nativeGameTests")&&physicalCamp();
  var player=server.getPlayerList().getPlayer(actor.value());var before=lease.before();
  if(player==null||!player.isAlive()||(requireOperator&&!player.hasPermissions(4))||player.isSpectator()||
     !player.serverLevel().dimension().location().toString().equals(before.dimension().value()))return false;
  var p=new BlockPos(before.position().x(),before.position().y(),before.position().z());
  double dx=player.getX()-(p.getX()+.5),dy=player.getY()-p.getY(),dz=player.getZ()-(p.getZ()+.5);
  if(dx*dx+dy*dy+dz*dz>64||player.serverLevel().getChunkSource().getChunkNow(p.getX()>>4,p.getZ()>>4)==null||
     !TraceBinding.actualAnchor(server,before))return false;
  var spec=DirectorGameplayAssets.specs().get(SharedChoiceOfferBinding.SYMBOL);
  return spec!=null&&DirectorNativeNetworkBootstrap.warningReceived(server,actor,receipt)&&
   DirectorNativeNetworkBootstrap.qualifiedRecipient(server,actor,PerceptionKind.SYMBOL,SharedChoiceOfferBinding.SYMBOL,spec.hash())
    .filter(s->s.worldId().equals(receipt.worldId())&&s.bootEpoch().equals(receipt.bootEpoch())&&
     s.connectionEpoch().equals(receipt.connectionEpoch())&&s.dimension().equals(receipt.dimension())).isPresent();
 }
 private boolean arguments(EffectIntent intent){
  var before=lease.before();
  return intent.primitive().equals(SharedChoiceOfferBinding.ID)&&intent.version()==1&&
   intent.operationToken().equals(lease.operation())&&intent.typedArgs().equals(args)&&
   decision.eventId().filter(intent.instanceId()::equals).isPresent()&&(nativeFixture||receipt.instanceId().equals(intent.instanceId()))&&
   before.active()&&before.tags().contains(args.anchorTag())&&
   !before.tags().contains(resolvedTag)&&
   intent.expectedFootprint().dimension().equals(before.dimension())&&intent.expectedFootprint().cells().equals(Set.of(before.position()));
 }
 private BlobContent decisionBlob(){return BlobContent.capture(PLAN_CODEC,1,new ModelCodec<>(DirectorFact.class).encode(decision));}
 private boolean contains(BlobBundle blobs,BlobContent exact){
  return blobs.contents().stream().anyMatch(b->b.reference().equals(exact.reference())&&Arrays.equals(b.bytes(),exact.bytes()));
 }
 public EffectPreparation capture(EffectIntent intent,PlanningSnapshot context){
  actorThread();if(!arguments(intent)||!owner.unchanged(lease)||!live())throw new IllegalArgumentException("choice decision no longer live");
  var before=lease.before();var tags=new HashSet<>(before.tags());tags.add(resolvedTag);var h=before.header();
  after=new AnchorRecord(new AggregateHeader(1,h.id(),h.revision().plus(1),h.lastSequence(),clock.get()),before.anchorId(),
   before.locationId(),before.regionIds(),before.dimension(),before.position(),before.radiusProfile(),tags,before.origin(),before.evidence(),before.active());
  var pre=TraceBinding.state(before);var post=TraceBinding.state(after);var plan=decisionBlob();var key=TraceBinding.key(before);
  var epoch=new Epoch(before.header().revision().value());
  var read=new ReadSet(context.snapshot().scopeRevisions(),Map.of(key,epoch),Map.of(key,pre.reference().hash()));
  var claim=new ResourceClaim(key,ClaimPolicy.EXCLUSIVE,BudgetMath.zero(),Optional.empty(),epoch);
  var refs=new EffectBlobs(Set.of(pre.reference(),post.reference()),plan.reference(),plan.reference(),Map.of(key,pre.reference()),Map.of(key,post.reference()));
  var prepared=new PreparedEffect(intent,context.snapshot(),List.of(claim),read,Map.of(key,post.reference().hash()),BudgetMath.zero(),
   List.of(),capabilities(),refs);
  return new EffectPreparation(intent,new ValidationResult(true,List.of(),read),Optional.of(prepared),new BlobBundle(List.of(pre,post,plan)));
 }
 public boolean revalidate(PreparedEffect prepared,BlobBundle blobs){
  actorThread();return after!=null&&arguments(prepared.intent())&&owner.unchanged(lease)&&live()&&
   prepared.capabilities().equals(capabilities())&&contains(blobs,TraceBinding.state(lease.before()))&&
   contains(blobs,TraceBinding.state(after))&&contains(blobs,decisionBlob())&&
   prepared.expectedPost().equals(Map.of(TraceBinding.key(after),TraceBinding.state(after).reference().hash()));
 }
 public SliceResult apply(PreparedEffect prepared,BlobBundle blobs){
  actorThread();if(!revalidate(prepared,blobs))throw new IllegalStateException("stale accepted choice");
  owner.applyTrace(lease,after);applied=true;var actual=owner.current(after.anchorId()).orElseThrow();
  if(!actual.equals(after))throw new IllegalStateException("choice anchor post-state mismatch");
  return new SliceResult(Outcome.SUCCEEDED,Set.of(prepared.intent().operationToken()),
   Map.of(TraceBinding.key(actual),TraceBinding.state(actual).reference().hash()),0,Set.of(),Set.of(),List.of());
 }
 public boolean wasApplied(){actorThread();return applied;}
 public AnchorRecord after(){actorThread();return Objects.requireNonNull(after,"choice not captured");}
 public List<DirectorFact> resultFacts(){actorThread();return List.of(decision);}
 public ReconcileResult reconcile(RecoveryProbe probe){
  actorThread();return new ReconcileResult(owner.current(lease.before().anchorId()).filter(a->after!=null&&a.equals(after)).isPresent()
   ?ReconcileStatus.SAME_POST:ReconcileStatus.UNAVAILABLE,probe.loadedProofs(),List.of());
 }
}
