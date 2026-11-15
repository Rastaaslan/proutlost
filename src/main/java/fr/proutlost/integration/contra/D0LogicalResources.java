package fr.proutlost.integration.contra;
import fr.proutlost.contract.director.*;
import fr.proutlost.contract.director.Vocabulary.*;
import fr.proutlost.director.api.model.PhysicalResourceClaim;
import fr.proutlost.director.api.model.PhysicalCatalogModels;
import fr.proutlost.director.api.model.ResourceId;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
/** Exact capacity projection for physical D0 resources. Unknown actor/slot
 * authorities defer instead of being silently treated as a free resource. */
public final class D0LogicalResources {
 private D0LogicalResources(){}
 private static void require(boolean yes,String why){if(!yes)throw new IllegalArgumentException(why);}
 public static List<PhysicalResourceClaim> seal(EventNeed need,List<StaticLocationCatalogEntry> entries,PhysicalCatalogModels.Key source){
  var claims=new TreeMap<String,PhysicalResourceClaim>();
  for(var request:need.resources()){
   var key=request.key();int capacity;String identity;
   switch(key.kind()){
    case GLOBAL_MAJOR->{require(request.mode()==LeaseMode.EXCLUSIVE&&request.units()==1,"global-major fixed exclusive capacity");capacity=1;identity=source.directorWorld().value()+"/GLOBAL_MAJOR";}
    case LOCATION,PHYSICAL_FOOTPRINT->{var found=entries.stream().filter(e->e.location_id().equals(key.scope_id())).toList();require(found.size()==1,"resource requires exact selected physical location");var e=found.getFirst();dimension(key,e);capacity=capacity(e.anchors(),request);identity=physical(source,key);}
    case ANCHOR->{var location=entries.stream().filter(e->e.location_id().equals(key.scope_id())).toList();require(location.size()==1&&key.sub_id()!=null,"anchor resource requires explicit selected location/local anchor");var e=location.getFirst();dimension(key,e);var anchors=e.anchors().stream().filter(a->a.anchor_id().equals(key.sub_id())).toList();require(anchors.size()==1,"actual local anchor unavailable");capacity=anchors.getFirst().capacity();identity=physical(source,key);}
    case SURFACE->{var location=entries.stream().filter(e->e.location_id().equals(key.scope_id())).toList();require(location.size()==1&&key.sub_id()!=null,"surface resource requires explicit selected location/surface");var e=location.getFirst();dimension(key,e);var surfaces=e.surfaces().stream().filter(a->a.surface_id().equals(key.sub_id())).toList();require(surfaces.size()==1,"actual interaction surface unavailable");var s=surfaces.getFirst();capacity=!s.slot_ids().isEmpty()?s.slot_ids().size():capacity(e.anchors().stream().filter(a->s.anchor_ids().contains(a.anchor_id())).toList(),request);identity=physical(source,key);}
    case SEMANTIC_REGION->{require(entries.stream().anyMatch(e->e.semantic_region_ids().stream().anyMatch(r->r.value().equals(key.scope_id()))),"resource semantic region outside selected source");require(request.mode()==LeaseMode.EXCLUSIVE&&request.units()==1,"semantic region has no authored shared capacity");capacity=1;identity=physical(source,key);}
    default->throw new IllegalArgumentException("D0 resource requires an installed current actor/channel/slot authority: "+key.kind());
   }
   var mode=request.mode()==LeaseMode.EXCLUSIVE?PhysicalResourceClaim.Mode.EXCLUSIVE:PhysicalResourceClaim.Mode.SHARED;
   var id=new ResourceId("proutlost_director:d0_resource/"+sha(identity));var value=new PhysicalResourceClaim(id,mode,request.units(),capacity);
   require(claims.putIfAbsent(id.value(),value)==null,"duplicate D0 resource request");
  }
  require(claims.size()<=256,"D0 resource count bound");return List.copyOf(claims.values());
 }
 private static int capacity(List<Anchor> anchors,ResourceRequest request){
  if(anchors.isEmpty()){require(request.mode()==LeaseMode.EXCLUSIVE&&request.units()==1,"unknown shared physical capacity");return 1;}
  long n=anchors.stream().mapToLong(Anchor::capacity).sum();require(n>0&&n<=4096,"actual bounded anchor capacity");return (int)n;
 }
 private static void dimension(fr.proutlost.contract.director.ResourceKey key,StaticLocationCatalogEntry entry){require(key.dimension_id()!=null&&entry.geometry().stream().allMatch(b->b.dimension_id().equals(key.dimension_id())),"resource actual dimension differs");}
 private static String physical(PhysicalCatalogModels.Key source,fr.proutlost.contract.director.ResourceKey key){return source.directorWorld().value()+"/"+source.sourceWorld().value()+"/"+source.sourceBuild().value()+"/"+StrictCodec.encode(key);}
 private static String sha(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}}
}
