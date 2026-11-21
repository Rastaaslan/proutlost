package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record SoundParameters(double volume, double pitch, double radius) implements PerceptionParameters {
 public SoundParameters { finite(volume); finite(pitch); finite(radius); require(volume>=0&&volume<=16&&pitch>0&&pitch<=4&&radius>=0&&radius<=512,"sound bounds"); }
}
