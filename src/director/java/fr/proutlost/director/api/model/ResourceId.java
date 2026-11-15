package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

public record ResourceId(String value) implements Comparable<ResourceId> { public ResourceId { key(value); } public int compareTo(ResourceId other) { return value.compareTo(other.value); } }
