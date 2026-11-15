package fr.proutlost.worldprep;

import fr.proutlost.contract.director.*;
import fr.proutlost.worldprep.director.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** PROPOSAL tests, NOT EXECUTED. Consumers here are deliberate test doubles. */
class CatalogImportAdmissionGateTest {
    private static final String SHA="a".repeat(64);
    private static CatalogSnapshot source() {
        var fixtures=new PhysicalCatalogExporterTest();
        return StrictCodec.decode(fixtures.export(List.of(fixtures.disk)).json(),CatalogSnapshot.class);
    }
    private static CatalogSnapshot different(CatalogSnapshot s) {
        var raw=new CatalogSnapshot(s.world_id(),s.schema_version(),s.build_id(),new Revision("p002-different"),
            new Digest("0".repeat(64)),s.state(),s.validated_at(),s.validation_evidence(),s.regions(),s.entries());
        return new CatalogSnapshot(raw.world_id(),raw.schema_version(),raw.build_id(),raw.catalog_revision(),
            StrictCodec.contentDigest(raw),raw.state(),raw.validated_at(),raw.validation_evidence(),raw.regions(),raw.entries());
    }
    private static CatalogImportProbe probe(AtomicInteger calls,Supplier<Optional<CatalogSnapshot>> answer) {
        return new CatalogImportProbe() {
            public CatalogSnapshot importAndReadAdmitted(String json,Id world,Id build) {
                throw new AssertionError("Async probe must not use the legacy blocking method");
            }
            public Optional<CatalogSnapshot> pollImportAndReadAdmitted(String json,Id world,Id build) {
                calls.incrementAndGet();return answer.get();
            }
        };
    }
    private static CatalogImportAdmissionGate.Receipt receipt(CatalogSnapshot s,String runtime) {
        return new CatalogImportAdmissionGate.Receipt(1,s.world_id().value(),s.build_id().value(),
            Digests.sha256(StrictCodec.encode(s)).value(),SHA,runtime,"IMPORTED_REAL_ISOLATED_RUNTIME");
    }
    private static CatalogImportAdmissionGate gate(CatalogSnapshot s) {
        return new CatalogImportAdmissionGate(s.world_id(),s.build_id(),SHA);
    }
    @Test void missingReceiptDoesNotPreventConsumerFromStartingOrProgressing()throws Exception {
        var s=source();var calls=new AtomicInteger();var g=gate(s);
        assertTrue(g.step(StrictCodec.encode(s),probe(calls,Optional::empty),Optional::empty).isEmpty());
        assertEquals(1,calls.get());
    }
    @Test void durableReadbackWithoutReceiptStaysPending()throws Exception {
        var s=source();var calls=new AtomicInteger();
        assertTrue(gate(s).step(StrictCodec.encode(s),probe(calls,()->Optional.of(s)),Optional::empty).isEmpty());
        assertEquals(1,calls.get());
    }
    @Test void receiptAloneCannotPassWithoutActualConsumerReadback()throws Exception {
        var s=source();var calls=new AtomicInteger();
        assertTrue(gate(s).step(StrictCodec.encode(s),probe(calls,Optional::empty),()->Optional.of(receipt(s,"qa-runtime"))).isEmpty());
        assertEquals(1,calls.get());
    }
    @Test void contradictoryExistingReceiptFailsBeforeConsumerInvocation() {
        var s=source();var calls=new AtomicInteger();var wrong=receipt(different(s),"qa-runtime");
        assertThrows(IOException.class,()->gate(s).step(StrictCodec.encode(s),probe(calls,()->Optional.of(s)),()->Optional.of(wrong)));
        assertEquals(0,calls.get());
    }
    @Test void sameDigestRetriesCompleteOnlyAfterBothReadbackAndReceipt()throws Exception {
        var s=source();var calls=new AtomicInteger();var polls=new AtomicInteger();var g=gate(s);
        var consumer=probe(calls,()->polls.incrementAndGet()==1?Optional.empty():Optional.of(s));
        var json=StrictCodec.encode(s);
        assertTrue(g.step(json,consumer,Optional::empty).isEmpty());
        assertTrue(g.step(json,consumer,Optional::empty).isEmpty());
        assertEquals(receipt(s,"qa-runtime"),g.step(json,consumer,()->Optional.of(receipt(s,"qa-runtime"))).orElseThrow());
        assertEquals(3,calls.get());
    }
    @Test void changedPendingSourceFailsBeforeAnotherConsumerCall()throws Exception {
        var s=source();var calls=new AtomicInteger();var g=gate(s);var consumer=probe(calls,Optional::empty);
        assertTrue(g.step(StrictCodec.encode(s),consumer,Optional::empty).isEmpty());
        assertThrows(IOException.class,()->g.step(StrictCodec.encode(different(s)),consumer,Optional::empty));
        assertEquals(1,calls.get());
    }
    @Test void differentConsumerReadbackCannotBeMaskedByValidReceipt() {
        var s=source();var calls=new AtomicInteger();
        assertThrows(IOException.class,()->gate(s).step(StrictCodec.encode(s),probe(calls,()->Optional.of(different(s))),()->Optional.of(receipt(s,"qa-runtime"))));
        assertEquals(1,calls.get());
    }
    @Test void receiptChangedDuringSinglePollFailsClosed() {
        var s=source();var calls=new AtomicInteger();var reads=new AtomicInteger();
        assertThrows(IOException.class,()->gate(s).step(StrictCodec.encode(s),probe(calls,()->Optional.of(s)),
            ()->Optional.of(receipt(s,reads.incrementAndGet()==1?"runtime-a":"runtime-b"))));
    }
    @Test void commonSourceGuardRejectsWrongWorldBuildAndNoncanonicalJson()throws Exception {
        var s=source();var json=StrictCodec.encode(s);
        assertEquals(s,CatalogImportAdmissionGate.requireExactSource(json,s.world_id(),s.build_id()));
        assertThrows(IOException.class,()->CatalogImportAdmissionGate.requireExactSource(json,new Id("proutlost:other_world"),s.build_id()));
        assertThrows(IOException.class,()->CatalogImportAdmissionGate.requireExactSource(json,s.world_id(),new Id("proutlost:other_build")));
        assertThrows(IOException.class,()->CatalogImportAdmissionGate.requireExactSource("\n"+json,s.world_id(),s.build_id()));
    }
    @Test void invalidSourceFailsBeforeAnyConsumerInvocation() {
        var s=source();var calls=new AtomicInteger();
        var g=new CatalogImportAdmissionGate(s.world_id(),new Id("proutlost:other_build"),SHA);
        assertThrows(IOException.class,()->g.step(StrictCodec.encode(s),probe(calls,()->Optional.of(s)),Optional::empty));
        assertEquals(0,calls.get());
    }
    @Test void anotherSealedCatalogCannotBeAdmittedBeforeThePhysicalArchiveIsChecked(){
        var s=source();var qualified=StrictCodec.encode(s);var calls=new AtomicInteger();
        var replacement=StrictCodec.encode(different(s));
        assertThrows(IOException.class,()->{
            CatalogImportAdmissionGate.requireQualifiedSource(replacement,qualified,s.world_id(),s.build_id());
            gate(s).step(replacement,probe(calls,()->Optional.of(different(s))),Optional::empty);
        });
        assertEquals(0,calls.get());
    }
    @Test void physicalArchivePinSurvivesARecreatedInMemoryGate()throws Exception {
        var s=source();var json=StrictCodec.encode(s);var calls=new AtomicInteger();
        assertEquals(s,CatalogImportAdmissionGate.requireQualifiedSource(json,json,s.world_id(),s.build_id()));
        assertTrue(gate(s).step(json,probe(calls,Optional::empty),Optional::empty).isEmpty());
        assertThrows(IOException.class,()->CatalogImportAdmissionGate.requireQualifiedSource(
            StrictCodec.encode(different(s)),json,s.world_id(),s.build_id()));
        assertEquals(1,calls.get());
    }
}
