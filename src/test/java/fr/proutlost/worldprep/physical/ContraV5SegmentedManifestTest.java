package fr.proutlost.worldprep.physical;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ContraV5SegmentedManifestTest {
    private static final UUID QUAL=UUID.fromString("6a7f7c1f-6d0d-4f9d-bf93-d8e7dc45b244");
    private static final UUID ECO=UUID.fromString("d26c66df-9e17-44c3-bf06-6252b6d6a3de");
    private static final String SHA="a".repeat(64);
    private static final List<String> ANIMALS=List.of("minecraft:cow","minecraft:cod");

    @Test void exactlySeventeenDisjointBoundedChildrenCoverEveryGroup() {
        var plan=ContraV5SegmentedManifest.create(QUAL,ECO,SHA,ANIMALS);
        ContraV5SegmentedManifest.verify(plan);
        assertEquals(17,plan.children().size());
        assertEquals(1024,plan.totalGroups());
        assertEquals(64516,plan.totalChunks());
        var all=new HashSet<String>();
        var lineage=new HashSet<UUID>();
        int total=0;
        for(var sector:plan.children()) {
            assertTrue(sector.groups()>0 && sector.groups()<=64);
            assertEquals(2,sector.faunaBatches().size());
            assertTrue(lineage.add(sector.structureBatch()));
            for(var fauna:sector.faunaBatches().values())assertTrue(lineage.add(fauna));
            var area=sector.area();
            for(int x=(area.minX()-16)/128;x<=(area.maxX()-16)/128;x++)
                for(int z=(area.minZ()-16)/128;z<=(area.maxZ()-16)/128;z++) {
                    assertTrue(all.add(x+","+z),"No duplicate 8x8 group");
                    total++;
                }
        }
        assertEquals(1024,total);
        assertEquals(1024,all.size());
        assertEquals(17*3,lineage.size());
    }

    @Test void identityFrozenDeterministicAndDependsOnParent() {
        var original=ContraV5SegmentedManifest.create(QUAL,ECO,SHA,ANIMALS);
        assertEquals(original,ContraV5SegmentedManifest.create(QUAL,ECO,SHA,ANIMALS));
        var changedEcology=ContraV5SegmentedManifest.create(QUAL,UUID.randomUUID(),SHA,ANIMALS);
        assertNotEquals(original.children().getFirst().structureBatch(),
            changedEcology.children().getFirst().structureBatch());
        var changedConfig=ContraV5SegmentedManifest.create(QUAL,ECO,"b".repeat(64),ANIMALS);
        assertNotEquals(original.children().getFirst().structureBatch(),
            changedConfig.children().getFirst().structureBatch());
        var changedSpecies=ContraV5SegmentedManifest.create(QUAL,ECO,SHA,
            List.of("minecraft:cod","minecraft:cow"));
        assertNotEquals(original.species(),changedSpecies.species());
    }

    @Test void rejectsMissingOrMutatedEvidenceNoSilentGroupSkip() {
        assertThrows(IllegalArgumentException.class,
            ()->ContraV5SegmentedManifest.create(QUAL,ECO,SHA,List.of("minecraft:cow","minecraft:cow")));
        assertThrows(IllegalArgumentException.class,
            ()->ContraV5SegmentedManifest.create(QUAL,ECO,SHA,List.of("minecraft:cow")));
        assertThrows(IllegalArgumentException.class,
            ()->ContraV5SegmentedManifest.create(QUAL,QUAL,SHA,ANIMALS));
        assertThrows(IllegalArgumentException.class,
            ()->ContraV5SegmentedManifest.create(QUAL,ECO,"invalid",ANIMALS));
        var p=ContraV5SegmentedManifest.create(QUAL,ECO,SHA,ANIMALS);
        var first=p.children().getFirst();
        var incorrect=new ContraV5SegmentedManifest.Child(first.ordinal(),first.areaId(),
            first.area(),first.structureBatch(),first.faunaBatches(),first.groups()-1,first.chunks());
        var changed=new ArrayList<>(p.children());changed.set(0,incorrect);
        var tampered=new ContraV5SegmentedManifest.Manifest(p.version(),p.qualification(),p.ecology(),
            p.frozenRequestSha256(),p.partitionSha256(),p.species(),changed,p.totalGroups(),p.totalChunks());
        assertThrows(IllegalArgumentException.class,()->ContraV5SegmentedManifest.verify(tampered));
        var truncated=new ContraV5SegmentedManifest.Manifest(p.version(),p.qualification(),p.ecology(),
            p.frozenRequestSha256(),p.partitionSha256(),p.species(),
            p.children().subList(0,16),p.totalGroups(),p.totalChunks());
        assertThrows(IllegalArgumentException.class,()->ContraV5SegmentedManifest.verify(truncated));
    }
}
