package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

public record DefinitionId(String value) implements Comparable<DefinitionId> { public DefinitionId { key(value); } public int compareTo(DefinitionId other) { return value.compareTo(other.value); } }
