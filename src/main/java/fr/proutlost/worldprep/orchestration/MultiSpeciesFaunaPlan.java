package fr.proutlost.worldprep.orchestration;

import fr.proutlost.worldprep.physical.NativeFaunaPass;
import fr.proutlost.worldprep.physical.PhysicalStore;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Pure, immutable S2 multi-habitat intent PLANNING contract.
 *
 * Not a v4 mutation or a runtime authorization. A future explicitly versioned
 * v5 QualificationBinding is required before any species stage can run.
 *
 * The shared physical-population ledger pins world/global habitat limits on
 * first use. Require all species to agree on those limits before a new intent
 * is frozen; otherwise the second species would fail halfway through S2.
 */
public final class MultiSpeciesFaunaPlan {
    public static final int VERSION=5;
    public static final String CONTRACT="proutlost:physical-fauna-multispecies-v1";

    public record Stage(int ordinal,String species,UUID childBatch,String directory,
                        String ruleSha256,NativeFaunaPass.Rule rule) {
        public Stage {
            if(ordinal<0||species==null||species.isBlank()||childBatch==null
               ||directory==null||ruleSha256==null||rule==null)
                throw new IllegalArgumentException("Invalid immutable species stage");
        }
    }
    public record Manifest(int version,String contract,UUID parentBatch,
                           List<Stage> stages, String manifestSha256) {
        public Manifest {
            stages=List.copyOf(stages);
            if(version!=VERSION||!CONTRACT.equals(contract)||parentBatch==null
               ||stages.size()<2||stages.size()>8||manifestSha256==null
               ||!manifestSha256.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("Invalid v5 plan manifest");
        }
    }

    public static Manifest prepare(UUID parentBatch,List<NativeFaunaPass.Rule> input){
        if(parentBatch==null||input==null||input.size()<2||input.size()>8)
            throw new IllegalArgumentException("Distinct multi-species parent batch required");
        var species=new TreeMap<String,NativeFaunaPass.Rule>();
        for(var rule:input){
            if(rule==null)throw new IllegalArgumentException("Null species rule refused");
            if(species.putIfAbsent(rule.species(),rule)!=null)
                throw new IllegalArgumentException("Duplicate species: "+rule.species());
            if(!rule.persistent())
                throw new IllegalArgumentException("Nonpersistent initial population forbidden");
        }
        boolean terrestrial=false,aquatic=false;
        var first=species.firstEntry().getValue();
        for(var rule:species.values()){
            terrestrial|=rule.waterDepth()==0;
            aquatic|=rule.waterDepth()>0;
            // NativePhysicalRuntime.reservePopulation retains a single immutable
            // world-policy (not one per species); reject conflicting policies BEFORE
            // any irreversible receipt or entity mutation.
            if(rule.globalLimit()!=first.globalLimit()
               ||rule.regionLimit()!=first.regionLimit()
               ||rule.habitatLimit()!=first.habitatLimit())
                throw new IllegalArgumentException("Conflicting shared world population budgets");
        }
        if(!terrestrial||!aquatic)
            throw new IllegalArgumentException("Terrestrial + aquatic habitat stages both required");
        var stages=new ArrayList<Stage>();
        int ordinal=0;
        for(var entry:species.entrySet()){
            var rule=entry.getValue();
            String speciesId=entry.getKey();
            String canonical=new com.google.gson.Gson().toJson(rule);
            String identity=CONTRACT+"/"+parentBatch+"/"+speciesId;
            UUID childBatch=UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8));
            String key=PhysicalStore.sha(speciesId);
            stages.add(new Stage(ordinal++,speciesId,childBatch,
                "species-"+key,PhysicalStore.sha(canonical),rule));
        }
        StringBuilder frozen=new StringBuilder(VERSION+"/"+CONTRACT+"/"+parentBatch);
        for(var stage:stages)frozen.append("/").append(stage.ordinal()).append(":")
            .append(stage.species()).append(":").append(stage.childBatch())
            .append(":").append(stage.directory()).append(":").append(stage.ruleSha256());
        return new Manifest(VERSION,CONTRACT,parentBatch,stages,
            PhysicalStore.sha(frozen.toString()));
    }
    /** A record constructor alone cannot attest that its supplied SHA was honest. */
    public static void verify(Manifest retained) {
        if(retained==null)throw new IllegalArgumentException("No v5 manifest");
        var reconstructed=prepare(retained.parentBatch(),
            retained.stages().stream().map(Stage::rule).toList());
        if(!reconstructed.equals(retained))
            throw new IllegalArgumentException("V5 manifest/child identity has been substituted");
    }
    private MultiSpeciesFaunaPlan() {}
}
