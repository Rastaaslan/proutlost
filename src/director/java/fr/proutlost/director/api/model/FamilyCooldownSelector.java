package fr.proutlost.director.api.model;
import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;
public record FamilyCooldownSelector(Family family) implements CooldownSelector {public FamilyCooldownSelector {Objects.requireNonNull(family);}}
