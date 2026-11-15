package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record DurableRequest(DirectorEnvelope original, RequestView request, Fingerprint idempotenceHash, Fingerprint schedulingPolicyHash) implements DurableRecord {
 public DurableRequest(DirectorEnvelope original,RequestView request,Fingerprint idempotenceHash){this(original,request,idempotenceHash,policyHash(request.state().schedulingPolicy()));}
 /** Internal retry identity compares BOTH semantic payload and scheduling policy hashes. */
 public static Fingerprint policyHash(RequestSchedulingPolicy policy){return BlobContent.hash(new fr.proutlost.director.api.codec.ModelCodec<>(RequestSchedulingPolicy.class).encode(policy));}
 public DurableRequest { require(policyHash(request.state().schedulingPolicy()).equals(schedulingPolicyHash),"scheduling policy hash"); Objects.requireNonNull(original); Objects.requireNonNull(request); Objects.requireNonNull(idempotenceHash); require(original.payload() instanceof RequestMessage && ((RequestMessage)original.payload()).value().equals(request.request().payload()),"original request payload"); require(MessageContracts.request(original).equals(request.request()),"original request envelope"); require(idempotenceHash.equals(RequestContracts.idempotenceHash(request.request())),"idempotence hash"); }
}
