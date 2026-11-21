package fr.proutlost.worldprep;

import fr.proutlost.worldprep.orchestration.MultiSpeciesFaunaPlan;
import fr.proutlost.worldprep.physical.NativeFaunaPass;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MultiSpeciesFaunaPlanTest {
    private static final UUID PARENT =
        UUID.fromString("ae72dbcf-d9ae-5090-8ee1-a4f63d0d0c18");

    private static NativeFaunaPass.Rule cow() {
        return new NativeFaunaPass.Rule("minecraft:cow",
            Set.of("minecraft:beach","minecraft:forest","minecraft:plains"),
            Set.of("minecraft:dirt","minecraft:grass_block"),
            -64,319,-10f,10f,0,64,16,2,16,true,8,32);
    }
    private static NativeFaunaPass.Rule cod() {
        return new NativeFaunaPass.Rule("minecraft:cod",
            Set.of("minecraft:ocean","minecraft:deep_ocean","minecraft:lukewarm_ocean"),
            Set.of("minecraft:sand"),-64,319,-10f,10f,6,64,16,2,16,true,8,32);
    }

    @Test void twoIndependentSpeciesStagesAreFrozenDeterministically() {
        var a=MultiSpeciesFaunaPlan.prepare(PARENT,List.of(cow(),cod()));
        var b=MultiSpeciesFaunaPlan.prepare(PARENT,List.of(cod(),cow()));
        assertEquals(5,a.version());
        assertEquals(a,b,"Species input order must not change the canonical v5 plan");
        assertEquals(2,a.stages().size());
        assertEquals("minecraft:cod",a.stages().get(0).species());
        assertEquals("minecraft:cow",a.stages().get(1).species());
        assertNotEquals(a.stages().get(0).childBatch(),a.stages().get(1).childBatch());
        assertNotEquals(a.stages().get(0).directory(),a.stages().get(1).directory());
        assertEquals(64,a.stages().get(0).rule().globalLimit());
        assertEquals(32,a.stages().get(1).rule().habitatLimit());
        assertTrue(a.manifestSha256().matches("[0-9a-f]{64}"));
        assertThrows(UnsupportedOperationException.class,()->a.stages().clear());
    }

    @Test void aNewParentCannotAccidentallyReuseAnOldOperation() {
        var a=MultiSpeciesFaunaPlan.prepare(PARENT,List.of(cow(),cod()));
        var another=MultiSpeciesFaunaPlan.prepare(UUID.randomUUID(),List.of(cow(),cod()));
        assertNotEquals(a.manifestSha256(),another.manifestSha256());
        assertNotEquals(a.stages().get(0).childBatch(),another.stages().get(0).childBatch());
    }

    @Test void duplicateSpeciesWouldRiskReusingTheSameLedgerClaim() {
        assertThrows(IllegalArgumentException.class,()->
            MultiSpeciesFaunaPlan.prepare(PARENT,List.of(cow(),cow())));
    }

    @Test void conflictingWorldGlobalLimitIsRejectedBeforeAnyWrite() {
        var conflicting=new NativeFaunaPass.Rule("minecraft:cod",Set.of("minecraft:ocean"),
            Set.of("minecraft:sand"),-64,319,-10f,10f,6,128,16,2,16,true,8,32);
        assertThrows(IllegalArgumentException.class,()->
            MultiSpeciesFaunaPlan.prepare(PARENT,List.of(cow(),conflicting)));
    }

    @Test void conflictingHabitatOrRegionLimitsRejectedUpfront() {
        var differing=new NativeFaunaPass.Rule("minecraft:cod",Set.of("minecraft:ocean"),
            Set.of("minecraft:sand"),-64,319,-10f,10f,6,64,16,2,16,true,8,16);
        assertThrows(IllegalArgumentException.class,()->
            MultiSpeciesFaunaPlan.prepare(PARENT,List.of(cow(),differing)));
    }

    @Test void onlyOneHabitatTypeIsInsufficientForS2() {
        var sheep=new NativeFaunaPass.Rule("minecraft:sheep",Set.of("minecraft:plains"),
            Set.of("minecraft:grass_block"),-64,319,-10f,10f,0,64,16,2,16,true,8,32);
        assertThrows(IllegalArgumentException.class,()->
            MultiSpeciesFaunaPlan.prepare(PARENT,List.of(cow(),sheep)));
    }

    @Test void productionInitialPopulationRequiresPersistence() {
        var transientFish=new NativeFaunaPass.Rule("minecraft:cod",Set.of("minecraft:ocean"),
            Set.of("minecraft:sand"),-64,319,-10f,10f,6,64,16,2,16,false,8,32);
        assertThrows(IllegalArgumentException.class,()->
            MultiSpeciesFaunaPlan.prepare(PARENT,List.of(cow(),transientFish)));
    }

    @Test void modifiedChildOperationOrDigestCannotPassPlanVerification() {
        var original=MultiSpeciesFaunaPlan.prepare(PARENT,List.of(cow(),cod()));
        assertDoesNotThrow(()->MultiSpeciesFaunaPlan.verify(original));
        var substituted=new MultiSpeciesFaunaPlan.Manifest(5,MultiSpeciesFaunaPlan.CONTRACT,
            PARENT,original.stages(),"0".repeat(64));
        assertThrows(IllegalArgumentException.class,()->
            MultiSpeciesFaunaPlan.verify(substituted));
        var changed=new ArrayList<>(original.stages());
        var first=changed.getFirst();
        changed.set(0,new MultiSpeciesFaunaPlan.Stage(first.ordinal(),first.species(),
            UUID.randomUUID(),first.directory(),first.ruleSha256(),first.rule()));
        var forged=new MultiSpeciesFaunaPlan.Manifest(5,MultiSpeciesFaunaPlan.CONTRACT,
            PARENT,changed,original.manifestSha256());
        assertThrows(IllegalArgumentException.class,()->MultiSpeciesFaunaPlan.verify(forged));
    }

    @Test void noImplicitUpgradeOfLegacyV4OrAbsentParent() {
        assertThrows(IllegalArgumentException.class,()->
            MultiSpeciesFaunaPlan.prepare(null,List.of(cow(),cod())));
        assertThrows(IllegalArgumentException.class,()->
            MultiSpeciesFaunaPlan.prepare(PARENT,List.of(cow())));
    }
}
