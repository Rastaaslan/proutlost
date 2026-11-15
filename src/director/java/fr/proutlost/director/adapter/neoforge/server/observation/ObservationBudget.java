package fr.proutlost.director.adapter.neoforge.server.observation;

import java.util.function.LongSupplier;

/** Shared cooperative operation/deadline budget, checked before each unit of observation work. */
public final class ObservationBudget {
    private int remaining;
    private final long start;
    private final double nanos;
    private final LongSupplier clock;
    public ObservationBudget(int operations,double nanos,LongSupplier clock) {
        if(operations<0||!Double.isFinite(nanos)||nanos<0)throw new IllegalArgumentException("observation budget");
        remaining=operations;this.nanos=nanos;this.clock=clock;start=clock.getAsLong();
    }
    public boolean available(){return remaining>0&&clock.getAsLong()-start<nanos;}
    public boolean take(){if(!available())return false;remaining--;return true;}
    public int remaining(){return remaining;}
}
