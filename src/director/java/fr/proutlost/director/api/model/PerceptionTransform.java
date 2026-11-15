package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record PerceptionTransform(double x, double y, double z, double yaw, double pitch, double scale) {
 public PerceptionTransform { finite(x); finite(y); finite(z); finite(yaw); finite(pitch); finite(scale); require(Math.abs(x)<=30000000&&Math.abs(z)<=30000000&&Math.abs(y)<=2048&&Math.abs(yaw)<=360&&Math.abs(pitch)<=90&&scale>0&&scale<=64,"transform bounds"); }
}
