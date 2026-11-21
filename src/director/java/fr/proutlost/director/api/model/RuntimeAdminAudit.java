package fr.proutlost.director.api.model;
import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;
/** J05 durable audit in same transaction as control change; never infer success from queue receipt. */
public record RuntimeAdminAudit(AdminCommand command,Fingerprint commandHash,ActorIdentity appliedAuthority,AdminSnapshotStamp before,AdminSnapshotStamp after,AdminResult result) {
 public RuntimeAdminAudit {Objects.requireNonNull(command);Objects.requireNonNull(commandHash);Objects.requireNonNull(appliedAuthority);Objects.requireNonNull(before);Objects.requireNonNull(after);Objects.requireNonNull(result);require(command.requestId().equals(result.requestId())&&command.actorSession().equals(appliedAuthority.session()),"admin audit correlation");require(result.revision().equals(after.snapshotRevision()),"admin audit revision");require(result.status()!=AdminStatus.SUCCEEDED||AdminContracts.authorized(command,appliedAuthority),"authorized success");require(result.status()!=AdminStatus.PENDING,"terminal audit required");require(before.worldId().equals(after.worldId())&&before.bootEpoch().equals(after.bootEpoch()),"admin audit snapshot binding");require(commandHash.equals(BlobContent.hash(new fr.proutlost.director.api.codec.ModelCodec<>(AdminCommand.class).encode(command))),"admin command hash");}
}
