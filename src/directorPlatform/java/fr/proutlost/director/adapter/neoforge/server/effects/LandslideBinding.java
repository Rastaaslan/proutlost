package fr.proutlost.director.adapter.neoforge.server.effects;
import fr.proutlost.director.api.codec.CanonicalJsonV1;
import fr.proutlost.director.api.model.*;
import java.math.BigDecimal;
import java.util.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;

/** Bounded native debris hit. A durable forward PREPARE consumes the whole hit allowance. */
public final class LandslideBinding implements ServerEffectPort.Binding {
 private static final PrimitiveId ID=new PrimitiveId("proutlost:landslide");
 private static final ResourceId PROFILE=new ResourceId("proutlost:test_debris_damage");
 private static final ResourceId LEDGER=new ResourceId("proutlost_director:landslide_damage_v1");
 private static final Set<CapabilityId> CAPS=Set.of(new CapabilityId("mutation.terrain_v1"),new CapabilityId("danger.debris_v1"));
 private final BlockTransformBinding base;private final MinecraftServer server;
 private final PlayerId witness;private final boolean damageEnabled;
 private final Map<PlayerId,JsonValue> observations=new LinkedHashMap<>();
 public LandslideBinding(BlockTransformBinding base,MinecraftServer server,PlayerId witness,boolean damageEnabled){
  this.base=Objects.requireNonNull(base);this.server=Objects.requireNonNull(server);
  this.witness=Objects.requireNonNull(witness);this.damageEnabled=damageEnabled;
  if(!base.supportedPrimitives().contains(ID))throw new IllegalArgumentException("landslide base required");
 }
 private void actor(){if(!server.isSameThread())throw new IllegalStateException("server actor");}
 public ProviderMode mode(){return base.mode();}
 public Set<PrimitiveId> supportedPrimitives(){return Set.of(ID);}
 public Set<CapabilityId> capabilities(){return CAPS;}
 private static JsonValue.Number number(double v){return new JsonValue.Number(BigDecimal.valueOf(v));}
 private BlobContent ledger(EffectIntent intent){
  if(!intent.primitive().equals(ID)||intent.version()!=1||!(intent.typedArgs() instanceof Landslide a)||
     !a.damageProfile().equals(PROFILE))throw new IllegalArgumentException("bounded damage profile required");
  return BlobContent.capture(LEDGER,1,CanonicalJsonV1.encode(new JsonValue.ObjectValue(Map.of(
   "player",new JsonValue.Text(witness.value().toString()),
   "operation",new JsonValue.Text(intent.operationToken().value().toString()),
   "profile",new JsonValue.Text(PROFILE.value()),"source",new JsonValue.Text("minecraft:falling_block"),
   "amount",number(2),"enabled",new JsonValue.Bool(damageEnabled)))));
 }
 public EffectPreparation capture(EffectIntent intent,PlanningSnapshot context){
  actor();var damage=ledger(intent);var captured=base.capture(intent,context);
  if(captured.prepared().isEmpty())return captured;
  var p=captured.prepared().orElseThrow();var old=p.durableBlobs();
  var snapshots=new HashSet<>(old.snapshots());snapshots.add(damage.reference());
  var refs=new EffectBlobs(snapshots,old.preparedPlan(),old.stopPlan(),old.preStates(),old.expectedPostStates());
  var plan=new PreparedEffect(p.intent(),p.snapshot(),p.claims(),p.preconditions(),p.expectedPost(),
   p.budgetEstimate(),p.inversePlan(),CAPS,refs);
  var bytes=new ArrayList<>(captured.blobs().contents());bytes.add(damage);
  return new EffectPreparation(intent,captured.validation(),Optional.of(plan),new BlobBundle(bytes));
 }
 public boolean revalidate(PreparedEffect plan,BlobBundle blobs){
  actor();
  try{
   if(!plan.capabilities().equals(CAPS))return false;
   // Physical placement must not trap a player who entered during PREPARE/fsync.
   var stone=BlockTransformBinding.stateBlob(net.minecraft.world.level.block.Blocks.STONE.defaultBlockState()).reference().hash();
   var rubble=BlockTransformBinding.stateBlob(net.minecraft.world.level.block.Blocks.COBBLESTONE.defaultBlockState()).reference().hash();
   for(var cell:plan.intent().expectedFootprint().cells()){
    var k=new ResourceKey(OwnershipDomain.BLOCK,cell.dimension(),cell.x()+","+cell.y()+","+cell.z());
    var post=plan.expectedPost().get(k);
    if(!stone.equals(post)&&!rubble.equals(post))continue;
    for(var l:server.getAllLevels())if(l.dimension().location().toString().equals(cell.dimension().value())&&
     !l.getEntities(null,new AABB(cell.x(),cell.y(),cell.z(),cell.x()+1.,cell.y()+1.,cell.z()+1.)).isEmpty())return false;
   }
   var expected=ledger(plan.intent());
   var refs=plan.durableBlobs().snapshots().stream().filter(r->r.codecId().equals(LEDGER)).toList();
   var hydrated=blobs.contents().stream().filter(b->b.reference().equals(expected.reference())).toList();
   return refs.size()==1&&refs.getFirst().equals(expected.reference())&&hydrated.size()==1&&
    Arrays.equals(hydrated.getFirst().bytes(),expected.bytes())&&base.revalidate(plan,blobs);
  }catch(IllegalArgumentException invalid){return false;}
 }
 private boolean inside(ServerPlayer p,Footprint f){
  if(!p.serverLevel().dimension().location().toString().equals(f.dimension().value()))return false;
  var actual=p.getBoundingBox();
  if(!f.bounds().isEmpty())return f.bounds().stream().anyMatch(b->actual.intersects(
   new AABB(b.minX(),b.minY(),b.minZ(),b.maxXExclusive(),b.maxYExclusive(),b.maxZExclusive())));
  return f.cells().stream().anyMatch(b->actual.intersects(new AABB(b.x(),b.y(),b.z(),b.x()+1.,b.y()+1.,b.z()+1.)));
 }
 private Map<String,JsonValue> fact(String reason,boolean attempted){
  var v=new LinkedHashMap<String,JsonValue>();v.put("profile",new JsonValue.Text(PROFILE.value()));
  v.put("source",new JsonValue.Text("minecraft:falling_block"));v.put("requested_amount",number(2));
  v.put("attempted",new JsonValue.Bool(attempted));v.put("reason",new JsonValue.Text(reason));return v;
 }
 public SliceResult apply(PreparedEffect plan,BlobBundle blobs){
  actor();if(!revalidate(plan,blobs))throw new IllegalStateException("stale landslide");
  var result=base.apply(plan,blobs);var p=server.getPlayerList().getPlayer(witness.value());
  if(!damageEnabled||result.outcome()!=Outcome.SUCCEEDED||p==null||!p.isAlive()||!inside(p,plan.intent().expectedFootprint())){
   observations.put(witness,new JsonValue.ObjectValue(fact(!damageEnabled?"disabled":
    result.outcome()!=Outcome.SUCCEEDED?"terrain_incomplete":p==null?"disconnected":"outside_or_dead",false)));return result;
  }
  float h=p.getHealth(),a=p.getAbsorptionAmount();
  boolean accepted=p.hurt(p.damageSources().fallingBlock(null),2.0F);
  float h2=p.getHealth(),a2=p.getAbsorptionAmount();double loss=Math.max(0,(double)h+a-h2-a2);
  var v=fact(!accepted?"native_refused":loss>0?"damage_observed":"no_damage_observed",true);
  v.put("accepted",new JsonValue.Bool(accepted));v.put("health_before",number(h));v.put("health_after",number(h2));
  v.put("absorption_before",number(a));v.put("absorption_after",number(a2));v.put("observed_loss",number(loss));
  v.put("damage_confirmed",new JsonValue.Bool(loss>0));observations.put(witness,new JsonValue.ObjectValue(v));
  System.out.println("DIRECTOR_LAB_LANDSLIDE_DAMAGE player="+witness.value()+" accepted="+accepted+" loss="+loss);
  return result;
 }
 public Map<PlayerId,JsonValue> observedDamageFacts(){actor();return Map.copyOf(observations);}
 public ReconcileResult reconcile(RecoveryProbe probe){actor();return new ReconcileResult(ReconcileStatus.UNAVAILABLE,probe.loadedProofs(),List.of());}
}
