package fr.proutlost.director.bootstrap;

import fr.proutlost.director.adapter.neoforge.server.effects.BlockTransformBinding;
import fr.proutlost.director.adapter.neoforge.server.ownership.ServerOwnershipAttachments;
import fr.proutlost.director.api.codec.*;
import fr.proutlost.director.api.model.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/** Read-only PlanningPort evidence. No RESERVED owner, physical prepare, permit,
 * application or successful result is created here. Native execution publishes its
 * real ownership dossier after J03 and captures J05 again with the SAME intent.
 * Definition-level entity/block costs follow the unchanged catalogue contract;
 * the native suboperation admits its actual IO/navigation overhead separately. */
public final class NativeCataloguePhysicalPlanning {
 private static final ServerOwnershipAttachments ATTACHED=new ServerOwnershipAttachments(64);
 private NativeCataloguePhysicalPlanning(){}
 public record Survey(String step,Footprint footprint,List<ResourceKey> claimed,BudgetVector cost){
  public Survey {Objects.requireNonNull(step);Objects.requireNonNull(footprint);claimed=List.copyOf(claimed);Objects.requireNonNull(cost);}
 }
 public static UUID goatUuid(EventInstanceId event){return named("catalogue-goat-v1",event.value());}
 public static UUID faunaUuid(EventInstanceId event,int index){
  if(index<0||index>=24)throw new IllegalArgumentException("cohort index");
  return named("catalogue-fauna-v1",event.value(),index);
 }
 private static UUID named(Object... pieces){
  return UUID.nameUUIDFromBytes(String.join("\0",Arrays.stream(pieces).map(Object::toString).toList()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
 }
 public static ResourceKey block(BlockPosition p){return new ResourceKey(OwnershipDomain.BLOCK,p.dimension(),p.x()+","+p.y()+","+p.z());}
 public static ResourceKey entity(DimensionId dimension,UUID uuid){return new ResourceKey(OwnershipDomain.ENTITY,dimension,uuid.toString());}
 private static ServerLevel level(MinecraftServer server,DimensionId dimension){
  for(var l:server.getAllLevels())if(l.dimension().location().toString().equals(dimension.value()))return l;
  throw new IllegalArgumentException("native planning dimension unavailable");
 }
 private static Set<BlockPosition> cells(Footprint footprint){
  var result=new LinkedHashSet<>(footprint.cells());
  for(var b:footprint.bounds()){
   long count=(long)(b.maxXExclusive()-b.minX())*(b.maxYExclusive()-b.minY())*(b.maxZExclusive()-b.minZ());
   if(count>1024)throw new IllegalArgumentException("planning geometry read bound");
   for(int x=b.minX();x<b.maxXExclusive();x++)for(int y=b.minY();y<b.maxYExclusive();y++)for(int z=b.minZ();z<b.maxZExclusive();z++){
    result.add(new BlockPosition(b.dimension(),x,y,z));if(result.size()>1024)throw new IllegalArgumentException("planning read bound");
   }
  }
  if(result.isEmpty()||result.size()>1024)throw new IllegalArgumentException("actual bounded physical geometry required");
  return result;
 }
 public static DirectorNativeCatalogueExecution.Preview capture(MinecraftServer server,Candidate candidate,DecisionInput input,
   EventInstanceId event,List<Survey> surveys,Set<PlayerId> actualAudience){
  if(!server.isSameThread())throw new IllegalStateException("native planning actor");
  var definition=Objects.requireNonNull(input.catalog().events().get(candidate.definition().id()));
  if(surveys.size()!=definition.steps().size())throw new IllegalArgumentException("all original steps required");
  var preparations=new ArrayList<EffectPreparation>();
  for(int index=0;index<surveys.size();index++){
   var survey=surveys.get(index);var step=definition.steps().get(index);var fp=survey.footprint();
   if(!step.id().equals(survey.step())||fp.loadedChunks().size()>64||!input.context().loadedChunks().containsAll(fp.loadedChunks()))
    throw new IllegalArgumentException("actual step/loaded footprint mismatch");
   var nativeLevel=level(server,fp.dimension());
   var epochs=new HashMap<ResourceKey,Epoch>();var hashes=new HashMap<ResourceKey,Fingerprint>();
   var pre=new LinkedHashMap<ResourceKey,BlobRef>();var contents=new LinkedHashMap<BlobRef,BlobContent>();
   for(var position:cells(fp)){
    var key=block(position);var pos=new BlockPos(position.x(),position.y(),position.z());
    if(!position.dimension().equals(fp.dimension())||nativeLevel.getChunkSource().getChunkNow(pos.getX()>>4,pos.getZ()>>4)==null)
     throw new IllegalArgumentException("physical planning chunk unavailable");
    var attached=ATTACHED.read(nativeLevel,pos).orElse(null);var durable=input.state().ownership().records().get(key);
    if(!Objects.equals(attached,durable))throw new IllegalStateException("ownership dossier has not reached the selected WAL cut");
    var blob=BlockTransformBinding.stateBlob(nativeLevel.getBlockState(pos));contents.put(blob.reference(),blob);pre.put(key,blob.reference());
    hashes.put(key,blob.reference().hash());epochs.put(key,durable==null?new Epoch(0):durable.epoch());
   }
   var claims=new ArrayList<ResourceClaim>();
   for(var key:survey.claimed()){
    if(!key.dimension().equals(fp.dimension()))throw new IllegalArgumentException("claim dimension");
    if(key.domain()==OwnershipDomain.ENTITY){
     var uuid=UUID.fromString(key.key());for(var l:server.getAllLevels())if(l.getEntity(uuid)!=null)throw new IllegalStateException("entity UUID already present");
     // The driver separately checked the hydrated durable EntityOwnership registry.
     var absence=BlobContent.capture(new ResourceId("proutlost_director:catalogue_entity_absence_v1"),1,
      CanonicalJsonV1.encode(new JsonValue.ObjectValue(Map.of("entity",new JsonValue.Text(uuid.toString()),
       "native_entity_present",new JsonValue.Bool(false),"durable_entity_slot_present",new JsonValue.Bool(false)))));
     contents.put(absence.reference(),absence);pre.put(key,absence.reference());hashes.put(key,absence.reference().hash());epochs.put(key,new Epoch(0));
    }else if(!hashes.containsKey(key))throw new IllegalArgumentException("claim outside observed geometry");
    claims.add(new ResourceClaim(key,ClaimPolicy.EXCLUSIVE,BudgetMath.zero(),Optional.empty(),epochs.get(key)));
   }
   var intent=NativeCataloguePlanning.intent(candidate,definition,step,event,fp,input);
   var plan=BlobContent.capture(new ResourceId("proutlost_director:catalogue_native_plan_v1"),1,new ModelCodec<>(EffectIntent.class).encode(intent));
   contents.put(plan.reference(),plan);var read=new ReadSet(input.context().scopeRevisions(),epochs,hashes);
   var refs=new EffectBlobs(Set.copyOf(pre.values()),plan.reference(),plan.reference(),pre,Map.of());
   var primitive=Objects.requireNonNull(input.catalog().primitives().get(step.primitive()));
   var prepared=new PreparedEffect(intent,input.context(),claims,read,Map.of(),survey.cost(),List.of(),primitive.capabilities(),refs);
   preparations.add(new EffectPreparation(intent,new ValidationResult(true,List.of(),read),Optional.of(prepared),new BlobBundle(List.copyOf(contents.values()))));
  }
  return NativeCataloguePlanning.captured(candidate,input,event,preparations,actualAudience);
 }
}
