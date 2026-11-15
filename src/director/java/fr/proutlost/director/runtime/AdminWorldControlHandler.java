package fr.proutlost.director.runtime;

import fr.proutlost.director.api.error.ReasonCode;
import fr.proutlost.director.api.model.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.*;

/** Pure WORLD proposals. The mailbox commits state, facts and audit before publication. */
public final class AdminWorldControlHandler implements RuntimeAdminMailbox.Handler {
    public static final Set<AdminOperation> OPERATIONS=Set.of(AdminOperation.PAUSE,AdminOperation.RESUME,
            AdminOperation.DRAIN,AdminOperation.TENSION,AdminOperation.INTENSITY);
    private final Supplier<DirectorWorldState> world;
    private final Map<ResourceId,ConfigView> profiles;
    private final LongSupplier now;
    private final ProviderMode mode;
    public AdminWorldControlHandler(Supplier<DirectorWorldState> world,Map<ResourceId,ConfigView> profiles,
            LongSupplier now,ProviderMode mode) {
        this.world=Objects.requireNonNull(world);this.profiles=Map.copyOf(profiles);
        this.now=Objects.requireNonNull(now);this.mode=Objects.requireNonNull(mode);
        if(mode==ProviderMode.PRODUCTION&&this.profiles.values().stream().anyMatch(p->p.calibration()!=Calibration.APPROVED))
            throw new IllegalArgumentException("production intensity profiles must be approved");
    }
    public ProviderMode mode(){return mode;}
    public RuntimeAdminMailbox.Change propose(AdminCommand command,AdminSnapshotStamp before) {
        if(!OPERATIONS.contains(command.operation()))throw new RuntimeAdminMailbox.Rejected(ReasonCode.CAPABILITY_MISSING);
        var old=world.get();long tick=now.getAsLong();
        if(!old.worldId().equals(before.worldId())||!old.bootEpoch().equals(before.bootEpoch())
                ||!old.header().revision().equals(command.expectedRevision())||!before.snapshotRevision().equals(old.header().revision())
                ||tick<old.simTick().value())throw new RuntimeAdminMailbox.Rejected(ReasonCode.STALE_CONTEXT);
        var control=old.runControl();var tension=old.tension();var profileId=old.intensityProfileId();var configHash=old.configHash();
        var extra=new ArrayList<DurableRecord>();
        switch(command.payload()) {
            case ControlPayload payload -> {
                if(command.operation()==AdminOperation.RESUME&&old.healthMode()!=HealthMode.NORMAL&&old.healthMode()!=HealthMode.DEGRADED)
                    throw new RuntimeAdminMailbox.Rejected(ReasonCode.RECOVERY_AMBIGUOUS);
                control=payload.control();
            }
            case IntensityPayload payload -> {
                var selected=profiles.get(payload.profileId());
                if(selected==null)throw new RuntimeAdminMailbox.Rejected(ReasonCode.NO_TARGET);
                profileId=payload.profileId();configHash=selected.provenanceHash();
            }
            case TensionPayload payload -> {
                if(payload.expiresTick().value()<=tick||payload.expiresTick().value()-tick>payload.duration().value())
                    throw new RuntimeAdminMailbox.Rejected(ReasonCode.STALE_CONTEXT);
                tension=payload.tension();
                var identity=(old.worldId().value()+"/admin-tension/"+command.actorSession().value()+"/"+command.requestId().value()).getBytes(StandardCharsets.UTF_8);
                var fact=new DirectorFact(new FactId(UUID.nameUUIDFromBytes(identity)),AdminContracts.TENSION_OVERRIDE_FACT,
                        List.of(new ScopeRef(ScopeKind.WORLD,old.worldId().value())),Optional.empty(),EvidenceStatus.CONFIRMED,
                        Map.of("tension",new JsonValue.Text(payload.tension().name()),"created_tick",new JsonValue.Text(Long.toString(tick)),
                                "expires_tick",new JsonValue.Text(Long.toString(payload.expiresTick().value())),
                                "control_revision",new JsonValue.Text(Long.toString(old.header().revision().plus(1).value()))),true,Optional.empty());
                extra.add(new DurableFact(fact));
            }
            default -> throw new RuntimeAdminMailbox.Rejected(ReasonCode.CAPABILITY_MISSING);
        }
        var header=new AggregateHeader(1,old.header().id(),old.header().revision().plus(1),old.header().lastSequence(),new Tick(tick));
        var next=new DirectorWorldState(header,old.worldId(),old.storeGeneration(),new Tick(tick),control,old.healthMode(),
                old.pacingState(),tension,profileId,old.catalogHash(),configHash,old.bootEpoch(),old.lastCheckpointSeq(),old.engineControl());
        var after=new AdminSnapshotStamp(before.worldId(),before.bootEpoch(),before.snapshotRevision().plus(1),new Tick(tick),before.lastDurableSequence());
        var records=new ArrayList<DurableRecord>();records.add(new DurableAggregate(new WorldAggregate(next)));records.addAll(extra);
        return new RuntimeAdminMailbox.Change(after,new AdminResult(AdminStatus.SUCCEEDED,command.requestId(),after.snapshotRevision(),Optional.empty(),Optional.empty()),
                Map.of(new ScopeRef(ScopeKind.WORLD,old.worldId().value()),old.header().revision()),records,List.of());
    }
    /** Runtime must use this exact registered configuration for the next decision. */
    public static ConfigView configuration(DirectorWorldState world,Map<ResourceId,ConfigView> profiles) {
        var selected=profiles.get(world.intensityProfileId());
        if(selected==null||!selected.provenanceHash().equals(world.configHash()))
            throw new IllegalStateException("unregistered durable intensity profile");
        return selected;
    }
}
