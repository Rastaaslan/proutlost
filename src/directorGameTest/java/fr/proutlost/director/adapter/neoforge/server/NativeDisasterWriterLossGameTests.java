package fr.proutlost.director.adapter.neoforge.server;

import fr.proutlost.director.adapter.neoforge.server.effects.*;
import fr.proutlost.director.adapter.neoforge.server.lifecycle.DirectorSavedIdentity;
import fr.proutlost.director.adapter.neoforge.server.ownership.ServerOwnershipAttachments;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.permission.CommitPermit;
import fr.proutlost.director.persistence.*;
import fr.proutlost.director.runtime.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.*;

/**
 * Four SERIAL technical cases on real native terrain / EventRunner / private J04 WAL:
 * COLLAPSE and LANDSLIDE geometry, each with no RESULT write or forced RESULT / lost ACK.
 * Reopens the SAME private WAL, proves physical POST and owner lineage, then applies only
 * a newly admitted inverse. No connected client, fabricated receipt, WARN, natural eligibility
 * or damage claim. This is a WAL reopen in the same Minecraft process, NOT a cold-world test.
 * Explicit bounded fixture setup precedes each START. No world copies/resets or process kills.
 */
@GameTestHolder("proutlost_director_writer_loss")
@PrefixGameTestTemplate(false)
public final class NativeDisasterWriterLossGameTests {
    private NativeDisasterWriterLossGameTests() {}
    private static final StoreOptions OPTIONS=new StoreOptions(1048620,2097152,4194304,16777216,32,0,128);
    private static final PrimitiveId ID=new PrimitiveId("proutlost:landslide");
    private static final ResourceId RECIPE=new ResourceId("proutlost_director:native_writer_loss_terrain");
    private static final CapabilityId TERRAIN=new CapabilityId("mutation.terrain_v1");
    private static final FactType START=new FactType("proutlost_director:native_writer_loss_start");
    private static final FactType END=new FactType("proutlost_director:native_writer_loss_end");
    private record Case(DisasterRunner.Family family,NativeWriterLossProbe.FailurePoint point) {}
    private static final List<Case> CASES=List.of(
        new Case(DisasterRunner.Family.COLLAPSE,NativeWriterLossProbe.FailurePoint.BEFORE_RESULT_WAL_WRITE),
        new Case(DisasterRunner.Family.COLLAPSE,NativeWriterLossProbe.FailurePoint.AFTER_RESULT_FORCE_BEFORE_ACK),
        new Case(DisasterRunner.Family.LANDSLIDE,NativeWriterLossProbe.FailurePoint.BEFORE_RESULT_WAL_WRITE),
        new Case(DisasterRunner.Family.LANDSLIDE,NativeWriterLossProbe.FailurePoint.AFTER_RESULT_FORCE_BEFORE_ACK));

    @GameTestGenerator public static Collection<TestFunction> writerLossTests() {
        if(!Boolean.getBoolean("proutlost.director.nativeWriterLossGameTest"))return List.of();
        return List.of(new TestFunction("native_writer_loss_terrain",
            "proutlost_director_writer_loss.four_serial_native_terrain_result_loss_cases",
            "proutlost_director_writer_loss:smoke_empty",Rotation.NONE,24000,0,true,h->{
                var p=new Probe(h);
                h.succeedWhen(()->{
                    try{p.poll();}
                    catch(RuntimeException failure){p.closeAfterFailure();throw failure;}
                    h.assertTrue(p.done,"native writer-loss pending phase="+p.phase+" case="+p.index);
                });
            }));
    }
    private static ResourceKey key(BlockPosition p){
        return new ResourceKey(OwnershipDomain.BLOCK,p.dimension(),p.x()+","+p.y()+","+p.z());
    }
    private static BlockPos pos(BlockPosition p){return new BlockPos(p.x(),p.y(),p.z());}
    private static JsonValue.Text text(Object x){return new JsonValue.Text(x.toString());}

    private static final class Probe {
        final GameTestHelper h;final MinecraftServer server;final ServerLevel level;final WorldId world;
        final Path path;final ServerOwnershipAttachments attached=new ServerOwnershipAttachments(64);
        final Map<ResourceKey,OwnershipRecord> owners=new HashMap<>(),walOwners=new HashMap<>(),initial=new HashMap<>(),postOwners=new HashMap<>();
        NativeWriterLossProbe store;CompletableFuture<NativeWriterLossProbe> opening;
        CompletableFuture<CommitAck> ack;CompletableFuture<Void> closing;
        CompletableFuture<StoreReadResult> reading;CompletableFuture<BlobReadResult> hydrating;
        StoreSession readSession,lostSession;StoreQuery query;Sequence cut;int readPhase;
        List<DurableRecord> records=List.of();List<BlockPosition> cells=List.of();Set<BlockPosition> sources=Set.of();
        NativeWriterLossCases.Execution forward,cleanup;BlockTransformBinding base;
        LandslideBinding forwardBinding;PreparedEffect original;BlobBundle blobs;
        Case selected;UUID event,witness;long firstBoot;int phase,index,forwardCalls,inverseCalls;
        boolean acquired,done;

        Probe(GameTestHelper h){
            this.h=h;level=h.getLevel();server=level.getServer();
            world=server.overworld().getDataStorage().computeIfAbsent(DirectorSavedIdentity.FACTORY,DirectorSavedIdentity.KEY).worldId();
            path=server.getWorldPath(LevelResource.ROOT).resolve("director-native-writer-loss-terrain");
        }
        void require(boolean ok,String reason){h.assertTrue(ok,reason);}
        Tick now(){return new Tick(server.overworld().getGameTime());}
        Fingerprint hash(BlockPosition p){return BlockTransformBinding.stateBlob(level.getBlockState(pos(p))).reference().hash();}
        void publish(BlockPosition p,OwnershipRecord owner){owners.put(key(p),owner);attached.write(level,pos(p),owner);}
        void append(List<DurableRecord> rows){
            ack=store.append(ResultTransaction.batch(new TransactionId(UUID.randomUUID()),store.session().storeGeneration(),
                Map.of(),rows,List.of())).toCompletableFuture();
        }
        boolean acked(){
            if(!ack.isDone())return false;
            require(!ack.isCompletedExceptionally()&&ack.getNow(null).ackStatus()==AckStatus.DURABLE
                &&ack.getNow(null).durableSequence().value()>0,"real private J04 metadata ACK required");
            return true;
        }
        void read(){
            readSession=store.session();cut=null;readPhase=0;
            query=new StoreQuery(world,Set.of(),new Sequence(0),Optional.empty(),128);
            reading=store.read(query).toCompletableFuture();
        }
        boolean readDone(){
            if(!reading.isDone())return false;
            require(!reading.isCompletedExceptionally()&&store.session().equals(readSession),"private read session changed");
            var result=reading.getNow(null);ReadContracts.validate(result,query,readSession);
            require(result.status()==ReadStatus.AVAILABLE&&result.page().isPresent()
                &&!result.page().orElseThrow().hasMore(),"bounded private fixture chronology unavailable");
            var page=result.page().orElseThrow();
            if(readPhase==0){
                records=page.records();cut=page.lastDurableSequence();var keys=new HashSet<AggregateKey>();
                for(var row:records)if(row instanceof DurableOwnership o)keys.add(new OwnershipAggregate(o.value()).key());
                if(keys.isEmpty()){walOwners.clear();return true;}
                readPhase=1;query=new StoreQuery(world,keys,new Sequence(0),Optional.empty(),128);
                reading=store.read(query).toCompletableFuture();return false;
            }
            require(page.lastDurableSequence().equals(cut),"private chronology/owner cut changed");
            require(page.aggregates().size()==query.aggregateKeys().size(),"exact private ownership projection missing");
            walOwners.clear();
            for(var row:page.aggregates()){
                require(row.committedSequence().value()>0&&row.committedSequence().compareTo(cut)<=0,"owner beyond actual WAL cut");
                require(row.state() instanceof OwnershipAggregate,"unexpected private aggregate");
                var owner=((OwnershipAggregate)row.state()).value();walOwners.put(owner.resourceKey(),owner);
            }
            return true;
        }
        BlockPosition point(BlockPos p){return new BlockPosition(new DimensionId(level.dimension().location().toString()),p.getX(),p.getY(),p.getZ());}
        void fixture(){
            String encoded=System.getProperty("proutlost.director.nativeWriterLossFixture","");
            require(encoded.matches("-?\\d+,-?\\d+,-?\\d+"),"set nativeWriterLossFixture=x,y,z in already loaded empty disposable LAB space");
            var xyz=encoded.split(",");var origin=new BlockPos(Integer.parseInt(xyz[0]),Integer.parseInt(xyz[1]),Integer.parseInt(xyz[2]));
            var bounds=new AABB(origin.getX()-1,origin.getY()-3,origin.getZ()-1,origin.getX()+3,origin.getY()+2,origin.getZ()+3);
            require(!bounds.intersects(h.getBounds().inflate(3,20,3)),"persistent fixture intersects current clearing box");
            for(int cx=(origin.getX()-1)>>4;cx<=(origin.getX()+2)>>4;cx++)
                for(int cz=(origin.getZ()-1)>>4;cz<=(origin.getZ()+2)>>4;cz++){
                    var chunk=level.getChunkSource().getChunkNow(cx,cz);require(chunk!=null,"fixture chunk must already be loaded");
                    for(var be:chunk.getBlockEntities().values())if(be instanceof net.minecraft.world.level.block.entity.StructureBlockEntity structure)
                        require(!bounds.intersects(StructureUtils.getStructureBounds(structure).inflate(3,20,3)),"persistent fixture intersects another clearing box");
                }
            var selectedCells=new ArrayList<BlockPosition>();var selectedSources=new HashSet<BlockPosition>();
            int count=selected.family()==DisasterRunner.Family.COLLAPSE?2:1;
            for(int i=0;i<count;i++){
                var source=origin.east(i);var destination=selected.family()==DisasterRunner.Family.COLLAPSE?source.below(2):source.south().below();
                var a=point(source);var b=point(destination);selectedCells.add(a);selectedCells.add(b);selectedSources.add(a);
                require(level.getBlockState(source).isAir()||level.getBlockState(source).is(Blocks.STONE),"source must be AIR or original STONE");
                require(level.getBlockState(destination).isAir(),"destination must remain AIR before new START");
                require(level.getEntities(null,new AABB(source).minmax(new AABB(destination))).isEmpty(),"fixture occupied");
            }
            cells=List.copyOf(selectedCells);sources=Set.copyOf(selectedSources);
            for(var p:cells){
                var local=attached.read(level,pos(p));
                require(local.isEmpty()||local.orElseThrow().disposition()==OwnershipDisposition.RELEASED,"never overwrite existing active/player provenance");
                require(local.equals(Optional.ofNullable(walOwners.get(key(p)))),"previous private WAL and saved local tombstone differ");
            }
            // Explicit vanilla fixture support before START; never a provider/recovery side effect.
            var support=new HashSet<BlockPos>();
            for(int x=-1;x<=2;x++)for(int z=-1;z<=2;z++)support.add(origin.offset(x,-3,z));
            if(selected.family()==DisasterRunner.Family.LANDSLIDE)support.add(origin.south().below(2));
            for(var p:support){
                require(level.getBlockState(p).isAir()||level.getBlockState(p).is(Blocks.STONE),"fixture support must be AIR/STONE");
                require(attached.read(level,p).isEmpty(),"support has provenance");
            }
            for(var p:support)if(level.getBlockState(p).isAir())
                level.setBlock(p,Blocks.STONE.defaultBlockState(),Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE|Block.UPDATE_SUPPRESS_DROPS);
            for(var p:sources)if(level.getBlockState(pos(p)).isAir())
                level.setBlock(pos(p),Blocks.STONE.defaultBlockState(),Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE|Block.UPDATE_SUPPRESS_DROPS);
        }
        Footprint footprint(){
            var chunks=new HashSet<ChunkRef>();for(var p:cells)chunks.add(new ChunkRef(p.dimension(),p.x()>>4,p.z()>>4));
            return new Footprint(cells.getFirst().dimension(),chunks,Set.copyOf(cells),List.of());
        }
        PlanningSnapshot snapshot(){
            var fp=footprint();var observed=new HashMap<BlockPosition,TerrainCellSnapshot>();
            for(var p:cells){var owner=owners.get(key(p));observed.put(p,new TerrainCellSnapshot(p,hash(p),BlockTransformBinding.STATE_CODEC,
                Set.of(),Set.of(),false,true,owner.epoch(),owner.playerOverrideEpoch()));}
            var terrain=new TerrainSnapshot(fp,observed,List.of(),Map.of(),new Revision(1));
            var context=new ObservationContext(Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),List.of(terrain),Map.of());
            return new PlanningSnapshot(new SnapshotBatch(new RequestId(UUID.randomUUID()),new ScopeSet(world,Set.of()),now(),Map.of(),context,
                Set.of(),fp.loadedChunks(),HealthMode.NORMAL),new OwnershipView(owners),new BudgetView(NativeWriterLossCases.CAP,BudgetMath.zero(),Map.of()),new BlobBundle(List.of()));
        }
        LandslideBinding binding(boolean inverse){
            var replacements=inverse?Map.of(new ResourceId("minecraft:air"),new ResourceId("minecraft:stone"),new ResourceId("minecraft:cobblestone"),new ResourceId("minecraft:air")):
                Map.of(new ResourceId("minecraft:stone"),new ResourceId("minecraft:air"),new ResourceId("minecraft:air"),new ResourceId("minecraft:cobblestone"));
            base=new BlockTransformBinding(d->level,()->new OwnershipView(owners),this::publish,this::now,
                Map.of(RECIPE,new BlockTransformBinding.Recipe(replacements,false)),4,ID,Set.of(TERRAIN));
            return new LandslideBinding(base,server,new PlayerId(witness),!inverse);
        }
        ServerEffectPort.Binding counted(LandslideBinding delegate,boolean inverse){
            return new ServerEffectPort.Binding(){
                public ProviderMode mode(){return delegate.mode();}
                public Set<PrimitiveId> supportedPrimitives(){return delegate.supportedPrimitives();}
                public Set<CapabilityId> capabilities(){return delegate.capabilities();}
                public EffectPreparation capture(EffectIntent i,PlanningSnapshot s){return delegate.capture(i,s);}
                public boolean revalidate(PreparedEffect p,BlobBundle b){return delegate.revalidate(p,b);}
                public SliceResult apply(PreparedEffect p,BlobBundle b){if(inverse)inverseCalls++;else forwardCalls++;return delegate.apply(p,b);}
                public ReconcileResult reconcile(RecoveryProbe p){return delegate.reconcile(p);}
            };
        }
        NativeWriterLossCases.Execution execution(boolean inverse){
            var provider=binding(inverse);if(!inverse)forwardBinding=provider;
            var intent=new EffectIntent(new OperationToken(UUID.randomUUID()),ID,1,new Landslide(RECIPE,cells.size(),
                new ResourceId("proutlost:test_debris_damage")),footprint(),new EventInstanceId(event));
            var captured=provider.capture(intent,snapshot());
            var execution=new NativeWriterLossCases.Execution(server,world,store,counted(provider,inverse),captured,
                ()->new OwnershipView(owners),this::now,result->{
                    var rows=new ArrayList<DurableRecord>();for(var p:cells)rows.add(new DurableOwnership(owners.get(key(p))));return rows;
                },inverse?Optional.empty():Optional.of(selected.point()),inverse?Set.of():Set.of(NoninvertibleEffect.DAMAGE));
            if(!inverse){original=captured.prepared().orElseThrow();blobs=captured.blobs();}
            return execution;
        }
        void validatePost(){
            for(var p:cells)require(hash(p).equals(original.expectedPost().get(key(p))),"native physical POST differs");
            require(forwardCalls==1&&inverseCalls==0,"no forward replay or premature cleanup");
        }
        void verifyForwardRows(boolean afterReconciliation){
            var prepares=records.stream().filter(DurablePrepare.class::isInstance).map(DurablePrepare.class::cast).map(DurablePrepare::value)
                .filter(p->p.slice().operations().getFirst().instanceId().value().equals(event)).toList();
            require(prepares.size()==1&&prepares.getFirst().equals(forward.prepare()),"original durable PREPARE must be identical and unique");
            var results=records.stream().filter(DurableResult.class::isInstance).map(DurableResult.class::cast).map(DurableResult::value)
                .filter(r->r.operationToken().equals(original.intent().operationToken())).toList();
            boolean forced=selected.point()==NativeWriterLossProbe.FailurePoint.AFTER_RESULT_FORCE_BEFORE_ACK;
            require(results.size()==(forced||afterReconciliation?1:0),"wrong RESULT existence at exact failure boundary");
            if(!results.isEmpty()){
                var r=results.getFirst();var outcome=forced?Outcome.SUCCEEDED:Outcome.RECONCILED;
                require(r.prepareTransactionId().equals(forward.prepare().transactionId())&&r.result().outcome()==outcome
                    &&r.result().appliedOperationIds().equals(Set.of(original.intent().operationToken()))
                    &&r.result().observedHashes().equals(original.expectedPost())&&r.result().remainingWork()==0,
                    "RESULT must be exact durable success or explicit observed-POST reconciliation");
            }
        }
        void provePost(){
            validatePost();var evidence=new ArrayList<RecoveryEvidence>();
            for(var p:cells){
                var before=initial.get(key(p));var owner=attached.read(level,pos(p)).orElseThrow();
                require(owner.equals(postOwners.get(key(p)))&&owner.header().id().equals(before.header().id())
                    &&owner.header().revision().equals(before.header().revision().plus(1))&&owner.ownerTokens().equals(before.ownerTokens())
                    &&owner.epoch().equals(before.epoch())&&owner.playerOverrideEpoch().equals(before.playerOverrideEpoch())
                    &&owner.expectedPostHash().equals(hash(p)),"actual POST owner lineage changed");
                evidence.add(new RecoveryEvidence(key(p),EvidenceStatus.CONFIRMED,Optional.of(hash(p)),
                    Optional.of(owner.ownerTokens().iterator().next()),owner.epoch(),owner.playerOverrideEpoch(),false));
            }
            binding(false);
            require(base.reconcile(new RecoveryProbe(original,evidence,blobs)).status()==ReconcileStatus.SAME_POST,
                "original sealed snapshots do not prove exact native POST");
        }
        void startCase(){
            selected=CASES.get(index);event=UUID.randomUUID();witness=UUID.randomUUID();firstBoot=store.session().bootEpoch().value();
            require(server.getPlayerList().getPlayer(witness)==null,"fixture witness must not impersonate a client");
            forwardCalls=0;inverseCalls=0;initial.clear();postOwners.clear();fixture();owners.clear();owners.putAll(walOwners);
            var rows=new ArrayList<DurableRecord>();
            for(var p:cells){
                var old=walOwners.get(key(p));var owner=new OwnershipRecord(new AggregateHeader(1,old==null?UUID.randomUUID():old.header().id(),
                    old==null?new Revision(1):old.header().revision().plus(1),old==null?new Sequence(0):old.header().lastSequence(),now()),
                    key(p),Set.of(new OwnerToken(UUID.randomUUID())),old==null?new Epoch(1):old.epoch().plus(1),OwnershipDisposition.DIRECTOR_ACTIVE,
                    new MutationId(UUID.randomUUID()),hash(p),old==null?new Epoch(0):old.playerOverrideEpoch(),Optional.empty());
                owners.put(key(p),owner);initial.put(key(p),owner);rows.add(new DurableOwnership(owner));
            }
            forward=execution(false);
            rows.add(new DurableFact(new DirectorFact(new FactId(UUID.randomUUID()),START,List.of(new ScopeRef(ScopeKind.WORLD,world.value())),
                Optional.of(new EventInstanceId(event)),EvidenceStatus.CONFIRMED,Map.of("case",text(index),"family",text(selected.family()),
                "failure_point",text(selected.point()),"boot",text(firstBoot),"testOnly",new JsonValue.Bool(true)),true,Optional.empty())));
            append(rows);phase=2;
        }
        void beginCleanup(){
            verifyForwardRows(true);owners.clear();owners.putAll(walOwners);
            for(var p:cells)require(attached.read(level,pos(p)).equals(Optional.of(owners.get(key(p)))),"durable POST owner and native attachment differ");
            cleanup=execution(true);cleanup.submit();phase=9;
        }
        void save(String reason){
            for(var p:cells)require(attached.read(level,pos(p)).equals(Optional.of(owners.get(key(p)))),"save owner differs");
            boolean saved=server.saveEverything(false,true,true);
            System.out.println("DIRECTOR_NATIVE_WRITER_LOSS_WORLD_SAVE reason="+reason+" event="+event+" world="+server.getWorldPath(LevelResource.ROOT)
                +" world_id="+world.value()+" boot="+store.session().bootEpoch().value()+" return="+saved+" cold_minecraft_claim=false");
        }
        void poll(){
            require(server.isSameThread(),"native writer-loss server actor");
            switch(phase){
                case 0->{
                    if(!acquired){if(!NativeWriterLossCases.tryAcquire(server,this))return;acquired=true;}
                    if(opening==null){opening=NativeWriterLossProbe.openPrivate(path,world,OPTIONS).toCompletableFuture();return;}
                    if(!opening.isDone())return;require(!opening.isCompletedExceptionally(),"private fixture open failed");
                    store=opening.getNow(null);require(!store.readOnlySafe(),"private store must be healthy before fixture");read();phase=1;
                }
                case 1->{if(!readDone())return;require(records.isEmpty(),"fresh writer-loss suite must not overwrite existing evidence");startCase();}
                case 2->{if(!acked())return;for(var p:cells)publish(p,owners.get(key(p)));lostSession=store.session();forward.submit();phase=3;}
                case 3->{
                    var view=forward.poll();require(view.stage()!=EventRunner.Stage.REJECTED,"PREPARE rejected before intended fault");
                    if(view.stage()!=EventRunner.Stage.RECOVERY_REQUIRED)return;
                    require(store.injection().isPresent()&&store.readOnlySafe()&&store.authoritySessionRevoked()
                        &&!store.session().equals(lostSession),"real IoProbe writer failure must revoke session and require recovery");
                    require(forward.actual().isPresent()&&forward.actual().orElseThrow().observedHashes().equals(original.expectedPost()),"fault must follow actual physical apply");
                    validatePost();for(var p:cells)postOwners.put(key(p),owners.get(key(p)));
                    for(var fact:forwardBinding.observedDamageFacts().values())require(fact instanceof JsonValue.ObjectValue object
                        &&object.values().get("attempted") instanceof JsonValue.Bool attempted&&!attempted.value(),"no native client hurt claim");
                    // Test the real fence; this append must not touch WAL or produce a durable ACK.
                    ack=store.append(ResultTransaction.batch(new TransactionId(UUID.randomUUID()),store.session().storeGeneration(),Map.of(),
                        List.of(new DurableFact(new DirectorFact(new FactId(UUID.randomUUID()),new FactType("proutlost_director:must_not_commit_after_writer_loss"),
                        List.of(new ScopeRef(ScopeKind.WORLD,world.value())),Optional.of(new EventInstanceId(event)),EvidenceStatus.CONFIRMED,Map.of(),true,Optional.empty()))),
                        List.of())).toCompletableFuture();phase=4;
                }
                case 4->{
                    if(!ack.isDone())return;require(!ack.isCompletedExceptionally()&&ack.getNow(null).ackStatus()==AckStatus.UNAVAILABLE,"lost writer accepted another append");
                    save("PHYSICAL_POST_AFTER_WRITER_LOSS");closing=store.closeAsync().toCompletableFuture();phase=5;
                }
                case 5->{
                    if(!closing.isDone())return;require(!closing.isCompletedExceptionally(),"real failed writer close failed");
                    opening=NativeWriterLossProbe.openPrivate(path,world,OPTIONS).toCompletableFuture();phase=6;
                }
                case 6->{
                    if(!opening.isDone())return;require(!opening.isCompletedExceptionally(),"same private WAL reopen failed");store=opening.getNow(null);
                    require(!store.readOnlySafe()&&store.session().bootEpoch().value()>firstBoot&&!store.session().equals(lostSession),"new healthy authority/boot fence required");
                    read();phase=7;
                }
                case 7->{
                    if(!readDone())return;verifyForwardRows(false);
                    require(records.stream().noneMatch(r->r instanceof DurableFact d&&d.value().factType().value().equals("proutlost_director:must_not_commit_after_writer_loss")),"rejected append became durable");
                    hydrating=store.readBlobs(new BlobReadRequest(world,store.session().storeGeneration(),CommitPermit.Authority.references(original.durableBlobs()))).toCompletableFuture();phase=8;
                }
                case 8->{
                    if(!hydrating.isDone())return;require(!hydrating.isCompletedExceptionally(),"original durable blob hydration failed");
                    var result=hydrating.getNow(null);require(result.status()==AckStatus.DURABLE
                        &&result.contents().stream().map(BlobContent::reference).collect(java.util.stream.Collectors.toSet()).equals(CommitPermit.Authority.references(original.durableBlobs())),"exact original forced blobs required");
                    blobs=new BlobBundle(result.contents());provePost();
                    if(selected.point()==NativeWriterLossProbe.FailurePoint.BEFORE_RESULT_WAL_WRITE){
                        for(var p:cells)require(walOwners.get(key(p)).equals(initial.get(key(p))),"unwritten RESULT must leave original WAL owner");
                        var observed=new SliceResult(Outcome.RECONCILED,Set.of(original.intent().operationToken()),original.expectedPost(),0,Set.of(),Set.of(),List.of());
                        var rows=new ArrayList<DurableRecord>();rows.add(new DurableResult(new ResultRecord(forward.prepare().transactionId(),original.intent().operationToken(),observed,Set.of(),List.of(),List.of())));
                        for(var p:cells)rows.add(new DurableOwnership(postOwners.get(key(p))));
                        append(rows);phase=15;
                    }else{for(var p:cells)require(walOwners.get(key(p)).equals(postOwners.get(key(p))),"forced RESULT POST owner lost");beginCleanup();}
                }
                case 15->{if(!acked())return;read();phase=16;}
                case 16->{if(!readDone())return;beginCleanup();}
                case 9->{
                    var view=cleanup.poll();require(view.stage()!=EventRunner.Stage.REJECTED&&view.stage()!=EventRunner.Stage.RECOVERY_REQUIRED,"new inverse admission failed "+view.failure());
                    if(view.stage()!=EventRunner.Stage.COMMITTED)return;cleanup.releaseCommitted();
                    require(forwardCalls==1&&inverseCalls==1,"physical forward must never replay across WAL reopen");
                    for(var p:cells)require(hash(p).equals(original.preconditions().fingerprints().get(key(p))),"inverse did not restore exact original STONE/AIR");
                    read();phase=10;
                }
                case 10->{
                    if(!readDone())return;
                    var ps=records.stream().filter(DurablePrepare.class::isInstance).map(DurablePrepare.class::cast).map(DurablePrepare::value)
                        .filter(p->p.slice().operations().getFirst().instanceId().value().equals(event)).toList();
                    require(ps.size()==2&&ps.stream().filter(p->p.operationToken().equals(original.intent().operationToken())).count()==1,"only original forward and one new inverse PREPARE permitted");
                    var rs=records.stream().filter(DurableResult.class::isInstance).map(DurableResult.class::cast).map(DurableResult::value)
                        .filter(r->ps.stream().anyMatch(p->p.transactionId().equals(r.prepareTransactionId()))).toList();
                    require(rs.size()==2&&rs.stream().anyMatch(r->r.operationToken().equals(cleanup.prepare().operationToken())
                        &&r.result().outcome()==Outcome.SUCCEEDED&&r.result().observedHashes().equals(cleanup.plan().expectedPost())),"real successful inverse RESULT required");
                    var rows=new ArrayList<DurableRecord>();owners.clear();owners.putAll(walOwners);
                    for(var p:cells){var owner=owners.get(key(p));require(attached.read(level,pos(p)).equals(Optional.of(owner)),"inverse owner WAL/attachment mismatch");
                        var header=owner.header();var released=new OwnershipRecord(new AggregateHeader(1,header.id(),header.revision().plus(1),header.lastSequence(),now()),
                            owner.resourceKey(),owner.ownerTokens(),owner.epoch(),OwnershipDisposition.RELEASED,owner.creationMutationId(),hash(p),owner.playerOverrideEpoch(),owner.exceptionContractId());
                        owners.put(key(p),released);rows.add(new DurableOwnership(released));}
                    rows.add(new DurableFact(new DirectorFact(new FactId(UUID.randomUUID()),END,List.of(new ScopeRef(ScopeKind.WORLD,world.value())),
                        Optional.of(new EventInstanceId(event)),EvidenceStatus.CONFIRMED,Map.of("case",text(index),"family",text(selected.family()),
                        "failure_point",text(selected.point()),"forward_calls",text(forwardCalls),"inverse_calls",text(inverseCalls),
                        "cleanup",text("EXACT_NATIVE_PRE"),"damage_replayed",new JsonValue.Bool(false),"full_disaster",new JsonValue.Bool(false)),true,Optional.empty())));
                    append(rows);phase=11;
                }
                case 11->{if(!acked())return;for(var p:cells)publish(p,owners.get(key(p)));save("EXACT_NATIVE_PRE_RELEASED");read();phase=12;}
                case 12->{
                    if(!readDone())return;for(var p:cells)require(walOwners.get(key(p)).disposition()==OwnershipDisposition.RELEASED
                        &&attached.read(level,pos(p)).equals(Optional.of(walOwners.get(key(p)))),"cleanup release must be genuinely durable");
                    System.out.println("DIRECTOR_NATIVE_WRITER_LOSS_TERRAIN_CASE_PASS case="+index+" family="+selected.family()+" mode="+selected.point()+" event="+event
                        +" wal_cut="+cut.value()+" forward_calls="+forwardCalls+" inverse_calls="+inverseCalls+" wal_reopen=true cold_minecraft=false full_disaster=false client=false g5=false");
                    if(++index<CASES.size()){startCase();return;}
                    closing=store.closeAsync().toCompletableFuture();phase=13;
                }
                case 13->{
                    if(!closing.isDone())return;require(!closing.isCompletedExceptionally(),"final private fixture close failed");
                    NativeWriterLossCases.release(server,this);acquired=false;done=true;phase=99;
                    System.out.println("DIRECTOR_NATIVE_WRITER_LOSS_FOUR_SERIAL_TERRAIN_CASES_PASS full_disaster=false cold_minecraft=false client=false g5=false");
                }
                case 99->{}
                default->throw new IllegalStateException("writer-loss fixture phase");
            }
        }
        void closeAfterFailure(){
            System.out.println("DIRECTOR_NATIVE_WRITER_LOSS_TERRAIN_FAILED phase="+phase+" case="+index+" evidence_retained=true");
            if(store!=null)store.closeAsync();
            if(acquired){NativeWriterLossCases.release(server,this);acquired=false;}
        }
    }
}
