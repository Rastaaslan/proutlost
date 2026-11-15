package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

public record PrimitiveId(String value) implements Comparable<PrimitiveId> { public PrimitiveId { key(value); } public int compareTo(PrimitiveId other) { return value.compareTo(other.value); } }
