package fr.proutlost.director.api.port;

import fr.proutlost.director.api.model.ProviderMode;
import java.util.Set;

/** Modes supported by the actual immutable backend configuration, never a
 * caller's Provider label. Implementations return a nonempty immutable set
 * fixed at construction; supporting a mode grants no gameplay authority. */
public interface ProviderProvenance {
    Set<ProviderMode> supportedModes();
}
