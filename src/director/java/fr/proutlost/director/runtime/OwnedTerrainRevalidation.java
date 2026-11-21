package fr.proutlost.director.runtime;
import fr.proutlost.director.api.model.*;
import java.util.*;
/** Read-only evidence classification. Never grants a reservation, permit, or write.
 * Only an explicitly ownership-only observation may be revalidated against the
 * same exact selected owner, local attachment and committed ownership projection. */
public final class OwnedTerrainRevalidation {
 private OwnedTerrainRevalidation(){}
 public static final TagId OWNERSHIP_ONLY=new TagId("proutlost_director:attached_ownership_only_protection");
 private static final ResourceId CODEC=new ResourceId("proutlost_director:block_state_v1");
 public static boolean permitsPriorOwnershipMask(TerrainCellSnapshot actual,TerrainCellSnapshot prior,
   OwnershipRecord expected,OwnershipRecord local,OwnershipRecord projected){
  if(actual==null||prior==null||expected==null||local==null||projected==null)return false;
  if(actual.protectedContent()||!actual.knownContent()||!actual.tags().isEmpty()||!actual.hazards().isEmpty()
    ||!prior.protectedContent()||!prior.knownContent()||!prior.tags().equals(Set.of(OWNERSHIP_ONLY))
    ||!prior.hazards().isEmpty()||!actual.codec().equals(CODEC)||!prior.codec().equals(CODEC)
    ||!actual.position().equals(prior.position())||!actual.stateFingerprint().equals(prior.stateFingerprint()))return false;
  var p=actual.position();var key=new ResourceKey(OwnershipDomain.BLOCK,p.dimension(),p.x()+","+p.y()+","+p.z());
  return expected.resourceKey().equals(key)&&expected.disposition()==OwnershipDisposition.DIRECTOR_ACTIVE
    &&!expected.ownerTokens().isEmpty()&&expected.equals(local)&&expected.equals(projected)
    &&expected.expectedPostHash().equals(actual.stateFingerprint())
    &&expected.epoch().equals(actual.ownershipEpoch())&&expected.epoch().equals(prior.ownershipEpoch())
    &&expected.playerOverrideEpoch().equals(actual.playerOverrideEpoch())&&expected.playerOverrideEpoch().equals(prior.playerOverrideEpoch());
 }
}
