package fr.proutlost.director.api.model;
import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;
/** Durable algorithm state only; no pacing algorithm or new calibration. Ticks use the world simulation clock. */
public record EngineControlState(int schemaVersion, Revision revision, Tick pacingEnteredTick, Tick pacingDeadlineTick, UnitValue tensionEma, Tick tensionLastUpdateTick, Optional<Tick> belowThresholdSince, UnitValue requiredServiceCredit) {
 public EngineControlState { require(schemaVersion==1,"engine state version"); Objects.requireNonNull(revision); Objects.requireNonNull(pacingEnteredTick); Objects.requireNonNull(pacingDeadlineTick); Objects.requireNonNull(tensionEma); Objects.requireNonNull(tensionLastUpdateTick); Objects.requireNonNull(belowThresholdSince); Objects.requireNonNull(requiredServiceCredit); require(pacingDeadlineTick.compareTo(pacingEnteredTick)>=0,"pacing deadline"); require(belowThresholdSince.isEmpty()||belowThresholdSince.orElseThrow().compareTo(tensionLastUpdateTick)<=0,"tension recovery window"); }
}
