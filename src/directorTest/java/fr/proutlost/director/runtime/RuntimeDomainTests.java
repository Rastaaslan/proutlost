package fr.proutlost.director.runtime;

import fr.proutlost.director.api.error.ReasonCode;
import fr.proutlost.director.api.model.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

public final class RuntimeDomainTests {
    private static int assertions;
    private static final WorldId WORLD = new WorldId(new UUID(0, 1));
    private static final ScopeRef SCOPE = new ScopeRef(ScopeKind.WORLD, WORLD.value());
    private static final ScopeRef REGION = new ScopeRef(ScopeKind.REGION, new UUID(0, 2));
    private static final DimensionId DIM = new DimensionId("minecraft:overworld");
    private static final ResourceKey A = key("a"), B = key("b"), C = key("c");
    private static final ResourceId MERGE = new ResourceId("proutlost_director:test_merge");
    private static final Map<ResourceKey, Epoch> EPOCHS = Map.of(A,new Epoch(1),B,new Epoch(1),C,new Epoch(1));
    private static final Map<ScopeRef, BudgetVector> CAPS = Map.of(SCOPE,budget(100),REGION,budget(100));
    private static final ResourceArbiter.Compatibility CLOSED = new ResourceArbiter.Compatibility() {
        public boolean share(ResourceClaim a, ResourceClaim b) { return false; }
        public boolean merge(ResourceId id, ResourceClaim a, ResourceClaim b) { return false; }
    };
    private static ResourceKey key(String s) { return new ResourceKey(OwnershipDomain.BLOCK,DIM,s); }
    private static BudgetVector budget(long n) { return new BudgetVector(n,n,n,n,n,n,n,n,n,n); }
    private static ResourceArbiter book() { return new ResourceArbiter(WORLD,new Epoch(10),100,64,CLOSED); }
    private static ResourceArbiter.Rank rank(int n) {
        return new ResourceArbiter.Rank(false,false,false,Priority.AMBIENT,n,1,n);
    }
    private static ResourceClaim claim(ResourceKey key, ClaimPolicy policy) {
        return new ResourceClaim(key,policy,budget(1),policy==ClaimPolicy.MERGE ? Optional.of(MERGE) : Optional.empty(),new Epoch(1));
    }
    private static ResourceArbiter.Candidate candidate(int id, ResourceArbiter.Rank rank, ResourceClaim... claims) {
        return new ResourceArbiter.Candidate(new ReservationId(new UUID(0,id)),new EventInstanceId(new UUID(1,id)),rank,
                new Tick(100),List.of(claims),Map.of(SCOPE,budget(claims.length),REGION,budget(claims.length)));
    }
    private static List<ResourceArbiter.Decision> admit(ResourceArbiter b, ResourceArbiter.Candidate... candidates) {
        return b.arbitrate(List.of(candidates),new Tick(1),EPOCHS,CAPS);
    }
    private static ReservationToken token(List<ResourceArbiter.Decision> d) { return d.getFirst().accepted().orElseThrow(); }
    private static void check(boolean b,String why) { if(!b) throw new AssertionError(why); assertions++; }
    private static void concurrency() throws InterruptedException {
        // Every input permutation must choose the same winner for an exclusive cell.
        var low=candidate(3,rank(1),claim(A,ClaimPolicy.EXCLUSIVE));
        var high=candidate(4,rank(2),claim(A,ClaimPolicy.EXCLUSIVE));
        for(var order:List.of(List.of(low,high),List.of(high,low))) {
            var b=book(); var d=b.arbitrate(order,new Tick(1),EPOCHS,CAPS);
            check(d.getFirst().candidate().equals(high.id()) && d.getFirst().accepted().isPresent(),"priority not arrival");
            check(d.getLast().rejection().equals(Optional.of(ReasonCode.CLAIM_CONFLICT)),"loser refused");
            check(b.snapshot().size()==1,"one winner");
        }
        var b=book(); admit(b,low);
        var failed=candidate(5,rank(3),claim(B,ClaimPolicy.EXCLUSIVE),claim(A,ClaimPolicy.EXCLUSIVE));
        check(admit(b,failed).getFirst().accepted().isEmpty(),"multiclaim conflict");
        check(admit(b,candidate(6,rank(1),claim(B,ClaimPolicy.EXCLUSIVE))).getFirst().accepted().isPresent(),"no partial claim leak");
        check(b.snapshot().size()==2,"multiple independent active events");
        var bounded=book();
        check(bounded.arbitrate(List.of(low),new Tick(1),EPOCHS,Map.of(SCOPE,budget(100),REGION,budget(0)))
                .getFirst().rejection().equals(Optional.of(ReasonCode.BUDGET_EXHAUSTED)),"regional budget");
        check(bounded.snapshot().isEmpty(),"budget refusal atomic");
        check(bounded.arbitrate(List.of(low),new Tick(1),Map.of(),CAPS).getFirst().rejection()
                .equals(Optional.of(ReasonCode.STALE_CONTEXT)),"unknown epoch unsafe");
        var original=token(admit(bounded,low));
        var replacement=candidate(7,rank(9),claim(A,ClaimPolicy.PREEMPT_BEFORE_EFFECT));
        var displaced=admit(bounded,replacement).getFirst();
        check(displaced.displaced().equals(List.of(original)),"preemption returns displaced reservation");
        check(!bounded.valid(original,new Tick(1)),"preempted generation invalid");
        var active=displaced.accepted().orElseThrow(); bounded.markStarted(active,new Tick(1));
        check(admit(bounded,candidate(8,rank(10),claim(A,ClaimPolicy.PREEMPT_BEFORE_EFFECT)))
                .getFirst().accepted().isEmpty(),"started effect cannot be preempted");
        check(bounded.expired(new Tick(100)).equals(List.of(active)),"expired cleanup queue");
        check(bounded.snapshot().equals(List.of(active)),"expiry retains protection");
        check(!bounded.valid(active,new Tick(100)),"expired permit revalidation fails");
        check(!bounded.release(original),"stale release refused"); check(bounded.release(active),"explicit cleanup release");
        var reused=candidate(7,rank(9),claim(A,ClaimPolicy.EXCLUSIVE));
        reused=new ResourceArbiter.Candidate(reused.id(),reused.instance(),reused.rank(),new Tick(200),reused.claims(),reused.scopeCosts());
        var next=token(bounded.arbitrate(List.of(reused),new Tick(101),EPOCHS,CAPS));
        check(next.generation().compareTo(active.generation())>0,"reservation ABA fenced");
        check(!bounded.release(active),"old generation cannot release reused ID");
        AtomicReference<Throwable> error=new AtomicReference<>();
        Thread t=new Thread(()->{try { bounded.snapshot(); } catch(Throwable e) { error.set(e); }}); t.start(); t.join();
        check(error.get() instanceof IllegalStateException,"actor confinement");
    }
    private static void renewal() {
        var b=book();var original=token(admit(b,candidate(9,rank(1),claim(A,ClaimPolicy.EXCLUSIVE))));
        b.markStarted(original,new Tick(2));var renewed=b.renew(original,new Tick(50),new DurationTicks(100));
        check(renewed.generation().compareTo(original.generation())>0,"renewal advances generation");
        check(renewed.claims().equals(original.claims())&&renewed.expiryTick().equals(new Tick(150)),"renewal preserves claims");
        check(!b.valid(original,new Tick(50))&&!b.release(original),"old lease cannot validate or release renewed holder");
        check(b.valid(renewed,new Tick(100)),"new lease is actually held");
        boolean refused=false;try{b.restoreGenerationFloor(new Epoch(100));}catch(IllegalStateException expected){refused=true;}
        check(refused&&b.snapshot().equals(List.of(renewed)),"floor cannot change a live generation");
        refused=false;try{b.renew(renewed,new Tick(150),new DurationTicks(1));}catch(IllegalArgumentException expected){refused=true;}
        check(refused&&b.snapshot().equals(List.of(renewed)),"expired renewal keeps protection without authority");
        var competitor=candidate(11,rank(2),claim(A,ClaimPolicy.EXCLUSIVE));competitor=new ResourceArbiter.Candidate(competitor.id(),competitor.instance(),competitor.rank(),new Tick(300),competitor.claims(),competitor.scopeCosts());
        check(b.arbitrate(List.of(competitor),new Tick(150),EPOCHS,CAPS).getFirst().rejection().equals(Optional.of(ReasonCode.CLAIM_CONFLICT)),"expired event still excludes competing writes until cleanup");
        check(b.release(renewed),"cleanup can release expired renewed holder");b.restoreGenerationFloor(new Epoch(100));
        var next=candidate(10,rank(1),claim(A,ClaimPolicy.EXCLUSIVE));next=new ResourceArbiter.Candidate(next.id(),next.instance(),next.rank(),new Tick(300),next.claims(),next.scopeCosts());
        check(token(b.arbitrate(List.of(next),new Tick(151),EPOCHS,CAPS)).generation().value()>100,"restored floor prevents generation reuse");
    }
    private static void tuple() {
        List<ResourceArbiter.Rank> ranks=List.of(
                new ResourceArbiter.Rank(true,false,false,Priority.MICRO,0,0,9),
                new ResourceArbiter.Rank(false,true,false,Priority.MICRO,0,0,9),
                new ResourceArbiter.Rank(false,false,true,Priority.MICRO,0,0,9),
                new ResourceArbiter.Rank(false,false,false,Priority.DANGER,0,0,9),
                rank(2),rank(1));
        for(int i=0;i<ranks.size()-1;i++) {
            var winner=candidate(20,ranks.get(i),claim(A,ClaimPolicy.EXCLUSIVE));
            var loser=candidate(21,ranks.get(i+1),claim(A,ClaimPolicy.EXCLUSIVE));
            check(token(admit(book(),loser,winner)).id().equals(winner.id()),"tuple component "+i);
        }
        var capped=new ResourceArbiter.Rank(false,false,false,Priority.AMBIENT,10000,1,0);
        var scored=new ResourceArbiter.Rank(false,false,false,Priority.AMBIENT,100,2,9);
        check(token(admit(book(),candidate(22,capped,claim(A,ClaimPolicy.EXCLUSIVE)),candidate(23,scored,claim(A,ClaimPolicy.EXCLUSIVE))))
                .id().value().getLeastSignificantBits()==23,"age capped before score");
        var tie1=new ResourceArbiter.Rank(false,false,false,Priority.AMBIENT,1,1,1);
        var tie2=new ResourceArbiter.Rank(false,false,false,Priority.AMBIENT,1,1,2);
        check(token(admit(book(),candidate(25,tie2,claim(A,ClaimPolicy.EXCLUSIVE)),candidate(24,tie1,claim(A,ClaimPolicy.EXCLUSIVE))))
                .id().value().getLeastSignificantBits()==24,"seeded tie");
    }
    private static void policy() {
        for(var p:List.of(ClaimPolicy.SHARE,ClaimPolicy.MERGE)) {
            var b=book(); admit(b,candidate(30,rank(1),claim(A,p)));
            check(admit(b,candidate(31,rank(2),claim(A,p))).getFirst().accepted().isEmpty(),"unregistered mixer/merge denied");
        }
        var compatible=new ResourceArbiter.Compatibility() {
            public boolean share(ResourceClaim a,ResourceClaim b) { return a.resource().equals(B); }
            public boolean merge(ResourceId id,ResourceClaim a,ResourceClaim b) { return id.equals(MERGE); }
        };
        var b=new ResourceArbiter(WORLD,new Epoch(0),100,64,compatible);
        check(admit(b,candidate(32,rank(1),claim(B,ClaimPolicy.SHARE)),candidate(33,rank(2),claim(B,ClaimPolicy.SHARE)))
                .stream().allMatch(d->d.accepted().isPresent()),"registered sharing admission");
        check(admit(b,candidate(34,rank(1),claim(C,ClaimPolicy.MERGE)),candidate(35,rank(2),claim(C,ClaimPolicy.MERGE)))
                .stream().allMatch(d->d.accepted().isPresent()),"registered merge admission only");
        var preempt=book();var original=token(admit(preempt,candidate(36,rank(1),claim(A,ClaimPolicy.EXCLUSIVE))));
        admit(preempt,candidate(37,rank(20),claim(B,ClaimPolicy.EXCLUSIVE)));
        check(admit(preempt,candidate(38,rank(5),claim(A,ClaimPolicy.PREEMPT_BEFORE_EFFECT),claim(B,ClaimPolicy.EXCLUSIVE)))
                .getFirst().accepted().isEmpty(),"partial preemption denied");
        check(preempt.valid(original,new Tick(1)),"failed preemption retains victim");
    }
    private static void ownership() {
        var owner=new OwnerToken(new UUID(0,40)); var post=BlobContent.hash(new byte[]{1});
        var before=new OwnershipRecord(new AggregateHeader(1,new UUID(0,41),new Revision(1),new Sequence(1),new Tick(1)),A,
                Set.of(owner),new Epoch(1),OwnershipDisposition.DIRECTOR_ACTIVE,new MutationId(new UUID(0,42)),post,new Epoch(0),Optional.empty());
        check(OwnershipRules.rollback(before,owner,new Epoch(1),new Epoch(0),post,Optional.of(post),true)==OwnershipRules.Rollback.ELIGIBLE,"exact CAS eligible");
        for(var cause:OwnershipRules.ExternalChange.values()) {
            var after=OwnershipRules.cede(before,cause,new Tick(2));
            check(after.epoch().equals(new Epoch(2)),"external edit increments epoch "+cause);
            check(after.header().revision().equals(new Revision(2)),"revision advanced "+cause);
            check(OwnershipRules.rollback(after,owner,new Epoch(1),new Epoch(0),post,Optional.of(post),true)!=OwnershipRules.Rollback.ELIGIBLE,"ABA same hash refused "+cause);
        }
        var yielded=OwnershipRules.cede(before,OwnershipRules.ExternalChange.PLAYER,new Tick(2));
        check(OwnershipRules.cede(yielded,OwnershipRules.ExternalChange.FLUID,new Tick(3)).disposition()==OwnershipDisposition.YIELDED_TO_PLAYER,"player tombstone survives fluid");
        check(OwnershipRules.rollback(before,owner,new Epoch(1),new Epoch(0),post,Optional.empty(),true)==OwnershipRules.Rollback.CONFLICT_ISOLATED,"unknown world state refused");
        check(OwnershipRules.rollback(before,owner,new Epoch(1),new Epoch(0),post,Optional.of(post),false)==OwnershipRules.Rollback.CONFLICT_ISOLATED,"unsafe inverse refused");
        check(OwnershipRules.rollback(before,new OwnerToken(new UUID(0,99)),new Epoch(1),new Epoch(0),post,Optional.of(post),true)==OwnershipRules.Rollback.CONFLICT_ISOLATED,"foreign owner refused");
    }
    public static void main(String[] args) throws Exception {
        concurrency(); renewal(); tuple(); policy(); ownership();
        System.out.println("PASS J05 pure domain assertions="+assertions);
        System.out.println("Coverage: T-CONC logical reservations; T-MUT CAS/cession rules only. No Minecraft, persistence, merge algebra, or writer-loss qualification.");
    }
}
