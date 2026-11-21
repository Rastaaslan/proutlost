package fr.proutlost.director.api.port;
import fr.proutlost.director.api.model.*;
import java.util.concurrent.CompletionStage;
/** Trusted single-writer contract. No method may block the server actor.
 * append forces every referenced blob before the WAL commit and ACK. ACK.forcedBlobs attests
 * all PREPARE references, including already durable blobs, with exact hash/codec/version/length.
 * A permit attempt is an atomic durable claim of (world,reservation,generation,operationToken).
 * Reusing a transaction returns its ORIGINAL ACK; a different transaction cannot reacquire a
 * claimed operation, even after reboot. Reconciliation uses a new explicitly admitted generation.
 * Operation claims/tombstones are durable state, never an evicting volatile deduplication cache.
 * A session is exclusive to one Authority; recovery publishes a new boot/session fence before
 * admissions. Old-session appends are rejected. Reopening an old session is forbidden.
 */
public interface DurableStore {
 CompletionStage<CommitAck> append(DurableBatch batch);
 /** Memory projection only: never performs disk IO. */
 StoreReadResult view(StoreQuery query);
 /** Bounded off-actor IO hydration or typed UNAVAILABLE/CORRUPT/STALE. Sync cache misses are
  * NOT_RESIDENT, never absence or expected exceptions. Validate ReadContracts binding before
  * AVAILABLE; snapshot retention loss is STALE. No cursor advances on read. Completion is DTO
  * delivery only, never permission to mutate Minecraft. */
 CompletionStage<StoreReadResult> read(StoreQuery query);
 /** Current exclusive boot/authority binding, published by the trusted store/bootstrap. */
 StoreSession session();
 /** Async bounded read, verifies exact length/hash/codec and returns copied immutable contents. */
 CompletionStage<BlobReadResult> readBlobs(BlobReadRequest request);
}
