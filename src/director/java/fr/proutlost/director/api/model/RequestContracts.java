package fr.proutlost.director.api.model;
public final class RequestContracts {
 private RequestContracts() {}
 /** Hash binds semantic request payload, producer and scope; delivery IDs/sequences may change on retry. */
 public static Fingerprint idempotenceHash(DirectorRequest request){
  var codec=new fr.proutlost.director.api.codec.ModelCodec<>(RequestPayload.class);
  var scope=new fr.proutlost.director.api.codec.ModelCodec<>(ScopeRef.class);
  var value=new JsonValue.ObjectValue(java.util.Map.of("producer_id",new JsonValue.Text(request.producerId().value()),"producer_key",new JsonValue.Text(request.producerKey()),"scope",fr.proutlost.director.api.codec.CanonicalJsonV1.decode(scope.encode(request.scope())),"payload",fr.proutlost.director.api.codec.CanonicalJsonV1.decode(codec.encode(request.payload()))));
  return BlobContent.hash(fr.proutlost.director.api.codec.CanonicalJsonV1.encode(value));
 }
 public static RequestId id(RequestPayload payload){return switch(payload){case DirectorPreference p -> p.requestId();case DirectorRequirement r -> r.requestId();case CancelPayload c -> c.requestId();};}
}
