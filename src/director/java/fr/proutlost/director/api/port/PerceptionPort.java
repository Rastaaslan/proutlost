package fr.proutlost.director.api.port;
import fr.proutlost.director.api.model.*;
import java.util.concurrent.CompletionStage;
/** Optional adapter contract; no production implementation in G0. */
public interface PerceptionPort { void upsert(PerceptionIntent intent); void stop(PlayerId target, PerceptionStop stop); void reset(PlayerId target, PerceptionReset reset); }
