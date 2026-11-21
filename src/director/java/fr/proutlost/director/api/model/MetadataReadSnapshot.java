package fr.proutlost.director.api.model;
import java.util.Map;
/** Opaque read-only storage handle. It identifies a pinned immutable metadata
 * cut, is owned by its producing read port, and is never a wire DTO or permit.
 * Every operation must reject a foreign/revoked handle rather than infer absence. */
public interface MetadataReadSnapshot {
    StoreSession session();
    Sequence cut();
    Map<ResourceId,Fingerprint> generations();
    Map<ResourceId,LocationTransferManifest> sources();
}
