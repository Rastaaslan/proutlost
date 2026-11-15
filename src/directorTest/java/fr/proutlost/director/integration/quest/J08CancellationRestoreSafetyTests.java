package fr.proutlost.director.integration.quest;

import fr.proutlost.director.api.codec.*;
import fr.proutlost.director.api.model.*;
import java.util.*;
import java.util.concurrent.*;

/** TEST_ONLY: cancellation receipts cannot mint replay ACKs after WAL recovery. */
public final class J08CancellationRestoreSafetyTests {
    private J08CancellationRestoreSafetyTests() {}
    private static int checks;
    private static void check(boolean ok, String label) {
        checks++;
        if (!ok) throw new AssertionError(label);
    }
    private static DirectorFact variant(DirectorFact base, List<ScopeRef> scope,
            Optional<EventInstanceId> event, EvidenceStatus evidence, boolean durable,
            Map<String,JsonValue> payload) {
        return new DirectorFact(new FactId(UUID.randomUUID()), base.factType(), scope,
                event, evidence, payload, durable, Optional.empty());
    }
    public static void main(String[] args) throws Exception {
        var executor=Executors.newSingleThreadExecutor();
        try {
            var store=new J08Tests.MemoryStore();
            var adapter=new DurableQuestAdapter(store,new J08Tests.PullBus(store.session),
                    J08Tests.PRODUCER,executor,()->100,32,.5);
            J08Tests.await(adapter.restore());
            var original=J08Tests.request(new RequestId(UUID.randomUUID()),"receipt-target",
                    FulfillmentMode.ALLOW_EXISTING_FACT);
            var accepted=J08Tests.await(adapter.submit(original));
            var cancellation=new CancelRequest(accepted.requestId(),accepted.revision(),"receipt-key");
            var cancelled=J08Tests.await(adapter.cancel(cancellation));
            check(cancelled.state()==RequestStatus.CANCELLED,"real cancellation committed");
            var originalView=adapter.inspect(accepted.requestId());
            var record=store.records.stream().filter(r -> r instanceof DurableFact f &&
                    f.value().factType().equals(new FactType("proutlost_director:request_cancel_receipt")))
                    .map(r -> (DurableFact)r).findFirst().orElseThrow();
            var fact=record.value();
            var valid=Map.copyOf(fact.payload());
            check(store.records.remove(record),"remove TEST_ONLY fixture");
            var invalid=new ArrayList<DirectorFact>();
            invalid.add(variant(fact,fact.subjectRefs(),fact.eventId(),EvidenceStatus.POSSIBLE,true,valid));
            invalid.add(variant(fact,fact.subjectRefs(),fact.eventId(),EvidenceStatus.CONFIRMED,false,valid));
            invalid.add(variant(fact,List.of(new ScopeRef(ScopeKind.REGION,UUID.randomUUID())),
                    fact.eventId(),EvidenceStatus.CONFIRMED,true,valid));
            invalid.add(variant(fact,fact.subjectRefs(),Optional.of(new EventInstanceId(UUID.randomUUID())),
                    EvidenceStatus.CONFIRMED,true,valid));
            var foreignAck=new RequestAck(new RequestId(UUID.randomUUID()),cancelled.revision(),
                    cancelled.state(),cancelled.reasons(),cancelled.factRefs());
            var wrongAck=new HashMap<>(valid);
            wrongAck.put("ack",CanonicalJsonV1.decode(new ModelCodec<>(RequestAck.class).encode(foreignAck)));
            invalid.add(variant(fact,fact.subjectRefs(),fact.eventId(),EvidenceStatus.CONFIRMED,true,wrongAck));
            var badHash=new HashMap<>(valid);
            badHash.put("request_hash",new JsonValue.Text("b".repeat(64)));
            invalid.add(variant(fact,fact.subjectRefs(),fact.eventId(),EvidenceStatus.CONFIRMED,true,badHash));
            var badKey=new HashMap<>(valid);
            badKey.put("producer_key",new JsonValue.Text("forged-key"));
            invalid.add(variant(fact,fact.subjectRefs(),fact.eventId(),EvidenceStatus.CONFIRMED,true,badKey));
            var extra=new HashMap<>(valid);
            extra.put("unexpected",new JsonValue.Text("injected"));
            invalid.add(variant(fact,fact.subjectRefs(),fact.eventId(),EvidenceStatus.CONFIRMED,true,extra));
            for(var unsafe:invalid) {
                var forged=new DurableFact(unsafe);
                store.records.add(forged);
                try {
                    J08Tests.await(adapter.restore());
                    throw new AssertionError("invalid cancellation receipt accepted: "+unsafe);
                } catch(ExecutionException expected) {
                    check(expected.getCause() instanceof IllegalArgumentException,
                            "fail closed on untrusted cancellation receipt");
                }
                check(adapter.inspect(accepted.requestId()).equals(originalView),
                        "failed restore leaves previous projection unchanged");
                try {
                    J08Tests.await(adapter.cancel(cancellation));
                    throw new AssertionError("admission allowed after failed cancellation recovery");
                } catch(ExecutionException expected) {
                    check(expected.getCause() instanceof IllegalStateException,
                            "failed restore fences new admission");
                }
                check(store.records.remove(forged),"remove only injected TEST_ONLY fact");
                J08Tests.await(adapter.restore());
            }
            // Valid receipt may be paginated before the matching request.
            store.records.add(0,record);
            store.paged=true;
            J08Tests.await(adapter.restore());
            check(adapter.inspect(accepted.requestId()).equals(originalView),
                    "authentic receipt restored from page preceding its request");
            int appends=store.appendCalls.get();
            check(J08Tests.await(adapter.cancel(cancellation)).equals(cancelled),
                    "authentic cancellation replay ACK unchanged");
            check(store.appendCalls.get()==appends,"authentic replay performs no writes");
        } finally {
            executor.shutdownNow();
        }
        System.out.println("PASS J08 cancellation WAL restore safety assertions="+checks);
    }
}
