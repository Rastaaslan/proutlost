package fr.proutlost.director.persistence;

import fr.proutlost.director.api.codec.MetadataAttemptContract;
import fr.proutlost.director.api.codec.ModelCodec;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.integration.locations.LocationTransfer;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static fr.proutlost.director.persistence.MetadataAttemptResumeFixture.*;

/** Real J04 forced source/stage boundaries in distinct abruptly stopped JVMs.
 * Worldless declared synthetic data, real MemoryReducer. Not native Minecraft QA.
 */
public final class MetadataAttemptResumeTests {
    private static final Epoch A0=new Epoch(0),A1=new Epoch(1),A2=new Epoch(2);
    private static final Tick T1=new Tick(120000),T2=new Tick(150000);
    private static final ModelCodec<RegionMemory> MEMORY=new ModelCodec<>(RegionMemory.class);
    private MetadataAttemptResumeTests(){}
    private static Optional<Fingerprint> oldGeneration(){return Optional.of(LocationTransfer.identity(prepare(1).manifest()));}
    private static DurableFact stageFact(Epoch attempt,LocationTransferManifest transfer,MetadataGenerationPage page,BlobContent blob){return new DurableFact(MetadataAttemptContract.stage(attempt,stage(transfer,page,blob)));}
    private static DurableFact activation(Epoch attempt,int count){return new DurableFact(MetadataAttemptContract.activate(attempt,new DurableMetadataActivation(prepare(2).manifest(),oldGeneration(),count)));}
    private static void descriptor(JournalStore store,Epoch epoch,Tick clock,int pageCount,int pages)throws Exception{
        var transfer=prepare(2).manifest();var state=store.metadataAttempt(transfer).orElseThrow();
        check(state.session().equals(store.session())&&state.cut().value()>0,"current attempt descriptor is bound to exact live session/cut");
        check(state.transfer().equals(transfer)&&state.attempt().equals(epoch)&&state.plannedTick().equals(Optional.of(clock)),"same unchanged source manifest has exact current technical epoch/clock");
        check(state.pageCount()==pageCount&&state.pages().size()==pages&&state.sourcePages().size()==transfer.pageHashes().size(),"current-only counters distinguish retained source from metadata stage pages");
        check(state.retainedStageBytes()>=0&&state.retainedStageBytes()<=64L*1024*1024,"repeated attempts retain bounded stage storage accounting");
        check(store.metadataPreparationTick(transfer).orElseThrow().equals(ORIGINAL_CLOCK),"original88593 source preparation remains immutable historical evidence");
        check(store.metadataClockFloor(store.metadataSnapshot().orElseThrow()).orElseThrow().compareTo(clock)>=0,"J04 clock floor includes current durable attempt without private files");
    }
    private static void reprepare(JournalStore store,Epoch before,Tick clock)throws Exception{
        var transfer=prepare(2).manifest();var old=store.metadataAttempt(transfer).orElseThrow();
        var wrong=new DurableFact(MetadataAttemptContract.reprepare(transfer,before,Optional.of(new Fingerprint("e".repeat(64))),clock));
        rejected(append(store,List.of(wrong),List.of(),Map.of()),"wrong active-generation CAS refuses reprepare without changing attempt");
        check(store.metadataAttempt(transfer).orElseThrow().equals(old),"rejected reprepare leaves descriptor cut and state unchanged");
        var fact=new DurableFact(MetadataAttemptContract.reprepare(transfer,before,oldGeneration(),clock));
        durable(store,fact,List.of(),"same source export reprepare is forced atomically by J04");
        descriptor(store,before.plus(1),clock,-1,0);oldActive(store);
        durable(store,fact,List.of(),"identical technical reprepare fact is idempotent");
        descriptor(store,before.plus(1),clock,-1,0);
        var changed=new DurableFact(MetadataAttemptContract.reprepare(transfer,before,oldGeneration(),clock.plus(1)));
        rejected(append(store,List.of(changed),List.of(),Map.of()),"same reprepare fact identity cannot change planned clock");
        var stale=new DurableFact(MetadataAttemptContract.reprepare(transfer,new Epoch(9),oldGeneration(),clock.plus(1)));
        rejected(append(store,List.of(stale),List.of(),Map.of()),"obsolete/wrong expected attempt cannot reset current metadata pages");
    }
    private static void sourceResumeAndStageCrash(Path root,Path profile)throws Exception{
        var store=open(root.resolve("store"));oldActive(store);descriptor(store,A0,ORIGINAL_CLOCK,-1,0);
        visit(store,profile,0,100561);visit(store,profile,99,110663);staleClockRefused(store);
        reprepare(store,A0,T1);var transfer=prepare(2).manifest();var pages=plan(store,source(SOURCE,2),source(SOURCE,1),T1);
        check(pages.size()>1,"actual fresh plan exceeds one native metadata page");
        var page=pages.getFirst();var blob=pageBlob(page);var fact=stageFact(A1,transfer,page,blob);
        var ack=durable(store,fact,List.of(blob),"current attempt1 metadata page0 genuinely durable");
        check(ack.forcedBlobs().equals(Set.of(blob.reference()))&&DurableBlobReferences.collect(fact).equals(Set.of(blob.reference())),"typed stage fact participates in writer and exact ACK blob references");
        Files.write(root.resolve("old-stage-ack.json"),new ModelCodec<>(CommitAck.class).encode(ack));
        Files.write(root.resolve("old-stage-fact.json"),new ModelCodec<>(DurableFact.class).encode(fact));
        Files.write(root.resolve("old-stage-blob.json"),new ModelCodec<>(BlobRef.class).encode(blob.reference()));
        descriptor(store,A1,T1,pages.size(),1);oldActive(store);
        check(await(store.readStagedMetadataPage(transfer,A0,0)).isEmpty(),"old epoch0 cannot read current epoch1 staged bodies");
        rejected(append(store,List.of(stage(transfer,page,blob)),List.of(blob),Map.of()),"old legacy stage cannot mutate current epoch1");
        System.out.println("STAGE_CRASH_DURABLE pid="+ProcessHandle.current().pid()+" attempt=1 clock=120000 stagePages=1 sourcePages="+transfer.pageHashes().size()+" sequence="+ack.durableSequence().value());
        System.out.flush();Runtime.getRuntime().halt(78);
    }
    private static void stageResumeAndActivate(Path root,Path profile)throws Exception{
        var store=open(root.resolve("store"));try{
            var transfer=prepare(2).manifest();oldActive(store);var count=plan(store,source(SOURCE,2),source(SOURCE,1),T1).size();descriptor(store,A1,T1,count,1);
            var oldPage=await(store.readStagedMetadataPage(transfer,A1,0)).orElseThrow();
            // Complete the retained attempt from its still-valid current cut,
            // then create genuine newer J01 heads before its visibility CAS.
            var oldPages=plan(store,source(SOURCE,2),source(SOURCE,1),T1);check(oldPage.equals(oldPages.getFirst()),"stage0 bytes survive abrupt JVM boundary exactly");
            for(var page:oldPages.subList(1,oldPages.size())){var blob=pageBlob(page);durable(store,stageFact(A1,transfer,page,blob),List.of(blob),"remaining current attempt1 pages durable before deliberate intervening J01 visits");}
            var first=visit(store,profile,0,130000);var far=visit(store,profile,99,140000);
            rejected(append(store,List.of(activation(A1,count)),List.of(),Map.of()),"complete attempt1 activation refuses actual stale per-head CAS");oldActive(store);
            var beforeBytes=store.metadataAttempt(transfer).orElseThrow().retainedStageBytes();check(beforeBytes>0,"first attempt retained-stage storage is counted before reprepare");
            reprepare(store,A1,T2);check(store.metadataAttempt(transfer).orElseThrow().retainedStageBytes()==beforeBytes,"reprepare preserves historical stage-byte accounting across attempts");
            check(await(store.readStagedMetadataPage(transfer,A1,0)).isEmpty()&&await(store.readStagedMetadataPage(transfer,A2,0)).isEmpty(),"old attempt heads are no longer current and new attempt has no pages");
            var historicalFact=new ModelCodec<>(DurableFact.class).decode(Files.readAllBytes(root.resolve("old-stage-fact.json")));
            durable(store,historicalFact,List.of(),"exact old durable stage fact remains an idempotent historical replay");
            descriptor(store,A2,T2,-1,0);
            check(await(store.readStagedMetadataPage(transfer,A2,0)).isEmpty(),"old durable replay cannot populate current attempt2 pages");
            var oldAck=new ModelCodec<>(CommitAck.class).decode(Files.readAllBytes(root.resolve("old-stage-ack.json")));
            check(store.transactionReceipt(oldAck.transactionId()).orElseThrow().equals(oldAck),"old transaction receipt remains exactly the original durable sequence/hash");
            rejected(append(store,List.of(activation(A1,count)),List.of(),Map.of()),"old attempt1 activation has no current authority after reprepare");
            var pages=plan(store,source(SOURCE,2),source(SOURCE,1),T2);
            for(var page:pages){var blob=pageBlob(page);durable(store,stageFact(A2,transfer,page,blob),List.of(blob),"new attempt2 rebuilt metadata page durable at fresh exact guards");}
            descriptor(store,A2,T2,pages.size(),pages.size());oldActive(store);
            durable(store,activation(A2,pages.size()),List.of(),"complete current attempt2 atomically activates exact same R2 source revision/digest");
            check(selectedMemory(store,regionId(0)).regions().getFirst().equals(first)&&selectedMemory(store,regionId(99)).regions().getFirst().equals(far),"activation preserves both genuine current J01 RegionMemory bodies byte-for-byte");
            Files.write(root.resolve("first-memory.json"),MEMORY.encode(first));Files.write(root.resolve("far-memory.json"),MEMORY.encode(far));
            historicalBlob(store,root);verifyR2(store,root);await(store.checkpoint());await(store.evictVerifiedCheckpointRows());
            System.out.println("R2_ACTIVATED pid="+ProcessHandle.current().pid()+" attempt=2 originalClock=88593 plannedClock=150000 sameExportRevision=2 assertions="+assertions);
        }finally{close(store);}
    }
    private static void historicalBlob(JournalStore store,Path root)throws Exception{
        var ref=new ModelCodec<>(BlobRef.class).decode(Files.readAllBytes(root.resolve("old-stage-blob.json")));
        var read=await(store.readBlobs(new BlobReadRequest(WORLD,store.session().storeGeneration(),Set.of(ref))));
        check(read.status()==AckStatus.DURABLE&&read.contents().size()==1&&read.contents().getFirst().reference().equals(ref),"old superseded stage blob is retained and independently verified after reprepare");
        var oldPage=PAGE.decode(read.contents().getFirst().bytes());check(oldPage.states().stream().allMatch(state->state.header().updatedTick().equals(T1)),"historical stage bytes retain old attempt120000 clock rather than being overwritten");
    }
    private static void verifyR2(JournalStore store,Path root)throws Exception{
        var snapshot=store.metadataSnapshot().orElseThrow();var transfer=prepare(2).manifest();
        check(snapshot.generations().get(SOURCE).equals(LocationTransfer.identity(transfer)),"active source generation remains semantic R2 identity, never technical attempt identity");
        check(await(store.readMetadataSource(snapshot,SOURCE)).orElseThrow().equals(source(SOURCE,2)),"exact source R2 revision/digest/body survives current activation");
        check(await(store.readMetadataSource(snapshot,OTHER)).orElseThrow().equals(source(OTHER,1)),"other-source bytes preserved throughout attempts and activation");
        var keys=new HashSet<AggregateKey>();Optional<AggregateKey> cursor=Optional.empty();
        do{var page=store.metadataKeysPage(snapshot,Set.of(AggregateKind.REGION,AggregateKind.REGION_MEMORY),cursor,96).orElseThrow();check(page.keys().size()<=96,"current-key inspection remains bounded");for(var key:page.keys())check(keys.add(key),"current visible key appears exactly once");cursor=page.next();}while(cursor.isPresent());
        check(keys.size()==202,"all100R2 regions plus unchanged other region and all memories visible, no partial catalogue");
        var first=selectedMemory(store,regionId(0));var far=selectedMemory(store,regionId(99));
        check(first.regions().getFirst().equals(MEMORY.decode(Files.readAllBytes(root.resolve("first-memory.json"))))&&far.regions().getFirst().equals(MEMORY.decode(Files.readAllBytes(root.resolve("far-memory.json")))),"both current VISIT bodies survive exact readback/cold reconstruction");
        check(first.regionRecords().get(regionId(0)).header().updatedTick().equals(T2)&&far.regionRecords().get(regionId(99)).header().updatedTick().equals(T2),"new RegionRecord headers use durable fresh attempt clock with no regression");
        check(first.regions().getFirst().header().updatedTick().value()==130000&&far.regions().getFirst().header().updatedTick().value()==140000,"RegionMemory independent timestamps are preserved rather than rewritten by import");
        check(store.metadataPreparationTick(transfer).orElseThrow().equals(ORIGINAL_CLOCK)&&store.metadataClockFloor(snapshot).orElseThrow().compareTo(T2)>=0,"historical source clock and newer durable global floor coexist after activation");
        check(store.metadataCachedEntries()<=512,"native J04 metadata hydration cache stays within unchanged512 cap");
    }
    private static void verifyCold(Path root,Path profile)throws Exception{
        var store=open(root.resolve("store"));try{verifyR2(store,root);historicalBlob(store,root);
            var actual=visit(store,profile,0,160000);check(actual.header().revision().value()==4,"next actual J01 MemoryReducer remains admissible after metadata pivot");
            check(selectedMemory(store,regionId(0)).regionRecords().get(regionId(0)).header().revision().value()==5,"post-pivot RegionRecord and RegionMemory revision domains advance coherently");
            var rows=chronology(store);check(rows[0]==2&&rows[1]>=6&&rows[2]==1,"chronology preserves both unique reprepare attempts, old/current page receipts and only successful activation");
            await(store.checkpoint());await(store.evictVerifiedCheckpointRows());
        }finally{close(store);}
        store=open(root.resolve("store"));try{historicalBlob(store,root);check(selectedMemory(store,regionId(0)).regions().getFirst().header().revision().value()==4,"post-pivot genuine J01 progress survives another checkpoint/new session");}
        finally{close(store);}
        System.out.println("PASS MetadataAttemptResumeTests assertions="+assertions+" scope=ACTUAL_J04_MULTIJVM_ABRUPT_SOURCE_STAGE_REAL_MEMORY_REDUCER_NO_NATIVE_GAMEPLAY");
    }
    private static long[] chronology(JournalStore store)throws Exception{
        Set<FactId> reprepares=new HashSet<>(),stages=new HashSet<>(),activations=new HashSet<>();Optional<String> cursor=Optional.empty();do{
            var query=new StoreQuery(WORLD,Set.of(),new Sequence(0),cursor,128);var read=await(store.read(query));ReadContracts.validate(read,query,store.session());check(read.status()==ReadStatus.AVAILABLE,"verified chronological J04 page available");
            for(var record:read.page().orElseThrow().records())if(record instanceof DurableFact fact){if(fact.value().factType().equals(MetadataAttemptContract.REPREPARE))reprepares.add(fact.value().factId());else if(fact.value().factType().equals(MetadataAttemptContract.STAGE))stages.add(fact.value().factId());else if(fact.value().factType().equals(MetadataAttemptContract.ACTIVATE))activations.add(fact.value().factId());}
            cursor=read.page().orElseThrow().nextCursor();
        }while(cursor.isPresent());return new long[]{reprepares.size(),stages.size(),activations.size()};
    }
    public static void main(String[] args)throws Exception{
        if(args.length!=3)throw new IllegalArgumentException("all|seed-crash|a1-crash|a2-activate|cold-verify NEW_PRIVATE_ROOT PROFILE");
        var root=Path.of(args[1]).toAbsolutePath();var profile=Path.of(args[2]).toAbsolutePath();
        switch(args[0]){
            case "all"->{Files.createDirectory(root);String[] phases={"seed-crash","a1-crash","a2-activate","cold-verify"};int[] exits={77,78,0,0};for(int n=0;n<phases.length;n++){
                var command=List.of(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",System.getProperty("java.class.path"),MetadataAttemptResumeTests.class.getName(),phases[n],root.toString(),profile.toString());
                var process=new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(root.resolve(phases[n]+".log").toFile()).start();
                check(process.waitFor(60,TimeUnit.SECONDS),"bounded child JVM phase finishes "+phases[n]);check(process.exitValue()==exits[n],"child phase "+phases[n]+" expected exit"+exits[n]+" actual"+process.exitValue()+" log="+root.resolve(phases[n]+".log"));
                System.out.print(Files.readString(root.resolve(phases[n]+".log")));
            }System.out.println("PASS MetadataAttemptResumeTests controllerAssertions="+assertions+" distinctChildJvms=4 abruptBoundaries=2");}
            case "seed-crash"->seedSourceCrash(root.resolve("store"));
            case "a1-crash"->sourceResumeAndStageCrash(root,profile);
            case "a2-activate"->stageResumeAndActivate(root,profile);
            case "cold-verify"->verifyCold(root,profile);
            default->throw new IllegalArgumentException("phase");
        }
    }
}
