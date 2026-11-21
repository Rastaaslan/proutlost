package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record ActorIdentity(SessionId session, ActorKind kind, Optional<PlayerId> playerId, Optional<ResourceId> systemModule, int permissionLevel, boolean serverAuthenticated) {
 public ActorIdentity { Objects.requireNonNull(session); Objects.requireNonNull(kind); Objects.requireNonNull(playerId); Objects.requireNonNull(systemModule); nonnegative(permissionLevel); require(permissionLevel<=4,"permission level"); require((kind==ActorKind.PLAYER)==playerId.isPresent() && (kind==ActorKind.SYSTEM)==systemModule.isPresent(),"actor kind"); }
}
