package fr.proutlost.director.adapter.neoforge.server;

import fr.proutlost.director.adapter.neoforge.server.effects.*;
import fr.proutlost.director.bootstrap.*;
import fr.proutlost.director.api.codec.ModelCodec;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.permission.CommitPermit;
import fr.proutlost.director.persistence.*;
import fr.proutlost.director.runtime.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.animal.goat.Goat;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.*;

/** Two distinct REAL native effects, each behind its own J04 durable PREPARE:
 * a one-slot goat visit then the exact-owner departure. No fake player and
 * no claim that the J02 production event is enabled by this LAB fixture. */
@GameTestHolder("proutlost_director")
@PrefixGameTestTemplate(false)
public final class DirectorNativeGoatVisitGameTests {
    private DirectorNativeGoatVisitGameTests(){}
    private static final StoreOptions OPTIONS=new StoreOptions(
        1_048_620,2_097_152,4_194_304,16_777_216,32,0,128);
    private static final BudgetVector CAP=new BudgetVector(100,100,100,100,100,100,100,100,100,100);

    private static final class Probe {
        final ServerLevel level;final net.minecraft.server.MinecraftServer server;
        final BlockPos block;final BlockPosition position;final DimensionId dimension;
        final WorldId world=new WorldId(UUID.randomUUID());
        final ScopeRef scope=new ScopeRef(ScopeKind.WORLD,world.value());
        final EventInstanceId instance=new EventInstanceId(UUID.randomUUID());
        final GoatVisit goatArgs;final CatalogSnapshot goatCatalog;
        final UUID uuid=UUID.randomUUID();final ResourceKey key;
        final Footprint footprint;final AtomicReference<EntityOwnershipRecord> slot;
        final GoatVisitBinding binding;final CompletableFuture<JournalStore> opening;
        final java.nio.file.Path root;
        JournalStore store;CommitPermit.Authority authority;ResourceArbiter arbiter;
        ServerEffectPort port;EventRunner runner;ReservationToken reservation;
        AdmissionService admission;DecisionId admittedDecision;
        PreparedEffect active;LiveValidationSnapshot live;TransactionId tx;
        CompletableFuture<Void> closed;
        CompletableFuture<CommitAck> initialOwner;
        CompletableFuture<JournalStore> reopened;
        int phase;
        Probe(GameTestHelper helper){
            level=helper.getLevel();server=Objects.requireNonNull(level.getServer());
            if(!server.isSameThread())throw new IllegalStateException("goat actor");
            block=helper.absolutePos(new BlockPos(1,2,1));
            level.setBlock(block.below(),Blocks.STONE.defaultBlockState(),3);
            level.setBlock(block,Blocks.AIR.defaultBlockState(),3);
            level.setBlock(block.above(),Blocks.AIR.defaultBlockState(),3);
            dimension=new DimensionId(level.dimension().location().toString());
            position=new BlockPosition(dimension,block.getX(),block.getY(),block.getZ());
            var chunk=new ChunkRef(dimension,block.getX()>>4,block.getZ()>>4);
            footprint=new Footprint(dimension,Set.of(chunk),Set.of(position),List.of());
            key=new ResourceKey(OwnershipDomain.ENTITY,dimension,uuid.toString());
            slot=new AtomicReference<>(new EntityOwnershipRecord(
                new AggregateHeader(1,UUID.randomUUID(),new Revision(1),new Sequence(0),new Tick(1)),
                new OwnerToken(UUID.randomUUID()),uuid,instance,new CohortId(UUID.randomUUID()),
                "goat-lab-1",EntityPhase.RESERVED,OwnershipDisposition.DIRECTOR_ACTIVE,
                chunk,new Tick(1),ArrivalPolicy.SETTLE_WORLD,CleanupReason.COMPLETED,
                Optional.empty(),new Epoch(1)));
            goatCatalog=new DirectorLabGoatCatalog(DirectorLabObservationProfile.load()).snapshot();
            var goatDefinition=goatCatalog.events().get(new DefinitionId("proutlost_director:candidate/goat_guest"));
            if(goatDefinition==null||goatDefinition.steps().size()!=1||
                !goatDefinition.steps().getFirst().primitive().equals(GoatVisitBinding.VISIT)||
                !(goatDefinition.steps().getFirst().args() instanceof GoatVisit))
                throw new IllegalStateException("J02 goat candidate does not match physical binding");
            goatArgs=(GoatVisit)goatDefinition.steps().getFirst().args();
            binding=new GoatVisitBinding(d->level,ignored->slot.get(),slot::set);
            root=server.getWorldPath(LevelResource.ROOT).resolve(
                "director-j05-goat-lab-only").resolve(UUID.randomUUID().toString());
            opening=JournalStore.open(root,world,OPTIONS).toCompletableFuture();
        }
        EffectPreparation capture(PrimitiveId primitive,int tick){
            var intent=new EffectIntent(new OperationToken(UUID.randomUUID()),primitive,1,
                goatArgs,footprint,instance);
            var cell=new TerrainCellSnapshot(position,
                BlobContent.capture(new ResourceId("proutlost:goat_habitat"),1,
                    new byte[]{1}).reference().hash(),
                new ResourceId("proutlost:goat_habitat"),Set.of(),Set.of(),false,true,
                new Epoch(1),new Epoch(0));
            var terrain=new TerrainSnapshot(footprint,Map.of(position,cell),
                List.of(),Map.of(),new Revision(1));
            var context=new ObservationContext(Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),
                Map.of(),List.of(terrain),Map.of());
            var snapshot=new SnapshotBatch(new RequestId(UUID.randomUUID()),
                new ScopeSet(world,Set.of(scope)),new Tick(tick),Map.of(),context,
                Set.of(),footprint.loadedChunks(),HealthMode.NORMAL);
            var plan=new PlanningSnapshot(snapshot,new OwnershipView(Map.of()),
                new BudgetView(CAP,BudgetMath.zero(),Map.of()),new BlobBundle(List.of()));
            var captured=binding.capture(intent,plan);
            if(!captured.validation().valid()||captured.prepared().isEmpty()
               ||!binding.revalidate(captured.prepared().orElseThrow(),captured.blobs()))
                throw new IllegalStateException("goat physical cannot capture/revalidate");
            return captured;
        }
        void submit(EffectPreparation captured,int tick){
            active=captured.prepared().orElseThrow();
            var intent=active.intent();
            if(intent.primitive().equals(GoatVisitBinding.VISIT)){
                var definition=goatCatalog.events().get(
                    new DefinitionId("proutlost_director:candidate/goat_guest"));
                var role=new PlayerId(UUID.randomUUID());
                var roles=new RoleAssignments(Map.of("witness",List.of(role)));
                var scopes=new ScopeSet(world,Set.of(scope,
                    new ScopeRef(ScopeKind.PLAYER,role.value())));
                var reference=new DefinitionRef(definition.id(),definition.contentRevision(),
                    BlobContent.hash(new ModelCodec<>(EventDefinition.class).encode(definition)));
                var execution=new ExecutionPlan(new CandidateId(UUID.randomUUID()),
                    reference,List.of(intent),roles,scopes,active.preconditions());
                var request=new AdmissionRequest(new DecisionId(UUID.randomUUID()),
                    active.preconditions(),active.claims(),roles,execution);
                var validator=new AdmissionService.HardValidator(){
                    public ProviderMode mode(){return ProviderMode.TEST_ONLY;}
                    public AdmissionService.Assessment assess(AdmissionRequest proposed,Tick now){
                        boolean valid=proposed.equals(request) && !store.readOnlySafe()
                            && active.intent().typedArgs().equals(
                                definition.steps().getFirst().args())
                            && binding.revalidate(active,captured.blobs());
                        return new AdmissionService.Assessment(
                            new ValidationResult(valid,valid?List.of():
                                List.of(fr.proutlost.director.api.error.ReasonCode.STALE_CONTEXT),
                                active.preconditions()),active.claims(),
                            new ResourceArbiter.Rank(false,false,false,Priority.AMBIENT,0,1.0,42),
                            Map.of(scope,active.budgetEstimate()));
                    }
                };
                admission=new AdmissionService(()->goatCatalog,validator,arbiter,
                    ()->new Tick(tick),new DurationTicks(1200),4,ProviderMode.TEST_ONLY);
                admittedDecision=request.proposalId();
                if(admission.admit(request).status()!=AdmissionStatus.DEFERRED)
                    throw new IllegalStateException("J03 goat manual admission not queued");
                var decision=admission.resolveWindow(
                    Map.of(key,slot.get().generation()),Map.of(scope,CAP))
                    .get(admittedDecision);
                if(decision==null||decision.status()!=AdmissionStatus.ACCEPTED)
                    throw new IllegalStateException("J03 goat manual admission rejected "+decision);
                reservation=decision.token().orElseThrow();
                System.out.println("DIRECTOR_NATIVE_J02_J03_GOAT_MANUAL_ADMISSION_ACCEPTED "+
                    definition.id().value()+" hash="+reference.hash().value()+
                    " mode=TEST_ONLY automatic=false");
            }else {
                var candidate=new ResourceArbiter.Candidate(new ReservationId(UUID.randomUUID()),
                    instance,new ResourceArbiter.Rank(false,false,false,Priority.AMBIENT,0,1.0,42),
                    new Tick(tick+1200),active.claims(),Map.of(scope,active.budgetEstimate()));
                var granted=arbiter.arbitrate(List.of(candidate),new Tick(tick),
                    Map.of(key,slot.get().generation()),Map.of(scope,CAP));
                if(granted.size()!=1||granted.getFirst().accepted().isEmpty())
                    throw new IllegalStateException("goat cleanup resource not admitted");
                reservation=granted.getFirst().accepted().orElseThrow();
            }
            var slice=new PreparedSlice(new SliceId(UUID.randomUUID()),
                intent.operationToken(),footprint,active.preconditions(),List.of(intent),
                active.budgetEstimate());
            live=new LiveValidationSnapshot(active.preconditions(),
                footprint.loadedChunks(),HealthMode.NORMAL,new Tick(tick),
                reservation.generation());
            var prepare=new PrepareRecord(new TransactionId(UUID.randomUUID()),
                intent.operationToken(),reservation.generation(),active.preconditions(),
                reservation.expiryTick(),slice,reservation,captured.validation(),
                List.of(),Set.of(),List.of(),active.durableBlobs());
            tx=prepare.transactionId();
            port.hydrate(active,captured.blobs());
            var batch=new DurableBatch(tx,store.session().storeGeneration(),
                prepare.readSet().revisions(),List.of(new DurablePrepare(prepare)),
                captured.blobs().contents(),Optional.empty(),
                CommitPermit.Authority.prepareHash(prepare));
            runner.submit(batch,active.expectedPost(),live,actual->
                ResultTransaction.batch(new TransactionId(UUID.randomUUID()),
                    store.session().storeGeneration(),Map.of(),
                    List.of(new DurableResult(new ResultRecord(tx,
                        intent.operationToken(),actual,Set.of(),List.of(),List.of())),
                        new DurableAggregate(new EntityOwnershipAggregate(slot.get()))),
                    List.of()));
        }
        boolean committed(){
            var state=runner.poll(tx,live);
            if(state.stage()==EventRunner.Stage.REJECTED ||
               state.stage()==EventRunner.Stage.RECOVERY_REQUIRED)
                throw new IllegalStateException("goat J04/J05 "+state.stage()+" "+state.failure());
            return state.stage()==EventRunner.Stage.COMMITTED;
        }
        void advance(GameTestHelper helper){
            switch(phase){
                case 0 -> {
                    if(!opening.isDone())return;
                    helper.assertTrue(!opening.isCompletedExceptionally(),"goat J04 open");
                    store=opening.getNow(null);
                    helper.assertTrue(store!=null&&!store.readOnlySafe(),"goat WAL writer");
                    initialOwner=store.append(ResultTransaction.batch(
                        new TransactionId(UUID.randomUUID()),
                        store.session().storeGeneration(),Map.of(),
                        List.of(new DurableAggregate(new EntityOwnershipAggregate(slot.get()))),
                        List.of())).toCompletableFuture();
                    phase=10;
                }
                case 10 -> {
                    if(!initialOwner.isDone())return;
                    helper.assertTrue(!initialOwner.isCompletedExceptionally() &&
                        initialOwner.getNow(null).ackStatus()==AckStatus.DURABLE,
                        "initial entity ownership not durably seeded");
                    authority=new CommitPermit.Authority(store,4);
                    arbiter=new ResourceArbiter(world,new Epoch(Math.max(
                        1,store.session().bootEpoch().value())),1000,4,
                        new ResourceArbiter.Compatibility(){
                            public boolean share(ResourceClaim a,ResourceClaim b){return false;}
                            public boolean merge(ResourceId i,ResourceClaim a,ResourceClaim b){return false;}
                        });
                    port=new ServerEffectPort(authority,i->{throw new IllegalStateException("captured");},
                        slice->live,Map.of(GoatVisitBinding.VISIT,binding,
                            GoatVisitBinding.DEPART,binding),
                        binding.capabilities(),4,
                        slice->new StabilizationLiveSnapshot(store.session(),
                            live,new OwnershipView(Map.of())),marker->{},(marker,out)->{});
                    runner=new EventRunner(authority,store,port,arbiter,4);
                    var captured=capture(GoatVisitBinding.VISIT,110);
                    helper.assertTrue(level.getEntity(uuid)==null,"goat present before PREPARE");
                    submit(captured,110);
                    helper.assertTrue(level.getEntity(uuid)==null,"goat before WAL fsync");
                    phase=1;
                }
                case 1 -> {
                    if(!committed())return;
                    var liveGoat=level.getEntity(uuid);
                    helper.assertTrue(liveGoat instanceof Goat&&liveGoat.isAlive(),
                        "J04 committed spawn did not produce real goat");
                    helper.assertTrue(slot.get().phase()==EntityPhase.PRESENT,
                        "goat owner not advanced after spawn");
                    helper.assertTrue(arbiter.release(reservation),"goat spawn claim not released");
                    admission.released(admittedDecision,reservation);
                    System.out.println("DIRECTOR_NATIVE_GOAT_VISIT_PHYSICAL_COMMITTED "+uuid);
                    var captured=capture(GoatVisitBinding.DEPART,220);
                    submit(captured,220);
                    helper.assertTrue(level.getEntity(uuid)!=null,"goat removed before inverse PREPARE");
                    phase=2;
                }
                case 2 -> {
                    if(!committed())return;
                    helper.assertTrue(level.getEntity(uuid)==null,
                        "goat native cleanup did not remove the exact UUID");
                    helper.assertTrue(slot.get().phase()==EntityPhase.REMOVED &&
                        slot.get().disposition()==OwnershipDisposition.RELEASED,
                        "goat departed without ownership release");
                    helper.assertTrue(arbiter.release(reservation),"goat departure claim not released");
                    System.out.println("DIRECTOR_NATIVE_GOAT_VISIT_INVERSE_COMMITTED "+uuid);
                    closed=store.closeAsync().toCompletableFuture();
                    phase=3;
                }
                case 3 -> {
                    if(!closed.isDone())return;
                    helper.assertTrue(!closed.isCompletedExceptionally(),"goat J04 close/fsync");
                    reopened=JournalStore.open(root,world,OPTIONS).toCompletableFuture();
                    phase=4;
                }
                case 4 -> {
                    if(!reopened.isDone())return;
                    helper.assertTrue(!reopened.isCompletedExceptionally(),"goat same WAL reopen");
                    store=reopened.getNow(null);
                    var rows=store.view(new StoreQuery(world,Set.of(),new Sequence(0),
                        Optional.empty(),64));
                    helper.assertTrue(rows.status()==ReadStatus.AVAILABLE,
                        "goat J04 read after reopening same WAL");
                    var durable=rows.page().orElseThrow().records();
                    helper.assertTrue(durable.stream().filter(x->x instanceof DurablePrepare).count()==2 &&
                        durable.stream().filter(x->x instanceof DurableResult).count()==2 &&
                        durable.stream().filter(x->x instanceof DurableAggregate).count()==3,
                        "goat native spawn/inverse J04 WAL not complete after reopen");
                    var owner=durable.stream().filter(x->x instanceof DurableAggregate)
                        .map(x->((DurableAggregate)x).state())
                        .filter(x->x instanceof EntityOwnershipAggregate)
                        .map(x->((EntityOwnershipAggregate)x).value()).toList();
                    helper.assertTrue(owner.size()==3 &&
                        owner.getLast().phase()==EntityPhase.REMOVED &&
                        owner.getLast().disposition()==OwnershipDisposition.RELEASED &&
                        owner.stream().allMatch(o->o.entityUuid().equals(uuid)),
                        "goat post-cleanup durable lineage not restored");
                    closed=store.closeAsync().toCompletableFuture();
                    phase=5;
                }
                case 5 -> {
                    if(!closed.isDone())return;
                    helper.assertTrue(!closed.isCompletedExceptionally(),"goat WAL final close");
                    System.out.println("DIRECTOR_NATIVE_GOAT_VISIT_J04_PREPARE_RESULT_FORWARD_INVERSE_REOPEN_PASS");
                    helper.succeed();
                    phase=6;
                }
                default -> {}
            }
        }
    }
    @GameTest(templateNamespace="proutlost_director",template="smoke_empty",timeoutTicks=800)
    public static void nativeGoatVisitAndExactOwnerRemovalWithDurableWal(GameTestHelper helper){
        var probe=new Probe(helper);
        helper.onEachTick(()->probe.advance(helper));
    }
}
