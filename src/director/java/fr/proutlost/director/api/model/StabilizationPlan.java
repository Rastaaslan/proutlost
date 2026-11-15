package fr.proutlost.director.api.model;
import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;
/** Canonical stop blob payload. Only PREPARE validation/forced ACK can turn this data into authority. */
public record StabilizationPlan(List<StabilizationSlice> slices) {
 public StabilizationPlan {
  slices=list(slices);require(!slices.isEmpty()&&slices.size()<=64,"stop tranche bound");
  require(slices.stream().map(s->s.slice().sliceId()).distinct().count()==slices.size(),"duplicate stop slice");
  require(slices.stream().map(s->s.slice().operationToken()).distinct().count()==slices.size(),"duplicate stop token");
  var ops=slices.stream().flatMap(s->s.slice().operations().stream()).toList();
  require(ops.stream().map(EffectIntent::operationToken).distinct().count()==ops.size(),"duplicate stop operation");
 }
 public BlobContent blob(){return BlobContent.capture(new ResourceId("proutlost:stabilization_plan"),1,new fr.proutlost.director.api.codec.ModelCodec<>(StabilizationPlan.class).encode(this));}
}
