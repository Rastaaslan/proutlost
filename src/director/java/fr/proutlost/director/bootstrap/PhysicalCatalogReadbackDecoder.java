package fr.proutlost.director.bootstrap;
import fr.proutlost.director.api.model.*;import fr.proutlost.director.api.model.PhysicalCatalogModels.Readback;
import java.util.*;import java.util.concurrent.*;import java.util.function.Function;
/** Pure readback conversion on the existing owner worker. This grants no authority;
 * the owner verifies the read/session and the consumer rechecks its live grant after decoding. */
public final class PhysicalCatalogReadbackDecoder {
 private PhysicalCatalogReadbackDecoder(){}
 public static <T>CompletionStage<T> read(CompletionStage<BlobReadResult> actual,Readback expected,Executor worker,Function<byte[],T> decoder){
  Objects.requireNonNull(actual);Objects.requireNonNull(expected);Objects.requireNonNull(worker);Objects.requireNonNull(decoder);
  return actual.thenApplyAsync(read->{
   if(read==null||read.status()!=AckStatus.DURABLE||read.contents().size()!=1||!read.contents().getFirst().reference().equals(expected.completeSource()))
    throw new IllegalStateException("actual exact J04 source read unavailable/refused");
   return decoder.apply(read.contents().getFirst().bytes());
  },worker);
 }
}
