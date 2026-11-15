package fr.proutlost.director.api.model;
/** Shape checks do not grant permission. Adapters derive ActorIdentity from the server session. */
public final class AdminContracts {
 public static final FactType TENSION_OVERRIDE_FACT=new FactType("proutlost_director:admin_tension_override");
 public static final FactType RECORDED_DECISION_INPUT_FACT=new FactType("proutlost_director:recorded_decision_input");
 private AdminContracts() {}
 public static boolean matches(AdminOperation op,AdminPayload p){return switch(op){
 case PAUSE -> p instanceof ControlPayload c&&c.control()==RunControl.PAUSED;
 case RESUME -> p instanceof ControlPayload c&&c.control()==RunControl.RUNNING;
 case DRAIN -> p instanceof ControlPayload c&&c.control()==RunControl.DRAINING;
 case TENSION -> p instanceof TensionPayload;case INTENSITY -> p instanceof IntensityPayload;
 case TRIGGER -> p instanceof TriggerPayload;case REPLAY -> p instanceof ReplayPayload;case AUDIT_EXPORT -> p instanceof AuditExportPayload;
 case STABILIZE -> p instanceof StabilizePayload;case SIMULATE -> p instanceof SimulationPayload;case RECORD_OBSERVATION -> p instanceof RecordObservationPayload;
 case REGION -> p instanceof RegionInspectionPayload;case PLAYER -> p instanceof PlayerInspectionPayload;case CHAIN -> p instanceof ChainInspectionPayload;
 case EVENT_INSPECT -> p instanceof EventInspectionPayload;case MUTATION_INSPECT -> p instanceof MutationInspectionPayload;case STOP -> p instanceof StopPayload;case RESCUE -> p instanceof RescuePayload;case REPORT -> p instanceof ReportPayload;
 default -> p instanceof EmptyAdminPayload;};}
 public static boolean matches(AdminPanel panel,AdminFilter f){return switch(panel){case WORLD -> f instanceof WorldFilter;case ACTIVE -> f instanceof EventFilter||f instanceof ChainFilter||f instanceof MutationFilter;case MEMORY -> f instanceof RegionFilter||f instanceof PlayerFilter;case DECISIONS -> f instanceof DecisionFilter;case LABORATORY -> f instanceof LaboratoryFilter;};}
 public static boolean authorized(AdminCommand command,ActorIdentity actor){
  if(!actor.serverAuthenticated()||!command.actorSession().equals(actor.session()))return false;
  if(actor.permissionLevel()>=4)return true;
  if(command.operation()==AdminOperation.REPORT||command.operation()==AdminOperation.RECORD_OBSERVATION)return actor.playerId().isPresent();
  if(command.payload() instanceof RescuePayload r)return r.self()?actor.playerId().isPresent():actor.permissionLevel()>=2;
  return command.operation()==AdminOperation.STATUS||command.operation()==AdminOperation.DEBUG&&actor.permissionLevel()>=2;
 }
}
