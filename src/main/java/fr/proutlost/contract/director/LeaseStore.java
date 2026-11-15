package fr.proutlost.contract.director;

import java.util.*;
import static fr.proutlost.contract.director.Vocabulary.*;

/** Backed by the SAME DirectorStore transaction as assignment and budget. Reservations all-or-none, ordered by ResourceKind; no independent lock authority. */
public interface LeaseStore {
    CommitResult compareAndReserve(LeaseReservation reservation);
    CommitResult renew(LeaseFence fence, long expectedLedgerRevision, long monotonicExpiryMs);
    CommitResult release(LeaseFence fence, long expectedLedgerRevision);
}
