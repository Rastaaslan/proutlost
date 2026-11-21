package fr.proutlost.director.api.port;

import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.model.PhysicalCatalogModels.*;
import java.util.*;
import java.util.concurrent.CompletionStage;

/** Actual world-owner port, used by the existing producer's nonblocking gate. */
public interface PhysicalCatalogRuntimePort {
    String prepare(Object authenticatedCommandSource,String inputName,String receiptRelative);
    Optional<Handle> approved(Fingerprint canonicalSha,ResourceId sourceWorld,ResourceId sourceBuild);
    String status();
    boolean ready();
    boolean failedSafe();
    /** Pure inspection only, off actor on the existing bounded owner worker. No grant/ACK. */
    CompletionStage<PhysicalCatalogSourceDescriptor> inspect(BlobContent exactSource);
    interface Handle {
        Key key();
        Progress submit(BlobContent exactSource);
        Progress poll();
        CompletionStage<BlobReadResult> readSource(Readback readback);
        <T>CompletionStage<T> readDecodedSource(Readback readback,java.util.function.Function<byte[],T> decoder);
        boolean stillApproved(Readback readback);
        boolean receiptReady(Readback readback,Fingerprint actualConsumerBytecodeSha);
    }
}
