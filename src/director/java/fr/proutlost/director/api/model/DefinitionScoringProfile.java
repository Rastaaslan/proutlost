package fr.proutlost.director.api.model;
import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;
/** J02 compiled optional companion metadata, hashed into CatalogSnapshot.hash/generation.
 * Missing profile/entries use central configuration neutral values, never content-ID heuristics.
 * Known entries are evaluated by J03; this is scoring evidence, not hard eligibility proof.
 */
public record DefinitionScoringProfile(int schemaVersion,DefinitionRef definition,Fingerprint profileHash,List<ContextAffinity> contextAffinities,Map<RegionTrait,UnitValue> regionAffinities,Map<GlobalTension,UnitValue> tensionAffinities,Optional<UnitValue> desiredLocalIntensity,Set<Family> habitationSensitiveFamilies) {
 public DefinitionScoringProfile {require(schemaVersion==1,"scoring profile version");Objects.requireNonNull(definition);Objects.requireNonNull(profileHash);contextAffinities=list(contextAffinities);regionAffinities=map(regionAffinities);tensionAffinities=map(tensionAffinities);Objects.requireNonNull(desiredLocalIntensity);habitationSensitiveFamilies=set(habitationSensitiveFamilies);}
}
