package fr.proutlost.director.runtime;

import fr.proutlost.director.api.codec.*;
import fr.proutlost.director.api.error.ReasonCode;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.port.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Durable dispatch to existing actor-owned operations. A queue receipt is never success. */
public final class AdminOperationDispatcher implements RuntimeAdminPort,ProviderProvenance {
    public static final FactType TYPE=new FactType("proutlost_director:admin_operation_intent");
    public static final Set<AdminOperation> OPERATIONS=Set.of(AdminOperation.TRIGGER,AdminOperation.STOP,
            AdminOperation.STABILIZE,AdminOperation.RESCUE);
    public interface Hooks {
        /** Called once after the exact intent ACK. Must use ordinary admission/cleanup safety paths. */
        AdminResult launch(AdminCommand command,ActorIdentity actor);
        /** Terminal success comes only from the actual started event or completed cleanup. */
        Optional<AdminResult> progress(RuntimeAdminKey key);
    }
    private enum Stage { INTENT_PENDING, LAUNCHED, RESULT_PENDING, TERMINAL, UNCERTAIN }
    private record Delivery(CommitAck ack,Throwable failure) {}
    private static final class Entry {
        final AdminCommand command;final Fingerprint hash;final ActorIdentity actor;final AdminSnapshotStamp before;
        Stage stage;AdminResult result,terminal;StoreSession session;DurableBatch batch;
        CompletableFuture<Delivery> delivery;
        Entry(AdminCommand command,Fingerprint hash,ActorIdentity actor,AdminSnapshotStamp before,AdminResult result,Stage stage) {
            this.command=command;this.hash=hash;this.actor=actor;this.before=before;this.result=result;this.stage=stage;
        }
    }
    private final Thread actor=Thread.currentThread();
    private final DurableStore store;private final ActorAuthorityPort authority;
    private final Supplier<AdminSnapshotStamp> current;private final RuntimeAdminMailbox controls;
    private final Hooks hooks;private final int capacity;private boolean fenced;private int pumpCursor;
    private final Map<RuntimeAdminKey,Entry> entries=new LinkedHashMap<>();
    public AdminOperationDispatcher(DurableStore store,ActorAuthorityPort authority,Supplier<AdminSnapshotStamp> current,
            RuntimeAdminMailbox controls,Hooks hooks,int capacity) {
        this.store=Objects.requireNonNull(store);this.authority=Objects.requireNonNull(authority);
        this.current=Objects.requireNonNull(current);this.controls=Objects.requireNonNull(controls);this.hooks=Objects.requireNonNull(hooks);
        if(capacity<1)throw new IllegalArgumentException("admin operation capacity");this.capacity=capacity;
    }
    private void actor(){if(Thread.currentThread()!=actor)throw new IllegalStateException("admin operation server actor required");}
    public Set<ProviderMode> supportedModes(){return controls.supportedModes();}
    private static RuntimeAdminKey key(AdminCommand command){return new RuntimeAdminKey(command.actorSession(),command.requestId());}
    private Optional<ActorIdentity> authorized(AdminCommand command) {
        return authority.current(command.actorSession()).filter(a->AdminContracts.authorized(command,a));
    }
    private AdminResult result(AdminCommand command,AdminStatus status,ReasonCode reason) {
        return new AdminResult(status,command.requestId(),current.get().snapshotRevision(),Optional.empty(),reason==null?Optional.empty():
                Optional.of(new SystemError(reason,Severity.WARNING,status==AdminStatus.DEFERRED&&!Set.of(ReasonCode.RECOVERY_AMBIGUOUS,
                        ReasonCode.WAL_UNAVAILABLE,ReasonCode.DELIVERY_UNCERTAIN).contains(reason),Optional.empty(),Set.of(),
                        new CorrelationId(command.requestId().value()),status==AdminStatus.DEFERRED?SafeState.DEFERRED:SafeState.UNCHANGED)));
    }
    private static Fingerprint hash(AdminCommand command){return BlobContent.hash(new ModelCodec<>(AdminCommand.class).encode(command));}
    private static UUID id(AdminCommand command,WorldId world,String kind) {
        return UUID.nameUUIDFromBytes((world.value()+"/admin-operation/"+command.actorSession().value()+"/"+
                command.requestId().value()+"/"+kind).getBytes(StandardCharsets.UTF_8));
    }
    private static <T> JsonValue encode(Class<T> type,T value){return CanonicalJsonV1.decode(new ModelCodec<>(type).encode(value));}
    private static <T> T decode(Class<T> type,JsonValue value){return new ModelCodec<>(type).decode(CanonicalJsonV1.encode(value));}
    @Override public AdminResult submit(AdminCommand command) {
        actor();var identity=authorized(command);if(identity.isEmpty())return result(command,AdminStatus.REJECTED,ReasonCode.AUTH_DENIED);
        var key=key(command);var old=entries.get(key);var digest=hash(command);
        if(old!=null)return old.hash.equals(digest)?old.result:result(command,AdminStatus.REJECTED,ReasonCode.IDEMPOTENCY_CONFLICT);
        if(controls.owns(key)||controls.handles(command.operation()))return controls.submit(command);
        if(!OPERATIONS.contains(command.operation()))return result(command,AdminStatus.REJECTED,ReasonCode.CAPABILITY_MISSING);
        if(command.operation()==AdminOperation.TRIGGER&&(fenced||entries.values().stream().anyMatch(e->e.stage==Stage.UNCERTAIN)))
            return result(command,AdminStatus.DEFERRED,ReasonCode.RECOVERY_AMBIGUOUS);
        if(entries.size()>=capacity)return result(command,AdminStatus.REJECTED,ReasonCode.RATE_LIMITED);
        var before=current.get();var session=store.session();
        if(!command.expectedRevision().equals(before.snapshotRevision())||!session.worldId().equals(before.worldId())||!session.bootEpoch().equals(before.bootEpoch()))
            return result(command,AdminStatus.REJECTED,ReasonCode.STALE_CONTEXT);
        var entry=new Entry(command,digest,identity.orElseThrow(),before,result(command,AdminStatus.PENDING,null),Stage.INTENT_PENDING);
        var fact=new DirectorFact(new FactId(id(command,before.worldId(),"intent")),TYPE,
                List.of(new ScopeRef(ScopeKind.WORLD,before.worldId().value())),Optional.empty(),EvidenceStatus.CONFIRMED,
                Map.of("command",encode(AdminCommand.class,command),"command_hash",new JsonValue.Text(digest.value()),
                        "authority",encode(ActorIdentity.class,entry.actor),"before",encode(AdminSnapshotStamp.class,before),
                        "session",encode(StoreSession.class,session)),true,Optional.empty());
        entries.put(key,entry);
        append(entry,ResultTransaction.batch(new TransactionId(id(command,before.worldId(),"intent")),session.storeGeneration(),
                Map.of(new ScopeRef(ScopeKind.WORLD,before.worldId().value()),before.snapshotRevision()),List.of(new DurableFact(fact)),List.of()));
        return entry.result;
    }
    @Override public AdminResult poll(RuntimeAdminKey key) {
        actor();var entry=entries.get(key);if(entry==null)return controls.poll(key);
        return authorized(entry.command).isPresent()?entry.result:result(entry.command,AdminStatus.REJECTED,ReasonCode.AUTH_DENIED);
    }
    private void append(Entry entry,DurableBatch batch) {
        entry.batch=batch;entry.session=store.session();entry.delivery=new CompletableFuture<>();
        var mailbox=entry.delivery;
        try{store.append(batch).whenComplete((ack,failure)->mailbox.complete(new Delivery(ack,failure)));}
        catch(RuntimeException failure){mailbox.complete(new Delivery(null,failure));}
    }
    private boolean durable(Entry entry,Delivery delivery) {
        var ack=delivery.ack();return delivery.failure()==null&&ack!=null&&ack.ackStatus()==AckStatus.DURABLE
                &&entry.session.equals(store.session())&&ack.storeGeneration().equals(entry.session.storeGeneration())
                &&ack.transactionId().equals(entry.batch.transactionId())&&ack.transactionHash().equals(entry.batch.transactionHash())
                &&ack.durableSequence().value()>0&&ack.permitAttempt().isEmpty()
                &&ack.forcedBlobs().equals(DurableBlobReferences.collect(entry.batch.records()));
    }
    private void uncertain(Entry entry,ReasonCode reason) {
        fenced=true;entry.stage=Stage.UNCERTAIN;entry.result=result(entry.command,AdminStatus.DEFERRED,reason);
    }
    /** At most one request is advanced per actor pump; callbacks only carry immutable ACKs. */
    public void pump() {
        actor();var ready=entries.values().stream().filter(e->e.stage==Stage.LAUNCHED||
                (e.stage==Stage.INTENT_PENDING||e.stage==Stage.RESULT_PENDING)&&e.delivery.isDone()).toList();
        if(ready.isEmpty())return;var entry=ready.get(Math.floorMod(pumpCursor++,ready.size()));
        if(entry.stage==Stage.INTENT_PENDING||entry.stage==Stage.RESULT_PENDING) {
            var delivery=entry.delivery.getNow(null);if(delivery==null)return;
            if(!durable(entry,delivery)){fenced=true;uncertain(entry,ReasonCode.WAL_UNAVAILABLE);return;}
            if(entry.stage==Stage.RESULT_PENDING){entry.result=entry.terminal;entry.stage=Stage.TERMINAL;return;}
            var identity=authorized(entry.command);var stamp=current.get();
            if(identity.isEmpty()||identity.get().kind()!=entry.actor.kind()||!identity.get().playerId().equals(entry.actor.playerId())
                    ||!identity.get().systemModule().equals(entry.actor.systemModule())||identity.get().permissionLevel()<entry.actor.permissionLevel()) {
                finish(entry,result(entry.command,AdminStatus.REJECTED,ReasonCode.AUTH_DENIED));return;
            }
            if(!entry.command.expectedRevision().equals(stamp.snapshotRevision())) {
                finish(entry,result(entry.command,AdminStatus.REJECTED,ReasonCode.STALE_CONTEXT));return;
            }
            entry.stage=Stage.LAUNCHED; // Set before calling: exceptions may follow a partial enqueue.
            try{accept(entry,hooks.launch(entry.command,identity.orElseThrow()));}
            catch(RuntimeException ambiguous){entry.result=result(entry.command,AdminStatus.DEFERRED,ReasonCode.DELIVERY_UNCERTAIN);}
            return;
        }
        if(!entry.session.equals(store.session())){fenced=true;uncertain(entry,ReasonCode.WAL_UNAVAILABLE);return;}
        try{hooks.progress(key(entry.command)).ifPresent(value->accept(entry,value));}
        catch(RuntimeException ambiguous){entry.result=result(entry.command,AdminStatus.DEFERRED,ReasonCode.DELIVERY_UNCERTAIN);}
    }
    private void accept(Entry entry,AdminResult value) {
        if(value==null||!value.requestId().equals(entry.command.requestId())) {
            uncertain(entry,ReasonCode.DELIVERY_UNCERTAIN);return;
        }
        if(value.status()==AdminStatus.PENDING||value.status()==AdminStatus.DEFERRED){entry.result=value;return;}
        if(value.status()==AdminStatus.SUCCEEDED&&entry.command.payload() instanceof TriggerPayload requested) {
            if(value.data().isEmpty()||!(value.data().get().data() instanceof EventAdminData data)
                    ||data.event().startedTick().isEmpty()||!data.event().definition().id().equals(requested.eventId())
                    ||!data.event().regionIds().contains(requested.regionId())
                    ||!value.data().get().scope().equals(new ScopeRef(ScopeKind.EVENT,data.event().instanceId().value()))
                    ||!data.event().roleAssignments().roles().values().stream().flatMap(Collection::stream).collect(java.util.stream.Collectors.toSet()).containsAll(requested.targets())) {
                uncertain(entry,ReasonCode.DELIVERY_UNCERTAIN);return;
            }
        }
        finish(entry,value);
    }
    private void finish(Entry entry,AdminResult value) {
        var after=current.get();
        if(!after.worldId().equals(entry.before.worldId())||!after.bootEpoch().equals(entry.before.bootEpoch())) {
            uncertain(entry,ReasonCode.RECOVERY_AMBIGUOUS);return;
        }
        entry.terminal=new AdminResult(value.status(),entry.command.requestId(),after.snapshotRevision(),value.data(),value.error());
        var audit=new RuntimeAdminAudit(entry.command,entry.hash,entry.actor,entry.before,after,entry.terminal);
        entry.stage=Stage.RESULT_PENDING;entry.result=result(entry.command,AdminStatus.PENDING,null);
        append(entry,ResultTransaction.batch(new TransactionId(id(entry.command,after.worldId(),"terminal")),store.session().storeGeneration(),
                Map.of(),List.of(new DurableRuntimeAdminAudit(audit)),List.of()));
    }
    /** Restore before accepting any command. An intent is not permission to enqueue again. */
    public void restore(DirectorFact fact) {
        actor();if(!fact.factType().equals(TYPE))return;
        if(!fact.durable()||fact.evidenceStatus()!=EvidenceStatus.CONFIRMED||fact.eventId().isPresent()
                ||!fact.payload().keySet().equals(Set.of("command","command_hash","authority","before","session")))
            throw new IllegalArgumentException("corrupt admin operation intent");
        var command=decode(AdminCommand.class,fact.payload().get("command"));var digest=hash(command);
        var identity=decode(ActorIdentity.class,fact.payload().get("authority"));
        var before=decode(AdminSnapshotStamp.class,fact.payload().get("before"));var saved=decode(StoreSession.class,fact.payload().get("session"));
        if(!OPERATIONS.contains(command.operation())||!AdminContracts.authorized(command,identity)
                ||!(fact.payload().get("command_hash") instanceof JsonValue.Text declared)||!digest.value().equals(declared.value())
                ||!saved.worldId().equals(before.worldId())||!saved.worldId().equals(store.session().worldId())
                ||!saved.storeGeneration().equals(store.session().storeGeneration())||!saved.bootEpoch().equals(before.bootEpoch())
                ||!fact.subjectRefs().equals(List.of(new ScopeRef(ScopeKind.WORLD,before.worldId().value())))
                ||!fact.factId().value().equals(id(command,before.worldId(),"intent")))throw new IllegalArgumentException("admin intent binding");
        var key=key(command);if(controls.owns(key))throw new IllegalArgumentException("admin intent/control key conflict");var old=entries.get(key);
        if(old!=null){if(!old.hash.equals(digest))throw new IllegalArgumentException("admin intent conflict");return;}
        if(entries.size()>=capacity)throw new IllegalStateException("admin restore capacity");
        entries.put(key,new Entry(command,digest,identity,before,result(command,AdminStatus.DEFERRED,ReasonCode.RECOVERY_AMBIGUOUS),Stage.UNCERTAIN));
    }
    public void restore(RuntimeAdminAudit audit) {
        actor();if(controls.handles(audit.command().operation())){
            if(entries.containsKey(key(audit.command())))throw new IllegalArgumentException("admin control/intent key conflict");controls.restore(audit);return;}
        if(!OPERATIONS.contains(audit.command().operation()))return;
        if(!audit.before().worldId().equals(store.session().worldId()))throw new IllegalArgumentException("admin receipt world");
        var key=key(audit.command());if(controls.owns(key))throw new IllegalArgumentException("admin receipt/control key conflict");var old=entries.get(key);
        if(old!=null&&!old.hash.equals(audit.commandHash()))throw new IllegalArgumentException("admin receipt conflict");
        if(old!=null&&old.stage==Stage.TERMINAL&&!old.result.equals(audit.result()))throw new IllegalArgumentException("conflicting terminal admin receipts");
        if(old==null){if(entries.size()>=capacity)throw new IllegalStateException("admin restore capacity");
            old=new Entry(audit.command(),audit.commandHash(),audit.appliedAuthority(),audit.before(),audit.result(),Stage.TERMINAL);entries.put(key,old);}
        else{old.result=audit.result();old.terminal=audit.result();old.stage=Stage.TERMINAL;}
    }
    public boolean hasPendingWrites(){actor();return entries.values().stream().anyMatch(e->e.stage==Stage.INTENT_PENDING||e.stage==Stage.RESULT_PENDING);}
}
