package fr.proutlost.worldprep.orchestration;

import fr.proutlost.worldprep.physical.PhysicalStore;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

/** One child slice per tick; immutable intents precede engine construction. Recovery validates
 * one retained child per tick. Evidence remains per child rather than rewriting a growing list.
 * The caller must own the parent fence and validate the child's physical evidence.
 */
public final class PerimeterExecution implements AutoCloseable {
    public record Intent(int version,UUID parent,long ordinal,UUID child,String configuration) {}
    public record Receipt(int version,UUID parent,long ordinal,UUID child,String previous,String evidence,String sha256) {}
    public record Aggregate(int version,UUID parent,long groups,long chunks,String terminal,String status) {}
    @FunctionalInterface public interface Factory { QualificationOrchestrator.Task open(PerimeterGroups.Group group)throws Exception; }
    @FunctionalInterface public interface Validator { void verify(PerimeterGroups.Group group,String evidence)throws Exception; }
    private final PerimeterGroups groups;
    private final PhysicalStore store;
    private final Factory factory;
    private final Validator validator;
    private long cursor;
    private String previous="0".repeat(64);
    private QualificationOrchestrator.Task task;
    private boolean failed;
    public PerimeterExecution(PerimeterGroups groups,PhysicalStore store,Factory factory,Validator validator)throws IOException {
        this.groups=groups;this.store=store;this.factory=factory;this.validator=validator;
        store.write("perimeter",groups);
    }
    public Optional<String> step()throws Exception {
        if(failed)throw new IOException("Failed perimeter executor must be reopened under parent fence");
        try {
            if(cursor==groups.count()) {
                store.write("aggregate",new Aggregate(1,groups.batch(),cursor,groups.chunks(),previous,"GROUP_EVIDENCE_COMPLETE_NOT_MAP_READY"));
                return Optional.of(store.readText("aggregate"));
            }
            var group=groups.group(cursor);
            var intent=new Intent(1,groups.batch(),cursor,group.operation(),groups.configurationSha256());
            String key="group-"+cursor;
            if(store.exists(key)) {
                if(!store.read("intent-"+cursor,Intent.class).equals(intent))throw new IOException("Child intent identity changed");
                var r=store.read(key,Receipt.class);
                if(r.version()!=1||!groups.batch().equals(r.parent())||r.ordinal()!=cursor||!group.operation().equals(r.child())
                    ||!previous.equals(r.previous())||!PhysicalStore.sha(r.evidence()).equals(r.sha256()))throw new IOException("Child receipt chain changed");
                validator.verify(group,r.evidence());previous=PhysicalStore.sha(store.readText(key));cursor++;return Optional.empty();
            }
            if(task==null) {
                store.write("intent-"+cursor,intent);
                task=factory.open(group);return Optional.empty();
            }
            var result=task.step();
            if(result.isEmpty())return Optional.empty();
            String evidence=result.get();
            if(evidence.length()>65536)throw new IOException("Child evidence exceeds bounded receipt");
            validator.verify(group,evidence);
            task.close();task=null;
            store.write(key,new Receipt(1,groups.batch(),cursor,group.operation(),previous,evidence,PhysicalStore.sha(evidence)));
            previous=PhysicalStore.sha(store.readText(key));cursor++;return Optional.empty();
        } catch(Exception e) {failed=true;try{close();}catch(IOException close){e.addSuppressed(close);}throw e;}
    }
    public long cursor(){return cursor;}
    @Override public void close()throws IOException {if(task!=null){var closing=task;task=null;closing.close();}}
}
