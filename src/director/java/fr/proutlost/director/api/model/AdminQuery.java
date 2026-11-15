package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record AdminQuery(SessionId actorSession, RequestId requestId, AdminPanel panel, AdminFilter filter, Optional<String> cursor, Optional<Revision> expectedSnapshotRevision, int limit) {
 public AdminQuery { Objects.requireNonNull(actorSession); Objects.requireNonNull(requestId); Objects.requireNonNull(panel); Objects.requireNonNull(filter); Objects.requireNonNull(cursor); Objects.requireNonNull(expectedSnapshotRevision); nonnegative(limit); require(limit>0&&limit<=50,"page bound"); require(AdminContracts.matches(panel,filter),"panel/filter mismatch"); cursor.ifPresent(c->require(c.length()<=512,"cursor bound")); }
}
