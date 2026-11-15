package fr.proutlost.worldprep.orchestration;

import fr.proutlost.worldprep.physical.PhysicalStore;
import java.io.IOException;
import java.util.*;

/** Crash-safe v5 multi-species stage sequencer. No Minecraft dependency.
 *
 * Each native species-perimeter engine has its own immutable child batch,
 * physical journal, and 36-group receipt chain. This parent stores a separate
 * chain. Replay validates a stage before skipping it; a failed child never
 * becomes completed by merely restarting the parent.
 */
public final class MultiSpeciesStageExecution implements QualificationOrchestrator.Task {
    public record Receipt(int version,UUID parent,int ordinal,UUID childBatch,
                          String species,String previous,String evidence,String evidenceSha256) {}
    public record Aggregate(int version,UUID parent,int stages,String previous,String status) {}
    @FunctionalInterface public interface Factory {
        QualificationOrchestrator.Task open(MultiSpeciesFaunaPlan.Stage stage) throws Exception;
    }
    @FunctionalInterface public interface Validator {
        void verify(MultiSpeciesFaunaPlan.Stage stage,String evidence)throws Exception;
    }
    private final MultiSpeciesFaunaPlan.Manifest plan;
    private final PhysicalStore store;
    private final Factory factory;
    private final Validator validator;
    private String previous="0".repeat(64);
    private int cursor;
    private QualificationOrchestrator.Task active;
    private boolean failed;

    public MultiSpeciesStageExecution(MultiSpeciesFaunaPlan.Manifest plan,PhysicalStore store,
                                      Factory factory,Validator validator)throws IOException {
        MultiSpeciesFaunaPlan.verify(plan);
        this.plan=plan;
        this.store=Objects.requireNonNull(store);
        this.factory=Objects.requireNonNull(factory);
        this.validator=Objects.requireNonNull(validator);
        // Immutable intent pinned before ANY child phase is opened.
        store.write("multi-species-intent",plan);
    }
    @Override public Optional<String> step()throws Exception {
        if(failed)throw new IOException("Failed multi-species coordinator needs a new owner");
        try {
            if(cursor==plan.stages().size()){
                store.write("aggregate",new Aggregate(1,plan.parentBatch(),cursor,previous,
                    "ALL_SPECIES_RECEIPTS_COMPLETE_NOT_MAP_READY"));
                return Optional.of(store.readText("aggregate"));
            }
            var stage=plan.stages().get(cursor);
            String key="stage-"+cursor;
            if(store.exists(key)) {
                var retained=store.read(key,Receipt.class);
                if(retained.version()!=1||!plan.parentBatch().equals(retained.parent())
                    ||retained.ordinal()!=cursor||!stage.childBatch().equals(retained.childBatch())
                    ||!stage.species().equals(retained.species())
                    ||!previous.equals(retained.previous())
                    ||!PhysicalStore.sha(retained.evidence()).equals(retained.evidenceSha256()))
                    throw new IOException("Changed retained species-stage receipt");
                validator.verify(stage,retained.evidence());
                previous=PhysicalStore.sha(store.readText(key));
                cursor++;return Optional.empty();
            }
            if(active==null){
                store.write(key+"-intent",stage);
                active=factory.open(stage);
                return Optional.empty();
            }
            var result=active.step();
            if(result.isEmpty())return Optional.empty();
            var evidence=result.orElseThrow();
            if(evidence.length()>65536)throw new IOException("Species-stage evidence size cap");
            validator.verify(stage,evidence);
            active.close();active=null;
            store.write(key,new Receipt(1,plan.parentBatch(),cursor,stage.childBatch(),
                stage.species(),previous,evidence,PhysicalStore.sha(evidence)));
            previous=PhysicalStore.sha(store.readText(key));
            cursor++;return Optional.empty();
        }catch(Exception failure) {
            failed=true;
            try {close();}catch(Exception close) {failure.addSuppressed(close);}
            throw failure;
        }
    }
    public int stageCursor(){return cursor;}
    @Override public void close()throws IOException {
        if(active!=null){var old=active;active=null;old.close();}
    }
}
