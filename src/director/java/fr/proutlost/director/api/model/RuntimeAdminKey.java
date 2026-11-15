package fr.proutlost.director.api.model;
import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;
public record RuntimeAdminKey(SessionId actorSession,RequestId requestId) {public RuntimeAdminKey {Objects.requireNonNull(actorSession);Objects.requireNonNull(requestId);}}
