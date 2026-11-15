package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

public record Revision(long value) implements Comparable<Revision> { public Revision { nonnegative(value); } public Revision plus(long delta) { nonnegative(delta); return new Revision(Math.addExact(value,delta)); } public int compareTo(Revision other) { return Long.compare(value,other.value); } }
