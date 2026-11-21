package fr.proutlost.director.api.port;
import fr.proutlost.director.api.model.*;
import java.util.Optional;
/** Server-derived current session authority, actor-thread memory lookup; never payload identity.
 * Resolve again immediately before queued application; revoked/disconnected authority rejects.
 */
public interface ActorAuthorityPort { Optional<ActorIdentity> current(SessionId session); }
