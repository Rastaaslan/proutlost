package fr.proutlost.director.api.port;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.permission.CommitPermit;
import java.util.concurrent.CompletionStage;
/** G0 contract. Completion callbacks deliver data only; world access requires the server actor. */
public interface WorldEffectPort {
 /** Actor-only stop path: validate/consume against the adapter's own Authority, apply at most one
  * bounded preauthorized tranche, record local attempt/outcome markers. No new durable ACK.
  * Missing capability must be explicit; no ordinary impact permit may substitute.
  */
 SliceResult stabilize(PreparedSlice slice, CommitPermit.StabilizationPermit permit, StabilizationLiveSnapshot live);
 EffectPreparation prepare(EffectIntent intent); SliceResult apply(PreparedSlice slice, CommitPermit permit); ReconcileResult reconcile(RecoveryProbe probe); CleanupResult cleanup(CleanupIntent intent, CommitPermit permit); }
