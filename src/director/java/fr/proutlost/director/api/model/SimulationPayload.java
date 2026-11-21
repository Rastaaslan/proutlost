package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record SimulationPayload(ResourceId fixtureId, SnapshotBatch injectedContext, boolean scoreOnly) implements AdminPayload {
 public SimulationPayload { Objects.requireNonNull(fixtureId); Objects.requireNonNull(injectedContext); require(scoreOnly,"simulation cannot execute"); }
}
