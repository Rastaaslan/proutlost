package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

public record TagId(String value) implements Comparable<TagId> { public TagId { key(value); } public int compareTo(TagId other) { return value.compareTo(other.value); } }
