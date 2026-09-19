package fr.proutlost.worldprep.structure;
import fr.proutlost.worldprep.plan.CanonicalDigest;
import java.util.*;
/** Order-independent global allocator: canonical scoring occurs before spacing allocation. */
public final class AutomaticStructurePlanner {
 public record Candidate(String structureId,int x,int y,int z,int suitability){public Candidate{if(structureId==null||structureId.isBlank())throw new IllegalArgumentException();}}
 public List<StructureInstancePlan> plan(Collection<Candidate> input,StructureCatalog catalog,long seed,int limit){
  if(limit<0)throw new IllegalArgumentException("limit"); var ordered=input.stream().sorted(Comparator.comparingInt(Candidate::suitability).reversed().thenComparing(Candidate::structureId).thenComparingInt(Candidate::x).thenComparingInt(Candidate::z).thenComparingInt(Candidate::y)).toList(); var result=new ArrayList<StructureInstancePlan>();
  for(var c:ordered){if(result.size()>=limit)break;var cap=catalog.requireProductionSupported(c.structureId());long mixed=mix(seed,c);var rotation=StructureInstancePlan.Rotation.values()[Math.floorMod(mixed,4)];int halfX=(cap.width()-1)/2,halfZ=(cap.depth()-1)/2;var foot=new StructureInstancePlan.Bounds(c.x()-halfX,c.y(),c.z()-halfZ,c.x()+cap.width()/2,c.y()+255,c.z()+cap.depth()/2);var reserve=new StructureInstancePlan.Bounds(foot.minX()-cap.clearance(),foot.minY(),foot.minZ()-cap.clearance(),foot.maxX()+cap.clearance(),foot.maxY(),foot.maxZ()+cap.clearance());if(result.stream().anyMatch(p->p.reservation().intersects(reserve)))continue;String id=CanonicalDigest.sha256().putString(c.structureId()).putInt(c.x()).putInt(c.y()).putInt(c.z()).putLong(seed).finish();result.add(new StructureInstancePlan(c.structureId(),id,c.x(),c.y(),c.z(),rotation,StructureInstancePlan.Mirror.NONE,mixed,foot,reserve,reserve));}
  return List.copyOf(result);
 }
 private static long mix(long seed,Candidate c){return seed^((long)c.x()*341873128712L)^((long)c.z()*132897987541L)^c.structureId().hashCode();}
}
