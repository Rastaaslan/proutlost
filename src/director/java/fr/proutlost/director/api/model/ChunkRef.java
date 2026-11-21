package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

public record ChunkRef(DimensionId dimension, int x, int z) { public ChunkRef { Objects.requireNonNull(dimension); } }
