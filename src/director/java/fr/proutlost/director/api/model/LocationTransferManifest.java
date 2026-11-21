package fr.proutlost.director.api.model;

import java.util.*;

/** Transport identity only. Staged pages grant no metadata or physical authority. */
public record LocationTransferManifest(int schemaVersion, WorldId worldId,
        ResourceId sourceNamespace, Revision exportRevision, Fingerprint exportDigest,
        Fingerprint sourceSha256, int sourceBytes, int entryCount, List<Fingerprint> pageHashes) {
    public LocationTransferManifest {
        Objects.requireNonNull(worldId); Objects.requireNonNull(sourceNamespace);
        Objects.requireNonNull(exportRevision); Objects.requireNonNull(exportDigest);
        Objects.requireNonNull(sourceSha256); pageHashes = List.copyOf(pageHashes);
        Checks.require(schemaVersion == 1 && sourceBytes > 0 && sourceBytes <= 1_048_576,
                "location transfer schema/source bound");
        Checks.require(entryCount >= 0 && pageHashes.size() == (sourceBytes + 19_999) / 20_000,
                "location transfer count/pages");
    }
}
