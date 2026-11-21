package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record BudgetVector(double tickMillis, double globalIntensity, double regionalIntensity, long liveEntities, long persistentEntities, long mutationsInFlight, long queuedIoBytes, double clientUnits, long payloadBytes, long navigationNodes) {
 public BudgetVector { finite(tickMillis); finite(globalIntensity); finite(regionalIntensity); nonnegative(liveEntities); nonnegative(persistentEntities); nonnegative(mutationsInFlight); nonnegative(queuedIoBytes); finite(clientUnits); nonnegative(payloadBytes); nonnegative(navigationNodes); require(tickMillis>=0 && globalIntensity>=0 && regionalIntensity>=0 && clientUnits>=0,"negative cost"); }
}
