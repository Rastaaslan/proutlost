package fr.proutlost.director.runtime;

import fr.proutlost.director.api.codec.ModelCodec;
import fr.proutlost.director.api.error.ReasonCode;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.port.CatalogPort;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class AdmissionTests {
    static int assertions;static void check(boolean b,String reason){if(!b)throw new AssertionError(reason);assertions++;}
    static final WorldId WORLD=new WorldId(new UUID(0,1));static final ScopeRef SCOPE=new ScopeRef(ScopeKind.WORLD,WORLD.value());
    static final DimensionId DIM=new DimensionId("minecraft:overworld");static final ResourceKey CELL=new ResourceKey(OwnershipDomain.BLOCK,DIM,"0,0,0");
    static final BudgetVector ZERO=BudgetMath.zero();static final PrimitiveId PRIMITIVE=new PrimitiveId("proutlost:trace");
    static final Trace ARGS=new Trace(new TagId("proutlost:test"),new TagId("proutlost:seen"),true);
    static EventDefinition definition(){
        var duration=new DurationTicks(100);var zero=new DurationTicks(0);
        return new EventDefinition(1,new Revision(1),new DefinitionId("proutlost:test"),Calibration.DEFAULT_FOR_TESTING,Category.AMBIENT,Set.of(Family.MEMORY),true,new Selection(1,0),
                new Target(TargetScope.SOLO,Set.of(),PlayerPolicy.FAIR,Map.of("witness",new Role(1,1,false))),new PlayersPresent(1),new Timing(duration,new Cooldowns(zero,zero,zero)),
                new Uniqueness(UniquenessScope.REPEATABLE,ConsumeOn.EFFECT_CONFIRMED),ZERO,new Conflicts(Set.of(),Set.of(),Set.of(),Priority.AMBIENT,Preemption.YIELD_BEFORE_COMMIT),
                new Safety(Risk.SAFE,Set.of(),Optional.empty(),Optional.empty(),PlayerEditPolicy.YIELD,JournalPolicy.PLAYER_OPT_IN,ParticipantLoss.ABORT_IF_MIN_LOST),
                List.of(new EventStep("trace",List.of(),zero,PRIMITIVE,ARGS,OnFailure.ABORT_SAFE)),new RecoveryPolicy(RecoveryMode.RECOVER,duration),new Completion(new FactType("proutlost:seen"),Optional.empty(),new UnitValue(1)));
    }
    static CatalogPort catalog(){
        var definition=definition();var name=new ResourceId("proutlost_director:test");
        var descriptor=new PrimitiveDescriptor(PRIMITIVE,1,name,Set.of(),ZERO,name,Set.of(),Set.of(OwnershipDomain.ANCHOR),Set.of(),new DurationTicks(100),PausePolicy.CLEANUP,RecoveryMode.RECOVER,name,Optional.empty(),Optional.empty(),ProviderMode.TEST_ONLY);
        var snapshot=new CatalogSnapshot(new Epoch(1),BlobContent.hash(new byte[]{1}),Map.of(definition.id(),definition),Map.of(),Map.of(PRIMITIVE,descriptor));return ()->snapshot;
    }
    static AdmissionRequest request(long n){
        var def=definition();var read=new ReadSet(Map.of(),Map.of(CELL,new Epoch(1)),Map.of());var roles=new RoleAssignments(Map.of("witness",List.of(new PlayerId(new UUID(0,5)))));
        var intent=new EffectIntent(new OperationToken(new UUID(0,n)),PRIMITIVE,1,ARGS,new Footprint(DIM,Set.of(),Set.of(),List.of()),new EventInstanceId(new UUID(1,n)));
        var plan=new ExecutionPlan(new CandidateId(new UUID(0,n)),new DefinitionRef(def.id(),def.contentRevision(),BlobContent.hash(new ModelCodec<>(EventDefinition.class).encode(def))),List.of(intent),roles,new ScopeSet(WORLD,Set.of(SCOPE)),read);
        return new AdmissionRequest(new DecisionId(new UUID(0,n)),read,List.of(new ResourceClaim(CELL,ClaimPolicy.EXCLUSIVE,ZERO,Optional.empty(),new Epoch(1))),roles,plan);
    }
    static ResourceArbiter arbiter(){return arbiter(10,0);}
    static ResourceArbiter arbiter(int windowLimit,long generationFloor){
        return new ResourceArbiter(WORLD,new Epoch(generationFloor),100,windowLimit,
            new ResourceArbiter.Compatibility(){
                public boolean share(ResourceClaim a,ResourceClaim b){return false;}
                public boolean merge(ResourceId id,ResourceClaim a,ResourceClaim b){return false;}
            });
    }
    static AdmissionService.HardValidator validator(AtomicBoolean blocked){return new AdmissionService.HardValidator(){
        public ProviderMode mode(){return ProviderMode.TEST_ONLY;}
        public AdmissionService.Assessment assess(AdmissionRequest request,Tick tick){return new AdmissionService.Assessment(new ValidationResult(!blocked.get(),blocked.get()?List.of(ReasonCode.SAFETY_COOLDOWN):List.of(),request.expectedRevisions()),request.claims(),new ResourceArbiter.Rank(false,false,false,Priority.AMBIENT,request.proposalId().value().getLeastSignificantBits(),1,1),Map.of(SCOPE,ZERO));}
    };}
    public static void main(String[] args){
        var blocked=new AtomicBoolean();var book=arbiter();var service=new AdmissionService(catalog(),validator(blocked),book,()->new Tick(1),new DurationTicks(10),10,ProviderMode.TEST_ONLY);
        var low=request(10);var high=request(20);
        check(service.admit(low).status()==AdmissionStatus.DEFERRED&&service.admit(high).status()==AdmissionStatus.DEFERRED,"arrivals queue until window closes");
        var results=service.resolveWindow(Map.of(CELL,new Epoch(1)),Map.of(SCOPE,ZERO));
        check(results.get(high.proposalId()).status()==AdmissionStatus.ACCEPTED&&results.get(low.proposalId()).status()==AdmissionStatus.DEFERRED,"window honors rank not arrival");
        check(service.admit(high).equals(results.get(high.proposalId()))&&book.snapshot().size()==1,"idempotent accepted request");
        // A previously accepted token cannot be replayed after live safety
        // changes or an atomic content reload. The arbiter still owns its
        // reservation: denial is NOT permission to release another writer.
        var mutable=new java.util.concurrent.atomic.AtomicReference<>(catalog().current());
        var replayBook=arbiter();
        var newlyBlocked=new AtomicBoolean(false);
        var replay=new AdmissionService((CatalogPort)mutable::get,validator(newlyBlocked),
            replayBook,()->new Tick(1),new DurationTicks(10),10,ProviderMode.TEST_ONLY);
        var accepted=request(70);
        check(replay.admit(accepted).status()==AdmissionStatus.DEFERRED &&
            replay.resolveWindow(Map.of(CELL,new Epoch(1)),Map.of(SCOPE,ZERO))
                .get(accepted.proposalId()).status()==AdmissionStatus.ACCEPTED,
            "replay scenario gets an actual reservation");
        newlyBlocked.set(true);
        check(replay.admit(accepted).reasons().equals(List.of(ReasonCode.SAFETY_COOLDOWN))
            && replayBook.snapshot().size()==1,
            "revoked safety denies accepted replay without destroying held claim");
        check(replay.resolveWindow(Map.of(CELL,new Epoch(1)),Map.of(SCOPE,ZERO))
                .get(accepted.proposalId()).reasons().equals(List.of(ReasonCode.SAFETY_COOLDOWN))
                && replayBook.snapshot().size()==1,
            "repeat decision window must not reissue token after safety change");
        newlyBlocked.set(false);
        var initial=mutable.get();
        mutable.set(new CatalogSnapshot(new Epoch(2),initial.hash(),
            Map.of(),initial.chains(),initial.primitives()));
        check(replay.admit(accepted).reasons().equals(List.of(ReasonCode.CATALOG_INVALID))
            && replayBook.snapshot().size()==1,
            "catalog reload denies old accepted token even while claim remains held");
        check(replay.resolveWindow(Map.of(CELL,new Epoch(1)),Map.of(SCOPE,ZERO))
                .get(accepted.proposalId()).reasons().equals(List.of(ReasonCode.CATALOG_INVALID)),
            "repeat decision window must not serve a cached token after catalog reload");
        mutable.set(initial);
        var providerGone=new AtomicBoolean(false);
        var goneBook=arbiter();
        var goneValidator=new AdmissionService.HardValidator(){
            public ProviderMode mode(){return ProviderMode.TEST_ONLY;}
            public AdmissionService.Assessment assess(AdmissionRequest request,Tick now){
                if(providerGone.get())throw new IllegalStateException("native provider unavailable");
                return validator(new AtomicBoolean(false)).assess(request,now);
            }
        };
        var gone=new AdmissionService(catalog(),goneValidator,goneBook,
            ()->new Tick(1),new DurationTicks(10),10,ProviderMode.TEST_ONLY);
        var previouslyAccepted=request(71);
        gone.admit(previouslyAccepted);
        check(gone.resolveWindow(Map.of(CELL,new Epoch(1)),Map.of(SCOPE,ZERO))
                .get(previouslyAccepted.proposalId()).status()==AdmissionStatus.ACCEPTED,
            "provider-loss scenario obtains reservation");
        providerGone.set(true);
        check(gone.resolveWindow(Map.of(CELL,new Epoch(1)),Map.of(SCOPE,ZERO))
                .get(previouslyAccepted.proposalId()).reasons().equals(List.of(ReasonCode.INTERNAL_FAULT)),
            "repeat window must fail closed when physical provider disappears");
        check(gone.admit(previouslyAccepted).reasons().equals(List.of(ReasonCode.INTERNAL_FAULT))
            && goneBook.snapshot().size()==1,
            "provider loss fails closed on replay without dropping existing claim");
        var substituted=new AdmissionRequest(high.proposalId(),low.expectedRevisions(),low.claims(),low.targetBindings(),low.plan());
        check(service.admit(substituted).reasons().contains(ReasonCode.IDEMPOTENCY_CONFLICT),"same proposal cannot replace payload");
        var otherBook=arbiter();var revalidation=new AdmissionService(catalog(),validator(blocked),otherBook,()->new Tick(1),new DurationTicks(10),10,ProviderMode.TEST_ONLY);
        revalidation.admit(low);blocked.set(true);var denied=revalidation.resolveWindow(Map.of(CELL,new Epoch(1)),Map.of(SCOPE,ZERO)).get(low.proposalId());
        check(denied.status()==AdmissionStatus.REJECTED&&denied.reasons().contains(ReasonCode.SAFETY_COOLDOWN)&&otherBook.snapshot().isEmpty(),"live safety revalidated at arbitration");

        // An unavailable production provider or catalog may not interrupt the
        // Minecraft actor or install even a temporary logical reservation.
        var unavailableBook=arbiter();
        AdmissionService.HardValidator unavailableProvider=new AdmissionService.HardValidator(){
            public ProviderMode mode(){return ProviderMode.TEST_ONLY;}
            public AdmissionService.Assessment assess(AdmissionRequest request,Tick now){
                throw new IllegalStateException("simulated native provider offline");
            }
        };
        var unavailable=new AdmissionService(catalog(),unavailableProvider,unavailableBook,
            ()->new Tick(1),new DurationTicks(10),10,ProviderMode.TEST_ONLY);
        var error=unavailable.admit(request(30));
        check(error.status()==AdmissionStatus.REJECTED &&
            error.reasons().equals(List.of(ReasonCode.INTERNAL_FAULT)) &&
            unavailableBook.snapshot().isEmpty() &&
            unavailable.resolveWindow(Map.of(CELL,new Epoch(1)),Map.of(SCOPE,ZERO)).isEmpty(),
            "provider exception must fail closed before reserving");

        var invalidCatalogBook=arbiter();
        CatalogPort invalidCatalog=()->{throw new IllegalStateException("catalog changed under actor");};
        var invalidCatalogService=new AdmissionService(invalidCatalog,validator(new AtomicBoolean()),
            invalidCatalogBook,()->new Tick(1),new DurationTicks(10),10,ProviderMode.TEST_ONLY);
        check(invalidCatalogService.admit(request(31)).reasons().equals(List.of(ReasonCode.INTERNAL_FAULT)) &&
            invalidCatalogBook.snapshot().isEmpty(),
            "catalog exception must fail closed before reserving");

        var intermittent=new AtomicBoolean();
        var stable=validator(new AtomicBoolean());
        var broken=request(32);var valid=request(33);
        AdmissionService.HardValidator selective=new AdmissionService.HardValidator(){
            public ProviderMode mode(){return ProviderMode.TEST_ONLY;}
            public AdmissionService.Assessment assess(AdmissionRequest request,Tick now){
                if(intermittent.get() && request.proposalId().equals(broken.proposalId()))
                    throw new IllegalStateException("provider went away between admission and window");
                return stable.assess(request,now);
            }
        };
        var mixedBook=arbiter();
        var mixed=new AdmissionService(catalog(),selective,mixedBook,
            ()->new Tick(1),new DurationTicks(10),10,ProviderMode.TEST_ONLY);
        check(mixed.admit(broken).status()==AdmissionStatus.DEFERRED &&
            mixed.admit(valid).status()==AdmissionStatus.DEFERRED,
            "mixed admission requests are initially valid");
        intermittent.set(true);
        var mixedResults=mixed.resolveWindow(Map.of(CELL,new Epoch(1)),Map.of(SCOPE,ZERO));
        check(mixedResults.get(broken.proposalId()).status()==AdmissionStatus.REJECTED &&
            mixedResults.get(broken.proposalId()).reasons().equals(List.of(ReasonCode.INTERNAL_FAULT)) &&
            mixedResults.get(valid.proposalId()).status()==AdmissionStatus.ACCEPTED &&
            mixedBook.snapshot().size()==1,
            "failed provider cannot poison a separate valid admission window");
        check(mixed.admit(broken).reasons().equals(List.of(ReasonCode.INTERNAL_FAULT)),
            "repeated request must retain the fail-closed window verdict");
        // Missing clock cannot approve either a new or a queued request.
        var clockOffline=new AtomicBoolean(false);
        java.util.function.Supplier<Tick> intermittentClock=()->{
            if(clockOffline.get())throw new IllegalStateException("clock offline");
            return new Tick(1);
        };
        var clockBook=arbiter();
        var clocked=new AdmissionService(catalog(),validator(new AtomicBoolean()),clockBook,
            intermittentClock,new DurationTicks(10),10,ProviderMode.TEST_ONLY);
        var clockRequest=request(40);
        check(clocked.admit(clockRequest).status()==AdmissionStatus.DEFERRED,
            "clocked proposal must queue before outage");
        clockOffline.set(true);
        var clockFailure=clocked.resolveWindow(Map.of(CELL,new Epoch(1)),Map.of(SCOPE,ZERO));
        check(clockFailure.get(clockRequest.proposalId()).reasons().equals(List.of(ReasonCode.INTERNAL_FAULT)) &&
            clockBook.snapshot().isEmpty(), "clock outage must not grant a reservation");
        check(clocked.admit(request(41)).reasons().equals(List.of(ReasonCode.INTERNAL_FAULT)),
            "clock outage cannot accept new arrival");

        // Misconfigured coordinator capacity vs arbiter window bound must be
        // rejected, not crash the tick actor or leave a logical reservation.
        var smallBook=arbiter(1,0);
        var smallService=new AdmissionService(catalog(),validator(new AtomicBoolean()),
            smallBook,()->new Tick(1),new DurationTicks(10),10,ProviderMode.TEST_ONLY);
        var first=request(50);var second=request(51);
        check(smallService.admit(first).status()==AdmissionStatus.DEFERRED &&
            smallService.admit(second).status()==AdmissionStatus.DEFERRED,
            "two proposals may queue before the misconfigured window");
        var smallResults=smallService.resolveWindow(Map.of(CELL,new Epoch(1)),Map.of(SCOPE,ZERO));
        check(smallResults.get(first.proposalId()).reasons().equals(List.of(ReasonCode.INTERNAL_FAULT)) &&
            smallResults.get(second.proposalId()).reasons().equals(List.of(ReasonCode.INTERNAL_FAULT)) &&
            smallBook.snapshot().isEmpty(),
            "arbiter bound fault must fail the entire window without granting");

        // Overflow must be identified before ANY reservation in a window
        // becomes visible; do not allow a partial J03 logical admission.
        var exhaustedBook=arbiter(10,Long.MAX_VALUE-1);
        var exhaustedService=new AdmissionService(catalog(),validator(new AtomicBoolean()),
            exhaustedBook,()->new Tick(1),new DurationTicks(10),10,ProviderMode.TEST_ONLY);
        var near1=request(60);var near2=request(61);
        check(exhaustedService.admit(near1).status()==AdmissionStatus.DEFERRED &&
            exhaustedService.admit(near2).status()==AdmissionStatus.DEFERRED,
            "pre-overflow proposals may queue without granting");
        var exhausted=exhaustedService.resolveWindow(Map.of(CELL,new Epoch(1)),Map.of(SCOPE,ZERO));
        check(exhausted.get(near1.proposalId()).reasons().equals(List.of(ReasonCode.INTERNAL_FAULT)) &&
            exhausted.get(near2.proposalId()).reasons().equals(List.of(ReasonCode.INTERNAL_FAULT)) &&
            exhaustedBook.snapshot().isEmpty(),
            "generation exhaustion must leave the arbiter entirely unchanged");

        // A compatibility backend can fail after an earlier candidate has
        // provisionally won. No claim, displacement or generation may escape.
        var compatibilityFault=new AtomicBoolean(true);
        var atomicBook=new ResourceArbiter(WORLD,new Epoch(0),100,8,
            new ResourceArbiter.Compatibility() {
                public boolean share(ResourceClaim left,ResourceClaim right) {
                    if(compatibilityFault.get()) throw new IllegalStateException("injected share validator failure");
                    return false;
                }
                public boolean merge(ResourceId id,ResourceClaim left,ResourceClaim right){return false;}
            });
        var shared=new ResourceClaim(CELL,ClaimPolicy.SHARE,ZERO,Optional.empty(),new Epoch(1));
        var firstShared=new ResourceArbiter.Candidate(new ReservationId(new UUID(0,100)),
            new EventInstanceId(new UUID(1,100)),
            new ResourceArbiter.Rank(false,false,false,Priority.AMBIENT,0,2,100),
            new Tick(100),List.of(shared),Map.of(SCOPE,ZERO));
        var secondShared=new ResourceArbiter.Candidate(new ReservationId(new UUID(0,101)),
            new EventInstanceId(new UUID(1,101)),
            new ResourceArbiter.Rank(false,false,false,Priority.AMBIENT,0,1,101),
            new Tick(100),List.of(shared),Map.of(SCOPE,ZERO));
        var sharedWindow=List.of(firstShared,secondShared);
        var sharedEpochs=Map.of(CELL,new Epoch(1));
        var sharedCapacities=Map.of(SCOPE,ZERO);
        boolean shareException=false;
        try {atomicBook.arbitrate(sharedWindow,new Tick(1),sharedEpochs,sharedCapacities);}
        catch(IllegalStateException expected){shareException=true;}
        check(shareException && atomicBook.snapshot().isEmpty(),
            "later compatibility exception cannot publish earlier grant");
        compatibilityFault.set(false);
        var retried=atomicBook.arbitrate(sharedWindow,new Tick(1),sharedEpochs,sharedCapacities);
        check(retried.size()==2 && retried.getFirst().accepted().isPresent() &&
            retried.getFirst().accepted().orElseThrow().generation().value()==1 &&
            retried.get(1).rejection().orElseThrow()==ReasonCode.CLAIM_CONFLICT &&
            atomicBook.snapshot().size()==1,
            "failed window must retry from original generation and original owners");

        try{new AdmissionService(catalog(),validator(blocked),arbiter(),()->new Tick(1),new DurationTicks(10),10,ProviderMode.PRODUCTION);throw new AssertionError("fake accepted");}catch(IllegalArgumentException expected){assertions++;}
        System.out.println("PASS AdmissionPort assertions="+assertions+" (TEST_ONLY validator; native live-validator integration pending)");
    }
}
