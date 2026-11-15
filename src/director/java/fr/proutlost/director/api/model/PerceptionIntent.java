package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record PerceptionIntent(SessionIdentity session, PlayerId target, PerceptionUpsert effect) {
 public PerceptionIntent { Objects.requireNonNull(session); Objects.requireNonNull(target); Objects.requireNonNull(effect); require(target.equals(session.playerId())&&effect.header().worldId().equals(session.worldId())&&effect.header().dimension().equals(session.dimension())&&effect.header().bootEpoch().equals(session.bootEpoch())&&effect.header().connectionEpoch().equals(session.connectionEpoch()),"target session"); }
}
