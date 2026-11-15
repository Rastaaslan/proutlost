package fr.proutlost.director.api.model;
import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;
/** Applies to every candidate intersecting the bound affected scopes, not every world. */
public record GlobalSafetyCooldownSelector() implements CooldownSelector {}
