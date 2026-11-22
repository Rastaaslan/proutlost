package fr.proutlost.director.api.model;

import java.util.Objects;
import java.util.Optional;

/** Immutable result shared by the administrative actor and its transport adapter. */
public record AdminReadReply(Optional<AdminSnapshot> snapshot,
                             Optional<AdminCommandResult> rejection) {
    public AdminReadReply {
        Objects.requireNonNull(snapshot);
        Objects.requireNonNull(rejection);
        if (snapshot.isPresent() == rejection.isPresent())
            throw new IllegalArgumentException("exactly one J07 reply");
    }
}
