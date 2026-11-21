package fr.proutlost.director.persistence;

import fr.proutlost.director.api.codec.MetadataAttemptContract;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.integration.locations.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static fr.proutlost.director.persistence.MetadataAttemptResumeFixture.*;

/** Focused adversarial J04 probes. Declared synthetic, no native gameplay claim. */
public final class MetadataAttemptBoundsTests {
    private MetadataAttemptBoundsTests(){}
    private static Optional<Fingerprint> active(){return Optional.of(LocationTransfer.identity(prepare(1).manifest()));}
    private static JournalStore setup(Path root)throws Exception{
        Files.createDirectory(root);var store=open(root.resolve("store"));
        activateInitial(store,source(SOURCE,1),new Tick(33218));activateInitial(store,source(OTHER,1),new Tick(31813));
        var transfer=prepare(2);durable(store,new DurableFact(MetadataPreparationClock.fact(transfer.manifest(),ORIGINAL_CLOCK)),List.of(),"original pending source clock durable");sourcePages(store,transfer);return store;
    }
    private static RegionAggregate headerAndShape(RegionRecord before,Tick tick,List<Aabb> boxes){
        var h=before.header();return new RegionAggregate(new RegionRecord(new AggregateHeader(h.schemaVersion(),h.id(),h.revision(),h.lastSequence(),tick),before.regionId(),before.dimension(),before.status(),
            new SupportShape(boxes,before.supportShape().discs(),before.supportShape().routes()),before.anchorIds(),before.importance(),before.parentIds(),before.aliases(),before.membershipRevision(),before.personality(),before.memoryRefs()));
    }
    private static void timestamp(Path root,Path profile,boolean fixed)throws Exception{
        var store=setup(root);try{
            visit(store,profile,0,100561);visit(store,profile,99,110663);var transfer=prepare(2).manifest();var low=new Tick(100000);
            durable(store,new DurableFact(MetadataAttemptContract.reprepare(transfer,new Epoch(0),active(),low)),List.of(),"lower-than-head attempt declaration is syntactically valid but grants no timestamp regression");
            var correct=plan(store,source(SOURCE,2),source(SOURCE,1),new Tick(120000));
            for(var page:correct){List<AggregateState> states=new ArrayList<>();for(var state:page.states()){
                check(state instanceof RegionAggregate,"timestamp probe only modifies actual generated RegionRecords");var region=((RegionAggregate)state).value();states.add(headerAndShape(region,low,region.supportShape().boxes()));
            }var rewritten=new MetadataGenerationPage(page.generation(),page.sourceNamespace(),page.exportRevision(),page.exportDigest(),page.pageIndex(),page.pageCount(),states,page.beforeRevisions());
                var blob=pageBlob(rewritten);durable(store,new DurableFact(MetadataAttemptContract.stage(new Epoch(1),stage(transfer,rewritten,blob))),List.of(blob),"full source-correct but temporally stale proposal genuinely staged");}
            var ack=append(store,List.of(new DurableFact(MetadataAttemptContract.activate(new Epoch(1),new DurableMetadataActivation(transfer,active(),correct.size())))),List.of(),Map.of());
            System.out.println("TIMESTAMP_PROBE actualHeadBefore=110663 attemptClock=100000 allHeadRevisionsExact=true activation="+ack.ackStatus()+" sequence="+ack.durableSequence().value());
            if(fixed){rejected(ack,"J04 refuses source-correct exact-CAS metadata whose changed header moves actual time backwards");oldActive(store);
                check(selectedMemory(store,regionId(99)).regionRecords().get(regionId(99)).header().updatedTick().value()==110663,"refused proposal preserves actual newer RegionRecord timestamp");}
            else{check(ack.ackStatus()==AckStatus.DURABLE,"timestamp counterexample activation really committed with exact per-head CAS");
                check(selectedMemory(store,regionId(99)).regionRecords().get(regionId(99)).header().updatedTick().equals(low),"timestamp counterexample actually moves newer RegionRecord back to100000");
                throw new AssertionError("EXACT_REVISION_CAS_ALONE_ALLOWED_METADATA_TIMESTAMP_REGRESSION");}
        }finally{close(store);}
    }
    private static void retained(Path root)throws Exception{
        var store=setup(root);try{
            var transfer=prepare(2).manifest();var ordinary=plan(store,source(SOURCE,2),source(SOURCE,1),new Tick(90000)).getFirst();
            var baseline=((RegionAggregate)ordinary.states().getFirst()).value();List<Aabb> boxes=new ArrayList<>(baseline.supportShape().boxes());
            for(int n=1;n<=5000;n++)boxes.add(new Aabb(DIM,n*2,0,1,n*2+1,1,2));
            long retained=0;int durablePages=0;Epoch epoch=new Epoch(0);boolean refused=false;
            for(int n=0;n<150;n++){
                var tick=new Tick(90000+n);durable(store,new DurableFact(MetadataAttemptContract.reprepare(transfer,epoch,active(),tick)),List.of(),"successive private plan requires a fresh durable technical epoch");epoch=epoch.plus(1);
                var state=headerAndShape(baseline,tick,boxes);var page=new MetadataGenerationPage(LocationTransfer.identity(transfer),SOURCE,transfer.exportRevision(),transfer.exportDigest(),0,1,List.of(state),Map.of(state.key(),new Revision(1),new AggregateKey(AggregateKind.REGION_MEMORY,state.key().id()),new Revision(1)));
                var blob=pageBlob(page);check(blob.reference().bytes()>600_000&&blob.reference().bytes()<1_048_576,"declared large stage is >600KiB but within unchanged1MiB page codec");
                if(retained+blob.reference().bytes()>64L*1024*1024){
                    // An unrelated ordinary record can legitimately retain the
                    // exact bytes first. This must not erase this manifest's
                    // own cumulative quota when the page is later proposed.
                    var id=UUID.randomUUID();var unique=new UniqueUseRecord(new AggregateHeader(1,id,new Revision(1),new Sequence(0),tick),
                        new DefinitionId("fixture:attempt_quota_reference"),UniquenessScope.ONCE_PER_WORLD,WORLD.value(),UniqueStatus.SPENT_CONFIRMED,
                        Optional.empty(),new EventInstanceId(id),Optional.of(blob.reference()),Set.of());
                    durable(store,new DurableUnique(unique),List.of(blob),"unrelated declared synthetic uniqueness evidence pre-references exact bytes without granting metadata quota");
                }
                var before=store.metadataAttempt(transfer).orElseThrow();check(before.retainedStageBytes()==retained&&before.pages().isEmpty(),"reprepare preserves cumulative bytes and resets only current pages");
                var ack=append(store,List.of(new DurableFact(MetadataAttemptContract.stage(epoch,stage(transfer,page,blob)))),List.of(blob),Map.of());
                if(retained+blob.reference().bytes()>64L*1024*1024){
                    rejected(ack,"first page exceeding cumulative64MiB across attempts is rejected before current stage publication");
                    check(store.metadataAttempt(transfer).orElseThrow().equals(before),"over-cap rejection leaves current descriptor and retained bytes unchanged");refused=true;break;
                }
                check(ack.ackStatus()==AckStatus.DURABLE,"within-cumulative-cap stage genuinely durable");retained=Math.addExact(retained,blob.reference().bytes());durablePages++;
                check(store.metadataAttempt(transfer).orElseThrow().retainedStageBytes()==retained,"writer counts every unique historical attempt blob");
                if(n%10==0)System.out.println("RETAINED_BOUND_PROGRESS attempt="+epoch.value()+" bytes="+retained+" durablePages="+durablePages);
            }
            check(refused&&durablePages>70&&retained<=64L*1024*1024,"actual repeated attempts reach bounded retained-storage refusal without changing the configured cap");
            oldActive(store);check(!store.readOnlySafe()&&store.metadataCachedEntries()<=512,"capacity refusal preserves valid store authority and metadata cache cap512");
            check(store.metadataAttempt(transfer).orElseThrow().sourcePages().size()==transfer.pageHashes().size(),"all verified source fragments retained at cumulative-cap refusal");
            check(store.metadataPreparationTick(transfer).orElseThrow().equals(ORIGINAL_CLOCK),"original preparation clock remains88593 across many repréparations");
            await(store.checkpoint());close(store);store=null;
            // Replaying ~64MiB of verified historical JSON in a constrained
            // worldless JVM has a separate generous test timeout. This is not
            // a performance acceptance or a production timeout change.
            store=JournalStore.open(root.resolve("store"),WORLD,OPTIONS).toCompletableFuture().get(180,TimeUnit.SECONDS);
            check(store.metadataAttempt(transfer).orElseThrow().retainedStageBytes()==retained,"actual cumulative byte floor survives checkpoint and cold new session");oldActive(store);
            System.out.println("PASS MetadataAttemptBoundsTests mode=retained-bound assertions="+assertions+" durablePages="+durablePages+" retainedBytes="+retained+" cap="+(64L*1024*1024));
        }finally{if(store!=null)close(store);}
    }
    public static void main(String[] args)throws Exception{
        if(args.length!=3)throw new IllegalArgumentException("timestamp-before|timestamp-fixed|retained-bound|retained-recovery PRIVATE_ROOT PROFILE");
        var root=Path.of(args[1]).toAbsolutePath();var profile=Path.of(args[2]).toAbsolutePath();
        switch(args[0]){case "timestamp-before"->timestamp(root,profile,false);case "timestamp-fixed"->{timestamp(root,profile,true);System.out.println("PASS MetadataAttemptBoundsTests mode=timestamp-fixed assertions="+assertions);}case "retained-bound"->retained(root);case "retained-recovery"->{
            System.out.println("RETAINED_COLD_REOPEN_STARTED ownedSyntheticStore="+root);
            var store=JournalStore.open(root.resolve("store"),WORLD,OPTIONS).toCompletableFuture().get(180,TimeUnit.SECONDS);try{
                var state=store.metadataAttempt(prepare(2).manifest()).orElseThrow();check(state.retainedStageBytes()>60L*1024*1024&&state.retainedStageBytes()<=64L*1024*1024,"retained quota count survives a genuinely cold large-history replay");oldActive(store);
                System.out.println("PASS MetadataAttemptBoundsTests mode=retained-recovery assertions="+assertions+" retainedBytes="+state.retainedStageBytes()+" currentAttempt="+state.attempt().value());
            }finally{close(store);}
        }default->throw new IllegalArgumentException("mode");}
    }
}
