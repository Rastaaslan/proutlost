package fr.proutlost.director.api.model;
import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;
/** Pure shared J04/J07/J08 binding algorithm, no IO/cache/authority. SHA256 of canonical ModelCodec
 * bytes: StoreQuery with pageCursor=null (world, keys, lower-bound sequence and limit retained);
 * full ConsumerSpec (consumer, filters, nextSequence, batchLimit). Canonical sets sort deterministically.
 * Producers MUST validate query/session and retained cursor position before AVAILABLE. Consumers
 * MUST validate the response against their original request/current session. Hashes are correlation,
 * not proofs of disk integrity. Lost snapshot/session/invalid cursor -> typed STALE, never empty success.
 */
public final class ReadContracts {
 private ReadContracts(){}
 public static Fingerprint queryHash(StoreQuery q){return BlobContent.hash(new fr.proutlost.director.api.codec.ModelCodec<>(StoreQuery.class).encode(new StoreQuery(q.worldId(),q.aggregateKeys(),q.sequence(),Optional.empty(),q.limit())));}
 public static Fingerprint queryHash(ConsumerSpec q){return BlobContent.hash(new fr.proutlost.director.api.codec.ModelCodec<>(ConsumerSpec.class).encode(q));}
 public static String encodeCursor(StoreReadCursor cursor){var encoded=Base64.getUrlEncoder().withoutPadding().encodeToString(new fr.proutlost.director.api.codec.ModelCodec<>(StoreReadCursor.class).encode(cursor));require(encoded.length()<=4096,"cursor encoded bound");return encoded;}
 public static StoreReadCursor decodeCursor(String value){require(value.length()<=4096,"cursor bound");var cursor=new fr.proutlost.director.api.codec.ModelCodec<>(StoreReadCursor.class).decode(Base64.getUrlDecoder().decode(value));require(encodeCursor(cursor).equals(value),"noncanonical cursor");return cursor;}
 public static void validate(StoreReadResult result,StoreQuery query,StoreSession session){require(query.worldId().equals(session.worldId()),"query world mismatch");result.stamp().ifPresent(s->{require(s.session().equals(session)&&s.queryHash().equals(queryHash(query)),"query/session mismatch");query.pageCursor().ifPresent(c->require(decodeCursor(c).stamp().equals(s),"stale cursor snapshot"));});result.page().ifPresent(v->require((long)v.records().size()+v.aggregates().size()<=query.limit(),"query page limit"));}
 public static void validate(SubscriptionReadResult result,ConsumerSpec query,StoreSession session){result.stamp().ifPresent(s->require(s.store().session().equals(session)&&s.store().queryHash().equals(queryHash(query))&&s.expectedCursor().equals(query.nextSequence()),"consumer/session mismatch"));result.page().ifPresent(v->require(v.consumer().equals(query),"consumer mismatch"));}
 /** Empty filters select all durable envelopes; nonempty filters select matching fact types only. */
 public static boolean matches(ConsumerSpec consumer,DirectorEnvelope message){return consumer.filters().isEmpty()||message.payload() instanceof FactPayload f&&consumer.filters().contains(f.value().factType());}
}
