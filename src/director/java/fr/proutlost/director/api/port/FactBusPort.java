package fr.proutlost.director.api.port;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.permission.CommitPermit;
import java.util.concurrent.CompletionStage;
/** G0 contract. Completion callbacks deliver data only; world access requires the server actor. */
public interface FactBusPort { /** Memory only: NOT_RESIDENT for cold data, no blocking or absence inference. */
 SubscriptionReadResult subscribe(ConsumerSpec consumer);
 /** Bounded off-actor IO, immutable typed result. Bind ReadContracts/session and stable retained
  * snapshot. Outbox sequences count messages, not commits. Reading NEVER advances consumer state.
  * Filtered empty hasMore pages attest only scanned interval. ACK retains original CAS,
  * delivered-message/filter/interval proofs, idempotence and fsync requirements unchanged. */
 CompletionStage<SubscriptionReadResult> readSubscription(ConsumerSpec consumer); CompletionStage<ConsumeAck> acknowledge(ConsumerAck ack); }
