package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

public record BlockPosition(DimensionId dimension, int x, int y, int z) { public BlockPosition { Objects.requireNonNull(dimension); } }
