package fr.proutlost.director.api.port;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.permission.CommitPermit;
import java.util.concurrent.CompletionStage;
/** G0 contract. Completion callbacks deliver data only; world access requires the server actor. */
public interface MemoryPort { MemoryView view(ScopeSet scopes); /** Pure deterministic reduction: no IO, no hidden mutation; actor appends complete CAS changes. */ Reduction reduce(MemoryView before, ObservationBatch input, DirectorClock clock); }
