package fr.proutlost.worldprep.mutation;
import java.util.*;
/** Complete inspectable multi-position intent; journal the entire group before its first write. */
public record MutationGroup(UUID id,List<Mutation> mutations){
 public record Mutation(int x,int y,int z,String before,String applied){}
 public MutationGroup{mutations=List.copyOf(mutations);if(mutations.isEmpty())throw new IllegalArgumentException("Empty mutation group");var positions=new HashSet<String>();for(var m:mutations)if(!positions.add(m.x()+":"+m.y()+":"+m.z()))throw new IllegalArgumentException("Duplicate position");}
}
