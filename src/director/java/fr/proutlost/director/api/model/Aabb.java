package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

public record Aabb(DimensionId dimension, int minX, int minY, int minZ, int maxXExclusive, int maxYExclusive, int maxZExclusive) {
 public Aabb { Objects.requireNonNull(dimension); require(minX<maxXExclusive && minY<maxYExclusive && minZ<maxZExclusive,"bounds"); Math.multiplyExact(Math.multiplyExact((long)maxXExclusive-minX,(long)maxYExclusive-minY),(long)maxZExclusive-minZ); }
 public long volume() { return Math.multiplyExact(Math.multiplyExact((long)maxXExclusive-minX,(long)maxYExclusive-minY),(long)maxZExclusive-minZ); }
}
