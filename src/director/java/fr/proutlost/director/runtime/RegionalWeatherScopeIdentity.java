package fr.proutlost.director.runtime;
import fr.proutlost.director.api.codec.*;
import fr.proutlost.director.api.model.*;
import java.util.*;
/** A region's physical membership/shape identity is independent of activity and
 * personality revisions. Actual J04 aggregate headers still fence every slice. */
public final class RegionalWeatherScopeIdentity {
 private RegionalWeatherScopeIdentity(){}
 public static boolean matches(RegionRecord region,RegionalWeatherLease lease){
  return region.status()==RegionStatus.ACTIVE&&region.dimension().equals(lease.dimension())&&region.membershipRevision().equals(lease.externalRevision());
 }
 public static Fingerprint fingerprint(RegionRecord region){
  return BlobContent.hash(CanonicalJsonV1.encode(new JsonValue.ObjectValue(Map.of(
   "region",new JsonValue.Text(region.regionId().value().toString()),
   "dimension",new JsonValue.Text(region.dimension().value()),
   "membership_revision",new JsonValue.Text(Long.toString(region.membershipRevision().value())),
   "shape",CanonicalJsonV1.decode(new ModelCodec<>(SupportShape.class).encode(region.supportShape()))))));
 }
 /** EVENT role scopes do not enumerate the incidental audience. Recover it from
  * the actual immutable J03 snapshot, including after cold context hydration. */
 public static Set<PlayerId> affectedAudience(SnapshotBatch snapshot,Footprint footprint){
  var out=new HashSet<PlayerId>();
  snapshot.context().players().forEach((id,player)->{
   var p=player.position().position();
   if(p.dimension().equals(footprint.dimension())&&footprint.bounds().stream().anyMatch(box->
    p.x()>=box.minX()&&p.x()<box.maxXExclusive()&&p.y()>=box.minY()&&p.y()<box.maxYExclusive()&&p.z()>=box.minZ()&&p.z()<box.maxZExclusive()))out.add(id);
  });
  return Set.copyOf(out);
 }

 /** NativeCatalogueInput advances only the observation clock on a durable WORLD
  * head. Header/CAS, control, active profile, catalog, session and engine state
  * must remain exactly identical; the projected clock stays bounded by now. */
 public static boolean sameDurableWorld(DirectorWorldState durable,DirectorWorldState projected,Tick now){
  if(projected.simTick().compareTo(durable.simTick())<0||projected.simTick().value()>Math.max(durable.simTick().value(),now.value()))return false;
  var normalized=new DirectorWorldState(projected.header(),projected.worldId(),projected.storeGeneration(),durable.simTick(),projected.runControl(),
   projected.healthMode(),projected.pacingState(),projected.tension(),projected.intensityProfileId(),projected.catalogHash(),projected.configHash(),projected.bootEpoch(),projected.lastCheckpointSeq(),projected.engineControl());
  return durable.equals(normalized);
 }

}
