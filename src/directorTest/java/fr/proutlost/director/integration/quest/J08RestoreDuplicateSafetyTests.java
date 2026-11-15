package fr.proutlost.director.integration.quest;

import fr.proutlost.director.api.model.*;
import java.util.*;
import java.util.concurrent.*;

/** TEST_ONLY: a conflicting WAL replay must never silently select one credential. */
public final class J08RestoreDuplicateSafetyTests {
    private J08RestoreDuplicateSafetyTests() {}
    private static int checks;
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static void mustFence(DurableQuestAdapter adapter, DirectorRequest request, RequestView prior)
            throws Exception {
        try {
            J08Tests.await(adapter.restore());
            throw new AssertionError("conflicting WAL replay was accepted");
        } catch (ExecutionException expected) {
            check(expected.getCause() instanceof IllegalArgumentException,
                    "restore rejects inconsistent durable WAL projection");
        }
        check(adapter.inspect(prior.state().requestId()).equals(prior),
                "failed restore leaves last published read projection intact");
        try {
            J08Tests.await(adapter.submit(request));
            throw new AssertionError("failed WAL restore enabled an admission");
        } catch (ExecutionException expected) {
            check(expected.getCause() instanceof IllegalStateException,
                    "failed restore remains fail-closed");
        }
    }
    public static void main(String[] args) throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        try {
            var store = new J08Tests.MemoryStore();
            var adapter = new DurableQuestAdapter(store, new J08Tests.PullBus(store.session),
                    J08Tests.PRODUCER, executor, () -> 100, 32, .5);
            J08Tests.await(adapter.restore());
            var request = J08Tests.request(new RequestId(UUID.randomUUID()), "dup-restore",
                    FulfillmentMode.ALLOW_EXISTING_FACT);
            J08Tests.await(adapter.submit(request));
            var prior = adapter.inspect(RequestContracts.id(request.payload()));
            var originalInbox = store.records.stream()
                    .filter(r -> r instanceof DurableInbox).map(r -> ((DurableInbox) r).value())
                    .findFirst().orElseThrow();
            var conflictInbox = new DurableInbox(new InboxRecord(originalInbox.consumerId(),
                    originalInbox.messageId(), new Fingerprint("b".repeat(64)),
                    originalInbox.contiguousCursor(), originalInbox.revision()));
            store.records.add(conflictInbox);
            mustFence(adapter, request, prior);
            check(store.records.remove(conflictInbox), "remove injected inbox conflict only");
            J08Tests.await(adapter.restore());
            var replay = J08Tests.await(adapter.submit(request));
            check(replay.state() == prior.state().status() && replay.revision().equals(prior.revision()),
                    "authentic identical replay still works");

            var original = store.records.stream().filter(r -> r instanceof DurableRequest)
                    .map(r -> (DurableRequest) r).findFirst().orElseThrow();
            var s = original.request().state();
            var forgedState = new DirectorRequestState(s.header(), s.requestId(), s.producerId(),
                    s.producerKey(), s.kind(), s.selector(), s.scope(), s.constraints(),
                    s.fulfillment(), RequestStatus.WAITING_SAFETY, s.eligibleWaitTicks(),
                    s.expiresTick(), s.resultFactIds(), s.lastReasonCodes(), s.schedulingPolicy());
            var forgedView = new RequestView(original.request().request(), forgedState,
                    original.request().revision());
            var duplicate = new DurableRequest(original.original(), forgedView,
                    original.idempotenceHash(), original.schedulingPolicyHash());
            store.records.add(duplicate);
            mustFence(adapter, request, prior);
            check(store.records.remove(duplicate), "remove injected same-revision conflict only");
            J08Tests.await(adapter.restore());
            check(adapter.inspect(prior.state().requestId()).equals(prior),
                    "valid restore after removing conflicts");

            // An older forged revision can be appended *after* a valid newer
            // revision. Checking only against the latest view misses it.
            var advanced = J08Tests.await(adapter.update(prior.state().requestId(), prior.revision(),
                    RequestStatus.WAITING_SAFETY, List.of()));
            check(advanced.revision().value() == prior.revision().value() + 1,
                    "valid next revision was durably written");
            var latest = adapter.inspect(prior.state().requestId());
            J08Tests.await(adapter.restore());
            check(adapter.inspect(prior.state().requestId()).equals(latest),
                    "valid mixed-revision replay still works");
            store.records.add(duplicate); // forged revision 1 comes after authentic revision 2
            mustFence(adapter, request, latest);
            check(store.records.remove(duplicate), "remove delayed old-revision conflict");
            J08Tests.await(adapter.restore());
            check(adapter.inspect(prior.state().requestId()).equals(latest),
                    "valid replay after delayed-conflict removal");
        } finally {
            executor.shutdownNow();
        }
        System.out.println("PASS J08 conflicting WAL duplicate assertions=" + checks);
    }
}
