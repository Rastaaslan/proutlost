package fr.proutlost.worldprep.pipeline;

import fr.proutlost.worldprep.plan.*;
import fr.proutlost.worldprep.environment.*;
import fr.proutlost.worldprep.validation.*;
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
 public record ExactInput(Map<PassId,PlanManifest> sealedUpstream,
   Map<PassId,? extends Iterable<EnvironmentalMutation>> destructive,
   Iterable<Entry> habitats,ValidationReport validation){public ExactInput{sealedUpstream=Map.copyOf(sealedUpstream);destructive=Map.copyOf(destructive);Objects.requireNonNull(habitats);Objects.requireNonNull(validation);}}
 public Result publish(Path root,Input input,Map<PassId,? extends Iterable<Entry>> entries,int pageSize)throws IOException{
  Objects.requireNonNull(root);Objects.requireNonNull(entries);if(pageSize<1)throw new IllegalArgumentException();var refs=new EnumMap<PassId,PipelineRun.PlanReference>(PassId.class);var manifests=new EnumMap<PassId,PlanManifest>(PassId.class);
  for(var pass:PassGraph.order()){if(!entries.containsKey(pass))throw new IllegalStateException("Missing plan input: "+pass);if(!refs.keySet().containsAll(PassGraph.dependencies(pass)))throw new IllegalStateException("Unsealed upstream dependency: "+pass);String semantic=semanticDigest(input,pass,refs);UUID id=deterministicId(input,pass,semantic);var publication=PagedPlanStore.publish(root,id,pass,input.area(),input.dimension(),input.profile(),semantic,ENGINE_VERSION,"environmental-1",pageSize,entries.get(pass),CODEC);var manifest=publication.manifest();manifests.put(pass,manifest);refs.put(pass,new PipelineRun.PlanReference(id,manifest.rootFingerprint()));}
  UUID runId=UUID.nameUUIDFromBytes((input.area()+input.sourceFingerprint()+input.packFingerprint()).getBytes(java.nio.charset.StandardCharsets.UTF_8));var run=PipelineRun.create(runId,input.area(),input.dimension(),input.profile(),input.sourceFingerprint(),refs,PipelineRun.State.SEALED);return new Result(run,Collections.unmodifiableMap(manifests),pageSize);
 }
 /**
  * Completes the production graph without republishing TERRAIN/BIOMES/GEOLOGY/ORES.
  * Every new destructive artifact uses the lossless mutation codec, while metadata
  * artifacts are independently sealed and all identities flow into downstream digests.
  */
 public Result publishExact(Path root,Input input,ExactInput exact,int pageSize)throws IOException{
  Objects.requireNonNull(root);Objects.requireNonNull(exact);if(pageSize<1)throw new IllegalArgumentException();
  var manifests=new EnumMap<PassId,PlanManifest>(PassId.class);var refs=new EnumMap<PassId,PipelineRun.PlanReference>(PassId.class);
  for(var pass:List.of(PassId.TERRAIN,PassId.BIOMES,PassId.GEOLOGY,PassId.ORES)){
   var manifest=exact.sealedUpstream().get(pass);if(manifest==null)throw new IllegalStateException("Missing existing upstream plan: "+pass);manifest.requireApplicable();if(manifest.passId()!=pass||!manifest.areaId().equals(input.area())||!manifest.dimension().equals(input.dimension())||!manifest.profile().equals(input.profile()))throw new IllegalStateException("Inapplicable existing upstream plan: "+pass);manifests.put(pass,manifest);refs.put(pass,new PipelineRun.PlanReference(manifest.planId(),manifest.rootFingerprint()));
  }
  for(var pass:List.of(PassId.SOILS,PassId.AQUATIC,PassId.STRUCTURES,PassId.FLORA,PassId.TREES)){
   var values=exact.destructive().get(pass);if(values==null)throw new IllegalStateException("Missing exact mutation plan: "+pass);String semantic=semanticDigest(input,pass,refs);UUID id=deterministicId(input,pass,semantic);var publication=PagedPlanStore.publish(root,id,pass,input.area(),input.dimension(),input.profile(),semantic,ENGINE_VERSION,"exact-environmental-2",pageSize,checked(pass,values),EnvironmentalMutationCodec.INSTANCE);put(publication.manifest(),manifests,refs);
  }
  publishMetadata(root,input,PassId.HABITATS,exact.habitats(),pageSize,manifests,refs);
  if(!exact.validation().allowsProductionApply())throw new IllegalStateException("FATAL environmental validation findings prevent sealing");
  var findings=exact.validation().findings().stream().map(f->new Entry(f.severity().ordinal(),0,0,f.code()+"\u0000"+f.message())).toList();publishMetadata(root,input,PassId.VALIDATION,findings,pageSize,manifests,refs);
  UUID runId=UUID.nameUUIDFromBytes((input.area()+input.sourceFingerprint()+input.packFingerprint()).getBytes(java.nio.charset.StandardCharsets.UTF_8));var run=PipelineRun.create(runId,input.area(),input.dimension(),input.profile(),input.sourceFingerprint(),refs,PipelineRun.State.SEALED);return new Result(run,Collections.unmodifiableMap(manifests),pageSize);
 }
 private static Iterable<EnvironmentalMutation> checked(PassId pass,Iterable<EnvironmentalMutation> source){return()->new Iterator<>(){final Iterator<EnvironmentalMutation> delegate=source.iterator();public boolean hasNext(){return delegate.hasNext();}public EnvironmentalMutation next(){var value=delegate.next();if(value.pass()!=pass)throw new IllegalStateException("Mutation stored under wrong pass");return value;}};}
 private static void publishMetadata(Path root,Input input,PassId pass,Iterable<Entry> values,int pageSize,Map<PassId,PlanManifest> manifests,Map<PassId,PipelineRun.PlanReference> refs)throws IOException{String semantic=semanticDigest(input,pass,refs);UUID id=deterministicId(input,pass,semantic);put(PagedPlanStore.publish(root,id,pass,input.area(),input.dimension(),input.profile(),semantic,ENGINE_VERSION,"environmental-metadata-2",pageSize,values,CODEC).manifest(),manifests,refs);}
 private static void put(PlanManifest manifest,Map<PassId,PlanManifest> manifests,Map<PassId,PipelineRun.PlanReference> refs){manifests.put(manifest.passId(),manifest);refs.put(manifest.passId(),new PipelineRun.PlanReference(manifest.planId(),manifest.rootFingerprint()));}
 public static String semanticDigest(Input input,PassId pass,Map<PassId,PipelineRun.PlanReference> upstream){var d=CanonicalDigest.sha256().putString("environmental-input").putString(input.area()).putString(input.dimension()).putString(input.profile()).putString(input.sourceFingerprint()).putString(input.packFingerprint()).putLong(input.seed()).putString(pass.name());for(var dep:PassGraph.order())if(PassGraph.dependencies(pass).contains(dep)){var ref=upstream.get(dep);if(ref==null)throw new IllegalStateException("Missing exact upstream identity: "+dep);d.putString(dep.name()).putUuid(ref.planId()).putString(ref.fingerprint());}return d.finish();}
 private static UUID deterministicId(Input input,PassId pass,String semantic){return UUID.nameUUIDFromBytes((input.area()+":"+pass+":"+semantic).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
 public static final PagedPlanStore.EntryCodec<Entry> CODEC=new PagedPlanStore.EntryCodec<>(){public void write(DataOutput out,Entry e)throws IOException{out.writeInt(e.x());out.writeInt(e.y());out.writeInt(e.z());out.writeUTF(e.value());}public Entry read(DataInput in)throws IOException{return new Entry(in.readInt(),in.readInt(),in.readInt(),in.readUTF());}};
}
