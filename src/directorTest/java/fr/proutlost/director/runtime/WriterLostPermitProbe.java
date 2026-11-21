package fr.proutlost.director.runtime;

import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.permission.CommitPermit;
import fr.proutlost.director.testkit.TestDurableStore;
import java.util.*;

/** Characterizes the frozen G0 boundary; this is NOT a physical qualification test. */
public final class WriterLostPermitProbe {
    private static int assertions;
    private record Fixture(TestDurableStore store, CommitPermit.Authority authority,
                           DurableBatch batch, PreparedSlice slice, PreparedSlice stop,
                           LiveValidationSnapshot live) {
        CommitPermit grant() {
            authority.submit(batch, live);
            check(authority.poll(batch.transactionId(), live).permit().isEmpty(), "no permit before ACK");
            check(store.force(batch.transactionId()).ackStatus() == AckStatus.DURABLE, "test ACK supplied");
            return authority.poll(batch.transactionId(), live).permit().orElseThrow();
        }
        LiveValidationSnapshot readOnly() {
            return new LiveValidationSnapshot(live.readSet(), live.loadedChunks(),
                    HealthMode.READ_ONLY_SAFE, live.now(), live.reservationGeneration());
        }
    }
    private static Fixture fixture() {
        var store = new TestDurableStore();
        var authority = new CommitPermit.Authority(store, 4);
        var dim = new DimensionId("minecraft:overworld");
        var footprint = new Footprint(dim, Set.of(new ChunkRef(dim, 0, 0)),
                Set.of(new BlockPosition(dim, 0, 64, 0)), List.of());
        var read = new ReadSet(Map.of(), Map.of(), Map.of());
        var blob = BlobContent.capture(new ResourceId("proutlost_director:probe"), 1, new byte[]{1});
        var instance = new EventInstanceId(new UUID(0, 2));
        var operation = new OperationToken(new UUID(0, 3));
        var impact = new EffectIntent(operation, new PrimitiveId("proutlost:controlled_water"), 1,
                new ControlledWater(new ResourceId("proutlost:test_water"), blob.reference(), 1, 8), footprint, instance);
        var stopIntent = new EffectIntent(new OperationToken(new UUID(0, 4)), impact.primitive(), 1,
                new ControlledWater(new ResourceId("proutlost:test_stop"), blob.reference(), 0, 8), footprint, instance);
        var budget = new BudgetVector(0.1, 1, 1, 0, 0, 1, 0, 0, 0, 0);
        var slice = new PreparedSlice(new SliceId(new UUID(0, 5)), operation, footprint, read, List.of(impact), budget);
        var stop = new PreparedSlice(new SliceId(new UUID(0, 6)), stopIntent.operationToken(), footprint, read, List.of(stopIntent), budget);
        var reservation = new ReservationToken(new ReservationId(new UUID(0, 7)), store.session().worldId(),
                instance, new Epoch(1), new Tick(200), List.of());
        var prepare = new PrepareRecord(new TransactionId(new UUID(0, 8)), operation, new Epoch(1), read,
                new Tick(200), slice, reservation, new ValidationResult(true, List.of(), read), List.of(), Set.of(),
                List.of(stopIntent), new EffectBlobs(Set.of(), blob.reference(), blob.reference(), Map.of(), Map.of()));
        var batch = new DurableBatch(prepare.transactionId(), store.session().storeGeneration(), Map.of(),
                List.of(new DurablePrepare(prepare)), List.of(blob), Optional.empty(), CommitPermit.Authority.prepareHash(prepare));
        return new Fixture(store, authority, batch, slice, stop,
                new LiveValidationSnapshot(read, footprint.loadedChunks(), HealthMode.NORMAL, new Tick(100), new Epoch(1)));
    }
    private static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
        assertions++;
    }
    private static void rejected(Runnable action, String expected, String label) {
        try { action.run(); } catch (IllegalArgumentException failure) {
            check(failure.getMessage().equals(expected), label + ": " + failure.getMessage());
            System.out.println("OBSERVED " + label + ": " + expected);
            return;
        }
        throw new AssertionError("Expected rejection: " + label);
    }
    public static void main(String[] args) {
        var used = fixture();
        var permit = used.grant();
        permit.consume(used.authority(), used.slice(), used.live());
        rejected(() -> permit.consume(used.authority(), used.stop(), used.readOnly()),
                "used permit or obsolete store session", "impact permit cannot authorize stop after impact");
        var reserved = fixture();
        var spare = reserved.grant();
        rejected(() -> spare.consume(reserved.authority(), reserved.slice(), reserved.readOnly()),
                "unsafe health", "even unused permit refuses READ_ONLY_SAFE");
        rejected(() -> spare.consume(reserved.authority(), reserved.stop(), reserved.live()),
                "different slice", "authorizedStabilization is not consumable as a stop slice");
        var unavailable = fixture();
        unavailable.authority().submit(unavailable.batch(), unavailable.live());
        unavailable.store().fail(unavailable.batch().transactionId());
        check(unavailable.authority().poll(unavailable.batch().transactionId(), unavailable.live()).permit().isEmpty(),
                "writer failure cannot issue replacement permit");
        System.out.println("PASS characterization assertions=" + assertions);
        System.out.println("BLOCKED normative writer-lost stabilization; no world execution or disk durability tested");
    }
}
