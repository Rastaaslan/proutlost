package fr.proutlost.director.api.model;
import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;
/** Exact preauthorized stop tranche. Expected ownership is the captured post-impact state.
 * Unsupported shared, yielded or unknown resources require reconciliation, never a guessed inverse.
 */
public record StabilizationSlice(PreparedSlice slice, Map<ResourceKey,OwnershipRecord> expectedOwnership) {
 public StabilizationSlice {
  Objects.requireNonNull(slice);expectedOwnership=map(expectedOwnership);
  require(!slice.operations().isEmpty()&&slice.operations().size()<=64&&!expectedOwnership.isEmpty()&&expectedOwnership.size()<=4096,"stop bounds");
  require(slice.preconditions().epochs().keySet().equals(expectedOwnership.keySet())&&slice.preconditions().fingerprints().keySet().equals(expectedOwnership.keySet()),"stop resource proofs");
  for(var entry:expectedOwnership.entrySet()){
   var r=entry.getValue();require(entry.getKey().equals(r.resourceKey())&&r.resourceKey().dimension().equals(slice.footprint().dimension()),"stop resource binding");
   require(r.disposition()==OwnershipDisposition.DIRECTOR_ACTIVE&&!r.ownerTokens().isEmpty(),"exclusive active ownership required");
   require(r.epoch().equals(slice.preconditions().epochs().get(entry.getKey()))&&r.expectedPostHash().equals(slice.preconditions().fingerprints().get(entry.getKey())),"stop epoch/hash binding");
  }
 }
}
