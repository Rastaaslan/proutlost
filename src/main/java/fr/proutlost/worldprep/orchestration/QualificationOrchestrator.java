package fr.proutlost.worldprep.orchestration;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import static fr.proutlost.worldprep.orchestration.QualificationPipeline.Phase;

/** Bounded engine driver: begin is forced before constructing/executing a phase engine.
 * Missing preconditions or exceptions pause the same batch. No engine is silently skipped.
 * Closing releases the fence; a replacement resumes through the same durable phase intent.
 */
public final class QualificationOrchestrator implements AutoCloseable {
    public enum Status { RUNNING, PAUSED, QUALIFIED_NOT_PUBLISHED }
    @FunctionalInterface public interface Task extends AutoCloseable {Optional<String> step()throws Exception;default void close()throws IOException {}}
    @FunctionalInterface public interface Factory {Task open(QualificationPipeline.Pending pending)throws Exception;}
    @FunctionalInterface public interface Gate {void verify()throws Exception;}
    @FunctionalInterface public interface EvidenceValidator {void verify(Phase phase,String receipt)throws Exception;}
    private final QualificationPipeline pipeline;
    private final Map<Phase,Factory> engines;
    private final Gate gate;
    private final EvidenceValidator validator;
    private QualificationPipeline.Pending pending;
    private Task task;
    private Status status=Status.RUNNING;
    private String reason="";
    public QualificationOrchestrator(Path directory,UUID batch,String configuration,Map<Phase,Factory> engines,
                                     Gate gate,EvidenceValidator validator)throws IOException {
        if(!engines.keySet().equals(EnumSet.allOf(Phase.class)))throw new IllegalArgumentException("All qualification phases require engines");
        this.engines=Map.copyOf(engines);this.gate=Objects.requireNonNull(gate);this.validator=Objects.requireNonNull(validator);
        this.pipeline=new QualificationPipeline(directory,batch,sha(configuration));
        try {pipeline.archiveConfiguration(configuration);}catch(IOException failure){pipeline.close();throw failure;}
    }
    /** Exactly one engine slice per call, no recursive advance into the next phase. */
    public void tick() {
        if(status!=Status.RUNNING)return;
        try {
            gate.verify();
            if(pipeline.cursor()==Phase.values().length) {status=Status.QUALIFIED_NOT_PUBLISHED;return;}
            if(pending==null)pending=pipeline.begin();
            if(task==null)task=engines.get(pending.phase()).open(pending);
            var result=task.step();
            if(result.isEmpty())return;
            validator.verify(pending.phase(),result.get());
            String evidence=pipeline.archiveEvidence(pending,result.get());
            task.close();
            pipeline.complete(pending,evidence);
            task=null;pending=null;
        } catch(Exception failure) {status=Status.PAUSED;reason=failure.getClass().getSimpleName()+": "+failure.getMessage();releaseTask();}
    }
    public void pause(){if(status==Status.RUNNING){status=Status.PAUSED;reason="Explicit pause";releaseTask();}}
    public void resume(){if(status==Status.PAUSED){releaseTask();status=Status.RUNNING;reason="";pending=null;}}
    public Status status(){return status;}
    public String reason(){return reason;}
    public int cursor()throws IOException{return pipeline.cursor();}
    public static String sha(String text) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException e){throw new AssertionError(e);}
    }
    private void releaseTask(){if(task!=null){try{task.close();}catch(IOException failure){reason+="; close: "+failure.getMessage();}finally{task=null;}}}
    @Override public void close()throws IOException{releaseTask();pipeline.close();status=Status.PAUSED;}
}
