import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import com.google.gson.*;
import fr.proutlost.contract.director.*;
import fr.proutlost.worldprep.director.PhysicalCatalogExporter;
import fr.proutlost.director.api.codec.ModelCodec;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.bootstrap.DirectorOperatorBundle;
import static fr.proutlost.contract.director.Vocabulary.*;
public class SealCampaignPhysical {
 static final Gson G=new GsonBuilder().serializeNulls().setPrettyPrinting().create();
 static final Id DIM=new Id("minecraft:overworld");
 static final fr.proutlost.contract.director.Revision REV=new fr.proutlost.contract.director.Revision("2");
 static String sha(Path p)throws Exception{return BlobContent.hash(Files.readAllBytes(p)).value();}
 static int n(JsonObject o,String k){return o.get(k).getAsInt();}
 static Id sid(String prefix,String id){return new Id("proutlost:"+prefix+"/"+id);}
 static fr.proutlost.contract.director.BlockPos pos(JsonArray a,int off){return new fr.proutlost.contract.director.BlockPos(a.get(off).getAsInt(),a.get(off+1).getAsInt(),a.get(off+2).getAsInt());}
 static Bounds bounds(JsonObject b){return new Bounds(DIM,new fr.proutlost.contract.director.BlockPos(n(b,"min_x"),n(b,"min_y"),n(b,"min_z")),new fr.proutlost.contract.director.BlockPos(n(b,"max_x_exclusive")-1,n(b,"max_y_exclusive")-1,n(b,"max_z_exclusive")-1));}
 static Set<Id> tags(JsonArray a){var s=new HashSet<Id>();for(var v:a)s.add(new Id(v.getAsString()));return s;}
 static void need(JsonArray rows,DirectorOperatorBundle bundle,Id world,String event,String capability,Set<Channel> channels,int capacity,List<EvidenceRef> evidence){
  var def=bundle.catalog().events().get(new DefinitionId("proutlost_director:candidate/"+event));Objects.requireNonNull(def);
  var reference=new fr.proutlost.director.api.model.DefinitionRef(def.id(),def.contentRevision(),BlobContent.hash(new ModelCodec<>(fr.proutlost.director.api.model.EventDefinition.class).encode(def)));
  var need=new EventNeed(world,SchemaVersion.BASE,sid("season2_need",event),fr.proutlost.contract.director.Vocabulary.Family.valueOf(def.category().name()),List.of(new CapabilityRequirement(new Id("proutlost:"+capability),List.of())),Set.of(new Id("proutlost:prepared_contra")),Set.of(),Set.of(DIM),List.of(),Set.of(),Set.of(),null,Set.of(),evidence,channels,capacity,List.of(),List.of(),new fr.proutlost.contract.director.RecoveryPolicy(List.of(RecoveryStrategy.RETRY),List.of(),true,new Id("proutlost:season2_retry_cue"),new Id("proutlost:season2_retry")));
  var row=new JsonObject();row.add("definition",JsonParser.parseString(new String(new ModelCodec<>(fr.proutlost.director.api.model.DefinitionRef.class).encode(reference),StandardCharsets.UTF_8)));row.add("need",JsonParser.parseString(StrictCodec.encode(need)));rows.add(row);
 }
 public static void main(String[] a)throws Exception{
  Path surveyPath=Path.of(a[0]),g0path=Path.of(a[1]),profile=Path.of(a[2]),out=Path.of(a[3]);
  var survey=JsonParser.parseString(Files.readString(surveyPath)).getAsJsonObject();var g0=JsonParser.parseString(Files.readString(g0path)).getAsJsonObject();
  if(!survey.get("read_only").getAsBoolean()||survey.get("new_chunks_generated").getAsInt()!=0||!survey.get("world_id").getAsString().equals(g0.get("world_id").getAsString())||!survey.get("g0_source_sha256").getAsString().equals(sha(g0path)))throw new IllegalStateException("Actual native origin and exact G0 required");
  Id world=new Id("proutlost:contra_"+survey.get("source_origin_world_id").getAsString()),build=new Id("proutlost:contra_saison2_r55");
  var evidence=new EvidenceRef(new Id("proutlost:contra_native_survey_r56"),new Digest(sha(surveyPath)),new Id("proutlost:actual_closed_native_source_survey"));
  var disk=new EvidenceRef(new Id("proutlost:contra_region_files_r56"),Digests.sha256(survey.getAsJsonObject("disk_regions_sha256").toString()),new Id("proutlost:closed_region_sha256"));
  var regions=new ArrayList<SemanticRegion>();for(var v:g0.getAsJsonArray("regions")){var r=v.getAsJsonObject();regions.add(new SemanticRegion(new SemanticRegionId(sid("region",r.get("region_id").getAsString())),REV,List.of(bounds(r.getAsJsonObject("bounds"))),tags(r.getAsJsonArray("tags")),0));}
  var originals=new HashMap<String,JsonObject>();for(var v:g0.getAsJsonArray("locations"))originals.put(v.getAsJsonObject().get("location_id").getAsString(),v.getAsJsonObject());
  var sites=new ArrayList<PhysicalCatalogExporter.Site>();var statePins=new HashMap<Id,Digest>();
  for(var v:survey.getAsJsonArray("locations")){
   var s=v.getAsJsonObject();String key=s.get("location_id").getAsString();var original=Objects.requireNonNull(originals.get(key));
   var geometry=new ArrayList<Bounds>();for(var b:s.getAsJsonArray("geometry")){var ar=b.getAsJsonArray();geometry.add(new Bounds(DIM,pos(ar,0),pos(ar,3)));}
   var anchors=new ArrayList<Anchor>();var standing=new HashSet<String>();for(var z:s.getAsJsonArray("standing_anchors"))standing.add(z.getAsJsonObject().get("anchor_id").getAsString());
   var anchorIds=new HashSet<Id>();var clearPositions=new HashSet<String>();
   for(var x:g0.getAsJsonArray("anchors")){
    var an=x.getAsJsonObject();if(!key.equals(an.get("location_id").getAsString()))continue;
    var p=an.getAsJsonObject("position");int bx=(int)Math.floor(p.get("x").getAsDouble()),by=(int)Math.floor(p.get("y").getAsDouble()),bz=(int)Math.floor(p.get("z").getAsDouble());
    boolean contained=geometry.stream().anyMatch(b->bx>=b.min().x()&&bx<=b.max().x()&&by>=b.min().y()&&by<=b.max().y()&&bz>=b.min().z()&&bz<=b.max().z());if(!contained)continue;
    var id=sid("anchor",an.get("anchor_id").getAsString());anchorIds.add(id);if(standing.contains(an.get("anchor_id").getAsString()))clearPositions.add(bx+"/"+by+"/"+bz);
    anchors.add(new Anchor(id,new Id("proutlost:observed_anchor"),DIM,new Vec3(p.get("x").getAsDouble(),p.get("y").getAsDouble(),p.get("z").getAsDouble()),null,1,tags(an.getAsJsonArray("tags"))));
   }
   var caps=new ArrayList<LocationCapability>();var tagset=tags(original.getAsJsonArray("tags"));
   if(clearPositions.size()>=2){var cap=new Id("proutlost:group_arrival");caps.add(new LocationCapability(cap,new fr.proutlost.contract.director.Revision("1"),new TypedValue(cap,new fr.proutlost.contract.director.Revision("1"),Map.of("capacity",new TypedValue.Number(clearPositions.size()))),anchorIds,Set.of(),List.of(evidence)));}
   if(tagset.contains(new Id("proutlost:signal_candidate"))&&!anchorIds.isEmpty()){
    var cap=new Id("proutlost:regional_signal");caps.add(new LocationCapability(cap,new fr.proutlost.contract.director.Revision("1"),new TypedValue(cap,new fr.proutlost.contract.director.Revision("1"),Map.of("signal_id",new TypedValue.Identifier(sid("signal",key)),"physical_source",new TypedValue.Bool(false))),anchorIds,Set.of(),List.of(evidence)));
   }
   var manifest=JsonParser.parseString(Files.readString(Path.of(a[4]))).getAsJsonObject();
   if(!survey.get("campaign_manifest_sha256").getAsString().equals(sha(Path.of(a[4]))))throw new IllegalStateException("Actual campaign installation differs");
   var authored=manifest.getAsJsonArray("sites").asList().stream().map(JsonElement::getAsJsonObject).filter(x->original.get("external_id").getAsString().endsWith("/"+x.get("id").getAsString())).findFirst();
   if(authored.isPresent()){
    String kind=authored.get().get("kind").getAsString(),siteid=authored.get().get("id").getAsString();
    var version=new fr.proutlost.contract.director.Revision("1");
    java.util.function.BiConsumer<String,Map<String,TypedValue.Scalar>> capability=(name,fields)->{var cap=new Id("proutlost:"+name);caps.add(new LocationCapability(cap,new fr.proutlost.contract.director.Revision("1"),new TypedValue(cap,version,fields),anchorIds,Set.of(),List.of(evidence)));};
    switch(kind){
     case "SAFE_SHORE" -> capability.accept("safe_shore",Map.of("capacity",new TypedValue.Number(Math.max(1,clearPositions.size()))));
     case "WRECK","OLD_CAMP" -> capability.accept("motif_trace",Map.of("motif_id",new TypedValue.Identifier(new Id("proutlost:questspec/early_motif"))));
     case "CAMP" -> {capability.accept("arrangement_recording",Map.of("slot_count",new TypedValue.Number(3)));capability.accept("routine_memory",Map.of("channels",new TypedValue.Names(List.of("SOUND"))));}
     case "PERCEPTION" -> capability.accept("split_perception_scene",Map.of("sound_count",new TypedValue.Number(3),"symbol_count",new TypedValue.Number(3),"movable_cue_positions",new TypedValue.Bool(true)));
     case "SIGNAL" -> capability.accept("signal_vantage",Map.of("direction_precision",new TypedValue.Text("APPROXIMATE"),"response_origin",new TypedValue.Text("ISLAND_INTERIOR")));
     case "SIGNAL_REGION" -> capability.accept("regional_signal",Map.of("signal_id",new TypedValue.Identifier(new Id("proutlost:questspec/"+siteid)),"physical_source",new TypedValue.Bool(false)));
     case "TRANSMITTER" -> {capability.accept("transmission_site",Map.of("modes",new TypedValue.Names(List.of("FILTER","AMPLIFY","MASTERED"))));capability.accept("temporary_perturbation",Map.of("channels",new TypedValue.Names(List.of("SOUND","TEMPORARY_CUE")),"fallback_required",new TypedValue.Bool(true)));}
     case "RETREAT" -> capability.accept("fallback_point",Map.of("capacity",new TypedValue.Number(Math.max(1,clearPositions.size()))));
    }
    var records=new ArrayList<Id>();for(var sv:manifest.getAsJsonArray("supports")){var sp=sv.getAsJsonObject();if(sp.get("site").getAsString().equals(siteid)&&sp.get("action").getAsString().startsWith("inspect "))records.add(new Id("proutlost:questspec/"+sp.get("action").getAsString().substring(8)));}
    if(!records.isEmpty())capability.accept("testimony_record",Map.of("record_ids",new TypedValue.Identifiers(records),"context_ids",new TypedValue.Identifiers(List.of(new Id("proutlost:questspec/"+siteid)))));
   }
   var rs=new HashSet<SemanticRegionId>();for(var id:original.getAsJsonArray("region_ids"))rs.add(new SemanticRegionId(sid("region",id.getAsString())));
   var location=sid("site",key);var fingerprint=new Digest(s.get("state_sha256").getAsString());statePins.put(location,fingerprint);
   var nativeEvidence=new EvidenceRef(sid("native_blocks",key),fingerprint,new Id("proutlost:native_loaded_block_state_v1"));
   boolean prepared=original.get("external_id").getAsString().startsWith("prepared-terrain/");
   var site=new PhysicalCatalogExporter.Site(location,REV,prepared?SourceType.worldprep_prepared_site:SourceType.worldprep_static_structure,prepared?null:sid("structure",key),sid("source_g0_v3",key),geometry,rs,anchors,List.of(),caps,List.of(),List.of(),List.of(nativeEvidence,evidence),List.of(disk),tagset,Set.of(Channel.SOUND,Channel.SYMBOL,Channel.TEXT_UI,Channel.TEMPORARY_CUE,Channel.NONPHYSICAL_APPARITION,Channel.LIGHT_AMBIENCE));
   sites.add(site);
  }
  var result=PhysicalCatalogExporter.export(world,build,REV,Instant.now(),regions,sites,site->{if(!site.physicalEvidence().stream().anyMatch(e->e.validator_id().equals(new Id("proutlost:native_loaded_block_state_v1"))&&e.digest().equals(statePins.get(site.location()))))throw new IllegalArgumentException("Missing actual native state witness");});
  if(result.json()==null)throw new IllegalStateException(result.reason());
  Files.createDirectories(out);Files.writeString(out.resolve("catalog.json"),result.json());
  var bundle=DirectorOperatorBundle.load(profile,BlobContent.hash(Files.readAllBytes(profile.resolve("manifest.json"))));
  // R55 source integration is immutable; retain its exact adapter descriptor bytes.
  Files.copy(Path.of(a[5]),out.resolve("adapters.json"),StandardCopyOption.REPLACE_EXISTING);
  Files.copy(surveyPath,out.resolve("native-source-survey.json"),StandardCopyOption.REPLACE_EXISTING);
  var definitions=new HashMap<DefinitionId,fr.proutlost.director.api.model.DefinitionRef>();bundle.catalog().events().forEach((id,d)->definitions.put(id,new fr.proutlost.director.api.model.DefinitionRef(d.id(),d.contentRevision(),BlobContent.hash(new ModelCodec<>(fr.proutlost.director.api.model.EventDefinition.class).encode(d)))));
  var descriptorModel=((JsonValue.ObjectValue)fr.proutlost.director.api.codec.CanonicalJsonV1.decode(Files.readAllBytes(out.resolve("adapters.json")))).values();
  fr.proutlost.integration.contra.D0PhysicalRequirements.decode(fr.proutlost.director.api.codec.CanonicalJsonV1.encode(descriptorModel.get("requirements")),definitions);
  fr.proutlost.integration.contra.D0NativeAdapters.compilerSemantics(Files.readAllBytes(out.resolve("adapters.json")));
  System.out.println("AUTHENTIC_PRODUCER_SEALED_D0 locations="+sites.size()+" world="+world+" build="+build+" status="+result.status()+" source="+sha(out.resolve("catalog.json")));
 }
}

