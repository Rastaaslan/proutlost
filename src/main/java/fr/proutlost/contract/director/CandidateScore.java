package fr.proutlost.contract.director;

import java.util.*;
import java.time.Instant;
import static fr.proutlost.contract.director.Vocabulary.*;

/** Immutable D0 contract; wire field names follow the frozen design. */
public record CandidateScore(
    LocationRef location,
    int score) {
    public CandidateScore {
        Objects.requireNonNull(location, "location");
    }
}
