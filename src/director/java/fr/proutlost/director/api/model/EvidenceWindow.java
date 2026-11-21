package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record EvidenceWindow(EvidenceWindowId id, Tick startTick, Tick endTick, Set<EpisodeId> independentEpisodes) {
 public EvidenceWindow { Objects.requireNonNull(id); Objects.requireNonNull(startTick); Objects.requireNonNull(endTick); independentEpisodes=set(independentEpisodes); require(startTick.compareTo(endTick)<0&&independentEpisodes.size()<=1024,"evidence window bound"); }
}
