package fr.proutlost.director.bootstrap;

import fr.proutlost.director.api.model.ProviderMode;
import fr.proutlost.director.api.model.WorldId;
import fr.proutlost.director.api.port.DurableStore;
import net.minecraft.server.MinecraftServer;

/** Strict legacy LAB entry point; the generic production implementation has no
 * TestOnlyBackend marker. The inherited Phase/View API stays source compatible. */
public final class DirectorLabObservationWalIngestion
        extends DirectorObservationWalIngestion
        implements DirectorBootstrap.TestOnlyBackend {
    DirectorLabObservationWalIngestion(MinecraftServer server,WorldId world,
            DurableStore writer,DirectorNativeObservationQualification observation,
            DirectorNativeMemoryProjectionQualification memory) {
        super(server,world,writer,observation,memory,ProviderMode.TEST_ONLY);
    }
}
