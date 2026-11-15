package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

public record FactType(String value) implements Comparable<FactType> { public FactType { key(value); } public int compareTo(FactType other) { return value.compareTo(other.value); } }
