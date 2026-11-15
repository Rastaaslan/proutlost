package fr.proutlost.worldprep.physical;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ContraV5SegmentedStructureProofsTest {
    private static final UUID QUAL=UUID.fromString("6a7f7c1f-6d0d-4f9d-bf93-d8e7dc45b244");
    private static final UUID ECO=UUID.fromString("d26c66df-9e17-44c3-bf06-6252b6d6a3de");
    private static final String SHA="a".repeat(64);
    private static ContraV5SegmentedManifest.Manifest manifest(){
        return ContraV5SegmentedManifest.create(QUAL,ECO,SHA,
            List.of("minecraft:cow","minecraft:cod"));
    }
    private static List<ContraV5SegmentedStructureProofs.Child> receiptList(){
        var result=new ArrayList<ContraV5SegmentedStructureProofs.Child>();
        for(var c:manifest().children())
            result.add(new ContraV5SegmentedStructureProofs.Child(
                c.ordinal(),c.areaId(),c.structureBatch(),c.groups(),c.chunks(),
                1,"1".repeat(64),"2".repeat(64)));
        return result;
    }
    @Test void requiresAllSeventeenDistinctExactSealedChildrenBeforeStructuralAdmissibility(){
        var m=manifest();
        var verified=ContraV5SegmentedStructureProofs.STATUS;
        var proof=assertDoesNotThrow(()->ContraV5SegmentedStructureProofs.validate(m,receiptList()));
        assertEquals(1024,proof.groups());assertEquals(64516,proof.chunks());
        assertEquals(17,proof.placed());assertEquals(17,proof.children().size());
        assertEquals(verified,proof.status());
        assertTrue(proof.status().contains("PENDING"),"Must never imply V5 completion");
    }
    @Test void missingDuplicatedReorderedTamperedOrNoPlacementFailsClosed(){
        var m=manifest();var base=receiptList();
        assertThrows(IOException.class,()->ContraV5SegmentedStructureProofs.validate(m,List.of()));
        assertThrows(IOException.class,()->ContraV5SegmentedStructureProofs.validate(m,base.subList(0,16)));
        var reordered=new ArrayList<>(base);Collections.swap(reordered,0,1);
        assertThrows(IOException.class,()->ContraV5SegmentedStructureProofs.validate(m,reordered));
        var duplicate=new ArrayList<>(base);duplicate.set(1,duplicate.get(0));
        assertThrows(IOException.class,()->ContraV5SegmentedStructureProofs.validate(m,duplicate));
        var wrongBatch=new ArrayList<>(base);var c=wrongBatch.get(7);
        wrongBatch.set(7,new ContraV5SegmentedStructureProofs.Child(c.ordinal(),c.areaId(),
            UUID.randomUUID(),c.groups(),c.chunks(),c.placed(),c.terminal(),c.aggregateSha256()));
        assertThrows(IOException.class,()->ContraV5SegmentedStructureProofs.validate(m,wrongBatch));
        var wrongCount=new ArrayList<>(base);c=wrongCount.get(0);
        wrongCount.set(0,new ContraV5SegmentedStructureProofs.Child(c.ordinal(),c.areaId(),
            c.batch(),65,c.chunks(),c.placed(),c.terminal(),c.aggregateSha256()));
        assertThrows(IOException.class,()->ContraV5SegmentedStructureProofs.validate(m,wrongCount));
        var missingHash=new ArrayList<>(base);c=missingHash.get(2);
        missingHash.set(2,new ContraV5SegmentedStructureProofs.Child(c.ordinal(),c.areaId(),
            c.batch(),c.groups(),c.chunks(),0,"changed",c.aggregateSha256()));
        assertThrows(IOException.class,()->ContraV5SegmentedStructureProofs.validate(m,missingHash));
        var missingPhysicalSector=new ArrayList<>(base);c=missingPhysicalSector.get(16);
        missingPhysicalSector.set(16,new ContraV5SegmentedStructureProofs.Child(c.ordinal(),c.areaId(),
            c.batch(),c.groups(),c.chunks(),0,c.terminal(),c.aggregateSha256()));
        assertThrows(IOException.class,
            ()->ContraV5SegmentedStructureProofs.validate(m,missingPhysicalSector));
        var noPhysicalPlacements=new ArrayList<ContraV5SegmentedStructureProofs.Child>();
        for(var child:base)noPhysicalPlacements.add(new ContraV5SegmentedStructureProofs.Child(
            child.ordinal(),child.areaId(),child.batch(),child.groups(),child.chunks(),
            0,child.terminal(),child.aggregateSha256()));
        assertThrows(IOException.class,
            ()->ContraV5SegmentedStructureProofs.validate(m,noPhysicalPlacements));
    }
    @Test void coldGateNeverCreatesMissingReceiptsOrAcceptsChangedParent(@TempDir Path temp)
        throws IOException {
        var m=manifest();
        var directory=temp.resolve("sector-ledger");
        Files.createDirectory(directory);
        new PhysicalStore(directory).write("manifest",m);
        assertThrows(IOException.class,()->ContraV5SegmentedStructureProofs.audit(
            m,directory,"not-the-frozen-parent"));
        assertThrows(IOException.class,()->ContraV5SegmentedStructureProofs.audit(
            m,directory,"a frozen request whose hash is not a".repeat(1)));
        assertFalse(Files.exists(directory.resolve("sector-00")),
            "Cold preflight must not create child journal or fake receipt");
    }
}
