package fr.proutlost.director.bootstrap;

import fr.proutlost.director.adapter.neoforge.server.lifecycle.DirectorSavedIdentity;
import fr.proutlost.director.persistence.JournalStore;
import java.util.Objects;
import net.minecraft.server.MinecraftServer;

/**
 * Binds a verified durable manifest to the world-local identity projection.
 * A failed binding never grants admission, resets world data or replaces a WAL.
 *
 * Only the real server actor may update SavedData. This does not declare the
 * Director runtime RUNNING or certify cold archive reads.
 */
public final class DirectorStorageIdentityGate {
    private DirectorStorageIdentityGate(){}

    public static void verifyAndBind(
            MinecraftServer server, DirectorSavedIdentity identity,
            JournalStore.ManifestAnchor manifest) {
        Objects.requireNonNull(server);
        Objects.requireNonNull(identity);
        Objects.requireNonNull(manifest);
        if(!server.isSameThread())
            throw new IllegalStateException("Director identity requires server actor");
        if(!identity.worldId().equals(manifest.worldId()))
            throw new IllegalStateException("Director WAL world identity mismatch");
        identity.recordVerifiedStore(manifest.storeId(),
            manifest.storeEpoch().value(),manifest.checkpointGeneration());
    }

    /** Call this only after J04 has completed its own recovery validation. */
    public static boolean tryBindRecovered(
            MinecraftServer server, DirectorSavedIdentity identity,
            JournalStore recovered) {
        Objects.requireNonNull(recovered);
        var verified=recovered.verifiedManifestAnchor();
        if(verified.isEmpty())return false;
        verifyAndBind(server,identity,verified.orElseThrow());
        return true;
    }
}
