package fr.proutlost.director.bootstrap;
import fr.proutlost.director.api.codec.ModelCodec;
import fr.proutlost.director.api.model.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
final class NativeMemoryCutCoherenceTest {
 private static MemoryView actual() throws Exception{
  try(var in=NativeMemoryCutCoherenceTest.class.getResourceAsStream("/native-expanded-context/decision-input.json")){
   return new ModelCodec<>(DecisionInput.class).decode(Objects.requireNonNull(in).readAllBytes()).memory();
  }
 }
 @Test void unchangedCompleteMemoryIsUsableAfterNonMemoryWalFacts() throws Exception{
  var actual=actual();var regions=new ArrayList<>(actual.regions());Collections.reverse(regions);
  var players=new ArrayList<>(actual.operationalPlayers());Collections.reverse(players);
  var reordered=new MemoryView(actual.scopes(),actual.revisions(),regions,players,actual.significantPlayers(),
      actual.regionRecords(),actual.anchors(),actual.locations());
  assertTrue(DirectorNativeCatalogueInput.verifiedMemoryAtCut(actual,reordered,new Sequence(28237),new Sequence(28240)));
  assertFalse(DirectorNativeCatalogueInput.verifiedMemoryAtCut(actual,reordered,new Sequence(28241),new Sequence(28240)),
      "a future projection never becomes current by an equality shortcut");
 }
 @Test void oneChangedCurrentRegionMemoryCannotPassTheOlderProjection() throws Exception{
  var actual=actual();var regions=new ArrayList<>(actual.regions());var first=regions.getFirst();
  var h=first.header();var changed=new RegionMemory(new AggregateHeader(h.schemaVersion(),h.id(),h.revision().plus(1),h.lastSequence(),h.updatedTick()),
      first.regionId(),first.components(),first.lastVisitTick(),first.lastEventTick(),first.persistentStateRefs(),first.durableFactRefs(),first.activity());
  regions.set(0,changed);
  var current=new MemoryView(actual.scopes(),actual.revisions(),regions,actual.operationalPlayers(),actual.significantPlayers(),
      actual.regionRecords(),actual.anchors(),actual.locations());
  assertFalse(DirectorNativeCatalogueInput.verifiedMemoryAtCut(current,actual,new Sequence(28237),new Sequence(28240)));
 }
 @Test void revisionChangesAndMissingPlayerSignificantMemoryAreNeverIgnored() throws Exception{
  var actual=actual();var revisions=new HashMap<>(actual.revisions());var key=revisions.keySet().iterator().next();
  revisions.put(key,revisions.get(key).plus(1));
  var changed=new MemoryView(actual.scopes(),revisions,actual.regions(),actual.operationalPlayers(),actual.significantPlayers(),
      actual.regionRecords(),actual.anchors(),actual.locations());
  assertFalse(DirectorNativeCatalogueInput.verifiedMemoryAtCut(changed,actual,new Sequence(28237),new Sequence(28240)));
  UUID id=UUID.randomUUID();var header=new AggregateHeader(1,id,new Revision(1),new Sequence(0),new Tick(0));
  var significant=new PlayerSignificantMemory(header,new MemoryId(id),actual.operationalPlayers().getFirst().playerId(),
      new EventInstanceId(UUID.randomUUID()),new DefinitionId("test:unit_only_event"),"witness",PerceptionStatus.values()[0],
      new BlobRef(new Fingerprint("0".repeat(64)),0,1,new ResourceId("test:unit_only_outcome")),Optional.empty(),Optional.empty(),Set.of(),Set.of(),new Tick(0));
  var current=new MemoryView(actual.scopes(),actual.revisions(),actual.regions(),actual.operationalPlayers(),List.of(significant),
      actual.regionRecords(),actual.anchors(),actual.locations());
  assertFalse(DirectorNativeCatalogueInput.verifiedMemoryAtCut(current,actual,new Sequence(28237),new Sequence(28240)));
 }
}
