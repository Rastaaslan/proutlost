package fr.proutlost.director.adapter.neoforge.server;

import fr.proutlost.director.neoforge.DirectorStandaloneMod;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.port.*;
import fr.proutlost.director.bootstrap.*;
import fr.proutlost.director.persistence.*;
import fr.proutlost.director.runtime.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.gametest.framework.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.*;

/**
 * REAL J02 packed safe_micro -> REAL J03 selected plan -> J04 audit fsync
 * -> J05 AdmissionService logical reservation. J05 TEST_ONLY HardValidator
 * uses bounded fixture evidence and does not activate a sound output.
 * This proves G3/G5 selection-to-admission ordering, NOT J06 rendering
 * nor a production-permitted event. It never accesses live worlds.
 */
@GameTestHolder("proutlost_director")
@PrefixGameTestTemplate(false)
public final class DirectorNativeSelectedAdmissionGameTests {
 private DirectorNativeSelectedAdmissionGameTests(){}
 private static final StoreOptions OPTIONS=new StoreOptions(
     1_048_620,2_097_152,4_194_304,16_777_216,32,0,128);
 private static final class Probe {
  final MinecraftServer server;
  final DirectorNativeJ02J03SelectedSafeMicroGameTests.TestScene scene;
  final WorldId world;
  final java.nio.file.Path root;
  final CompletableFuture<JournalStore> opening;
  final AtomicInteger validations=new AtomicInteger();
  JournalStore store;
  CompletableFuture<CommitAck> initial;
  CompletableFuture<Void> closing;
  CompletableFuture<JournalStore> reopened;
  AdmissionService admission;
  ResourceArbiter arbiter;
  DirectorDecisionAdmissionBridge bridge;
  DecisionId decision;
  int stage;
  Probe(GameTestHelper helper){
   server=Objects.requireNonNull(helper.getLevel().getServer());
   if(!server.isSameThread())throw new IllegalStateException("actor");
   scene=DirectorNativeJ02J03SelectedSafeMicroGameTests.select(helper);
   world=scene.input().state().world().worldId();
   root=server.getWorldPath(LevelResource.ROOT)
       .resolve("director-j02-selected-admission-test-only")
       .resolve(UUID.randomUUID().toString());
   opening=JournalStore.open(root,world,OPTIONS).toCompletableFuture();
  }
  private void actor(){
   if(!server.isSameThread())throw new IllegalStateException("server actor");
  }
  void advance(GameTestHelper helper) {
   actor();
   switch(stage){
    case 0 -> {
     helper.assertTrue(opening.isDone()&&!opening.isCompletedExceptionally(),
       "J04 selected catalog WAL startup pending");
     store=opening.getNow(null);
     helper.assertTrue(store!=null && !store.readOnlySafe(),"J04 writer unavailable");
     var state=scene.input().state().world();
     initial=store.append(ResultTransaction.batch(new TransactionId(UUID.randomUUID()),
       store.session().storeGeneration(),Map.of(),
       List.of(new DurableAggregate(new WorldAggregate(state))),List.of()))
       .toCompletableFuture();
     stage=1;
    }
    case 1 -> {
     helper.assertTrue(initial.isDone() && !initial.isCompletedExceptionally(),
       "J03 selected WORLD initial WAL fsync pending");
     helper.assertTrue(initial.getNow(null).ackStatus()==AckStatus.DURABLE,
       "initial J04 world CAS not durable");
     arbiter=new ResourceArbiter(world,new Epoch(0),1000,8,
       new ResourceArbiter.Compatibility(){
        public boolean share(ResourceClaim l,ResourceClaim r){return false;}
        public boolean merge(ResourceId id,ResourceClaim l,ResourceClaim r){return false;}
       });
     CatalogPort catalog=scene::catalog;
     AdmissionService.HardValidator validator=new AdmissionService.HardValidator(){
      public ProviderMode mode(){return ProviderMode.TEST_ONLY;}
      public AdmissionService.Assessment assess(AdmissionRequest req,Tick now){
       validations.incrementAndGet();
       var event=scene.catalog().events().get(req.plan().definition().id());
       if(event==null || !event.families().contains(Family.SAFE_MICRO_EVENT)
          || req.plan().effects().size()!=1
          || !(req.plan().effects().getFirst().typedArgs() instanceof TargetSound))
          throw new IllegalArgumentException("J05 lab input not a safe sound");
       var ok=new ValidationResult(true,List.of(),req.expectedRevisions());
       var rank=new ResourceArbiter.Rank(false,false,false,
         Priority.MICRO,0,1.0,42L);
       var scope=new ScopeRef(ScopeKind.WORLD,world.value());
       return new AdmissionService.Assessment(ok,List.of(),rank,
         Map.of(scope,BudgetMath.zero()));
      }
     };
     admission=new AdmissionService(catalog,validator,arbiter,
       ()->new Tick(20100),new DurationTicks(400),8,ProviderMode.TEST_ONLY);
     var journal=new DirectorDecisionJournal(store,4);
     bridge=new DirectorDecisionAdmissionBridge(
       captured -> {
        helper.assertTrue(captured.equals(scene.input()),
          "J03 selected input was substituted");
        return scene.proposal();
       },journal,admission,4);
     decision=bridge.begin(scene.input());
     helper.assertTrue(validations.get()==0,
       "J05 was invoked BEFORE J03 selected audit WAL fsync");
     stage=2;
    }
    case 2 -> {
     var progress=bridge.poll(decision);
     if(progress.stage()==DirectorDecisionAdmissionBridge.Stage.WAITING_FOR_AUDIT)
      return;
     helper.assertTrue(progress.stage()==
       DirectorDecisionAdmissionBridge.Stage.ADMISSION_DEFERRED,
       "J05 logical admission must queue ONLY after audited J04 decision");
     helper.assertTrue(validations.get()==1,
       "J05 validator must examine the selected sound once");
     var row=store.view(new StoreQuery(world,Set.of(),
        new Sequence(0),Optional.empty(),32));
     helper.assertTrue(row.status()==ReadStatus.AVAILABLE &&
       row.page().orElseThrow().records().stream()
        .filter(x->x instanceof DurableAudit).count()==1,
       "J03 real selection audit missing in J04");
     // J07 reads the REAL J03 WAL audit; never a synthetic decision.
     var auditQuery=new AdminQuery(new SessionId(UUID.randomUUID()),
        new RequestId(UUID.randomUUID()),AdminPanel.DECISIONS,
        new DecisionFilter(Optional.of(decision)),
        Optional.empty(),Optional.empty(),10);
     var auditSnapshot=fr.proutlost.director.bootstrap.DirectorNativeAdminReadBridge
        .decisionPage(store,auditQuery,scene.proposal().stateChanges().stream()
            .map(change->change.next())
            .filter(WorldAggregate.class::isInstance).map(WorldAggregate.class::cast)
            .map(WorldAggregate::value).findFirst().orElseThrow());
     helper.assertTrue(auditSnapshot.isPresent() &&
        auditSnapshot.orElseThrow().rows().size()==1 &&
        auditSnapshot.orElseThrow().nextCursor().isEmpty() &&
        auditSnapshot.orElseThrow().capabilities().equals(fr.proutlost.director.admin.server.NativeReadCommandFence.supported()),
        "J07 must read real J03 audit from J04 WAL");
     var detail=(DecisionAdminData)auditSnapshot.orElseThrow().rows()
         .getFirst().data();
     helper.assertTrue(detail.decisionId().equals(decision) &&
        detail.selected().isPresent() &&
        detail.candidates().size()==scene.proposal().candidates().size(),
        "J07 actual decision detail mismatch");
     // J07 default DECISIONS list must return the exact durable audit,
     // not an invented empty tab or a stale mutable in-memory history.
     var listQuery=new AdminQuery(auditQuery.actorSession(),
         new RequestId(UUID.randomUUID()),AdminPanel.DECISIONS,
         new DecisionFilter(Optional.empty()),Optional.empty(),
         Optional.empty(),4);
     var listing=fr.proutlost.director.bootstrap.DirectorNativeAdminReadBridge
         .decisionPage(store,listQuery,scene.proposal().stateChanges().stream()
             .map(change->change.next())
             .filter(WorldAggregate.class::isInstance)
             .map(WorldAggregate.class::cast)
             .map(WorldAggregate::value).findFirst().orElseThrow());
     helper.assertTrue(listing.isPresent() &&
         listing.orElseThrow().rows().stream().allMatch(entry->
             entry.data() instanceof DecisionAdminData) &&
         listing.orElseThrow().rows().stream().anyMatch(entry->
             entry.data() instanceof DecisionAdminData actual &&
             actual.decisionId().equals(decision)) &&
         listing.orElseThrow().capabilities().equals(fr.proutlost.director.admin.server.NativeReadCommandFence.supported()),
         "J07 listed audit returned non-audit data or execution rights");
     // Force a one-record J04 page, then follow every returned opaque
     // cursor. A page with zero audits is not evidence of global absence.
     var continuation=Optional.<String>empty();
     var seenCursors=new HashSet<String>();
     boolean foundPaged=false;
     for(int attempt=0;attempt<12;attempt++) {
         var pagedQuery=new AdminQuery(auditQuery.actorSession(),
             new RequestId(UUID.randomUUID()),AdminPanel.DECISIONS,
             new DecisionFilter(Optional.empty()),continuation,
             Optional.empty(),1);
         var actualPage=fr.proutlost.director.bootstrap.DirectorNativeAdminReadBridge
             .decisionPage(store,pagedQuery,scene.proposal().stateChanges().stream()
                 .map(change->change.next())
                 .filter(WorldAggregate.class::isInstance)
                 .map(WorldAggregate.class::cast)
                 .map(WorldAggregate::value).findFirst().orElseThrow());
         helper.assertTrue(actualPage.isPresent() &&
             actualPage.orElseThrow().rows().size()<=1 &&
             actualPage.orElseThrow().capabilities().equals(fr.proutlost.director.admin.server.NativeReadCommandFence.supported()),
             "J07 cursor page forged more rows/privileges than requested");
         var page=actualPage.orElseThrow();
         if(page.rows().stream().anyMatch(entry->
             entry.data() instanceof DecisionAdminData actual &&
             actual.decisionId().equals(decision))) {
             foundPaged=true;
             break;
         }
         if(page.nextCursor().isEmpty())break;
         helper.assertTrue(seenCursors.add(page.nextCursor().orElseThrow()),
             "J07 WAL cursor repeated without progress");
         continuation=page.nextCursor();
     }
     helper.assertTrue(foundPaged,
         "J07 cursor skipped the durable J03 audit behind earlier WAL records");
     // Untrusted C2S cursor must fail closed on BOTH list and exact-ID
     // requests. The J04 reader never executes arbitrary client offsets.
     for(var filtered:List.of(Optional.<DecisionId>empty(),
                              Optional.of(decision))){
         var forged=new AdminQuery(auditQuery.actorSession(),
             new RequestId(UUID.randomUUID()),AdminPanel.DECISIONS,
             new DecisionFilter(filtered),
             Optional.of("not-a-valid-j04-wal-cursor"),Optional.empty(),1);
         helper.assertTrue(fr.proutlost.director.bootstrap.DirectorNativeAdminReadBridge
                 .decisionPage(store,forged,scene.proposal().stateChanges().stream()
                     .map(change->change.next())
                     .filter(WorldAggregate.class::isInstance)
                     .map(WorldAggregate.class::cast)
                     .map(WorldAggregate::value).findFirst().orElseThrow()).isEmpty(),
             "J07 accepted a forged client cursor or threw on invalid pagination");
     }
     System.out.println("DIRECTOR_NATIVE_J07_BAD_WAL_CURSOR_FAIL_CLOSED_PASS");
     System.out.println("DIRECTOR_NATIVE_J07_REAL_AUDIT_CURSOR_PAGINATION_PASS");
     System.out.println("DIRECTOR_NATIVE_J07_REAL_SELECTED_J03_AUDIT_FROM_WAL_PASS");
     var worldState=store.view(new StoreQuery(world,
        Set.of(new AggregateKey(AggregateKind.WORLD,world.value())),
        new Sequence(0),Optional.empty(),1));
     helper.assertTrue(worldState.page().orElseThrow().aggregates()
        .getFirst().state().header().revision().value()==2,
       "J03 selected world revision CAS not committed");
     var capacity=Map.of(new ScopeRef(ScopeKind.WORLD,world.value()),
       new BudgetVector(100,100,100,100,100,100,100,100,100,100));
     var resolved=admission.resolveWindow(Map.of(),capacity);
     helper.assertTrue(resolved.get(decision).status()==AdmissionStatus.ACCEPTED,
       "J05 failed to resolve safe logical reservation");
     var accepted=bridge.refreshResolvedWindow(decision);
     helper.assertTrue(accepted.stage()==
       DirectorDecisionAdmissionBridge.Stage.LOGICAL_RESERVATION_ONLY &&
       accepted.needsJ05Prepare(),
       "J05 logical reservation improperly became physical permit");
     var token=accepted.admission().orElseThrow().token().orElseThrow();
     helper.assertTrue(arbiter.snapshot().contains(token),
       "J05 did not track a real logical reservation");
     helper.assertTrue(arbiter.release(token),
       "J05 logical TEST_ONLY reservation not releasable");
     admission.released(decision,token);
     helper.assertTrue(arbiter.snapshot().isEmpty(),
       "J05 leaked logical reservation after test");
     helper.assertTrue(!DirectorStandaloneMod.directorLifecycle().snapshot(server)
       .admitsEvents(),"TEST_ONLY admission changed physical gameplay");
     closing=store.closeAsync().toCompletableFuture();
     stage=3;
     System.out.println("DIRECTOR_NATIVE_J02_J03_J04_J05_SELECTED_LOGICAL_ADMISSION_PASS");
    }
    case 3 -> {
     helper.assertTrue(closing.isDone() && !closing.isCompletedExceptionally(),
       "J04 selected audit close pending");
     reopened=JournalStore.open(root,world,OPTIONS).toCompletableFuture();
     stage=4;
    }
    case 4 -> {
     helper.assertTrue(reopened.isDone() && !reopened.isCompletedExceptionally(),
       "J04 selected audit replay pending");
     store=reopened.getNow(null);
     var row=store.view(new StoreQuery(world,Set.of(),
       new Sequence(0),Optional.empty(),32));
     helper.assertTrue(row.status()==ReadStatus.AVAILABLE &&
       row.page().orElseThrow().records().stream()
        .filter(x->x instanceof DurableAudit).count()==1,
       "real selected candidate audit lost across WAL restart");
     var finalWorld=scene.proposal().stateChanges().stream()
         .map(change->change.next())
         .filter(WorldAggregate.class::isInstance)
         .map(WorldAggregate.class::cast).map(WorldAggregate::value)
         .findFirst().orElseThrow();
     var target=new AdminQuery(new SessionId(UUID.randomUUID()),
         new RequestId(UUID.randomUUID()),AdminPanel.DECISIONS,
         new DecisionFilter(Optional.of(decision)),
         Optional.empty(),Optional.empty(),10);
     var recovered=fr.proutlost.director.bootstrap.DirectorNativeAdminReadBridge
         .decisionPage(store,target,finalWorld);
     helper.assertTrue(recovered.isPresent() &&
         recovered.orElseThrow().rows().size()==1 &&
         ((DecisionAdminData)recovered.orElseThrow().rows().getFirst().data())
           .decisionId().equals(decision),
         "J07 true selected decision not visible after WAL reopen");
     var unknown=new AdminQuery(target.actorSession(),new RequestId(UUID.randomUUID()),
         AdminPanel.DECISIONS,
         new DecisionFilter(Optional.of(new DecisionId(UUID.randomUUID()))),
         Optional.empty(),Optional.empty(),10);
     var missing=fr.proutlost.director.bootstrap.DirectorNativeAdminReadBridge
         .decisionPage(store,unknown,finalWorld);
     helper.assertTrue(missing.isPresent() && missing.orElseThrow().rows().isEmpty(),
         "J07 invented a missing decision in the recovered WAL");
     System.out.println("DIRECTOR_NATIVE_J07_REAL_SELECTED_AUDIT_REBOOT_PASS");
     closing=store.closeAsync().toCompletableFuture();
     stage=5;
    }
    case 5 -> {
     helper.assertTrue(closing.isDone() && !closing.isCompletedExceptionally(),
       "reopened WAL did not drain");
     stage=6;
     System.out.println("DIRECTOR_NATIVE_SELECTED_SAFE_MICRO_AUDIT_REBOOT_PASS");
    }
    case 6 -> {}
    default -> throw new IllegalStateException("unknown selected admission phase");
   }
  }
 }

 @GameTest(templateNamespace="proutlost_director",template="smoke_empty",timeoutTicks=12000)
 public static void selectedRealJ02MicroAuditedThenJ05Admitted(GameTestHelper helper){
  var probe=new Probe(helper);
  helper.succeedWhen(()->{
   probe.advance(helper);
   helper.assertTrue(probe.stage==6,
     "real J02 J03 selection, J04 WAL and J05 admission still pending");
  });
 }
}
