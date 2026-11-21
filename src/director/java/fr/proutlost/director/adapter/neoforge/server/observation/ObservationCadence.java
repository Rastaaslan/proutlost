package fr.proutlost.director.adapter.neoforge.server.observation;

import fr.proutlost.director.api.model.Tick;

/** Independent periodic pass with a rotating cursor; skipped players never gain elapsed occupancy. */
public final class ObservationCadence {
    private final long interval;
    private int cursor;
    public ObservationCadence(long interval){if(interval<=0)throw new IllegalArgumentException("cadence");this.interval=interval;}
    public boolean due(Tick now){return now.value()%interval==0;}
    public int index(int offset,int population){return Math.floorMod(cursor+offset,population);}
    public void advance(int processed,int population){cursor=population==0?0:Math.floorMod(cursor+processed,population);}
}
