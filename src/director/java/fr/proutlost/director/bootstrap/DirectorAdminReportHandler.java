package fr.proutlost.director.bootstrap;
import fr.proutlost.director.api.codec.*;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.port.ActorAuthorityPort;
import fr.proutlost.director.api.error.ReasonCode;
import fr.proutlost.director.runtime.RuntimeAdminMailbox;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.*;
/** A rate-limited player report is persisted as untrusted personal input, not
 * translated into an observation, a consequence or a Quest fact. */
public final class DirectorAdminReportHandler implements RuntimeAdminMailbox.Handler {
 private final Supplier<DirectorWorldState> world;private final ActorAuthorityPort authority;private final LongSupplier clock;private final ProviderMode mode;
 public DirectorAdminReportHandler(Supplier<DirectorWorldState> world,ActorAuthorityPort authority,LongSupplier clock,ProviderMode mode){this.world=world;this.authority=authority;this.clock=clock;this.mode=mode;}
 public ProviderMode mode(){return mode;}
 public RuntimeAdminMailbox.Change propose(AdminCommand command,AdminSnapshotStamp before){
  if(command.operation()!=AdminOperation.REPORT||!(command.payload() instanceof ReportPayload payload))throw new RuntimeAdminMailbox.Rejected(ReasonCode.CAPABILITY_MISSING);
  var actor=authority.current(command.actorSession()).filter(a->AdminContracts.authorized(command,a)).orElseThrow(()->new RuntimeAdminMailbox.Rejected(ReasonCode.AUTH_DENIED));
  var player=actor.playerId().orElseThrow(()->new RuntimeAdminMailbox.Rejected(ReasonCode.AUTH_DENIED));var old=world.get();
  if(!old.header().revision().equals(command.expectedRevision())||!old.header().revision().equals(before.snapshotRevision()))throw new RuntimeAdminMailbox.Rejected(ReasonCode.STALE_CONTEXT);
  var tick=new Tick(Math.max(clock.getAsLong(),old.simTick().value()));var header=new AggregateHeader(1,old.header().id(),old.header().revision().plus(1),old.header().lastSequence(),tick);
  var next=new DirectorWorldState(header,old.worldId(),old.storeGeneration(),tick,old.runControl(),old.healthMode(),old.pacingState(),old.tension(),old.intensityProfileId(),old.catalogHash(),old.configHash(),old.bootEpoch(),old.lastCheckpointSeq(),old.engineControl());
  var id=UUID.nameUUIDFromBytes((old.worldId().value()+"/player-report/"+command.actorSession().value()+"/"+command.requestId().value()).getBytes(StandardCharsets.UTF_8));
  var fact=new DirectorFact(new FactId(id),new FactType("proutlost_director:player_report"),List.of(new ScopeRef(ScopeKind.PLAYER,player.value())),Optional.empty(),EvidenceStatus.CONFIRMED,
   Map.of("untrusted_input",new JsonValue.Bool(true),"message",new JsonValue.Text(payload.message()),"correlations",CanonicalJsonV1.decode(new ModelCodec<>(ReportPayload.class).encode(payload))),true,Optional.empty());
  var stamp=new AdminSnapshotStamp(before.worldId(),before.bootEpoch(),header.revision(),tick,before.lastDurableSequence());
  return new RuntimeAdminMailbox.Change(stamp,new AdminResult(AdminStatus.SUCCEEDED,command.requestId(),header.revision(),Optional.empty(),Optional.empty()),
   Map.of(new ScopeRef(ScopeKind.WORLD,old.worldId().value()),old.header().revision()),List.of(new DurableAggregate(new WorldAggregate(next)),new DurableFact(fact)),List.of());
 }
}
