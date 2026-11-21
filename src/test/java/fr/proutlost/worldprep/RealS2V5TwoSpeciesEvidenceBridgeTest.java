package fr.proutlost.worldprep;

import fr.proutlost.worldprep.orchestration.*;
import fr.proutlost.worldprep.physical.*;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Operator-opt-in offline bridge. Two ACTUAL native disk-audited S2 entities
 * are validated before accepting stage evidence in a disposable v5 parent WAL.
 *
 * Does not spawn entities, does not seal a v5 request, and does not authorize
 * either old v4 batch or a production Director admission.
 */
class RealS2V5TwoSpeciesEvidenceBridgeTest {
    private record PhysicalSpecies(PhysicalStore store,NativeFaunaPass.Plan plan,
                                   NativePopulationAudit.Receipt receipt) {}

    private static PhysicalSpecies physical(Path lab,String species)throws IOException {
        var root=lab.resolve(species.equals("minecraft:cow")?"fauna-journal":"marine-journal");
        var store=new PhysicalStore(root);
        var plan=store.read("plan",NativeFaunaPass.Plan.class);
        var receipt=store.read("population-disk-verified",NativePopulationAudit.Receipt.class);
        if(!plan.rule().species().equals(species)||plan.animals().size()!=1
            ||!plan.rule().persistent()
            ||!plan.operation().equals(receipt.operation())
            ||!PhysicalStore.sha(store.readText("plan")).equals(receipt.planSha256())
            ||!"POPULATION_SAVE_READBACK_VERIFIED".equals(receipt.status())
            ||receipt.issued()!=1||receipt.living()!=1||receipt.diskVerified()!=1)
            throw new IOException("Inconsistent authentic S2 physical population evidence");
        return new PhysicalSpecies(store,plan,receipt);
    }
    private static void disk(Path lab,PhysicalSpecies pair)throws IOException {
        var animal=pair.plan().animals().getFirst();
        var entities=ReadOnlyChunkNbt.entitiesBounded(lab.resolve("world/entities"),
            animal.x()>>4,animal.z()>>4).getList("Entities",Tag.TAG_COMPOUND);
        int match=0;
        for(int i=0;i<entities.size();i++) {
            var tag=entities.getCompound(i);
            if(!tag.hasUUID("UUID")||!tag.getUUID("UUID").equals(animal.uuid()))continue;
            match++;
            if(!tag.getString("id").equals(pair.plan().rule().species())
                ||!tag.getBoolean("PersistenceRequired")
                ||!tag.getCompound("NeoForgeData")
                   .getString("proutlost_initial_operation").equals(pair.plan().operation().toString()))
                throw new IOException("Anvil UUID does not prove native species ownership");
        }
        if(match!=1)throw new IOException("Unique persisted S2 entity not found");
    }
    @Test void bothActualAnvilEntityProofsCanBeChainedAndReopenedWithoutRespawning()throws Exception {
        String location=System.getenv("WORLDPREP_V5_REAL_EVIDENCE_LAB");
        Assumptions.assumeTrue(location!=null&&!location.isBlank(),
            "DEV operator opt-in only; never use during normal unit test runs");
        Path lab=Path.of(location).toRealPath();
        assertTrue(lab.endsWith("native-s2-physical-lab"));
        var cow=physical(lab,"minecraft:cow");
        var cod=physical(lab,"minecraft:cod");
        disk(lab,cow);disk(lab,cod);
        var lookup=Map.of("minecraft:cow",cow,"minecraft:cod",cod);
        var plan=MultiSpeciesFaunaPlan.prepare(
            UUID.fromString("5f71e31c-2c2b-5350-9bea-2fe075521e08"),
            List.of(cow.plan().rule(),cod.plan().rule()));
        assertEquals(2,plan.stages().size());
        var path=lab.resolve("v5-evidence-bridge-dev");
        // This is a separate test evidence store; it is NOT the real frozen v5
        // qualification directory, and cannot create worldprep-qualification.json.
        var store=new PhysicalStore(path);
        var validator=(MultiSpeciesStageExecution.Validator)(stage,evidence)->{
            var real=lookup.get(stage.species());
            disk(lab,real);
            String proof=real.receipt().operation()+"/"
                +real.receipt().planSha256()+"/"
                +real.plan().animals().getFirst().uuid();
            if(!proof.equals(evidence))
                throw new IOException("Wrong authentic physical S2 evidence for v5 stage");
        };
        var openedFirst=new AtomicInteger();
        var factory=(MultiSpeciesStageExecution.Factory)stage->{
            openedFirst.incrementAndGet();
            var real=lookup.get(stage.species());
            disk(lab,real);
            String output=real.receipt().operation()+"/"
                +real.receipt().planSha256()+"/"
                +real.plan().animals().getFirst().uuid();
            return ()->Optional.of(output);
        };
        try(var first=new MultiSpeciesStageExecution(plan,store,factory,validator)){
            first.step(); // pin first intent and open cod
            first.step(); // accept authentic cod disk evidence
            assertEquals(1,first.stageCursor());
        }
        assertEquals(1,openedFirst.get());
        var openedAfterReopen=new AtomicInteger();
        try(var resumed=new MultiSpeciesStageExecution(plan,store,stage->{
            openedAfterReopen.incrementAndGet();
            return factory.open(stage);
        },validator)){
            for(int t=0;t<12&&resumed.stageCursor()!=2;t++)resumed.step();
            assertEquals(2,resumed.stageCursor());
            assertTrue(resumed.step().isPresent());
        }
        assertEquals(1,openedAfterReopen.get(),
            "The authentic cod stage must be REPLAYED, not reissued");
        assertEquals("ALL_SPECIES_RECEIPTS_COMPLETE_NOT_MAP_READY",
            store.read("aggregate",MultiSpeciesStageExecution.Aggregate.class).status());
        assertFalse(Files.exists(lab.resolve("world/worldprep-qualification.json")));
        System.out.println("S2_V5_NATIVE_TWO_SPECIES_EVIDENCE_BRIDGE_PASS "+
            "cow="+cow.plan().animals().getFirst().uuid()+
            " cod="+cod.plan().animals().getFirst().uuid()+
            " resumedSecondOnly=true animalsEmitted=0 production=false");
    }
}
