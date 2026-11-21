package fr.proutlost.director.bootstrap;

import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.error.ReasonCode;
import fr.proutlost.director.api.port.ActorAuthorityPort;
import fr.proutlost.director.admin.server.AdminFacade;
import fr.proutlost.director.persistence.JournalStore;
import fr.proutlost.director.runtime.RuntimeAdminMailbox;
import fr.proutlost.director.adapter.neoforge.network.DirectorNativeNetworkBootstrap;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import net.minecraft.server.MinecraftServer;

/** Two TEST_ONLY J07 operations over the existing durable runtime mailbox.
 * Recovery rows are supplied only by Gameplay's ReadContracts-validated scan.
 * No effects, new protocol, fabricated actor, or unacknowledged success. */
public final class DirectorNativeF8Control implements AutoCloseable {
 private final MinecraftServer server;private final JournalStore store;
 private final StoreSession session;private final DirectorNativeCatalogueControl control;
 private final BooleanSupplier resumeReady;
 private final Map<RuntimeAdminKey,RuntimeAdminAudit> recovered=new LinkedHashMap<>();
 private final Set<RuntimeAdminKey> retainedKeys=new HashSet<>();
 private final Map<RuntimeAdminKey,CompletableFuture<AdminResult>> requests=new LinkedHashMap<>();
 private RuntimeAdminMailbox mailbox;private AdminFacade facade;
 private boolean recoveryDone,started,closed;
 public DirectorNativeF8Control(MinecraftServer server,JournalStore store,
   DirectorNativeCatalogueControl control,BooleanSupplier resumeReady){
  this.server=Objects.requireNonNull(server);this.store=Objects.requireNonNull(store);
  this.control=Objects.requireNonNull(control);this.resumeReady=Objects.requireNonNull(resumeReady);
  this.session=store.session();actor();
  control.adminPending(this::pending);
 }
 private void actor(){if(closed||!server.isSameThread())throw new IllegalStateException("J07 control actor");}
 private ActorAuthorityPort authority(){return id->DirectorNativeNetworkBootstrap.adminActor(server,id);}
 public void resetRecoveryScan(){actor();if(recoveryDone)throw new IllegalStateException("admin already recovered");recovered.clear();}
 public void acceptRecoveryRows(List<DurableRecord> rows,boolean last){
  actor();if(recoveryDone)throw new IllegalStateException("duplicate admin recovery");
  for(var row:rows)if(row instanceof DurableRuntimeAdminAudit durable){
   var audit=durable.audit();var key=new RuntimeAdminKey(audit.command().actorSession(),audit.command().requestId());
   var old=recovered.putIfAbsent(key,audit);
   if(old!=null&&!old.equals(audit))throw new IllegalStateException("ambiguous J07 audit");
  }
  if(!last)return;
  if(recovered.size()>256)throw new IllegalStateException("J07 audit capacity");
  var handler=new RuntimeAdminMailbox.Handler(){
   public ProviderMode mode(){return ProviderMode.TEST_ONLY;}
   public RuntimeAdminMailbox.Change propose(AdminCommand command,AdminSnapshotStamp before){
    if(command.operation()==AdminOperation.RESUME&&!resumeReady.getAsBoolean())
     throw new IllegalStateException("catalogue providers/recovery not ready");
    return control.proposeAdmin(command,before);
   }
  };
  mailbox=new RuntimeAdminMailbox(store,authority(),control::adminStamp,control::publishAdmin,
   Map.of(AdminOperation.PAUSE,handler,AdminOperation.RESUME,handler),256,ProviderMode.TEST_ONLY);
  recovered.values().forEach(mailbox::restore);retainedKeys.addAll(recovered.keySet());recovered.clear();
  facade=new AdminFacade(mailbox,authority(),session.worldId(),session.bootEpoch(),64);
  recoveryDone=true;
 }
 private AdminResult rejected(AdminCommand command,AdminStatus status,ReasonCode reason){
  return new AdminResult(status,command.requestId(),command.expectedRevision(),Optional.empty(),
   Optional.of(new SystemError(reason,Severity.WARNING,status==AdminStatus.DEFERRED,Optional.empty(),
    Set.of(),new CorrelationId(command.requestId().value()),SafeState.UNCHANGED)));
 }
 public boolean available(){
  actor();return recoveryDone&&session.equals(store.session())&&!store.readOnlySafe()&&control.adminConfigured();
 }
 public boolean pending(){actor();return mailbox!=null&&mailbox.hasWork()||requests.values().stream().anyMatch(f->!f.isDone());}
 public CompletionStage<AdminResult> submit(AdminCommand command){
  actor();Objects.requireNonNull(command);
  if(command.operation()!=AdminOperation.PAUSE&&command.operation()!=AdminOperation.RESUME)
   return CompletableFuture.completedFuture(rejected(command,AdminStatus.REJECTED,ReasonCode.CAPABILITY_MISSING));
  var identity=authority().current(command.actorSession());
  if(identity.isEmpty()||!AdminContracts.authorized(command,identity.get()))
   return CompletableFuture.completedFuture(rejected(command,AdminStatus.REJECTED,ReasonCode.AUTH_DENIED));
  if(!available()||command.operation()==AdminOperation.RESUME&&!resumeReady.getAsBoolean())
   return CompletableFuture.completedFuture(rejected(command,AdminStatus.DEFERRED,ReasonCode.STALE_CONTEXT));
  var key=new RuntimeAdminKey(command.actorSession(),command.requestId());
  requests.entrySet().removeIf(e->e.getValue().isDone());
  if(!retainedKeys.contains(key)&&retainedKeys.size()>=256)
   return CompletableFuture.completedFuture(rejected(command,AdminStatus.REJECTED,ReasonCode.RATE_LIMITED));
  retainedKeys.add(key);
  var result=facade.submit(command,identity.get());requests.putIfAbsent(key,result.toCompletableFuture());
  return result;
 }
 public void poll(){
  actor();if(!recoveryDone)return;
  mailbox.rejectRevokedQueued();
  // The existing control owns WORLD for all queued/applying requests. Its old
  // scheduler may finish an ACK, but no new writer can race this transaction.
  if(pending()&&(started||control.adminWorldWriterIdle())){started=true;mailbox.pump();}
  facade.tick();if(!pending())started=false;
 }
 @Override public void close(){
  actor();if(facade!=null)for(var id:requests.keySet().stream().map(RuntimeAdminKey::actorSession).distinct().toList())facade.disconnect(id);
  closed=true;
 }
}
