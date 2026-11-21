package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

public record DimensionId(String value) implements Comparable<DimensionId> { public DimensionId { key(value); } public int compareTo(DimensionId other) { return value.compareTo(other.value); } }
