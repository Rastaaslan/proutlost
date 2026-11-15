package fr.proutlost.director.adapter.neoforge.server;

import fr.proutlost.director.adapter.neoforge.server.effects.*;
import fr.proutlost.director.adapter.neoforge.server.lifecycle.DirectorSavedIdentity;
import fr.proutlost.director.adapter.neoforge.server.ownership.ServerOwnershipAttachments;
import fr.proutlost.director.api.codec.ModelCodec;
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
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.*;

/** Two serial REAL FIRE provider / private J04 writer-loss cases. Same-process WAL reopen,
 * exact native POST + forced blobs, then a NEW cleanup-only permit; no old FIRE replay.
 * nativeFixture has no human audience. No WARN/J06, actual hurt, cold-world or G5 claim.
 * Requires nativeWriterLossGameTest=true AND nativeFireProviderGameTest=true.
 * Explicit bounded vanilla fixture and doFireTick=true precede START; no copied/reset world. */
@GameTestHolder("proutlost_director_writer_loss")
@PrefixGameTestTemplate(false)
public final class NativeFireWriterLossGameTests {
    private NativeFireWriterLossGameTests() {}
    private static final StoreOptions OPTIONS=new StoreOptions(1048620,2097152,4194304,16777216,32,0,128);
    private static final ResourceId RECIPE=new ResourceId("proutlost_director:native_writer_loss_fire");
    private static final ResourceId PROFILE=new ResourceId("proutlost_director:native_writer_loss_fire_damage");
    private static final FactType START=new FactType("proutlost_director:native_writer_loss_fire_start");
    private static final FactType END=new FactType("proutlost_director:native_writer_loss_fire_end");
    private static final FactType WAVE=new FactType("proutlost_director:controlled_fire_wave");
    private static final ModelCodec<Footprint> MASK=new ModelCodec<>(Footprint.class);

    @GameTestGenerator public static Collection<TestFunction> writerLossFire(){
        if(!Boolean.getBoolean("proutlost.director.nativeWriterLossGameTest"))return List.of();
        return List.of(new TestFunction("native_writer_loss_fire","proutlost_director_writer_loss.two_serial_fire_result_loss_cases",
            "proutlost_director_writer_loss:smoke_empty",Rotation.NONE,24000,0,true,h->{
                var p=new Probe(h);
                h.succeedWhen(()->{
                    try{p.poll();}catch(RuntimeException failed){p.closeAfterFailure();throw failed;}
                    h.assertTrue(p.done,"native FIRE writer-loss pending phase="+p.phase+" case="+p.index);
                });
            }));
    }
    private static ResourceKey key(BlockPosition p){return new ResourceKey(OwnershipDomain.BLOCK,p.dimension(),p.x()+","+p.y()+","+p.z());}
    private static BlockPos pos(BlockPosition p){return new BlockPos(p.x(),p.y(),p.z());}
    private static JsonValue.Text text(Object value){return new JsonValue.Text(value.toString());}

    private static final class Probe {
        final GameTestHelper h;final MinecraftServer server;final ServerLevel level;final WorldId world;final Path path;
        final ServerOwnershipAttachments attached=new ServerOwnershipAttachments(64);
        final Map<ResourceKey,OwnershipRecord> owners=new HashMap<>(),walOwners=new HashMap<>(),initial=new HashMap<>(),postOwners=new HashMap<>();
        final Map<BlockPosition,Fingerprint> sentries=new HashMap<>();
        NativeWriterLossProbe store;CompletableFuture<NativeWriterLossProbe> opening;
        CompletableFuture<Void> closing;CompletableFuture<CommitAck> ack;
        CompletableFuture<StoreReadResult> reading;CompletableFuture<BlobReadResult> hydrating;
        StoreSession readSession,lostSession;StoreQuery query;Sequence cut;int readPart;
        List<DurableRecord> records=List.of();List<BlockPosition> cells=List.of();Footprint footprint;BlobContent mask;
        ControlledFire args;ControlledFireWaveBinding binding;PreparedEffect original;BlobBundle blobs;
        NativeWriterLossCases.Execution forward,cleanup;
        UUID event;long firstBoot;int phase,index,forwardCalls,cleanupCalls;boolean acquired,done,ruleBefore,ruleCaptured;
        Probe(GameTestHelper h){
            this.h=h;level=h.getLevel();server=level.getServer();
            world=server.overworld().getDataStorage().computeIfAbsent(DirectorSavedIdentity.FACTORY,DirectorSavedIdentity.KEY).worldId();
            path=server.getWorldPath(LevelResource.ROOT).resolve("director-native-writer-loss-fire");
        }
        NativeWriterLossProbe.FailurePoint point(){return NativeWriterLossProbe.FailurePoint.values()[index];}
        void require(boolean ok,String reason){h.assertTrue(ok,reason);}
        Tick now(){return new Tick(server.overworld().getGameTime());}
        Fingerprint hash(BlockPosition p){return BlockTransformBinding.stateBlob(level.getBlockState(pos(p))).reference().hash();}
        void publish(BlockPosition p,OwnershipRecord owner){owners.put(key(p),owner);attached.write(level,pos(p),owner);}
        void append(List<DurableRecord> rows){
            ack=store.append(ResultTransaction.batch(new TransactionId(UUID.randomUUID()),store.session().storeGeneration(),Map.of(),rows,List.of())).toCompletableFuture();
        }
        boolean acked(){
            if(!ack.isDone())return false;
            require(!ack.isCompletedExceptionally()&&ack.getNow(null).ackStatus()==AckStatus.DURABLE&&ack.getNow(null).durableSequence().value()>0,"real J04 metadata ACK");
            return true;
        }
        void read(){
            readSession=store.session();cut=null;readPart=0;
            query=new StoreQuery(world,Set.of(),new Sequence(0),Optional.empty(),128);reading=store.read(query).toCompletableFuture();
        }
        boolean readDone(){
            if(!reading.isDone())return false;
            require(!reading.isCompletedExceptionally()&&store.session().equals(readSession),"private fire read/session failed");
            var response=reading.getNow(null);ReadContracts.validate(response,query,readSession);
            require(response.status()==ReadStatus.AVAILABLE&&!response.page().orElseThrow().hasMore(),"bounded fire WAL page unavailable");
            var page=response.page().orElseThrow();
            if(readPart==0){
                records=page.records();cut=page.lastDurableSequence();var keys=new HashSet<AggregateKey>();
                for(var row:records)if(row instanceof DurableOwnership o)keys.add(new OwnershipAggregate(o.value()).key());
                if(keys.isEmpty()){walOwners.clear();return true;}
                require(keys.size()==2,"exact two private fire owner IDs");readPart=1;
                query=new StoreQuery(world,keys,new Sequence(0),Optional.empty(),128);reading=store.read(query).toCompletableFuture();return false;
            }
            require(page.lastDurableSequence().equals(cut)&&page.aggregates().size()==2,"fire owner projection cut/size differs");walOwners.clear();
            for(var row:page.aggregates()){
                require(row.committedSequence().value()>0&&row.committedSequence().compareTo(cut)<=0&&row.state() instanceof OwnershipAggregate,"actual committed fire owner row");
                var owner=((OwnershipAggregate)row.state()).value();walOwners.put(owner.resourceKey(),owner);
            }
            return true;
        }
        BlockPosition point(BlockPos p){return new BlockPosition(new DimensionId(level.dimension().location().toString()),p.getX(),p.getY(),p.getZ());}
        void fixture(){
            String encoded=System.getProperty("proutlost.director.nativeWriterLossFireFixture","");
            require(encoded.matches("-?\\d+,-?\\d+,-?\\d+"),"set nativeWriterLossFireFixture=x,y,z in already loaded empty LAB space");
            var xyz=encoded.split(",");var origin=new BlockPos(Integer.parseInt(xyz[0]),Integer.parseInt(xyz[1]),Integer.parseInt(xyz[2]));
            require(origin.getY()>level.getMinBuildHeight()+1&&origin.getY()<level.getMaxBuildHeight()-3,"fire fixture height");
            var box=new AABB(origin.getX()-2,origin.getY()-1,origin.getZ()-2,origin.getX()+4,origin.getY()+3,origin.getZ()+3);
            require(!box.intersects(h.getBounds().inflate(3,20,3))&&level.getEntities(null,box).isEmpty(),"fixture intersects clearing/entities");
            var chunks=new HashSet<ChunkRef>();var dimension=new DimensionId(level.dimension().location().toString());
            for(int x=-2;x<=3;x++)for(int z=-2;z<=2;z++)for(int y=-1;y<=2;y++){
                var p=origin.offset(x,y,z);var chunk=level.getChunkSource().getChunkNow(p.getX()>>4,p.getZ()>>4);
                require(chunk!=null,"fixture must already be loaded");chunks.add(new ChunkRef(dimension,p.getX()>>4,p.getZ()>>4));
                require(attached.read(level,p).isEmpty(),"fresh fire fixture has existing provenance");
                require(level.getBlockState(p).isAir()||(y==-1&&level.getBlockState(p).is(Blocks.STONE)),"never overwrite existing fixture/content");
                for(var be:chunk.getBlockEntities().values())if(be instanceof net.minecraft.world.level.block.entity.StructureBlockEntity structure)
                    require(!box.intersects(StructureUtils.getStructureBounds(structure).inflate(3,20,3)),"fixture intersects another clearing");
            }
            require(chunks.size()==1,"fire fixture and guards must fit one loaded chunk");
            int flags=Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE|Block.UPDATE_SUPPRESS_DROPS;
            for(int x=-1;x<=2;x++)for(int z=-1;z<=1;z++){
                level.setBlock(origin.offset(x,-1,z),Blocks.STONE.defaultBlockState(),flags);
                if(x==-1||x==2||z==-1||z==1)level.setBlock(origin.offset(x,0,z),Blocks.STONE.defaultBlockState(),flags);
            }
            cells=List.of(point(origin),point(origin.east()));
            for(var p:cells)level.setBlock(pos(p).above(),Blocks.OAK_PLANKS.defaultBlockState(),flags);
            footprint=new Footprint(dimension,chunks,Set.copyOf(cells),List.of(new Aabb(dimension,origin.getX()-2,origin.getY()-1,origin.getZ()-2,origin.getX()+4,origin.getY()+3,origin.getZ()+3)));
            mask=BlobContent.capture(ControlledFireWaveBinding.MASK_CODEC,1,MASK.encode(footprint));
            for(int x=-2;x<=3;x++)for(int z=-2;z<=2;z++)for(int y=-1;y<=2;y++){
                var p=point(origin.offset(x,y,z));if(!cells.contains(p))sentries.put(p,hash(p));
            }
        }
        void assertSentries(){
            for(var entry:sentries.entrySet())require(hash(entry.getKey()).equals(entry.getValue())
                &&!(level.getBlockState(pos(entry.getKey())).getBlock() instanceof BaseFireBlock),"outside-mask fire/content changed");
        }
        boolean nativeSafe(){
            for(var c:footprint.loadedChunks())if(level.getChunkSource().getChunkNow(c.x(),c.z())==null)return false;
            var b=footprint.bounds().getFirst();
            return level.getEntities(null,new AABB(b.minX(),b.minY(),b.minZ(),b.maxXExclusive(),b.maxYExclusive(),b.maxZExclusive())).isEmpty();
        }
        PlanningSnapshot snapshot(){
            var observed=new HashMap<BlockPosition,TerrainCellSnapshot>();
            for(var p:cells){var owner=owners.get(key(p));observed.put(p,new TerrainCellSnapshot(p,hash(p),BlockTransformBinding.STATE_CODEC,
                Set.of(),Set.of(),false,true,owner.epoch(),owner.playerOverrideEpoch()));}
            var terrain=new TerrainSnapshot(footprint,observed,List.of(),Map.of(),new Revision(1));
            var context=new ObservationContext(Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),List.of(terrain),Map.of());
            return new PlanningSnapshot(new SnapshotBatch(new RequestId(UUID.randomUUID()),new ScopeSet(world,Set.of()),now(),Map.of(),context,
                Set.of(),footprint.loadedChunks(),HealthMode.NORMAL),new OwnershipView(owners),new BudgetView(NativeWriterLossCases.CAP,BudgetMath.zero(),Map.of()),new BlobBundle(List.of(mask)));
        }
        void bind(){
            var originalOwners=new HashMap<BlockPosition,OwnershipRecord>();for(var p:cells)originalOwners.put(p,initial.get(key(p)));
            var profile=new DamageProfile(PROFILE,Set.of(new ResourceId("minecraft:in_fire")),1,6,new DurationTicks(20),List.of(),
                Set.of(fr.proutlost.director.api.error.ReasonCode.CAPABILITY_MISSING),ControlledFireWaveBinding.HIT_CODEC);
            binding=ControlledFireWaveBinding.nativeFixture(server,world,new EventInstanceId(event),RECIPE,mask,Map.of(cells.get(0),4000L,cells.get(1),0L),
                DirectorControlledFireBlocks.CONTROLLED_FIRE::get,()->new OwnershipView(owners),this::publish,this::now,profile,this::nativeSafe,1.0,originalOwners);
        }
        NativeWriterLossCases.Execution execution(boolean cleaning){
            final var real=binding;
            var counted=new ServerEffectPort.Binding(){
                public ProviderMode mode(){return real.mode();}
                public Set<PrimitiveId> supportedPrimitives(){return real.supportedPrimitives();}
                public Set<CapabilityId> capabilities(){return real.capabilities();}
                public EffectPreparation capture(EffectIntent i,PlanningSnapshot s){return real.capture(i,s);}
                public boolean revalidate(PreparedEffect p,BlobBundle b){return real.revalidate(p,b);}
                public SliceResult apply(PreparedEffect p,BlobBundle b){if(cleaning)cleanupCalls++;else forwardCalls++;return real.apply(p,b);}
                public ReconcileResult reconcile(RecoveryProbe p){return real.reconcile(p);}
            };
            var intent=new EffectIntent(new OperationToken(UUID.randomUUID()),cleaning?ControlledFireWaveBinding.CLEANUP:ControlledFireWaveBinding.FIRE,
                1,args,footprint,new EventInstanceId(event));var captured=real.capture(intent,snapshot());
            var execution=new NativeWriterLossCases.Execution(server,world,store,counted,captured,()->new OwnershipView(owners),this::now,result->{
                require(result.outcome()==Outcome.SUCCEEDED&&result.observedHashes().equals(captured.prepared().orElseThrow().expectedPost()),"actual native fire readback");
                var rows=real.resultRecords();
                for(var row:rows)if(row instanceof DurableFact fact&&fact.value().factType().equals(WAVE))
                    require(((JsonValue.ObjectValue)fact.value().payload().get("damage")).values().isEmpty(),"native fixture may not manufacture human hurt");
                return rows;
            },cleaning?Optional.empty():Optional.of(point()),real.noninvertibleEffects());
            if(!cleaning){original=captured.prepared().orElseThrow();blobs=captured.blobs();}
            return execution;
        }
        void startCase(){
            event=UUID.randomUUID();firstBoot=store.session().bootEpoch().value();forwardCalls=0;cleanupCalls=0;initial.clear();postOwners.clear();
            owners.clear();owners.putAll(walOwners);assertSentries();var token=new OwnerToken(UUID.randomUUID());var rows=new ArrayList<DurableRecord>();
            for(var p:cells){
                var old=walOwners.get(key(p));require(level.getBlockState(pos(p)).isAir()&&attached.read(level,pos(p)).equals(Optional.ofNullable(old))
                    &&(old==null||old.disposition()==OwnershipDisposition.RELEASED),"reuse only actual AIR/released original fixture");
                var owner=new OwnershipRecord(new AggregateHeader(1,old==null?UUID.randomUUID():old.header().id(),old==null?new Revision(1):old.header().revision().plus(1),
                    old==null?new Sequence(0):old.header().lastSequence(),now()),key(p),Set.of(token),old==null?new Epoch(1):old.epoch().plus(1),
                    OwnershipDisposition.DIRECTOR_ACTIVE,new MutationId(UUID.randomUUID()),hash(p),old==null?new Epoch(0):old.playerOverrideEpoch(),Optional.empty());
                owners.put(key(p),owner);initial.put(key(p),owner);rows.add(new DurableOwnership(owner));
            }
            args=new ControlledFire(RECIPE,mask.reference(),new DurationTicks(4000),PROFILE);
            rows.add(new DurableFact(new DirectorFact(new FactId(UUID.randomUUID()),START,List.of(new ScopeRef(ScopeKind.WORLD,world.value())),
                Optional.of(new EventInstanceId(event)),EvidenceStatus.CONFIRMED,Map.of("case",text(index),"point",text(point()),"boot",text(firstBoot),"nativeFixture",new JsonValue.Bool(true)),true,Optional.empty())));
            append(rows);phase=2;
        }
        List<PrepareRecord> eventPrepares(){return records.stream().filter(DurablePrepare.class::isInstance).map(DurablePrepare.class::cast).map(DurablePrepare::value)
            .filter(p->p.reservation().instanceId().value().equals(event)).toList();}
        List<ResultRecord> originalResults(){return records.stream().filter(DurableResult.class::isInstance).map(DurableResult.class::cast).map(DurableResult::value)
            .filter(r->r.operationToken().equals(original.intent().operationToken())).toList();}
        void provePost(){
            require(forwardCalls==1&&cleanupCalls==0,"no old fire replay before cleanup");
            for(var p:cells)require(hash(p).equals(original.expectedPost().get(key(p)))&&attached.read(level,pos(p)).equals(Optional.of(postOwners.get(key(p)))),"native exact saved fire POST/owner changed");
            require(cells.stream().anyMatch(p->level.getBlockState(pos(p)).is(DirectorControlledFireBlocks.CONTROLLED_FIRE.get())),"at least one real controlled FIRE write required");
            assertSentries();var evidence=new ArrayList<RecoveryEvidence>();
            for(var p:cells){var owner=postOwners.get(key(p));evidence.add(new RecoveryEvidence(key(p),EvidenceStatus.CONFIRMED,Optional.of(hash(p)),Optional.of(owner.ownerTokens().iterator().next()),owner.epoch(),owner.playerOverrideEpoch(),false));}
            require(binding.reconcile(new RecoveryProbe(original,evidence,blobs)).status()==ReconcileStatus.SAME_POST,"forced original fire POST/lineage does not reconcile");
        }
        void beginCleanup(){
            owners.clear();owners.putAll(walOwners);
            for(var p:cells)require(attached.read(level,pos(p)).equals(Optional.of(owners.get(key(p)))),"durable owner differs before cleanup");
            binding.recoverCleanupBudget(forward.prepare(),blobs);
            boolean denied=false;try{binding.capture(new EffectIntent(new OperationToken(UUID.randomUUID()),ControlledFireWaveBinding.FIRE,1,args,footprint,new EventInstanceId(event)),snapshot());}
            catch(IllegalArgumentException cleanupOnly){denied=true;}
            require(denied,"recovery budget must permanently deny another FIRE/damage authorization");
            cleanup=execution(true);cleanup.submit();phase=9;
        }
        void save(String reason){
            for(var p:cells)require(attached.read(level,pos(p)).equals(Optional.of(owners.get(key(p)))),"saved native fire owner mismatch");
            boolean saved=server.saveEverything(false,true,true);
            System.out.println("DIRECTOR_NATIVE_FIRE_WRITER_LOSS_WORLD_SAVE reason="+reason+" event="+event+" return="+saved+" world="+server.getWorldPath(LevelResource.ROOT)+" cold_minecraft=false");
        }
        void poll(){
            require(server.isSameThread(),"native fire actor");switch(phase){
                case 0->{
                    if(!acquired){if(!NativeWriterLossCases.tryAcquire(server,this))return;acquired=true;
                        require(Boolean.getBoolean("proutlost.director.nativeFireProviderGameTest"),"explicit nativeFireProviderGameTest=true required for nativeFixture API");
                        ruleBefore=level.getGameRules().getBoolean(GameRules.RULE_DOFIRETICK);ruleCaptured=true;level.getGameRules().getRule(GameRules.RULE_DOFIRETICK).set(true,server);}
                    if(opening==null){opening=NativeWriterLossProbe.openPrivate(path,world,OPTIONS).toCompletableFuture();return;}
                    if(!opening.isDone())return;require(!opening.isCompletedExceptionally(),"private fire open failed");store=opening.getNow(null);require(!store.readOnlySafe(),"private writer starts live");read();phase=1;
                }
                case 1->{if(!readDone())return;require(records.isEmpty(),"never overwrite existing fire writer-loss evidence");fixture();startCase();}
                case 2->{if(!acked())return;for(var p:cells)publish(p,owners.get(key(p)));bind();forward=execution(false);lostSession=store.session();forward.submit();phase=3;}
                case 3->{
                    var v=forward.poll();require(v.stage()!=EventRunner.Stage.REJECTED,"fire rejected before intended IO fault");
                    if(v.stage()!=EventRunner.Stage.RECOVERY_REQUIRED)return;
                    require(store.injection().isPresent()&&store.readOnlySafe()&&store.authoritySessionRevoked()&&!store.session().equals(lostSession),"actual fire writer failure must revoke authority");
                    require(forward.actual().isPresent()&&forward.actual().orElseThrow().outcome()==Outcome.SUCCEEDED,"IO loss must follow actual physical fire application");
                    for(var p:cells)postOwners.put(key(p),owners.get(key(p)));provePost();save("FIRE_POST_AFTER_WRITER_LOSS");
                    binding.close();binding=null;closing=store.closeAsync().toCompletableFuture();phase=4;
                }
                case 4->{
                    if(!closing.isDone())return;require(!closing.isCompletedExceptionally(),"failed fire writer close");
                    opening=NativeWriterLossProbe.openPrivate(path,world,OPTIONS).toCompletableFuture();phase=5;
                }
                case 5->{
                    if(!opening.isDone())return;require(!opening.isCompletedExceptionally(),"same fire WAL reopen");store=opening.getNow(null);
                    require(!store.readOnlySafe()&&store.session().bootEpoch().value()>firstBoot&&!store.session().equals(lostSession),"new healthy fire boot/session required");read();phase=6;
                }
                case 6->{
                    if(!readDone())return;var ps=eventPrepares();require(ps.size()==1&&ps.getFirst().equals(forward.prepare()),"original FIRE PREPARE exact/unique");
                    require(originalResults().size()==(point()==NativeWriterLossProbe.FailurePoint.BEFORE_RESULT_WAL_WRITE?0:1),"absent vs forced RESULT boundary");
                    hydrating=store.readBlobs(new BlobReadRequest(world,store.session().storeGeneration(),DurableBlobReferences.collect(forward.prepare()))).toCompletableFuture();phase=7;
                }
                case 7->{
                    if(!hydrating.isDone())return;require(!hydrating.isCompletedExceptionally(),"forced fire blob hydration");var loaded=hydrating.getNow(null);
                    require(loaded.status()==AckStatus.DURABLE&&loaded.contents().stream().map(BlobContent::reference).collect(java.util.stream.Collectors.toSet()).equals(DurableBlobReferences.collect(forward.prepare())),"exact original PREPARE blobs");
                    blobs=new BlobBundle(loaded.contents());bind();provePost();
                    if(originalResults().isEmpty()){
                        for(var p:cells)require(walOwners.get(key(p)).equals(initial.get(key(p))),"unwritten RESULT cannot publish POST owners");
                        var observed=new HashMap<ResourceKey,Fingerprint>();for(var p:cells)observed.put(key(p),hash(p));
                        var rows=new ArrayList<DurableRecord>();rows.add(new DurableResult(new ResultRecord(forward.prepare().transactionId(),original.intent().operationToken(),
                            new SliceResult(Outcome.RECONCILED,Set.of(original.intent().operationToken()),observed,0,Set.of(),Set.of(),List.of()),Set.of(),List.of(),List.of())));
                        for(var p:cells)rows.add(new DurableOwnership(postOwners.get(key(p))));append(rows);phase=8;
                    }else{
                        var r=originalResults().getFirst();require(r.prepareTransactionId().equals(forward.prepare().transactionId())&&r.result().equals(forward.actual().orElseThrow()),"retain original forced RESULT verbatim");
                        for(var p:cells)require(walOwners.get(key(p)).equals(postOwners.get(key(p))),"forced RESULT must retain exact POST owners");
                        beginCleanup();
                    }
                }
                case 8->{if(!acked())return;read();phase=15;}
                case 15->{if(!readDone())return;var r=originalResults();require(r.size()==1&&r.getFirst().result().outcome()==Outcome.RECONCILED,"explicit observed-POST result required");beginCleanup();}
                case 9->{
                    var v=cleanup.poll();require(v.stage()!=EventRunner.Stage.REJECTED&&v.stage()!=EventRunner.Stage.RECOVERY_REQUIRED,"new cleanup requires actual durable result "+v.failure());
                    if(v.stage()!=EventRunner.Stage.COMMITTED)return;binding.committed(cleanup.prepare().operationToken());cleanup.releaseCommitted();
                    require(forwardCalls==1&&cleanupCalls==1,"only original fire + one NEW cleanup application");
                    for(var p:cells)require(level.getBlockState(pos(p)).isAir()&&owners.get(key(p)).disposition()==OwnershipDisposition.RELEASED,"actual cleanup must yield AIR/RELEASED");
                    assertSentries();read();phase=10;
                }
                case 10->{
                    if(!readDone())return;var ps=eventPrepares();require(ps.size()==2&&ps.stream().filter(p->p.slice().operations().getFirst().primitive().equals(ControlledFireWaveBinding.FIRE)).count()==1
                        &&ps.stream().filter(p->p.slice().operations().getFirst().primitive().equals(ControlledFireWaveBinding.CLEANUP)).count()==1,"no old FIRE/damage replay PREPARE");
                    var rs=records.stream().filter(DurableResult.class::isInstance).map(DurableResult.class::cast).map(DurableResult::value).filter(r->ps.stream().anyMatch(p->p.transactionId().equals(r.prepareTransactionId()))).toList();
                    require(rs.size()==2&&rs.stream().anyMatch(r->r.operationToken().equals(cleanup.prepare().operationToken())&&r.result().outcome()==Outcome.SUCCEEDED&&r.result().observedHashes().equals(cleanup.plan().expectedPost())),"only exact original result + new successful cleanup RESULT");
                    var waves=records.stream().filter(DurableFact.class::isInstance).map(DurableFact.class::cast).map(DurableFact::value).filter(f->f.factType().equals(WAVE)&&f.eventId().orElseThrow().value().equals(event)).toList();
                    require(waves.size()==(point()==NativeWriterLossProbe.FailurePoint.BEFORE_RESULT_WAL_WRITE?1:2),"never fabricate the missing forward WAVE fact");
                    var cursors=waves.stream().map(f->Long.parseLong(((JsonValue.Text)f.payload().get("burn_cursor")).value())).collect(java.util.stream.Collectors.toSet());
                    require(cursors.equals(point()==NativeWriterLossProbe.FailurePoint.BEFORE_RESULT_WAL_WRITE?Set.of(2L):Set.of(1L,2L)),"new cleanup cursor preserves forced old budget without forward replay");
                    for(var p:cells)require(walOwners.get(key(p)).disposition()==OwnershipDisposition.RELEASED&&attached.read(level,pos(p)).equals(Optional.of(walOwners.get(key(p)))),"real durable released owner");
                    append(List.of(new DurableFact(new DirectorFact(new FactId(UUID.randomUUID()),END,List.of(new ScopeRef(ScopeKind.WORLD,world.value())),Optional.of(new EventInstanceId(event)),
                        EvidenceStatus.CONFIRMED,Map.of("case",text(index),"point",text(point()),"forward_calls",text(forwardCalls),"cleanup_calls",text(cleanupCalls),"full_disaster",new JsonValue.Bool(false)),true,Optional.empty()))));phase=11;
                }
                case 11->{if(!acked())return;save("FIRE_AIR_RELEASED");binding.close();binding=null;
                    System.out.println("DIRECTOR_NATIVE_FIRE_WRITER_LOSS_CASE_PASS case="+index+" point="+point()+" event="+event+" forward_calls=1 cleanup_calls=1 wal_reopen=true cold_minecraft=false client=false g5=false");
                    if(++index<NativeWriterLossProbe.FailurePoint.values().length){read();phase=12;return;}closing=store.closeAsync().toCompletableFuture();phase=13;}
                case 12->{if(!readDone())return;startCase();}
                case 13->{if(!closing.isDone())return;require(!closing.isCompletedExceptionally(),"final fire WAL close");
                    level.getGameRules().getRule(GameRules.RULE_DOFIRETICK).set(ruleBefore,server);ruleCaptured=false;
                    NativeWriterLossCases.release(server,this);acquired=false;done=true;phase=99;
                    System.out.println("DIRECTOR_NATIVE_FIRE_WRITER_LOSS_TWO_SERIAL_REAL_IO_CASES_PASS full_disaster=false cold_minecraft=false client=false g5=false");}
                case 99->{}
                default->throw new IllegalStateException("fire writer-loss fixture phase");
            }
        }
        void closeAfterFailure(){
            System.out.println("DIRECTOR_NATIVE_FIRE_WRITER_LOSS_FAILED phase="+phase+" case="+index+" physical_evidence_retained=true");
            if(binding!=null){binding.close();binding=null;}if(store!=null)store.closeAsync();
            if(ruleCaptured){level.getGameRules().getRule(GameRules.RULE_DOFIRETICK).set(ruleBefore,server);ruleCaptured=false;}
            if(acquired){NativeWriterLossCases.release(server,this);acquired=false;}
        }
    }
}
