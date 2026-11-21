package fr.proutlost.worldprep.director;

import fr.proutlost.contract.director.*;
import fr.proutlost.contract.director.Vocabulary.SourceType;
import fr.proutlost.director.api.codec.CanonicalJsonV1;
import fr.proutlost.director.api.model.JsonValue;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/** A new explicitly scoped, geometry-only snapshot. Existing metadata identifies
 * the support to observe; only the supplied current physical+disk witness attests
 * its cells. Never invents a capability, action, anchor, channel, policy or V5 receipt. */
public final class ObservedPhysicalCatalogProducer {
    public static final int MAX_REQUEST_BYTES=32768,MAX_TARGETS=32,MAX_CELLS=32768;
    public enum Origin { OBSERVED_EXISTING_CELLS, VERIFIED_NATIVE_STRUCTURE_MODULE }
    public enum Scope { PHYSICAL_GEOMETRY_ONLY, SYNTHETIC_QA }
    public record Target(Id location,Id sourceRef,List<Bounds> geometry,Set<Id> tags,
                         Origin origin,String moduleDirectory,Digest moduleRecordSha256) {
        public Target {
            Objects.requireNonNull(location);Objects.requireNonNull(sourceRef);Objects.requireNonNull(origin);
            geometry=List.copyOf(geometry);tags=Set.copyOf(tags);
            require(!geometry.isEmpty()&&geometry.size()<=8&&tags.size()<=24,"survey target geometry/tag bound");
            for(var bounds:geometry)require(bounds.min().x()>=-29999984&&bounds.min().z()>=-29999984&&bounds.max().x()<=29999984&&bounds.max().z()<=29999984
                &&bounds.min().y()>=-2048&&bounds.max().y()<=2047,"survey coordinates exceed finite native bounds");
            if(origin==Origin.OBSERVED_EXISTING_CELLS)
                require(moduleDirectory==null&&moduleRecordSha256==null&&tags.isEmpty()
                    &&sourceRef.equals(new Id("proutlost:observed_existing_cells")),"observed cells cannot claim module/tag authority");
            else require(moduleDirectory!=null&&moduleRecordSha256!=null,"explicit native module provenance required");
        }
    }
    public record Request(UUID survey,Id sourceWorld,Id sourceBuild,Revision revision,long expectedSeed,
                          Digest candidateMarkerSha256,Scope scope,List<Target> targets) {
        public Request {
            Objects.requireNonNull(survey);Objects.requireNonNull(sourceWorld);Objects.requireNonNull(sourceBuild);
            Objects.requireNonNull(revision);Objects.requireNonNull(candidateMarkerSha256);Objects.requireNonNull(scope);
            targets=List.copyOf(targets);require(!targets.isEmpty()&&targets.size()<=MAX_TARGETS,"survey target count");
            var ids=new HashSet<Id>();long cells=0;
            for(var target:targets){
                require(ids.add(target.location()),"duplicate survey target");
                cells=Math.addExact(cells,volume(target.geometry()));
                require(cells<=MAX_CELLS,"survey complete cell bound; no truncation");
            }
        }
    }
    public record Witness(Id location,Digest loaded,Digest disk,long cells,int chunks,List<EvidenceRef> originEvidence) {
        public Witness {
            Objects.requireNonNull(location);Objects.requireNonNull(loaded);Objects.requireNonNull(disk);
            originEvidence=List.copyOf(originEvidence);
            require(loaded.equals(disk)&&cells>0&&cells<=MAX_CELLS&&chunks>0&&chunks<=96,"matching bounded loaded/disk survey witness required");
        }
    }
    public static long volume(List<Bounds> geometry){
        long cells=0;
        try{for(var b:geometry)cells=Math.addExact(cells,Math.multiplyExact(Math.multiplyExact((long)b.max().x()-b.min().x()+1,(long)b.max().y()-b.min().y()+1),(long)b.max().z()-b.min().z()+1));}
        catch(ArithmeticException overflow){throw new IllegalArgumentException("survey geometry overflow",overflow);}
        return cells;
    }
    public static Id nativeSupportId(UUID moduleLocation,List<Bounds> geometry){
        return new Id("proutlost:observed_support/"+moduleLocation+"/"+Digests.sha256(StrictCodec.encode(geometry)).value());
    }
    public static PhysicalCatalogExporter.Result export(Request request,Digest exactRequestSha,Instant capturedAt,List<Witness> witnesses){
        Objects.requireNonNull(exactRequestSha);Objects.requireNonNull(capturedAt);
        require(witnesses.size()==request.targets().size(),"complete survey witnesses required");
        var byId=new HashMap<Id,Witness>();
        for(var witness:witnesses)require(byId.putIfAbsent(witness.location(),witness)==null,"duplicate survey witness");
        var regions=new ArrayList<SemanticRegion>();var sites=new ArrayList<PhysicalCatalogExporter.Site>();
        for(var target:request.targets().stream().sorted(Comparator.comparing(Target::location)).toList()){
            var witness=Objects.requireNonNull(byId.get(target.location()),"survey target witness missing");
            require(witness.cells()==volume(target.geometry()),"survey witness geometry differs");
            require(target.origin()!=Origin.VERIFIED_NATIVE_STRUCTURE_MODULE||!witness.originEvidence().isEmpty(),"native module witness absent");
            var region=new SemanticRegionId(new Id("proutlost:observed_region/"+Digests.sha256(target.location().value()).value()));
            regions.add(new SemanticRegion(region,request.revision(),target.geometry(),Set.of(),0));
            String suffix=Digests.sha256(request.survey()+"/"+target.location()).value();
            var live=new ArrayList<EvidenceRef>(witness.originEvidence());
            live.add(new EvidenceRef(new Id("proutlost:survey_request/"+suffix),exactRequestSha,new Id("proutlost:explicit_geometry_survey_v1")));
            live.add(new EvidenceRef(new Id("proutlost:survey_loaded/"+suffix),witness.loaded(),new Id("proutlost:native_loaded_block_state_v1")));
            var disk=List.of(new EvidenceRef(new Id("proutlost:survey_anvil/"+suffix),witness.disk(),new Id("proutlost:anvil_readback_v1")));
            sites.add(new PhysicalCatalogExporter.Site(target.location(),request.revision(),SourceType.worldprep_prepared_site,null,target.sourceRef(),
                target.geometry(),Set.of(region),List.of(),List.of(),List.of(),List.of(),List.of(),live,disk,target.tags(),Set.of()));
        }
        // Exact witnesses are collected before construction/publication; a changed/extra
        // target cannot become an independently fabricated successful source revision.
        var accepted=Set.copyOf(sites);
        return PhysicalCatalogExporter.export(request.sourceWorld(),request.sourceBuild(),request.revision(),capturedAt,regions,sites,
            site->require(accepted.contains(site),"survey producer substituted a witnessed target"));
    }
    public static Request decode(byte[] bytes){
        require(bytes.length>0&&bytes.length<=MAX_REQUEST_BYTES,"survey request byte bound");
        var root=object(CanonicalJsonV1.decode(bytes));
        fields(root,Set.of("schema_version","survey_id","source_world","source_build","revision","expected_seed","candidate_marker_sha256","scope","targets"));
        require(number(root.get("schema_version"))==1,"survey schema version");
        String rawUuid=text(root.get("survey_id"));UUID survey=UUID.fromString(rawUuid);require(survey.toString().equals(rawUuid),"canonical survey UUID required");
        String seed=text(root.get("expected_seed"));require(seed.matches("0|-?[1-9][0-9]*"),"canonical survey seed required");
        var targets=new ArrayList<Target>();
        for(var value:array(root.get("targets"))){
            var row=object(value);fields(row,Set.of("location_id","source_ref","geometry","tags","origin","module_directory","module_record_sha256"));
            var geometry=array(row.get("geometry")).stream().map(v->StrictCodec.decode(new String(CanonicalJsonV1.encode(v),StandardCharsets.UTF_8),Bounds.class)).toList();
            var tags=new HashSet<Id>();for(var tag:array(row.get("tags")))require(tags.add(new Id(text(tag))),"duplicate survey tag");
            targets.add(new Target(new Id(text(row.get("location_id"))),new Id(text(row.get("source_ref"))),geometry,tags,Origin.valueOf(text(row.get("origin"))),
                row.get("module_directory")==JsonValue.Nil.INSTANCE?null:text(row.get("module_directory")),
                row.get("module_record_sha256")==JsonValue.Nil.INSTANCE?null:new Digest(text(row.get("module_record_sha256")))));
        }
        return new Request(survey,new Id(text(root.get("source_world"))),new Id(text(root.get("source_build"))),new Revision(text(root.get("revision"))),Long.parseLong(seed),
            new Digest(text(root.get("candidate_marker_sha256"))),Scope.valueOf(text(root.get("scope"))),targets);
    }
    private static Map<String,JsonValue> object(JsonValue value){if(value instanceof JsonValue.ObjectValue o)return o.values();throw new IllegalArgumentException("survey object required");}
    private static List<JsonValue> array(JsonValue value){if(value instanceof JsonValue.Array a)return a.values();throw new IllegalArgumentException("survey array required");}
    private static String text(JsonValue value){if(value instanceof JsonValue.Text t)return t.value();throw new IllegalArgumentException("survey string required");}
    private static int number(JsonValue value){if(value instanceof JsonValue.Number n)return n.value().intValueExact();throw new IllegalArgumentException("survey integer required");}
    private static void fields(Map<String,JsonValue> value,Set<String> expected){require(value.keySet().equals(expected),"unknown/missing survey fields");}
    private static void require(boolean condition,String reason){if(!condition)throw new IllegalArgumentException(reason);}
    private ObservedPhysicalCatalogProducer(){}
}
