package fr.proutlost.director.api.model;

import java.util.*;

/** Closed TEST_ONLY preparation data. Planned identities are never runtime
 * sessions, client capabilities, receipts, permissions or measured players. */
public record DeclaredCohortQaPlan(int schemaVersion,String runId,Branch branch,String mode,
 int actualPlayersTarget,int humanOperators,String controllerId,List<Actor> actors,
 Set<PlayerId> surveyWitnesses,Map<String,String> pins,boolean automaticSelection) {
 public enum Branch { BASELINE,DIRECTOR }
 public record Actor(PlayerId playerId,String profileName,String controllerId) {
  public Actor {
   Objects.requireNonNull(playerId);
   if(profileName==null||!profileName.matches("[A-Za-z0-9_]{1,16}")||!id(controllerId))
    throw new IllegalArgumentException("actual cohort profile/controller declaration required");
  }
 }
 public DeclaredCohortQaPlan {
  Objects.requireNonNull(branch);actors=List.copyOf(actors);surveyWitnesses=Set.copyOf(surveyWitnesses);pins=Map.copyOf(pins);
  if(schemaVersion!=1||!id(runId)||!id(controllerId)||!"FULL_MC24".equals(mode)
   ||actualPlayersTarget!=24||humanOperators!=0||actors.size()!=24
   ||surveyWitnesses.size()!=2||branch==Branch.BASELINE&&automaticSelection)
   throw new IllegalArgumentException("closed zero-human FULL_MC24 branch required");
  var uuids=new HashSet<PlayerId>();var names=new HashSet<String>();
  for(var actor:actors)if(!uuids.add(actor.playerId())||!names.add(actor.profileName().toLowerCase(Locale.ROOT))||!actor.controllerId().equals(controllerId))
   throw new IllegalArgumentException("distinct real profile declarations and one pinned controller required");
  if(!uuids.containsAll(surveyWitnesses))throw new IllegalArgumentException("two survey witness profiles must belong to actual cohort");
  if(!pins.keySet().equals(Set.of("candidate_sha256","profile_sha256","action_plan_sha256","sources_sha256","director_jar_sha256")))
   throw new IllegalArgumentException("exact cohort provenance pins required");
  for(var value:pins.values())if(value==null||!value.matches("[0-9a-f]{64}")||value.equals("0".repeat(64)))
   throw new IllegalArgumentException("nonzero cohort SHA256 required");
 }
 private static boolean id(String value){return value!=null&&value.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}");}
 public Set<UUID> actualUuids(){var ids=new HashSet<UUID>();actors.forEach(a->ids.add(a.playerId().value()));return Set.copyOf(ids);}
 public Map<UUID,String> actualProfiles(){var names=new HashMap<UUID,String>();actors.forEach(a->names.put(a.playerId().value(),a.profileName()));return Map.copyOf(names);}
}
