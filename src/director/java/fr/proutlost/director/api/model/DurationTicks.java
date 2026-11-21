package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

public record DurationTicks(long value) implements Comparable<DurationTicks> { public DurationTicks { nonnegative(value); } public DurationTicks plus(long delta) { nonnegative(delta); return new DurationTicks(Math.addExact(value,delta)); } public int compareTo(DurationTicks other) { return Long.compare(value,other.value); } }
