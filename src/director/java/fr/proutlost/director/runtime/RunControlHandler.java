package fr.proutlost.director.runtime;

import fr.proutlost.director.api.model.*;
import java.util.*;
import java.util.function.Supplier;

/** Durable PAUSE/RESUME/DRAIN proposals. This never bypasses permits or stops hazards by itself. */
public final class RunControlHandler implements RuntimeAdminMailbox.Handler {
 private final Supplier<DirectorWorldState> world;
 public RunControlHandler(Supplier<DirectorWorldState> world){this.world=Objects.requireNonNull(world);}
 public ProviderMode mode(){return ProviderMode.PRODUCTION;}
 public RuntimeAdminMailbox.Change propose(AdminCommand command,AdminSnapshotStamp before){
  if(!(command.payload() instanceof ControlPayload payload)||!Set.of(AdminOperation.PAUSE,AdminOperation.RESUME,AdminOperation.DRAIN).contains(command.operation()))throw new IllegalArgumentException("run control required");
  var old=world.get();if(!old.worldId().equals(before.worldId())||!old.bootEpoch().equals(before.bootEpoch())||!old.header().revision().equals(command.expectedRevision()))throw new IllegalArgumentException("stale world control");
  var header=new AggregateHeader(1,old.header().id(),old.header().revision().plus(1),old.header().lastSequence(),before.capturedTick());
  var next=new DirectorWorldState(header,old.worldId(),old.storeGeneration(),old.simTick(),payload.control(),old.healthMode(),old.pacingState(),old.tension(),old.intensityProfileId(),old.catalogHash(),old.configHash(),old.bootEpoch(),old.lastCheckpointSeq(),old.engineControl());
  var after=new AdminSnapshotStamp(before.worldId(),before.bootEpoch(),before.snapshotRevision().plus(1),before.capturedTick(),before.lastDurableSequence());
  return new RuntimeAdminMailbox.Change(after,new AdminResult(AdminStatus.SUCCEEDED,command.requestId(),after.snapshotRevision(),Optional.empty(),Optional.empty()),Map.of(new ScopeRef(ScopeKind.WORLD,old.worldId().value()),old.header().revision()),List.of(new DurableAggregate(new WorldAggregate(next))),List.of());
 }
}
