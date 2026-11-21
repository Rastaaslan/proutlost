package fr.proutlost.contract.director;

import java.util.*;
import static fr.proutlost.contract.director.Vocabulary.*;

/** Single logical writer. A commit atomically CASes every row, dedup key and store sequence. No partial writes. D1 owns journal/checkpoint durability, fencing and quarantine. */
public interface DirectorStore {
    Optional<StoredRow> read(RowKey key);
    long sequence();
    CommitResult commit(StoreTransaction transaction);
    List<StoredRow> scan(Id world, Id kind, Id afterId, int limit);
    BackupGeneration checkpoint();
    void quarantine(Id world, ReasonCode reason);
}
