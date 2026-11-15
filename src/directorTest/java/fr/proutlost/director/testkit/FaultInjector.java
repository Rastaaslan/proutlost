package fr.proutlost.director.testkit;
import fr.proutlost.director.api.model.FaultPoint;
/** TEST_ONLY fault boundary; no production binding. */
@FunctionalInterface
public interface FaultInjector { void at(FaultPoint point); }
