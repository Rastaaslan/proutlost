package fr.proutlost.worldprep.director;

import fr.proutlost.contract.director.*;
import java.util.Optional;
import java.io.IOException;

/** Consumer-owned bridge. No provider is packaged in WorldPrep. A receipt alone cannot grant import. */
public interface CatalogImportProbe {
    /** Must execute the actual consumer import and return the snapshot admitted by that consumer. */
    CatalogSnapshot importAndReadAdmitted(String json,Id world,Id build) throws IOException;

    /**
     * Nonblocking admission/readback attempt on the existing consumer actor.
     * Empty means PENDING, including awaiting actual operator/bootstrap authority.
     * The same world/build/canonical bytes identify the same operation across
     * repeated calls and process recovery. Implementations must not allocate a
     * fresh operation or apply effects on a retry. A present snapshot must come
     * from verified durable consumer state, never from parsing the input alone.
     *
     * Existing synchronous DEV providers remain source/binary compatible. A real
     * async production provider must override this method and never wait for an
     * actor tick, filesystem operation or CompletionStage on the server thread.
     */
    default Optional<CatalogSnapshot> pollImportAndReadAdmitted(String json,Id world,Id build) throws IOException {
        return Optional.of(importAndReadAdmitted(json,world,build));
    }
}
