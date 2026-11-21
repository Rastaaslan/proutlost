package fr.proutlost.worldprep.physical;

import fr.proutlost.worldprep.orchestration.*;
import fr.proutlost.worldprep.persistence.WorldPrepSavedData;
import net.minecraft.nbt.Tag;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/** Independent final-readback verifier for each v5 species child perimeter.
 *
 * A parent aggregate containing only NO_ELIGIBLE_HABITAT receipts is NOT
 * proof of actual fauna. Group receipts are replayed with exact identity and
 * live-independent, persisted Anvil UUID ownership checks. Verification is
 * read-only on the Minecraft world; writes only an optional named proof in the
 * already-isolated qualification journal when the v5 session explicitly opts in.
 */
public final class NativeV5SpeciesSettlement {
    public record Summary(int version,String species,UUID childBatch,long groups,
                          long groupsPopulated,long animalsVerified,long groupsSkipped,
                          String chainSha256,String status) {}
    public static Summary verify(Path world,Path speciesDirectory,
            WorldPrepSavedData.Area area,MultiSpeciesFaunaPlan.Stage stage,
            String frozenRequest)throws IOException {
        if(world==null||speciesDirectory==null||area==null||stage==null
           ||frozenRequest==null||frozenRequest.isBlank())
            throw new IOException("Explicit v5 settlement identity required");
        if(!stage.species().equals(stage.rule().species()))
            throw new IOException("Species stage rule identity conflict");
        var configuration=frozenRequest+"/species/"+stage.species()+"/"+stage.ruleSha256();
        var groups=new PerimeterGroups(stage.childBatch(),PhysicalStore.sha(configuration),
            area.dimension(),area.minX(),area.minZ(),area.maxX(),area.maxZ());
        if(groups.count()<1||groups.count()>64)
            throw new IOException("Unbounded v5 species groups");
        var index=new PhysicalStore(speciesDirectory.resolve("index"));
        var pinned=index.read("perimeter",PerimeterGroups.class);
        if(!pinned.equals(groups))throw new IOException("Foreign species group partition");
        String previous="0".repeat(64);
        long populated=0,animals=0,skipped=0;
        var uuids=new HashSet<UUID>();
        for(long i=0;i<groups.count();i++) {
            var group=groups.group(i);
            var intent=index.read("intent-"+i,PerimeterExecution.Intent.class);
            if(intent.version()!=1||!intent.parent().equals(stage.childBatch())
               ||intent.ordinal()!=i||!intent.child().equals(group.operation())
               ||!intent.configuration().equals(groups.configurationSha256()))
                throw new IOException("Wrong immutable v5 child group intent");
            var receipt=index.read("group-"+i,PerimeterExecution.Receipt.class);
            if(receipt.version()!=1||!receipt.parent().equals(stage.childBatch())
               ||receipt.ordinal()!=i||!receipt.child().equals(group.operation())
               ||!receipt.previous().equals(previous)
               ||!receipt.sha256().equals(PhysicalStore.sha(receipt.evidence())))
                throw new IOException("Missing or changed child receipt chain");
            var child=new PhysicalStore(speciesDirectory.resolve("group-"+i));
            if(!child.readText("evidence").equals(receipt.evidence()))
                throw new IOException("Native child evidence mismatch");
            var e=new com.google.gson.Gson().fromJson(receipt.evidence(),
                NativePerimeterFauna.Evidence.class);
            if(e==null||e.ordinal()!=i||!e.child().equals(group.operation().toString()))
                throw new IOException("Changed v5 group disposition identity");
            if("POPULATION_SAVE_READBACK_VERIFIED".equals(e.disposition())) {
                if(!PhysicalStore.sha(child.readText("plan")).equals(e.planSha())
                  ||!PhysicalStore.sha(child.readText("population-disk-verified")).equals(e.diskSha()))
                    throw new IOException("Native species plan/disk receipt hashes differ");
                var plan=child.read("plan",NativeFaunaPass.Plan.class);
                NativePerimeterFauna.requireSpeciesLineage(stage.rule(),plan,
                    group,configuration,area.dimension());
                var disk=child.read("population-disk-verified",NativePopulationAudit.Receipt.class);
                if(!disk.operation().equals(plan.operation())||!disk.planSha256().equals(e.planSha())
                    ||disk.diskVerified()!=plan.animals().size()
                    ||!"POPULATION_SAVE_READBACK_VERIFIED".equals(disk.status()))
                    throw new IOException("Incomplete persisted group population receipt");
                for(var animal:plan.animals()){
                    if(!uuids.add(animal.uuid()))throw new IOException("Duplicate species UUID between groups");
                    requireAnvilAnimal(world,stage.species(),plan.operation(),animal);
                    animals++;
                }
                populated++;
            } else if(Set.of("NO_ELIGIBLE_HABITAT","DURABLE_BUDGET_CONSUMED").contains(e.disposition())) {
                NativePerimeterFauna.requireNoRetainedIntentForSkip(child);
                skipped++;
            } else throw new IOException("Unknown/unsafe v5 species disposition");
            previous=PhysicalStore.sha(index.readText("group-"+i));
        }
        var aggregate=index.read("aggregate",PerimeterExecution.Aggregate.class);
        if(aggregate.version()!=1||!aggregate.parent().equals(stage.childBatch())
            ||aggregate.groups()!=groups.count()||aggregate.chunks()!=groups.chunks()
            ||!aggregate.terminal().equals(previous)
            ||!"GROUP_EVIDENCE_COMPLETE_NOT_MAP_READY".equals(aggregate.status()))
            throw new IOException("Species aggregate not bound to full verified perimeter");
        return new Summary(1,stage.species(),stage.childBatch(),
            groups.count(),populated,animals,skipped,previous,
            "NATIVE_SPECIES_ANVIL_FULL_PERIMETER_VERIFIED_NOT_MAP_READY");
    }

    /** The presence of 36 audited SKIP receipts cannot substitute for real fauna. */
    public static void requireObservedFauna(Summary summary)throws IOException {
        if(summary==null||summary.animalsVerified()<1||summary.groupsPopulated()<1
            ||summary.groupsPopulated()+summary.groupsSkipped()!=summary.groups())
            throw new IOException("V5_NO_ACTUAL_NATIVE_FAUNA_PER_SPECIES: audited skips are not animals");
    }

    private static void requireAnvilAnimal(Path world,String species,UUID operation,
                                           NativeFaunaPass.Animal animal)throws IOException {
        var tags=ReadOnlyChunkNbt.entitiesBounded(world.resolve("entities"),
            animal.x()>>4,animal.z()>>4).getList("Entities",Tag.TAG_COMPOUND);
        if(tags.size()>4096)throw new IOException("Entity Anvil size bound");
        int matches=0;
        for(int i=0;i<tags.size();i++) {
            var tag=tags.getCompound(i);
            if(!tag.hasUUID("UUID")||!tag.getUUID("UUID").equals(animal.uuid()))continue;
            matches++;
            if(!species.equals(tag.getString("id"))
                ||!operation.toString().equals(tag.getCompound("NeoForgeData")
                    .getString("proutlost_initial_operation"))
                ||!tag.getBoolean("PersistenceRequired"))
                throw new IOException("Native v5 disk fauna identity or persistence mismatch");
        }
        if(matches!=1)throw new IOException("Missing/duplicate native v5 animal in persisted Anvil");
    }
    private NativeV5SpeciesSettlement(){}
}
