package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record RoleAssignments(Map<String,List<PlayerId>> roles) {
 public RoleAssignments { roles=map(roles); var copied=new HashMap<String,List<PlayerId>>(); roles.forEach((k,v)->copied.put(k,list(v))); roles=Map.copyOf(copied); }
}
