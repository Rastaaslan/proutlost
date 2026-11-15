package fr.proutlost.director.api.time;
import java.util.*;
/** Java 21 SplittableRandom with explicit seed and draw evidence; actor-confined. */
public final class DeterministicRandom {
 private final long seed; private final SplittableRandom random; private final List<Double> draws=new ArrayList<>();
 public DeterministicRandom(long seed){this.seed=seed;random=new SplittableRandom(seed);}
 public double nextUnit(){if(draws.size()>=65536)throw new IllegalStateException("audit draw bound");double draw=random.nextDouble();draws.add(draw);return draw;}
 public long seed(){return seed;}
 public List<Double> draws(){return List.copyOf(draws);}
}
