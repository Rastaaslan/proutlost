package fr.proutlost.director.api.port;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.permission.CommitPermit;
import java.util.concurrent.CompletionStage;
/** G0 contract. Completion callbacks deliver data only; world access requires the server actor. */
public interface DirectorPrimitive<A extends PrimitiveArgs> { PrimitiveDescriptor descriptor(); PreparedEffect plan(A args, PlanningSnapshot snapshot); ValidationResult validate(PreparedEffect plan, LiveValidationSnapshot snapshot); EffectProgress applySlice(PreparedEffect plan, EffectCursor cursor, CommitPermit permit); ReconcileResult reconcile(PreparedEffect plan, RecoveryEvidence evidence); CleanupPlan cleanup(PreparedEffect plan, CleanupReason reason, OwnershipView ownership); }
