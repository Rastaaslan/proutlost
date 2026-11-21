package fr.proutlost.integration.contra;

import fr.proutlost.contract.director.Anchor;
import fr.proutlost.contract.director.BlockPos;
import fr.proutlost.contract.director.Bounds;
import fr.proutlost.contract.director.CapabilityRequirement;
import fr.proutlost.contract.director.CapabilityTypes;
import fr.proutlost.contract.director.Digest;
import fr.proutlost.contract.director.EvidenceRef;
import fr.proutlost.contract.director.EventNeed;
import fr.proutlost.contract.director.Id;
import fr.proutlost.contract.director.LocationCapability;
import fr.proutlost.contract.director.LocationObservation;
import fr.proutlost.contract.director.ObservationPort;
import fr.proutlost.contract.director.SchemaVersion;
import fr.proutlost.contract.director.SemanticRegion;
import fr.proutlost.contract.director.SemanticRegionId;
import fr.proutlost.contract.director.StrictCodec;
import fr.proutlost.contract.director.TypedValue;
import fr.proutlost.contract.director.Vec3;
import fr.proutlost.contract.director.Vocabulary.Channel;
import fr.proutlost.contract.director.Vocabulary.SourceType;
import fr.proutlost.director.api.codec.ModelCodec;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.model.PhysicalCatalogModels.*;
import fr.proutlost.director.bootstrap.PhysicalCatalogJournalAdmission;
import fr.proutlost.director.persistence.JournalStore;
import fr.proutlost.director.persistence.StoreOptions;
import fr.proutlost.director.runtime.ResultTransaction;
import fr.proutlost.worldprep.director.PhysicalCatalogExporter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Declared synthetic semantics through the real exporter, J04 and resolver.
 * No Minecraft world, authored production capability, effect execution or audio perception. */
public final class PhysicalCatalogExporterResolverScenario {
    private PhysicalCatalogExporterResolverScenario() {}
    private static final Id WORLD=new Id("test:physical_source"),BUILD=new Id("test:physical_build"),DIM=new Id("minecraft:overworld");
    private static final Id LOCATION=new Id("test:site"),ANCHOR=new Id("test:anchor"),CAPABILITY=new Id("proutlost:safe_shore"),TAG=new Id("test:explicit_tag");
    private static final WorldId DIRECTOR_WORLD=new WorldId(new UUID(7,1));
    private static final SemanticRegionId REGION=new SemanticRegionId(new Id("test:region"));
    private static final Bounds BOX=new Bounds(DIM,new BlockPos(0,64,0),new BlockPos(4,68,4));
    private static final Instant NOW=Instant.parse("2026-11-18T00:00:00Z");
    private static final List<EvidenceRef> EVIDENCE=List.of(new EvidenceRef(new Id("test:declared_synthetic"),new Digest("0".repeat(64)),new Id("test:synthetic_validator")));
    private static final BudgetVector ZERO=new BudgetVector(0,0,0,0,0,0,0,0,0,0);
    private static final PrimitiveId SOUND=new PrimitiveId("proutlost:target_sound");
    private static final TargetSound SOUND_ARGS=new TargetSound("witness",new ResourceId("minecraft:block.gravel.step"),1,4,1);
    private static int assertions;
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);assertions++;}
    private static void guard(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static fr.proutlost.contract.director.Revision rev(){return new fr.proutlost.contract.director.Revision("qa-explicit-r1");}

    private static PhysicalCatalogExporter.Site site(boolean explicit){
        var capability=new LocationCapability(CAPABILITY,new fr.proutlost.contract.director.Revision("1"),
            new TypedValue(CAPABILITY,new fr.proutlost.contract.director.Revision("1"),Map.of("capacity",new TypedValue.Number(1))),
            Set.of(ANCHOR),Set.of(),EVIDENCE);
        var anchors=List.of(new Anchor(ANCHOR,new Id("test:observation"),DIM,new Vec3(1,65,1),null,1,Set.of()));
        if(!explicit)return new PhysicalCatalogExporter.Site(LOCATION,rev(),SourceType.worldprep_prepared_site,null,new Id("test:source_receipt"),
            List.of(BOX),Set.of(REGION),anchors,List.of(),List.of(capability),List.of(),List.of(),EVIDENCE,EVIDENCE);
        var tags=new HashSet<>(Set.of(TAG));var channels=new HashSet<>(Set.of(Channel.SOUND));
        var result=new PhysicalCatalogExporter.Site(LOCATION,rev(),SourceType.worldprep_prepared_site,null,new Id("test:source_receipt"),
            List.of(BOX),Set.of(REGION),anchors,List.of(),List.of(capability),List.of(),List.of(),EVIDENCE,EVIDENCE,tags,channels);
        tags.clear();channels.clear();
        check(result.tags().equals(Set.of(TAG))&&result.presentationChannels().equals(Set.of(Channel.SOUND)),"caller mutation must not alter producer declarations");
        return result;
    }
    private static fr.proutlost.contract.director.CatalogSnapshot exported(boolean explicit){
        var site=site(explicit);
        var region=new SemanticRegion(REGION,rev(),List.of(BOX),Set.of(),0);
        var result=PhysicalCatalogExporter.export(WORLD,BUILD,rev(),NOW,List.of(region),List.of(site),accepted->{
            check(accepted==site,"the physical authority sees the exact complete explicit site");
        });
        check(result.json()!=null,"sealed producer export failed: "+result.reason());
        var source=StrictCodec.decode(result.json(),fr.proutlost.contract.director.CatalogSnapshot.class);
        if(explicit){
            check(source.entries().getFirst().tags().equals(Set.of(TAG)),"producer lost explicit tags before the real consumer");
            check(source.entries().getFirst().presentation_channels().equals(Set.of(Channel.SOUND)),"producer lost explicit SOUND channel before the real resolver");
        }else{
            check(source.entries().getFirst().tags().isEmpty()&&source.entries().getFirst().presentation_channels().isEmpty(),"legacy producers must not gain inferred semantics");
        }
        return source;
    }
    private static Document document(fr.proutlost.contract.director.CatalogSnapshot source){
        var blob=BlobContent.capture(PhysicalCatalogFacts.SOURCE_CODEC,1,StrictCodec.encode(source).getBytes(StandardCharsets.UTF_8));
        var regions=Map.of(new ResourceId(REGION.value().value()),new RegionId(new UUID(7,2)));
        var locations=Map.of(new ResourceId(LOCATION.value()),new LocationId(new UUID(7,3)));
        var anchors=List.of(new AnchorBinding(new ResourceId(LOCATION.value()),new ResourceId(ANCHOR.value()),new AnchorId(new UUID(7,4))));
        var draft=new Binding(DIRECTOR_WORLD,new ResourceId(WORLD.value()),new ResourceId(BUILD.value()),blob.reference().hash(),new Fingerprint("0".repeat(64)),regions,locations,anchors);
        var binding=new Binding(draft.directorWorld(),draft.sourceWorld(),draft.sourceBuild(),draft.canonicalSourceSha(),PhysicalCatalogFacts.bindingDigest(draft),regions,locations,anchors);
        var key=new Key(DIRECTOR_WORLD,binding.sourceWorld(),binding.sourceBuild(),source.catalog_revision().value(),new Fingerprint(source.digest().value()),blob.reference().hash(),binding.bindingSha());
        return new Document(key,binding,blob);
    }
    private static DirectorWorldState worldState(StoreSession session){
        var tick=new Tick(0);var hash=BlobContent.hash(new byte[]{1});
        var engine=new EngineControlState(1,new Revision(0),tick,tick,new UnitValue(0),tick,Optional.empty(),new UnitValue(.5));
        return new DirectorWorldState(new AggregateHeader(1,DIRECTOR_WORLD.value(),new Revision(1),new Sequence(0),tick),DIRECTOR_WORLD,session.storeGeneration(),tick,
            RunControl.PAUSED,HealthMode.NORMAL,PacingState.REST,GlobalTension.CALM,new ResourceId("test:metadata"),hash,hash,session.bootEpoch(),new Sequence(0),engine);
    }
    private static EventDefinition definition(){
        var duration=new DurationTicks(100);var zero=new DurationTicks(0);
        return new EventDefinition(1,new Revision(1),new DefinitionId("test:physical_sound"),Calibration.DEFAULT_FOR_TESTING,Category.AMBIENT,Set.of(Family.MEMORY),true,new Selection(1,0),
            new Target(TargetScope.SOLO,Set.of(),PlayerPolicy.FAIR,Map.of("witness",new Role(1,1,false))),new PlayersPresent(1),new Timing(duration,new Cooldowns(zero,zero,zero)),
            new Uniqueness(UniquenessScope.REPEATABLE,ConsumeOn.EFFECT_CONFIRMED),ZERO,new Conflicts(Set.of(),Set.of(),Set.of(),Priority.AMBIENT,Preemption.YIELD_BEFORE_COMMIT),
            new Safety(Risk.SAFE,Set.of(),Optional.empty(),Optional.empty(),PlayerEditPolicy.YIELD,JournalPolicy.PLAYER_OPT_IN,ParticipantLoss.ABORT_IF_MIN_LOST),
            List.of(new EventStep("sound",List.of(),zero,SOUND,SOUND_ARGS,OnFailure.ABORT_SAFE)),new RecoveryPolicy(RecoveryMode.RECOVER,duration),new Completion(new FactType("test:sound_seen"),Optional.empty(),new UnitValue(1)));
    }
    private static DecisionInput input(StoreSession session,EventDefinition definition){
        var scopes=new ScopeSet(DIRECTOR_WORLD,Set.of(new ScopeRef(ScopeKind.WORLD,DIRECTOR_WORLD.value())));
        var snapshot=new SnapshotBatch(new RequestId(new UUID(7,5)),scopes,new Tick(0),Map.of(),new ObservationContext(Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),List.of(),Map.of()),Set.of(),Set.of(),HealthMode.NORMAL);
        var memory=new MemoryView(scopes,Map.of(),List.of(),List.of(),List.of(),Map.of(),Map.of(),Map.of());
        var hash=BlobContent.hash(new byte[]{1});var catalog=new CatalogSnapshot(new Epoch(1),hash,Map.of(definition.id(),definition),Map.of(),Map.of());
        var config=new ConfigView(new Revision(1),hash,Calibration.DEFAULT_FOR_TESTING,Map.of());
        var state=new DecisionState(worldState(session),new BudgetView(ZERO,ZERO,Map.of()),new OwnershipView(Map.of()),List.of(),List.of(),List.of(),List.of(),List.of(),List.of());
        return new DecisionInput(snapshot,memory,catalog,config,List.of(),List.of(),42,state,Optional.empty());
    }
    private static D0PreparedSiteResolver resolver(DurablePhysicalCatalogView view,DefinitionRef definition){
        var need=new EventNeed(WORLD,SchemaVersion.BASE,new Id("test:authored_synthetic_need"),fr.proutlost.contract.director.Vocabulary.Family.AMBIENT,
            List.of(new CapabilityRequirement(CAPABILITY,List.of())),Set.of(new Id("test:prepared_source")),Set.of(REGION),Set.of(DIM),List.of(),Set.of(),Set.of(),null,Set.of(),EVIDENCE,Set.of(Channel.SOUND),1,List.of(),List.of(),
            new fr.proutlost.contract.director.RecoveryPolicy(List.of(fr.proutlost.contract.director.Vocabulary.RecoveryStrategy.RETRY),List.of(),true,new Id("test:cue"),new Id("test:retry")));
        ObservationPort observations=new ObservationPort(){
            public List<fr.proutlost.contract.director.Observation> after(Id world,long sequence,int limit){throw new AssertionError("this selection requires current observation only");}
            public Optional<LocationObservation> current(Id world,fr.proutlost.contract.director.LocationRef ref){
                return Optional.of(new LocationObservation(WORLD,SchemaVersion.BASE,ref,rev(),NOW,NOW.plusSeconds(1),true,true,Set.of(CAPABILITY),Set.of(),rev(),List.of(),EVIDENCE));
            }
        };
        return new D0PreparedSiteResolver(view,Map.of(definition,need),Map.of(SOUND,new D0PreparedSiteResolver.EffectUse(Set.of(Channel.SOUND),Set.of(),false)),
            Map.of(SourceType.worldprep_prepared_site,new Id("test:prepared_source")),observations,new D0PreparedSiteResolver.PolicySupport(Map.of()),
            (policy,domain,required,entry,effect,use)->{throw new AssertionError("fixture has no protection/surface policy to invoke");},binding->Optional.empty(),()->NOW,()->{});
    }
    private static void until(PhysicalCatalogJournalAdmission runtime,BooleanSupplier ready)throws Exception{
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        while(System.nanoTime()<end){runtime.tick();if(ready.getAsBoolean())return;Thread.sleep(1);}
        throw new AssertionError("J04 fixture timeout: "+runtime.diagnostic());
    }
    private static <T>T await(CompletionStage<T> future)throws Exception{return future.toCompletableFuture().get(15,TimeUnit.SECONDS);}
    private static void scenario(Path path,boolean explicit)throws Exception{
        var source=exported(explicit);var document=document(source);var worker=Executors.newSingleThreadExecutor();
        var options=new StoreOptions(1_048_620,2_097_152,4_194_304,16_777_216,32,0,128);
        var store=await(JournalStore.open(path,DIRECTOR_WORLD,options));
        var actor=new SessionId(new UUID(7,8));var owner=Thread.currentThread();
        PhysicalCatalogJournalAdmission runtime=null;
        try{
            var batch=ResultTransaction.batch(new TransactionId(new UUID(7,9)),store.session().storeGeneration(),Map.of(),List.of(new DurableAggregate(new WorldAggregate(worldState(store.session())))),List.of());
            check(await(store.append(batch)).ackStatus()==AckStatus.DURABLE,"test WORLD reaches real J04");
            var view=new DurablePhysicalCatalogView(WORLD,BUILD,store::session,Map.of(DIM,BOX),snapshot->{for(var entry:snapshot.entries())for(var capability:entry.capabilities())CapabilityTypes.requireKnown(capability.capability_id());});
            runtime=new PhysicalCatalogJournalAdmission(store,()->guard(owner==Thread.currentThread(),"actual actor thread"),actor::equals,
                (binding,use)->guard(binding.equals(document.binding()),"explicit test binding only"),()->true,ignored->true,ignored->{},
                ()->Map.of(new ScopeRef(ScopeKind.WORLD,DIRECTOR_WORLD.value()),new Revision(1)),worker,view);
            until(runtime,runtime::ready);check(runtime.submit(actor,document).state()==State.PENDING,"source admitted through actual J04 lane");
            var ownedRuntime=runtime;until(runtime,()->ownedRuntime.poll(actor,document.key()).state()==State.DURABLE);
            var definition=definition();var definitionRef=new DefinitionRef(definition.id(),definition.contentRevision(),BlobContent.hash(new ModelCodec<>(EventDefinition.class).encode(definition)));
            var input=input(store.session(),definition);var candidate=new Candidate(new CandidateId(new UUID(7,10)),definitionRef,true,List.of(),List.of(),1,new RoleAssignments(Map.of("witness",List.of(new PlayerId(new UUID(7,11))))),input.context().scopes());
            var event=new EventInstanceId(new UUID(7,12));var footprint=new Footprint(new DimensionId(DIM.value()),Set.of(),Set.of(new BlockPosition(new DimensionId(DIM.value()),1,65,1)),List.of());
            var intent=new EffectIntent(new OperationToken(new UUID(7,13)),SOUND,1,SOUND_ARGS,footprint,event);
            var blob=BlobContent.capture(new ResourceId("test:synthetic_plan"),1,new byte[]{1});
            var preview=new PreparedEffect(intent,input.context(),List.of(),new ReadSet(Map.of(),Map.of(),Map.of()),Map.of(),ZERO,List.of(),Set.of(),new EffectBlobs(Set.of(),blob.reference(),blob.reference(),Map.of(),Map.of()));
            var resolved=resolver(view,definitionRef).resolve(candidate,input,event,List.of(preview));
            check(resolved.isPresent()==explicit,"real D0 resolver must accept declared SOUND and refuse unqualified legacy channels");
            if(explicit)check(resolved.orElseThrow().locations().equals(Set.of(new LocationId(new UUID(7,3)))),"real resolver preserves bound site identity");
        }finally{
            if(runtime!=null)runtime.close();await(store.closeAsync());worker.shutdown();if(!worker.awaitTermination(15,TimeUnit.SECONDS))throw new AssertionError("fixture worker did not stop");
        }
    }
    public static void run(Path root)throws Exception{assertions=0;scenario(root.resolve("explicit"),true);scenario(root.resolve("legacy"),false);System.out.println("P008_PHYSICAL_EXPORT_RESOLVER_PASS scope=SYNTHETIC_SEMANTICS_REAL_J04_RESOLVER assertions="+assertions);}
    public static void main(String[] args)throws Exception{if(args.length!=1)throw new IllegalArgumentException("one private fixture path required");run(Path.of(args[0]));}
}
