package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record JournalEntry(PlayerId playerId, Optional<FactId> sourceFactId, Tick chosenAtTick, Optional<String> playerText, String visibleSummary) {
 public JournalEntry { Objects.requireNonNull(playerId); Objects.requireNonNull(sourceFactId); Objects.requireNonNull(chosenAtTick); Objects.requireNonNull(playerText); text(visibleSummary);  }
}
