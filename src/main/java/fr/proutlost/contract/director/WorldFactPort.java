package fr.proutlost.contract.director;

import java.util.*;
import static fr.proutlost.contract.director.Vocabulary.*;

/** Durable at-least-once delivery. Enqueue is part of result/outbox transaction. Consumer ACK follows its own durable dedup/progress commit. */
public interface WorldFactPort {
    CommitResult enqueue(StoreTransaction resultTransaction, FactOutboxEntry entry);
    List<FactOutboxEntry> replay(Id world, Id consumer, long afterCursor, int limit);
    Optional<WorldFact> scopedDelivery(Id world, Id consumer, Id factId, FactPlayerScope authorizedPlayers, FactGroupScope authorizedGroups);
    void acknowledge(Id world, Id consumer, Id factId);
}
