package fr.proutlost.director.api.model;
import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;
public record EventCooldownSelector(DefinitionId definitionId) implements CooldownSelector {public EventCooldownSelector {Objects.requireNonNull(definitionId);}}
