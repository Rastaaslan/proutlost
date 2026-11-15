package fr.proutlost.director.api.time;
import fr.proutlost.director.api.model.DirectorClock;
/** Snapshot of the three distinct clock domains. Reading never advances simTick. */
public interface ClockSource { DirectorClock snapshot(); }
