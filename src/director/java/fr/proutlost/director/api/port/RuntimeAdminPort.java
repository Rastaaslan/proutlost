package fr.proutlost.director.api.port;
import fr.proutlost.director.api.model.*;
/** J05 actor-owned bounded nonblocking mailbox, consumed by J07 facade (not another AdminPort).
 * submit authenticates through injected ActorAuthorityPort and returns correlated PENDING or
 * explicit REJECTED/DEFERRED. Idempotency key is (actorSession,requestId); compare full canonical
 * command hash, rejecting changed payload/revision/operation. poll returns latest correlated
 * receipt/result without waiting. Unknown/evicted IDs never silently reexecute: durable audit
 * index or explicit DEFERRED required. Recheck current session authority and revision at application.
 * J05 writes RuntimeAdminAudit atomically with control change before terminal success; all effects
 * still require normal permits or the distinct forced stabilization authority. J07 cannot mutate
 * runner state. Bootstrap Work wires the resolver, mailbox and durable audit sink.
 */
public interface RuntimeAdminPort {
 AdminResult submit(AdminCommand command);
 AdminResult poll(RuntimeAdminKey key);
}
