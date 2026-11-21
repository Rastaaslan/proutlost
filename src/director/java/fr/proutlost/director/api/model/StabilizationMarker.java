package fr.proutlost.director.api.model;
import java.util.*;
/** Local attempted-stop identity, not evidence of completion. Adapter records with world-local
 * operation markers; J04/J05 reconcile outcomes after failure/reboot before any retry.
 */
public record StabilizationMarker(StoreSession session, Sequence prepareSequence, OperationToken originalOperation, SliceId stopSlice, OperationToken stopOperation, BlobRef stopPlan) {
 public StabilizationMarker {Objects.requireNonNull(session);Objects.requireNonNull(prepareSequence);Objects.requireNonNull(originalOperation);Objects.requireNonNull(stopSlice);Objects.requireNonNull(stopOperation);Objects.requireNonNull(stopPlan);}
}
