package fr.proutlost.director.adapter.neoforge.server.effects;

import fr.proutlost.director.api.model.*;

/** Native/backend-specific bytes remain in J04's sealed blobs. */
public interface RestorableWeatherBinding extends ServerEffectPort.Binding {
    void registerRestore(EffectIntent inverse, BlobContent stop);
    Fingerprint observedFingerprint(DimensionId dimension);
    default boolean expiredByBackend(PreparedEffect plan){return false;}
    default void releaseReceipt(PreparedEffect plan){}
}
