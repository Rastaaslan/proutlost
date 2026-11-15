package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

public record Sequence(long value) implements Comparable<Sequence> { public Sequence { nonnegative(value); } public Sequence plus(long delta) { nonnegative(delta); return new Sequence(Math.addExact(value,delta)); } public int compareTo(Sequence other) { return Long.compare(value,other.value); } }
