package fr.proutlost.director.bootstrap;
import fr.proutlost.director.api.model.*;
import java.util.*;
/** Actual selected J01/J04 frame plus newly measured native terrain. No scope/ownership/budget fabrication. */
public final class SelectedNativeSnapshot {
 private SelectedNativeSnapshot(){}
 public static PlanningSnapshot terrain(DecisionInput current,TerrainSnapshot measured){
  var raw=NativeCataloguePlanning.raw(current);var source=raw.snapshot();var c=source.context();
  var cells=new HashMap<BlockPosition,TerrainCellSnapshot>();
  for(var actual:measured.cells().values()){
   var tags=new HashSet<>(actual.tags());var hazards=new HashSet<>(actual.hazards());boolean protectedContent=actual.protectedContent();
   for(var old:c.terrain()){var prior=old.cells().get(actual.position());if(prior==null)continue;
    tags.addAll(prior.tags());hazards.addAll(prior.hazards());protectedContent|=prior.protectedContent();
   }
   cells.put(actual.position(),new TerrainCellSnapshot(actual.position(),actual.stateFingerprint(),actual.codec(),
       tags,hazards,protectedContent,actual.knownContent(),actual.ownershipEpoch(),actual.playerOverrideEpoch()));
  }
  var fresh=new TerrainSnapshot(measured.loadedFootprint(),cells,measured.safeExits(),measured.risks(),measured.revision());
  var terrain=new ArrayList<TerrainSnapshot>();terrain.add(fresh);
  for(var old:c.terrain()){
   var remaining=new HashMap<>(old.cells());remaining.keySet().removeAll(cells.keySet());
   terrain.add(new TerrainSnapshot(old.loadedFootprint(),remaining,old.safeExits(),old.risks(),old.revision()));
  }
  var chunks=new HashSet<>(source.loadedChunks());chunks.addAll(measured.loadedFootprint().loadedChunks());
  var snapshot=new SnapshotBatch(source.requestId(),source.scopes(),source.capturedTick(),source.scopeRevisions(),
      new ObservationContext(c.scopes(),c.players(),c.groups(),c.regions(),c.anchors(),c.locations(),terrain,c.capabilities()),
      source.missingData(),chunks,source.serverHealth());
  return new PlanningSnapshot(snapshot,raw.ownership(),raw.budgets(),raw.blobs(),raw.bindings());
 }
}
