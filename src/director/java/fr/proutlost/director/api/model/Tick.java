package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

public record Tick(long value) implements Comparable<Tick> { public Tick { nonnegative(value); } public Tick plus(long delta) { nonnegative(delta); return new Tick(Math.addExact(value,delta)); } public int compareTo(Tick other) { return Long.compare(value,other.value); } }
