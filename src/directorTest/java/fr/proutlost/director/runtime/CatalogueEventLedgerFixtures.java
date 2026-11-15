package fr.proutlost.director.runtime;

import fr.proutlost.director.api.codec.ModelCodec;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.config.TestingConfig;
import java.nio.file.*;
import java.util.*;

/** Pure ledger fixtures: uses the ORIGINAL nine compiled catalog goldens and real config.
 * DTO results here are explicit unit inputs, never claims of physical execution/client receipts. */
public final class CatalogueEventLedgerFixtures {
    private CatalogueEventLedgerFixtures() {}
    public static final WorldId WORLD=new WorldId(new UUID(0x50414c4544474552L,1));
    public static final RegionId REGION=new RegionId(new UUID(0x50414c4544474552L,2));
    public static final RegionId OTHER_REGION=new RegionId(new UUID(0x50414c4544474552L,3));
    public static final PlayerId FIRST=new PlayerId(new UUID(0x50414c4544474552L,4));
    public static final PlayerId SECOND=new PlayerId(new UUID(0x50414c4544474552L,5));
    public static final DimensionId DIM=new DimensionId("minecraft:overworld");
    public static final ResourceKey RESOURCE=new ResourceKey(OwnershipDomain.BLOCK,DIM,"ledger-pure-fixture");
    public static final BudgetVector CAP=new BudgetVector(100,100,100,100,100,10000,1000000,100,1000000,10000);
    public static final ModelCodec<EventDefinition> EVENT=new ModelCodec<>(EventDefinition.class);
    public record Loaded(ConfigView config,CatalogSnapshot catalog) {
        public EventDefinition event(String name){
            return Objects.requireNonNull(catalog.events().get(new DefinitionId("proutlost:"+name)),"original catalog event "+name);
        }
    }
    public record Prepared(PrepareRecord prepare,List<BlobContent> blobs,SliceResult success) {}

    public static Loaded load(Path catalogRoot)throws Exception{
        var config=TestingConfig.load(Files.readAllBytes(catalogRoot.resolve("DEFAULT_FOR_TESTING.json"))).view();
        var definitions=new TreeMap<DefinitionId,EventDefinition>(Comparator.comparing(DefinitionId::value));
        var bytes=new java.io.ByteArrayOutputStream();
        for(String name:List.of("animal_migration","camp_regrowth","controlled_landslide","goat_guest","local_mist",
                "parallel_steps","required_signal","safe_micro","shared_choice")){
            var raw=Files.readAllBytes(catalogRoot.resolve("golden").resolve(name+".json"));
            var event=EVENT.decode(raw);
            if(!event.id().equals(new DefinitionId("proutlost:"+name)))throw new AssertionError("golden identity "+name);
            definitions.put(event.id(),event);bytes.write(raw);
        }
        return new Loaded(config,new CatalogSnapshot(new Epoch(1),BlobContent.hash(bytes.toByteArray()),definitions,Map.of(),Map.of()));
    }
    public static AggregateHeader header(UUID id,Tick now){
        return new AggregateHeader(1,id,new Revision(1),new Sequence(0),now);
    }
    public static DecisionInput input(Loaded loaded,RegionId region,List<PlayerId> players,Tick now,long snapshotRevision,
            List<UniqueUseRecord> uniqueness,List<CooldownRecord> cooldowns,List<DirectorFact> facts){
        var selectedScopes=new HashSet<ScopeRef>();
        selectedScopes.add(new ScopeRef(ScopeKind.WORLD,WORLD.value()));
        selectedScopes.add(new ScopeRef(ScopeKind.REGION,region.value()));
        for(var player:players)selectedScopes.add(new ScopeRef(ScopeKind.PLAYER,player.value()));
        var scopes=new ScopeSet(WORLD,selectedScopes);var revisions=new HashMap<ScopeRef,Revision>();
        var scopeSnapshots=new HashMap<ScopeRef,ScopeSnapshot>();
        for(var scope:selectedScopes){
            revisions.put(scope,new Revision(snapshotRevision));
            scopeSnapshots.put(scope,new ScopeSnapshot(scope,new Revision(snapshotRevision),EnumSet.allOf(SensorKind.class),Set.of(),Map.of()));
        }
        var playerSnapshots=new HashMap<PlayerId,PlayerSnapshot>();var operational=new ArrayList<PlayerOperationalMemory>();
        for(var player:players){
            playerSnapshots.put(player,new PlayerSnapshot(player,new Revision(snapshotRevision),now,PlayerEligibility.ELIGIBLE,
                new PositionSnapshot(new BlockPosition(DIM,0,64,0),now),Set.of(region),Set.of(),new DurationTicks(10000),Set.of(),
                new ClientCapabilitySnapshot(1,Set.of(),Map.of(),true),Set.of()));
            operational.add(new PlayerOperationalMemory(header(player.value(),now),player,new DurationTicks(20000),Optional.of(region),Optional.empty(),
                List.of(),List.of(),Set.of(),new UnitValue(.5),Map.of(),Optional.empty(),new Epoch(0)));
        }
        var record=new RegionRecord(header(region.value(),now),region,DIM,RegionStatus.ACTIVE,new SupportShape(List.of(),List.of(),List.of()),Set.of(),
            new UnitValue(.5),Set.of(),Set.of(),new Revision(1),new RegionPersonality(Map.of()),Set.of());
        var regionMemory=new RegionMemory(header(region.value(),now),region,Map.of(),now,now,Set.of(),Set.of(),new RegionActivityState(Map.of(),Map.of()));
        var context=new ObservationContext(scopeSnapshots,playerSnapshots,Map.of(),
            Map.of(region,new RegionSnapshot(record,Set.of(),Set.copyOf(players),List.of(),Map.of())),Map.of(),Map.of(),List.of(),Map.of());
        var snapshot=new SnapshotBatch(new RequestId(UUID.randomUUID()),scopes,now,revisions,context,Set.of(),Set.of(new ChunkRef(DIM,0,0)),HealthMode.NORMAL);
        var memory=new MemoryView(scopes,revisions,List.of(regionMemory),operational,List.of(),Map.of(region,record),Map.of(),Map.of());
        var controls=new EngineControlState(1,new Revision(0),now,now,new UnitValue(0),now,Optional.empty(),new UnitValue(.5));
        var world=new DirectorWorldState(header(WORLD.value(),now),WORLD,new Epoch(1),now,RunControl.RUNNING,HealthMode.NORMAL,PacingState.REST,
            GlobalTension.CALM,new ResourceId("proutlost:ledger_pure_fixture"),loaded.catalog().hash(),loaded.config().provenanceHash(),
            new Epoch(1),new Sequence(0),controls);
        var state=new DecisionState(world,new BudgetView(CAP,BudgetMath.zero(),Map.of()),new OwnershipView(Map.of()),cooldowns,uniqueness,
            facts,List.of(),List.of(),List.of());
        return new DecisionInput(snapshot,memory,loaded.catalog(),loaded.config(),List.of(),List.of(),42,state,Optional.empty());
    }
    public static DecisionInput input(Loaded loaded,RegionId region,List<PlayerId> players,Tick now){
        return input(loaded,region,players,now,1,List.of(),List.of(),List.of());
    }
    public static DecisionInput withLedger(DecisionInput input,List<UniqueUseRecord> uniqueness,List<CooldownRecord> cooldowns,List<DirectorFact> facts){
        var before=input.state();var state=new DecisionState(before.world(),before.budgets(),before.ownership(),cooldowns,uniqueness,facts,before.events(),before.chains(),before.disasters());
        return new DecisionInput(input.context(),input.memory(),input.catalog(),input.config(),input.requests(),input.chains(),input.seed(),state,input.advisorySnapshot());
    }
    public static ExecutionPlan plan(EventDefinition definition,DecisionInput input,List<PlayerId> participants){
        var roles=new HashMap<String,List<PlayerId>>();
        for(var role:definition.target().roles().entrySet()){
            int count=Math.max(role.getValue().min(),Math.min(participants.size(),role.getValue().max()));
            if(count>participants.size())throw new IllegalArgumentException("insufficient explicit fixture participants");
            roles.put(role.getKey(),List.copyOf(participants.subList(0,count)));
        }
        var event=new EventInstanceId(UUID.randomUUID());var footprint=new Footprint(DIM,input.context().loadedChunks(),Set.of(),List.of());
        var intents=new ArrayList<EffectIntent>();
        for(var step:definition.steps())intents.add(new EffectIntent(new OperationToken(UUID.randomUUID()),step.primitive(),1,step.args(),footprint,event));
        return new ExecutionPlan(new CandidateId(UUID.randomUUID()),new DefinitionRef(definition.id(),definition.contentRevision(),BlobContent.hash(EVENT.encode(definition))),
            intents,new RoleAssignments(roles),input.context().scopes(),new ReadSet(Map.of(),Map.of(RESOURCE,new Epoch(1)),Map.of()));
    }
    public static ReservationToken reserve(ExecutionPlan plan,Tick now){
        return reserve(plan,now,RESOURCE);
    }
    public static ReservationToken reserve(ExecutionPlan plan,Tick now,ResourceKey resource){
        var event=plan.effects().getFirst().instanceId();
        var claim=new ResourceClaim(resource,ClaimPolicy.EXCLUSIVE,BudgetMath.zero(),Optional.empty(),new Epoch(1));
        var arbiter=new ResourceArbiter(WORLD,new Epoch(0),100,4,new ResourceArbiter.Compatibility(){
            public boolean share(ResourceClaim a,ResourceClaim b){return false;}
            public boolean merge(ResourceId f,ResourceClaim a,ResourceClaim b){return false;}
        });
        var candidate=new ResourceArbiter.Candidate(new ReservationId(UUID.randomUUID()),event,
            new ResourceArbiter.Rank(false,false,false,Priority.AMBIENT,0,1,42),now.plus(10000),List.of(claim),
            Map.of(new ScopeRef(ScopeKind.WORLD,WORLD.value()),BudgetMath.zero()));
        return arbiter.arbitrate(List.of(candidate),now,Map.of(resource,new Epoch(1)),Map.of(new ScopeRef(ScopeKind.WORLD,WORLD.value()),CAP))
            .getFirst().accepted().orElseThrow();
    }
    public static Prepared prepared(ExecutionPlan plan,ReservationToken reservation,List<UniqueUseRecord> uniqueness,int effectIndex,Tick now){
        var effect=plan.effects().get(effectIndex);var pre=BlobContent.capture(new ResourceId("proutlost_director:ledger_fixture_pre_v1"),1,new byte[]{1});
        var post=BlobContent.capture(new ResourceId("proutlost_director:ledger_fixture_post_v1"),1,new byte[]{2});
        var refs=new EffectBlobs(Set.of(pre.reference(),post.reference()),post.reference(),pre.reference(),
            Map.of(RESOURCE,pre.reference()),Map.of(RESOURCE,post.reference()));
        var read=new ReadSet(plan.readSet().revisions(),Map.of(RESOURCE,new Epoch(1)),Map.of(RESOURCE,pre.reference().hash()));
        var slice=new PreparedSlice(new SliceId(UUID.randomUUID()),effect.operationToken(),effect.expectedFootprint(),read,List.of(effect),CAP);
        var prepare=new PrepareRecord(new TransactionId(UUID.randomUUID()),effect.operationToken(),reservation.generation(),read,reservation.expiryTick(),
            slice,reservation,new ValidationResult(true,List.of(),read),uniqueness,Set.of(),List.of(),refs);
        var result=new SliceResult(Outcome.SUCCEEDED,Set.of(effect.operationToken()),Map.of(RESOURCE,post.reference().hash()),0,Set.of(),Set.of(),List.of());
        return new Prepared(prepare,List.of(pre,post),result);
    }
    public static DirectorFact completion(EventDefinition definition,ExecutionPlan plan){
        var id=UUID.nameUUIDFromBytes(String.join("\0","catalogue-completion-v1",plan.effects().getFirst().instanceId().value().toString()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return new DirectorFact(new FactId(id),definition.completion().factType(),List.copyOf(plan.scopes().scopes()),
            Optional.of(plan.effects().getFirst().instanceId()),EvidenceStatus.CONFIRMED,
            Map.of("definition",new JsonValue.Text(definition.id().value()),"source",new JsonValue.Text("CATALOGUE_SELECTED"),
                "pureFixture",new JsonValue.Bool(true),"humanInteraction",new JsonValue.Bool(false)),true,Optional.empty());
    }
}
