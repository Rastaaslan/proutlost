package fr.proutlost.worldprep.compiler;
import fr.proutlost.worldprep.pipeline.*;import fr.proutlost.worldprep.plan.CanonicalDigest;import java.util.*;
/** Zero-touch orchestration model. Planning is pure and build accepts only an exact sealed id. */
public final class WorldPrepCompiler {
 public record SealedPipeline(String pipelineId,List<PassId> passes,String semanticInputFingerprint){public SealedPipeline{passes=List.copyOf(passes);}}
 public SealedPipeline compile(String sourceFingerprint,String profileFingerprint,String semanticPackFingerprint,long seed){if(Stream.of(sourceFingerprint,profileFingerprint,semanticPackFingerprint).anyMatch(s->s==null||s.isBlank()))throw new IllegalArgumentException("Complete semantic identity required");var digest=CanonicalDigest.sha256().putString("worldprep-v2-pipeline").putString(sourceFingerprint).putString(profileFingerprint).putString(semanticPackFingerprint).putLong(seed);PassGraph.order().forEach(p->digest.putString(p.name()));return new SealedPipeline(digest.finish(),PassGraph.order(),semanticPackFingerprint);}
 public void requireExact(SealedPipeline sealed,String requestedPipelineId){if(!sealed.pipelineId().equals(requestedPipelineId))throw new IllegalStateException("Exact sealed pipeline id required");}
 private static final class Stream {static java.util.stream.Stream<String> of(String...s){return java.util.Arrays.stream(s);}}
}
