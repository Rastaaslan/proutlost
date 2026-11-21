package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record ScoreContribution(String feature, double value, double weight, double contribution) {
 public ScoreContribution {
  text(feature); finite(value); finite(weight); finite(contribution);
  // CanonicalJson represents both IEEE signed zeros as 0; keep the immutable
  // DTO in that same canonical domain before audit persistence or export.
  value=value==0.0?0.0:value;weight=weight==0.0?0.0:weight;contribution=contribution==0.0?0.0:contribution;
 }
}
