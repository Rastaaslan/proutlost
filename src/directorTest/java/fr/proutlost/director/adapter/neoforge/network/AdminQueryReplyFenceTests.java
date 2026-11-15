package fr.proutlost.director.adapter.neoforge.network;

import fr.proutlost.director.api.error.ReasonCode;
import fr.proutlost.director.api.model.*;
import java.util.*;

/** Adversarial, executable J07 real-wire response boundary tests. */
public final class AdminQueryReplyFenceTests {
    private static int assertions;
    private AdminQueryReplyFenceTests() {}

    private static void check(boolean ok, String message) {
        assertions++;
        if (!ok) throw new AssertionError(message);
    }

    private static AdminQuery query(Optional<Revision> expected, int limit) {
        return new AdminQuery(new SessionId(new UUID(1, 1)),
            new RequestId(new UUID(2, 2)), AdminPanel.WORLD, new WorldFilter(),
            Optional.empty(), expected, limit);
    }

    private static AdminSnapshot snapshot(AdminQuery query, Revision revision,
                                          List<AdminRow> rows, Optional<String> cursor,
                                          Set<AdminOperation> caps) {
        return new AdminSnapshot(query.requestId(), revision, new Tick(7),
            new Sequence(8), rows, cursor, caps);
    }

    private static void rejected(AdminQuery query,
                                 AdminQueryReplyFence.Reply reply,
                                 AdminStatus status, ReasonCode reason) {
        check(reply.snapshot().isEmpty(), "rejected J07 read published data");
        var error = reply.rejection().orElseThrow();
        check(error.commandId().equals(query.requestId()), "lost request correlation");
        check(error.status() == status, "wrong rejection status");
        check(error.reasonCodes().equals(List.of(reason)), "wrong rejection reason");
        check(error.newRevision().isEmpty() && error.resultRef().isEmpty(),
            "failed J07 read leaked metadata");
    }

    public static void main(String[] args) {
        var query = query(Optional.empty(), 1);
        var empty = snapshot(query, new Revision(1), List.of(),
            Optional.empty(), Set.of());
        var ok = AdminQueryReplyFence.read(query, () -> Optional.of(empty));
        check(ok.rejection().isEmpty(), "good read denied");
        check(ok.snapshot().orElseThrow().equals(empty), "good read mutated");

        var tagged = new AdminQuery(query.actorSession(), new RequestId(new UUID(2, 3)), AdminPanel.MEMORY,
            new RegionFilter(Optional.empty(), Set.of(new TagId("test:unindexed"))),
            Optional.empty(), Optional.empty(), 1);
        rejected(tagged, AdminQueryReplyFence.read(tagged, () -> { throw new AssertionError("unindexed tag query read projection"); }),
            AdminStatus.REJECTED, ReasonCode.CAPABILITY_MISSING);

        var nativeRead=snapshot(query,new Revision(1),List.of(),Optional.empty(),
            fr.proutlost.director.admin.server.NativeReadCommandFence.supported());
        var readable=AdminQueryReplyFence.read(query,()->Optional.of(nativeRead));
        check(readable.snapshot().filter(nativeRead::equals).isPresent()
            &&readable.rejection().isEmpty(),"actual native read-command capabilities denied");
        var mixed=new HashSet<>(nativeRead.capabilities());mixed.add(AdminOperation.TRIGGER);
        rejected(query,AdminQueryReplyFence.read(query,()->Optional.of(
            snapshot(query,new Revision(1),List.of(),Optional.empty(),mixed))),
            AdminStatus.FAILED_SAFE,ReasonCode.INTERNAL_FAULT);
        rejected(query, AdminQueryReplyFence.read(query, Optional::empty),
            AdminStatus.REJECTED, ReasonCode.STALE_CONTEXT);
        rejected(query, AdminQueryReplyFence.read(query, () -> {
            throw new IllegalStateException("private J04 WAL details must not leak");
        }), AdminStatus.FAILED_SAFE, ReasonCode.INTERNAL_FAULT);
        rejected(query, AdminQueryReplyFence.read(query, () -> null),
            AdminStatus.FAILED_SAFE, ReasonCode.INTERNAL_FAULT);
        rejected(query, AdminQueryReplyFence.read(query,
            () -> Optional.of(new AdminSnapshot(new RequestId(new UUID(9, 9)),
                new Revision(1), new Tick(7), new Sequence(8), List.of(),
                Optional.empty(), Set.of()))),
            AdminStatus.FAILED_SAFE, ReasonCode.INTERNAL_FAULT);
        rejected(query, AdminQueryReplyFence.read(query,
            () -> Optional.of(snapshot(query, new Revision(1), List.of(),
                Optional.empty(), Set.of(AdminOperation.TRIGGER)))),
            AdminStatus.FAILED_SAFE, ReasonCode.INTERNAL_FAULT);
        rejected(query, AdminQueryReplyFence.read(query,
            () -> Optional.of(snapshot(query, new Revision(1), List.of(),
                Optional.of("X".repeat(513)), Set.of()))),
            AdminStatus.FAILED_SAFE, ReasonCode.INTERNAL_FAULT);

        var row = new AdminRow(new ScopeRef(ScopeKind.WORLD, new UUID(3, 3)),
            new Revision(1), new Tick(7),
            new PublicStatusData(HealthMode.READ_ONLY_SAFE, true, Set.of()));
        rejected(query, AdminQueryReplyFence.read(query,
            () -> Optional.of(snapshot(query, new Revision(1),
                List.of(row, row), Optional.empty(), Set.of()))),
            AdminStatus.FAILED_SAFE, ReasonCode.INTERNAL_FAULT);

        var revision = query(Optional.of(new Revision(2)), 1);
        rejected(revision, AdminQueryReplyFence.read(revision,
            () -> Optional.of(snapshot(revision, new Revision(1),
                List.of(), Optional.empty(), Set.of()))),
            AdminStatus.REJECTED, ReasonCode.STALE_CONTEXT);

        var bigRow = new AdminRow(new ScopeRef(ScopeKind.WORLD, new UUID(3, 3)),
            new Revision(1), new Tick(7),
            new PublicStatusData(HealthMode.READ_ONLY_SAFE, true, Set.of()));
        // Confirm a valid bounded one-row page remains acceptable.
        var bounded = AdminQueryReplyFence.read(query,
            () -> Optional.of(snapshot(query, new Revision(1),
                List.of(bigRow), Optional.empty(), Set.of())));
        check(bounded.snapshot().isPresent() && bounded.rejection().isEmpty(),
            "bounded one-row read unexpectedly rejected");

        var next = query(Optional.of(new Revision(2)), 1);
        var goodCursor = AdminQueryReplyFence.read(next,
            () -> Optional.of(snapshot(next, new Revision(2),
                List.of(row), Optional.of("A".repeat(512)), Set.of())));
        check(goodCursor.snapshot().isPresent(), "boundary 512 cursor rejected");

        // The physical client reuses AdminCommandResult for an outstanding
        // read query; every failure state must clear that pending F8 request.
        for (var status : List.of(AdminStatus.REJECTED, AdminStatus.DEFERRED,
                                  AdminStatus.FAILED_SAFE)) {
            var result = new AdminCommandResult(query.requestId(), status,
                List.of(ReasonCode.INTERNAL_FAULT), Optional.empty(), Optional.empty());
            check(AdminQueryReplyFence.readFailure(result)
                .equals(Optional.of(ReasonCode.INTERNAL_FAULT)),
                "read failure remains pending after " + status);
        }
        var denied = new AdminCommandResult(query.requestId(), AdminStatus.REJECTED,
            List.of(ReasonCode.AUTH_DENIED), Optional.empty(), Optional.empty());
        check(AdminQueryReplyFence.readFailure(denied).equals(Optional.of(ReasonCode.AUTH_DENIED)),
            "non-operator F8 read rejection completes the client pending query");
        for (var status : List.of(AdminStatus.SUCCEEDED, AdminStatus.PENDING)) {
            var result = new AdminCommandResult(query.requestId(), status,
                List.of(ReasonCode.INTERNAL_FAULT), Optional.empty(), Optional.empty());
            check(AdminQueryReplyFence.readFailure(result).isEmpty(),
                "non-error status falsely rejected query " + status);
        }
        for (var status : List.of(AdminStatus.REJECTED, AdminStatus.DEFERRED,
                                  AdminStatus.FAILED_SAFE)) {
            check(AdminQueryReplyFence.readFailure(new AdminCommandResult(
                query.requestId(), status, List.of(),
                Optional.empty(), Optional.empty()))
                .equals(Optional.of(ReasonCode.INTERNAL_FAULT)),
                "empty reason list must release pending read as internal failure " + status);
        }

        System.out.println("ADMIN_J07_NATIVE_READ_FENCE_PASS assertions=" + assertions);
    }
}
