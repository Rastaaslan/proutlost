package fr.proutlost.contract.director;

import java.util.*;
import static fr.proutlost.contract.director.Vocabulary.*;

/** Only neutral request/receipt seam for the future dedicated service. No executor in D0. Receipt is not player observation or a cross-store atomic commit. */
public interface StructureService {
    MutationReceipt prepare(MutationRequest request);
    Optional<MutationReceipt> reconcile(Id world, Id mutationId);
    void finalizeReceipt(Id world, Id mutationId, Id committedTransactionId);
}
