package fr.proutlost.worldprep.pipeline;

import fr.proutlost.worldprep.plan.*;
import fr.proutlost.worldprep.storage.*;
import java.io.*;
import java.nio.file.Path;
import java.util.*;

/** Publishes the complete environmental graph through the one generic paged plan store. */
public final class EnvironmentalPipeline {
 public static final String ENGINE_VERSION="worldprep-v2";
 public record Input(String area,String dimension,String profile,String sourceFingerprint,String packFingerprint,long seed){public Input{if(area==null||area.isBlank()||dimension==null||dimension.isBlank()||profile==null||profile.isBlank())throw new IllegalArgumentException();new PlanFingerprint(sourceFingerprint);new PlanFingerprint(packFingerprint);}}
 public record Entry(int x,int y,int z,String value){}
 public record Result(PipelineRun run,Map<PassId,PlanManifest> manifests,int maximumEntriesPerPage){}
 public Result publish(Path root,Input input,Map<PassId,? extends Iterable<Entry>> entries,int pageSize)throws IOException{
  Objects.requireNonNull(root);Objects.requireNonNull(entries);if(pageSize<1)throw new IllegalArgumentException();var refs=new EnumMap<PassId,PipelineRun.PlanReference>(PassId.class);var manifests=new EnumMap<PassId,PlanManifest>(PassId.class);
  for(var pass:PassGraph.order()){if(pass==PassId.VALIDATION)continue;if(!entries.containsKey(pass))throw new IllegalStateException("Missing plan input: "+pass);if(!refs.keySet().containsAll(PassGraph.dependencies(pass)))throw new IllegalStateException("Unsealed upstream dependency: "+pass);String semantic=semanticDigest(input,pass,refs);UUID id=deterministicId(input,pass,semantic);var publication=PagedPlanStore.publish(root,id,pass,input.area(),input.dimension(),input.profile(),semantic,ENGINE_VERSION,"environmental-1",pageSize,entries.get(pass),CODEC);var manifest=publication.manifest();manifests.put(pass,manifest);refs.put(pass,new PipelineRun.PlanReference(id,manifest.rootFingerprint()));}
  UUID runId=UUID.nameUUIDFromBytes((input.area()+input.sourceFingerprint()+input.packFingerprint()).getBytes(java.nio.charset.StandardCharsets.UTF_8));var run=PipelineRun.create(runId,input.area(),input.dimension(),input.profile(),input.sourceFingerprint(),refs,PipelineRun.State.SEALED);return new Result(run,Collections.unmodifiableMap(manifests),pageSize);
 }
 public static String semanticDigest(Input input,PassId pass,Map<PassId,PipelineRun.PlanReference> upstream){var d=CanonicalDigest.sha256().putString("environmental-input").putString(input.area()).putString(input.dimension()).putString(input.profile()).putString(input.sourceFingerprint()).putString(input.packFingerprint()).putLong(input.seed()).putString(pass.name());for(var dep:PassGraph.order())if(PassGraph.dependencies(pass).contains(dep)){var ref=upstream.get(dep);if(ref==null)throw new IllegalStateException("Missing exact upstream identity: "+dep);d.putString(dep.name()).putUuid(ref.planId()).putString(ref.fingerprint());}return d.finish();}
 private static UUID deterministicId(Input input,PassId pass,String semantic){return UUID.nameUUIDFromBytes((input.area()+":"+pass+":"+semantic).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
 public static final PagedPlanStore.EntryCodec<Entry> CODEC=new PagedPlanStore.EntryCodec<>(){public void write(DataOutput out,Entry e)throws IOException{out.writeInt(e.x());out.writeInt(e.y());out.writeInt(e.z());out.writeUTF(e.value());}public Entry read(DataInput in)throws IOException{return new Entry(in.readInt(),in.readInt(),in.readInt(),in.readUTF());}};
}
