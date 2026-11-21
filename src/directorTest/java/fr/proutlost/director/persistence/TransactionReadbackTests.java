package fr.proutlost.director.persistence;

import fr.proutlost.director.api.model.*;
import fr.proutlost.director.runtime.ResultTransaction;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Actual private J04 transactions: absence is evidence only while a readback is available.
 * Synthetic facts, no Minecraft world, no canonical data, no effect or execution permission.
 */
public final class TransactionReadbackTests {
    private TransactionReadbackTests(){}
    private static final WorldId WORLD=new WorldId(new UUID(9208,1));
    private static final StoreOptions OPTIONS=new StoreOptions(1_048_620,4_194_304,16_777_216,33_554_432,32,0,128);
    private static final TransactionId REJECTED_ONLY=new TransactionId(new UUID(9208,2));
    private static final TransactionId COMMITTED=new TransactionId(new UUID(9208,3));
    private static int assertions;
    private static void check(boolean condition,String label){assertions++;if(!condition)throw new AssertionError(label);}
    private static <T>T await(CompletionStage<T> result)throws Exception{return result.toCompletableFuture().get(30,TimeUnit.SECONDS);}
    private static DirectorFact fact(int identity,String value){
        return new DirectorFact(new FactId(new UUID(9209,identity)),new FactType("fixture:transaction_readback"),
            List.of(new ScopeRef(ScopeKind.WORLD,WORLD.value())),Optional.empty(),EvidenceStatus.CONFIRMED,
            Map.of("synthetic_value",new JsonValue.Text(value)),true,Optional.empty());
    }
    private static DurableBatch batch(JournalStore store,TransactionId identity,DirectorFact value,Map<ScopeRef,Revision> expected){
        return ResultTransaction.batch(identity,store.session().storeGeneration(),expected,List.of(new DurableFact(value)),List.of());
    }
    private static JournalStore.TransactionReadback available(JournalStore store,TransactionId transaction){
        var result=store.transactionReadback(transaction).orElseThrow(()->new AssertionError("readback unavailable while actual store is healthy"));
        check(result.session().equals(store.session()),"available readback is bound to actual current session");
        check(result.cut().value()>0,"available readback identifies a real durable cut");
        return result;
    }
    private static void unavailableConvenience(JournalStore store,TransactionId identity){
        try{store.transactionReceipt(identity);throw new AssertionError("unavailable receipt collapsed into Optional.empty");}
        catch(IllegalStateException expected){assertions++;}
    }
    public static void main(String[] args)throws Exception{
        Path root=args.length==1?Path.of(args[0]):Files.createTempDirectory("P008-transaction-readback-synthetic-");Files.createDirectories(root);
        Path storePath=root.resolve("store");var store=await(JournalStore.open(storePath,WORLD,OPTIONS));
        CommitAck original;StoreSession firstSession;Sequence committedCut;
        try{
            check(!store.readOnlySafe(),"private actual J04 opened healthy");firstSession=store.session();
            var initial=available(store,REJECTED_ONLY);check(initial.receipt().isEmpty(),"initial available readback proves absence");
            check(store.transactionReceipt(REJECTED_ONLY).isEmpty(),"convenience absence is valid while actual readback is available");
            var invalid=batch(store,REJECTED_ONLY,fact(1,"CAS refusal"),Map.of(new ScopeRef(ScopeKind.WORLD,WORLD.value()),new Revision(99)));
            var rejection=await(store.append(invalid));
            check(rejection.ackStatus()==AckStatus.REJECTED&&rejection.durableSequence().value()==0,"wrong real CAS is rejected with sequence zero");
            var absent=available(store,REJECTED_ONLY);
            check(absent.session().equals(firstSession)&&store.session().equals(firstSession),"CAS refusal does not replace authority session");
            check(absent.cut().equals(initial.cut()),"pre-IO rejection does not invent a durable cut");
            check(absent.receipt().isEmpty(),"rejected never-committed transaction has a proven absent receipt");
            check(!store.readOnlySafe(),"semantic rejection leaves actual store healthy");
            System.out.println("CAS_REJECTED_SEQ="+rejection.durableSequence().value()+" AVAILABLE=true RECEIPT_PRESENT="+absent.receipt().isPresent());

            var first=batch(store,COMMITTED,fact(2,"original durable A"),Map.of());original=await(store.append(first));
            check(original.ackStatus()==AckStatus.DURABLE&&original.durableSequence().value()>0,"first identity/hash A genuinely commits");
            var known=available(store,COMMITTED);committedCut=known.cut();
            check(known.receipt().orElseThrow().equals(original),"operational lookup returns original exact durable ACK");
            check(known.receipt().orElseThrow().transactionHash().equals(first.transactionHash()),"original hash A retained");
            check(known.cut().compareTo(original.durableSequence())>=0,"receipt is proven within captured current cut");
            var changed=batch(store,COMMITTED,fact(3,"conflicting proposed B"),Map.of());
            check(!changed.transactionHash().equals(first.transactionHash()),"real canonical transaction producer creates distinct hash B");
            var conflict=await(store.append(changed));
            check(conflict.ackStatus()==AckStatus.REJECTED&&conflict.durableSequence().value()==0,"same ID with hash B is rejected at sequence zero");
            var retained=available(store,COMMITTED);
            check(retained.receipt().isPresent(),"REJECTED sequence zero never hides a prior real receipt");
            check(retained.receipt().orElseThrow().equals(original),"hash conflict retains original exact hash A ACK");
            check(!retained.receipt().orElseThrow().transactionHash().equals(changed.transactionHash()),"receipt is not filtered by the rejected attempt hash");
            check(retained.cut().equals(committedCut),"identity conflict does not advance durable state");
            check(store.transactionReceipt(COMMITTED).orElseThrow().equals(original),"convenience returns same original receipt");
            check(available(store,REJECTED_ONLY).receipt().isEmpty(),"unrelated commit does not turn earlier rejection into a receipt");
            System.out.println("SAME_ID_CONFLICT_SEQ="+conflict.durableSequence().value()+" RECEIPT_PRESENT=true ORIGINAL_HASH="+original.transactionHash().value()+" REJECTED_HASH="+changed.transactionHash().value());
            await(store.checkpoint());
        }finally{await(store.closeAsync());}
        check(store.transactionReadback(COMMITTED).isEmpty(),"closed store returns outer unavailable for committed identity");
        check(store.transactionReadback(REJECTED_ONLY).isEmpty(),"closed store returns outer unavailable for previously absent identity");
        unavailableConvenience(store,COMMITTED);unavailableConvenience(store,REJECTED_ONLY);
        System.out.println("CLOSED_AVAILABLE=false CONVENIENCE=THROWS");

        store=await(JournalStore.open(storePath,WORLD,OPTIONS));
        try{
            check(!store.readOnlySafe(),"checkpoint reopens as healthy actual J04");
            check(!store.session().equals(firstSession),"reopen publishes a different boot authority session");
            check(store.session().bootEpoch().compareTo(firstSession.bootEpoch())>0,"new boot epoch is proven");
            var restored=available(store,COMMITTED);
            check(restored.receipt().orElseThrow().equals(original),"new session returns exact original durable receipt");
            check(restored.cut().compareTo(committedCut)>0,"new session readback identifies the later durable session cut");
            check(available(store,REJECTED_ONLY).receipt().isEmpty(),"known absent identity remains provably absent after recovery");
            check(store.transactionReceipt(COMMITTED).orElseThrow().equals(original),"new-session convenience preserves original ACK");
            check(store.transactionReceipt(REJECTED_ONLY).isEmpty(),"new-session convenience absent result is backed by available evidence");
            System.out.println("COLD_NEW_SESSION=true ORIGINAL_RECEIPT_EXACT=true REJECTED_ONLY_RECEIPT_PRESENT=false");
        }finally{await(store.closeAsync());}
        System.out.println("PASS TransactionReadbackTests assertions="+assertions+" scope=ACTUAL_J04_SYNTHETIC_WORLDLESS_NO_NATIVE_ACK_OR_PERMISSION");
    }
}
