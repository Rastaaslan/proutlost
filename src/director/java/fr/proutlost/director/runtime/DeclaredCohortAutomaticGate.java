package fr.proutlost.director.runtime;

import fr.proutlost.director.api.model.*;
import java.util.Objects;
import java.util.Optional;

/** Process/session-local arm, never a permit. Only a NEW durable control ACK
 * callback may arm it. A recovered RUNNING WORLD or restored receipt cannot. */
public final class DeclaredCohortAutomaticGate {
 private final StoreSession owner;private boolean armed,revoked;private long lastSequence;
 public DeclaredCohortAutomaticGate(StoreSession owner){this.owner=Objects.requireNonNull(owner);}
 public void newlyCommitted(AdminCommand command,AdminSnapshotStamp published,DirectorWorldState current,StoreSession actual,Optional<ActorIdentity> currentActor){
  Objects.requireNonNull(command);Objects.requireNonNull(published);Objects.requireNonNull(current);
  if(revoked){armed=false;return;} // A terminal ACK notification must still drain during shutdown.
  if(!owner.equals(actual)||!published.worldId().equals(owner.worldId())||!published.bootEpoch().equals(owner.bootEpoch())
   ||!current.worldId().equals(owner.worldId())||!current.bootEpoch().equals(owner.bootEpoch())||!current.storeGeneration().equals(owner.storeGeneration())
   ||!published.snapshotRevision().equals(current.header().revision())||published.lastDurableSequence().value()<=lastSequence
   ||published.lastDurableSequence().value()<=0)
   throw new IllegalStateException("new current-session durable cohort control publication required");
  if(Objects.requireNonNull(currentActor).filter(actor->AdminContracts.authorized(command,actor)).isEmpty()){armed=false;lastSequence=published.lastDurableSequence().value();return;}
  if(command.operation()==AdminOperation.RESUME){
   if(!(command.payload() instanceof ControlPayload value)||value.control()!=RunControl.RUNNING||current.runControl()!=RunControl.RUNNING)
    throw new IllegalArgumentException("actual durable RESUME required");
   armed=true;
  }else if(command.operation()==AdminOperation.PAUSE||command.operation()==AdminOperation.DRAIN)armed=false;
  lastSequence=published.lastDurableSequence().value();
 }
 public boolean enabled(StoreSession actual,DirectorWorldState current){
  return !revoked&&armed&&owner.equals(actual)&&current.worldId().equals(owner.worldId())&&current.bootEpoch().equals(owner.bootEpoch())
   &&current.storeGeneration().equals(owner.storeGeneration())&&current.runControl()==RunControl.RUNNING;
 }
 public void disarm(){armed=false;}
 public void revoke(){armed=false;revoked=true;}
}
