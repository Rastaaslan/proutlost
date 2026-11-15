package fr.proutlost.director.api.model;
import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;
/** Typed historical binding; no lookup of expired EventInstance required. Affected scopes include
 * incidental audiences. inheritedRegions is the authoritative transitive lineage/alias closure to
 * which this record applies. Incomplete closure or absent binding means DEFER affected safety
 * decisions pending observation, never ignore-all or suppress unrelated events globally.
 * Conditional release requires positive fresh evidence AND notBeforeTick; unknown is not released.
 */
public record CooldownBinding(CooldownSelector selector,ScopeSet affectedScopes,Set<RegionId> inheritedRegions,boolean lineageComplete,CooldownReleaseMode releaseMode,Optional<Condition> releasePredicate) {
 public CooldownBinding {Objects.requireNonNull(selector);Objects.requireNonNull(affectedScopes);inheritedRegions=set(inheritedRegions);Objects.requireNonNull(releaseMode);Objects.requireNonNull(releasePredicate);require(!affectedScopes.scopes().isEmpty(),"cooldown scopes");require((releaseMode==CooldownReleaseMode.AFTER_TICK_AND_CONDITION)==releasePredicate.isPresent(),"cooldown release predicate");}
}
