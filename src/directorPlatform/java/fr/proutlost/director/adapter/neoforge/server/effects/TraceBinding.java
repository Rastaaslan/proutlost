package fr.proutlost.director.adapter.neoforge.server.effects;
import fr.proutlost.director.api.codec.ModelCodec;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.bootstrap.DirectorNativeAnchorOwner;
import java.util.*;
import java.util.function.Supplier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.BlockTags;
import net.minecraft.core.BlockPos;

/** Actual anchor state under the shared J01/Trace actor; exposure still requires J04's consumed permit. */
public final class TraceBinding implements AnchorMutationBinding {
 public static final PrimitiveId ID=new PrimitiveId("proutlost:trace");
 public static final CapabilityId STATE=new CapabilityId("anchor.state_v1");
 public static final ResourceId CODEC=new ResourceId("proutlost_director:anchor_state_v1");
 private static final ModelCodec<AnchorRecord> ANCHOR=new ModelCodec<>(AnchorRecord.class);
 private final MinecraftServer server;private final DirectorNativeAnchorOwner owner;
 private final DirectorNativeAnchorOwner.Lease lease;private final Supplier<Tick> clock;
 private AnchorRecord after;private boolean applied;
 public TraceBinding(MinecraftServer server,DirectorNativeAnchorOwner owner,
   DirectorNativeAnchorOwner.Lease lease,Supplier<Tick> clock){
  this.server=Objects.requireNonNull(server);this.owner=Objects.requireNonNull(owner);
  this.lease=Objects.requireNonNull(lease);this.clock=Objects.requireNonNull(clock);
 }
 private void actor(){if(!server.isSameThread())throw new IllegalStateException("Trace server actor");}
 public ProviderMode mode(){return ProviderMode.PRODUCTION;}
 public Set<PrimitiveId> supportedPrimitives(){return Set.of(ID);}
 public Set<CapabilityId> capabilities(){return Set.of(STATE);}
 public static ResourceKey key(AnchorRecord anchor){return new ResourceKey(OwnershipDomain.ANCHOR,anchor.dimension(),anchor.anchorId().value().toString());}
 public static BlobContent state(AnchorRecord anchor){return BlobContent.capture(CODEC,1,ANCHOR.encode(anchor));}
 public static boolean actualAnchor(MinecraftServer server,AnchorRecord before){
  for(var level:server.getAllLevels())if(level.dimension().location().toString().equals(before.dimension().value())){
   var p=new BlockPos(before.position().x(),before.position().y(),before.position().z());
   return level.getChunkSource().getChunkNow(p.getX()>>4,p.getZ()>>4)!=null&&
    (before.origin()==AnchorOrigin.CAMP&&level.getBlockState(p).is(BlockTags.CAMPFIRES)||
     before.origin()==AnchorOrigin.IMPORTED&&before.locationId().isPresent()&&before.evidence()==EvidenceStatus.CONFIRMED);
  }
  return false;
 }
 private boolean arguments(EffectIntent intent){
  var before=lease.before();
  return intent.primitive().equals(ID)&&intent.version()==1&&intent.operationToken().equals(lease.operation())&&
   intent.typedArgs() instanceof Trace trace&&trace.persistent()&&before.active()&&
   before.tags().contains(trace.anchorTag())&&!before.tags().contains(trace.stateTag())&&
   intent.expectedFootprint().dimension().equals(before.dimension())&&intent.expectedFootprint().cells().equals(Set.of(before.position()));
 }
 public EffectPreparation capture(EffectIntent intent,PlanningSnapshot context){
  actor();if(!arguments(intent)||!owner.unchanged(lease)||!actualAnchor(server,lease.before()))
   throw new IllegalArgumentException("real unchanged camp anchor required");
  return prepareReadOnly(intent,context);
 }
 /** Planning capture observes the same real durable anchor without acquiring the mutation lane. */
 public EffectPreparation preview(EffectIntent intent,PlanningSnapshot context){
  actor();if(!arguments(intent)||!owner.durablyCurrent(lease.before())||!actualAnchor(server,lease.before()))
   throw new IllegalArgumentException("actual durable tagged anchor required");
  return prepareReadOnly(intent,context);
 }
 private EffectPreparation prepareReadOnly(EffectIntent intent,PlanningSnapshot context){
  var before=lease.before();var trace=(Trace)intent.typedArgs();var tags=new HashSet<>(before.tags());tags.add(trace.stateTag());
  var h=before.header();after=new AnchorRecord(new AggregateHeader(1,h.id(),h.revision().plus(1),h.lastSequence(),clock.get()),
   before.anchorId(),before.locationId(),before.regionIds(),before.dimension(),before.position(),before.radiusProfile(),
   tags,before.origin(),before.evidence(),before.active());
  var pre=state(before);var post=state(after);
  var plan=BlobContent.capture(new ResourceId("proutlost_director:trace_intent_v1"),1,new ModelCodec<>(EffectIntent.class).encode(intent));
  var key=key(before);var epoch=new Epoch(before.header().revision().value());
  var read=new ReadSet(context.snapshot().scopeRevisions(),Map.of(key,epoch),Map.of(key,pre.reference().hash()));
  var claim=new ResourceClaim(key,ClaimPolicy.EXCLUSIVE,BudgetMath.zero(),Optional.empty(),epoch);
  var refs=new EffectBlobs(Set.of(pre.reference(),post.reference()),plan.reference(),plan.reference(),
   Map.of(key,pre.reference()),Map.of(key,post.reference()));
  var prepared=new PreparedEffect(intent,context.snapshot(),List.of(claim),read,Map.of(key,post.reference().hash()),
   BudgetMath.zero(),List.of(),capabilities(),refs);
  return new EffectPreparation(intent,new ValidationResult(true,List.of(),read),Optional.of(prepared),new BlobBundle(List.of(pre,post,plan)));
 }
 private boolean contains(BlobBundle blobs,BlobContent exact){
  return blobs.contents().stream().anyMatch(b->b.reference().equals(exact.reference())&&Arrays.equals(b.bytes(),exact.bytes()));
 }
 public boolean revalidate(PreparedEffect plan,BlobBundle blobs){
  actor();return after!=null&&arguments(plan.intent())&&owner.unchanged(lease)&&actualAnchor(server,lease.before())&&
   plan.capabilities().equals(capabilities())&&contains(blobs,state(lease.before()))&&contains(blobs,state(after))&&
   plan.expectedPost().equals(Map.of(key(after),state(after).reference().hash()));
 }
 public SliceResult apply(PreparedEffect plan,BlobBundle blobs){
  actor();if(!revalidate(plan,blobs))throw new IllegalStateException("Trace changed before apply");
  owner.applyTrace(lease,after);applied=true;var actual=owner.current(after.anchorId()).orElseThrow();
  if(!actual.equals(after))throw new IllegalStateException("Trace CAS post-state mismatch");
  return new SliceResult(Outcome.SUCCEEDED,Set.of(plan.intent().operationToken()),
   Map.of(key(actual),state(actual).reference().hash()),0,Set.of(),Set.of(),List.of());
 }
 public boolean wasApplied(){actor();return applied;}
 public AnchorRecord after(){actor();return Objects.requireNonNull(after,"Trace not captured");}
 public ReconcileResult reconcile(RecoveryProbe probe){
  actor();var current=owner.current(lease.before().anchorId());
  var status=current.filter(a->after!=null&&a.equals(after)).isPresent()?ReconcileStatus.SAME_POST:ReconcileStatus.UNAVAILABLE;
  return new ReconcileResult(status,probe.loadedProofs(),List.of());
 }
}
