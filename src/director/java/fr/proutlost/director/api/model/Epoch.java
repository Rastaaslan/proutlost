package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

public record Epoch(long value) implements Comparable<Epoch> { public Epoch { nonnegative(value); } public Epoch plus(long delta) { nonnegative(delta); return new Epoch(Math.addExact(value,delta)); } public int compareTo(Epoch other) { return Long.compare(value,other.value); } }
