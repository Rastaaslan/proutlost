package fr.proutlost.director.api.model;
import java.util.*;
/** Logical D0 use on the existing global arbiter, separate from world-write
 * claims. Capacity comes from the actual selected source, never a budget alias. */
public record PhysicalResourceClaim(ResourceId resource,Mode mode,int units,int capacity) {
 public enum Mode { SHARED,EXCLUSIVE }
 public PhysicalResourceClaim {Objects.requireNonNull(resource);Objects.requireNonNull(mode);if(units<1||capacity<1||units>capacity||capacity>4096)throw new IllegalArgumentException("bounded physical resource capacity");}
}
