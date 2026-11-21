package fr.proutlost.director.runtime;
import fr.proutlost.director.api.model.*;
import java.util.*;

/** Actor-owner memo of a pure terminal reconciliation, never effect authority.
 * The sole owner MUST invalidate on every incoming durable evidence touch,
 * including facts that do not change map sizes or the committed EVENT head.
 * Immutable input identity is deliberately conservative: an equal replacement
 * recomputes. The caller still validates chronology, publication and session. */
public final class TerminalEventProgressMemo {
 private EventInstance head;private EventDefinition definition;private ExecutionPlan plan;private DisasterInstance disaster;
 public void invalidate(){head=null;definition=null;plan=null;disaster=null;}
 public boolean unchanged(EventInstance current,EventDefinition currentDefinition,ExecutionPlan currentPlan,DisasterInstance currentDisaster){
  return head!=null&&head==current&&definition==currentDefinition&&plan==currentPlan&&disaster==currentDisaster;
 }
 public void reconciled(EventInstance current,EventDefinition currentDefinition,ExecutionPlan currentPlan,DisasterInstance currentDisaster){
  Objects.requireNonNull(current);Objects.requireNonNull(currentDefinition);Objects.requireNonNull(currentPlan);
  if(!Set.of(EventState.SUCCEEDED,EventState.PARTIAL,EventState.ABORTED_SAFE,EventState.FAILED_SAFE).contains(current.state()))
   throw new IllegalArgumentException("terminal EVENT reconciliation only");
  head=current;definition=currentDefinition;plan=currentPlan;disaster=currentDisaster;
 }
}
