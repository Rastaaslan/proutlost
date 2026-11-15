package fr.proutlost.director.api.model;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** Exact J06 connection correlation for J07, shared by client/server.
 * Not an auth token: the server must check the authenticated op level
 * and the current PerceptionTransport SessionIdentity itself. */
public final class DirectorAdminSessionKey {
    private DirectorAdminSessionKey(){}
    public static SessionId of(SessionIdentity session){
        Objects.requireNonNull(session);
        var text="director-j07-session-v1/"+session.worldId().value()+
            "/"+session.bootEpoch().value()+"/"+
            session.connectionEpoch().value()+"/"+session.playerId().value();
        return new SessionId(UUID.nameUUIDFromBytes(
            text.getBytes(StandardCharsets.UTF_8)));
    }
}
